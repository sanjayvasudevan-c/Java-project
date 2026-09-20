package com.swarmcron.state;

import com.swarmcron.clock.HybridClock;
import com.swarmcron.clock.VectorClock;
import com.swarmcron.config.JobSpec;
import com.swarmcron.util.Log;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Observed-remove map of jobId -> JobEntry: the CRDT that makes job specs
 * converge across the cluster regardless of which nodes talked to which, in
 * what order, or after a partition heals. Local edits always tick this
 * node's own vector-clock dimension; merge() (used by the anti-entropy
 * puller, and by gossip in a later milestone) applies an incoming entry
 * using pure vector-clock causality, falling back to MergeEngine's
 * deterministic tie-break only when two writes are genuinely concurrent.
 *
 * ConcurrentHashMap.compute makes every merge atomic: concurrent calls from
 * different threads (an HTTP-triggered local edit vs. an anti-entropy
 * response arriving on a sync worker thread) can never race each other into
 * a lost update.
 */
public final class JobRegistry {

    private final String selfId;
    private final HybridClock hybridClock;
    private final ConcurrentHashMap<String, JobEntry> jobs = new ConcurrentHashMap<>();
    private final List<JobRegistryListener> listeners = new CopyOnWriteArrayList<>();

    public JobRegistry(String selfId, HybridClock hybridClock) {
        this.selfId = selfId;
        this.hybridClock = hybridClock;
    }

    public void addListener(JobRegistryListener l) {
        listeners.add(l);
    }

    /** Local create/update. Seed jobs loaded from jobs.json on first boot go through this too. */
    public JobEntry put(JobSpec spec) {
        JobEntry result = jobs.compute(spec.id(), (id, current) -> {
            VectorClock base = current != null ? current.clock() : VectorClock.empty();
            return new JobEntry(id, spec, base.tick(selfId), false, selfId, hybridClock.tick());
        });
        fireChanged(result);
        return result;
    }

    /** Local delete: tombstones rather than removing, so the deletion itself can propagate like any other write. */
    public JobEntry delete(String jobId) {
        JobEntry result = jobs.compute(jobId, (id, current) -> {
            VectorClock base = current != null ? current.clock() : VectorClock.empty();
            JobSpec keptSpec = current != null ? current.spec() : null;
            return new JobEntry(id, keptSpec, base.tick(selfId), true, selfId, hybridClock.tick());
        });
        fireChanged(result);
        return result;
    }

    /** Applies an entry learned from a peer. Returns true iff it actually changed local state. */
    public boolean merge(JobEntry incoming) {
        boolean[] changed = {false};
        jobs.compute(incoming.jobId(), (id, current) -> {
            if (current == null) {
                hybridClock.witness(incoming.timestamp());
                changed[0] = true;
                return incoming;
            }
            VectorClock.Relation rel = current.clock().compareTo(incoming.clock());
            if (rel == VectorClock.Relation.AFTER || rel == VectorClock.Relation.EQUAL) {
                return current; // we already dominate or are identical; nothing to learn
            }
            hybridClock.witness(incoming.timestamp());
            if (rel == VectorClock.Relation.BEFORE) {
                changed[0] = true;
                return incoming; // incoming strictly dominates
            }
            // CONCURRENT: MergeEngine picks the winning value deterministically,
            // but the clock must record BOTH branches' history so future
            // comparisons against this entry are correct.
            boolean incomingWins = MergeEngine.incomingWins(current, incoming);
            JobEntry winnerBase = incomingWins ? incoming : current;
            VectorClock mergedClock = current.clock().merge(incoming.clock());
            changed[0] = true;
            return new JobEntry(id, winnerBase.spec(), mergedClock, winnerBase.tombstone(),
                    winnerBase.lastWriterNodeId(), winnerBase.timestamp());
        });
        if (changed[0]) {
            JobEntry now = jobs.get(incoming.jobId());
            Log.info("registry", "job '%s' merged: tombstone=%s writer=%s",
                    now.jobId(), now.tombstone(), now.lastWriterNodeId());
            fireChanged(now);
        }
        return changed[0];
    }

    public JobEntry get(String jobId) {
        return jobs.get(jobId);
    }

    public Collection<JobEntry> all() {
        return jobs.values();
    }

    /** Active (non-tombstoned) job specs, e.g. for the scheduler to iterate in a later milestone. */
    public List<JobSpec> activeSpecs() {
        List<JobSpec> out = new ArrayList<>();
        for (JobEntry e : jobs.values()) {
            if (!e.tombstone()) {
                out.add(e.spec());
            }
        }
        return out;
    }

    /** jobId -> vector clock, for anti-entropy digests. */
    public Map<String, VectorClock> digest() {
        Map<String, VectorClock> out = new HashMap<>();
        for (JobEntry e : jobs.values()) {
            out.put(e.jobId(), e.clock());
        }
        return out;
    }

    /** Entries this node has that a peer reporting peerDigest is missing or behind on. */
    public List<JobEntry> computeMissingFor(Map<String, VectorClock> peerDigest) {
        List<JobEntry> out = new ArrayList<>();
        for (JobEntry entry : jobs.values()) {
            VectorClock peerClock = peerDigest.get(entry.jobId());
            if (peerClock == null) {
                out.add(entry);
                continue;
            }
            VectorClock.Relation rel = entry.clock().compareTo(peerClock);
            if (rel == VectorClock.Relation.AFTER || rel == VectorClock.Relation.CONCURRENT) {
                out.add(entry);
            }
        }
        return out;
    }

    private void fireChanged(JobEntry entry) {
        for (JobRegistryListener l : listeners) {
            try {
                l.onJobChanged(entry);
            } catch (RuntimeException e) {
                Log.error("registry", "listener threw: %s", e);
            }
        }
    }
}
