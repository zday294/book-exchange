package com.bookexchange.cover;

import com.bookexchange.net.PinnedFetcher;
import com.bookexchange.net.SocketTransport;
import com.bookexchange.validation.BookLinkProperties;
import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The cover lookup's way onto the network. The fetching rules are {@link PinnedFetcher}'s (allowed hosts re-checked
 * on every redirect, https on the standard port only, public addresses only and a connection to the address that
 * was checked, redirects followed by hand and capped, one overall time limit, a size cap). Here: which hosts, what
 * counts as an answer (a short link must lead to a URL with an ASIN; an image must be a small genuine
 * {@code image/*}).
 */
@Component
public class GuardedCoverHttp implements CoverHttp {

    private final CoverProperties props;
    private final List<String> shortLinkHosts;
    private final PinnedFetcher fetcher;

    @Autowired
    public GuardedCoverHttp(CoverProperties props, BookLinkProperties bookLinks) {
        this(props, bookLinks, PinnedFetcher.production());
    }

    GuardedCoverHttp(CoverProperties props, BookLinkProperties bookLinks, PinnedFetcher fetcher) {
        this.props = props;
        this.shortLinkHosts = bookLinks.allowedHosts();
        this.fetcher = fetcher;
    }

    @Override
    public Optional<URI> resolveShortLink(URI shortLink) {
        var spec = new PinnedFetcher.Spec(shortLinkHosts, uri -> AsinParser.parse(uri).isPresent(), false,
                "text/html,*/*", 0, null, props.connectTimeoutMs(), props.totalTimeoutMs(), props.maxRedirects());
        // The walk stops as soon as a URL contains an ASIN, without ever downloading the product page.
        return switch (fetcher.fetch(shortLink, spec)) {
            case PinnedFetcher.Stopped stopped -> Optional.of(stopped.uri());
            default -> Optional.empty();
        };
    }

    @Override
    public Optional<byte[]> fetchImage(URI imageUrl) {
        var spec = new PinnedFetcher.Spec(props.allowedImageHosts(), uri -> false, false, "image/*",
                props.maxImageBytes(), "image/", props.connectTimeoutMs(), props.totalTimeoutMs(), props.maxRedirects());
        return switch (fetcher.fetch(imageUrl, spec)) {
            case PinnedFetcher.Reached reached when reached.reply().status() == 200
                    && reached.reply().body() != null && reached.reply().body().length > 0 ->
                    Optional.of(reached.reply().body());
            default -> Optional.empty();
        };
    }
}
