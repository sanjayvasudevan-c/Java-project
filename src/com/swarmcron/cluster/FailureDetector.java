package com.swarmcron.cluster;

import com.swarmcron.clock.Clock;
import com.swarmcron.clock.Scheduler;
import com.swarmcron.net.MessageType;
import com.swarmcron.net.PeerAddress;
import com.swarmcron.net.Transport;
import com.swarmcron.util.Json;
import com.swarmcron.util.Log;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SWIM failure detector: one protocol-period tick probes a random ALIVE peer
 * directly; on timeout it fans out indirect probes via k random relays;
 * total silence marks the peer SUSPECT (gossiped), and an un-refuted
 * suspicion marks it DEAD after suspicionTimeout.
 *
 * Probe correlation uses (originatorId, probeId) rather than a bare counter:
 * probeId alone is only unique within the node that generated it, so a relay
 * node juggling its own probes (in pendingProbes) alongside probes it is
 * relaying for other nodes (in relayRequesters) could otherwise see two
 * different nodes' counters collide on the same numeric value. originatorId
 * disambiguates whose counter namespace a given probeId belongs to.
 *
 * All periodic/timeout work goes through Scheduler (never Thread.sleep), so
 * the exact same code runs under SystemScheduler in production and
 * SimScheduler in the simulator. pendingProbes/relayRequesters are
 * ConcurrentHashMaps because in production the selector thread (delivering
 * ACKs) and the scheduler thread (firing timeouts) touch them concurrently;
 * under SimScheduler both happen on the single sim-driving thread, so this
 * is belt-and-braces there.
 */
public final class FailureDetector {

    public record Config(long protocolPeriodMillis, long pingTimeoutMillis, int indirectProbeCount, int suspicionMultiplier) {
        public long suspicionTimeoutMillis() {
            return protocolPeriodMillis * suspicionMultiplier;
        }
    }

    private static final int MAX_PIGGYBACK_UPDATES = 8;

    private final Membership membership;
    private final Transport transport;
    private final GossipEngine gossipEngine;
    private final Scheduler scheduler;
    private final Clock clock;
    private final Config config;
    private final List<PeerAddress> seedAddresses;
    private final Random random;

    private final AtomicLong probeIdGenerator = new AtomicLong();
    private final Map<Long, PendingProbe> pendingProbes = new ConcurrentHashMap<>();
    private final Map<String, PeerAddress> relayRequesters = new ConcurrentHashMap<>();

    private record PendingProbe(String targetId, PeerAddress targetAddress) {}

    /**
     * randomSeed drives target/relay selection. Production callers (Main)
     * should pass real entropy (e.g. a fresh random seed per process);
     * simulation callers (SimSwarmNode) must pass a fixed seed so a scenario
     * is fully reproducible -- an unseeded Random here would silently
     * reintroduce nondeterminism into an otherwise deterministic simulation.
     */
    public FailureDetector(Membership membership, Transport transport, GossipEngine gossipEngine,
                            Scheduler scheduler, Clock clock, Config config, List<PeerAddress> seedAddresses,
                            long randomSeed) {
        this.membership = membership;
        this.transport = transport;
        this.gossipEngine = gossipEngine;
        this.scheduler = scheduler;
        this.clock = clock;
        this.config = config;
        this.seedAddresses = seedAddresses;
        this.random = new Random(randomSeed);
        // Eagerly fan a few pings out to random peers the instant any membership
        // state changes, instead of waiting for the next scheduled tick to carry
        // it as a piggyback. Without this, a node that didn't do its own direct
        // detection of a failure only learns the verdict on its next scheduled
        // exchange with the node that did -- up to a full extra protocolPeriod
        // on top of the detecting node's own suspicion timeout, which is exactly
        // the gap that can push cluster-wide convergence past the target bound.
        membership.addListener(this::eagerlyPropagate);
    }

    public void start() {
        scheduler.scheduleAtFixedRate(this::tick, config.protocolPeriodMillis(), config.protocolPeriodMillis());
    }

    private static final int EAGER_FANOUT = 3;

