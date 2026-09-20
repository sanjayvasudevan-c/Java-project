package com.swarmcron.util;

import java.util.zip.CRC32;

/** Thin wrapper around java.util.zip.CRC32 for frame integrity checks (see net.Codec). */
public final class Crc32Util {

    private Crc32Util() {}

    public static long checksum(byte[] data, int offset, int length) {
        CRC32 crc = new CRC32();
        crc.update(data, offset, length);
        return crc.getValue();
    }
}
