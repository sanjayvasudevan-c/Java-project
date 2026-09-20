package com.swarmcron.state;

/** Notified whenever a job entry actually changes (used by the hash ring/scheduler in later milestones, and SSE in M9). */
@FunctionalInterface
public interface JobRegistryListener {
    void onJobChanged(JobEntry entry);
}
