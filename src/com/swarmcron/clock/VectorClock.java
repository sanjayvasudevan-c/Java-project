package com.swarmcron.clock;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Immutable vector clock: nodeId -> logical counter. Used by JobRegistry to
 * decide, for two versions of the same job, whether one strictly happened
 * before the other (causality) or whether they were concurrent writes that
 * need a deterministic tie-break (see state.MergeEngine).
 */
public final class VectorClock {

    public enum Relation { BEFORE, AFTER, EQUAL, CONCURRENT }

    private static final VectorClock EMPTY = new VectorClock(Map.of());

    private final Map<String, Long> counters;

    private VectorClock(Map<String, Long> counters) {
        this.counters = counters;
    }

    public static VectorClock empty() {
        return EMPTY;
    }

    public static VectorClock of(Map<String, Long> counters) {
        return new VectorClock(Map.copyOf(counters));
    }

    public long get(String nodeId) {
        return counters.getOrDefault(nodeId, 0L);
    }

    /** Returns a new clock with nodeId's counter incremented by one -- call on every local edit. */
    public VectorClock tick(String nodeId) {
        Map<String, Long> next = new HashMap<>(counters);
        next.merge(nodeId, 1L, Long::sum);
        return new VectorClock(Map.copyOf(next));
    }

    /** Elementwise max -- the standard vector clock merge, used to fold in a peer's knowledge. */
    public VectorClock merge(VectorClock other) {
        Map<String, Long> next = new HashMap<>(counters);
        for (Map.Entry<String, Long> e : other.counters.entrySet()) {
            next.merge(e.getKey(), e.getValue(), Math::max);
        }
        return new VectorClock(Map.copyOf(next));
    }

    public Relation compareTo(VectorClock other) {
        boolean lessOrEqual = true;
        boolean greaterOrEqual = true;
        Set<String> keys = new HashSet<>(counters.keySet());
        keys.addAll(other.counters.keySet());
        for (String k : keys) {
            long a = get(k);
            long b = other.get(k);
            if (a < b) {
                greaterOrEqual = false;
            }
            if (a > b) {
                lessOrEqual = false;
            }
        }
        if (lessOrEqual && greaterOrEqual) {
            return Relation.EQUAL;
        }
        if (lessOrEqual) {
            return Relation.BEFORE;
        }
        if (greaterOrEqual) {
            return Relation.AFTER;
        }
        return Relation.CONCURRENT;
    }

    public boolean happensBefore(VectorClock other) {
        return compareTo(other) == Relation.BEFORE;
    }

    public boolean concurrentWith(VectorClock other) {
        return compareTo(other) == Relation.CONCURRENT;
    }

    public Map<String, Object> toJson() {
        return new LinkedHashMap<>(counters);
    }

    @SuppressWarnings("unchecked")
    public static VectorClock fromJson(Object raw) {
        Map<String, Object> m = (Map<String, Object>) raw;
        Map<String, Long> counters = new HashMap<>();
        for (Map.Entry<String, Object> e : m.entrySet()) {
            counters.put(e.getKey(), ((Number) e.getValue()).longValue());
        }
        return VectorClock.of(counters);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof VectorClock other && counters.equals(other.counters);
    }

    @Override
    public int hashCode() {
        return counters.hashCode();
    }

    @Override
    public String toString() {
        return counters.toString();
    }
}
