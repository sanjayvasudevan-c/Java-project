package com.swarmcron.test;

import com.swarmcron.net.MessageType;
import com.swarmcron.net.PeerAddress;
import com.swarmcron.net.TcpSyncChannel;
import com.swarmcron.util.Assert;
import com.swarmcron.util.TestSuite;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/** Real loopback TCP exercise of the SyncChannel wire protocol -- the anti-entropy counterpart to UdpTransportTest. */
final class TcpSyncChannelTest {

    private static final byte[] NULL_MARKER = new byte[0];

    static boolean run() {
        return new TestSuite("TcpSyncChannelTest")
                .test("a request/response round trip works over real loopback TCP", TcpSyncChannelTest::roundTripOverLoopback)
                .test("a request to an unreachable port resolves with a null response", TcpSyncChannelTest::unreachablePeerYieldsNull)
                .run();
    }

    private static void roundTripOverLoopback() throws Exception {
        TcpSyncChannel server = new TcpSyncChannel(new PeerAddress("127.0.0.1", 0), 2000);
        TcpSyncChannel client = new TcpSyncChannel(new PeerAddress("127.0.0.1", 0), 2000);
        try {
            server.start((from, type, payload) -> {
                Assert.equals(MessageType.SYNC_DIGEST, type, "server should see the request type");
                String text = new String(payload, StandardCharsets.UTF_8);
                return ("echo:" + text).getBytes(StandardCharsets.UTF_8);
            });
            client.start((from, type, payload) -> {
                throw new AssertionError("client should not receive inbound requests in this test");
            });

            BlockingQueue<byte[]> responses = new ArrayBlockingQueue<>(1);
            client.request(server.boundAddress(), MessageType.SYNC_DIGEST, "hello".getBytes(StandardCharsets.UTF_8), responses::add);

            byte[] response = responses.poll(3, TimeUnit.SECONDS);
            Assert.that(response != null, "expected a response within the timeout");
            Assert.equals("echo:hello", new String(response, StandardCharsets.UTF_8), "response payload");
        } finally {
            server.stop();
            client.stop();
        }
    }

    private static void unreachablePeerYieldsNull() throws Exception {
        TcpSyncChannel client = new TcpSyncChannel(new PeerAddress("127.0.0.1", 0), 500);
        try {
            client.start((from, type, payload) -> new byte[0]);
            BlockingQueue<byte[]> responses = new ArrayBlockingQueue<>(1);
            // Port 1 is a privileged port essentially never listening in a test sandbox.
            client.request(new PeerAddress("127.0.0.1", 1), MessageType.SYNC_DIGEST, new byte[0], response -> {
                responses.add(response == null ? NULL_MARKER : response);
            });
            byte[] result = responses.poll(3, TimeUnit.SECONDS);
            Assert.that(result != null, "callback should have fired within the timeout");
            Assert.that(result == NULL_MARKER, "an unreachable peer should resolve with a null response, not throw or hang");
        } finally {
            client.stop();
        }
    }
}
