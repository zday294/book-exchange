package com.bookexchange.net;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The raw HTTP client against a local plain-text server (the TLS layer is the JDK's, swapped for a pass-through here). */
class SocketTransportTest {

    private ServerSocket server;
    private final SocketTransport transport = new SocketTransport();

    @BeforeEach
    void start() throws Exception {
        server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
    }

    @AfterEach
    void stop() throws Exception {
        server.close();
    }

    private int port() { return server.getLocalPort(); }

    private static Transport.Request req(String method, URI uri, int connectMs, long totalMs) {
        return new Transport.Request(method, uri, InetAddress.getLoopbackAddress(), "*/*", 0, null, connectMs, deadline(totalMs));
    }

    private static Transport.Request bodyReq(URI uri, int maxBytes, long totalMs) {
        return new Transport.Request("GET", uri, InetAddress.getLoopbackAddress(), "image/*", maxBytes, "image/", 1000, deadline(totalMs));
    }

    private static long deadline(long ms) { return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ms); }

    /** Serves one connection: remembers the request head, writes `response`, then waits for the client to hang up. */
    private CompletableFuture<String> serveOnce(String response) {
        return CompletableFuture.supplyAsync(() -> {
            try (Socket s = server.accept()) {
                InputStream in = s.getInputStream();
                ByteArrayOutputStream head = new ByteArrayOutputStream();
                int b;
                while ((b = in.read()) != -1) {
                    head.write(b);
                    if (head.toString(StandardCharsets.ISO_8859_1).endsWith("\r\n\r\n")) break;
                }
                OutputStream out = s.getOutputStream();
                out.write(response.getBytes(StandardCharsets.ISO_8859_1));
                out.flush();
                in.read(); // until the client closes
                return head.toString(StandardCharsets.ISO_8859_1);
            } catch (IOException e) {
                return "ERROR " + e;
            }
        });
    }

    private URI url(String path) { return URI.create("http://localhost:" + port() + path); }

    @Test
    void readsTheStatusAndTheLocation() throws Exception {
        var served = serveOnce("HTTP/1.1 301 Moved Permanently\r\nlocation: https://www.amazon.com/dp/1\r\nContent-Length: 0\r\n\r\n");
        Transport.Reply reply = transport.send(req("HEAD", url("/x"), 1000, 2000));
        assertThat(reply.status()).isEqualTo(301);
        assertThat(reply.location()).isEqualTo("https://www.amazon.com/dp/1");
        served.get(2, TimeUnit.SECONDS);
    }

    @Test
    void sendsAnHonestMinimalRequestWithNoCookiesOrCredentials() throws Exception {
        var served = serveOnce("HTTP/1.1 200 OK\r\n\r\n");
        transport.send(req("GET", url("/w/the-hobbit/1100054405?ean=9780547928227"), 1000, 2000));
        String head = served.get(2, TimeUnit.SECONDS);

        assertThat(head).startsWith("GET /w/the-hobbit/1100054405?ean=9780547928227 HTTP/1.1\r\n");
        assertThat(head).contains("Host: localhost:" + port() + "\r\n");
        assertThat(head).contains("User-Agent: book-exchange-link-check");
        assertThat(head).contains("Connection: close");
        // Only these headers: extras such as Accept-Encoding make Amazon answer 200 to everything (found by LiveLinkCheckTest).
        assertThat(head.toLowerCase()).doesNotContain("accept-encoding");
        assertThat(head.toLowerCase()).doesNotContain("cookie").doesNotContain("authorization").doesNotContain("referer");
    }

    @Test
    void anEmptyPathIsASlash() throws Exception {
        var served = serveOnce("HTTP/1.1 200 OK\r\n\r\n");
        transport.send(req("HEAD", URI.create("http://localhost:" + port()), 1000, 2000));
        assertThat(served.get(2, TimeUnit.SECONDS)).startsWith("HEAD / HTTP/1.1\r\n");
    }

    @Test
    void doesNotReadTheBody() throws Exception {
        // A body that never ends must not matter: only the head is read, then the connection is closed.
        var served = CompletableFuture.runAsync(() -> {
            try (Socket s = server.accept()) {
                s.getOutputStream().write("HTTP/1.1 404 Not Found\r\nContent-Length: 99999999\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                s.getOutputStream().flush();
                Thread.sleep(500);
            } catch (Exception ignored) {
                // the client hung up
            }
        });
        long start = System.nanoTime();
        Transport.Reply reply = transport.send(req("GET", url("/"), 1000, 3000));
        assertThat(reply.status()).isEqualTo(404);
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(2000);
        served.get(3, TimeUnit.SECONDS);
    }

    @Test
    void aServerThatSaysNothingTimesOutAtTheDeadline() {
        CompletableFuture.runAsync(() -> {
            try (Socket s = server.accept()) {
                Thread.sleep(2000);
            } catch (Exception ignored) {
                // done
            }
        });
        long start = System.nanoTime();
        assertThatThrownBy(() -> transport.send(req("HEAD", url("/"), 1000, 300)))
                .isInstanceOf(IOException.class);
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(1500);
    }

    @Test
    void headersThatNeverEndAreRefused() {
        serveOnce("HTTP/1.1 200 OK\r\n" + "X-Filler: " + "a".repeat(40_000) + "\r\n\r\n");
        assertThatThrownBy(() -> transport.send(req("HEAD", url("/"), 1000, 2000)))
                .isInstanceOf(IOException.class).hasMessageContaining("too large");
    }

    @Test
    void somethingThatIsNotHttpIsRefused() {
        serveOnce("SSH-2.0-OpenSSH_9.6\r\n\r\n");
        assertThatThrownBy(() -> transport.send(req("HEAD", url("/"), 1000, 2000)))
                .isInstanceOf(IOException.class);
    }

    @Test
    void aClosedPortIsAnIoError() throws Exception {
        int closed = port();
        server.close();
        assertThatThrownBy(() -> transport.send(req("HEAD", URI.create("http://localhost:" + closed + "/"), 500, 1000))).isInstanceOf(IOException.class);
    }

    // ---- bodies (only the cover lookup asks for one) ----

    @Test
    void readsABodyWithAContentLength() throws Exception {
        serveOnce("HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: 5\r\n\r\nhello");
        Transport.Reply reply = transport.send(bodyReq(url("/i"), 100, 2000));
        assertThat(reply.status()).isEqualTo(200);
        assertThat(new String(reply.body(), StandardCharsets.ISO_8859_1)).isEqualTo("hello");
    }

    @Test
    void readsAChunkedBody() throws Exception {
        serveOnce("HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nTransfer-Encoding: chunked\r\n\r\n"
                + "5\r\nhello\r\n6;ext=1\r\n world\r\n0\r\nX-Trailer: ignored\r\n\r\n");
        assertThat(new String(transport.send(bodyReq(url("/i"), 100, 2000)).body(), StandardCharsets.ISO_8859_1))
                .isEqualTo("hello world");
    }

    @Test
    void readsABodyWithNoLengthUntilTheConnectionCloses() throws Exception {
        CompletableFuture.runAsync(() -> {
            try (Socket s = server.accept()) {
                s.getInputStream().read(new byte[1024]);
                s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: image/gif\r\n\r\nabcdef".getBytes(StandardCharsets.ISO_8859_1));
            } catch (IOException ignored) {
                // done
            }
        });
        assertThat(new String(transport.send(bodyReq(url("/i"), 100, 2000)).body(), StandardCharsets.ISO_8859_1)).isEqualTo("abcdef");
    }

    @Test
    void aBodyBiggerThanTheCapIsRefusedWhetherTheLengthIsAnnouncedOrNot() {
        serveOnce("HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: 1000\r\n\r\n" + "x".repeat(1000));
        assertThatThrownBy(() -> transport.send(bodyReq(url("/i"), 100, 2000)))
                .isInstanceOf(IOException.class).hasMessageContaining("too large");

        // a lie about the length (small announced, big sent) is cut off at the cap too
        serveOnce("HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nTransfer-Encoding: chunked\r\n\r\n" + Integer.toHexString(500) + "\r\n" + "x".repeat(500) + "\r\n0\r\n\r\n");
        assertThatThrownBy(() -> transport.send(bodyReq(url("/i"), 100, 2000)))
                .isInstanceOf(IOException.class).hasMessageContaining("too large");
    }

    @Test
    void aBodyThatIsNotOfTheWantedTypeOrNotA200IsNeverRead() throws Exception {
        serveOnce("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: 5\r\n\r\nhello");
        Transport.Reply html = transport.send(bodyReq(url("/i"), 100, 2000));
        assertThat(html.status()).isEqualTo(200);
        assertThat(html.body()).isNull();

        serveOnce("HTTP/1.1 404 Not Found\r\nContent-Type: image/png\r\nContent-Length: 5\r\n\r\nhello");
        assertThat(transport.send(bodyReq(url("/i"), 100, 2000)).body()).isNull();
    }

    @Test
    void aBodyThatStallsIsCutOffAtTheDeadline() {
        CompletableFuture.runAsync(() -> {
            try (Socket s = server.accept()) {
                s.getInputStream().read(new byte[1024]);
                s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: 1000\r\n\r\nabc".getBytes(StandardCharsets.ISO_8859_1));
                s.getOutputStream().flush();
                Thread.sleep(3000);
            } catch (Exception ignored) {
                // done
            }
        });
        long start = System.nanoTime();
        assertThatThrownBy(() -> transport.send(bodyReq(url("/i"), 5000, 400))).isInstanceOf(IOException.class);
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(2000);
    }

    @Test
    void aCompressedBodyIsNeverUsed() throws Exception {
        serveOnce("HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nContent-Encoding: gzip\r\nContent-Length: 5\r\n\r\nhello");
        Transport.Reply reply = transport.send(bodyReq(url("/i"), 100, 2000));
        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.body()).isNull();
    }
}
