package com.swarmcron.hash;

/** Notified whenever the ring is rebuilt (used by the scheduler in a later milestone, and the dashboard's ring view in M9). */
@FunctionalInterface
public interface RingChangeListener {
    void onRingChanged(ConsistentHashRing newRing);
}
