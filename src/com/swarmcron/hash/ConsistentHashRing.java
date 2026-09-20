package com.swarmcron.hash;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;

/**
 * Consistent hash ring over the cluster's ALIVE node ids. Immutable: every
 * membership change (see RingManager) rebuilds a fresh ring rather than
 * mutating one in place, so a reader holding a reference to one ring never
 * observes a partial rebuild. SHA-1 gives a uniformly-distributed 64-bit
 * position (the first 8 bytes of the digest, as a signed long) for both
 * virtual nodes and lookup keys, without an external hashing library.
 *
 * This is the property that makes it worth the complexity over a plain
 * hash(key) % N: removing or adding one physical node only reassigns the
 * roughly 1/N of keys that were owned by (or land closest to) that node's
 * virtual nodes -- every other key's owner is untouched. See HashRingTest's
 * "minimal disruption" case for a direct demonstration.
 */
public final class ConsistentHashRing {

    private final NavigableMap<Long, String> ring; // position -> physical nodeId
    private final List<VirtualNode> virtualNodes;
    private final List<String> physicalNodes; // sorted, deterministic order

    private ConsistentHashRing(NavigableMap<Long, String> ring, List<VirtualNode> virtualNodes, List<String> physicalNodes) {
        this.ring = ring;
        this.virtualNodes = virtualNodes;
        this.physicalNodes = physicalNodes;
    }

    public static ConsistentHashRing build(Collection<String> aliveNodeIds, int virtualNodesPerNode) {
        NavigableMap<Long, String> map = new TreeMap<>();
        List<VirtualNode> vnodes = new ArrayList<>();
        List<String> sortedIds = new ArrayList<>(new LinkedHashSet<>(aliveNodeIds));
        sortedIds.sort(String::compareTo); // deterministic construction regardless of the caller's iteration order
        for (String nodeId : sortedIds) {
            for (int i = 0; i < virtualNodesPerNode; i++) {
                long position = hash(nodeId + "#" + i);
                map.putIfAbsent(position, nodeId); // an astronomically rare collision keeps the first writer, staying deterministic
                vnodes.add(new VirtualNode(position, nodeId));
            }
        }
        vnodes.sort((a, b) -> Long.compare(a.position(), b.position()));
        return new ConsistentHashRing(map, List.copyOf(vnodes), List.copyOf(sortedIds));
    }

    public boolean isEmpty() {
        return ring.isEmpty();
    }

    public int physicalNodeCount() {
        return physicalNodes.size();
    }

    /** The node id owning key: the first virtual node clockwise from hash(key), wrapping around past the highest position. */
    public String owner(String key) {
        if (ring.isEmpty()) {
            return null;
        }
        Map.Entry<Long, String> entry = ring.ceilingEntry(hash(key));
        return entry != null ? entry.getValue() : ring.firstEntry().getValue();
    }

    /** Up to count distinct physical nodes clockwise from hash(key), owner first. Fewer than count if the ring has fewer physical nodes. */
    public List<String> replicas(String key, int count) {
        List<String> out = new ArrayList<>();
        if (ring.isEmpty() || count <= 0) {
            return out;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String nodeId : ring.tailMap(hash(key), true).values()) {
            if (seen.add(nodeId)) {
                out.add(nodeId);
                if (out.size() == count) {
                    return out;
                }
            }
        }
        // Wrapped past the highest position without finding `count` distinct nodes; continue from the start.
        for (String nodeId : ring.values()) {
            if (seen.add(nodeId)) {
                out.add(nodeId);
                if (out.size() == count) {
                    return out;
                }
            }
        }
        return out;
    }

    public List<VirtualNode> virtualNodes() {
        return virtualNodes;
    }

    public List<String> physicalNodes() {
        return physicalNodes;
    }

    static long hash(String key) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] bytes = digest.digest(key.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(bytes, 0, 8).getLong();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 is required by every JDK implementation", e);
        }
    }
}
