package com.bookexchange.link;

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
    private final SocketTransport transport = new SocketTransport((connected, host, port) -> connected);

    @BeforeEach
    void start() throws Exception {
        server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
    }

    @AfterEach
    void stop() throws Exception {
        server.close();
    }

    private int port() { return server.getLocalPort(); }

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

    private URI url(String path) { return URI.create("https://localhost:" + port() + path); }

    @Test
    void readsTheStatusAndTheLocation() throws Exception {
        var served = serveOnce("HTTP/1.1 301 Moved Permanently\r\nlocation: https://www.amazon.com/dp/1\r\nContent-Length: 0\r\n\r\n");
        Transport.Reply reply = transport.send("HEAD", url("/x"), InetAddress.getLoopbackAddress(), 1000, deadline(2000));
        assertThat(reply.status()).isEqualTo(301);
        assertThat(reply.location()).isEqualTo("https://www.amazon.com/dp/1");
        served.get(2, TimeUnit.SECONDS);
    }

    @Test
    void sendsAnHonestMinimalRequestWithNoCookiesOrCredentials() throws Exception {
        var served = serveOnce("HTTP/1.1 200 OK\r\n\r\n");
        transport.send("GET", url("/w/the-hobbit/1100054405?ean=9780547928227"), InetAddress.getLoopbackAddress(), 1000, deadline(2000));
        String head = served.get(2, TimeUnit.SECONDS);

        assertThat(head).startsWith("GET /w/the-hobbit/1100054405?ean=9780547928227 HTTP/1.1\r\n");
        assertThat(head).contains("Host: localhost:" + port() + "\r\n");
        assertThat(head).contains("User-Agent: book-exchange-link-check");
        assertThat(head).contains("Connection: close");
        assertThat(head.toLowerCase()).doesNotContain("cookie").doesNotContain("authorization").doesNotContain("referer");
    }

    @Test
    void anEmptyPathIsASlash() throws Exception {
        var served = serveOnce("HTTP/1.1 200 OK\r\n\r\n");
        transport.send("HEAD", URI.create("https://localhost:" + port()), InetAddress.getLoopbackAddress(), 1000, deadline(2000));
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
        Transport.Reply reply = transport.send("GET", url("/"), InetAddress.getLoopbackAddress(), 1000, deadline(3000));
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
        assertThatThrownBy(() -> transport.send("HEAD", url("/"), InetAddress.getLoopbackAddress(), 1000, deadline(300)))
                .isInstanceOf(IOException.class);
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(1500);
    }

    @Test
    void headersThatNeverEndAreRefused() {
        serveOnce("HTTP/1.1 200 OK\r\n" + "X-Filler: " + "a".repeat(40_000) + "\r\n\r\n");
        assertThatThrownBy(() -> transport.send("HEAD", url("/"), InetAddress.getLoopbackAddress(), 1000, deadline(2000)))
                .isInstanceOf(IOException.class).hasMessageContaining("too large");
    }

    @Test
    void somethingThatIsNotHttpIsRefused() {
        serveOnce("SSH-2.0-OpenSSH_9.6\r\n\r\n");
        assertThatThrownBy(() -> transport.send("HEAD", url("/"), InetAddress.getLoopbackAddress(), 1000, deadline(2000)))
                .isInstanceOf(IOException.class);
    }

    @Test
    void aClosedPortIsAnIoError() throws Exception {
        int closed = port();
        server.close();
        assertThatThrownBy(() -> transport.send("HEAD", URI.create("https://localhost:" + closed + "/"),
                InetAddress.getLoopbackAddress(), 500, deadline(1000))).isInstanceOf(IOException.class);
    }
}
