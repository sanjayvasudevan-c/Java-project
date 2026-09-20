package com.swarmcron.http;

import com.sun.net.httpserver.HttpExchange;
import com.swarmcron.clock.Scheduler;
import com.swarmcron.util.Json;
import com.swarmcron.util.Log;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Server-Sent Events fan-out for the dashboard: each connected browser holds
 * one HTTP response open indefinitely, and broadcast() pushes one "data: ..."
 * line to every connection's queue. A connection occupies one thread from
 * the server's executor for its entire lifetime (blocked in take()), which
 * is why HttpApiServer gives the server an unbounded cached thread pool
 * rather than the single-threaded default -- otherwise one open dashboard
 * tab would starve every other request.
 *
 * A periodic keep-alive comment line (SSE ignores lines starting with ':')
 * is what detects a client that vanished without a clean close: the very
 * next write after the socket died throws IOException, which is when we
 * actually notice and clean up -- up to one keep-alive interval late, an
 * accepted simplification for a dashboard rather than a protocol needing
 * tight resource bounds.
 */
public final class SseHub {

    private static final long KEEPALIVE_INTERVAL_MILLIS = 15_000;

    private record Client(BlockingQueue<String> queue, Thread thread) {}

    private final Map<Long, Client> clients = new ConcurrentHashMap<>();
    private final AtomicLong idGenerator = new AtomicLong();

    public SseHub(Scheduler scheduler) {
        scheduler.scheduleAtFixedRate(this::sendKeepAlive, KEEPALIVE_INTERVAL_MILLIS, KEEPALIVE_INTERVAL_MILLIS);
    }

    /** Blocks for the lifetime of the connection; call from an HttpHandler registered on the executor's pool, never the accept thread. */
    public void handle(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.getResponseHeaders().add("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(200, 0); // 0 = chunked, unknown total length

        long id = idGenerator.incrementAndGet();
        BlockingQueue<String> queue = new LinkedBlockingQueue<>();
        clients.put(id, new Client(queue, Thread.currentThread()));
        OutputStream out = exchange.getResponseBody();
        try {
            out.write(": connected\n\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            while (true) {
                String event = queue.take();
                out.write(event.getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            Log.debug("http", "SSE client %d disconnected: %s", id, e.getMessage());
        } finally {
            clients.remove(id);
            exchange.close();
        }
    }

    public void broadcast(String type, Object payload) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", type);
        body.put("payload", payload);
        String event = "data: " + Json.write(body) + "\n\n";
        for (Client c : clients.values()) {
            c.queue().offer(event);
        }
    }

    public int connectedClients() {
        return clients.size();
    }

    /** Interrupts every open connection's blocked take(), letting each handler thread unwind and close its exchange. */
    public void closeAll() {
        for (Client c : clients.values()) {
            c.thread().interrupt();
        }
    }

    private void sendKeepAlive() {
        for (Client c : clients.values()) {
            c.queue().offer(": ping\n\n");
        }
    }
}
