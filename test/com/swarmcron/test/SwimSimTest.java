package com.swarmcron.test;

import com.swarmcron.cluster.FailureDetector;
import com.swarmcron.cluster.MemberInfo;
import com.swarmcron.cluster.NodeState;
import com.swarmcron.net.PeerAddress;
import com.swarmcron.sim.SimNetwork;
import com.swarmcron.sim.SimSwarmNode;
import com.swarmcron.sim.SimWorld;
import com.swarmcron.util.Assert;
import com.swarmcron.util.TestSuite;

import java.util.List;

/**
 * Deterministic (seeded) simulations of the real production SWIM stack --
 * SimSwarmNode wires the exact same Membership/GossipEngine/FailureDetector
 * classes Main uses, just against SimTransport/SimScheduler/VirtualClock.
 */
final class SwimSimTest {

    static boolean run() {
        return new TestSuite("SwimSimTest")
                .test("three nodes discover each other via seed bootstrap", SwimSimTest::discoversViaBootstrap)
                .test("a killed node is marked DEAD by survivors within 6s", SwimSimTest::detectsDeathWithinSixSeconds)
                .test("a lossy but not-dead link does not cause a false DEAD", SwimSimTest::lossyLinkDoesNotFalselyKill)
                .run();
    }

    private static FailureDetector.Config defaultConfig() {
        return new FailureDetector.Config(1000, 300, 3, 5);
    }

    private static void discoversViaBootstrap() {
        SimWorld world = new SimWorld(0, 1);
        PeerAddress a = new PeerAddress("sim", 1);
        PeerAddress b = new PeerAddress("sim", 2);
        PeerAddress c = new PeerAddress("sim", 3);
        SimSwarmNode alpha = new SimSwarmNode(world, "alpha", a, List.of(b, c), defaultConfig());
        SimSwarmNode beta = new SimSwarmNode(world, "beta", b, List.of(a, c), defaultConfig());
        SimSwarmNode gamma = new SimSwarmNode(world, "gamma", c, List.of(a, b), defaultConfig());

        world.advanceTo(5000);

        Assert.equals(3, alpha.membership.aliveCount(), "alpha should know about all 3 nodes");
        Assert.equals(3, beta.membership.aliveCount(), "beta should know about all 3 nodes");
        Assert.equals(3, gamma.membership.aliveCount(), "gamma should know about all 3 nodes");
    }

    private static void detectsDeathWithinSixSeconds() {
        SimWorld world = new SimWorld(0, 42);
        PeerAddress a = new PeerAddress("sim", 1);
        PeerAddress b = new PeerAddress("sim", 2);
        PeerAddress c = new PeerAddress("sim", 3);
        SimSwarmNode alpha = new SimSwarmNode(world, "alpha", a, List.of(b, c), defaultConfig());
        SimSwarmNode beta = new SimSwarmNode(world, "beta", b, List.of(a, c), defaultConfig());
        SimSwarmNode gamma = new SimSwarmNode(world, "gamma", c, List.of(a, b), defaultConfig());

        world.advanceTo(10_000);
        Assert.equals(3, alpha.membership.aliveCount(), "cluster should be fully formed before we kill anything");

        long killAt = world.clock().nowMillis();
        gamma.kill();
        world.advanceTo(killAt + 6000);

        Assert.equals(NodeState.DEAD, alpha.membership.get("gamma").state(), "alpha should see gamma DEAD within 6s");
        Assert.equals(NodeState.DEAD, beta.membership.get("gamma").state(), "beta should see gamma DEAD within 6s");
    }

    private static void lossyLinkDoesNotFalselyKill() {
        SimWorld world = new SimWorld(0, 5);
        world.network().setDefaultLink(new SimNetwork.LinkConfig(1, 10, 0.1));
        PeerAddress a = new PeerAddress("sim", 1);
        PeerAddress b = new PeerAddress("sim", 2);
        PeerAddress c = new PeerAddress("sim", 3);
        SimSwarmNode alpha = new SimSwarmNode(world, "alpha", a, List.of(b, c), defaultConfig());
        SimSwarmNode beta = new SimSwarmNode(world, "beta", b, List.of(a, c), defaultConfig());
        SimSwarmNode gamma = new SimSwarmNode(world, "gamma", c, List.of(a, b), defaultConfig());

        // Sample throughout rather than checking one instant: a brief ALIVE<->SUSPECT
        // flicker under lossy conditions is expected and self-corrects (that's the
        // point of refutation, and SUSPECT means "actively re-verifying," not
        // "wrong") -- what must never happen, and is this test's actual point, is
        // an escalation all the way to DEAD.
        for (long t = 1000; t <= 30_000; t += 1000) {
            world.advanceTo(t);
            MemberInfo alphaSeesBeta = alpha.membership.get("beta");
            MemberInfo betaSeesGamma = beta.membership.get("gamma");
            Assert.that(alphaSeesBeta == null || alphaSeesBeta.state() != NodeState.DEAD,
                    "10% loss with 3 nodes (indirect probing available) should never escalate to DEAD, t=" + t);
            Assert.that(betaSeesGamma == null || betaSeesGamma.state() != NodeState.DEAD,
                    "10% loss with 3 nodes (indirect probing available) should never escalate to DEAD, t=" + t);
        }
    }
}
