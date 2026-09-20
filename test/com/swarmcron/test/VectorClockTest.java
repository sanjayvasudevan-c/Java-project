package com.swarmcron.test;

import com.swarmcron.clock.VectorClock;
import com.swarmcron.util.Assert;
import com.swarmcron.util.TestSuite;

import java.util.Map;

final class VectorClockTest {

    static boolean run() {
        return new TestSuite("VectorClockTest")
                .test("tick increments only the ticking node's counter", VectorClockTest::tickIncrementsOwnDimension)
                .test("a clock happens before its own tick", VectorClockTest::selfBeforeTick)
                .test("two independently-ticked empty clocks are concurrent", VectorClockTest::independentTicksAreConcurrent)
                .test("merge takes the elementwise max", VectorClockTest::mergeTakesElementwiseMax)
                .test("equal clocks compare EQUAL", VectorClockTest::equalClocksAreEqual)
                .test("round-trips through JSON", VectorClockTest::jsonRoundTrip)
                .run();
    }

    private static void tickIncrementsOwnDimension() {
        VectorClock c = VectorClock.empty().tick("a").tick("a").tick("b");
        Assert.equals(2L, c.get("a"), "a's counter");
        Assert.equals(1L, c.get("b"), "b's counter");
        Assert.equals(0L, c.get("c"), "unseen node defaults to 0");
    }

    private static void selfBeforeTick() {
        VectorClock before = VectorClock.empty().tick("a");
        VectorClock after = before.tick("a");
        Assert.that(before.happensBefore(after), "ticking again should strictly dominate the prior clock");
        Assert.that(!after.happensBefore(before), "the later clock should not happen before the earlier one");
    }

    private static void independentTicksAreConcurrent() {
        VectorClock a = VectorClock.empty().tick("a");
        VectorClock b = VectorClock.empty().tick("b");
        Assert.that(a.concurrentWith(b), "clocks ticked independently by different nodes should be concurrent");
        Assert.that(b.concurrentWith(a), "concurrency should be symmetric");
    }

    private static void mergeTakesElementwiseMax() {
        VectorClock a = VectorClock.of(Map.of("a", 3L, "b", 1L));
        VectorClock b = VectorClock.of(Map.of("a", 1L, "b", 5L, "c", 2L));
        VectorClock merged = a.merge(b);
        Assert.equals(3L, merged.get("a"), "max of a's dimension");
        Assert.equals(5L, merged.get("b"), "max of b's dimension");
        Assert.equals(2L, merged.get("c"), "c's dimension only present in b");
        Assert.that(a.happensBefore(merged), "merge result should dominate both inputs");
        Assert.that(b.happensBefore(merged), "merge result should dominate both inputs");
    }

    private static void equalClocksAreEqual() {
        VectorClock a = VectorClock.of(Map.of("a", 2L));
        VectorClock b = VectorClock.of(Map.of("a", 2L));
        Assert.equals(VectorClock.Relation.EQUAL, a.compareTo(b), "identical clocks should compare EQUAL");
        Assert.equals(a, b, "equals() should agree with compareTo");
    }

    private static void jsonRoundTrip() {
        VectorClock original = VectorClock.of(Map.of("alpha", 4L, "beta", 7L));
        VectorClock restored = VectorClock.fromJson(original.toJson());
        Assert.equals(original, restored, "round trip through toJson/fromJson should preserve the clock");
    }
}
