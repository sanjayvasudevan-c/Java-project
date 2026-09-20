package com.swarmcron.sim;

import com.swarmcron.clock.HybridClock;
import com.swarmcron.cluster.FailureDetector;
import com.swarmcron.cluster.GossipEngine;
import com.swarmcron.cluster.Membership;
import com.swarmcron.net.PeerAddress;
import com.swarmcron.state.AntiEntropySync;
import com.swarmcron.state.JobRegistry;

import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Wires the real production node components (Membership, GossipEngine,
 * FailureDetector, JobRegistry, AntiEntropySync) to simulated Transport/
 * SyncChannel/Scheduler/Clock from one SimWorld. This is not a stand-in for
 * those components -- it is the exact same classes Main constructs, just
 * given Sim* dependencies instead of Udp/Tcp/System ones, so a scenario
 * proves actual production behavior rather than a simulated approximation
 * of it.
 */
public final class SimSwarmNode {

    private static final long DEFAULT_ANTI_ENTROPY_INTERVAL_MILLIS = 30_000;

    public final String nodeId;
    public final PeerAddress address;
    public final Membership membership;
    public final GossipEngine gossipEngine;
    public final FailureDetector failureDetector;
    public final JobRegistry jobRegistry;
    public final AntiEntropySync antiEntropySync;
    private final SimTransport transport;
    private final SimSyncChannel syncChannel;

    public SimSwarmNode(SimWorld world, String nodeId, PeerAddress address, List<PeerAddress> seeds, FailureDetector.Config config) {
        this(world, nodeId, address, seeds, config, nodeId.hashCode(), DEFAULT_ANTI_ENTROPY_INTERVAL_MILLIS);
    }

    /** Overload taking an explicit random seed and anti-entropy interval, for scenarios that need full control over timing. */
    public SimSwarmNode(SimWorld world, String nodeId, PeerAddress address, List<PeerAddress> seeds,
                         FailureDetector.Config config, long randomSeed, long antiEntropyIntervalMillis) {
        this.nodeId = nodeId;
        this.address = address;
        this.membership = new Membership(nodeId, address, world.clock());
        this.gossipEngine = new GossipEngine(membership);
        this.transport = new SimTransport(address, world.network());
        this.failureDetector = new FailureDetector(
                membership, transport, gossipEngine, world.scheduler(), world.clock(), config, seeds, randomSeed);
        transport.start(failureDetector::onMessage);
        failureDetector.start();

        HybridClock hybridClock = new HybridClock(world.clock());
        this.jobRegistry = new JobRegistry(nodeId, hybridClock);
        this.syncChannel = world.createSyncChannel(address);
        this.antiEntropySync = new AntiEntropySync(
                membership, jobRegistry, syncChannel, world.scheduler(), antiEntropyIntervalMillis, randomSeed + 1,
                UnaryOperator.identity());
        antiEntropySync.start();
    }

    /** Simulates a crash: stops sending/receiving on both channels, but any already-scheduled timers on other nodes keep firing normally. */
    public void kill() {
        transport.stop();
        syncChannel.stop();
    }
}
