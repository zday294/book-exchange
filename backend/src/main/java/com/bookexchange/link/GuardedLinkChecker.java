package com.bookexchange.link;

import com.bookexchange.net.PinnedFetcher;
import com.bookexchange.validation.BookLinkProperties;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Asks the store whether a submitted link's page exists, treating the link as untrusted. All the fetching rules
 * live in {@link PinnedFetcher} (allowed hosts re-checked on every redirect, public addresses only, connecting to
 * the address that was checked, short time limit, no cookies or credentials, only the status and headers read).
 * Here: a HEAD request first, then a GET when HEAD is refused or says "not found" (so a store that answers HEAD
 * badly never makes a good link look dead), and what the answer means.
 *
 * <p>Only a definite 404 or 410 gives {@link LinkStatus#NOT_FOUND}; everything else that is not a success is
 * {@link LinkStatus#UNVERIFIED}, because stores often block or rate-limit programs.
 *
 * <p>Every answer is logged with the host and status, so a store that starts answering 404 to everyone shows up
 * as a spike of {@code NOT_FOUND} lines (see backend/README.md).
 */
@Component
public class GuardedLinkChecker implements LinkChecker {

    private static final Logger log = LoggerFactory.getLogger(GuardedLinkChecker.class);

    private final LinkCheckProperties props;
    private final BookLinkProperties bookLinks;
    private final PinnedFetcher fetcher;
    private final LinkResultCache cache;

    @Autowired
    public GuardedLinkChecker(LinkCheckProperties props, BookLinkProperties bookLinks) {
        this(props, bookLinks, PinnedFetcher.production(), System::nanoTime);
    }

    GuardedLinkChecker(LinkCheckProperties props, BookLinkProperties bookLinks, PinnedFetcher fetcher,
                       java.util.function.LongSupplier nanoClock) {
        this.props = props;
        this.bookLinks = bookLinks;
        this.fetcher = fetcher;
        this.cache = new LinkResultCache(props.cacheMinutes(), nanoClock);
    }

    @Override
    public LinkStatus check(URI link) {
        String key = link.toString();
        var remembered = cache.get(key);
        if (remembered.isPresent()) {
            log.info("Link check: host={} result={} (remembered)", link.getHost(), remembered.get());
            return remembered.get();
        }
        var spec = new PinnedFetcher.Spec(bookLinks.allowedHosts(), uri -> false, true, "*/*", 0, null,
                props.connectTimeoutMs(), props.totalTimeoutMs(), props.maxRedirects());
        LinkStatus result;
        String detail;
        switch (fetcher.fetch(link, spec)) {
            case PinnedFetcher.Reached reached -> {
                int status = reached.reply().status();
                result = status == 404 || status == 410 ? LinkStatus.NOT_FOUND
                        : status >= 200 && status < 300 ? LinkStatus.OK : LinkStatus.UNVERIFIED;
                detail = "status=" + status + " finalHost=" + reached.uri().getHost();
            }
            case PinnedFetcher.Stopped stopped -> {
                result = LinkStatus.UNVERIFIED;
                detail = "stopped";
            }
            case PinnedFetcher.Unreachable unreachable -> {
                result = LinkStatus.UNVERIFIED;
                detail = "unreachable (" + unreachable.reason() + ")";
            }
        }
        log.info("Link check: host={} result={} {}", link.getHost(), result, detail);
        cache.put(key, result);
        return result;
    }
}
