package com.swarmcron.test;

import com.swarmcron.cluster.GossipBuffer;
import com.swarmcron.cluster.MemberUpdate;
import com.swarmcron.cluster.NodeState;
import com.swarmcron.net.PeerAddress;
import com.swarmcron.util.Assert;
import com.swarmcron.util.TestSuite;

import java.util.List;

final class GossipBufferTest {

    static boolean run() {
        return new TestSuite("GossipBufferTest")
                .test("take() returns least-recently-gossiped entries first", GossipBufferTest::fifoOrdering)
                .test("a fresher update for the same node supersedes the queued one", GossipBufferTest::supersedesSameNode)
                .test("an update is dropped after maxTransmits gossips", GossipBufferTest::dropsAfterMaxTransmits)
                .run();
    }

    private static MemberUpdate update(String id, NodeState state) {
        return new MemberUpdate(id, new PeerAddress("127.0.0.1", 7000), state, 0);
    }

    private static void fifoOrdering() {
        GossipBuffer buf = new GossipBuffer(3);
        buf.enqueue(update("a", NodeState.ALIVE));
        buf.enqueue(update("b", NodeState.ALIVE));
        List<MemberUpdate> first = buf.take(1);
        Assert.equals("a", first.get(0).nodeId(), "a was enqueued first, should come out first");
        List<MemberUpdate> second = buf.take(1);
        Assert.equals("b", second.get(0).nodeId(), "b should come out next (a was requeued behind it)");
    }

    private static void supersedesSameNode() {
        GossipBuffer buf = new GossipBuffer(3);
        buf.enqueue(update("a", NodeState.ALIVE));
        buf.enqueue(update("a", NodeState.SUSPECT));
        Assert.equals(1, buf.size(), "only one entry should remain queued for node a");
        List<MemberUpdate> taken = buf.take(5);
        Assert.equals(1, taken.size(), "only one update for a");
        Assert.equals(NodeState.SUSPECT, taken.get(0).state(), "the fresher SUSPECT update should have won");
    }

    private static void dropsAfterMaxTransmits() {
        GossipBuffer buf = new GossipBuffer(2);
        buf.enqueue(update("a", NodeState.ALIVE));
        for (int i = 0; i < 2; i++) {
            List<MemberUpdate> taken = buf.take(5);
            Assert.equals(1, taken.size(), "update should still be present on gossip round " + i);
        }
        List<MemberUpdate> afterLimit = buf.take(5);
        Assert.equals(0, afterLimit.size(), "update should be dropped once maxTransmits gossips have happened");
    }
}
