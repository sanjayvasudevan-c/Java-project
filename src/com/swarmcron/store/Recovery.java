package com.swarmcron.store;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Reconstructs enough state from a Snapshot plus whatever the WAL has
 * recorded since to resume scheduling correctly after a restart (Scenario 2)
 * without either re-running an already-completed firing or silently losing
 * track of one that was interrupted mid-run (Scenario 4, from the same
 * node's point of view after it comes back).
 *
 * Deliberately does NOT auto-resume an interrupted (STARTED-with-no-terminal-
 * record) run: job commands are opaque shell invocations with no resumability
 * contract, so "resuming" one can only ever mean "run it again," which is
 * exactly what the job's next scheduled firing (or, if the crash happened
 * mid-run on the ring owner, a takeover triggered by RingChangeListener) will
 * naturally do. Recovery's job is bookkeeping and honesty, not silently
 * guessing whether a re-run is safe.
 */
public final class Recovery {

    public record Recovered(Map<String, Long> lastFireMillisByJobId, Set<String> jobsWithIncompleteRun) {}

    private Recovery() {}

    public static Recovered recover(Snapshot snapshot, WriteAheadLog wal) {
        Map<String, Long> lastFire = new LinkedHashMap<>(snapshot.load());
        Map<String, WalRecord.Event> lastEventByJob = new HashMap<>();
        for (WalRecord r : wal.readAll()) {
            lastEventByJob.put(r.jobId(), r.event());
            if (r.event() == WalRecord.Event.COMPLETED || r.event() == WalRecord.Event.FAILED) {
                lastFire.merge(r.jobId(), r.scheduledFireMillis(), Math::max);
            }
        }
        Set<String> incomplete = new LinkedHashSet<>();
        for (Map.Entry<String, WalRecord.Event> e : lastEventByJob.entrySet()) {
            if (e.getValue() == WalRecord.Event.STARTED) {
                incomplete.add(e.getKey());
            }
        }
        return new Recovered(lastFire, incomplete);
    }
}
