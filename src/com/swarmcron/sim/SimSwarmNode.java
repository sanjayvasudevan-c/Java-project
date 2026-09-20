package com.swarmcron.sim;

import com.swarmcron.clock.HybridClock;
import com.swarmcron.cluster.FailureDetector;
import com.swarmcron.cluster.GossipEngine;
import com.swarmcron.cluster.Membership;
import com.swarmcron.election.InMemoryTermStore;
import com.swarmcron.election.RaftLite;
import com.swarmcron.exec.JobExecutor;
import com.swarmcron.hash.RingManager;
import com.swarmcron.net.PeerAddress;
import com.swarmcron.state.AntiEntropySync;
import com.swarmcron.state.JobRegistry;
import com.swarmcron.store.Recovery;
import com.swarmcron.store.Snapshot;
import com.swarmcron.store.WriteAheadLog;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.UnaryOperator;

/**
 * Wires the real production node components (Membership, GossipEngine,
 * FailureDetector, RaftLite, JobRegistry, AntiEntropySync, RingManager,
 * JobExecutor) to simulated Transport/SyncChannel/Scheduler/Clock from one
 * SimWorld. This is not a stand-in for those components -- it is the exact
 * same classes Main constructs, just given Sim* dependencies instead of
 * Udp/Tcp/System ones, so a scenario proves actual production behavior
 * rather than a simulated approximation of it. JobExecutor's ProcessRunner
 * is the one exception (see its own javadoc): it always spawns a real OS
 * process regardless of which Transport/Clock a node was built with, so sim
 * scenarios that exercise execution use trivial, near-instant commands.
 */
public final class SimSwarmNode {

    private static final long DEFAULT_ANTI_ENTROPY_INTERVAL_MILLIS = 30_000;
    private static final int DEFAULT_VIRTUAL_NODES_PER_NODE = 128;
    private static final long DEFAULT_COMPACTION_INTERVAL_MILLIS = 60_000;

    public final String nodeId;
    public final PeerAddress address;
    public final Path dataDir;
    public final Membership membership;
    public final GossipEngine gossipEngine;
    public final FailureDetector failureDetector;
    public final RaftLite raftLite;
    public final JobRegistry jobRegistry;
    public final AntiEntropySync antiEntropySync;
    public final RingManager ringManager;
    public final JobExecutor jobExecutor;
    private final SimTransport transport;
    private final SimSyncChannel syncChannel;
    private final WriteAheadLog wal;

    public SimSwarmNode(SimWorld world, String nodeId, PeerAddress address, List<PeerAddress> seeds, FailureDetector.Config config) {
        this(world, nodeId, address, seeds, config, nodeId.hashCode(), DEFAULT_ANTI_ENTROPY_INTERVAL_MILLIS,
                DEFAULT_VIRTUAL_NODES_PER_NODE, defaultDataDir(nodeId));
    }

    /** Overload taking an explicit random seed, anti-entropy interval, and virtual node count, for scenarios that need full control over timing/ring shape. */
    public SimSwarmNode(SimWorld world, String nodeId, PeerAddress address, List<PeerAddress> seeds,
                         FailureDetector.Config config, long randomSeed, long antiEntropyIntervalMillis, int virtualNodesPerNode) {
        this(world, nodeId, address, seeds, config, randomSeed, antiEntropyIntervalMillis, virtualNodesPerNode, defaultDataDir(nodeId));
    }

    /**
     * Full overload additionally taking an explicit on-disk data directory
     * (WAL + snapshot). A scenario simulates "this node restarts" by calling
     * kill() on one instance and constructing a fresh SimSwarmNode against
     * the SAME dataDir -- Recovery then reads back whatever the first
     * instance's WAL/snapshot left behind, exactly as a real process restart
     * would.
     */
    public SimSwarmNode(SimWorld world, String nodeId, PeerAddress address, List<PeerAddress> seeds,
                         FailureDetector.Config config, long randomSeed, long antiEntropyIntervalMillis,
                         int virtualNodesPerNode, Path dataDir) {
        this.nodeId = nodeId;
        this.address = address;
        this.dataDir = dataDir;
        this.membership = new Membership(nodeId, address, world.clock());
        this.gossipEngine = new GossipEngine(membership);
        this.transport = new SimTransport(address, world.network());
        this.failureDetector = new FailureDetector(
                membership, transport, gossipEngine, world.scheduler(), world.clock(), config, seeds, randomSeed);
        this.raftLite = new RaftLite(membership, transport, world.scheduler(), new InMemoryTermStore(), randomSeed + 2);

        HybridClock hybridClock = new HybridClock(world.clock());
        this.jobRegistry = new JobRegistry(nodeId, hybridClock);
        this.syncChannel = world.createSyncChannel(address);
        this.antiEntropySync = new AntiEntropySync(
                membership, jobRegistry, syncChannel, world.scheduler(), antiEntropyIntervalMillis, randomSeed + 1,
                UnaryOperator.identity());
        antiEntropySync.start();

        this.ringManager = new RingManager(membership, virtualNodesPerNode);
        ringManager.start();

        this.wal = new WriteAheadLog(dataDir.resolve("run.wal"));
        Snapshot snapshot = new Snapshot(dataDir.resolve("run.snapshot"));
        Recovery.Recovered recovered = Recovery.recover(snapshot, wal);
        // Daemon: dozens of SimSwarmNodes get constructed across the whole test suite and most
        // scenarios never call kill() on every one of them, so a non-daemon pool here would
        // leave live threads behind that keep the test JVM from ever exiting after main()
        // returns. Harmless in production too -- Main's own main thread blocks forever on
        // CountDownLatch.await(), so it never reaches a natural exit for daemon status to affect.
        ExecutorService jobWorkerPool = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "job-worker-" + nodeId);
            t.setDaemon(true);
            return t;
        });
        this.jobExecutor = new JobExecutor(nodeId, membership, ringManager, raftLite, jobRegistry,
                world.clock(), world.scheduler(), transport, wal, recovered, ZoneId.systemDefault(), jobWorkerPool);

        // One transport, one inbound stream: dispatch by message type to whichever
        // component owns it -- same pattern as Main, so a scenario exercises the
        // exact same dispatch logic production runs.
        transport.start((from, type, payload) -> {
            switch (type) {
                case PING, ACK, PING_REQ -> failureDetector.onMessage(from, type, payload);
                case REQUEST_VOTE, VOTE, HEARTBEAT -> raftLite.onMessage(from, type, payload);
                case RUN_CLAIM, CLAIM_GRANT, RUN_RESULT -> jobExecutor.onMessage(from, type, payload);
                default -> { /* not ours */ }
            }
        });
        failureDetector.start();
        raftLite.start();
        jobExecutor.start();
    }

    private static Path defaultDataDir(String nodeId) {
        try {
            return Files.createTempDirectory("swarmcron-sim-" + nodeId + "-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Simulates a crash: stops sending/receiving on both channels and the job
     * worker pool, but any already-scheduled timers on other nodes keep
     * firing normally. Also closes the WAL file handle -- a real process
     * exit releases every file descriptor it held, and a "restart" against
     * the same dataDir (a fresh SimSwarmNode instance) opens its own
     * WriteAheadLog on the same path; leaving the old one's file channel
     * open would let two independently-positioned writers corrupt each
     * other's appends to the same file.
     */
    public void kill() {
        transport.stop();
        syncChannel.stop();
        jobExecutor.stop();
        wal.close();
    }
}
