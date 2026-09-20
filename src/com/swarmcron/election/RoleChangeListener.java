package com.swarmcron.election;

/** Notified whenever this node's Raft role changes (used by the dashboard's leader/term banner in M9). */
@FunctionalInterface
public interface RoleChangeListener {
    void onRoleChanged(RaftLite.Role newRole, long term, String leaderId);
}
