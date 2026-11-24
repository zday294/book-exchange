package com.bookexchange.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** A minimal browser EventSource for tests: opens the live stream (optionally with a name) and queues what arrives. */
public final class SseTestClient implements AutoCloseable {

    public record Message(String event, String data) {}

    public final BlockingQueue<Message> messages = new LinkedBlockingQueue<>();
    public final int status;
    private final InputStream body;

    public SseTestClient(int port, String exchangeCode, String name) throws Exception {
        String url = "http://localhost:" + port + "/api/exchanges/" + exchangeCode + "/events"
                + (name == null ? "" : "?name=" + URLEncoder.encode(name, StandardCharsets.UTF_8));
        HttpResponse<InputStream> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(url)).header("Accept", "text/event-stream").build(),
                HttpResponse.BodyHandlers.ofInputStream());
        status = response.statusCode();
        body = response.body();
        if (status == 200) {
            Thread reader = new Thread(this::read, "sse-test-reader");
            reader.setDaemon(true);
            reader.start();
        }
    }

    private void read() {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String event = null;
            StringBuilder data = new StringBuilder();
            String line;
            while ((line = in.readLine()) != null) {
                if (line.startsWith("event:")) {
                    event = line.substring(6).strip();
                } else if (line.startsWith("data:")) {
                    data.append(line.substring(5).strip());
                } else if (line.isEmpty() && event != null) {
                    messages.add(new Message(event, data.toString()));
                    event = null;
                    data.setLength(0);
                }
            }
        } catch (IOException closed) {
            // the test closed the connection
        }
    }

    /** Waits for an event with this name, skipping others, and returns it. */
    public Message waitFor(String name, int seconds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        List<String> skipped = new ArrayList<>();
        while (System.nanoTime() < deadline) {
            Message m = messages.poll(100, TimeUnit.MILLISECONDS);
            if (m == null) continue;
            if (m.event().equals(name)) return m;
            skipped.add(m.event());
        }
        throw new AssertionError("no '" + name + "' event within " + seconds + " seconds (saw: " + skipped + ")");
    }

    /** Everything that arrives in the next moment. */
    public List<Message> drain(long millis) throws InterruptedException {
        Thread.sleep(millis);
        List<Message> out = new ArrayList<>();
        messages.drainTo(out);
        return out;
    }

    @Override
    public void close() {
        try {
            body.close();
        } catch (IOException ignored) {
            // already closed
        }
    }

    /** Fails unless the connection opened and announced itself. */
    public SseTestClient readyOrFail() throws InterruptedException {
        assertThat(status).isEqualTo(200);
        assertThat(waitFor("ready", 5)).isNotNull();
        return this;
    }
}
