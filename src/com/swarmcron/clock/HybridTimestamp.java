package com.swarmcron.clock;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * (physicalMillis, logicalCounter) pair produced by HybridClock. Totally
 * ordered via compareTo, and physicalMillis alone is human-readable enough
 * for a dashboard timeline -- that's the point of a hybrid clock over a bare
 * Lamport counter.
 */
public record HybridTimestamp(long physicalMillis, long logicalCounter) implements Comparable<HybridTimestamp> {

    @Override
    public int compareTo(HybridTimestamp o) {
        int c = Long.compare(physicalMillis, o.physicalMillis);
        return c != 0 ? c : Long.compare(logicalCounter, o.logicalCounter);
    }

    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("physicalMillis", physicalMillis);
        m.put("logicalCounter", logicalCounter);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static HybridTimestamp fromJson(Object raw) {
        Map<String, Object> m = (Map<String, Object>) raw;
        return new HybridTimestamp(((Number) m.get("physicalMillis")).longValue(), ((Number) m.get("logicalCounter")).longValue());
    }
}
