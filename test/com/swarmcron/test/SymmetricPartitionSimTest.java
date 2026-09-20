package com.swarmcron.test;

import com.swarmcron.cluster.FailureDetector;
import com.swarmcron.election.RaftLite;
import com.swarmcron.net.PeerAddress;
import com.swarmcron.sim.SimNetwork;
import com.swarmcron.sim.SimSwarmNode;
import com.swarmcron.sim.SimWorld;
import com.swarmcron.util.Assert;
import com.swarmcron.util.TestSuite;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Deterministic (seeded) simulation of scenario 3: a 4/3 symmetric partition.
 * The majority side must keep (or elect) a leader and stay non-degraded; the
 * minority side must recognize it cannot see a majority of the known cluster
 * and mark itself DEGRADED, refusing to act as leader. After the partition
 * heals, every node must converge on the same leader.
 */
final class SymmetricPartitionSimTest {

    static boolean run() {
        return new TestSuite("SymmetricPartitionSimTest")
                .test("majority side keeps a leader and stays non-degraded during a 4/3 partition", SymmetricPartitionSimTest::majoritySurvives)
                .test("minority side marks itself DEGRADED and elects no leader during the partition", SymmetricPartitionSimTest::minorityDegrades)
                .test("the whole cluster converges on one leader after the partition heals", SymmetricPartitionSimTest::convergesAfterHeal)
                .run();
    }

    private static final class Cluster {
        final SimWorld world;
        final List<SimSwarmNode> nodes = new ArrayList<>();
        final List<SimSwarmNode> majority = new ArrayList<>();
        final List<SimSwarmNode> minority = new ArrayList<>();

        Cluster(long seed) {
            world = new SimWorld(0, seed);
            world.network().setDefaultLink(new SimNetwork.LinkConfig(5, 20, 0.0));
            FailureDetector.Config config = new FailureDetector.Config(1000, 300, 3, 5);
            List<PeerAddress> addrs = new ArrayList<>();
            List<String> ids = List.of("n1", "n2", "n3", "n4", "n5", "n6", "n7");
            for (int i = 1; i <= 7; i++) {
                addrs.add(new PeerAddress("sim", i));
            }
            for (int i = 0; i < 7; i++) {
                List<PeerAddress> seeds = new ArrayList<>(addrs);
                seeds.remove(i);
                nodes.add(new SimSwarmNode(world, ids.get(i), addrs.get(i), seeds, config));
            }
            majority.addAll(nodes.subList(0, 4));
            minority.addAll(nodes.subList(4, 7));
        }

        void partition() {
            for (SimSwarmNode a : majority) {
                for (SimSwarmNode b : minority) {
                    world.network().blockPair(a.address, b.address);
                }
            }
        }

        void heal() {
            for (SimSwarmNode a : majority) {
                for (SimSwarmNode b : minority) {
                    world.network().unblockPair(a.address, b.address);
                }
            }
        }
    }

    private static void majoritySurvives() {
        Cluster c = new Cluster(33);
        c.world.advanceTo(10_000); // bootstrap + first election settles
        c.partition();
        c.world.advanceTo(c.world.clock().nowMillis() + 60_000);

        boolean hasLeader = c.majority.stream().anyMatch(n -> n.raftLite.role() == RaftLite.Role.LEADER);
        boolean noneDegraded = c.majority.stream().noneMatch(n -> n.raftLite.isDegraded());
        Assert.that(hasLeader, "the 4-node majority side should have (or elect) a leader during the partition");
        Assert.that(noneDegraded, "the 4-node majority side should not consider itself degraded");
    }

    private static void minorityDegrades() {
        Cluster c = new Cluster(34);
        c.world.advanceTo(10_000);
        c.partition();
        c.world.advanceTo(c.world.clock().nowMillis() + 60_000);

        boolean allDegraded = c.minority.stream().allMatch(n -> n.raftLite.isDegraded());
        boolean noLeader = c.minority.stream().noneMatch(n -> n.raftLite.role() == RaftLite.Role.LEADER);
        Assert.that(allDegraded, "every node in the 3-node minority should mark itself DEGRADED");
        Assert.that(noLeader, "the minority side should never have a self-appointed leader");
    }

    private static void convergesAfterHeal() {
        Cluster c = new Cluster(35);
        c.world.advanceTo(10_000);
        c.partition();
        c.world.advanceTo(c.world.clock().nowMillis() + 60_000);
        c.heal();
        c.world.advanceTo(c.world.clock().nowMillis() + 15_000);

        Set<String> leaderIds = new HashSet<>();
        for (SimSwarmNode n : c.nodes) {
            if (n.raftLite.leaderId() != null) {
                leaderIds.add(n.raftLite.leaderId());
            }
        }
        Assert.equals(1, leaderIds.size(), "every node should recognize exactly one leader once healed and settled");

        boolean noneDegraded = c.nodes.stream().noneMatch(n -> n.raftLite.isDegraded());
        Assert.that(noneDegraded, "no node should still be degraded once the full cluster is reachable again");
    }
}
