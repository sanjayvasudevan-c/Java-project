package com.swarmcron.test;

import com.swarmcron.cluster.FailureDetector;
import com.swarmcron.config.JobSpec;
import com.swarmcron.net.PeerAddress;
import com.swarmcron.sim.SimNetwork;
import com.swarmcron.sim.SimSwarmNode;
import com.swarmcron.sim.SimWorld;
import com.swarmcron.store.WalRecord;
import com.swarmcron.store.WriteAheadLog;
import com.swarmcron.util.Assert;
import com.swarmcron.util.TestSuite;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Exercises the M8 executor end to end using the real production classes
 * (JobExecutor, ProcessRunner, WriteAheadLog, ClaimRegistry, RunLease) wired
 * through SimSwarmNode, covering the three scenarios M8's spec calls out:
 * happy path, rolling restart, and owner-dies-mid-run (takeover). Ownership
 * gating and the DEGRADED refusal are also covered directly since they're
 * what those scenarios rely on to be meaningful.
 *
 * ProcessRunner always spawns a real OS process regardless of which Clock a
 * node runs on (see its javadoc), so these tests are the one place in the
 * suite that waits on real wall-clock time (a short, bounded real poll) for
 * a background job to actually finish -- everything about scheduling,
 * ownership, and claims leading up to that point is still fully deterministic
 * and driven by world.advanceTo().
 */
final class JobExecutorSimTest {

    static boolean run() {
        return new TestSuite("JobExecutorSimTest")
                .test("a single node runs its own job to completion and journals it", JobExecutorSimTest::happyPath)
                .test("only the ring owner executes a job shared by the whole cluster", JobExecutorSimTest::ownershipGating)
                .test("a degraded (minority) node never executes, even once it locally believes it owns everything", JobExecutorSimTest::degradedNodeNeverExecutes)
                .test("when the owner dies before running an overdue job, a new owner takes over via the ring-change path", JobExecutorSimTest::takeoverAfterOwnerDies)
                .test("a node resumes correctly after a restart against the same data directory", JobExecutorSimTest::rollingRestartResumesCorrectly)
                .run();
    }

    private static final FailureDetector.Config FD_CONFIG = new FailureDetector.Config(1000, 300, 3, 5);

    private static JobSpec everySecond(String id, List<String> command) {
        return new JobSpec(id, "*/1 * * * * *", command, ".", 5, 0, 0, JobSpec.OVERLAP_SKIP, true);
    }

    private static JobSpec hourly(String id, List<String> command) {
        return new JobSpec(id, "0 0 * * * *", command, ".", 5, 0, 0, JobSpec.OVERLAP_SKIP, true);
    }

    private static List<WalRecord> readWal(Path dataDir) {
        return new WriteAheadLog(dataDir.resolve("run.wal")).readAll();
    }

