package com.swarmcron.sim;

import com.swarmcron.net.MessageType;
import com.swarmcron.net.PeerAddress;

import java.util.ArrayList;
import java.util.List;

/**
 * Entry point for simulation scenarios (run-sim.sh &lt;scenario&gt;). Each
 * scenario builds a SimNetwork plus some SimTransports, drives time forward
 * with SimNetwork.advanceTo, asserts an invariant, and prints a PASS/FAIL
 * line plus a short summary of what it proved. The scenario roster grows as
 * later milestones add membership, elections and execution (see M11 in the
 * design doc for the full list: happy path, rolling restart, partition,
 * owner-dies-mid-run, concurrent edits, packet-loss soak).
 */
public final class ClusterSim {

    public static void main(String[] args) {
        String scenario = args.length > 0 ? args[0] : "ping-pong";
        boolean ok = switch (scenario) {
            case "ping-pong" -> pingPong();
            default -> {
                System.out.println("Unknown scenario: " + scenario);
                yield false;
            }
        };
        System.out.println(ok ? "PASS" : "FAIL");
        if (!ok) {
            System.exit(1);
        }
    }

    /** M2 smoke scenario: two SimTransport nodes, one PING, one ACK, entirely clock-driven. */
    static boolean pingPong() {
        VirtualClock clock = new VirtualClock(0);
        SimNetwork network = new SimNetwork(clock, 42);
        network.setDefaultLink(new SimNetwork.LinkConfig(5, 15, 0.0));

        PeerAddress a = new PeerAddress("sim", 1);
        PeerAddress b = new PeerAddress("sim", 2);

        List<MessageType> aReceived = new ArrayList<>();
        List<MessageType> bReceived = new ArrayList<>();

        SimTransport ta = new SimTransport(a, network);
        SimTransport tb = new SimTransport(b, network);

        ta.start((from, type, payload) -> aReceived.add(type));
        tb.start((from, type, payload) -> {
            bReceived.add(type);
            if (type == MessageType.PING) {
                tb.send(from, MessageType.ACK, new byte[0]);
            }
        });

        ta.send(b, MessageType.PING, new byte[0]);
        network.advanceTo(1000);

        boolean bGotPing = bReceived.contains(MessageType.PING);
        boolean aGotAck = aReceived.contains(MessageType.ACK);
        boolean pass = bGotPing && aGotAck;

        System.out.println("scenario ping-pong: node b received PING=" + bGotPing + ", node a received ACK=" + aGotAck
                + " (clock at t=" + clock.nowMillis() + "ms)");
        System.out.println("Proves that SimTransport delivers messages through SimNetwork with simulated "
                + "latency, driven entirely by VirtualClock.advanceTo  -  no real threads, sockets, or timers "
                + "are involved anywhere in this exchange.");
        return pass;
    }
}
