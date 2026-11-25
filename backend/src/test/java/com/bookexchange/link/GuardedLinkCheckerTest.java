package com.bookexchange.link;

import static org.assertj.core.api.Assertions.assertThat;

import com.bookexchange.net.PinnedFetcher;
import com.bookexchange.net.Transport;
import com.bookexchange.validation.BookLinkProperties;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The rules, with the network and DNS faked: what gets visited, and what each answer means. */
class GuardedLinkCheckerTest {

    private static final List<String> HOSTS = List.of("amazon.com", "amzn.to", "a.co", "barnesandnoble.com", "bn.com");
    private static final LinkCheckProperties PROPS = new LinkCheckProperties(true, 2000, 4000, 5, 0); // cache off here

    record Call(String method, URI uri, InetAddress address) {}

    /** Answers from a script: a function from (method, uri) to a reply, or an exception. */
    static class FakeTransport implements Transport {
        final List<Call> calls = new ArrayList<>();
        Function<Call, Reply> script = call -> new Reply(200, null);
        IOException failWith;

        @Override
        public Reply send(Request request) throws IOException {
            Call call = new Call(request.method(), request.uri(), request.address());
            calls.add(call);
            if (failWith != null) throw failWith;
            return script.apply(call);
        }

        List<String> methods() { return calls.stream().map(Call::method).toList(); }
        List<String> hosts() { return calls.stream().map(c -> c.uri().getHost()).toList(); }
    }

    private static final InetAddress PUBLIC = addr(93, 184, 216, 34);
    private final FakeTransport transport = new FakeTransport();
    private Map<String, InetAddress[]> dns = Map.of();

    private static InetAddress addr(int... b) {
        try {
            return InetAddress.getByAddress(new byte[] {(byte) b[0], (byte) b[1], (byte) b[2], (byte) b[3]});
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private LinkStatus check(String url) {
        return checker(PROPS, System::nanoTime).check(URI.create(url));
    }

    private GuardedLinkChecker checker(LinkCheckProperties props, java.util.function.LongSupplier clock) {
        var fetcher = new PinnedFetcher(PinnedFetcher.Policy.PRODUCTION, transport,
                host -> dns.getOrDefault(host, new InetAddress[] {PUBLIC}));
        return new GuardedLinkChecker(props, new BookLinkProperties(HOSTS), fetcher, clock);
    }

    private static Transport.Reply redirect(String to) { return new Transport.Reply(301, to); }

    @Test
    void aPageThatExistsIsOkAndOnlyHeadIsNeeded() {
        assertThat(check("https://www.amazon.com/dp/0547928221")).isEqualTo(LinkStatus.OK);
        assertThat(transport.methods()).containsExactly("HEAD");
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 204})
    void successStatusesAreOk(int status) {
        transport.script = c -> new Transport.Reply(status, null);
        assertThat(check("https://www.barnesandnoble.com/w/x/1")).isEqualTo(LinkStatus.OK);
    }

    @ParameterizedTest
    @ValueSource(ints = {404, 410})
    void aDefiniteNotFoundIsNotFoundOnlyWhenGetAgrees(int status) {
        transport.script = c -> new Transport.Reply(status, null);
        assertThat(check("https://www.amazon.com/dp/0000000000")).isEqualTo(LinkStatus.NOT_FOUND);
        assertThat(transport.methods()).containsExactly("HEAD", "GET"); // confirmed with GET
    }

    @Test
    void aHeadThatSaysNotFoundButAGetThatWorksIsOk() {
        transport.script = c -> c.method().equals("HEAD") ? new Transport.Reply(404, null) : new Transport.Reply(200, null);
        assertThat(check("https://www.amazon.com/dp/0547928221")).isEqualTo(LinkStatus.OK);
    }

    @ParameterizedTest
    @ValueSource(ints = {405, 501})
    void whenHeadIsRefusedTheGetDecides(int headStatus) {
        transport.script = c -> c.method().equals("HEAD") ? new Transport.Reply(headStatus, null) : new Transport.Reply(200, null);
        assertThat(check("https://www.amazon.com/dp/0547928221")).isEqualTo(LinkStatus.OK);
        assertThat(transport.methods()).containsExactly("HEAD", "GET");

        transport.calls.clear();
        transport.script = c -> c.method().equals("HEAD") ? new Transport.Reply(headStatus, null) : new Transport.Reply(404, null);
        assertThat(check("https://www.amazon.com/dp/0000000000")).isEqualTo(LinkStatus.NOT_FOUND);
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 429, 500, 503})
    void blockedRateLimitedOrBrokenIsUnverifiedNotRejected(int status) {
        transport.script = c -> new Transport.Reply(status, null);
        assertThat(check("https://www.amazon.com/dp/0547928221")).isEqualTo(LinkStatus.UNVERIFIED);
    }

