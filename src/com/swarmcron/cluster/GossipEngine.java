package com.swarmcron.cluster;

import java.util.ArrayList;
import java.util.List;

/**
 * Membership-specific glue around GossipBuffer: turns outgoing updates into
 * wire JSON for piggybacking, and applies incoming wire JSON back into
 * Membership, re-enqueuing anything that actually changed local state so it
 * keeps spreading (classic SWIM infection-style dissemination).
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
            MemberUpdate update = MemberUpdate.fromJson(o);
            if (membership.merge(update)) {
                buffer.enqueue(update);
            }
        }
    }

    public int pendingCount() {
        return buffer.size();
    }
}
