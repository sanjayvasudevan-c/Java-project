package com.swarmcron.test;

import com.swarmcron.clock.SystemClock;
import com.swarmcron.clock.HybridClock;
import com.swarmcron.clock.SystemScheduler;
import com.swarmcron.cluster.Membership;
import com.swarmcron.config.JobSpec;
import com.swarmcron.election.InMemoryTermStore;
import com.swarmcron.election.RaftLite;
import com.swarmcron.exec.JobExecutor;
import com.swarmcron.hash.RingManager;
import com.swarmcron.http.HttpApiServer;
import com.swarmcron.net.MessageHandler;
import com.swarmcron.net.MessageType;
import com.swarmcron.net.PeerAddress;
import com.swarmcron.net.Transport;
import com.swarmcron.state.JobRegistry;
import com.swarmcron.store.Recovery;
import com.swarmcron.store.WriteAheadLog;
import com.swarmcron.util.Assert;
import com.swarmcron.util.Json;
import com.swarmcron.util.TestSuite;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Exercises HttpApiServer's real HTTP/SSE surface over a real loopback
 * socket using java.net.http.HttpClient (JDK-provided, not an external
 * dependency). Wires one real, unstarted-Raft single-node cluster: since
 * these tests are about the HTTP/JSON/SSE plumbing rather than distributed
 * execution behavior (already covered end to end by JobExecutorSimTest),
 * they never call raftLite.start(), which would otherwise force every test
 * to wait out a real multi-second election timeout for no benefit here --
 * a single unstarted RaftLite already reports the correct single-node
 * defaults (FOLLOWER, term 0, no leader, not degraded).
 */
final class HttpApiServerTest {

    static boolean run() {
        return new TestSuite("HttpApiServerTest")
                .test("GET /api/status reports raft/ring/self shape", HttpApiServerTest::statusEndpoint)
                .test("GET /api/nodes lists the self node", HttpApiServerTest::nodesEndpoint)
                .test("POST /api/jobs creates a job visible via GET, with owner and no lastRun yet", HttpApiServerTest::createAndListJob)
                .test("DELETE /api/jobs/{id} removes it from the list", HttpApiServerTest::deleteJob)
                .test("GET /api/runs starts empty", HttpApiServerTest::runsEndpointStartsEmpty)
                .test("GET / serves the dashboard page", HttpApiServerTest::dashboardServed)
                .test("GET /events delivers an SSE event when a job is created", HttpApiServerTest::sseDeliversJobEvent)
                .run();
    }

    /** No-op Transport: these tests never exercise cross-node claim traffic, only the HTTP-facing read/write surface. */
    private static final class NoopTransport implements Transport {
        private final PeerAddress address;
        NoopTransport(PeerAddress address) { this.address = address; }
        @Override public void start(MessageHandler handler) { }
        @Override public void send(PeerAddress to, MessageType type, byte[] payload) { }
        @Override public PeerAddress localAddress() { return address; }
        @Override public void stop() { }
    }

    private record Harness(HttpApiServer server, JobRegistry jobRegistry, HttpClient client, String baseUrl) {}

    private static Harness start() throws IOException {
        PeerAddress address = new PeerAddress("localhost", 0);
        Membership membership = new Membership("alpha", address, SystemClock.INSTANCE);
        RingManager ringManager = new RingManager(membership, 128);
        ringManager.start();
        SystemScheduler scheduler = new SystemScheduler("alpha-http-test");
        RaftLite raftLite = new RaftLite(membership, new NoopTransport(address), scheduler, new InMemoryTermStore(), 1);
        // Deliberately not started: see class javadoc.

        JobRegistry jobRegistry = new JobRegistry("alpha", new HybridClock(SystemClock.INSTANCE));
        Path dataDir = Files.createTempDirectory("swarmcron-http-test");
        WriteAheadLog wal = new WriteAheadLog(dataDir.resolve("run.wal"));
        Recovery.Recovered recovered = new Recovery.Recovered(Map.of(), java.util.Set.of());
        JobExecutor jobExecutor = new JobExecutor("alpha", membership, ringManager, raftLite, jobRegistry,
                SystemClock.INSTANCE, scheduler, new NoopTransport(address), wal, recovered,
                ZoneId.systemDefault(), Executors.newFixedThreadPool(2));
        jobExecutor.start();

        HttpApiServer server = new HttpApiServer(new InetSocketAddress("localhost", 0), "alpha",
                membership, ringManager, raftLite, jobRegistry, jobExecutor, scheduler);
        server.start();
        String baseUrl = "http://localhost:" + server.boundAddress().getPort();
        HttpClient client = HttpClient.newHttpClient();
        return new Harness(server, jobRegistry, client, baseUrl);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> getJson(Harness h, String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(h.baseUrl() + path)).GET().build();
        HttpResponse<String> resp = h.client().send(req, HttpResponse.BodyHandlers.ofString());
        Assert.equals(200, resp.statusCode(), "GET " + path + " status");
        return (Map<String, Object>) Json.parse(resp.body());
    }

