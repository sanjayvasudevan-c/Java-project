package com.swarmcron.test;

import com.swarmcron.cluster.MemberUpdate;
import com.swarmcron.cluster.Membership;
import com.swarmcron.cluster.NodeState;
import com.swarmcron.election.FencingToken;
import com.swarmcron.election.InMemoryTermStore;
import com.swarmcron.election.RaftLite;
import com.swarmcron.net.MessageHandler;
import com.swarmcron.net.MessageType;
import com.swarmcron.net.PeerAddress;
import com.swarmcron.net.Transport;
import com.swarmcron.sim.SimTransport;
import com.swarmcron.sim.SimWorld;
import com.swarmcron.util.Assert;
import com.swarmcron.util.Json;
import com.swarmcron.util.TestSuite;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class RaftLiteTest {

    static boolean run() {
        return new TestSuite("RaftLiteTest")
                .test("a single node elects itself leader", RaftLiteTest::singleNodeElectsSelf)
                .test("a 3-node cluster elects exactly one leader and agrees on the term", RaftLiteTest::threeNodeClusterElectsOneLeader)
                .test("a follower grants at most one vote per term", RaftLiteTest::grantsAtMostOneVotePerTerm)
                .test("seeing a higher term causes an immediate step-down to follower", RaftLiteTest::higherTermCausesStepDown)
                .test("a node that cannot see a majority of known members marks itself DEGRADED", RaftLiteTest::minorityIsDegraded)
                .test("a leader that loses majority visibility steps down", RaftLiteTest::leaderStepsDownOnLostMajority)
                .test("fencing tokens increase monotonically within a term", RaftLiteTest::fencingTokensIncreaseMonotonically)
                .run();
    }

    // ---- test scaffolding ----

    private record RaftNode(String id, PeerAddress address, Membership membership, RaftLite raft) {}

    private static RaftNode newClusterNode(SimWorld world, String id, PeerAddress address, List<PeerAddress> allAddresses, List<String> allIds) {
        Membership membership = new Membership(id, address, world.clock());
        for (int i = 0; i < allIds.size(); i++) {
            if (!allIds.get(i).equals(id)) {
                membership.merge(new MemberUpdate(allIds.get(i), allAddresses.get(i), NodeState.ALIVE, 0));
            }
        }
        SimTransport transport = new SimTransport(address, world.network());
        RaftLite raft = new RaftLite(membership, transport, world.scheduler(), new InMemoryTermStore(), id.hashCode());
        transport.start(raft::onMessage);
        raft.start();
        return new RaftNode(id, address, membership, raft);
    }

    private static List<RaftNode> cluster(SimWorld world, int n) {
        List<String> ids = new ArrayList<>();
        List<PeerAddress> addrs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ids.add("n" + i);
            addrs.add(new PeerAddress("sim", i + 1));
        }
        List<RaftNode> nodes = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            nodes.add(newClusterNode(world, ids.get(i), addrs.get(i), addrs, ids));
        }
        return nodes;
    }

    /** Records every send() call instead of actually delivering anything -- for tests that need to inspect exactly what RaftLite sent in response to a directly-injected message. */
    private static final class RecordingTransport implements Transport {
        final List<Object[]> sent = new ArrayList<>(); // {PeerAddress to, MessageType type, byte[] payload}

        @Override
        public void start(MessageHandler handler) { /* nothing to receive in these tests */ }

        @Override
        public void send(PeerAddress to, MessageType type, byte[] payload) {
            sent.add(new Object[]{to, type, payload});
        }

        @Override
        public PeerAddress localAddress() {
            return new PeerAddress("sim", 0);
        }

        @Override
        public void stop() { /* nothing to stop */ }
    }

    private static byte[] requestVotePayload(long term, String candidateId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("term", term);
        body.put("candidateId", candidateId);
        return Json.write(body).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] heartbeatPayload(long term, String leaderId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("term", term);
        body.put("leaderId", leaderId);
        return Json.write(body).getBytes(StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private static boolean extractGranted(Object[] sentEntry) {
        byte[] payload = (byte[]) sentEntry[2];
        Map<String, Object> body = (Map<String, Object>) Json.parse(new String(payload, StandardCharsets.UTF_8));
        return (Boolean) body.get("granted");
    }

    // ---- tests ----

    private static void singleNodeElectsSelf() {
        SimWorld world = new SimWorld(0, 1);
        List<RaftNode> nodes = cluster(world, 1);
        world.advanceTo(7000);
        Assert.equals(RaftLite.Role.LEADER, nodes.get(0).raft.role(), "a lone node should elect itself leader");
    }

    private static void threeNodeClusterElectsOneLeader() {
        SimWorld world = new SimWorld(0, 2);
        List<RaftNode> nodes = cluster(world, 3);
        world.advanceTo(10_000);

        long leaderCount = nodes.stream().filter(n -> n.raft.role() == RaftLite.Role.LEADER).count();
        Assert.equals(1L, leaderCount, "exactly one node should become leader");

        long term = nodes.get(0).raft.currentTerm();
        for (RaftNode n : nodes) {
            Assert.equals(term, n.raft.currentTerm(), "all nodes should agree on the current term once settled");
        }
    }

    private static void grantsAtMostOneVotePerTerm() {
        SimWorld world = new SimWorld(0, 9);
        PeerAddress self = new PeerAddress("sim", 1);
        PeerAddress candidateA = new PeerAddress("sim", 2);
        PeerAddress candidateB = new PeerAddress("sim", 3);
        Membership membership = new Membership("follower", self, world.clock());
        membership.merge(new MemberUpdate("candidateA", candidateA, NodeState.ALIVE, 0));
        membership.merge(new MemberUpdate("candidateB", candidateB, NodeState.ALIVE, 0));
        RecordingTransport transport = new RecordingTransport();
        RaftLite raft = new RaftLite(membership, transport, world.scheduler(), new InMemoryTermStore(), 42);

        raft.onMessage(candidateA, MessageType.REQUEST_VOTE, requestVotePayload(5, "candidateA"));
        raft.onMessage(candidateB, MessageType.REQUEST_VOTE, requestVotePayload(5, "candidateB"));

        Assert.equals(2, transport.sent.size(), "should have responded to both requests");
        Assert.that(extractGranted(transport.sent.get(0)), "the first request in a brand-new term should be granted");
        Assert.that(!extractGranted(transport.sent.get(1)), "a second request in the SAME term must not also be granted");
    }

    private static void higherTermCausesStepDown() {
        SimWorld world = new SimWorld(0, 4);
        PeerAddress self = new PeerAddress("sim", 1);
        Membership membership = new Membership("self", self, world.clock());
        RecordingTransport transport = new RecordingTransport();
        RaftLite raft = new RaftLite(membership, transport, world.scheduler(), new InMemoryTermStore(), 1);

        raft.onMessage(new PeerAddress("sim", 2), MessageType.HEARTBEAT, heartbeatPayload(10, "someLeader"));

        Assert.equals(RaftLite.Role.FOLLOWER, raft.role(), "seeing a higher-term heartbeat should make us a follower");
        Assert.equals(10L, raft.currentTerm(), "our term should adopt the higher term");
        Assert.equals("someLeader", raft.leaderId(), "we should recognize the heartbeat's sender as leader");
    }

    private static void minorityIsDegraded() {
        SimWorld world = new SimWorld(0, 3);
        PeerAddress self = new PeerAddress("sim", 1);
        Membership membership = new Membership("self", self, world.clock());
        for (int i = 2; i <= 4; i++) {
            membership.merge(new MemberUpdate("alive" + i, new PeerAddress("sim", i), NodeState.ALIVE, 0));
        }
        for (int i = 5; i <= 7; i++) {
            membership.merge(new MemberUpdate("dead" + i, new PeerAddress("sim", i), NodeState.DEAD, 0));
        }
        RecordingTransport transport = new RecordingTransport();
        RaftLite raft = new RaftLite(membership, transport, world.scheduler(), new InMemoryTermStore(), 1);

        // 7 known total -> majority threshold 4; alive = self + 3 = 4 -> exactly a majority, not degraded.
        Assert.that(!raft.isDegraded(), "4 alive out of 7 known members should be enough for a majority");

        membership.merge(new MemberUpdate("alive2", new PeerAddress("sim", 2), NodeState.DEAD, 0));
        // alive now = self + 2 = 3 < 4.
        Assert.that(raft.isDegraded(), "3 alive out of 7 known members is a minority");
    }

    private static void leaderStepsDownOnLostMajority() {
        SimWorld world = new SimWorld(0, 5);
        List<RaftNode> nodes = cluster(world, 3);
        world.advanceTo(10_000);

        RaftNode leaderNode = nodes.stream().filter(n -> n.raft.role() == RaftLite.Role.LEADER).findFirst()
                .orElseThrow(() -> new AssertionError("expected a leader to have been elected by t=10000"));

        // Simulate the leader losing visibility of everyone else (e.g. a partition just opened around it).
        for (RaftNode n : nodes) {
            if (n != leaderNode) {
                leaderNode.membership.merge(new MemberUpdate(n.id(), n.address(), NodeState.DEAD, 0));
            }
        }

        world.advanceTo(world.clock().nowMillis() + 600); // past one heartbeat interval (500ms)

        Assert.equals(RaftLite.Role.FOLLOWER, leaderNode.raft.role(), "a leader that loses majority visibility should step down");
        Assert.that(leaderNode.raft.isDegraded(), "it should also report itself as DEGRADED");
    }

    private static void fencingTokensIncreaseMonotonically() {
        SimWorld world = new SimWorld(0, 6);
        PeerAddress self = new PeerAddress("sim", 1);
        Membership membership = new Membership("self", self, world.clock());
        RaftLite raft = new RaftLite(membership, new RecordingTransport(), world.scheduler(), new InMemoryTermStore(), 1);

        FencingToken t1 = raft.mintFencingToken();
        FencingToken t2 = raft.mintFencingToken();
        FencingToken t3 = raft.mintFencingToken();

        Assert.that(t1.compareTo(t2) < 0, "t2 should be strictly after t1");
        Assert.that(t2.compareTo(t3) < 0, "t3 should be strictly after t2");
        Assert.equals(t1.term(), t2.term(), "tokens minted without a term change should share the same term");
    }
}
