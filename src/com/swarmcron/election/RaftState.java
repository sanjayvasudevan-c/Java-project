package com.swarmcron.election;

/** The two values Raft must never forget across a restart: what term it's in, and who (if anyone) it already voted for this term. */
public record RaftState(long currentTerm, String votedFor) {
    public static final RaftState INITIAL = new RaftState(0, null);
}
