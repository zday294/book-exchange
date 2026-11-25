package com.bookexchange.cover;

import static org.assertj.core.api.Assertions.assertThat;

import com.bookexchange.net.PinnedFetcher;
import com.bookexchange.net.SocketTransport;
import com.bookexchange.validation.BookLinkProperties;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises the real HTTP code against local servers. The test policy allows plain http and loopback
 * addresses (a real server can't be reached any other way); the production policy is tested separately.
 */
class GuardedCoverHttpTest {

    private static final byte[] IMAGE = new byte[] {1, 2, 3, 4, 5, 6, 7, 8};

    private HttpServer server;     // plays the allow-listed host
    private HttpServer forbidden;  // plays a host that is NOT allow-listed (reached only by 127.0.0.1)
    private final AtomicInteger forbiddenHits = new AtomicInteger();

    @BeforeEach
    void start() throws IOException {
        forbidden = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        forbidden.createContext("/", ex -> {
            forbiddenHits.incrementAndGet();
            ex.getResponseHeaders().add("Content-Type", "image/jpeg");
            ex.sendResponseHeaders(200, IMAGE.length);
            ex.getResponseBody().write(IMAGE);
            ex.close();
        });
        forbidden.start();

        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/image", ex -> {
            ex.getResponseHeaders().add("Content-Type", "image/jpeg");
            ex.sendResponseHeaders(200, IMAGE.length);
            ex.getResponseBody().write(IMAGE);
            ex.close();
        });
        server.createContext("/text", ex -> {
            ex.getResponseHeaders().add("Content-Type", "text/html");
            ex.sendResponseHeaders(200, 4);
            ex.getResponseBody().write("<p/>".getBytes());
            ex.close();
        });
        server.createContext("/missing", ex -> {
            ex.sendResponseHeaders(404, -1);
            ex.close();
        });
        server.createContext("/big", ex -> {
            ex.getResponseHeaders().add("Content-Type", "image/jpeg");
            ex.sendResponseHeaders(200, 100_000);
            ex.getResponseBody().write(new byte[100_000]);
            ex.close();
        });
        server.createContext("/slow", ex -> {
            ex.getResponseHeaders().add("Content-Type", "image/jpeg");
            ex.sendResponseHeaders(200, 1000);
            ex.getResponseBody().write(new byte[10]);
            ex.getResponseBody().flush();
            try {
                Thread.sleep(5000); // never finishes within the limit
            } catch (InterruptedException ignored) {
                // the client gave up
            }
            ex.close();
        });
        server.createContext("/to-image", ex -> redirect(ex, base() + "/image"));
        server.createContext("/loop", ex -> redirect(ex, base() + "/loop"));
        server.createContext("/to-forbidden", ex -> redirect(ex, "http://127.0.0.1:" + forbidden.getAddress().getPort() + "/img"));
        server.createContext("/short", ex -> redirect(ex, "https://www.amazon.com/dp/054792822X?ref=x"));
        server.createContext("/short-twice", ex -> redirect(ex, base() + "/short"));
        server.createContext("/short-nowhere", ex -> redirect(ex, base() + "/missing"));
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
        forbidden.stop(0);
    }

    private String base() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    private static void redirect(com.sun.net.httpserver.HttpExchange ex, String to) throws IOException {
        ex.getResponseHeaders().add("Location", to);
        ex.sendResponseHeaders(302, -1);
        ex.close();
    }

    private GuardedCoverHttp http(int totalMs, int maxBytes, int redirects) {
        CoverProperties props = new CoverProperties(true, "unused-%s", List.of("localhost"), 1000, totalMs, maxBytes, redirects);
        // Short links: "localhost" is the Amazon short-link host in this test; the redirect target is amazon.com
        // and is never fetched because the walk stops as soon as a URL contains an ASIN.
        BookLinkProperties amazon = new BookLinkProperties(List.of("localhost", "amazon.com"));
        // Test policy: plain http and loopback are allowed (a local server can't be reached any other way), and
        // "localhost" is pinned to the loopback address the servers listen on.
        var fetcher = new PinnedFetcher(new PinnedFetcher.Policy(false, a -> true), new SocketTransport(),
                host -> host.equals("localhost") ? new InetAddress[] {InetAddress.getLoopbackAddress()} : InetAddress.getAllByName(host));
        return new GuardedCoverHttp(props, amazon, fetcher);
    }

    @Test
    void downloadsAnImage() {
        assertThat(http(3000, 1000, 3).fetchImage(URI.create(base() + "/image"))).contains(IMAGE);
    }

    @Test
    void followsARedirectWithinTheAllowList() {
        assertThat(http(3000, 1000, 3).fetchImage(URI.create(base() + "/to-image"))).contains(IMAGE);
    }

    @Test
    void refusesARedirectToAHostThatIsNotAllowListed() {
        assertThat(http(3000, 1000, 3).fetchImage(URI.create(base() + "/to-forbidden"))).isEmpty();
        assertThat(forbiddenHits).hasValue(0); // the other host was never contacted
    }

    @Test
    void stopsAfterTooManyRedirects() {
        assertThat(http(3000, 1000, 3).fetchImage(URI.create(base() + "/loop"))).isEmpty();
    }

    @Test
    void rejectsNonImagesNotFoundAndOversizedDownloads() {
        assertThat(http(3000, 1000, 3).fetchImage(URI.create(base() + "/text"))).isEmpty();
        assertThat(http(3000, 1000, 3).fetchImage(URI.create(base() + "/missing"))).isEmpty();
        assertThat(http(3000, 1000, 3).fetchImage(URI.create(base() + "/big"))).isEmpty();
    }

    @Test
    void givesUpOnAStalledDownloadWithinTheTimeLimit() {
        long start = System.nanoTime();
        assertThat(http(800, 100_000, 3).fetchImage(URI.create(base() + "/slow"))).isEmpty();
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(3000);
    }

    @Test
    void resolvesAShortLinkToTheFirstUrlThatHasAnAsinWithoutFetchingTheProductPage() {
        assertThat(http(3000, 1000, 3).resolveShortLink(URI.create(base() + "/short")))
                .contains(URI.create("https://www.amazon.com/dp/054792822X?ref=x"));
        assertThat(http(3000, 1000, 3).resolveShortLink(URI.create(base() + "/short-twice")))
                .contains(URI.create("https://www.amazon.com/dp/054792822X?ref=x"));
    }

    @Test
    void shortLinkThatLeadsNowhereResolvesToNothing() {
        assertThat(http(3000, 1000, 3).resolveShortLink(URI.create(base() + "/short-nowhere"))).isEmpty();
        assertThat(http(3000, 1000, 3).resolveShortLink(URI.create(base() + "/image"))).isEmpty();
    }

    // ---- the production rules ----

    private GuardedCoverHttp production() {
        CoverProperties props = new CoverProperties(true, "unused-%s", List.of("covers.openlibrary.org", "localhost"), 500, 2000, 1000, 3);
        return new GuardedCoverHttp(props, new BookLinkProperties(List.of("amazon.com", "a.co", "localhost")));
    }

    @Test
    void productionRulesNeverFetchInternalOrUnlistedAddresses() {
        GuardedCoverHttp http = production();
        for (String url : List.of(
                "http://covers.openlibrary.org/b/isbn/1.jpg",       // not https
                "https://covers.openlibrary.org:8443/b/isbn/1.jpg", // not the standard port
                "https://user:pw@covers.openlibrary.org/x.jpg",     // credentials in the address
                "https://localhost/x.jpg",                          // allow-listed name that resolves to loopback
                "https://127.0.0.1/x.jpg",                          // loopback
                "https://10.0.0.5/x.jpg",                           // private network
                "https://192.168.1.1/x.jpg",
                "https://169.254.169.254/latest/meta-data/",        // cloud metadata address
                "https://[::1]/x.jpg",
                "https://evilopenlibrary.org/x.jpg",                // looks similar, different host
                "https://example.com/x.jpg")) {
            assertThat(http.fetchImage(URI.create(url))).as(url).isEmpty();
        }
        assertThat(http.resolveShortLink(URI.create("https://localhost/d/abc"))).isEmpty();
        assertThat(http.resolveShortLink(URI.create("https://evil.example/d/abc"))).isEmpty();
    }
}
