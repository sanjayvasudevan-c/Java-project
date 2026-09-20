package com.swarmcron.cluster;

import com.swarmcron.net.PeerAddress;

/** One row of the local membership table. lastChangedMillis is this node's own clock time of the last state transition. */
public record MemberInfo(String nodeId, PeerAddress address, NodeState state, long incarnation, long lastChangedMillis) {
}
