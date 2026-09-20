package com.swarmcron.test;

import com.swarmcron.cluster.FailureDetector;
import com.swarmcron.config.JobSpec;
import com.swarmcron.net.PeerAddress;
import com.swarmcron.sim.SimSwarmNode;
import com.swarmcron.sim.SimWorld;
import com.swarmcron.util.Assert;
import com.swarmcron.util.TestSuite;

import java.util.List;

/**
 * Deterministic (seeded) simulation of scenario 5 (concurrent job edits under
 * a partition) using the exact production Membership/GossipEngine/
 * FailureDetector/JobRegistry/AntiEntropySync classes SimSwarmNode wires up.
 */
final class JobRegistrySimTest {

    static boolean run() {
        return new TestSuite("JobRegistrySimTest")
                .test("anti-entropy syncs a new job from one node to another", JobRegistrySimTest::syncsNewJob)
                .test("two partitioned nodes diverge, then converge identically once the partition heals", JobRegistrySimTest::partitionedEditsConverge)
                .run();
    }

    private static FailureDetector.Config config() {
        return new FailureDetector.Config(1000, 300, 3, 5);
    }

    private static JobSpec spec(String schedule, String overlapPolicy) {
        return new JobSpec("nightly-backup", schedule, List.of("/bin/backup"), "/", 3600, 1, 1000, overlapPolicy, true);
    }

    private static void syncsNewJob() {
        SimWorld world = new SimWorld(0, 3);
        PeerAddress a = new PeerAddress("sim", 1);
        PeerAddress b = new PeerAddress("sim", 2);
        long antiEntropyIntervalMillis = 5000;

        SimSwarmNode alpha = new SimSwarmNode(world, "alpha", a, List.of(b), config(), "alpha".hashCode(), antiEntropyIntervalMillis, 128);
        SimSwarmNode beta = new SimSwarmNode(world, "beta", b, List.of(a), config(), "beta".hashCode(), antiEntropyIntervalMillis, 128);

        world.advanceTo(2000);
        alpha.jobRegistry.put(spec("0 2 * * *", JobSpec.OVERLAP_SKIP));

        Assert.that(beta.jobRegistry.get("nightly-backup") == null, "beta should not know about the job yet");

        world.advanceTo(2000 + antiEntropyIntervalMillis + 100);

        Assert.that(beta.jobRegistry.get("nightly-backup") != null, "beta should have learned about the job via anti-entropy");
        Assert.equals("0 2 * * *", beta.jobRegistry.get("nightly-backup").spec().schedule(), "beta's copy should match alpha's spec");
    }

    private static void partitionedEditsConverge() {
        SimWorld world = new SimWorld(0, 9);
        PeerAddress a = new PeerAddress("sim", 1);
        PeerAddress b = new PeerAddress("sim", 2);
        long antiEntropyIntervalMillis = 5000;

        SimSwarmNode alpha = new SimSwarmNode(world, "alpha", a, List.of(b), config(), "alpha".hashCode(), antiEntropyIntervalMillis, 128);
        SimSwarmNode beta = new SimSwarmNode(world, "beta", b, List.of(a), config(), "beta".hashCode(), antiEntropyIntervalMillis, 128);

        world.advanceTo(5000);
        world.network().blockPair(a, b);

        alpha.jobRegistry.put(spec("0 2 * * *", JobSpec.OVERLAP_SKIP));
        beta.jobRegistry.put(spec("0 3 * * *", JobSpec.OVERLAP_QUEUE));

        world.advanceTo(world.clock().nowMillis() + antiEntropyIntervalMillis * 2);
        Assert.that(
                !alpha.jobRegistry.get("nightly-backup").spec().equals(beta.jobRegistry.get("nightly-backup").spec()),
                "the two nodes should still disagree while the partition holds");

        world.network().unblockPair(a, b);
        world.advanceTo(world.clock().nowMillis() + antiEntropyIntervalMillis * 3);

        JobSpec alphaFinal = alpha.jobRegistry.get("nightly-backup").spec();
        JobSpec betaFinal = beta.jobRegistry.get("nightly-backup").spec();
        Assert.equals(alphaFinal, betaFinal, "both nodes should converge to the identical spec once the partition heals");
    }
}
