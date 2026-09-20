package com.swarmcron.exec;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A completed or failed run, as shown on the HTTP dashboard (M9). Recorded
 * both for this node's own runs and for RUN_RESULT broadcasts received from
 * peers, so a dashboard opened against any one node shows cluster-wide
 * activity rather than just what that particular node happened to execute.
 */
public record RunSummary(
        String jobId,
        String nodeId,
        long scheduledFireMillis,
        boolean success,
        Integer exitCode,
        long durationMillis,
        long timestampMillis
) {
    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jobId", jobId);
        m.put("nodeId", nodeId);
        m.put("scheduledFireMillis", scheduledFireMillis);
        m.put("success", success);
        m.put("exitCode", exitCode);
        m.put("durationMillis", durationMillis);
        m.put("timestampMillis", timestampMillis);
        return m;
    }
}
