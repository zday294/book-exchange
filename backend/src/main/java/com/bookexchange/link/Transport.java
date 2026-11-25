package com.bookexchange.link;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;

/** One HTTP request to one address that has already been vetted. Only the status line and headers are read. */
interface Transport {

    record Reply(int status, String location) {
    }

    Reply send(String method, URI uri, InetAddress address, int connectTimeoutMs, long deadlineNanos) throws IOException;
}
