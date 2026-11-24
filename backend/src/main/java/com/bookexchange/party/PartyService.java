package com.bookexchange.party;

import com.bookexchange.domain.Exchange;
import com.bookexchange.domain.ExchangeMode;
import com.bookexchange.domain.ExchangeStatus;
import com.bookexchange.error.ApiException;
import com.bookexchange.exchange.ExchangeModeChangedEvent;
import com.bookexchange.exchange.HostTokens;
import com.bookexchange.live.ExchangeEventHub;
import com.bookexchange.party.PartyTurns.Turn;
import com.bookexchange.repository.BookSubmissionRepository;
import com.bookexchange.repository.ExchangeRepository;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The party: a host-run queue of turns, one person claiming at a time.
 *
 * <p>Every change to an exchange's party happens in a transaction that holds the exchange's row lock
 * ({@code FOR UPDATE}), the same lock that claiming and switching the mode take, so a claim, a skip, an
 * expiring turn and the host ending the party are always handled one after the other.
 */
@Service
public class PartyService {

    static final String NOT_ANYONES_TURN = "It's not anyone's turn right now";
    static final String ALL_CLAIMED = "All the presents have been claimed. Party over!";
    static final String NOBODY_PLAYING = "Nobody claimed a present for a whole round, so the party has ended. "
            + "The host can start it again.";
    static final String HOST_ENDED = "The host ended the party.";

    private static final Logger log = LoggerFactory.getLogger(PartyService.class);

    private final ExchangeRepository exchanges;
    private final BookSubmissionRepository books;
    private final PartyTurns turns;
    private final HostTokens hostTokens;
    private final ExchangeEventHub hub;
    private final ApplicationEventPublisher events;
    private final int turnSeconds;
    private final long graceSeconds;
    private final SecureRandom random = new SecureRandom();

    /** The last queue sent out per exchange (without the countdown), so the timer only publishes real changes. */
    private final Map<String, String> lastSent = new ConcurrentHashMap<>();

    public PartyService(ExchangeRepository exchanges, BookSubmissionRepository books, PartyTurns turns,
                        HostTokens hostTokens, ExchangeEventHub hub, ApplicationEventPublisher events,
                        @Value("${app.party.turn-seconds:120}") int turnSeconds,
                        @Value("${app.party.offline-grace-seconds:30}") long graceSeconds) {
        this.exchanges = exchanges;
        this.books = books;
        this.turns = turns;
        this.hostTokens = hostTokens;
        this.hub = hub;
        this.events = events;
        this.turnSeconds = turnSeconds;
        this.graceSeconds = graceSeconds;
    }

    // ---- what the host does ----

    /**
     * Starts a party: the exchange goes to party mode (from submit or claim mode) and everyone online who hasn't
     * claimed a book yet is put in the queue in a random order. The first person's turn starts at once.
     */
    @Transactional
    public PartyState start(String code, String hostToken) {
        Exchange exchange = lockedExchange(code);
        requireHost(exchange, hostToken);
        if (exchange.getStatus() != ExchangeStatus.OPEN) {
            throw ApiException.conflict("This exchange is closed");
        }
        if (exchange.getMode() == ExchangeMode.PARTY) {
            throw ApiException.conflict("A party is already running");
        }
        if (unclaimedBooks(exchange) == 0) {
            throw ApiException.conflict("There are no presents left to claim");
        }
        List<String> eligible = eligibleNames(exchange);
        if (eligible.isEmpty()) {
            throw ApiException.conflict("Nobody is online who can still claim a present. "
                    + "Ask everyone to open the exchange page, then try again.");
        }

        Collections.shuffle(eligible, random); // fair: the host doesn't choose the order
        turns.deleteAll(exchange.getId());
        turns.resetIdleTurns(exchange.getId());
        eligible.forEach(name -> turns.addToEnd(exchange.getId(), name));
        turns.promoteNext(exchange.getId(), turnSeconds);

        exchange.setMode(ExchangeMode.PARTY);
        exchanges.saveAndFlush(exchange);
        events.publishEvent(new ExchangeModeChangedEvent(exchange.getCode(), ExchangeMode.PARTY));
        return publish(exchange, null);
    }

