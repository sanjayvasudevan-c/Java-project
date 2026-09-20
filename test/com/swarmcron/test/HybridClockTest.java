package com.swarmcron.test;

import com.swarmcron.clock.HybridClock;
import com.swarmcron.clock.HybridTimestamp;
import com.swarmcron.util.Assert;
import com.swarmcron.util.TestSuite;

final class HybridClockTest {

    static boolean run() {
        return new TestSuite("HybridClockTest")
                .test("consecutive ticks are strictly increasing", HybridClockTest::consecutiveTicksIncrease)
                .test("ticks at the same physical millisecond bump the logical counter", HybridClockTest::sameMillisecondBumpsCounter)
                .test("witness advances past a remote timestamp that is ahead", HybridClockTest::witnessAdvancesPastAheadRemote)
                .test("witness does not regress when local is already ahead", HybridClockTest::witnessDoesNotRegress)
                .run();
    }

    private static void consecutiveTicksIncrease() {
        long[] now = {1000};
        HybridClock clock = new HybridClock(() -> now[0]);
        HybridTimestamp t1 = clock.tick();
        now[0] = 2000;
        HybridTimestamp t2 = clock.tick();
        Assert.that(t1.compareTo(t2) < 0, "later tick should compare greater");
    }

    private static void sameMillisecondBumpsCounter() {
        long[] now = {1000};
        HybridClock clock = new HybridClock(() -> now[0]);
        HybridTimestamp t1 = clock.tick();
        HybridTimestamp t2 = clock.tick(); // physical time hasn't moved
        Assert.equals(t1.physicalMillis(), t2.physicalMillis(), "physical millis unchanged");
        Assert.that(t2.logicalCounter() > t1.logicalCounter(), "logical counter should bump when physical time stalls");
        Assert.that(t1.compareTo(t2) < 0, "t2 should still compare strictly after t1");
    }

    private static void witnessAdvancesPastAheadRemote() {
        long[] now = {1000};
        HybridClock local = new HybridClock(() -> now[0]);
        local.tick();
        HybridTimestamp remoteAhead = new HybridTimestamp(5000, 3);
        HybridTimestamp witnessed = local.witness(remoteAhead);
        Assert.that(witnessed.compareTo(remoteAhead) > 0, "witnessing a remote timestamp should produce something strictly after it");
    }

    private static void witnessDoesNotRegress() {
        long[] now = {10_000};
        HybridClock local = new HybridClock(() -> now[0]);
        HybridTimestamp localTs = local.tick();
        HybridTimestamp remoteBehind = new HybridTimestamp(1, 0);
        HybridTimestamp witnessed = local.witness(remoteBehind);
        Assert.that(witnessed.compareTo(localTs) > 0, "witnessing a stale remote timestamp should not move the clock backwards");
    }
}