    private void eagerlyPropagate(MemberInfo info) {
        // Every actual state change lands here (this listener fires on every
        // Membership.merge/mergeSelf, regardless of source), so this is the one
        // place that reliably enqueues it into the retry-backed GossipBuffer.
        // This matters most for self-refutation: mergeSelf() has no other path
        // that enqueues the refutation, so without this, a refutation whose
        // eager push below gets lost to packet loss would vanish for good --
        // the node would stay wrongly DEAD in every peer that missed the push,
        // with no periodic-piggyback retry to eventually correct it.
        gossipEngine.enqueue(MemberUpdate.from(info));
        // Note: this fires only on an actual state transition (see Membership.merge/
        // mergeSelf), and self only ever transitions here via a refutation -- there is
        // no separate "routine self-liveness" case to special-case away. A refutation
        // is exactly as urgent as any other change, so it always gets the eager push too.
        List<MemberInfo> alive = membership.aliveMembers();
        alive.removeIf(m -> m.nodeId().equals(membership.selfId()));
        Collections.shuffle(alive, random);
        int fanout = Math.min(EAGER_FANOUT, alive.size());
        // Pass the triggering update directly rather than relying on it already
        // being in the gossip buffer: this listener runs *during* Membership.merge(),
        // synchronously and before the caller's own gossipEngine.enqueue(update) for
        // this same change has had a chance to run, so the buffer would not yet
        // contain it.
        MemberUpdate urgent = MemberUpdate.from(info);
        for (int i = 0; i < fanout; i++) {
            sendPing(probeIdGenerator.incrementAndGet(), membership.selfId(), alive.get(i).address(), urgent);
        }
    }

    public void onMessage(PeerAddress from, MessageType type, byte[] payload) {
        try {
            switch (type) {
                case PING -> handlePing(from, payload);
                case ACK -> handleAck(from, payload);
                case PING_REQ -> handlePingReq(from, payload);
                default -> { /* not ours */ }
            }
        } catch (RuntimeException e) {
            Log.error("swim", "failed to handle %s from %s: %s", type, from, e);
        }
    }

    private void tick() {
        // Re-announce ourselves to configured seeds every period -- cheap, and
        // makes bootstrap self-healing regardless of which node started first.
        for (PeerAddress seed : seedAddresses) {
            sendPing(probeIdGenerator.incrementAndGet(), membership.selfId(), seed);
        }
        // Candidates include SUSPECT peers, not just ALIVE ones: a SUSPECT node
        // still needs continued direct pings both to give it more chances to
        // hear (and refute) the suspicion via our piggybacked gossip, and to
        // give us more chances to notice it really has stopped responding.
        // Excluding SUSPECT here would leave it in limbo, dependent entirely on
        // gossip arriving through a third party.
        List<MemberInfo> candidates = new ArrayList<>();
        for (MemberInfo m : membership.all()) {
            if (!m.nodeId().equals(membership.selfId()) && (m.state() == NodeState.ALIVE || m.state() == NodeState.SUSPECT)) {
                candidates.add(m);
            }
        }
        if (candidates.isEmpty()) {
            return;
        }
        MemberInfo target = candidates.get(random.nextInt(candidates.size()));
        probe(target.nodeId(), target.address());
    }

    private void probe(String targetId, PeerAddress targetAddress) {
        long probeId = probeIdGenerator.incrementAndGet();
        pendingProbes.put(probeId, new PendingProbe(targetId, targetAddress));
        sendPing(probeId, membership.selfId(), targetAddress);
        scheduler.scheduleOnce(() -> onPingTimeout(probeId), config.pingTimeoutMillis());
    }

    private void onPingTimeout(long probeId) {
        PendingProbe pending = pendingProbes.get(probeId);
        if (pending == null) {
            return; // already acked directly
        }
        List<MemberInfo> relays = membership.aliveMembers();
        relays.removeIf(m -> m.nodeId().equals(membership.selfId()) || m.nodeId().equals(pending.targetId()));
        Collections.shuffle(relays, random);
        int k = Math.min(config.indirectProbeCount(), relays.size());
        for (int i = 0; i < k; i++) {
            sendPingReq(probeId, relays.get(i).address(), pending.targetId(), pending.targetAddress());
        }
        scheduler.scheduleOnce(() -> onIndirectTimeout(probeId), config.pingTimeoutMillis());
    }

    private void onIndirectTimeout(long probeId) {
        PendingProbe pending = pendingProbes.remove(probeId);
        if (pending == null) {
            return; // acked via some path in the meantime
        }
        Log.warn("swim", "[%s] no ack for %s (probe %d) via direct or indirect probe -- marking SUSPECT",
                membership.selfId(), pending.targetId(), probeId);
        markSuspect(pending.targetId(), pending.targetAddress());
    }

    private void sendPing(long probeId, String originatorId, PeerAddress to) {
        sendPing(probeId, originatorId, to, null);
    }