    /** Skips the person whose turn it is (they go to the end of the line) and starts the next turn. */
    @Transactional
    public PartyState skip(String code, String hostToken) {
        Exchange exchange = lockedExchange(code);
        requireHost(exchange, hostToken);
        requireParty(exchange);
        if (turns.current(exchange.getId()).isEmpty()) {
            throw ApiException.conflict("Nobody has the turn right now");
        }
        return endTurnWithoutClaim(exchange);
    }

    /** Ends the party; the exchange goes to claim mode so the remaining presents can still be claimed. */
    @Transactional
    public PartyState end(String code, String hostToken) {
        Exchange exchange = lockedExchange(code);
        requireHost(exchange, hostToken);
        requireParty(exchange);
        return endParty(exchange, HOST_ENDED);
    }

    /** What everyone may see of the party right now. */
    @Transactional(readOnly = true)
    public PartyState state(String code) {
        Exchange exchange = exchanges.findByCode(code).orElseThrow(() -> ApiException.notFound("Exchange not found"));
        return state(exchange, null);
    }

    // ---- what claiming does ----

    /** Throws unless it is this person's turn, by the database's clock. Called before a claim in party mode. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void requireTurn(Exchange exchange, String name) {
        Optional<Turn> current = turns.current(exchange.getId());
        if (current.isEmpty()) {
            throw ApiException.conflict(NOT_ANYONES_TURN);
        }
        if (current.get().expired()) {
            throw ApiException.conflict("That turn has just ended. Wait for the next one.");
        }
        if (!current.get().name().equalsIgnoreCase(name.strip())) {
            throw ApiException.conflict("It's not your turn; " + current.get().name() + " is up");
        }
    }

    /**
     * The person whose turn it was has claimed a book (the claim itself is already done, in the same transaction).
     * Marks the turn done, and starts the next one, or ends the party when nothing is left to claim.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void completeTurn(Exchange exchange, String name) {
        if (turns.completeCurrent(exchange.getId(), name.strip()) != 1) {
            // Only possible if the turn ended between the check and now. The whole claim is rolled back.
            throw ApiException.conflict("That turn has just ended. Wait for the next one.");
        }
        turns.resetIdleTurns(exchange.getId());
        if (unclaimedBooks(exchange) == 0) {
            endParty(exchange, ALL_CLAIMED);
            return;
        }
        // Books remain. If nobody is waiting, the party stays open for late arrivals until the host ends it.
        turns.promoteNext(exchange.getId(), turnSeconds);
        publish(exchange, null);
    }

    // ---- what happens by itself ----

    /**
     * Run about once a second for each running party: ends a turn whose time ran out or whose person has gone
     * offline, gives the turn to someone when nobody has it, ends a party that has nothing left to do, and tells
     * everyone when who is online has changed.
     */
    @Transactional
    public void tick(String code) {
        Optional<Exchange> found = exchanges.findByCodeForUpdate(code);
        if (found.isEmpty() || found.get().getMode() != ExchangeMode.PARTY) {
            lastSent.remove(code);
            return;
        }
        Exchange exchange = found.get();
        if (unclaimedBooks(exchange) == 0) {
            endParty(exchange, ALL_CLAIMED);
            return;
        }
        Optional<Turn> current = turns.current(exchange.getId());
        if (current.isPresent()) {
            Turn turn = current.get();
            if (turn.expired() || !hub.isOnline(code, turn.name(), graceSeconds)) {
                endTurnWithoutClaim(exchange); // out of time, or gone: to the end of the line
                return;
            }
        } else if (turns.activeCount(exchange.getId()) > 0) {
            turns.promoteNext(exchange.getId(), turnSeconds);
            publish(exchange, null);
            return;
        }
        publishIfChanged(exchange);
    }

    /** Someone with a name has just come online. In a running party they join the end of the queue. */
    @Transactional
    public void onPersonOnline(String code, String name) {
        Optional<Exchange> found = exchanges.findByCodeForUpdate(code);
        if (found.isEmpty() || found.get().getMode() != ExchangeMode.PARTY) {
            return;
        }
        Exchange exchange = found.get();
        boolean alreadyClaimed = books.existsByExchangeIdAndClaimedByNameIgnoreCase(exchange.getId(), name);
        if (alreadyClaimed || turns.contains(exchange.getId(), name)) {
            publishIfChanged(exchange); // (they may have been offline in the list)
            return;
        }
        turns.addToEnd(exchange.getId(), name);
        if (turns.current(exchange.getId()).isEmpty()) {
            turns.promoteNext(exchange.getId(), turnSeconds); // the party was waiting for someone
        }
        publish(exchange, null);
    }

