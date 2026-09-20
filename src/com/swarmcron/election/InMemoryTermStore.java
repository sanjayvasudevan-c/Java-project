package com.swarmcron.election;

/** Non-durable TermStore for the simulator and unit tests, where "survives a restart" isn't the thing under test. */
public final class InMemoryTermStore implements TermStore {

    private volatile RaftState state = RaftState.INITIAL;

    @Override
    public RaftState load() {
        return state;
    }

    @Override
    public void save(RaftState state) {
        this.state = state;
    }
}
