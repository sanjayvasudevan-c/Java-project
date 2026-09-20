package com.swarmcron.sim;

import com.swarmcron.cluster.FailureDetector;
import com.swarmcron.cluster.NodeState;
import com.swarmcron.net.MessageType;
import com.swarmcron.net.PeerAddress;

import java.util.ArrayList;
import java.util.List;

/**
 * Entry point for simulation scenarios (run-sim.sh &lt;scenario&gt;). Each
 * scenario builds a SimWorld plus some simulated nodes, drives time forward
 * with SimWorld.advanceTo, asserts an invariant, and prints a PASS/FAIL line
 * plus a short summary of what it proved. The scenario roster grows as later
 * milestones add CRDT sync, elections and execution (see M11 in the design
 * doc for the full list: happy path, rolling restart, partition,
 * owner-dies-mid-run, concurrent edits, packet-loss soak).
 */
public final class ClusterSim {

    public static void main(String[] args) {
        String scenario = args.length > 0 ? args[0] : "ping-pong";
        boolean ok = switch (scenario) {
            case "ping-pong" -> pingPong();
            case "swim-failure-detection" -> swimFailureDetection();
            default -> {
                System.out.println("Unknown scenario: " + scenario);
                yield false;
            }
        };
        System.out.println(ok ? "PASS" : "FAIL");
        if (!ok) {
            System.exit(1);
        }
    }

    /** M2 smoke scenario: two SimTransport nodes, one PING, one ACK, entirely clock-driven. */
    static boolean pingPong() {
        SimWorld world = new SimWorld(0, 42);
        world.network().setDefaultLink(new SimNetwork.LinkConfig(5, 15, 0.0));

        PeerAddress a = new PeerAddress("sim", 1);
        PeerAddress b = new PeerAddress("sim", 2);

        List<MessageType> aReceived = new ArrayList<>();
        List<MessageType> bReceived = new ArrayList<>();

        SimTransport ta = new SimTransport(a, world.network());
        SimTransport tb = new SimTransport(b, world.network());

        ta.start((from, type, payload) -> aReceived.add(type));
        tb.start((from, type, payload) -> {
            bReceived.add(type);
            if (type == MessageType.PING) {
                tb.send(from, MessageType.ACK, new byte[0]);
            }
        });

        ta.send(b, MessageType.PING, new byte[0]);
        world.advanceTo(1000);

        boolean bGotPing = bReceived.contains(MessageType.PING);
        boolean aGotAck = aReceived.contains(MessageType.ACK);
        boolean pass = bGotPing && aGotAck;

        System.out.println("scenario ping-pong: node b received PING=" + bGotPing + ", node a received ACK=" + aGotAck
                + " (clock at t=" + world.clock().nowMillis() + "ms)");
        System.out.println("Proves that SimTransport delivers messages through SimNetwork with simulated "
                + "latency, driven entirely by VirtualClock.advanceTo  -  no real threads, sockets, or timers "
                + "are involved anywhere in this exchange.");
        return pass;
    }

    /** M3 scenario: 3-node cluster discovers itself via seed bootstrap, then one node is killed and the survivors converge on DEAD within 6s. */
    static boolean swimFailureDetection() {
        SimWorld world = new SimWorld(0, 7);
        world.network().setDefaultLink(new SimNetwork.LinkConfig(5, 20, 0.0));

        PeerAddress addrA = new PeerAddress("sim", 1);
        PeerAddress addrB = new PeerAddress("sim", 2);
        PeerAddress addrC = new PeerAddress("sim", 3);
        FailureDetector.Config config = new FailureDetector.Config(1000, 300, 3, 5);

        SimSwarmNode alpha = new SimSwarmNode(world, "alpha", addrA, List.of(addrB, addrC), config);
        SimSwarmNode beta = new SimSwarmNode(world, "beta", addrB, List.of(addrA, addrC), config);
        SimSwarmNode gamma = new SimSwarmNode(world, "gamma", addrC, List.of(addrA, addrB), config);

        world.advanceTo(10_000);
        boolean discovered = alpha.membership.aliveCount() == 3
                && beta.membership.aliveCount() == 3
                && gamma.membership.aliveCount() == 3;

        long killAt = world.clock().nowMillis();
        gamma.kill();
        world.advanceTo(killAt + 6000);

        boolean aSeesDead = alpha.membership.get("gamma") != null && alpha.membership.get("gamma").state() == NodeState.DEAD;
        boolean bSeesDead = beta.membership.get("gamma") != null && beta.membership.get("gamma").state() == NodeState.DEAD;
        boolean pass = discovered && aSeesDead && bSeesDead;

        System.out.println("scenario swim-failure-detection: discovered all 3 nodes=" + discovered
                + ", alpha sees gamma DEAD=" + aSeesDead + ", beta sees gamma DEAD=" + bSeesDead
                + " (" + (world.clock().nowMillis() - killAt) + "ms after kill)");
        System.out.println("Proves SWIM failure detection end to end: seed bootstrap discovery, random "
                + "probing, indirect ping-req fallback, suspicion timeout, and gossip-propagated DEAD "
                + "state, all driven by VirtualClock/SimScheduler with no real time elapsed.");
        return pass;
    }
}
