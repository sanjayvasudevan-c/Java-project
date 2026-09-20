package com.swarmcron.sim;

import com.swarmcron.net.MessageType;
import com.swarmcron.net.PeerAddress;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;

/**
 * In-memory network fabric shared by every SimTransport in one simulation.
 * A send is queued as an event at (now + latency); it is only handed to the
 * destination's handler once the simulation's VirtualClock is advanced past
 * that time. The whole simulation runs on the single thread that calls
 * advanceTo, so there is no concurrency to reason about here  -  no locks, no
 * volatile fields, nothing shared across threads.
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

    private record Event(long deliverAt, long seq, PeerAddress from, PeerAddress to, MessageType type, byte[] payload) {}

    private final VirtualClock clock;
    private final Random random;
    private final Map<PeerAddress, SimTransport> transports = new HashMap<>();
    private final Map<PeerPair, LinkConfig> overrides = new HashMap<>();
    private final PriorityQueue<Event> events =
            new PriorityQueue<>(Comparator.<Event>comparingLong(Event::deliverAt).thenComparingLong(Event::seq));

    private LinkConfig defaultLink = new LinkConfig(1, 1, 0.0);
    private long seqCounter = 0;

    public SimNetwork(VirtualClock clock, long seed) {
        this.clock = clock;
        this.random = new Random(seed);
    }

    public void setDefaultLink(LinkConfig link) {
        this.defaultLink = link;
    }

    /** Overrides the link used for messages sent from -> to (one direction; set both ways for a symmetric link). */
    public void setLink(PeerAddress from, PeerAddress to, LinkConfig link) {
        overrides.put(new PeerPair(from, to), link);
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
        events.add(new Event(deliverAt, seqCounter++, from, to, type, payload));
    }

    /** Delivers every pending event with deliverAt <= targetMillis (earliest first), then advances the clock to targetMillis. */
    public void advanceTo(long targetMillis) {
        while (!events.isEmpty() && events.peek().deliverAt() <= targetMillis) {
            Event e = events.poll();
            clock.advanceTo(e.deliverAt());
            SimTransport dest = transports.get(e.to());
            if (dest != null) {
                dest.deliver(e.from(), e.type(), e.payload());
            }
        }
        clock.advanceTo(targetMillis);
    }

    public int pendingEvents() {
        return events.size();
    }
}
