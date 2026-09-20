package com.swarmcron.sim;

import com.swarmcron.net.MessageType;
import com.swarmcron.net.PeerAddress;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * In-memory network fabric shared by every SimTransport in one simulation.
 * A send is scheduled as an event on SimWorld's shared SimEventQueue at
 * (now + latency); it is only handed to the destination's handler once the
 * simulation's VirtualClock is advanced past that time (see SimWorld.advanceTo).
 * The whole simulation runs on the single thread that drives that advance, so
 * there is no concurrency to reason about here -- no locks, no volatile
 * fields, nothing shared across threads.
 */
public final class SimNetwork {

    public record LinkConfig(long minLatencyMillis, long maxLatencyMillis, double lossRate) {
        public LinkConfig {
            if (minLatencyMillis < 0 || maxLatencyMillis < minLatencyMillis) {
                throw new IllegalArgumentException("invalid latency range [" + minLatencyMillis + "," + maxLatencyMillis + "]");
            }
            if (lossRate < 0.0 || lossRate > 1.0) {
                throw new IllegalArgumentException("lossRate must be in [0,1], got " + lossRate);
            }
        }
    }

    private record PeerPair(PeerAddress from, PeerAddress to) {}

    private final VirtualClock clock;
    private final SimEventQueue eventQueue;
    private final Random random;
    private final Map<PeerAddress, SimTransport> transports = new HashMap<>();
    private final Map<PeerPair, LinkConfig> overrides = new HashMap<>();
    private final Set<PeerPair> blockedPairs = new HashSet<>();

    private LinkConfig defaultLink = new LinkConfig(1, 1, 0.0);

    SimNetwork(VirtualClock clock, SimEventQueue eventQueue, long seed) {
        this.clock = clock;
        this.eventQueue = eventQueue;
        this.random = new Random(seed);
    }

    public void setDefaultLink(LinkConfig link) {
        this.defaultLink = link;
    }

    /** Overrides the link used for messages sent from -> to (one direction; set both ways for a symmetric link). */
    public void setLink(PeerAddress from, PeerAddress to, LinkConfig link) {
        overrides.put(new PeerPair(from, to), link);
    }

    /**
     * Simulates a network partition between a and b: every send in either
     * direction is dropped, for every channel that consults this network
     * (gossip via SimTransport, and anti-entropy via SimSyncChannel) -- a
     * minimal preview of the full PartitionInjector arriving later, but
     * already enough for a scenario that partitions two nodes and heals them.
     */
    public void blockPair(PeerAddress a, PeerAddress b) {
        blockedPairs.add(new PeerPair(a, b));
        blockedPairs.add(new PeerPair(b, a));
    }

    public void unblockPair(PeerAddress a, PeerAddress b) {
        blockedPairs.remove(new PeerPair(a, b));
        blockedPairs.remove(new PeerPair(b, a));
    }

    public boolean isBlocked(PeerAddress from, PeerAddress to) {
        return blockedPairs.contains(new PeerPair(from, to));
    }

    void register(PeerAddress addr, SimTransport transport) {
        transports.put(addr, transport);
    }

    void unregister(PeerAddress addr) {
        transports.remove(addr);
    }

    void enqueueSend(PeerAddress from, PeerAddress to, MessageType type, byte[] payload) {
        LinkConfig link = overrides.getOrDefault(new PeerPair(from, to), defaultLink);
        if (link.lossRate() > 0.0 && random.nextDouble() < link.lossRate()) {
            return;
        }
        long spread = link.maxLatencyMillis() - link.minLatencyMillis();
        long jitter = spread == 0 ? 0 : Math.floorMod(random.nextLong(), spread + 1);
        long deliverAt = clock.nowMillis() + link.minLatencyMillis() + jitter;
        eventQueue.schedule(deliverAt, () -> {
            // Checked at actual delivery time, not send time: a partition
            // imposed after a message is sent but before it would have
            // arrived must still drop it, or a message sent in the same
            // instant a scenario calls blockPair() could slip through.
            if (isBlocked(from, to)) {
                return;
            }
            SimTransport dest = transports.get(to);
            if (dest != null) {
                dest.deliver(from, type, payload);
            }
        });
    }
}
