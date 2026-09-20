package com.swarmcron.election;

/**
 * A monotonically increasing token scoped to a Raft term, minted by the
 * leader for a job claim (see exec/ in a later milestone). Comparing by
 * (term, sequence) is exactly the tie-break rule the claim protocol uses:
 * the claim with the highest (term, fencingToken) wins a conflict, so a
 * token from a newer term always beats one from an older term regardless of
 * sequence, and within the same term, higher sequence wins.
 */
public record FencingToken(long term, long sequence) implements Comparable<FencingToken> {
    @Override
    public int compareTo(FencingToken o) {
        int c = Long.compare(term, o.term);
        return c != 0 ? c : Long.compare(sequence, o.sequence);
    }
}
