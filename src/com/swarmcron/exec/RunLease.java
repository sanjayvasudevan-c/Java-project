package com.swarmcron.exec;

import com.swarmcron.election.FencingToken;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A time-bounded, fencing-token-backed right for exactly one node to execute
 * one job. The ring (see hash/ConsistentHashRing) picks a *hint* for who
 * should own a job, but two nodes' local views of ring membership can
 * briefly disagree while SWIM gossip is still converging after a membership
 * change -- a lease is what bounds the damage from that disagreement: the
 * token comes from the single elected Raft-lite leader, a far more stable
 * source of truth than raw ring agreement, and expiresAtMillis caps how long
 * a claim is honored even if the owner goes silent without anyone hearing
 * about it.
 */
public record RunLease(String jobId, String ownerId, FencingToken token, long expiresAtMillis) {

    public boolean isExpired(long nowMillis) {
        return nowMillis >= expiresAtMillis;
    }

    public boolean isHeldBy(String nodeId) {
        return ownerId.equals(nodeId);
    }

    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jobId", jobId);
        m.put("ownerId", ownerId);
        m.put("term", token.term());
        m.put("sequence", token.sequence());
        m.put("expiresAtMillis", expiresAtMillis);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static RunLease fromJson(Object raw) {
        Map<String, Object> m = (Map<String, Object>) raw;
        String jobId = (String) m.get("jobId");
        String ownerId = (String) m.get("ownerId");
        long term = ((Number) m.get("term")).longValue();
        long sequence = ((Number) m.get("sequence")).longValue();
        long expiresAtMillis = ((Number) m.get("expiresAtMillis")).longValue();
        return new RunLease(jobId, ownerId, new FencingToken(term, sequence), expiresAtMillis);
    }
}
