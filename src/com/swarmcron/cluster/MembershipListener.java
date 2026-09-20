package com.swarmcron.cluster;

/** Notified whenever a member's recorded state actually changes (used by the hash ring in M5, the dashboard/SSE feed in M9). */
@FunctionalInterface
public interface MembershipListener {
    void onMembershipChanged(MemberInfo info);
}