    private void sendPing(long probeId, String originatorId, PeerAddress to, MemberUpdate extra) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("probeId", probeId);
        body.put("originatorId", originatorId);
        body.put("from", membership.selfId());
        body.put("incarnation", membership.selfIncarnation());
        List<Object> updates = gossipEngine.takeForPiggyback();
        if (extra != null) {
            updates.add(extra.toJson());
        }
        body.put("updates", updates);
        transport.send(to, MessageType.PING, Json.write(body).getBytes(StandardCharsets.UTF_8));
    }

    private void sendPingReq(long probeId, PeerAddress relay, String targetId, PeerAddress targetAddress) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("probeId", probeId);
        body.put("originatorId", membership.selfId());
        body.put("targetId", targetId);
        body.put("targetHost", targetAddress.host());
        body.put("targetPort", targetAddress.port());
        transport.send(relay, MessageType.PING_REQ, Json.write(body).getBytes(StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    private void handlePing(PeerAddress from, byte[] payload) {
        Map<String, Object> body = (Map<String, Object>) Json.parse(new String(payload, StandardCharsets.UTF_8));
        long probeId = ((Number) body.get("probeId")).longValue();
        String originatorId = (String) body.get("originatorId");
        String senderId = (String) body.get("from");
        long senderIncarnation = ((Number) body.get("incarnation")).longValue();

        ensureKnown(senderId, from, senderIncarnation);
        gossipEngine.applyIncoming(body.get("updates"));

        Map<String, Object> ack = new LinkedHashMap<>();
        ack.put("probeId", probeId);
        ack.put("originatorId", originatorId);
        ack.put("from", membership.selfId());
        ack.put("incarnation", membership.selfIncarnation());
        ack.put("updates", gossipEngine.takeForPiggyback());
        transport.send(from, MessageType.ACK, Json.write(ack).getBytes(StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    private void handleAck(PeerAddress from, byte[] payload) {
        Map<String, Object> body = (Map<String, Object>) Json.parse(new String(payload, StandardCharsets.UTF_8));
        long probeId = ((Number) body.get("probeId")).longValue();
        String originatorId = (String) body.get("originatorId");
        String senderId = (String) body.get("from");
        long senderIncarnation = ((Number) body.get("incarnation")).longValue();

        ensureKnown(senderId, from, senderIncarnation);
        gossipEngine.applyIncoming(body.get("updates"));

        if (originatorId.equals(membership.selfId())) {
            pendingProbes.remove(probeId);
            return;
        }
        PeerAddress requester = relayRequesters.remove(relayKey(originatorId, probeId));
        if (requester != null) {
            transport.send(requester, MessageType.ACK, payload);
        }
    }

    @SuppressWarnings("unchecked")
    private void handlePingReq(PeerAddress from, byte[] payload) {
        Map<String, Object> body = (Map<String, Object>) Json.parse(new String(payload, StandardCharsets.UTF_8));
        long probeId = ((Number) body.get("probeId")).longValue();
        String originatorId = (String) body.get("originatorId");
        String targetId = (String) body.get("targetId");
        PeerAddress targetAddress = new PeerAddress((String) body.get("targetHost"), ((Number) body.get("targetPort")).intValue());

        String key = relayKey(originatorId, probeId);
        relayRequesters.put(key, from);
        sendPing(probeId, originatorId, targetAddress);
        scheduler.scheduleOnce(() -> relayRequesters.remove(key), config.pingTimeoutMillis());
    }

    private void ensureKnown(String nodeId, PeerAddress address, long incarnation) {
        if (nodeId.equals(membership.selfId()) || membership.get(nodeId) != null) {
            return;
        }
        MemberUpdate update = new MemberUpdate(nodeId, address, NodeState.ALIVE, incarnation);
        if (membership.merge(update)) {
            gossipEngine.enqueue(update);
        }
    }

    private void markSuspect(String nodeId, PeerAddress address) {
        MemberInfo current = membership.get(nodeId);
        long incarnation = current != null ? current.incarnation() : 0;
        MemberUpdate update = new MemberUpdate(nodeId, address, NodeState.SUSPECT, incarnation);
        if (membership.merge(update)) {
            gossipEngine.enqueue(update);
        }
        scheduler.scheduleOnce(() -> onSuspicionTimeout(nodeId, incarnation), config.suspicionTimeoutMillis());
    }

    private void onSuspicionTimeout(String nodeId, long trackedIncarnation) {
        MemberInfo current = membership.get(nodeId);
        if (current == null || current.state() != NodeState.SUSPECT || current.incarnation() != trackedIncarnation) {
            return; // refuted, or superseded by fresher info in the meantime
        }
        Log.warn("swim", "[%s] suspicion timeout elapsed for %s -- marking DEAD", membership.selfId(), nodeId);
        MemberUpdate update = new MemberUpdate(nodeId, current.address(), NodeState.DEAD, trackedIncarnation);
        if (membership.merge(update)) {
            gossipEngine.enqueue(update);
        }
    }

    private static String relayKey(String originatorId, long probeId) {
        return originatorId + "#" + probeId;
    }
}
