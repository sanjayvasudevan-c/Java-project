package com.swarmcron.test;

import com.swarmcron.net.MessageType;
import com.swarmcron.net.PeerAddress;
import com.swarmcron.net.UdpTransport;
import com.swarmcron.util.Assert;
import com.swarmcron.util.TestSuite;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * These use real loopback sockets and real waits (not the VirtualClock) since
 * they're exercising the actual OS network stack  -  that's the point of this
 * suite. SimTransportTest covers the deterministic, clock-driven behavior.
 */
final class UdpTransportTest {

    static boolean run() {
        return new TestSuite("UdpTransportTest")
                .test("two real UDP nodes exchange PING/ACK over loopback", UdpTransportTest::pingAckOverLoopback)
                .test("a garbage datagram from a raw socket does not crash the transport", UdpTransportTest::survivesGarbageDatagram)
                .run();
    }

    private static void pingAckOverLoopback() throws Exception {
        UdpTransport nodeA = new UdpTransport(new PeerAddress("127.0.0.1", 0));
        UdpTransport nodeB = new UdpTransport(new PeerAddress("127.0.0.1", 0));
        BlockingQueue<MessageType> aInbox = new LinkedBlockingQueue<>();
        BlockingQueue<MessageType> bInbox = new LinkedBlockingQueue<>();
        try {
            nodeB.start((from, type, payload) -> {
                bInbox.add(type);
                if (type == MessageType.PING) {
                    nodeB.send(from, MessageType.ACK, new byte[0]);
                }
            });
            nodeA.start((from, type, payload) -> aInbox.add(type));

            nodeA.send(nodeB.localAddress(), MessageType.PING, "ping-payload".getBytes());

            MessageType bReceived = bInbox.poll(2, TimeUnit.SECONDS);
            Assert.equals(MessageType.PING, bReceived, "node B should receive the PING");

            MessageType aReceived = aInbox.poll(2, TimeUnit.SECONDS);
            Assert.equals(MessageType.ACK, aReceived, "node A should receive B's ACK");
        } finally {
            nodeA.stop();
            nodeB.stop();
        }
    }

    private static void survivesGarbageDatagram() throws Exception {
        UdpTransport node = new UdpTransport(new PeerAddress("127.0.0.1", 0));
        BlockingQueue<MessageType> inbox = new LinkedBlockingQueue<>();
        UdpTransport sender = new UdpTransport(new PeerAddress("127.0.0.1", 0));
        try {
            node.start((from, type, payload) -> inbox.add(type));

            try (DatagramSocket rawSocket = new DatagramSocket()) {
                byte[] garbage = "not a swarmcron frame".getBytes();
                rawSocket.send(new DatagramPacket(garbage, garbage.length,
                        InetAddress.getByName("127.0.0.1"), node.localAddress().port()));
            }

            // Follow up with a real frame; if the selector loop survived the garbage, this arrives fine.
            sender.start((from, type, payload) -> {});
            sender.send(node.localAddress(), MessageType.PING, new byte[0]);
            MessageType received = inbox.poll(2, TimeUnit.SECONDS);
            Assert.equals(MessageType.PING, received, "transport should keep working after receiving garbage");
        } finally {
            node.stop();
            sender.stop();
        }
    }
}
