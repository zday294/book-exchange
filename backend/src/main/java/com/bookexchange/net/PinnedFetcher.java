package com.bookexchange.net;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one place the server fetches addresses that people typed in. Every request, including each redirect:
 * <ul>
 *   <li>is https on the default port, without credentials (the test policy may also allow plain http);</li>
 *   <li>goes to an allowed host (the caller's list; "amazon.com" allows "www.amazon.com", never "evilamazon.com");</li>
 *   <li>goes to a host that resolves <em>only</em> to public addresses, and connects to <em>that</em> address,
 *       so a second DNS answer can't send it elsewhere (TLS is still verified against the host name);</li>
 *   <li>shares one overall time limit, with redirects followed by hand and capped, and sizes capped.</li>
 * </ul>
 * It never throws; trouble is {@link Unreachable}.
 */
public final class PinnedFetcher {

    private static final Logger log = LoggerFactory.getLogger(PinnedFetcher.class);

    /** What counts as an acceptable destination. Production: https only, public addresses only. */
    public record Policy(boolean httpsOnly, Predicate<InetAddress> addressAllowed) {
        public static final Policy PRODUCTION = new Policy(true, PublicAddresses::isPublic);
    }

    @FunctionalInterface
    public interface Resolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    /**
     * @param allowedHosts   hosts (and their subdomains) this walk may visit
     * @param stopHere       if the walk reaches a URL for which this is true it stops there without contacting it
     * @param headFirst      try HEAD, and confirm with GET when HEAD is refused or says "not found"
     * @param accept         the Accept header
     * @param maxBodyBytes   0 = headers only; otherwise a 200 answer of type {@code bodyTypePrefix} is read up to this size
     */
    public record Spec(List<String> allowedHosts, Predicate<URI> stopHere, boolean headFirst, String accept,
                       int maxBodyBytes, String bodyTypePrefix, int connectTimeoutMs, int totalTimeoutMs, int maxRedirects) {
    }

    public sealed interface Outcome permits Reached, Stopped, Unreachable {
    }

    /** The walk ended on an answer that is not a redirect. */
    public record Reached(URI uri, Transport.Reply reply) implements Outcome {
    }

    /** The walk reached a URL the caller said to stop at; nothing was fetched from it. */
    public record Stopped(URI uri) implements Outcome {
    }

    /** Refused for safety, or failed, timed out, looped. {@code reason} is for the log only. */
    public record Unreachable(String reason) implements Outcome {
    }

    private final Policy policy;
    private final Transport transport;
    private final Resolver resolver;

    public PinnedFetcher(Policy policy, Transport transport, Resolver resolver) {
        this.policy = policy;
        this.transport = transport;
        this.resolver = resolver;
    }

    public static PinnedFetcher production() {
        return new PinnedFetcher(Policy.PRODUCTION, new SocketTransport(), InetAddress::getAllByName);
    }

    public Outcome fetch(URI start, Spec spec) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(spec.totalTimeoutMs());
        URI current = start;
        try {
            for (int hop = 0; hop <= spec.maxRedirects(); hop++) {
                if (!allowed(current, spec.allowedHosts())) {
                    return new Unreachable("not allowed: " + current.getHost());
                }
                if (spec.stopHere().test(current)) {
                    return new Stopped(current);
                }
                List<InetAddress> addresses = publicAddresses(current.getHost());
                if (addresses.isEmpty()) {
                    return new Unreachable("not a public address: " + current.getHost());
                }
                Transport.Reply reply = askAnyAddress(current, addresses, spec, deadline);
                int status = reply.status();
                if (status >= 300 && status < 400) {
                    if (reply.location() == null || reply.location().isBlank()) {
                        return new Unreachable("redirect without a location: " + current.getHost());
                    }
                    current = current.resolve(reply.location().strip());
                    continue;
                }
                return new Reached(current, reply);
            }
            return new Unreachable("too many redirects");
        } catch (IOException | RuntimeException e) {
            log.info("Could not fetch {}: {}", current.getHost(), e.toString());
            return new Unreachable(e.toString());
        }
    }

    /** Tries the vetted addresses in order (an IPv6 address first on a machine without IPv6 shouldn't be fatal). */
    private Transport.Reply askAnyAddress(URI uri, List<InetAddress> addresses, Spec spec, long deadline) throws IOException {
        IOException last = null;
        for (InetAddress address : addresses) {
            try {
                return ask(uri, address, spec, deadline);
            } catch (IOException e) {
                last = e;
                if (System.nanoTime() >= deadline) {
                    break;
                }
            }
        }
        throw last;
    }

    private Transport.Reply ask(URI uri, InetAddress address, Spec spec, long deadline) throws IOException {
        if (spec.headFirst()) {
            Transport.Reply head = transport.send(new Transport.Request("HEAD", uri, address, spec.accept(), 0, null,
                    spec.connectTimeoutMs(), deadline));
            if (head.status() < 400) {
                return head;
            }
        }
        return transport.send(new Transport.Request("GET", uri, address, spec.accept(), spec.maxBodyBytes(),
                spec.bodyTypePrefix(), spec.connectTimeoutMs(), deadline));
    }

    private boolean allowed(URI uri, List<String> allowedHosts) {
        String scheme = uri.getScheme();
        boolean schemeOk = "https".equalsIgnoreCase(scheme) || (!policy.httpsOnly() && "http".equalsIgnoreCase(scheme));
        if (!schemeOk || uri.getUserInfo() != null || uri.getHost() == null) {
            return false;
        }
        if (policy.httpsOnly() && uri.getPort() != -1 && uri.getPort() != 443) {
            return false;
        }
        return HostMatcher.matches(uri.getHost(), allowedHosts);
    }

    /** The addresses to connect to: empty unless the host resolves ONLY to acceptable addresses. */
    private List<InetAddress> publicAddresses(String host) {
        try {
            InetAddress[] addresses = resolver.resolve(host);
            for (InetAddress address : addresses) {
                if (!policy.addressAllowed().test(address)) {
                    return List.of(); // one bad answer is enough to refuse
                }
            }
            return List.of(addresses);
        } catch (UnknownHostException e) {
            return List.of();
        }
    }
}
