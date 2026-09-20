package com.swarmcron.sim;

/**
 * Owns one simulation's shared time base: a VirtualClock plus the single
 * chronological event queue that both SimNetwork (message delivery) and
 * SimScheduler (timer callbacks) schedule into. Driving advanceTo() here is
 * the only way time moves in a simulation; it processes network deliveries
 * and timer fires in true relative order, so a periodic gossip tick and an
 * in-flight PING/ACK land exactly where they would on a real clock.
 */
public final class SimWorld {

    private final VirtualClock clock;
    private final SimEventQueue eventQueue = new SimEventQueue();
    private final SimNetwork network;
    private final SimScheduler scheduler;

    public SimWorld(long startMillis, long seed) {
        this.clock = new VirtualClock(startMillis);
        this.network = new SimNetwork(clock, eventQueue, seed);
        this.scheduler = new SimScheduler(clock, eventQueue);
    }

    public VirtualClock clock() {
        return clock;
    }

    public SimNetwork network() {
        return network;
    }

    public SimScheduler scheduler() {
        return scheduler;
    }

    public void advanceTo(long targetMillis) {
        while (eventQueue.peekFireAt() <= targetMillis) {
            long fireAt = eventQueue.peekFireAt();
            Runnable task = eventQueue.poll();
            clock.advanceTo(fireAt);
            if (task != null) {
                task.run();
            }
        }
        clock.advanceTo(targetMillis);
    }

    public void advanceBy(long deltaMillis) {
        advanceTo(clock.nowMillis() + deltaMillis);
    }
}
