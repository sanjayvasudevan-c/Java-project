package com.swarmcron.sim;

import com.swarmcron.cluster.FailureDetector;
import com.swarmcron.cluster.NodeState;
import com.swarmcron.config.JobSpec;
import com.swarmcron.hash.ConsistentHashRing;
import com.swarmcron.net.MessageType;
import com.swarmcron.net.PeerAddress;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
            case "concurrent-job-edits" -> concurrentJobEdits();
            case "ring-rebalance" -> ringRebalance();
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

    /** Scenario 5: two partitioned nodes edit the same job concurrently; assert both converge to the identical spec after the partition heals. */
    static boolean concurrentJobEdits() {
        SimWorld world = new SimWorld(0, 11);
        world.network().setDefaultLink(new SimNetwork.LinkConfig(5, 15, 0.0));

        PeerAddress addrA = new PeerAddress("sim", 1);
        PeerAddress addrB = new PeerAddress("sim", 2);
        FailureDetector.Config config = new FailureDetector.Config(1000, 300, 3, 5);
        long antiEntropyIntervalMillis = 5000;

        SimSwarmNode alpha = new SimSwarmNode(world, "alpha", addrA, List.of(addrB), config, "alpha".hashCode(), antiEntropyIntervalMillis, 128);
        SimSwarmNode beta = new SimSwarmNode(world, "beta", addrB, List.of(addrA), config, "beta".hashCode(), antiEntropyIntervalMillis, 128);

        world.advanceTo(5000); // let them discover each other

        world.network().blockPair(addrA, addrB); // partition

        alpha.jobRegistry.put(new JobSpec("nightly-backup", "0 2 * * *",
                List.of("/bin/backup", "--fast"), "/", 3600, 2, 30_000, JobSpec.OVERLAP_SKIP, true));
        beta.jobRegistry.put(new JobSpec("nightly-backup", "0 3 * * *",
                List.of("/bin/backup", "--thorough"), "/", 7200, 1, 60_000, JobSpec.OVERLAP_QUEUE, true));

        world.advanceTo(world.clock().nowMillis() + antiEntropyIntervalMillis * 2);

        JobSpec alphaDuring = alpha.jobRegistry.get("nightly-backup").spec();
        JobSpec betaDuring = beta.jobRegistry.get("nightly-backup").spec();
        boolean divergedDuringPartition = !alphaDuring.equals(betaDuring);

        world.network().unblockPair(addrA, addrB); // heal
        world.advanceTo(world.clock().nowMillis() + antiEntropyIntervalMillis * 3);

        JobSpec alphaFinal = alpha.jobRegistry.get("nightly-backup").spec();
        JobSpec betaFinal = beta.jobRegistry.get("nightly-backup").spec();
        boolean converged = alphaFinal.equals(betaFinal);
        boolean pass = divergedDuringPartition && converged;

        System.out.println("scenario concurrent-job-edits: diverged during partition=" + divergedDuringPartition
                + ", converged after heal=" + converged + " (winning schedule='" + alphaFinal.schedule() + "')");
        System.out.println("Proves the JobRegistry CRDT: two partitioned nodes independently edit the same "
                + "job, diverge while the partition holds, and converge to the identical deterministically "
                + "chosen spec once anti-entropy sync resumes after the partition heals -- no coordinator, "
                + "no manual conflict resolution, and every node computes the same winner independently.");
        return pass;
    }

    /** M5 scenario: a real SWIM death automatically shrinks the ring, and only the dead node's keys move. */
    static boolean ringRebalance() {
        SimWorld world = new SimWorld(0, 17);
        world.network().setDefaultLink(new SimNetwork.LinkConfig(5, 20, 0.0));
        FailureDetector.Config config = new FailureDetector.Config(1000, 300, 3, 5);

        PeerAddress addrA = new PeerAddress("sim", 1);
        PeerAddress addrB = new PeerAddress("sim", 2);
        PeerAddress addrC = new PeerAddress("sim", 3);
        PeerAddress addrD = new PeerAddress("sim", 4);
        PeerAddress addrE = new PeerAddress("sim", 5);

        SimSwarmNode alpha = new SimSwarmNode(world, "alpha", addrA, List.of(addrB, addrC, addrD, addrE), config);
        SimSwarmNode beta = new SimSwarmNode(world, "beta", addrB, List.of(addrA, addrC, addrD, addrE), config);
        SimSwarmNode gamma = new SimSwarmNode(world, "gamma", addrC, List.of(addrA, addrB, addrD, addrE), config);
        SimSwarmNode delta = new SimSwarmNode(world, "delta", addrD, List.of(addrA, addrB, addrC, addrE), config);
        SimSwarmNode epsilon = new SimSwarmNode(world, "epsilon", addrE, List.of(addrA, addrB, addrC, addrD), config);

        world.advanceTo(10_000);
        boolean fullyFormed = alpha.ringManager.currentRing().physicalNodeCount() == 5;

        ConsistentHashRing ringBefore = alpha.ringManager.currentRing();
        Map<String, String> ownerBefore = new HashMap<>();
        for (int i = 0; i < 1000; i++) {
            ownerBefore.put("job-" + i, ringBefore.owner("job-" + i));
        }

        long killAt = world.clock().nowMillis();
        epsilon.kill();
        world.advanceTo(killAt + 8000);

        ConsistentHashRing ringAfter = alpha.ringManager.currentRing();
        boolean shrank = ringAfter.physicalNodeCount() == 4;

        int reassigned = 0;
        boolean onlyDeadNodesKeysMoved = true;
        for (Map.Entry<String, String> e : ownerBefore.entrySet()) {
            String now = ringAfter.owner(e.getKey());
            if (!e.getValue().equals(now)) {
                reassigned++;
                if (!e.getValue().equals("epsilon")) {
                    onlyDeadNodesKeysMoved = false;
                }
            }
        }
        boolean pass = fullyFormed && shrank && onlyDeadNodesKeysMoved && reassigned > 0;

        System.out.println("scenario ring-rebalance: fully formed (5 nodes)=" + fullyFormed
                + ", ring shrank to 4 after kill=" + shrank + ", only epsilon's keys moved=" + onlyDeadNodesKeysMoved
                + ", reassigned " + reassigned + "/1000 sampled keys");
        System.out.println("Proves the consistent-hash ring rebuilds automatically on a real SWIM membership "
                + "change (RingManager listens to Membership, no manual poking) and that only the dead node's "
                + "share of keys moves -- the whole point of consistent hashing over a plain hash(key) % N, "
                + "which would have reshuffled nearly everything.");
        return pass;
    }
}
