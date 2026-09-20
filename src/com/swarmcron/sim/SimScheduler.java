package com.swarmcron.sim;

import com.swarmcron.clock.Scheduler;

/**
 * Scheduler backed by the simulation's shared SimEventQueue: callbacks fire
 * synchronously, on the thread driving SimWorld.advanceTo, exactly when the
 * VirtualClock passes their fire time. Fixed-rate tasks reschedule themselves
 * after each firing rather than relying on any background thread -- there is
 * none here.
 */
public final class SimScheduler implements Scheduler {

    private final VirtualClock clock;
    private final SimEventQueue eventQueue;

    SimScheduler(VirtualClock clock, SimEventQueue eventQueue) {
        this.clock = clock;
        this.eventQueue = eventQueue;
    }

    @Override
    public Cancellable scheduleAtFixedRate(Runnable task, long initialDelayMillis, long periodMillis) {
        boolean[] cancelled = {false};
        Runnable[] wrapper = new Runnable[1];
        wrapper[0] = () -> {
            if (cancelled[0]) {
                return;
            }
            task.run();
            if (!cancelled[0]) {
                eventQueue.schedule(clock.nowMillis() + periodMillis, wrapper[0]);
            }
        };
        eventQueue.schedule(clock.nowMillis() + initialDelayMillis, wrapper[0]);
        return () -> cancelled[0] = true;
    }

    @Override
    public Cancellable scheduleOnce(Runnable task, long delayMillis) {
        boolean[] cancelled = {false};
        eventQueue.schedule(clock.nowMillis() + delayMillis, () -> {
            if (!cancelled[0]) {
                task.run();
            }
        });
        return () -> cancelled[0] = true;
    }
}