    @SuppressWarnings("unchecked")
    private static List<Object> getJsonList(Harness h, String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(h.baseUrl() + path)).GET().build();
        HttpResponse<String> resp = h.client().send(req, HttpResponse.BodyHandlers.ofString());
        Assert.equals(200, resp.statusCode(), "GET " + path + " status");
        return (List<Object>) Json.parse(resp.body());
    }

    private static void statusEndpoint() throws Exception {
        Harness h = start();
        try {
            Map<String, Object> body = getJson(h, "/api/status");
            Assert.equals("alpha", body.get("selfId"), "selfId");
            Map<String, Object> raft = (Map<String, Object>) body.get("raft");
            Assert.equals("FOLLOWER", raft.get("role"), "unstarted raft defaults to FOLLOWER");
            Assert.equals(0.0, raft.get("term"), "term starts at 0");
            Assert.equals(null, raft.get("leaderId"), "no leader yet");
            Assert.equals(false, raft.get("degraded"), "a lone known node is its own majority");
            Map<String, Object> ring = (Map<String, Object>) body.get("ring");
            Assert.equals(1.0, ring.get("physicalNodeCount"), "ring has just self");
        } finally {
            h.server().stop();
        }
    }

    private static void nodesEndpoint() throws Exception {
        Harness h = start();
        try {
            List<Object> nodes = getJsonList(h, "/api/nodes");
            Assert.equals(1, nodes.size(), "one known node (self)");
            Map<String, Object> self = (Map<String, Object>) nodes.get(0);
            Assert.equals("alpha", self.get("nodeId"), "self node id");
            Assert.equals("ALIVE", self.get("state"), "self is always ALIVE");
        } finally {
            h.server().stop();
        }
    }

    @SuppressWarnings("unchecked")
    private static void createAndListJob() throws Exception {
        Harness h = start();
        try {
            Map<String, Object> spec = Map.of(
                    "id", "nightly-report", "schedule", "0 0 * * * *",
                    "command", List.of("/bin/true"));
            HttpRequest post = HttpRequest.newBuilder(URI.create(h.baseUrl() + "/api/jobs"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(Json.write(spec)))
                    .build();
            HttpResponse<String> postResp = h.client().send(post, HttpResponse.BodyHandlers.ofString());
            Assert.equals(200, postResp.statusCode(), "POST /api/jobs status");

            List<Object> jobs = getJsonList(h, "/api/jobs");
            Assert.equals(1, jobs.size(), "one job listed");
            Map<String, Object> job = (Map<String, Object>) jobs.get(0);
            Assert.equals("nightly-report", job.get("id"), "job id");
            Assert.equals("alpha", job.get("owner"), "the only node in the ring owns every job");
            Assert.equals(null, job.get("lastRun"), "no run has happened yet");
        } finally {
            h.server().stop();
        }
    }

    @SuppressWarnings("unchecked")
    private static void deleteJob() throws Exception {
        Harness h = start();
        try {
            h.jobRegistry().put(new JobSpec("to-delete", "0 0 * * * *", List.of("/bin/true"), ".", 0, 0, 0, JobSpec.OVERLAP_SKIP, true));
            Assert.equals(1, getJsonList(h, "/api/jobs").size(), "job present before delete");

            HttpRequest del = HttpRequest.newBuilder(URI.create(h.baseUrl() + "/api/jobs/to-delete")).DELETE().build();
            HttpResponse<Void> resp = h.client().send(del, HttpResponse.BodyHandlers.discarding());
            Assert.equals(204, resp.statusCode(), "DELETE status");

            Assert.equals(0, getJsonList(h, "/api/jobs").size(), "job gone after delete (tombstoned)");
        } finally {
            h.server().stop();
        }
    }

    private static void runsEndpointStartsEmpty() throws Exception {
        Harness h = start();
        try {
            Assert.equals(0, getJsonList(h, "/api/runs").size(), "no runs recorded yet");
        } finally {
            h.server().stop();
        }
    }

    private static void dashboardServed() throws Exception {
        Harness h = start();
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(h.baseUrl() + "/")).GET().build();
            HttpResponse<String> resp = h.client().send(req, HttpResponse.BodyHandlers.ofString());
            Assert.equals(200, resp.statusCode(), "dashboard status");
            Assert.that(resp.body().contains("SwarmCron"), "dashboard HTML should mention SwarmCron");
            Assert.that(resp.body().contains("EventSource"), "dashboard should wire up the SSE client");
        } finally {
            h.server().stop();
        }
    }

    private static void sseDeliversJobEvent() throws Exception {
        Harness h = start();
        BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        Thread reader = new Thread(() -> {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(h.baseUrl() + "/events")).GET().build();
                HttpResponse<java.util.stream.Stream<String>> resp = h.client().send(req, HttpResponse.BodyHandlers.ofLines());
                resp.body().forEach(lines::offer);
            } catch (Exception ignored) {
                // Expected once the test closes the server underneath this thread's blocking read.
            }
        }, "sse-reader");
        reader.setDaemon(true);
        reader.start();
        try {
            // Wait for the ": connected" preamble so we know the SSE handler is actually attached
            // before we act, otherwise the put() below could race ahead of the subscription.
            waitForLineContaining(lines, "connected", 3000);

            h.jobRegistry().put(new JobSpec("sse-job", "0 0 * * * *", List.of("/bin/true"), ".", 0, 0, 0, JobSpec.OVERLAP_SKIP, true));

            String dataLine = waitForLineContaining(lines, "\"type\":\"job\"", 3000);
            Assert.that(dataLine.contains("sse-job"), "the job event should reference the job id");
        } finally {
            h.server().stop();
            reader.interrupt();
        }
    }

    private static String waitForLineContaining(BlockingQueue<String> lines, String needle, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            String line = lines.poll(200, TimeUnit.MILLISECONDS);
            if (line != null && line.contains(needle)) {
                return line;
            }
        }
        Assert.fail("timed out waiting for an SSE line containing: " + needle);
        return null; // unreachable
    }
}
