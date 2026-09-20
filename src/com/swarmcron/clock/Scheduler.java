package com.swarmcron.clock;

/**
 * Schedules recurring/delayed callbacks against a Clock. SystemScheduler backs
 * it with a real timer thread for production; sim.SimScheduler instead fires
 * callbacks synchronously as the simulation's VirtualClock is advanced. This
 * is the mechanism that keeps FailureDetector, GossipEngine, election
 * timeouts etc. off Thread.sleep entirely: they only ever say "call me back
 * in N ms" through this interface, never touch a timer or thread directly.
 */
public interface Scheduler {

    Cancellable scheduleAtFixedRate(Runnable task, long initialDelayMillis, long periodMillis);

    Cancellable scheduleOnce(Runnable task, long delayMillis);

    interface Cancellable {
        void cancel();
    }
}
