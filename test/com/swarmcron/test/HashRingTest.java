package com.swarmcron.test;

import com.swarmcron.hash.ConsistentHashRing;
import com.swarmcron.util.Assert;
import com.swarmcron.util.TestSuite;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class HashRingTest {

    static boolean run() {
        return new TestSuite("HashRingTest")
                .test("an empty ring owns nothing", HashRingTest::emptyRingOwnsNothing)
                .test("owner is deterministic and stable across rebuilds with the same members", HashRingTest::ownerIsDeterministic)
                .test("replicas returns the requested count of distinct physical nodes", HashRingTest::replicasAreDistinct)
                .test("replicas wraps around the ring when needed", HashRingTest::replicasWrapAround)
                .test("128 virtual nodes per physical node spreads keys reasonably evenly", HashRingTest::distributionIsReasonablyEven)
                .test("removing one node only reassigns the keys it owned (minimal disruption)", HashRingTest::removingOneNodeMinimallyDisrupts)
                .test("adding one node only steals keys from others, never reshuffles arbitrarily", HashRingTest::addingOneNodeMinimallyDisrupts)
                .run();
    }

    private static void emptyRingOwnsNothing() {
        ConsistentHashRing ring = ConsistentHashRing.build(List.of(), 128);
        Assert.that(ring.isEmpty(), "a ring built from no members should be empty");
        Assert.that(ring.owner("job-a") == null, "an empty ring should own nothing");
        Assert.equals(0, ring.replicas("job-a", 2).size(), "an empty ring should have no replicas");
    }

    private static void ownerIsDeterministic() {
        List<String> members = List.of("alpha", "beta", "gamma");
        ConsistentHashRing ring1 = ConsistentHashRing.build(members, 128);
        ConsistentHashRing ring2 = ConsistentHashRing.build(members, 128);
        for (int i = 0; i < 50; i++) {
            String key = "job-" + i;
            Assert.equals(ring1.owner(key), ring2.owner(key), "two rings built from the same members should agree on every owner");
        }
    }

    private static void replicasAreDistinct() {
        ConsistentHashRing ring = ConsistentHashRing.build(List.of("alpha", "beta", "gamma", "delta"), 128);
        List<String> replicas = ring.replicas("job-a", 3);
        Assert.equals(3, replicas.size(), "should return exactly 3 replicas when 4 nodes are available");
        Assert.equals(3, new HashSet<>(replicas).size(), "replicas should be distinct physical nodes");
        Assert.equals(ring.owner("job-a"), replicas.get(0), "the first replica should be the owner");
    }

    private static void replicasWrapAround() {
        ConsistentHashRing ring = ConsistentHashRing.build(List.of("alpha", "beta"), 128);
        List<String> replicas = ring.replicas("job-a", 2);
        Assert.equals(2, replicas.size(), "should find both distinct nodes even if that requires wrapping past the top of the ring");
        List<String> tooMany = ring.replicas("job-a", 5);
        Assert.equals(2, tooMany.size(), "should cap at the number of physical nodes actually available");
    }

    private static void distributionIsReasonablyEven() {
        List<String> members = List.of("alpha", "beta", "gamma", "delta", "epsilon");
        ConsistentHashRing ring = ConsistentHashRing.build(members, 128);
        Map<String, Integer> counts = new HashMap<>();
        int totalKeys = 5000;
        for (int i = 0; i < totalKeys; i++) {
            counts.merge(ring.owner("job-" + i), 1, Integer::sum);
        }
        Assert.equals(members.size(), counts.size(), "every member should own at least one key over a large enough sample");
        double expected = (double) totalKeys / members.size();
        for (int count : counts.values()) {
            double ratio = count / expected;
            Assert.that(ratio > 0.5 && ratio < 1.5,
                    "each node's share should be within 2x of the ideal even split, got ratio=" + ratio);
        }
    }

    private static void removingOneNodeMinimallyDisrupts() {
        List<String> before = List.of("alpha", "beta", "gamma", "delta", "epsilon");
        ConsistentHashRing ringBefore = ConsistentHashRing.build(before, 128);

        List<String> keys = new java.util.ArrayList<>();
        Map<String, String> ownerBefore = new HashMap<>();
        for (int i = 0; i < 2000; i++) {
            String key = "job-" + i;
            keys.add(key);
            ownerBefore.put(key, ringBefore.owner(key));
        }

        List<String> after = List.of("alpha", "beta", "gamma", "delta"); // epsilon removed
        ConsistentHashRing ringAfter = ConsistentHashRing.build(after, 128);

        int reassigned = 0;
        for (String key : keys) {
            String was = ownerBefore.get(key);
            String now = ringAfter.owner(key);
            if (!was.equals(now)) {
                reassigned++;
                Assert.equals("epsilon", was, "only keys previously owned by the removed node should move");
            }
        }
        Assert.that(reassigned > 0, "removing a node that owned some keys should reassign at least those keys");
        // With 5 equally-sized nodes, ~1/5 of keys should move; allow generous slack for hash variance.
        Assert.that(reassigned < keys.size() / 3,
                "removing one of five nodes should reassign roughly 1/5 of keys, not scramble the whole ring; moved " + reassigned + "/" + keys.size());
    }

    private static void addingOneNodeMinimallyDisrupts() {
        List<String> before = List.of("alpha", "beta", "gamma", "delta");
        ConsistentHashRing ringBefore = ConsistentHashRing.build(before, 128);

        Map<String, String> ownerBefore = new HashMap<>();
        List<String> keys = new java.util.ArrayList<>();
        for (int i = 0; i < 2000; i++) {
            String key = "job-" + i;
            keys.add(key);
            ownerBefore.put(key, ringBefore.owner(key));
        }

        List<String> after = List.of("alpha", "beta", "gamma", "delta", "epsilon"); // epsilon added
        ConsistentHashRing ringAfter = ConsistentHashRing.build(after, 128);

        int movedToNewNode = 0;
        for (String key : keys) {
            String was = ownerBefore.get(key);
            String now = ringAfter.owner(key);
            if (!was.equals(now)) {
                Assert.equals("epsilon", now, "a key that moved should only have moved to the newly-added node");
                movedToNewNode++;
            }
        }
        Assert.that(movedToNewNode > 0, "adding a fifth node should claim ownership of at least some keys");
    }
}
