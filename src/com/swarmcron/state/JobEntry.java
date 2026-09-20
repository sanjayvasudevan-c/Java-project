package com.swarmcron.state;

import com.swarmcron.clock.HybridTimestamp;
import com.swarmcron.clock.VectorClock;
import com.swarmcron.config.JobSpec;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One row of the JobRegistry OR-map. spec is null for a pure tombstone with
 * no prior known spec (deleting a job nobody on this node ever saw). Every
 * transition (local edit, merge) produces a new JobEntry; none is ever
 * mutated in place.
 */
public record JobEntry(
        String jobId,
        JobSpec spec,
        VectorClock clock,
        boolean tombstone,
        String lastWriterNodeId,
        HybridTimestamp timestamp
) {
    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jobId", jobId);
        m.put("spec", spec == null ? null : spec.toJson());
        m.put("clock", clock.toJson());
        m.put("tombstone", tombstone);
        m.put("lastWriterNodeId", lastWriterNodeId);
        m.put("timestamp", timestamp.toJson());
        return m;
    }

    @SuppressWarnings("unchecked")
    public static JobEntry fromJson(Object raw) {
        Map<String, Object> m = (Map<String, Object>) raw;
        String jobId = (String) m.get("jobId");
        Object specRaw = m.get("spec");
        JobSpec spec = specRaw == null ? null : JobSpec.fromJson((Map<String, Object>) specRaw);
        VectorClock clock = VectorClock.fromJson(m.get("clock"));
        boolean tombstone = (Boolean) m.get("tombstone");
        String lastWriterNodeId = (String) m.get("lastWriterNodeId");
        HybridTimestamp timestamp = HybridTimestamp.fromJson(m.get("timestamp"));
        return new JobEntry(jobId, spec, clock, tombstone, lastWriterNodeId, timestamp);
    }
}
