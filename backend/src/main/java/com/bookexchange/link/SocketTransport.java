package com.bookexchange.link;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * A tiny HTTP/1.1 client that connects to the exact address it is given (so a second DNS answer can't
 * send it somewhere else), keeps the real host name for TLS (certificate check and SNI) and the Host header,
 * sends no cookies or credentials, and reads only the status line and headers, never the page.
 */
final class SocketTransport implements Transport {

    static final String USER_AGENT = "book-exchange-link-check (checks that a submitted link works)";
    private static final int MAX_HEADER_BYTES = 16 * 1024;

    /** Layers TLS over a connected socket. Tests use a plain pass-through against a local server. */
    @FunctionalInterface
    interface Wrapper {
        Socket wrap(Socket connected, String host, int port) throws IOException;
    }

    static final Wrapper TLS = (connected, host, port) -> {
        SSLSocket ssl = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(connected, host, port, true);
        SSLParameters params = ssl.getSSLParameters();
        params.setEndpointIdentificationAlgorithm("HTTPS"); // the certificate must be for the host, not the address
        ssl.setSSLParameters(params);
        return ssl;
    };

    private final Wrapper wrapper;

    SocketTransport(Wrapper wrapper) {
        this.wrapper = wrapper;
    }

    @Override
    public Reply send(String method, URI uri, InetAddress address, int connectTimeoutMs, long deadlineNanos)
            throws IOException {
        int port = uri.getPort() == -1 ? 443 : uri.getPort();
        try (Socket raw = new Socket()) {
            raw.connect(new InetSocketAddress(address, port), Math.min(connectTimeoutMs, millisLeft(deadlineNanos)));
            raw.setSoTimeout(millisLeft(deadlineNanos));
            try (Socket socket = wrapper.wrap(raw, uri.getHost(), port)) {
                if (socket instanceof SSLSocket ssl) {
                    ssl.startHandshake();
                }
                socket.getOutputStream().write(request(method, uri, port).getBytes(StandardCharsets.ISO_8859_1));
                socket.getOutputStream().flush();
                return readHead(socket, deadlineNanos);
            }
        }
    }

    private static String request(String method, URI uri, int port) {
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        if (uri.getRawQuery() != null) {
            path += "?" + uri.getRawQuery();
        }
        String host = port == 443 ? uri.getHost() : uri.getHost() + ":" + port;
        return method + " " + path + " HTTP/1.1\r\n"
                + "Host: " + host + "\r\n"
                + "User-Agent: " + USER_AGENT + "\r\n"
                + "Accept: */*\r\n"
                + "Connection: close\r\n\r\n";
    }

    private static Reply readHead(Socket socket, long deadlineNanos) throws IOException {
        InputStream in = socket.getInputStream();
        int total = 0;
        String statusLine = null;
        String location = null;
        while (true) {
            socket.setSoTimeout(millisLeft(deadlineNanos));
            String line = readLine(in, MAX_HEADER_BYTES - total);
            total += line.length() + 2;
            if (statusLine == null) {
                statusLine = line;
            } else if (line.isEmpty()) {
                break;
            } else if (line.toLowerCase(Locale.ROOT).startsWith("location:")) {
                location = line.substring("location:".length()).strip();
            }
        }
        String[] parts = statusLine.split(" ", 3);
        if (parts.length < 2 || !parts[0].startsWith("HTTP/")) {
            throw new IOException("not an HTTP answer");
        }
        try {
            return new Reply(Integer.parseInt(parts[1]), location);
        } catch (NumberFormatException e) {
            throw new IOException("not an HTTP status", e);
        }
    }

    private static String readLine(InputStream in, int budget) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                byte[] bytes = line.toByteArray();
                int length = bytes.length > 0 && bytes[bytes.length - 1] == '\r' ? bytes.length - 1 : bytes.length;
                return new String(bytes, 0, length, StandardCharsets.ISO_8859_1);
            }
            line.write(b);
            if (line.size() > budget) {
                throw new IOException("headers too large");
            }
        }
        throw new IOException("connection closed before the headers ended");
    }

    private static int millisLeft(long deadlineNanos) throws IOException {
        long ms = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
        if (ms <= 0) {
            throw new IOException("time is up");
        }
        return (int) Math.min(ms, Integer.MAX_VALUE);
    }
}
