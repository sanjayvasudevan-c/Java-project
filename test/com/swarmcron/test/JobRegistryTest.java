package com.swarmcron.test;

import com.swarmcron.clock.HybridClock;
import com.swarmcron.config.JobSpec;
import com.swarmcron.state.JobEntry;
import com.swarmcron.state.JobRegistry;
import com.swarmcron.util.Assert;
import com.swarmcron.util.TestSuite;

import java.util.List;
import java.util.Map;

final class JobRegistryTest {

    static boolean run() {
        return new TestSuite("JobRegistryTest")
                .test("local put ticks this node's own clock dimension", JobRegistryTest::putTicksOwnDimension)
                .test("local delete tombstones rather than removing", JobRegistryTest::deleteTombstones)
                .test("merge applies a strictly-dominating incoming entry", JobRegistryTest::mergeAppliesDominatingEntry)
                .test("merge ignores a stale (dominated) incoming entry", JobRegistryTest::mergeIgnoresStaleEntry)
                .test("merge of two independently-created registries converges deterministically both ways", JobRegistryTest::concurrentEditsConvergeBothWays)
                .test("computeMissingFor reports entries the peer digest doesn't dominate", JobRegistryTest::computeMissingForReportsGaps)
                .test("JobEntry round-trips through JSON", JobRegistryTest::jobEntryJsonRoundTrip)
                .run();
    }

    private static JobSpec spec(String id, String schedule) {
        return new JobSpec(id, schedule, List.of("/bin/true"), "/", 60, 0, 0, JobSpec.OVERLAP_SKIP, true);
    }

    private static void putTicksOwnDimension() {
        JobRegistry registry = new JobRegistry("alpha", new HybridClock(() -> 1000L));
        JobEntry entry = registry.put(spec("job-a", "* * * * *"));
        Assert.equals(1L, entry.clock().get("alpha"), "first local put should tick alpha's dimension to 1");
        JobEntry updated = registry.put(spec("job-a", "*/5 * * * *"));
        Assert.equals(2L, updated.clock().get("alpha"), "second local put should tick again");
        Assert.equals("*/5 * * * *", registry.get("job-a").spec().schedule(), "registry should hold the latest local write");
    }

    private static void deleteTombstones() {
        JobRegistry registry = new JobRegistry("alpha", new HybridClock(() -> 1000L));
        registry.put(spec("job-a", "* * * * *"));
        JobEntry deleted = registry.delete("job-a");
        Assert.that(deleted.tombstone(), "delete should tombstone the entry");
        Assert.equals("job-a", deleted.spec().id(), "tombstone should retain the last known spec for diagnostics");
        Assert.equals(0, registry.activeSpecs().size(), "a tombstoned job should not appear in activeSpecs");
    }

    private static void mergeAppliesDominatingEntry() {
        JobRegistry a = new JobRegistry("alpha", new HybridClock(() -> 1000L));
        JobRegistry b = new JobRegistry("beta", new HybridClock(() -> 2000L));
        JobEntry fromB = b.put(spec("job-a", "0 3 * * *"));
        boolean changed = a.merge(fromB);
        Assert.that(changed, "merging a brand-new entry should report a change");
        Assert.equals("0 3 * * *", a.get("job-a").spec().schedule(), "a should now hold b's spec");
    }

    private static void mergeIgnoresStaleEntry() {
        JobRegistry a = new JobRegistry("alpha", new HybridClock(() -> 1000L));
        JobEntry v1 = a.put(spec("job-a", "0 2 * * *"));
        a.put(spec("job-a", "0 4 * * *")); // v2, dominates v1
        boolean changed = a.merge(v1); // re-deliver the stale v1
        Assert.that(!changed, "merging a dominated (stale) entry should report no change");
        Assert.equals("0 4 * * *", a.get("job-a").spec().schedule(), "the newer local write should survive");
    }

    private static void concurrentEditsConvergeBothWays() {
        JobRegistry alpha = new JobRegistry("alpha", new HybridClock(() -> 5000L));
        JobRegistry beta = new JobRegistry("beta", new HybridClock(() -> 5000L));
        JobEntry alphaEntry = alpha.put(spec("job-a", "0 2 * * *"));
        JobEntry betaEntry = beta.put(spec("job-a", "0 3 * * *"));

        Assert.that(alphaEntry.clock().concurrentWith(betaEntry.clock()), "independent edits with no prior sync should be concurrent");

        alpha.merge(betaEntry);
        beta.merge(alphaEntry);

        JobEntry alphaFinal = alpha.get("job-a");
        JobEntry betaFinal = beta.get("job-a");
        Assert.equals(alphaFinal.spec(), betaFinal.spec(), "both directions of merge should pick the identical winning spec");
        Assert.equals(alphaFinal.clock(), betaFinal.clock(), "both registries should end up with the same merged clock");
    }

    private static void computeMissingForReportsGaps() {
        JobRegistry registry = new JobRegistry("alpha", new HybridClock(() -> 1000L));
        registry.put(spec("job-a", "* * * * *"));
        registry.put(spec("job-b", "* * * * *"));

        List<JobEntry> missingFromEmptyPeer = registry.computeMissingFor(Map.of());
        Assert.equals(2, missingFromEmptyPeer.size(), "a peer with an empty digest is missing everything");

        Map<String, com.swarmcron.clock.VectorClock> peerDigest = Map.of("job-a", registry.get("job-a").clock());
        List<JobEntry> missing = registry.computeMissingFor(peerDigest);
        Assert.equals(1, missing.size(), "only job-b should be reported missing");
        Assert.equals("job-b", missing.get(0).jobId(), "the missing entry should be job-b");
    }

    private static void jobEntryJsonRoundTrip() {
        JobRegistry registry = new JobRegistry("alpha", new HybridClock(() -> 1000L));
        JobEntry entry = registry.put(spec("job-a", "0 2 * * *"));
        JobEntry restored = JobEntry.fromJson(entry.toJson());
        Assert.equals(entry, restored, "JobEntry should round-trip through JSON unchanged");
    }
}
