package com.swarmcron.net;

import com.swarmcron.util.Crc32Util;

import java.nio.ByteBuffer;

/**
 * Length-prefixed binary frame format shared by every transport:
 * [magic:2][version:1][type:1][len:4][payload][crc32:4].
 * decode() never throws  -  a malformed or corrupt frame yields null so callers
 * (in particular the UDP selector loop) can log and move on instead of
 * crashing the read loop.
 */
public final class Codec {

    public static final byte[] MAGIC = {(byte) 0xC5, (byte) 0x40};
    public static final byte VERSION = 1;

    private static final int HEADER_LEN = 2 + 1 + 1 + 4; // magic + version + type + len
    private static final int CRC_LEN = 4;

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
}
