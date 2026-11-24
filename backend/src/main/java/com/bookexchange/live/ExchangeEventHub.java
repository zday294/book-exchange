package com.bookexchange.live;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.bookexchange.error.ApiException;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
 * <p>The connections live in this process, so live updates work with a single backend instance (see the
 * README). One connection per open browser page is fine for a group-sized exchange; a cap protects the server.
 */
@Component
public class ExchangeEventHub implements ApplicationListener<ContextClosedEvent> {

    private static final Logger log = LoggerFactory.getLogger(ExchangeEventHub.class);

    private final Map<String, Set<SseEmitter>> byExchange = new ConcurrentHashMap<>();
    private final AtomicInteger total = new AtomicInteger();
    private final ObjectMapper json;
    private final int maxSubscribers;
    private final long timeoutMillis;

    public ExchangeEventHub(ObjectMapper json,
                            @Value("${app.events.max-subscribers:1000}") int maxSubscribers,
                            @Value("${app.events.connection-minutes:30}") long connectionMinutes) {
        this.json = json;
        this.maxSubscribers = maxSubscribers;
        this.timeoutMillis = Duration.ofMinutes(connectionMinutes).toMillis();
    }

    /**
     * Opens a stream for one exchange. The first thing sent is a {@code ready} event, which makes the
     * browser's "connected" callback fire at once. Connections are recycled after a while; the browser
     * reconnects by itself (and re-reads the pile, so nothing is missed).
     */
    public SseEmitter subscribe(String exchangeCode) {
        if (total.incrementAndGet() > maxSubscribers) {
            total.decrementAndGet();
            throw ApiException.unavailable("Too many live connections right now. Please try again in a moment.");
        }
        SseEmitter emitter = new SseEmitter(timeoutMillis);
        Set<SseEmitter> group = byExchange.computeIfAbsent(exchangeCode, c -> ConcurrentHashMap.newKeySet());
        group.add(emitter);
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
            if (group.isEmpty()) {
                byExchange.remove(exchangeCode, group);
            }
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
        total.set(0);
    }
}
