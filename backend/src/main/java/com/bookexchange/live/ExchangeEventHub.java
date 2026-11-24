package com.bookexchange.live;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.bookexchange.error.ApiException;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Holds the open live-update connections (Server-Sent Events) of each exchange and sends them events.
 *
 * <p>Everything sent here goes to everyone watching that exchange, so callers must only ever pass public
 * data (never a book link, never anything about an unclaimed book beyond who brought it).
 *
 * <p>A connection may carry the viewer's name. That is how the party (#43) knows who is online: a name with at
 * least one open connection is online, and stays online for a short grace period after the last one closes, so
 * a page refresh or a moment without signal doesn't count as leaving.
 *
 * <p>The connections live in this process, so live updates work with a single backend instance (see the
 * README). One connection per open browser page is fine for a group-sized exchange; a cap protects the server.
 */
@Component
public class ExchangeEventHub implements ApplicationListener<ContextClosedEvent> {

    private static final Logger log = LoggerFactory.getLogger(ExchangeEventHub.class);

    /** Who is behind one name in one exchange: how many connections they have open, and since when none. */
    private static final class Presence {
        final String displayName;
        int connections;
        Instant offlineSince;

        Presence(String displayName) {
            this.displayName = displayName;
        }
    }

    private static final int MAX_NAME_LENGTH = 100;

    private final Map<String, Set<SseEmitter>> byExchange = new ConcurrentHashMap<>();
    private final Map<SseEmitter, String> nameOf = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Presence>> presence = new ConcurrentHashMap<>(); // code -> lower-cased name -> presence
    private final AtomicInteger total = new AtomicInteger();
    private final ObjectMapper json;
    private final ApplicationEventPublisher events;
    private final int maxSubscribers;
    private final long timeoutMillis;

    public ExchangeEventHub(ObjectMapper json, ApplicationEventPublisher events,
                            @Value("${app.events.max-subscribers:1000}") int maxSubscribers,
                            @Value("${app.events.connection-minutes:30}") long connectionMinutes) {
        this.json = json;
        this.events = events;
        this.maxSubscribers = maxSubscribers;
        this.timeoutMillis = Duration.ofMinutes(connectionMinutes).toMillis();
    }

    /**
     * Opens a stream for one exchange. The first thing sent is a {@code ready} event, which makes the
     * browser's "connected" callback fire at once. Connections are recycled after a while; the browser
     * reconnects by itself (and re-reads the pile, so nothing is missed).
     */
    public SseEmitter subscribe(String exchangeCode) {
        return subscribe(exchangeCode, null);
    }

    /** Same, for a viewer who says who they are (their name decides whether they count as online). */
    public SseEmitter subscribe(String exchangeCode, String name) {
        if (total.incrementAndGet() > maxSubscribers) {
            total.decrementAndGet();
            throw ApiException.unavailable("Too many live connections right now. Please try again in a moment.");
        }
        SseEmitter emitter = new SseEmitter(timeoutMillis);
        Set<SseEmitter> group = byExchange.computeIfAbsent(exchangeCode, c -> ConcurrentHashMap.newKeySet());
        group.add(emitter);
        String cleanName = cleanName(name);
        if (cleanName != null) {
            nameOf.put(emitter, cleanName);
            connected(exchangeCode, cleanName);
        }
        Runnable forget = () -> remove(exchangeCode, emitter);
        emitter.onCompletion(forget);
        emitter.onTimeout(forget);
        emitter.onError(e -> forget.run());
        try {
            emitter.send(SseEmitter.event().name("ready").data("{}"));
        } catch (IOException | IllegalStateException e) {
            forget.run();
        }
        return emitter;
    }

    /** Sends one event to everyone watching the exchange. A connection that has gone away is dropped. */
    public void publish(String exchangeCode, String eventName, Object data) {
        Set<SseEmitter> group = byExchange.get(exchangeCode);
        if (group == null || group.isEmpty()) {
            return;
        }
        String payload;
        try {
            payload = json.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            log.error("Could not write the {} event", eventName, e);
            return;
        }
        for (SseEmitter emitter : group) {
            send(exchangeCode, emitter, SseEmitter.event().name(eventName).data(payload));
        }
    }

    /**
     * A comment line now and then keeps proxies and load balancers from closing an idle connection, and
     * is how a connection whose browser has gone away is noticed and cleaned up.
     */
    @Scheduled(fixedDelayString = "${app.events.heartbeat-millis:15000}")
    void heartbeat() {
        forgetLongGone();
        byExchange.forEach((code, group) -> {
            for (SseEmitter emitter : group) {
                send(code, emitter, SseEmitter.event().comment("keep-alive"));
            }
        });
    }

