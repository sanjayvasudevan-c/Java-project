package com.swarmcron.sim;

import com.swarmcron.clock.Clock;

/**
 * Clock implementation whose time only moves when a simulation explicitly
 * advances it (see SimNetwork.advanceTo). Every production component depends
 * on the Clock interface rather than the wall clock, so wiring this in place
 * of SystemClock makes an entire node's timing deterministic and controllable
 * from a test.
 */
public final class VirtualClock implements Clock {

    private long millis;

    public VirtualClock(long startMillis) {
        this.millis = startMillis;
    }

    @Override
    public long nowMillis() {
        return millis;
    }

    void advanceTo(long newMillis) {
        if (newMillis < millis) {
            throw new IllegalArgumentException("virtual clock cannot go backwards: " + millis + " -> " + newMillis);
        }
        millis = newMillis;
    }
}
