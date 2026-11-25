package com.bookexchange.net;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
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
 * sends no cookies or credentials (and deliberately only the few headers below: stores such as Amazon treat
 * unusual extra headers, for instance Accept-Encoding: identity, as a robot and answer with a 200 "robot check"
 * page, which made every link look fine), and enforces one overall deadline, a header size cap and a body size cap.
 * Plain http (no TLS) exists only because the fetcher's test policy allows it; the production policy never does.
 */
public final class SocketTransport implements Transport {

    public static final String USER_AGENT = "book-exchange-link-check (checks that a submitted link works)";
    private static final int MAX_HEADER_BYTES = 16 * 1024;

    @Override
    public Reply send(Request request) throws IOException {
        URI uri = request.uri();
        boolean tls = "https".equalsIgnoreCase(uri.getScheme());
        int port = uri.getPort() == -1 ? (tls ? 443 : 80) : uri.getPort();
        try (Socket raw = new Socket()) {
            raw.connect(new InetSocketAddress(request.address(), port),
                    Math.min(request.connectTimeoutMs(), millisLeft(request.deadlineNanos())));
            raw.setSoTimeout(millisLeft(request.deadlineNanos()));
            try (Socket socket = tls ? tls(raw, uri.getHost(), port) : raw) {
                if (socket instanceof SSLSocket ssl) {
                    ssl.startHandshake();
                }
                socket.getOutputStream().write(head(request, port, tls).getBytes(StandardCharsets.ISO_8859_1));
                socket.getOutputStream().flush();
                return read(socket, request);
            }
        }
    }

    private static Socket tls(Socket connected, String host, int port) throws IOException {
        SSLSocket ssl = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(connected, host, port, true);
        SSLParameters params = ssl.getSSLParameters();
        params.setEndpointIdentificationAlgorithm("HTTPS"); // the certificate must be for the host, not the address
        ssl.setSSLParameters(params);
        return ssl;
    }

    private static String head(Request request, int port, boolean tls) {
        URI uri = request.uri();
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        if (uri.getRawQuery() != null) {
            path += "?" + uri.getRawQuery();
        }
        boolean defaultPort = port == (tls ? 443 : 80);
        String host = defaultPort ? uri.getHost() : uri.getHost() + ":" + port;
        return request.method() + " " + path + " HTTP/1.1\r\n"
                + "Host: " + host + "\r\n"
                + "User-Agent: " + USER_AGENT + "\r\n"
                + "Accept: " + request.accept() + "\r\n"
                + "Connection: close\r\n\r\n";
    }

    private static Reply read(Socket socket, Request request) throws IOException {
        InputStream in = socket.getInputStream();
        int total = 0;
        String statusLine = null;
        String location = null;
        String contentType = null;
        long contentLength = -1;
        boolean chunked = false;
        boolean encoded = false;
        while (true) {
            socket.setSoTimeout(millisLeft(request.deadlineNanos()));
            String line = readLine(in, MAX_HEADER_BYTES - total);
            total += line.length() + 2;
            if (statusLine == null) {
                statusLine = line;
            } else if (line.isEmpty()) {
                break;
            } else {
                String lower = line.toLowerCase(Locale.ROOT);
                if (lower.startsWith("location:")) {
                    location = line.substring("location:".length()).strip();
                } else if (lower.startsWith("content-type:")) {
                    contentType = line.substring("content-type:".length()).strip();
                } else if (lower.startsWith("content-length:")) {
                    try {
                        contentLength = Long.parseLong(line.substring("content-length:".length()).strip());
                    } catch (NumberFormatException e) {
                        throw new IOException("bad Content-Length", e);
                    }
                } else if (lower.startsWith("transfer-encoding:") && lower.contains("chunked")) {
                    chunked = true;
                } else if (lower.startsWith("content-encoding:") && !lower.contains("identity")) {
                    encoded = true; // gzip and friends: we never ask for them and never unpack them
                }
            }
        }
        String[] parts = statusLine.split(" ", 3);
        if (parts.length < 2 || !parts[0].startsWith("HTTP/")) {
            throw new IOException("not an HTTP answer");
        }
        int status;
        try {
            status = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            throw new IOException("not an HTTP status", e);
        }
        boolean wantBody = request.maxBodyBytes() > 0 && "GET".equals(request.method()) && status == 200
                && !encoded && contentType != null && request.bodyTypePrefix() != null
                && contentType.toLowerCase(Locale.ROOT).startsWith(request.bodyTypePrefix());
        if (!wantBody) {
            return new Reply(status, location, contentType, null);
        }
        if (contentLength > request.maxBodyBytes()) {
            throw new IOException("body too large");
        }
        byte[] body = chunked ? readChunked(socket, request) : readPlain(socket, request, contentLength);
        return new Reply(status, location, contentType, body);
    }

    private static byte[] readPlain(Socket socket, Request request, long contentLength) throws IOException {
        InputStream in = socket.getInputStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        while (contentLength < 0 || out.size() < contentLength) {
            socket.setSoTimeout(millisLeft(request.deadlineNanos()));
            int wanted = contentLength < 0 ? buffer.length : (int) Math.min(buffer.length, contentLength - out.size());
            int read = in.read(buffer, 0, wanted);
            if (read == -1) {
                if (contentLength >= 0) {
                    throw new IOException("connection closed before the body ended");
                }
                break; // no length given: the end of the body is the end of the connection
            }
            out.write(buffer, 0, read);
            if (out.size() > request.maxBodyBytes()) {
                throw new IOException("body too large");
            }
        }
        return out.toByteArray();
    }

    private static byte[] readChunked(Socket socket, Request request) throws IOException {
        InputStream in = socket.getInputStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (true) {
            socket.setSoTimeout(millisLeft(request.deadlineNanos()));
            String sizeLine = readLine(in, 64);
            int semicolon = sizeLine.indexOf(';');
            String hex = (semicolon >= 0 ? sizeLine.substring(0, semicolon) : sizeLine).strip();
            int size;
            try {
                size = Integer.parseInt(hex, 16);
            } catch (NumberFormatException e) {
                throw new IOException("bad chunk size", e);
            }
            if (size < 0 || out.size() + (long) size > request.maxBodyBytes()) {
                throw new IOException("body too large");
            }
            if (size == 0) {
                return out.toByteArray(); // trailers, if any, are ignored
            }
            byte[] chunk = new byte[size];
            int done = 0;
            while (done < size) {
                socket.setSoTimeout(millisLeft(request.deadlineNanos()));
                int read = in.read(chunk, done, size - done);
                if (read == -1) {
                    throw new IOException("connection closed inside a chunk");
                }
                done += read;
            }
            out.write(chunk);
            readLine(in, 8); // the line break after the chunk
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