    private void send(String exchangeCode, SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        try {
            emitter.send(event);
        } catch (IOException | IllegalStateException e) {
            remove(exchangeCode, emitter); // the browser left (or the connection is already finished)
        }
    }

    private void remove(String exchangeCode, SseEmitter emitter) {
        Set<SseEmitter> group = byExchange.get(exchangeCode);
        if (group != null && group.remove(emitter)) {
            total.decrementAndGet();
            String name = nameOf.remove(emitter);
            if (name != null) {
                disconnected(exchangeCode, name);
            }
            if (group.isEmpty()) {
                byExchange.remove(exchangeCode, group);
            }
        }
    }

    // ---- who is online ----

    private static String cleanName(String name) {
        if (name == null) return null;
        String clean = name.strip();
        return clean.isEmpty() || clean.length() > MAX_NAME_LENGTH || clean.chars().anyMatch(Character::isISOControl)
                ? null : clean;
    }

    private void connected(String exchangeCode, String name) {
        Presence entry;
        boolean cameOnline;
        synchronized (presence) {
            entry = presence.computeIfAbsent(exchangeCode, c -> new ConcurrentHashMap<>())
                    .computeIfAbsent(name.toLowerCase(java.util.Locale.ROOT), k -> new Presence(name));
            cameOnline = entry.connections == 0;
            entry.connections++;
            entry.offlineSince = null;
        }
        if (cameOnline) {
            events.publishEvent(new PresenceChangedEvent(exchangeCode, name));
        }
    }

    private void disconnected(String exchangeCode, String name) {
        synchronized (presence) {
            Map<String, Presence> group = presence.get(exchangeCode);
            Presence entry = group == null ? null : group.get(name.toLowerCase(java.util.Locale.ROOT));
            if (entry != null && entry.connections > 0 && --entry.connections == 0) {
                entry.offlineSince = Instant.now();
            }
        }
    }

    /** Online means a connection is open, or the last one closed less than {@code graceSeconds} ago. */
    public boolean isOnline(String exchangeCode, String name, long graceSeconds) {
        synchronized (presence) {
            Map<String, Presence> group = presence.get(exchangeCode);
            Presence entry = group == null ? null : group.get(name.strip().toLowerCase(java.util.Locale.ROOT));
            if (entry == null) return false;
            return entry.connections > 0
                    || (entry.offlineSince != null && Duration.between(entry.offlineSince, Instant.now()).getSeconds() < graceSeconds);
        }
    }

    /** The names (as people typed them) that are online in the exchange right now. */
    public List<String> onlineNames(String exchangeCode, long graceSeconds) {
        List<String> names = new ArrayList<>();
        synchronized (presence) {
            Map<String, Presence> group = presence.get(exchangeCode);
            if (group != null) {
                group.values().forEach(p -> {
                    if (p.connections > 0
                            || (p.offlineSince != null && Duration.between(p.offlineSince, Instant.now()).getSeconds() < graceSeconds)) {
                        names.add(p.displayName);
                    }
                });
            }
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    /** Forgets people who have been gone for a long time (so the table can't grow without end). */
    private void forgetLongGone() {
        Instant cutoff = Instant.now().minus(Duration.ofHours(1));
        synchronized (presence) {
            presence.values().forEach(group -> group.values().removeIf(p -> p.connections == 0 && p.offlineSince != null
                    && p.offlineSince.isBefore(cutoff)));
            presence.values().removeIf(Map::isEmpty);
        }
    }

    /** How many connections an exchange has open (for tests and monitoring). */
    public int subscriberCount(String exchangeCode) {
        Set<SseEmitter> group = byExchange.get(exchangeCode);
        return group == null ? 0 : group.size();
    }

    /** How many connections are open in total (for tests and monitoring). */
    public int totalSubscribers() {
        return total.get();
    }

    /**
     * Ends every stream as soon as the application starts shutting down. The web server's graceful shutdown
     * waits for in-flight requests, and an open event stream never finishes by itself, so without this a deploy
     * would wait for its full timeout. Browsers reconnect to the new instance on their own.
     */
    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        closeAll();
    }

    void closeAll() {
        byExchange.values().forEach(group -> group.forEach(emitter -> {
            try {
                emitter.complete();
            } catch (RuntimeException ignored) {
                // already finished
            }
        }));
        byExchange.clear();
        nameOf.clear();
        total.set(0);
    }
}
