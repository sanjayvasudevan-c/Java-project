package com.swarmcron.clock;

/**
 * Hybrid logical clock: combines the physical Clock with a logical counter so
 * timestamps stay human-readable (physicalMillis) while remaining totally
 * ordered even when two events share the same physical millisecond or the
 * physical clock hasn't advanced. tick() stamps a local event; witness()
 * folds in a timestamp observed from a remote event, advancing this clock
 * past it. Used by JobRegistry as the deterministic tie-break for two
 * causally-concurrent writes to the same job.
 *
 * Synchronized because in production a local HTTP-triggered job edit (one
 * thread) and an anti-entropy merge applying a peer's entry (another thread)
 * can both tick/witness this clock concurrently.
 */
public final class HybridClock {

    private final Clock clock;
    private long lastPhysical;
    private long counter;

    public HybridClock(Clock clock) {
        this.clock = clock;
    }

    public synchronized HybridTimestamp tick() {
        long now = clock.nowMillis();
        if (now > lastPhysical) {
            lastPhysical = now;
            counter = 0;
        } else {
            counter++;
        }
        return new HybridTimestamp(lastPhysical, counter);
    }

    public synchronized HybridTimestamp witness(HybridTimestamp remote) {
        long now = clock.nowMillis();
        long physical = Math.max(now, Math.max(lastPhysical, remote.physicalMillis()));
        long nextCounter;
        if (physical == lastPhysical && physical == remote.physicalMillis()) {
            nextCounter = Math.max(counter, remote.logicalCounter()) + 1;
        } else if (physical == lastPhysical) {
            nextCounter = counter + 1;
        } else if (physical == remote.physicalMillis()) {
            nextCounter = remote.logicalCounter() + 1;
        } else {
            nextCounter = 0;
        }
        lastPhysical = physical;
        counter = nextCounter;
        return new HybridTimestamp(lastPhysical, counter);
    }
}
