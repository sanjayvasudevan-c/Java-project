package com.swarmcron.cluster;

import com.swarmcron.clock.Clock;
import com.swarmcron.net.PeerAddress;
import com.swarmcron.util.Log;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * SWIM membership table. All mutation goes through merge(), which applies the
 * SWIM precedence rule atomically via ConcurrentHashMap.compute: higher
 * incarnation always wins; at equal incarnation, state rank ALIVE &lt;
 * SUSPECT &lt; DEAD &lt; LEFT decides. This makes it safe for concurrent
 * gossip deliveries (selector thread) and local failure-detector decisions
 * (scheduler thread) to call merge() from different threads at once --
 * neither can race the other into an inconsistent per-node state. Readers
 * (ring builder, dashboard, HTTP API) get a point-in-time snapshot via all()/
 * aliveMembers() and never block a writer.
 */
public final class Membership {

    private final String selfId;
    private final PeerAddress selfAddress;
    private final Clock clock;
    private final ConcurrentHashMap<String, MemberInfo> members = new ConcurrentHashMap<>();
    private final List<MembershipListener> listeners = new CopyOnWriteArrayList<>();
    private volatile long selfIncarnation = 0;

    public Membership(String selfId, PeerAddress selfAddress, Clock clock) {
        this.selfId = selfId;
        this.selfAddress = selfAddress;
        this.clock = clock;
        members.put(selfId, new MemberInfo(selfId, selfAddress, NodeState.ALIVE, 0, clock.nowMillis()));
    }

    public String selfId() {
        return selfId;
    }

    public long selfIncarnation() {
        return selfIncarnation;
    }

    public MemberInfo selfInfo() {
        return members.get(selfId);
    }

    public void addListener(MembershipListener l) {
        listeners.add(l);
    }

    public MemberInfo get(String nodeId) {
        return members.get(nodeId);
    }

    public Collection<MemberInfo> all() {
        return members.values();
    }

    public List<MemberInfo> aliveMembers() {
        List<MemberInfo> out = new ArrayList<>();
        for (MemberInfo m : members.values()) {
            if (m.state() == NodeState.ALIVE) {
                out.add(m);
            }
        }
        return out;
    }

    public int aliveCount() {
        return aliveMembers().size();
    }

    /**
     * Applies one incoming update (from a piggyback, a direct suspicion
     * decision, or first contact with an unknown peer). Returns true iff it
     * actually changed local state, which callers use to decide whether to
     * re-gossip it.
     */
    public boolean merge(MemberUpdate update) {
        if (update.nodeId().equals(selfId)) {
            return mergeSelf(update);
        }
        boolean[] changed = {false};
        members.compute(update.nodeId(), (id, current) -> {
            MemberInfo candidate = new MemberInfo(
                    update.nodeId(), update.address(), update.state(), update.incarnation(), clock.nowMillis());
            if (current == null || isNewer(candidate, current)) {
                changed[0] = current == null
                        || current.state() != candidate.state()
                        || current.incarnation() != candidate.incarnation();
                return candidate;
            }
            return current;
        });
        if (changed[0]) {
            MemberInfo now = members.get(update.nodeId());
            Log.info("membership", "[%s] %s is now %s (incarnation %d)", selfId, update.nodeId(), now.state(), now.incarnation());
            fireChanged(now);
        }
        return changed[0];
    }

    private synchronized boolean mergeSelf(MemberUpdate update) {
        // Someone is gossiping that we are SUSPECT/DEAD at an incarnation >= ours.
        // Refute it by bumping our own incarnation and re-asserting ALIVE.
        if (update.state() != NodeState.ALIVE && update.incarnation() >= selfIncarnation) {
            selfIncarnation = update.incarnation() + 1;
            MemberInfo refuted = new MemberInfo(selfId, selfAddress, NodeState.ALIVE, selfIncarnation, clock.nowMillis());
            members.put(selfId, refuted);
            Log.warn("membership", "[%s] refuting suspicion at incarnation %d, now ALIVE at incarnation %d",
                    selfId, update.incarnation(), selfIncarnation);
            fireChanged(refuted);
            return true;
        }
        return false;
    }

    private static boolean isNewer(MemberInfo candidate, MemberInfo current) {
        if (candidate.incarnation() != current.incarnation()) {
            return candidate.incarnation() > current.incarnation();
        }
        return rank(candidate.state()) > rank(current.state());
    }

    private static int rank(NodeState state) {
        return switch (state) {
            case ALIVE -> 0;
            case SUSPECT -> 1;
            case DEAD -> 2;
            case LEFT -> 3;
        };
    }

    private void fireChanged(MemberInfo info) {
        for (MembershipListener l : listeners) {
            try {
                l.onMembershipChanged(info);
            } catch (RuntimeException e) {
                Log.error("membership", "listener threw: %s", e);
            }
        }
    }
}
