package com.bookexchange.net;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;

/** One HTTP request to one address that has already been vetted. */
public interface Transport {

    /**
     * @param method         HEAD or GET
     * @param address        the address to connect to (already checked); the URI's host is only used for TLS and the Host header
     * @param accept         the Accept header
     * @param maxBodyBytes   0 = read only the status line and headers; otherwise the body is read (up to this many bytes,
     *                       anything bigger is an error) but only for a 200 answer whose Content-Type starts with {@code bodyTypePrefix}
     * @param bodyTypePrefix e.g. "image/"
     * @param deadlineNanos  {@link System#nanoTime()} by which everything, connecting and reading, must be finished
     */
    record Request(String method, URI uri, InetAddress address, String accept, int maxBodyBytes,
                   String bodyTypePrefix, int connectTimeoutMs, long deadlineNanos) {
    }

    /** {@code body} is null unless one was asked for and the answer was a 200 of the right type. */
    record Reply(int status, String location, String contentType, byte[] body) {

        public Reply(int status, String location) {
            this(status, location, null, null);
        }
    }

    Reply send(Request request) throws IOException;
}
