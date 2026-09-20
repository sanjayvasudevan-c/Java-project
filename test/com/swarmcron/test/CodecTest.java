package com.swarmcron.test;

import com.swarmcron.net.Codec;
import com.swarmcron.net.MessageType;
import com.swarmcron.util.Assert;
import com.swarmcron.util.TestSuite;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

final class CodecTest {

    static boolean run() {
        return new TestSuite("CodecTest")
                .test("round-trips a frame", CodecTest::roundTrips)
                .test("round-trips an empty payload", CodecTest::roundTripsEmpty)
                .test("rejects a frame with a flipped payload byte (bad crc)", CodecTest::rejectsCorruptCrc)
                .test("rejects bad magic", CodecTest::rejectsBadMagic)
                .test("rejects truncated frames", CodecTest::rejectsTruncated)
                .run();
    }

    private static void roundTrips() {
        byte[] payload = "hello-peer".getBytes(StandardCharsets.UTF_8);
        byte[] frame = Codec.encode(MessageType.PING, payload);
        Codec.DecodedFrame decoded = Codec.decode(frame, frame.length);
        Assert.that(decoded != null, "expected a decoded frame");
        Assert.equals(MessageType.PING, decoded.type(), "type");
        Assert.that(Arrays.equals(payload, decoded.payload()), "payload should round-trip byte for byte");
    }

    private static void roundTripsEmpty() {
        byte[] frame = Codec.encode(MessageType.ACK, new byte[0]);
        Codec.DecodedFrame decoded = Codec.decode(frame, frame.length);
        Assert.that(decoded != null, "expected a decoded frame");
        Assert.equals(MessageType.ACK, decoded.type(), "type");
        Assert.equals(0, decoded.payload().length, "empty payload length");
    }

    private static void rejectsCorruptCrc() {
        byte[] frame = Codec.encode(MessageType.HEARTBEAT, "term=3".getBytes(StandardCharsets.UTF_8));
        frame[frame.length - 6] ^= 0x1; // flip a payload bit without touching the trailing crc bytes
        Codec.DecodedFrame decoded = Codec.decode(frame, frame.length);
        Assert.that(decoded == null, "corrupted payload should fail the crc check and return null, not throw");
    }

    private static void rejectsBadMagic() {
        byte[] frame = Codec.encode(MessageType.VOTE, new byte[]{1, 2, 3});
        frame[0] = 0x00;
        Codec.DecodedFrame decoded = Codec.decode(frame, frame.length);
        Assert.that(decoded == null, "wrong magic should be rejected");
    }

    private static void rejectsTruncated() {
        byte[] frame = Codec.encode(MessageType.REQUEST_VOTE, new byte[]{1, 2, 3, 4, 5});
        Codec.DecodedFrame decoded = Codec.decode(frame, frame.length - 3);
        Assert.that(decoded == null, "truncated frame should be rejected, not throw");
    }
}
