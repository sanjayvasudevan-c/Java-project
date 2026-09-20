package com.swarmcron.cluster;

import com.swarmcron.net.PeerAddress;

import java.util.LinkedHashMap;
import java.util.Map;

/** Wire form of a membership delta -- what actually gets piggybacked on PING/ACK frames. */
public record MemberUpdate(String nodeId, PeerAddress address, NodeState state, long incarnation) {

    public static MemberUpdate from(MemberInfo info) {
        return new MemberUpdate(info.nodeId(), info.address(), info.state(), info.incarnation());
    }

    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nodeId", nodeId);
        m.put("host", address.host());
        m.put("port", address.port());
        m.put("state", state.name());
        m.put("incarnation", incarnation);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static MemberUpdate fromJson(Object obj) {
        Map<String, Object> m = (Map<String, Object>) obj;
        String nodeId = (String) m.get("nodeId");
        String host = (String) m.get("host");
        int port = ((Number) m.get("port")).intValue();
        NodeState state = NodeState.valueOf((String) m.get("state"));
        long incarnation = ((Number) m.get("incarnation")).longValue();
        return new MemberUpdate(nodeId, new PeerAddress(host, port), state, incarnation);
    }
}
