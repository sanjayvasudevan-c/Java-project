package com.swarmcron.net;

import com.swarmcron.util.Crc32Util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;

/**
 * Length-prefixed binary frame format shared by every transport:
 * [magic:2][version:1][type:1][len:4][payload][crc32:4].
 * decode() never throws  -  a malformed or corrupt frame yields null so callers
 * (in particular the UDP selector loop) can log and move on instead of
 * crashing the read loop. readFrame()/writeFrame() are the stream-oriented
 * counterparts used over TCP (see SyncChannel), where -- unlike a UDP
 * datagram -- a frame boundary isn't given for free and must be parsed out
 * of a byte stream using the embedded length field.
 */
public final class Codec {

    public static final byte[] MAGIC = {(byte) 0xC5, (byte) 0x40};
    public static final byte VERSION = 1;

    private static final int HEADER_LEN = 2 + 1 + 1 + 4; // magic + version + type + len
    private static final int CRC_LEN = 4;
    private static final int MAX_STREAM_FRAME = 16 * 1024 * 1024;

    private Codec() {}

    public static byte[] encode(MessageType type, byte[] payload) {
        ByteBuffer buf = ByteBuffer.allocate(HEADER_LEN + payload.length + CRC_LEN);
        buf.put(MAGIC);
        buf.put(VERSION);
        buf.put((byte) type.ordinal());
        buf.putInt(payload.length);
        buf.put(payload);
        int crc = (int) Crc32Util.checksum(buf.array(), 0, HEADER_LEN + payload.length);
        buf.putInt(crc);
        return buf.array();
    }

    /** Decodes the first {@code length} bytes of {@code frame}. Returns null instead of throwing on any malformation. */
    public static DecodedFrame decode(byte[] frame, int length) {
        try {
            if (length < HEADER_LEN + CRC_LEN) {
                return null;
            }
            ByteBuffer buf = ByteBuffer.wrap(frame, 0, length);
            byte m0 = buf.get();
            byte m1 = buf.get();
            if (m0 != MAGIC[0] || m1 != MAGIC[1]) {
                return null;
            }
            byte version = buf.get();
            if (version != VERSION) {
                return null;
            }
            int typeOrdinal = buf.get() & 0xFF;
            MessageType[] types = MessageType.values();
            if (typeOrdinal >= types.length) {
                return null;
            }
            int len = buf.getInt();
            if (len < 0 || HEADER_LEN + len + CRC_LEN != length) {
                return null;
            }
            byte[] payload = new byte[len];
            buf.get(payload);
            int expectedCrc = buf.getInt();
            int actualCrc = (int) Crc32Util.checksum(frame, 0, HEADER_LEN + len);
            if (expectedCrc != actualCrc) {
                return null;
            }
            return new DecodedFrame(types[typeOrdinal], payload);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public record DecodedFrame(MessageType type, byte[] payload) {}

    /** Writes one frame to a stream (e.g. a TCP socket). Unlike decode(), failures here are real I/O errors and are thrown. */
    public static void writeFrame(OutputStream out, MessageType type, byte[] payload) throws IOException {
        out.write(encode(type, payload));
        out.flush();
    }

    /**
     * Reads exactly one frame from a stream. Returns null on a clean EOF
     * before any bytes of a new frame arrive (the peer closed the connection
     * normally); throws IOException on a bad magic/version/length or a
     * connection that died mid-frame, since neither is recoverable for the
     * caller the way a single malformed UDP datagram is.
     */
    public static DecodedFrame readFrame(InputStream in) throws IOException {
        byte[] header = in.readNBytes(HEADER_LEN);
        if (header.length == 0) {
            return null;
        }
        if (header.length < HEADER_LEN) {
            throw new IOException("connection closed mid-frame (short header)");
        }
        ByteBuffer hbuf = ByteBuffer.wrap(header);
        byte m0 = hbuf.get();
        byte m1 = hbuf.get();
        if (m0 != MAGIC[0] || m1 != MAGIC[1]) {
            throw new IOException("bad magic in stream frame");
        }
        byte version = hbuf.get();
        if (version != VERSION) {
            throw new IOException("unsupported frame version " + version);
        }
        int typeOrdinal = hbuf.get() & 0xFF;
        MessageType[] types = MessageType.values();
        if (typeOrdinal >= types.length) {
            throw new IOException("unknown message type ordinal " + typeOrdinal);
        }
        int len = hbuf.getInt();
        if (len < 0 || len > MAX_STREAM_FRAME) {
            throw new IOException("invalid or oversized frame length " + len);
        }
        byte[] rest = in.readNBytes(len + CRC_LEN);
        if (rest.length < len + CRC_LEN) {
            throw new IOException("connection closed mid-frame (short body)");
        }
        byte[] full = new byte[HEADER_LEN + len + CRC_LEN];
        System.arraycopy(header, 0, full, 0, HEADER_LEN);
        System.arraycopy(rest, 0, full, HEADER_LEN, rest.length);
        DecodedFrame decoded = decode(full, full.length);
        if (decoded == null) {
            throw new IOException("crc mismatch in stream frame");
        }
        return decoded;
    }
}
