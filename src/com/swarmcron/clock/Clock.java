package com.swarmcron.clock;

/**
 * Source of wall-clock time for the whole node. Production code runs against
 * SystemClock; the simulator (sim/VirtualClock) implements the same interface
 * so scenarios can control time deterministically. No production code path may
 * call System.currentTimeMillis() or Thread.sleep() directly  -  everything goes
 * through this abstraction.
 */
public interface Clock {
    long nowMillis();
}