    @Test
    void timeoutsAndConnectionErrorsAreUnverified() {
        transport.failWith = new java.net.SocketTimeoutException("slow");
        assertThat(check("https://www.amazon.com/dp/1")).isEqualTo(LinkStatus.UNVERIFIED);
        transport.failWith = new IOException("connection reset");
        assertThat(check("https://www.amazon.com/dp/1")).isEqualTo(LinkStatus.UNVERIFIED);
    }

    @Test
    void anUnexpectedErrorNeverEscapes() {
        transport.script = c -> { throw new IllegalStateException("boom"); };
        assertThat(check("https://www.amazon.com/dp/1")).isEqualTo(LinkStatus.UNVERIFIED);
    }

    @Test
    void aShortLinkIsFollowedAndTheFinalPageDecides() {
        transport.script = c -> switch (c.uri().getHost()) {
            case "a.co" -> redirect("https://www.amazon.com/dp/0000000000");
            default -> new Transport.Reply(404, null);
        };
        assertThat(check("https://a.co/d/abc123")).isEqualTo(LinkStatus.NOT_FOUND);
        assertThat(transport.hosts()).contains("a.co", "www.amazon.com");
    }

    @Test
    void aRelativeRedirectStaysOnTheSameHost() {
        transport.script = c -> c.uri().getPath().equals("/old") ? redirect("/new") : new Transport.Reply(200, null);
        assertThat(check("https://www.amazon.com/old")).isEqualTo(LinkStatus.OK);
        assertThat(transport.calls.get(transport.calls.size() - 1).uri().getPath()).isEqualTo("/new");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://evil.example.com/steal",
            "http://www.amazon.com/dp/1",                 // downgrade to http
            "https://www.amazon.com:8443/dp/1",           // odd port
            "https://user:pw@www.amazon.com/dp/1",        // credentials
            "https://amazon.com.evil.com/dp/1",           // lookalike
            "https://127.0.0.1/",
            "https://169.254.169.254/latest/meta-data/"})
    void aRedirectToAnythingNotAllowedIsNeverFollowed(String target) {
        transport.script = c -> redirect(target);
        assertThat(check("https://a.co/d/abc123")).isEqualTo(LinkStatus.UNVERIFIED);
        assertThat(transport.hosts()).containsOnly("a.co"); // only the first hop was ever contacted
    }

    @Test
    void tooManyRedirectsIsUnverified() {
        transport.script = c -> redirect("https://www.amazon.com/loop");
        assertThat(check("https://www.amazon.com/start")).isEqualTo(LinkStatus.UNVERIFIED);
        assertThat(transport.calls.stream().filter(c -> c.method().equals("HEAD")).count()).isEqualTo(6); // first + 5
    }

    @Test
    void aRedirectWithoutALocationIsUnverified() {
        transport.script = c -> new Transport.Reply(302, null);
        assertThat(check("https://www.amazon.com/dp/1")).isEqualTo(LinkStatus.UNVERIFIED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "10.0.0.5", "192.168.1.1", "172.16.0.9", "169.254.169.254", "0.0.0.0", "100.64.0.1"})
    void aHostThatResolvesToAnInternalAddressIsNeverContacted(String ip) throws Exception {
        dns = Map.of("www.amazon.com", new InetAddress[] {InetAddress.getByName(ip)});
        assertThat(check("https://www.amazon.com/dp/1")).isEqualTo(LinkStatus.UNVERIFIED);
        assertThat(transport.calls).isEmpty();
    }

    @Test
    void oneInternalAddressAmongPublicOnesIsEnoughToRefuse() throws Exception {
        dns = Map.of("www.amazon.com", new InetAddress[] {PUBLIC, InetAddress.getByName("10.0.0.5")});
        assertThat(check("https://www.amazon.com/dp/1")).isEqualTo(LinkStatus.UNVERIFIED);
        assertThat(transport.calls).isEmpty();
    }

    @Test
    void ipv6LoopbackAndUniqueLocalAreRefused() throws Exception {
        for (String ip : List.of("::1", "fd00::1", "fe80::1")) {
            dns = Map.of("www.amazon.com", new InetAddress[] {InetAddress.getByName(ip)});
            assertThat(check("https://www.amazon.com/dp/1")).as(ip).isEqualTo(LinkStatus.UNVERIFIED);
        }
        assertThat(transport.calls).isEmpty();
    }

    @Test
    void aHostThatDoesNotResolveIsUnverified() {
        var fetcher = new PinnedFetcher(PinnedFetcher.Policy.PRODUCTION, transport,
                host -> { throw new java.net.UnknownHostException(host); });
        var checker = new GuardedLinkChecker(PROPS, new BookLinkProperties(HOSTS), fetcher, System::nanoTime);
        assertThat(checker.check(URI.create("https://www.amazon.com/dp/1"))).isEqualTo(LinkStatus.UNVERIFIED);
        assertThat(transport.calls).isEmpty();
    }

    @Test
    void theConnectionGoesToTheAddressThatWasChecked() {
        InetAddress checked = addr(151, 101, 1, 1);
        dns = Map.of("www.amazon.com", new InetAddress[] {checked});
        check("https://www.amazon.com/dp/1");
        assertThat(transport.calls).allSatisfy(c -> assertThat(c.address()).isEqualTo(checked));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://www.amazon.com/dp/1",
            "https://example.com/dp/1",
            "https://evilbarnesandnoble.com/w/x/1",
            "https://user:pw@www.amazon.com/dp/1",
            "https://www.amazon.com:8443/dp/1",
            "ftp://www.amazon.com/dp/1"})
    void aLinkThatIsNotAllowedIsNeverVisitedEvenIfAskedDirectly(String url) {
        assertThat(check(url)).isEqualTo(LinkStatus.UNVERIFIED);
        assertThat(transport.calls).isEmpty();
    }

    // ---- remembering definite answers ----

    private static final LinkCheckProperties CACHED = new LinkCheckProperties(true, 2000, 4000, 5, 10);

    @Test
    void aDefiniteAnswerIsRememberedForTheSameExactLink() {
        long[] now = {0};
        var checker = checker(CACHED, () -> now[0]);
        URI link = URI.create("https://www.amazon.com/dp/054792822X");

        assertThat(checker.check(link)).isEqualTo(LinkStatus.OK);
        int callsAfterFirst = transport.calls.size();
        assertThat(checker.check(link)).isEqualTo(LinkStatus.OK);
        assertThat(transport.calls).hasSize(callsAfterFirst); // no second visit

        // a different link (the typo fixed) is a fresh question
        assertThat(checker.check(URI.create("https://www.amazon.com/dp/0000000000"))).isEqualTo(LinkStatus.OK);
        assertThat(transport.calls.size()).isGreaterThan(callsAfterFirst);
    }

    @Test
    void aRememberedNotFoundIsAlsoRememberedButOnlyForTenMinutes() {
        long[] now = {0};
        transport.script = c -> new Transport.Reply(404, null);
        var checker = checker(CACHED, () -> now[0]);
        URI link = URI.create("https://www.amazon.com/dp/0000000000");

        assertThat(checker.check(link)).isEqualTo(LinkStatus.NOT_FOUND);
        int visits = transport.calls.size();
        now[0] = java.util.concurrent.TimeUnit.MINUTES.toNanos(9);
        assertThat(checker.check(link)).isEqualTo(LinkStatus.NOT_FOUND);
        assertThat(transport.calls).hasSize(visits); // still remembered

        // the page was fixed in the meantime; after ten minutes the store is asked again
        transport.script = c -> new Transport.Reply(200, null);
        now[0] = java.util.concurrent.TimeUnit.MINUTES.toNanos(11);
        assertThat(checker.check(link)).isEqualTo(LinkStatus.OK);
        assertThat(transport.calls.size()).isGreaterThan(visits);
    }

    @Test
    void couldntTellIsNeverRemembered() {
        long[] now = {0};
        transport.script = c -> new Transport.Reply(429, null);
        var checker = checker(CACHED, () -> now[0]);
        URI link = URI.create("https://www.amazon.com/dp/054792822X");

        assertThat(checker.check(link)).isEqualTo(LinkStatus.UNVERIFIED);
        transport.script = c -> new Transport.Reply(200, null); // the rate limit is over
        assertThat(checker.check(link)).isEqualTo(LinkStatus.OK);
    }

    @Test
    void theCacheCanBeSwitchedOff() {
        transport.script = c -> new Transport.Reply(200, null);
        URI link = URI.create("https://www.amazon.com/dp/054792822X");
        var checker = checker(PROPS, System::nanoTime); // cache-minutes 0
        checker.check(link);
        int visits = transport.calls.size();
        checker.check(link);
        assertThat(transport.calls.size()).isEqualTo(visits * 2);
    }

    @Test
    void theCacheStaysBounded() {
        var cache = new LinkResultCache(10, () -> 0L);
        for (int i = 0; i < LinkResultCache.MAX_ENTRIES * 3; i++) {
            cache.put("https://www.amazon.com/dp/" + i, LinkStatus.OK);
        }
        // never more than the cap is held; the newest entry is always there
        assertThat(cache.get("https://www.amazon.com/dp/" + (LinkResultCache.MAX_ENTRIES * 3 - 1))).contains(LinkStatus.OK);
        long held = java.util.stream.IntStream.range(0, LinkResultCache.MAX_ENTRIES * 3)
                .filter(i -> cache.get("https://www.amazon.com/dp/" + i).isPresent()).count();
        assertThat(held).isLessThanOrEqualTo(LinkResultCache.MAX_ENTRIES);
    }
}
