package com.swarmcron.store;

import com.swarmcron.util.Json;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One entry in the run ledger: "node X observed event E for job J's firing
 * scheduled at T, attempt N, at wall-clock time W." STARTED/COMPLETED/FAILED
 * bracket one execution attempt; a STARTED with no matching COMPLETED/FAILED
 * later in the log means the process that wrote it crashed mid-run (see
 * Recovery). fencingTerm/fencingSequence are null for SKIPPED (no lease was
 * ever acquired for that firing).
 */
public record WalRecord(
        String jobId,
        long scheduledFireMillis,
        int attempt,
        Event event,
        long timestampMillis,
        Integer exitCode,
        Long fencingTerm,
        Long fencingSequence,
        String note
) {
    public enum Event { STARTED, COMPLETED, FAILED, SKIPPED }

    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jobId", jobId);
        m.put("scheduledFireMillis", scheduledFireMillis);
        m.put("attempt", attempt);
        m.put("event", event.name());
        m.put("timestampMillis", timestampMillis);
        m.put("exitCode", exitCode);
        m.put("fencingTerm", fencingTerm);
        m.put("fencingSequence", fencingSequence);
        m.put("note", note);
        return m;
    }

    public byte[] encode() {
        return Json.write(toJson()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    public static WalRecord decode(byte[] bytes) {
        Map<String, Object> m = (Map<String, Object>) Json.parse(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
        return new WalRecord(
                (String) m.get("jobId"),
                ((Number) m.get("scheduledFireMillis")).longValue(),
                ((Number) m.get("attempt")).intValue(),
                Event.valueOf((String) m.get("event")),
                ((Number) m.get("timestampMillis")).longValue(),
                m.get("exitCode") == null ? null : ((Number) m.get("exitCode")).intValue(),
                m.get("fencingTerm") == null ? null : ((Number) m.get("fencingTerm")).longValue(),
                m.get("fencingSequence") == null ? null : ((Number) m.get("fencingSequence")).longValue(),
                (String) m.get("note")
        );
    }
}
