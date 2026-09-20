package com.swarmcron.test;

import com.swarmcron.cluster.MemberInfo;
import com.swarmcron.cluster.MemberUpdate;
import com.swarmcron.cluster.Membership;
import com.swarmcron.cluster.NodeState;
import com.swarmcron.net.PeerAddress;
import com.swarmcron.util.Assert;
import com.swarmcron.util.TestSuite;

import java.util.List;

final class MembershipTest {

    static boolean run() {
        return new TestSuite("MembershipTest")
                .test("accepts a higher incarnation over a lower one", MembershipTest::higherIncarnationWins)
                .test("ignores a lower incarnation even with a higher-rank state", MembershipTest::lowerIncarnationIgnored)
                .test("at equal incarnation, SUSPECT beats ALIVE", MembershipTest::suspectBeatsAliveAtSameIncarnation)
                .test("at equal incarnation, DEAD beats SUSPECT", MembershipTest::deadBeatsSuspectAtSameIncarnation)
                .test("at equal incarnation, does not regress DEAD back to ALIVE", MembershipTest::doesNotRegressDeadToAlive)
                .test("self refutes a suspicion by bumping its own incarnation", MembershipTest::selfRefutesSuspicion)
                .test("aliveMembers excludes SUSPECT/DEAD/LEFT", MembershipTest::aliveMembersFiltersCorrectly)
                .run();
    }

    private static Membership newMembership(String selfId) {
        return new Membership(selfId, new PeerAddress("127.0.0.1", 7000), () -> 1000L);
    }

    private static void higherIncarnationWins() {
        Membership m = newMembership("self");
        PeerAddress addr = new PeerAddress("127.0.0.1", 7001);
        m.merge(new MemberUpdate("peer", addr, NodeState.ALIVE, 1));
        boolean changed = m.merge(new MemberUpdate("peer", addr, NodeState.SUSPECT, 2));
        Assert.that(changed, "higher incarnation update should be applied");
        Assert.equals(NodeState.SUSPECT, m.get("peer").state(), "state after higher-incarnation update");
        Assert.equals(2L, m.get("peer").incarnation(), "incarnation after update");
    }

    private static void lowerIncarnationIgnored() {
        Membership m = newMembership("self");
        PeerAddress addr = new PeerAddress("127.0.0.1", 7001);
        m.merge(new MemberUpdate("peer", addr, NodeState.ALIVE, 5));
        boolean changed = m.merge(new MemberUpdate("peer", addr, NodeState.DEAD, 3));
        Assert.that(!changed, "lower incarnation update should be ignored even if state rank is higher");
        Assert.equals(NodeState.ALIVE, m.get("peer").state(), "state should be unchanged");
    }

    private static void suspectBeatsAliveAtSameIncarnation() {
        Membership m = newMembership("self");
        PeerAddress addr = new PeerAddress("127.0.0.1", 7001);
        m.merge(new MemberUpdate("peer", addr, NodeState.ALIVE, 4));
        boolean changed = m.merge(new MemberUpdate("peer", addr, NodeState.SUSPECT, 4));
        Assert.that(changed, "SUSPECT at same incarnation should override ALIVE");
        Assert.equals(NodeState.SUSPECT, m.get("peer").state(), "state");
    }

    private static void deadBeatsSuspectAtSameIncarnation() {
        Membership m = newMembership("self");
        PeerAddress addr = new PeerAddress("127.0.0.1", 7001);
        m.merge(new MemberUpdate("peer", addr, NodeState.SUSPECT, 4));
        boolean changed = m.merge(new MemberUpdate("peer", addr, NodeState.DEAD, 4));
        Assert.that(changed, "DEAD at same incarnation should override SUSPECT");
        Assert.equals(NodeState.DEAD, m.get("peer").state(), "state");
    }

    private static void doesNotRegressDeadToAlive() {
        Membership m = newMembership("self");
        PeerAddress addr = new PeerAddress("127.0.0.1", 7001);
        m.merge(new MemberUpdate("peer", addr, NodeState.DEAD, 4));
        boolean changed = m.merge(new MemberUpdate("peer", addr, NodeState.ALIVE, 4));
        Assert.that(!changed, "ALIVE at the same incarnation must not undo DEAD");
        Assert.equals(NodeState.DEAD, m.get("peer").state(), "state should remain DEAD");
    }

    private static void selfRefutesSuspicion() {
        Membership m = newMembership("self");
        Assert.equals(0L, m.selfIncarnation(), "starting incarnation");
        boolean changed = m.merge(new MemberUpdate("self", m.selfInfo().address(), NodeState.SUSPECT, 0));
        Assert.that(changed, "self should refute the suspicion");
        Assert.equals(NodeState.ALIVE, m.selfInfo().state(), "self should remain ALIVE after refuting");
        Assert.equals(1L, m.selfIncarnation(), "self incarnation should have been bumped past the suspicion's incarnation");
    }

    private static void aliveMembersFiltersCorrectly() {
        Membership m = newMembership("self");
        m.merge(new MemberUpdate("alive-peer", new PeerAddress("127.0.0.1", 7001), NodeState.ALIVE, 0));
        m.merge(new MemberUpdate("dead-peer", new PeerAddress("127.0.0.1", 7002), NodeState.DEAD, 0));
        m.merge(new MemberUpdate("suspect-peer", new PeerAddress("127.0.0.1", 7003), NodeState.SUSPECT, 0));
        List<String> aliveIds = m.aliveMembers().stream().map(MemberInfo::nodeId).sorted().toList();
        Assert.equals(List.of("alive-peer", "self"), aliveIds, "only self and alive-peer should be ALIVE");
    }
}
