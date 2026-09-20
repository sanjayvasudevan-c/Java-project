package com.swarmcron.sim;

import com.swarmcron.clock.Clock;

/**
 * Clock implementation whose time only moves when a simulation explicitly
 * advances it (see SimNetwork.advanceTo). Every production component depends
 * on the Clock interface rather than the wall clock, so wiring this in place
 * of SystemClock makes an entire node's timing deterministic and controllable
 * from a test.
 *
 * millis is volatile because nowMillis() is not only-ever read from the
 * single sim-driving thread in practice: JobExecutor's ProcessRunner work
 * genuinely runs on a real worker-pool thread (see ProcessRunner's own
 * javadoc), and that thread reads the clock when computing timestamps for
 * its post-run continuation (WAL records, RUN_RESULT broadcasts). volatile
 * only guarantees that read sees the latest value the sim thread wrote, not
 * that it's still "current" by the time it's used a moment later -- see
 * SimEventQueue for how a stale/overtaken reading is handled safely.
 */
public final class VirtualClock implements Clock {

    private volatile long millis;

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
