package com.swarmcron.test;

import com.swarmcron.clock.HybridTimestamp;
import com.swarmcron.clock.VectorClock;
import com.swarmcron.config.JobSpec;
import com.swarmcron.state.JobEntry;
import com.swarmcron.state.MergeEngine;
import com.swarmcron.util.Assert;
import com.swarmcron.util.TestSuite;

import java.util.List;

final class MergeEngineTest {

    static boolean run() {
        return new TestSuite("MergeEngineTest")
                .test("a later hybrid timestamp wins", MergeEngineTest::laterTimestampWins)
                .test("an earlier hybrid timestamp loses", MergeEngineTest::earlierTimestampLoses)
                .test("equal timestamps break the tie by nodeId, deterministically both ways", MergeEngineTest::equalTimestampsBreakTieByNodeId)
                .run();
    }

    private static JobEntry entry(String writer, long physicalMillis, long logicalCounter) {
        JobSpec spec = new JobSpec("job-a", "* * * * *", List.of("/bin/true"), "/", 60, 0, 0, JobSpec.OVERLAP_SKIP, true);
        return new JobEntry("job-a", spec, VectorClock.empty().tick(writer), false, writer, new HybridTimestamp(physicalMillis, logicalCounter));
    }

    private static void laterTimestampWins() {
        JobEntry current = entry("alpha", 1000, 0);
        JobEntry incoming = entry("beta", 2000, 0);
        Assert.that(MergeEngine.incomingWins(current, incoming), "the strictly later timestamp should win regardless of nodeId");
    }

    private static void earlierTimestampLoses() {
        JobEntry current = entry("zeta", 2000, 0);
        JobEntry incoming = entry("alpha", 1000, 0);
        Assert.that(!MergeEngine.incomingWins(current, incoming), "the strictly earlier timestamp should lose regardless of nodeId");
    }

    private static void equalTimestampsBreakTieByNodeId() {
        JobEntry alphaEntry = entry("alpha", 5000, 3);
        JobEntry betaEntry = entry("beta", 5000, 3);

        // Same comparison from both directions must agree on the same absolute winner (beta, since "beta" > "alpha").
        Assert.that(MergeEngine.incomingWins(alphaEntry, betaEntry), "beta should win over alpha at an identical timestamp");
        Assert.that(!MergeEngine.incomingWins(betaEntry, alphaEntry), "alpha should not win over beta at an identical timestamp");
    }
}
