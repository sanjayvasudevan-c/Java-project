package com.swarmcron.test;

import com.swarmcron.net.MessageType;
import com.swarmcron.net.PeerAddress;
import com.swarmcron.sim.SimNetwork;
import com.swarmcron.sim.SimTransport;
import com.swarmcron.sim.VirtualClock;
import com.swarmcron.util.Assert;
import com.swarmcron.util.TestSuite;

import java.util.ArrayList;
import java.util.List;

final class SimTransportTest {

    static boolean run() {
        return new TestSuite("SimTransportTest")
                .test("delivers a message once the simulated latency elapses", SimTransportTest::deliversAfterLatency)
                .test("withholds a message until the simulated latency elapses", SimTransportTest::withholdsBeforeLatency)
                .test("drops all messages on a 100% loss link", SimTransportTest::dropsOnFullLoss)
                .test("is deterministic for a fixed seed", SimTransportTest::deterministicForSeed)
                .run();
    }

    private static void deliversAfterLatency() {
        VirtualClock clock = new VirtualClock(0);
        SimNetwork network = new SimNetwork(clock, 1);
        network.setDefaultLink(new SimNetwork.LinkConfig(10, 10, 0.0));
        PeerAddress a = new PeerAddress("sim", 1);
        PeerAddress b = new PeerAddress("sim", 2);
        List<MessageType> received = new ArrayList<>();
        SimTransport ta = new SimTransport(a, network);
        SimTransport tb = new SimTransport(b, network);
        ta.start((from, type, payload) -> {});
        tb.start((from, type, payload) -> received.add(type));

        ta.send(b, MessageType.PING, new byte[0]);
        network.advanceTo(100);

        Assert.equals(1, received.size(), "message should have been delivered by t=100");
        Assert.equals(MessageType.PING, received.get(0), "delivered type");
    }

    private static void withholdsBeforeLatency() {
        VirtualClock clock = new VirtualClock(0);
        SimNetwork network = new SimNetwork(clock, 1);
        network.setDefaultLink(new SimNetwork.LinkConfig(50, 50, 0.0));
        PeerAddress a = new PeerAddress("sim", 1);
        PeerAddress b = new PeerAddress("sim", 2);
        List<MessageType> received = new ArrayList<>();
        SimTransport ta = new SimTransport(a, network);
        SimTransport tb = new SimTransport(b, network);
        ta.start((from, type, payload) -> {});
        tb.start((from, type, payload) -> received.add(type));

        ta.send(b, MessageType.PING, new byte[0]);
        network.advanceTo(10);
        Assert.equals(0, received.size(), "message with 50ms latency should not have arrived by t=10");

        network.advanceTo(50);
        Assert.equals(1, received.size(), "message should have arrived by t=50");
    }

    private static void dropsOnFullLoss() {
        VirtualClock clock = new VirtualClock(0);
        SimNetwork network = new SimNetwork(clock, 7);
        network.setDefaultLink(new SimNetwork.LinkConfig(1, 1, 1.0));
        PeerAddress a = new PeerAddress("sim", 1);
        PeerAddress b = new PeerAddress("sim", 2);
        List<MessageType> received = new ArrayList<>();
        SimTransport ta = new SimTransport(a, network);
        SimTransport tb = new SimTransport(b, network);
        ta.start((from, type, payload) -> {});
        tb.start((from, type, payload) -> received.add(type));

        for (int i = 0; i < 20; i++) {
            ta.send(b, MessageType.PING, new byte[0]);
        }
        network.advanceTo(1000);

        Assert.equals(0, received.size(), "100% loss link should deliver nothing");
    }

    private static void deterministicForSeed() {
        long delivered1 = runWithSeed(99);
        long delivered2 = runWithSeed(99);
        Assert.equals(delivered1, delivered2, "same seed should produce the same delivery count");
    }

    private static long runWithSeed(long seed) {
        VirtualClock clock = new VirtualClock(0);
        SimNetwork network = new SimNetwork(clock, seed);
        network.setDefaultLink(new SimNetwork.LinkConfig(1, 20, 0.3));
        PeerAddress a = new PeerAddress("sim", 1);
        PeerAddress b = new PeerAddress("sim", 2);
        List<MessageType> received = new ArrayList<>();
        SimTransport ta = new SimTransport(a, network);
        SimTransport tb = new SimTransport(b, network);
        ta.start((from, type, payload) -> {});
        tb.start((from, type, payload) -> received.add(type));
        for (int i = 0; i < 50; i++) {
            ta.send(b, MessageType.PING, new byte[0]);
        }
        network.advanceTo(1000);
        return received.size();
    }
}