    private static boolean hasEvent(Path dataDir, String jobId, WalRecord.Event event) {
        for (WalRecord r : readWal(dataDir)) {
            if (r.jobId().equals(jobId) && r.event() == event) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasEventAfter(Path dataDir, String jobId, WalRecord.Event event, long afterMillis) {
        for (WalRecord r : readWal(dataDir)) {
            if (r.jobId().equals(jobId) && r.event() == event && r.scheduledFireMillis() > afterMillis) {
                return true;
            }
        }
        return false;
    }

    /** Real-time poll (not simulated) since real subprocess completion doesn't advance on VirtualClock. Bounded and short: every test command here is near-instant. */
    private static void waitUntil(BooleanSupplier condition, long timeoutMillis, String failureMessage) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Assert.fail("interrupted while waiting: " + failureMessage);
            }
        }
        Assert.fail("timed out waiting: " + failureMessage);
    }

    private static void happyPath() {
        SimWorld world = new SimWorld(System.currentTimeMillis(), 101);
        world.network().setDefaultLink(new SimNetwork.LinkConfig(5, 20, 0.0));
        PeerAddress addr = new PeerAddress("sim", 1);
        SimSwarmNode node = new SimSwarmNode(world, "alpha", addr, List.of(), FD_CONFIG);

        world.advanceBy(7000); // single-node cluster self-elects leader (initial election timeout headroom)
        node.jobRegistry.put(everySecond("job-happy", List.of("/bin/true")));
        world.advanceBy(3000);

        waitUntil(() -> hasEvent(node.dataDir, "job-happy", WalRecord.Event.COMPLETED), 5000,
                "job-happy should complete on its only node");
    }

    private static void ownershipGating() {
        SimWorld world = new SimWorld(System.currentTimeMillis(), 102);
        world.network().setDefaultLink(new SimNetwork.LinkConfig(5, 20, 0.0));
        List<SimSwarmNode> nodes = threeNodeCluster(world, 102);
        world.advanceBy(10_000); // bootstrap, election, and ring settle

        JobSpec spec = everySecond("job-owned", List.of("/bin/true"));
        for (SimSwarmNode n : nodes) {
            n.jobRegistry.put(spec); // seed identically everywhere; this test is about ownership, not registry sync (see M4)
        }
        String ownerId = nodes.get(0).ringManager.currentRing().owner("job-owned");
        world.advanceBy(3000);

        SimSwarmNode owner = nodeById(nodes, ownerId);
        waitUntil(() -> hasEvent(owner.dataDir, "job-owned", WalRecord.Event.COMPLETED), 5000,
                "the ring owner should have run job-owned");

        for (SimSwarmNode n : nodes) {
            if (!n.nodeId.equals(ownerId)) {
                Assert.that(!hasEvent(n.dataDir, "job-owned", WalRecord.Event.STARTED),
                        "non-owner " + n.nodeId + " should never have attempted job-owned");
            }
        }
    }

    private static void degradedNodeNeverExecutes() {
        SimWorld world = new SimWorld(System.currentTimeMillis(), 103);
        world.network().setDefaultLink(new SimNetwork.LinkConfig(5, 20, 0.0));
        List<SimSwarmNode> nodes = threeNodeCluster(world, 103);
        SimSwarmNode isolated = nodes.get(0);
        world.advanceBy(10_000);

        isolated.jobRegistry.put(everySecond("job-degraded", List.of("/bin/true")));

        for (int i = 1; i < nodes.size(); i++) {
            world.network().blockPair(isolated.address, nodes.get(i).address);
        }
        // Long enough for SWIM to mark the other two DEAD from the isolated node's point of
        // view, which shrinks ITS local ring to itself alone -- it would then believe it owns
        // job-degraded outright. isDegraded() must still refuse to run it regardless.
        world.advanceBy(18_000);

        Assert.that(isolated.raftLite.isDegraded(), "the isolated single node should be DEGRADED (1 alive out of 3 known)");
        Assert.equals(isolated.nodeId, isolated.ringManager.currentRing().owner("job-degraded"),
                "the isolated node's own ring view should (incorrectly, in isolation) show it as owner");
        Assert.that(!hasEvent(isolated.dataDir, "job-degraded", WalRecord.Event.STARTED),
                "a degraded node must never start a run, even one it locally believes it owns");
        Assert.that(hasEvent(isolated.dataDir, "job-degraded", WalRecord.Event.SKIPPED),
                "the degraded refusal should be journaled as a SKIPPED record");
    }

    private static void takeoverAfterOwnerDies() {
        SimWorld world = new SimWorld(System.currentTimeMillis(), 104);
        world.network().setDefaultLink(new SimNetwork.LinkConfig(5, 20, 0.0));
        List<SimSwarmNode> nodes = threeNodeCluster(world, 104);
        world.advanceBy(10_000);

        // Hourly, not every-second: within this test's short window, the only way ANY node
        // other than the original owner ever runs this job is via the ring-change-triggered
        // immediate takeover path (JobExecutor.onRingChanged), since each node's own routine
        // per-job timer wouldn't naturally fire again for up to an hour of simulated time.
        JobSpec spec = hourly("job-takeover", List.of("/bin/true"));
        for (SimSwarmNode n : nodes) {
            n.jobRegistry.put(spec);
        }
        String originalOwnerId = nodes.get(0).ringManager.currentRing().owner("job-takeover");
        SimSwarmNode originalOwner = nodeById(nodes, originalOwnerId);

        long killAt = world.clock().nowMillis();
        originalOwner.kill();
        world.advanceTo(killAt + 20_000); // SWIM DEAD detection + ring rebuild + raft re-election + claim round trip

        SimSwarmNode survivor = nodes.stream().filter(n -> !n.nodeId.equals(originalOwnerId)).findFirst().orElseThrow();
        String newOwnerId = survivor.ringManager.currentRing().owner("job-takeover");
        Assert.that(!newOwnerId.equals(originalOwnerId), "ownership of job-takeover should have moved off the dead node");

        SimSwarmNode newOwner = nodeById(nodes, newOwnerId);
        waitUntil(() -> hasEventAfter(newOwner.dataDir, "job-takeover", WalRecord.Event.COMPLETED, killAt), 5000,
                "the new owner should have taken over and completed job-takeover after the original owner died");
    }

    private static void rollingRestartResumesCorrectly() throws IOException {
        SimWorld world = new SimWorld(System.currentTimeMillis(), 105);
        world.network().setDefaultLink(new SimNetwork.LinkConfig(5, 20, 0.0));
        PeerAddress addr = new PeerAddress("sim", 1);
        Path dataDir = Files.createTempDirectory("swarmcron-restart-test");

        SimSwarmNode first = new SimSwarmNode(world, "alpha", addr, List.of(), FD_CONFIG,
                "alpha".hashCode(), 30_000, 128, dataDir);
        world.advanceBy(7000);
        first.jobRegistry.put(everySecond("job-restart", List.of("/bin/true")));
        world.advanceBy(3000);
        waitUntil(() -> hasEvent(dataDir, "job-restart", WalRecord.Event.COMPLETED), 5000,
                "job-restart should complete at least once before the restart");

        long restartAt = world.clock().nowMillis();
        first.kill();

        // "Restart": same node id/address/dataDir, fresh SimSwarmNode instance, same SimWorld
        // (so the virtual clock keeps advancing rather than resetting) -- exactly what Recovery
        // exists to make safe, mirroring a real process restart against the same disk.
        SimSwarmNode second = new SimSwarmNode(world, "alpha", addr, List.of(), FD_CONFIG,
                "alpha".hashCode(), 30_000, 128, dataDir);
        world.advanceBy(7000); // re-election as a "new" process, then job registry re-seeds and re-arms

        // The registry itself isn't persisted (that's JobRegistry/anti-entropy's job, tested in
        // M4); a real restart re-seeds it from jobs.json, which this simulates directly.
        second.jobRegistry.put(everySecond("job-restart", List.of("/bin/true")));
        world.advanceBy(3000);

        waitUntil(() -> hasEventAfter(dataDir, "job-restart", WalRecord.Event.COMPLETED, restartAt), 5000,
                "job-restart should resume completing after the restart");
    }

    private static List<SimSwarmNode> threeNodeCluster(SimWorld world, long seed) {
        List<PeerAddress> addrs = List.of(new PeerAddress("sim", 1), new PeerAddress("sim", 2), new PeerAddress("sim", 3));
        List<String> ids = List.of("n1", "n2", "n3");
        List<SimSwarmNode> nodes = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            List<PeerAddress> seeds = new ArrayList<>(addrs);
            seeds.remove(i);
            nodes.add(new SimSwarmNode(world, ids.get(i), addrs.get(i), seeds, FD_CONFIG, seed + i, 30_000, 128));
        }
        return nodes;
    }

    private static SimSwarmNode nodeById(List<SimSwarmNode> nodes, String id) {
        return nodes.stream().filter(n -> n.nodeId.equals(id)).findFirst()
                .orElseThrow(() -> new AssertionError("no node with id " + id));
    }
}
