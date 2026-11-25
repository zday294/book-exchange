package com.bookexchange.net;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** The shared rules, with the network and DNS faked. (The link check and cover tests cover them from their side too.) */
class PinnedFetcherTest {

    private static final List<String> HOSTS = List.of("amazon.com", "a.co");

    private record Sent(String method, URI uri, InetAddress address) {}

    private final List<Sent> sent = new ArrayList<>();
    private Function<Sent, Transport.Reply> script = s -> new Transport.Reply(200, null);
    private final Transport transport = request -> {
        Sent s = new Sent(request.method(), request.uri(), request.address());
        sent.add(s);
        return script.apply(s);
    };

    private static InetAddress ip(String text) {
        try {
            return InetAddress.getByName(text);
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    private PinnedFetcher fetcher(PinnedFetcher.Resolver resolver) {
        return new PinnedFetcher(PinnedFetcher.Policy.PRODUCTION, transport, resolver);
    }

    private static PinnedFetcher.Spec spec(java.util.function.Predicate<URI> stopHere) {
        return new PinnedFetcher.Spec(HOSTS, stopHere, false, "*/*", 0, null, 1000, 3000, 3);
    }

    @Test
    void connectsToTheAddressItCheckedNotToAnotherLookup() {
        int[] lookups = {0};
        InetAddress first = ip("151.101.1.1");
        InetAddress later = ip("127.0.0.1"); // what a rebinding DNS server would answer the second time
        var fetcher = fetcher(host -> new InetAddress[] {lookups[0]++ == 0 ? first : later});

        assertThat(fetcher.fetch(URI.create("https://www.amazon.com/x"), spec(u -> false))).isInstanceOf(PinnedFetcher.Reached.class);
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).address()).isEqualTo(first);
        assertThat(lookups[0]).isEqualTo(1); // looked up once, then used
    }

    @Test
    void aStopUrlIsReturnedWithoutBeingContactedOrEvenLookedUp() {
        int[] lookups = {0};
        script = s -> new Transport.Reply(302, "https://www.amazon.com/dp/054792822X");
        var fetcher = fetcher(host -> { lookups[0]++; return new InetAddress[] {ip("151.101.1.1")}; });

        var outcome = fetcher.fetch(URI.create("https://a.co/d/abc"), spec(u -> u.getPath().startsWith("/dp/")));

        assertThat(outcome).isEqualTo(new PinnedFetcher.Stopped(URI.create("https://www.amazon.com/dp/054792822X")));
        assertThat(sent).hasSize(1);          // only the short link was fetched
        assertThat(lookups[0]).isEqualTo(1);  // and only its host was looked up
    }

    @Test
    void ifTheFirstAddressCantBeReachedTheNextCheckedOneIsTried() {
        InetAddress v6 = ip("2606:4700:4700::1111");
        InetAddress v4 = ip("151.101.1.1");
        Transport flaky = request -> {
            sent.add(new Sent(request.method(), request.uri(), request.address()));
            if (request.address().equals(v6)) throw new IOException("network unreachable");
            return new Transport.Reply(200, null);
        };
        var fetcher = new PinnedFetcher(PinnedFetcher.Policy.PRODUCTION, flaky, host -> new InetAddress[] {v6, v4});

        assertThat(fetcher.fetch(URI.create("https://www.amazon.com/x"), spec(u -> false))).isInstanceOf(PinnedFetcher.Reached.class);
        assertThat(sent).extracting(Sent::address).containsExactly(v6, v4);
    }

    @Test
    void anUnreachableHostIsAnOutcomeNotAnException() {
        Transport broken = request -> { throw new IOException("refused"); };
        var fetcher = new PinnedFetcher(PinnedFetcher.Policy.PRODUCTION, broken, host -> new InetAddress[] {ip("151.101.1.1")});
        assertThat(fetcher.fetch(URI.create("https://www.amazon.com/x"), spec(u -> false))).isInstanceOf(PinnedFetcher.Unreachable.class);
    }

    @Test
    void anyNonPublicAnswerRefusesTheWholeHost() {
        for (String bad : List.of("127.0.0.1", "10.0.0.1", "169.254.169.254", "100.64.0.1", "::1", "fd00::1")) {
            sent.clear();
            var fetcher = fetcher(host -> new InetAddress[] {ip("151.101.1.1"), ip(bad)});
            assertThat(fetcher.fetch(URI.create("https://www.amazon.com/x"), spec(u -> false))).as(bad)
                    .isInstanceOf(PinnedFetcher.Unreachable.class);
            assertThat(sent).as(bad).isEmpty();
        }
    }

    @Test
    void publicAddressCheckRecognisesInternalRanges() throws Exception {
        for (String internal : List.of("127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.0.9", "169.254.1.1", "0.0.0.0",
                "100.64.0.1", "::1", "fe80::1", "fc00::1", "fd12:3456::1", "224.0.0.1")) {
            assertThat(PublicAddresses.isPublic(InetAddress.getByName(internal))).as(internal).isFalse();
        }
        for (String external : List.of("8.8.8.8", "151.101.1.1", "2606:4700:4700::1111")) {
            assertThat(PublicAddresses.isPublic(InetAddress.getByName(external))).as(external).isTrue();
        }
    }
}
