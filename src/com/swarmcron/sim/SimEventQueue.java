package com.swarmcron.sim;

import java.util.Comparator;
import java.util.PriorityQueue;

/**
 * Shared discrete-event queue driving one simulation's VirtualClock. Both
 * SimNetwork (message delivery) and SimScheduler (timer callbacks) schedule
 * into the same queue so SimWorld.advanceTo can process them in true
 * chronological order relative to each other -- a periodic gossip tick and an
 * in-flight PING/ACK land exactly where they would on a real clock.
 */
final class SimEventQueue {

    private record ScheduledEvent(long fireAt, long seq, Runnable task) {}

    private final PriorityQueue<ScheduledEvent> queue =
            new PriorityQueue<>(Comparator.<ScheduledEvent>comparingLong(ScheduledEvent::fireAt).thenComparingLong(ScheduledEvent::seq));
    private long seqCounter = 0;

    void schedule(long fireAt, Runnable task) {
        queue.add(new ScheduledEvent(fireAt, seqCounter++, task));
    }

    long peekFireAt() {
        return queue.isEmpty() ? Long.MAX_VALUE : queue.peek().fireAt();
    }

    Runnable poll() {
        ScheduledEvent e = queue.poll();
        return e == null ? null : e.task();
    }
}
