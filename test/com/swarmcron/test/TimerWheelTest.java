package com.swarmcron.test;

import com.swarmcron.clock.Scheduler;
import com.swarmcron.schedule.CronExpression;
import com.swarmcron.schedule.TimerWheel;
import com.swarmcron.sim.SimWorld;
import com.swarmcron.util.Assert;
import com.swarmcron.util.TestSuite;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

final class TimerWheelTest {

    private static final ZoneId UTC = ZoneId.of("UTC");

    static boolean run() {
        return new TestSuite("TimerWheelTest")
                .test("a task scheduled within one rotation fires at approximately the right tick", TimerWheelTest::firesWithinOneRotation)
                .test("a task scheduled beyond one rotation waits the correct number of extra rounds", TimerWheelTest::firesAcrossMultipleRotations)
                .test("multiple tasks in the same bucket all fire", TimerWheelTest::multipleTasksSameBucket)
                .test("rescheduling from within the fire handler drives repeated cron-timed fires", TimerWheelTest::reschedulesAfterEachFire)
                .run();
    }

    private static Scheduler simScheduler(SimWorld world) {
        return world.scheduler();
    }

    private static void firesWithinOneRotation() {
        SimWorld world = new SimWorld(0, 1);
        List<String> fired = new ArrayList<>();
        TimerWheel wheel = new TimerWheel(simScheduler(world), fired::add);
        wheel.start();

        wheel.schedule("task-a", 2500); // 2.5s, well within the 60s rotation

        world.advanceTo(2400);
        Assert.equals(0, fired.size(), "should not have fired before its delay elapses");

        world.advanceTo(2600);
        Assert.equals(1, fired.size(), "should have fired once its delay elapses");
        Assert.equals("task-a", fired.get(0), "the fired task id should match");
    }

    private static void firesAcrossMultipleRotations() {
        SimWorld world = new SimWorld(0, 1);
        List<String> fired = new ArrayList<>();
        TimerWheel wheel = new TimerWheel(simScheduler(world), fired::add);
        wheel.start();

        long delayMillis = 150_000; // 150s = 2 full rotations (60s each) + 30s
        wheel.schedule("task-a", delayMillis);

        world.advanceTo(delayMillis - 200);
        Assert.equals(0, fired.size(), "should not fire before a delay spanning multiple rotations elapses");

        world.advanceTo(delayMillis + 200);
        Assert.equals(1, fired.size(), "should fire once the full multi-rotation delay elapses");
    }

    private static void multipleTasksSameBucket() {
        SimWorld world = new SimWorld(0, 1);
        List<String> fired = new ArrayList<>();
        TimerWheel wheel = new TimerWheel(simScheduler(world), fired::add);
        wheel.start();

        wheel.schedule("a", 5000);
        wheel.schedule("b", 5000);
        wheel.schedule("c", 5000);

        world.advanceTo(5200);
        Assert.equals(3, fired.size(), "all three tasks scheduled for the same delay should fire");
        Assert.that(fired.containsAll(List.of("a", "b", "c")), "all three task ids should be present");
    }

    /** Treats the sim clock's raw millis as epoch millis -- the actual calendar date doesn't matter, only that "every minute" fires every 60000ms of sim time. */
    private static ZonedDateTime simNow(SimWorld world) {
        return Instant.ofEpochMilli(world.clock().nowMillis()).atZone(UTC);
    }

    private static void reschedulesAfterEachFire() {
        SimWorld world = new SimWorld(0, 1);
        CronExpression everyMinute = CronExpression.parse("* * * * *");
        List<Long> fireTimesMillis = new ArrayList<>();
        TimerWheel[] wheelHolder = new TimerWheel[1];

        TimerWheel wheel = new TimerWheel(simScheduler(world), taskId -> {
            fireTimesMillis.add(world.clock().nowMillis());
            scheduleNext(wheelHolder[0], everyMinute, world);
        });
        wheelHolder[0] = wheel;
        wheel.start();

        scheduleNext(wheel, everyMinute, world);

        world.advanceTo(4 * 60_000L + 500);

        Assert.equals(4, fireTimesMillis.size(), "an every-minute cron rescheduled from its own fire handler should fire 4 times in 4 minutes");
        for (int i = 0; i < fireTimesMillis.size(); i++) {
            long expected = (i + 1) * 60_000L;
            Assert.that(Math.abs(fireTimesMillis.get(i) - expected) < TimerWheel.TICK_MILLIS,
                    "fire #" + i + " should land within one tick of the expected minute boundary, got " + fireTimesMillis.get(i));
        }
    }

    private static void scheduleNext(TimerWheel wheel, CronExpression expr, SimWorld world) {
        ZonedDateTime now = simNow(world);
        ZonedDateTime next = expr.nextFireTime(now);
        long delay = next.toInstant().toEpochMilli() - now.toInstant().toEpochMilli();
        wheel.schedule("cron-task", delay);
    }
}
