package com.bookexchange.cover;

import com.bookexchange.validation.BookLinkProperties;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The server fetches addresses that people typed in, so every request goes through these rules:
 * only allow-listed hosts (re-checked on every redirect), https on the standard port only, no host
 * that resolves to a private, loopback or link-local address, redirects followed by hand and capped,
 * a short overall time limit, a size cap, and only image content types accepted.
 */
@Component
public class GuardedCoverHttp implements CoverHttp {

    private static final Logger log = LoggerFactory.getLogger(GuardedCoverHttp.class);
    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "cover-http-watchdog");
        t.setDaemon(true);
        return t;
    });

    /** What counts as an acceptable destination. Production: https only, public addresses only. */
    record Policy(boolean httpsOnly, Predicate<InetAddress> addressAllowed) {
        static final Policy PRODUCTION = new Policy(true, GuardedCoverHttp::isPublic);
    }

    private final CoverProperties props;
    private final List<String> shortLinkHosts;
    private final Policy policy;
    private final HttpClient client;

    @Autowired
    public GuardedCoverHttp(CoverProperties props, BookLinkProperties bookLinks) {
        this(props, bookLinks, Policy.PRODUCTION);
    }

    GuardedCoverHttp(CoverProperties props, BookLinkProperties bookLinks, Policy policy) {
        this.props = props;
        this.shortLinkHosts = bookLinks.allowedHosts();
        this.policy = policy;
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER) // redirects are followed by hand, with checks
                .connectTimeout(Duration.ofMillis(props.connectTimeoutMs()))
                .build();
    }

    @Override
    public Optional<URI> resolveShortLink(URI shortLink) {
        return follow(shortLink, shortLinkHosts, uri -> AsinParser.parse(uri).isPresent(), 0)
                .filter(r -> r.uri() != null && AsinParser.parse(r.uri()).isPresent())
                .map(Result::uri);
    }

    @Override
    public Optional<byte[]> fetchImage(URI imageUrl) {
        return follow(imageUrl, props.allowedImageHosts(), uri -> false, props.maxImageBytes())
                .filter(r -> r.body() != null && r.body().length > 0)
                .map(Result::body);
    }

    /** The URL a walk ended on, plus the body when one was asked for (maxBytes > 0). */
    private record Result(URI uri, byte[] body) {
    }

    private Optional<Result> follow(URI start, List<String> allowedHosts, Predicate<URI> stopHere, int maxBytes) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(props.totalTimeoutMs());
        URI current = start;
        try {
            for (int hop = 0; hop <= props.maxRedirects(); hop++) {
                if (!acceptable(current, allowedHosts)) {
                    log.info("Cover lookup refused to fetch {}", current.getHost());
                    return Optional.empty();
                }
                if (stopHere.test(current)) {
                    return Optional.of(new Result(current, null));
                }
                long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (remainingMs <= 0) {
                    return Optional.empty();
                }
                HttpRequest request = HttpRequest.newBuilder(current)
                        .timeout(Duration.ofMillis(remainingMs))
                        .header("Accept", maxBytes > 0 ? "image/*" : "text/html,*/*")
                        .header("User-Agent", "book-exchange-cover-lookup")
                        .GET()
                        .build();
                HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                try (InputStream in = response.body()) {
                    int status = response.statusCode();
                    if (status >= 300 && status < 400) {
                        Optional<String> location = response.headers().firstValue("Location");
                        if (location.isEmpty()) {
                            return Optional.empty();
                        }
                        current = current.resolve(location.get());
                        continue;
                    }
                    if (status != 200) {
                        return Optional.empty();
                    }
                    if (maxBytes <= 0) {
                        return Optional.of(new Result(current, null));
                    }
                    String type = response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
                    if (!type.startsWith("image/")) {
                        return Optional.empty();
                    }
                    // Closing the stream is what stops a download that stalls, so do it when time is up.
                    var timer = WATCHDOG.schedule(() -> closeQuietly(in),
                            Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())), TimeUnit.MILLISECONDS);
                    try {
                        return readLimited(in, maxBytes).map(bytes -> new Result(null, bytes));
                    } finally {
                        timer.cancel(false);
                    }
                }
            }
            return Optional.empty(); // too many redirects
        } catch (IOException | IllegalArgumentException e) {
            log.info("Cover lookup could not fetch {}: {}", current.getHost(), e.toString());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    private boolean acceptable(URI uri, List<String> allowedHosts) {
        String scheme = uri.getScheme();
        boolean schemeOk = "https".equalsIgnoreCase(scheme) || (!policy.httpsOnly() && "http".equalsIgnoreCase(scheme));
        if (!schemeOk || uri.getUserInfo() != null || uri.getHost() == null) {
            return false;
        }
        if (policy.httpsOnly() && uri.getPort() != -1 && uri.getPort() != 443) {
            return false;
        }
        if (!HostMatcher.matches(uri.getHost(), allowedHosts)) {
            return false;
        }
        try {
            InetAddress[] addresses = InetAddress.getAllByName(uri.getHost());
            for (InetAddress address : addresses) {
                if (!policy.addressAllowed().test(address)) {
                    return false;
                }
            }
            return addresses.length > 0;
        } catch (UnknownHostException e) {
            return false;
        }
    }

    private static Optional<byte[]> readLimited(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            if (out.size() + read > maxBytes) {
                return Optional.empty(); // too big
            }
            out.write(buffer, 0, read);
        }
        return Optional.of(out.toByteArray());
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException ignored) {
            // nothing to do
        }
    }

    /** True for ordinary internet addresses; false for anything internal (loopback, private, link-local, etc.). */
    public static boolean isPublic(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress()
                || a.isSiteLocalAddress() || a.isMulticastAddress()) {
            return false;
        }
        byte[] b = a.getAddress();
        if (b.length == 4) {
            int first = b[0] & 0xff;
            int second = b[1] & 0xff;
            if (first == 0) {
                return false; // "this network"
            }
            return !(first == 100 && second >= 64 && second <= 127); // carrier-grade NAT
        }
        return (b[0] & 0xfe) != 0xfc; // IPv6 unique local (fc00::/7)
    }
}
