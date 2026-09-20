package com.swarmcron.clock;

/** Production Clock backed by the wall clock. Stateless, thread-safe by construction. */
public final class SystemClock implements Clock {

    public static final SystemClock INSTANCE = new SystemClock();

    @Override
    public long nowMillis() {
        return System.currentTimeMillis();
    }
}
