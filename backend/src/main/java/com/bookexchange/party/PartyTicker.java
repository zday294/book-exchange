package com.bookexchange.party;

import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.bookexchange.live.PresenceChangedEvent;

/** Drives the party: the once-a-second timer, and late arrivals joining the queue. */
@Component
public class PartyTicker {

    private final PartyService party;

    public PartyTicker(PartyService party) {
        this.party = party;
    }

    @Scheduled(fixedDelayString = "${app.party.tick-millis:1000}")
    void tick() {
        for (String code : party.runningParties()) {
            try {
                party.tick(code);
            } catch (RuntimeException e) {
                party.logTickFailure(code, e); // one exchange's problem must not stop the others
            }
        }
    }

    /** Off the request thread: opening the live connection must not wait for this. */
    @Async
    @EventListener
    public void onPresence(PresenceChangedEvent event) {
        try {
            party.onPersonOnline(event.exchangeCode(), event.name());
        } catch (RuntimeException e) {
            party.logTickFailure(event.exchangeCode(), e);
        }
    }
}
