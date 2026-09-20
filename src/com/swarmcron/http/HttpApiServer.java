package com.swarmcron.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.swarmcron.clock.Scheduler;
import com.swarmcron.cluster.MemberInfo;
import com.swarmcron.cluster.Membership;
import com.swarmcron.config.JobSpec;
import com.swarmcron.election.RaftLite;
import com.swarmcron.exec.JobExecutor;
import com.swarmcron.exec.RunSummary;
import com.swarmcron.hash.ConsistentHashRing;
import com.swarmcron.hash.RingManager;
import com.swarmcron.state.JobEntry;
import com.swarmcron.state.JobRegistry;
import com.swarmcron.util.Json;
import com.swarmcron.util.Log;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Read/write JSON API plus an SSE event stream plus the dashboard page
 * (DashboardPage), all on one com.sun.net.httpserver.HttpServer -- the only
 * HTTP server implementation in the standard library, matching the
 * project's zero-external-dependency constraint. Every route is a thin
 * translation between the real cluster components (Membership, RingManager,
 * RaftLite, JobRegistry, JobExecutor) and JSON; there is no separate
 * "view model" layer since these objects' own accessors are already exactly
 * what the dashboard needs.
 *
 * The executor is an unbounded cached thread pool rather than HttpServer's
 * single-threaded default: an SSE connection (see SseHub) blocks its
 * handler thread for the connection's entire lifetime, so the default
 * executor would let one open dashboard tab starve every other request,
 * including the dashboard's own polling calls from a second tab.
 */
public final class HttpApiServer {

    private final HttpServer server;
    private final SseHub sseHub;
    private final ExecutorService executor;
    private final String selfId;
    private final Membership membership;
    private final RingManager ringManager;
    private final RaftLite raftLite;
    private final JobRegistry jobRegistry;
    private final JobExecutor jobExecutor;

    public HttpApiServer(InetSocketAddress bindAddress, String selfId, Membership membership, RingManager ringManager,
                          RaftLite raftLite, JobRegistry jobRegistry, JobExecutor jobExecutor, Scheduler scheduler) throws IOException {
        this.selfId = selfId;
        this.membership = membership;
        this.ringManager = ringManager;
        this.raftLite = raftLite;
        this.jobRegistry = jobRegistry;
        this.jobExecutor = jobExecutor;
        this.server = HttpServer.create(bindAddress, 0);
        this.sseHub = new SseHub(scheduler);
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "http-" + selfId);
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(executor);

        server.createContext("/", this::handleDashboard);
        server.createContext("/api/status", this::handleStatus);
        server.createContext("/api/nodes", this::handleNodes);
        server.createContext("/api/jobs", this::handleJobs);
        server.createContext("/api/runs", this::handleRuns);
        server.createContext("/events", sseHub::handle);

