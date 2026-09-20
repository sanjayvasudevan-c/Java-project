package com.swarmcron.cluster;

import java.util.ArrayList;
import java.util.List;

/**
 * Membership-specific glue around GossipBuffer: turns outgoing updates into
 * wire JSON for piggybacking, and applies incoming wire JSON back into
 * Membership. Re-dissemination of anything that actually changes local state
 * is handled centrally by whoever calls Membership.addListener (see
 * FailureDetector.eagerlyPropagate) -- deliberately NOT here. The raw
 * incoming claim and the resulting state can differ (self-refutation is
 * exactly this: an incoming DEAD claim about us produces an outgoing ALIVE
 * result), so re-enqueuing the raw claim after a successful merge would be
 * wrong; only the listener, which sees Membership's actual resulting
 * MemberInfo, can re-gossip the correct value.
 */
public final class GossipEngine {

    private static final int DEFAULT_MAX_PIGGYBACK = 8;

    private final Membership membership;
    private final GossipBuffer buffer = new GossipBuffer();
    private final int maxPiggyback;

    public GossipEngine(Membership membership) {
        this(membership, DEFAULT_MAX_PIGGYBACK);
    }

    public GossipEngine(Membership membership, int maxPiggyback) {
        this.membership = membership;
        this.maxPiggyback = maxPiggyback;
    }

    public void enqueue(MemberUpdate update) {
        buffer.enqueue(update);
    }

    public List<Object> takeForPiggyback() {
        List<Object> out = new ArrayList<>();
        for (MemberUpdate u : buffer.take(maxPiggyback)) {
            out.add(u.toJson());
        }
        return out;
    }

    public void applyIncoming(Object rawList) {
        if (!(rawList instanceof List<?> list)) {
            return;
        }
        for (Object o : list) {
            membership.merge(MemberUpdate.fromJson(o));
        }
    }

    public int pendingCount() {
        return buffer.size();
    }
}
