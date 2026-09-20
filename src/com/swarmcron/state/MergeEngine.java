package com.swarmcron.state;

/**
 * Deterministic tie-break for two causally CONCURRENT JobEntry versions --
 * the one case vector-clock comparison alone can't resolve. Callers must
 * only invoke this once they've confirmed the two entries' clocks are
 * actually concurrent (see VectorClock.Relation.CONCURRENT); BEFORE/AFTER/
 * EQUAL should be resolved directly from clock comparison, since there's no
 * real conflict to break there. Pure function of its inputs, so it computes
 * the same answer on every node regardless of arrival order.
 */
public final class MergeEngine {

    private MergeEngine() {}

    public static boolean incomingWins(JobEntry current, JobEntry incoming) {
        int cmp = incoming.timestamp().compareTo(current.timestamp());
        if (cmp == 0) {
            cmp = incoming.lastWriterNodeId().compareTo(current.lastWriterNodeId());
        }
        return cmp > 0;
    }
}
