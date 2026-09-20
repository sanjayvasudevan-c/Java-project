package com.swarmcron.schedule;

import com.swarmcron.clock.Scheduler;
import com.swarmcron.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Hashed timing wheel: 600 buckets of 100ms each (60s per full rotation). A
 * task scheduled further out than one rotation is placed in the bucket it
 * would occupy modulo the wheel size, tagged with how many additional full
 * rotations to wait before it's actually due -- the classic technique that
 * lets a small, fixed number of buckets represent an unbounded time horizon
 * with O(1) insertion and O(1) amortized per-tick work.
 *
 * Driven by Scheduler.scheduleAtFixedRate (never Thread.sleep), so the exact
 * same wheel runs under SystemScheduler in production and SimScheduler in
 * the simulator. currentBucket is only ever WRITTEN by tick() (the single
 * scheduler-driven thread) and only READ by schedule() (potentially other
 * threads, e.g. an HTTP request enqueuing a manual trigger) -- a
 * single-writer/multiple-reader pattern that volatile alone makes safe.
 * Each bucket is its own ConcurrentLinkedQueue so schedule() can add() from
 * any thread while tick() concurrently poll()s the current bucket.
 */
public final class TimerWheel {

    public static final int TICK_MILLIS = 100;
    public static final int WHEEL_SIZE = 600; // 600 * 100ms = 60s per rotation

    @FunctionalInterface
    public interface FireHandler {
        void onFire(String taskId);
    }

    private record Entry(String taskId, int remainingRounds) {}

    private final Scheduler scheduler;
    private final FireHandler fireHandler;
    private final List<ConcurrentLinkedQueue<Entry>> buckets;
    private volatile int currentBucket = 0;

    public TimerWheel(Scheduler scheduler, FireHandler fireHandler) {
        this.scheduler = scheduler;
        this.fireHandler = fireHandler;
        this.buckets = new ArrayList<>(WHEEL_SIZE);
        for (int i = 0; i < WHEEL_SIZE; i++) {
            buckets.add(new ConcurrentLinkedQueue<>());
        }
    }

    public void start() {
        scheduler.scheduleAtFixedRate(this::tick, TICK_MILLIS, TICK_MILLIS);
    }

    /** Schedules taskId to fire (via fireHandler.onFire) after approximately delayMillis, rounded up to the nearest tick, minimum one tick out. */
    public void schedule(String taskId, long delayMillis) {
        long delayTicks = Math.max(1, Math.ceilDiv(Math.max(0, delayMillis), TICK_MILLIS));
        // currentBucket is the bucket the NEXT tick will process, so a delay of
        // N ticks lands on the (N-1)-th bucket after it: N=1 fires on that very
        // next tick (bucketIndex == currentBucket, 0 extra rounds); N=WHEEL_SIZE
        // fires on the WHEEL_SIZE-th tick from now, which is the last bucket
        // before wrapping back to currentBucket, still on its first visit.
        int rounds = (int) ((delayTicks - 1) / WHEEL_SIZE);
        int bucketIndex = (int) ((currentBucket + delayTicks - 1) % WHEEL_SIZE);
        buckets.get(bucketIndex).add(new Entry(taskId, rounds));
    }

    private void tick() {
        // Snapshot-and-clear the bucket, advance currentBucket, and only THEN
        // invoke any fire handlers. This matters because "jobs are rescheduled
        // after each fire" means a handler routinely calls schedule() again
        // from right here, reentrantly, mid-tick. If we instead kept draining
        // this same ConcurrentLinkedQueue live (poll-until-null) while handlers
        // ran, a reschedule targeting "the very next tick" (this bucket, once
        // currentBucket has moved past it) could never land there -- but a
        // reschedule that happened to target THIS bucket before advancing would
        // get picked up by the still-running poll loop and fire immediately,
        // zero ticks later instead of the intended delay. Snapshotting first
        // means new entries added during handler execution can never be seen
        // by this tick's processing, however they're addressed.
        int processedBucket = currentBucket;
        ConcurrentLinkedQueue<Entry> bucket = buckets.get(processedBucket);
        List<Entry> snapshot = new ArrayList<>();
        Entry e;
        while ((e = bucket.poll()) != null) {
            snapshot.add(e);
        }
        currentBucket = (processedBucket + 1) % WHEEL_SIZE;

        for (Entry entry : snapshot) {
            if (entry.remainingRounds() <= 0) {
                try {
                    fireHandler.onFire(entry.taskId());
                } catch (RuntimeException ex) {
                    Log.error("timer-wheel", "fire handler threw for task %s: %s", entry.taskId(), ex);
                }
            } else {
                buckets.get(processedBucket).add(new Entry(entry.taskId(), entry.remainingRounds() - 1));
            }
        }
    }
}
