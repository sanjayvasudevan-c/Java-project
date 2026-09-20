package com.swarmcron.test;

import com.swarmcron.cluster.FailureDetector;
import com.swarmcron.hash.ConsistentHashRing;
import com.swarmcron.net.PeerAddress;
import com.swarmcron.sim.SimSwarmNode;
import com.swarmcron.sim.SimWorld;
import com.swarmcron.util.Assert;
import com.swarmcron.util.TestSuite;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Proves RingManager end to end: a real SWIM failure detection (kill a node,
 * survivors mark it DEAD) automatically triggers a ring rebuild via the
 * Membership listener, with no manual poking -- and the resulting ring only
 * reassigns the keys the dead node owned.
 */
final class RingManagerSimTest {

    static boolean run() {
        return new TestSuite("RingManagerSimTest")
                .test("ring includes all discovered nodes once the cluster stabilizes", RingManagerSimTest::ringReflectsDiscoveredCluster)
                .test("killing a node shrinks the ring and only reassigns the keys it owned", RingManagerSimTest::ringRebalancesOnDeath)
                .run();
    }

    private static FailureDetector.Config config() {
        return new FailureDetector.Config(1000, 300, 3, 5);
    }

    private static void ringReflectsDiscoveredCluster() {
        SimWorld world = new SimWorld(0, 21);
        PeerAddress a = new PeerAddress("sim", 1);
        PeerAddress b = new PeerAddress("sim", 2);
        PeerAddress c = new PeerAddress("sim", 3);

        SimSwarmNode alpha = new SimSwarmNode(world, "alpha", a, List.of(b, c), config());
        SimSwarmNode beta = new SimSwarmNode(world, "beta", b, List.of(a, c), config());
        SimSwarmNode gamma = new SimSwarmNode(world, "gamma", c, List.of(a, b), config());

        world.advanceTo(10_000);

        Assert.equals(3, alpha.ringManager.currentRing().physicalNodeCount(), "alpha's ring should include all 3 discovered nodes");
        Assert.equals(3, beta.ringManager.currentRing().physicalNodeCount(), "beta's ring should include all 3 discovered nodes");
        Assert.equals(3, gamma.ringManager.currentRing().physicalNodeCount(), "gamma's ring should include all 3 discovered nodes");
        Assert.equals(alpha.ringManager.currentRing().owner("job-x"), beta.ringManager.currentRing().owner("job-x"),
                "all nodes should agree on ownership once they share the same membership view");
    }

    private static void ringRebalancesOnDeath() {
        SimWorld world = new SimWorld(0, 22);
        PeerAddress a = new PeerAddress("sim", 1);
        PeerAddress b = new PeerAddress("sim", 2);
        PeerAddress c = new PeerAddress("sim", 3);
        PeerAddress d = new PeerAddress("sim", 4);
        PeerAddress e = new PeerAddress("sim", 5);

        SimSwarmNode alpha = new SimSwarmNode(world, "alpha", a, List.of(b, c, d, e), config());
        SimSwarmNode beta = new SimSwarmNode(world, "beta", b, List.of(a, c, d, e), config());
        SimSwarmNode gamma = new SimSwarmNode(world, "gamma", c, List.of(a, b, d, e), config());
        SimSwarmNode delta = new SimSwarmNode(world, "delta", d, List.of(a, b, c, e), config());
        SimSwarmNode epsilon = new SimSwarmNode(world, "epsilon", e, List.of(a, b, c, d), config());

        world.advanceTo(10_000);
        Assert.equals(5, alpha.ringManager.currentRing().physicalNodeCount(), "cluster should be fully formed before killing anything");

        List<String> keys = new ArrayList<>();
        Map<String, String> ownerBefore = new HashMap<>();
        ConsistentHashRing ringBefore = alpha.ringManager.currentRing();
        for (int i = 0; i < 1000; i++) {
            String key = "job-" + i;
            keys.add(key);
            ownerBefore.put(key, ringBefore.owner(key));
        }

        long killAt = world.clock().nowMillis();
        epsilon.kill();
        world.advanceTo(killAt + 8000); // SWIM detection + suspicion timeout, with margin

        Assert.equals(4, alpha.ringManager.currentRing().physicalNodeCount(), "the ring should shrink to 4 physical nodes after epsilon is marked DEAD");

        ConsistentHashRing ringAfter = alpha.ringManager.currentRing();
        int reassigned = 0;
        for (String key : keys) {
            String was = ownerBefore.get(key);
            String now = ringAfter.owner(key);
            if (!was.equals(now)) {
                reassigned++;
                Assert.equals("epsilon", was, "only keys previously owned by the dead node should have moved");
            }
        }
        Assert.that(reassigned > 0, "epsilon should have owned at least some of the sampled keys");
    }
}
