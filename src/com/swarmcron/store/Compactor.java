package com.swarmcron.store;

import com.swarmcron.clock.Scheduler;
import com.swarmcron.util.Log;

import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Periodically snapshots JobExecutor's in-memory "last completed/failed
 * fire time per job" and, once that snapshot is durable, truncates the WAL --
 * every record it held is now redundant with the snapshot. Skips the
 * truncation step (but still refreshes the snapshot) whenever a run is
 * currently in flight: truncating out from under an in-progress run would
 * erase its STARTED record, and Recovery would then have no way to notice a
 * crash mid-run ever happened. The next tick tries again once nothing is
 * in flight.
 */
public final class Compactor {

    private final Snapshot snapshot;
    private final WriteAheadLog wal;
    private final Supplier<Map<String, Long>> currentLastFireSupplier;
    private final BooleanSupplier hasInFlightRuns;
    private final Scheduler scheduler;
    private final long intervalMillis;

    public Compactor(Snapshot snapshot, WriteAheadLog wal, Supplier<Map<String, Long>> currentLastFireSupplier,
                      BooleanSupplier hasInFlightRuns, Scheduler scheduler, long intervalMillis) {
        this.snapshot = snapshot;
        this.wal = wal;
        this.currentLastFireSupplier = currentLastFireSupplier;
        this.hasInFlightRuns = hasInFlightRuns;
        this.scheduler = scheduler;
        this.intervalMillis = intervalMillis;
    }

    public void start() {
        scheduler.scheduleAtFixedRate(this::compact, intervalMillis, intervalMillis);
    }

    public synchronized void compact() {
        Map<String, Long> current = currentLastFireSupplier.get();
        snapshot.save(current);
        if (hasInFlightRuns.getAsBoolean()) {
            Log.debug("compactor", "snapshot saved (%d job(s)); WAL left in place, run(s) still in flight", current.size());
            return;
        }
        wal.truncate();
        Log.debug("compactor", "snapshot saved (%d job(s)); WAL truncated", current.size());
    }
}
