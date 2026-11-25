package com.bookexchange.link;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * A small in-memory memory of recent definite answers, so fixing a typo and resubmitting (or two people bringing
 * the same book) doesn't open the same page again. One backend instance only, like the rest of the live state.
 * Only {@link LinkStatus#OK} and {@link LinkStatus#NOT_FOUND} are kept: "couldn't tell" is usually a temporary
 * block that a retry may get past.
 */
final class LinkResultCache {

    static final int MAX_ENTRIES = 500;

    private record Entry(LinkStatus status, long expiresAtNanos) {
    }

    private final long ttlNanos;
    private final LongSupplier clock;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    LinkResultCache(int minutes, LongSupplier nanoClock) {
        this.ttlNanos = TimeUnit.MINUTES.toNanos(minutes);
        this.clock = nanoClock;
    }

    boolean enabled() {
        return ttlNanos > 0;
    }

    Optional<LinkStatus> get(String link) {
        if (!enabled()) {
            return Optional.empty();
        }
        Entry entry = entries.get(link);
        if (entry == null) {
            return Optional.empty();
        }
        if (clock.getAsLong() - entry.expiresAtNanos() >= 0) {
            entries.remove(link, entry);
            return Optional.empty();
        }
        return Optional.of(entry.status());
    }

    void put(String link, LinkStatus status) {
        if (!enabled() || status == LinkStatus.UNVERIFIED) {
            return;
        }
        if (entries.size() >= MAX_ENTRIES) {
            long now = clock.getAsLong();
            entries.values().removeIf(e -> now - e.expiresAtNanos() >= 0);
            if (entries.size() >= MAX_ENTRIES) {
                entries.clear(); // simplest safe answer to a flood of different links
            }
        }
        entries.put(link, new Entry(status, clock.getAsLong() + ttlNanos));
    }
}
