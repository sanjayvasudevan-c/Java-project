package com.swarmcron.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable description of a cron job, as it appears in jobs.json and as it is
 * carried inside JobRegistry entries. Loaded from file only as a seed on first
 * boot (see JobsFile)  -  after that the gossiped registry is authoritative.
 */
public record JobSpec(
        String id,
        String schedule,
        List<String> command,
        String workingDir,
        int timeoutSeconds,
        int maxRetries,
        long backoffMillis,
        String overlapPolicy,
        boolean enabled
) {
    public static final String OVERLAP_SKIP = "SKIP";
    public static final String OVERLAP_QUEUE = "QUEUE";
    public static final String OVERLAP_PARALLEL = "PARALLEL";

    @SuppressWarnings("unchecked")
    public static JobSpec fromJson(Map<String, Object> obj) {
        String id = require(obj, "id");
        String schedule = require(obj, "schedule");
        List<String> command = new ArrayList<>();
        for (Object o : (List<Object>) obj.getOrDefault("command", List.of())) {
            command.add(String.valueOf(o));
        }
        if (command.isEmpty()) {
            throw new IllegalArgumentException("Job '" + id + "' has an empty command");
        }
        String workingDir = (String) obj.getOrDefault("workingDir", ".");
        int timeoutSeconds = intOf(obj, "timeoutSeconds", 0);
        int maxRetries = intOf(obj, "maxRetries", 0);
        long backoffMillis = longOf(obj, "backoffMillis", 0L);
        String overlapPolicy = (String) obj.getOrDefault("overlapPolicy", OVERLAP_SKIP);
        boolean enabled = (Boolean) obj.getOrDefault("enabled", Boolean.TRUE);
        return new JobSpec(id, schedule, List.copyOf(command), workingDir, timeoutSeconds,
                maxRetries, backoffMillis, overlapPolicy, enabled);
    }

    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("schedule", schedule);
        m.put("command", command);
        m.put("workingDir", workingDir);
        m.put("timeoutSeconds", timeoutSeconds);
        m.put("maxRetries", maxRetries);
        m.put("backoffMillis", backoffMillis);
        m.put("overlapPolicy", overlapPolicy);
        m.put("enabled", enabled);
        return m;
    }

    private static String require(Map<String, Object> obj, String key) {
        Object v = obj.get(key);
        if (v == null) {
            throw new IllegalArgumentException("Job spec missing required field: " + key);
        }
        return String.valueOf(v);
    }

    private static int intOf(Map<String, Object> obj, String key, int def) {
        Object v = obj.get(key);
        return v == null ? def : ((Number) v).intValue();
    }

    private static long longOf(Map<String, Object> obj, String key, long def) {
        Object v = obj.get(key);
        return v == null ? def : ((Number) v).longValue();
    }
}
