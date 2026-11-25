package com.bookexchange.link;

import com.bookexchange.cover.GuardedCoverHttp;
import com.bookexchange.cover.HostMatcher;
import com.bookexchange.validation.BookLinkProperties;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Asks the store whether a submitted link's page exists, treating the link as untrusted:
 * <ul>
 *   <li>every address visited (the link and each redirect) must be https, on the default port, without
 *       credentials, on an allowed host, and must resolve only to public addresses;</li>
 *   <li>the connection goes to the address that was checked, not to a second DNS answer;</li>
 *   <li>redirects are followed by hand, at most a few, with a short overall time limit;</li>
 *   <li>only the status and headers are read; a HEAD request is tried first, then a GET when HEAD is refused
 *       or says "not found" (so a store that answers HEAD badly never makes a good link look dead);</li>
 *   <li>no cookies, no credentials and nothing about the participant is sent.</li>
 * </ul>
 * Only a definite 404 or 410 gives {@link LinkStatus#NOT_FOUND}; everything else that is not a success is
 * {@link LinkStatus#UNVERIFIED}, because stores often block or rate-limit programs.
 */
@Component
public class GuardedLinkChecker implements LinkChecker {

    private static final Logger log = LoggerFactory.getLogger(GuardedLinkChecker.class);

    /** What counts as an acceptable destination. Production: https only, public addresses only. */
    record Policy(boolean httpsOnly, Predicate<InetAddress> addressAllowed) {
        static final Policy PRODUCTION = new Policy(true, GuardedCoverHttp::isPublic);
    }

    @FunctionalInterface
    interface Resolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private final LinkCheckProperties props;
    private final List<String> allowedHosts;
    private final Policy policy;
    private final Transport transport;
    private final Resolver resolver;

    @Autowired
    public GuardedLinkChecker(LinkCheckProperties props, BookLinkProperties bookLinks) {
        this(props, bookLinks.allowedHosts(), Policy.PRODUCTION, new SocketTransport(SocketTransport.TLS),
                InetAddress::getAllByName);
    }

    GuardedLinkChecker(LinkCheckProperties props, List<String> allowedHosts, Policy policy, Transport transport,
                       Resolver resolver) {
        this.props = props;
        this.allowedHosts = allowedHosts;
        this.policy = policy;
        this.transport = transport;
        this.resolver = resolver;
    }

    @Override
    public LinkStatus check(URI link) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(props.totalTimeoutMs());
        URI current = link;
        try {
            for (int hop = 0; hop <= props.maxRedirects(); hop++) {
                Optional<InetAddress> address = vet(current);
                if (address.isEmpty()) {
                    log.info("Link check did not visit {} (not allowed or not a public address)", current.getHost());
                    return LinkStatus.UNVERIFIED;
                }
                Transport.Reply reply = ask(current, address.get(), deadline);
                int status = reply.status();
                if (status >= 300 && status < 400) {
                    if (reply.location() == null || reply.location().isBlank()) {
                        return LinkStatus.UNVERIFIED;
                    }
                    current = current.resolve(reply.location().strip());
                    continue;
                }
                if (status == 404 || status == 410) {
                    return LinkStatus.NOT_FOUND;
                }
                if (status >= 200 && status < 300) {
                    return LinkStatus.OK;
                }
                log.info("Link check could not tell for {}: status {}", current.getHost(), status);
                return LinkStatus.UNVERIFIED;
            }
            return LinkStatus.UNVERIFIED; // too many redirects
        } catch (IOException | RuntimeException e) {
            log.info("Link check could not reach {}: {}", current.getHost(), e.toString());
            return LinkStatus.UNVERIFIED;
        }
    }

    /** HEAD first; if it is refused or says "not found", confirm with GET before believing it. */
    private Transport.Reply ask(URI uri, InetAddress address, long deadline) throws IOException {
        Transport.Reply head = transport.send("HEAD", uri, address, props.connectTimeoutMs(), deadline);
        if (head.status() < 400) {
            return head;
        }
        return transport.send("GET", uri, address, props.connectTimeoutMs(), deadline);
    }

    /** The address to connect to, or empty if this URL may not be visited. */
    private Optional<InetAddress> vet(URI uri) {
        String scheme = uri.getScheme();
        boolean schemeOk = "https".equalsIgnoreCase(scheme) || (!policy.httpsOnly() && "http".equalsIgnoreCase(scheme));
        if (!schemeOk || uri.getUserInfo() != null || uri.getHost() == null) {
            return Optional.empty();
        }
        if (policy.httpsOnly() && uri.getPort() != -1 && uri.getPort() != 443) {
            return Optional.empty();
        }
        if (!HostMatcher.matches(uri.getHost(), allowedHosts)) {
            return Optional.empty();
        }
        try {
            InetAddress[] addresses = resolver.resolve(uri.getHost());
            if (addresses.length == 0) {
                return Optional.empty();
            }
            for (InetAddress address : addresses) {
                if (!policy.addressAllowed().test(address)) {
                    return Optional.empty(); // one bad answer is enough to refuse
                }
            }
            return Optional.of(addresses[0]);
        } catch (UnknownHostException e) {
            return Optional.empty();
        }
    }
}
