package com.swarmcron.sim;

import java.util.Comparator;
import java.util.PriorityQueue;

/**
 * Shared discrete-event queue driving one simulation's VirtualClock. Both
 * SimNetwork (message delivery) and SimScheduler (timer callbacks) schedule
 * into the same queue so SimWorld.advanceTo can process them in true
 * chronological order relative to each other -- a periodic gossip tick and an
 * in-flight PING/ACK land exactly where they would on a real clock.
 *
 * poll()/peekFireAt() are only ever called from the single thread driving
 * SimWorld.advanceTo(), which is what keeps event *processing* order fully
 * deterministic. schedule() is a different story: JobExecutor's ProcessRunner
 * work genuinely runs on a real worker-pool thread (see its javadoc), and
 * that thread's post-run continuation (a WAL record's timestamp, a
 * RUN_RESULT broadcast, a claim retry) calls back into SimNetwork/
 * SimScheduler, which call schedule() here -- from a thread other than the
 * sim-driving one. Two things follow from that: the queue's internal
 * PriorityQueue needs real mutual exclusion (an unsynchronized PriorityQueue
 * mutated from two threads at once corrupts its heap silently, surfacing
 * later as a bewildering NullPointerException deep in an unrelated poll()),
 * and a fireAt computed by a worker thread against a clock reading that's
 * since been overtaken by the sim thread's own advancement must not be
 * allowed through as a "time travel to the past" -- it's clamped to the
 * latest fireAt this queue has already processed, matching how a real
 * scheduler treats a task that's late: it just runs at the next opportunity
 * instead of throwing.
 */
final class SimEventQueue {

    private record ScheduledEvent(long fireAt, long seq, Runnable task) {}

    private final PriorityQueue<ScheduledEvent> queue =
            new PriorityQueue<>(Comparator.<ScheduledEvent>comparingLong(ScheduledEvent::fireAt).thenComparingLong(ScheduledEvent::seq));
    private long seqCounter = 0;
    private volatile long floorFireAt = Long.MIN_VALUE;

    synchronized void schedule(long fireAt, Runnable task) {
        queue.add(new ScheduledEvent(Math.max(fireAt, floorFireAt), seqCounter++, task));
    }

    synchronized long peekFireAt() {
        return queue.isEmpty() ? Long.MAX_VALUE : queue.peek().fireAt();
    }

    synchronized Runnable poll() {
        ScheduledEvent e = queue.poll();
        if (e == null) {
            return null;
        }
        floorFireAt = e.fireAt();
        return e.task();
    }
}