    // ---- inside the lock ----

    /** The current turn passed without a claim (time ran out, they went offline, or the host skipped them). */
    private PartyState endTurnWithoutClaim(Exchange exchange) {
        long id = exchange.getId();
        turns.moveCurrentToEnd(id);
        int idle = turns.addIdleTurn(id);
        int people = turns.activeCount(id);
        if (people == 0 || idle >= people) {
            return endParty(exchange, NOBODY_PLAYING); // a whole round with no claim at all
        }
        turns.promoteNext(id, turnSeconds);
        return publish(exchange, null);
    }

    private PartyState endParty(Exchange exchange, String reason) {
        turns.deleteAll(exchange.getId());
        turns.resetIdleTurns(exchange.getId());
        exchange.setMode(ExchangeMode.CLAIM);
        exchanges.saveAndFlush(exchange);
        lastSent.remove(exchange.getCode());
        events.publishEvent(new ExchangeModeChangedEvent(exchange.getCode(), ExchangeMode.CLAIM));
        PartyState ended = PartyState.inactive(reason, eligibleNames(exchange));
        events.publishEvent(new PartyChangedEvent(exchange.getCode(), ended));
        return ended;
    }

    private PartyState publish(Exchange exchange, String endedReason) {
        PartyState state = state(exchange, endedReason);
        lastSent.put(exchange.getCode(), fingerprint(state));
        events.publishEvent(new PartyChangedEvent(exchange.getCode(), state));
        return state;
    }

    private void publishIfChanged(Exchange exchange) {
        PartyState state = state(exchange, null);
        String fingerprint = fingerprint(state);
        if (!fingerprint.equals(lastSent.put(exchange.getCode(), fingerprint))) {
            events.publishEvent(new PartyChangedEvent(exchange.getCode(), state));
        }
    }

    /** The queue without the countdown, which changes every second. */
    private static String fingerprint(PartyState state) {
        return state.active() + "|" + state.current() + "|" + state.queue();
    }

    private PartyState state(Exchange exchange, String endedReason) {
        if (exchange.getMode() != ExchangeMode.PARTY) {
            return PartyState.inactive(endedReason, eligibleNames(exchange));
        }
        List<Turn> queue = turns.list(exchange.getId());
        Turn current = queue.stream().filter(t -> PartyTurns.CURRENT.equals(t.status())).findFirst().orElse(null);
        List<PartyState.Entry> entries = new ArrayList<>();
        for (Turn turn : queue) {
            entries.add(new PartyState.Entry(turn.name(), turn.status(),
                    hub.isOnline(exchange.getCode(), turn.name(), graceSeconds)));
        }
        return new PartyState(true, endedReason, current == null ? null : current.name(),
                current == null ? null : current.secondsLeft(), entries, List.of());
    }

    // ---- helpers ----

    private Exchange lockedExchange(String code) {
        return exchanges.findByCodeForUpdate(code).orElseThrow(() -> ApiException.notFound("Exchange not found"));
    }

    private void requireHost(Exchange exchange, String hostToken) {
        if (!hostTokens.matches(hostToken, exchange.getHostTokenHash())) {
            throw ApiException.forbidden("Only the host of this exchange can change it");
        }
    }

    private void requireParty(Exchange exchange) {
        if (exchange.getMode() != ExchangeMode.PARTY) {
            throw ApiException.conflict("There is no party running");
        }
    }

    private long unclaimedBooks(Exchange exchange) {
        return books.countByExchangeId(exchange.getId()) - books.countByExchangeIdAndClaimedByNameIsNotNull(exchange.getId());
    }

    /** People online now who haven't claimed a book yet: who would be put in the queue. */
    private List<String> eligibleNames(Exchange exchange) {
        List<String> names = new ArrayList<>();
        for (String name : hub.onlineNames(exchange.getCode(), graceSeconds)) {
            if (!books.existsByExchangeIdAndClaimedByNameIgnoreCase(exchange.getId(), name)) {
                names.add(name);
            }
        }
        return names;
    }

    /** Called by the scheduler: the codes of the exchanges that have a party running. */
    public List<String> runningParties() {
        return exchanges.findCodesByMode(ExchangeMode.PARTY);
    }

    void logTickFailure(String code, RuntimeException e) {
        log.warn("Party tick failed for exchange {}", code, e);
    }
}
