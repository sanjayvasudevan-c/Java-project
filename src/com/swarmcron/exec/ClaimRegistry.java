package com.swarmcron.exec;

import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory jobId -> current RunLease, kept up to date by direct best-effort
 * broadcast (JobExecutor sends a RUN_CLAIM announcement to every alive peer
 * whenever it wins a claim) rather than the anti-entropy/CRDT machinery
 * JobRegistry uses. That machinery exists to converge slow-moving state
 * (job definitions) even across an extended partition; a lease is the
 * opposite -- short-lived on purpose, so a periodic pull cadence measured in
 * tens of seconds would be far slower than the lease's own expiry. A missed
 * broadcast just means a peer might also attempt a claim for the same job,
 * which the fencing-token tie-break below resolves the instant either side
 * learns of the other; worst case is a rare duplicate execution, an accepted
 * at-least-once cost documented alongside JobExecutor.
 *
 * Merge rule is simpler than JobRegistry's vector-clock dance: a strictly
 * higher FencingToken always wins, full stop. Tokens are minted by a single
 * Raft-lite leader's monotonically increasing (term, sequence) counter, so
 * two distinct tokens are never equal and a higher one is never wrong to
 * prefer -- there is no concurrent-write case to arbitrate.
 */
public final class ClaimRegistry {

    private final ConcurrentHashMap<String, RunLease> leases = new ConcurrentHashMap<>();

    /** Adopts candidate if it strictly beats whatever is on file. Returns true iff it was adopted. */
    public boolean adopt(RunLease candidate) {
        boolean[] adopted = {false};
        leases.compute(candidate.jobId(), (id, current) -> {
            if (current == null || candidate.token().compareTo(current.token()) > 0) {
                adopted[0] = true;
                return candidate;
            }
            return current;
        });
        return adopted[0];
    }

    /** The current lease for jobId, or null if there is none or it has expired. */
    public RunLease activeLease(String jobId, long nowMillis) {
        RunLease lease = leases.get(jobId);
        return (lease != null && !lease.isExpired(nowMillis)) ? lease : null;
    }
}
