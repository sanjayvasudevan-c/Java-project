package com.swarmcron.cluster;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Bounded queue of membership deltas waiting to be piggybacked on outgoing
 * PING/ACK frames. Each update is gossiped up to maxTransmits times, then
 * dropped -- the classic SWIM "infection counter" so nodes stop retransmitting
 * a stale event forever. Least-recently-gossiped entries are preferred (FIFO
 * within take()) so every update gets a fair chance to spread before it's
 * retired. All access is synchronized: this is touched from both the
 * selector/handler thread (incoming gossip) and the scheduler thread
 * (locally-decided suspicions), which can run concurrently in production.
 */
public final class GossipBuffer {

    private static final int DEFAULT_MAX_TRANSMITS = 6;

    private final int maxTransmits;
    private final Deque<Entry> queue = new ArrayDeque<>();

    public GossipBuffer() {
        this(DEFAULT_MAX_TRANSMITS);
    }

    public GossipBuffer(int maxTransmits) {
        this.maxTransmits = maxTransmits;
    }

    public synchronized void enqueue(MemberUpdate update) {
        // A fresher update for the same node supersedes anything already queued for it.
        queue.removeIf(e -> e.update.nodeId().equals(update.nodeId()));
        queue.addLast(new Entry(update, 0));
    }

    /** Pulls up to maxCount least-recently-gossiped updates, incrementing their transmit counts. */
    public synchronized List<MemberUpdate> take(int maxCount) {
        List<MemberUpdate> out = new ArrayList<>();
        List<Entry> requeue = new ArrayList<>();
        int taken = 0;
        while (!queue.isEmpty() && taken < maxCount) {
            Entry e = queue.pollFirst();
            out.add(e.update);
            taken++;
            if (e.transmits + 1 < maxTransmits) {
                requeue.add(new Entry(e.update, e.transmits + 1));
            }
        }
        for (Entry e : requeue) {
            queue.addLast(e);
        }
        return out;
    }

    public synchronized int size() {
        return queue.size();
    }

    private record Entry(MemberUpdate update, int transmits) {}
}
