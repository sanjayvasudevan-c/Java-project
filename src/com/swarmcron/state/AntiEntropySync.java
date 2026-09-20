package com.swarmcron.state;

import com.swarmcron.clock.Scheduler;
import com.swarmcron.clock.VectorClock;
import com.swarmcron.cluster.MemberInfo;
import com.swarmcron.cluster.Membership;
import com.swarmcron.net.MessageType;
import com.swarmcron.net.PeerAddress;
import com.swarmcron.net.SyncChannel;
import com.swarmcron.util.Json;
import com.swarmcron.util.Log;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.UnaryOperator;

/**
 * Periodically pulls JobRegistry state from one random ALIVE peer: send our
 * digest (jobId -> vector clock), receive back whatever entries the peer
 * thinks we're missing or behind on, and merge them in. This is what
 * guarantees eventual convergence even if a future gossip-piggyback path for
 * job updates drops something, and it's what heals a registry split by a
 * network partition once the partition clears -- the pull itself is
 * one-directional, but every node in the cluster runs it, so information
 * flows both ways over time.
 *
 * Membership tracks peers by their gossip (UDP) address, which is not
 * necessarily where their SyncChannel listens (in production it's
 * gossipPort + 1; see Main). syncAddressResolver converts one to the other --
 * production wires in the +1 convention, while the simulator wires in the
 * identity function, since sim.SimSwarmNode uses one opaque PeerAddress for
 * both channels. Keeping that conversion here as an injected function (not
 * hardcoded) is what let this exact class stay shared between production and
 * simulation while still being correct in both.
 */
public final class AntiEntropySync {

    private final Membership membership;
    private final JobRegistry registry;
    private final SyncChannel syncChannel;
    private final Scheduler scheduler;
    private final long intervalMillis;
    private final Random random;
    private final UnaryOperator<PeerAddress> syncAddressResolver;

    public AntiEntropySync(Membership membership, JobRegistry registry, SyncChannel syncChannel,
                            Scheduler scheduler, long intervalMillis, long randomSeed,
                            UnaryOperator<PeerAddress> syncAddressResolver) {
        this.membership = membership;
        this.registry = registry;
        this.syncChannel = syncChannel;
        this.scheduler = scheduler;
        this.intervalMillis = intervalMillis;
        this.random = new Random(randomSeed);
        this.syncAddressResolver = syncAddressResolver;
    }

    public void start() {
        syncChannel.start(this::handleRequest);
        scheduler.scheduleAtFixedRate(this::tick, intervalMillis, intervalMillis);
    }

    private void tick() {
        List<MemberInfo> candidates = membership.aliveMembers();
        candidates.removeIf(m -> m.nodeId().equals(membership.selfId()));
        if (candidates.isEmpty()) {
            return;
        }
        MemberInfo peer = candidates.get(random.nextInt(candidates.size()));
        syncWith(syncAddressResolver.apply(peer.address()));
    }

    /**
     * Pulls from a specific peer right now, outside the periodic schedule
     * (e.g. right after a partition heals, or from a test). Takes the peer's
     * SyncChannel address directly (already resolved), not its gossip address.
     */
    public void syncWith(PeerAddress peer) {
        Map<String, Object> digestJson = new LinkedHashMap<>();
        for (Map.Entry<String, VectorClock> e : registry.digest().entrySet()) {
            digestJson.put(e.getKey(), e.getValue().toJson());
        }
        byte[] payload = Json.write(digestJson).getBytes(StandardCharsets.UTF_8);
        syncChannel.request(peer, MessageType.SYNC_DIGEST, payload, response -> {
            if (response == null) {
                Log.warn("anti-entropy", "sync with %s failed or timed out", peer);
                return;
            }
            applyDelta(response);
        });
    }

    @SuppressWarnings("unchecked")
    private void applyDelta(byte[] payload) {
        Map<String, Object> body = (Map<String, Object>) Json.parse(new String(payload, StandardCharsets.UTF_8));
        List<Object> entries = (List<Object>) body.get("entries");
        int mergedCount = 0;
        for (Object raw : entries) {
            if (registry.merge(JobEntry.fromJson(raw))) {
                mergedCount++;
            }
        }
        if (mergedCount > 0) {
            Log.info("anti-entropy", "merged %d job update(s) from peer sync", mergedCount);
        }
    }

    @SuppressWarnings("unchecked")
    private byte[] handleRequest(PeerAddress from, MessageType type, byte[] payload) {
        Map<String, Object> digestJson = (Map<String, Object>) Json.parse(new String(payload, StandardCharsets.UTF_8));
        Map<String, VectorClock> peerDigest = new HashMap<>();
        for (Map.Entry<String, Object> e : digestJson.entrySet()) {
            peerDigest.put(e.getKey(), VectorClock.fromJson(e.getValue()));
        }
        List<JobEntry> missing = registry.computeMissingFor(peerDigest);
        List<Object> entriesJson = new ArrayList<>();
        for (JobEntry entry : missing) {
            entriesJson.add(entry.toJson());
        }
        Map<String, Object> responseBody = new LinkedHashMap<>();
        responseBody.put("entries", entriesJson);
        return Json.write(responseBody).getBytes(StandardCharsets.UTF_8);
    }
}
