package com.swarmcron.hash;

import com.swarmcron.cluster.MemberInfo;
import com.swarmcron.cluster.Membership;
import com.swarmcron.util.Log;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Keeps a live ConsistentHashRing in sync with Membership's ALIVE set. Every
 * Membership change re-derives the current ALIVE node id set; the ring is
 * only rebuilt (and listeners only notified) when that set actually differs
 * from the last build -- an incarnation bump or a SUSPECT<->DEAD transition
 * for an already-excluded node doesn't change ring membership and shouldn't
 * trigger a rebuild storm.
 */
public final class RingManager {

    private final Membership membership;
    private final int virtualNodesPerNode;
    private final AtomicReference<ConsistentHashRing> currentRing =
            new AtomicReference<>(ConsistentHashRing.build(List.of(), 0));
    private final List<RingChangeListener> listeners = new CopyOnWriteArrayList<>();
    private volatile Set<String> lastAliveIds = Set.of();

    public RingManager(Membership membership, int virtualNodesPerNode) {
        this.membership = membership;
        this.virtualNodesPerNode = virtualNodesPerNode;
    }

    public void start() {
        rebuildIfChanged();
        membership.addListener(info -> rebuildIfChanged());
    }

    public void addListener(RingChangeListener listener) {
        listeners.add(listener);
    }

    public ConsistentHashRing currentRing() {
        return currentRing.get();
    }

    private synchronized void rebuildIfChanged() {
        Set<String> aliveIds = new HashSet<>();
        for (MemberInfo m : membership.aliveMembers()) {
            aliveIds.add(m.nodeId());
        }
        if (aliveIds.equals(lastAliveIds)) {
            return;
        }
        lastAliveIds = aliveIds;
        ConsistentHashRing ring = ConsistentHashRing.build(aliveIds, virtualNodesPerNode);
        currentRing.set(ring);
        Log.info("ring", "rebuilt: %d physical node(s), %d virtual node(s)", ring.physicalNodeCount(), ring.virtualNodes().size());
        for (RingChangeListener l : listeners) {
            try {
                l.onRingChanged(ring);
            } catch (RuntimeException e) {
                Log.error("ring", "listener threw: %s", e);
            }
        }
    }
}
