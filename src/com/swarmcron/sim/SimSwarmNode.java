package com.swarmcron.sim;

import com.swarmcron.cluster.FailureDetector;
import com.swarmcron.cluster.GossipEngine;
import com.swarmcron.cluster.Membership;
import com.swarmcron.net.PeerAddress;

import java.util.List;

/**
 * Wires the real production cluster components (Membership, GossipEngine,
 * FailureDetector) to a simulated Transport/Scheduler/Clock from one
 * SimWorld. This is not a stand-in for those components -- it is the exact
 * same classes Main constructs, just given Sim* dependencies instead of
 * Udp/System ones, so a scenario proves actual production behavior rather
 * than a simulated approximation of it.
 */
public final class SimSwarmNode {

    public final String nodeId;
    public final PeerAddress address;
    public final Membership membership;
    public final GossipEngine gossipEngine;
    public final FailureDetector failureDetector;
    private final SimTransport transport;

    public SimSwarmNode(SimWorld world, String nodeId, PeerAddress address, List<PeerAddress> seeds, FailureDetector.Config config) {
        this(world, nodeId, address, seeds, config, nodeId.hashCode());
    }

    /** Overload taking an explicit random seed for the failure detector's own target/relay selection, for scenarios that need full control. */
    public SimSwarmNode(SimWorld world, String nodeId, PeerAddress address, List<PeerAddress> seeds,
                         FailureDetector.Config config, long randomSeed) {
        this.nodeId = nodeId;
        this.address = address;
        this.membership = new Membership(nodeId, address, world.clock());
        this.gossipEngine = new GossipEngine(membership);
        this.transport = new SimTransport(address, world.network());
        this.failureDetector = new FailureDetector(
                membership, transport, gossipEngine, world.scheduler(), world.clock(), config, seeds, randomSeed);
        transport.start(failureDetector::onMessage);
        failureDetector.start();
    }

    /** Simulates a crash: stops sending/receiving, but any already-scheduled timers on other nodes keep firing normally. */
    public void kill() {
        transport.stop();
    }
}
