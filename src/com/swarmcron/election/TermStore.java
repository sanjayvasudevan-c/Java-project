package com.swarmcron.election;

/** Durable storage for RaftState. FileTermStore backs production; InMemoryTermStore backs the simulator and tests. */
public interface TermStore {
    RaftState load();

    void save(RaftState state);
}