        membership.addListener(info -> sseHub.broadcast("membership", nodeJson(info)));
        ringManager.addListener(ring -> sseHub.broadcast("ring", ringJson(ring)));
        raftLite.addListener((role, term, leaderId) -> sseHub.broadcast("raft", raftJson()));
        jobRegistry.addListener(entry -> sseHub.broadcast("job", entry.jobId()));
        jobExecutor.addRunResultListener(summary -> sseHub.broadcast("run", summary.toJson()));
    }

    public void start() {
        server.start();
        Log.info("http", "[%s] dashboard/API listening on http://%s", selfId, server.getAddress());
    }

    public void stop() {
        sseHub.closeAll();
        server.stop(0);
        executor.shutdownNow();
    }

    public int connectedSseClients() {
        return sseHub.connectedClients();
    }

    /** The address actually bound, including the real port when constructed with port 0 (as tests do). Only meaningful after start(). */
    public InetSocketAddress boundAddress() {
        return server.getAddress();
    }

    // ---- routes ----

    private void handleDashboard(HttpExchange exchange) throws IOException {
        if (!"/".equals(exchange.getRequestURI().getPath())) {
            sendText(exchange, 404, "not found");
            return;
        }
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendText(exchange, 405, "method not allowed");
            return;
        }
        byte[] bytes = DashboardPage.HTML.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void handleStatus(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendText(exchange, 405, "method not allowed");
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("selfId", selfId);
        body.put("raft", raftJson());
        body.put("ring", ringJson(ringManager.currentRing()));
        body.put("jobCount", jobRegistry.activeSpecs().size());
        sendJson(exchange, 200, body);
    }

    private void handleNodes(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendText(exchange, 405, "method not allowed");
            return;
        }
        List<Object> out = new ArrayList<>();
        for (MemberInfo m : membership.all()) {
            out.add(nodeJson(m));
        }
        sendJson(exchange, 200, out);
    }

    private void handleJobs(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        if (path.equals("/api/jobs")) {
            if ("GET".equals(method)) {
                listJobs(exchange);
            } else if ("POST".equals(method)) {
                createOrUpdateJob(exchange);
            } else {
                sendText(exchange, 405, "method not allowed");
            }
            return;
        }
        if (path.startsWith("/api/jobs/")) {
            String id = URLDecoder.decode(path.substring("/api/jobs/".length()), StandardCharsets.UTF_8);
            if ("DELETE".equals(method) && !id.isEmpty()) {
                jobRegistry.delete(id);
                sendText(exchange, 204, "");
            } else {
                sendText(exchange, 405, "method not allowed");
            }
            return;
        }
        sendText(exchange, 404, "not found");
    }

    private void listJobs(HttpExchange exchange) throws IOException {
        List<Object> out = new ArrayList<>();
        ConsistentHashRing ring = ringManager.currentRing();
        Map<String, RunSummary> latest = jobExecutor.latestRunByJobId();
        for (JobEntry entry : jobRegistry.all()) {
            if (entry.tombstone() || entry.spec() == null) {
                continue;
            }
            JobSpec spec = entry.spec();
            Map<String, Object> j = new LinkedHashMap<>(spec.toJson());
            j.put("owner", ring.owner(spec.id()));
            RunSummary lastRun = latest.get(spec.id());
            j.put("lastRun", lastRun == null ? null : lastRun.toJson());
            out.add(j);
        }
        sendJson(exchange, 200, out);
    }

    @SuppressWarnings("unchecked")
    private void createOrUpdateJob(HttpExchange exchange) throws IOException {
        byte[] bodyBytes = exchange.getRequestBody().readAllBytes();
        try {
            Map<String, Object> body = (Map<String, Object>) Json.parse(new String(bodyBytes, StandardCharsets.UTF_8));
            JobSpec spec = JobSpec.fromJson(body);
            JobEntry entry = jobRegistry.put(spec);
            sendJson(exchange, 200, entry.spec().toJson());
        } catch (RuntimeException e) {
            sendJson(exchange, 400, Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    private void handleRuns(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendText(exchange, 405, "method not allowed");
            return;
        }
        List<Object> out = new ArrayList<>();
        for (RunSummary r : jobExecutor.recentRuns()) {
            out.add(r.toJson());
        }
        sendJson(exchange, 200, out);
    }

    // ---- JSON shaping ----

    private Map<String, Object> nodeJson(MemberInfo m) {
        Map<String, Object> j = new LinkedHashMap<>();
        j.put("nodeId", m.nodeId());
        j.put("address", m.address().toString());
        j.put("state", m.state().name());
        j.put("incarnation", m.incarnation());
        j.put("lastChangedMillis", m.lastChangedMillis());
        return j;
    }

    private Map<String, Object> ringJson(ConsistentHashRing ring) {
        Map<String, Object> j = new LinkedHashMap<>();
        j.put("physicalNodeCount", ring.physicalNodeCount());
        j.put("virtualNodeCount", ring.virtualNodes().size());
        j.put("physicalNodes", ring.physicalNodes());
        return j;
    }

    private Map<String, Object> raftJson() {
        Map<String, Object> j = new LinkedHashMap<>();
        j.put("role", raftLite.role().name());
        j.put("term", raftLite.currentTerm());
        j.put("leaderId", raftLite.leaderId());
        j.put("degraded", raftLite.isDegraded());
        return j;
    }

    // ---- response helpers ----

    private void sendJson(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] bytes = Json.write(value).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void sendText(HttpExchange exchange, int status, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0) {
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
            return;
        }
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
