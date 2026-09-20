package com.swarmcron.store;

import com.swarmcron.util.Crc32Util;
import com.swarmcron.util.Log;

import java.io.EOFException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Hand-rolled append-only log of WalRecords, fsync'd after every append. On-
 * disk framing is private to this class (not a wire protocol, so it doesn't
 * need net.Codec's magic/version/type header): [len:4][json bytes][crc32:4].
 * Durability plus this exact per-record checksum is what recovery relies on
 * to tell "this record is valid" from "the process died mid-write and left a
 * torn tail record" -- readAll() stops at the first invalid record rather
 * than throwing, since a torn tail after a crash is expected, not corruption
 * to panic over; everything before it remains valid and is returned.
 */
public final class WriteAheadLog implements AutoCloseable {

    private final Path path;
    private RandomAccessFile file;
    private FileChannel channel;

    public WriteAheadLog(Path path) {
        this.path = path;
    }

    public synchronized void open() throws IOException {
        if (file != null) {
            return;
        }
        file = new RandomAccessFile(path.toFile(), "rw");
        channel = file.getChannel();
        channel.position(channel.size()); // append from wherever the file already ends
    }

    public synchronized void append(WalRecord record) {
        try {
            open();
            byte[] payload = record.encode();
            ByteBuffer buf = ByteBuffer.allocate(4 + payload.length + 4);
            buf.putInt(payload.length);
            buf.put(payload);
            int crc = (int) Crc32Util.checksum(payload, 0, payload.length);
            buf.putInt(crc);
            buf.flip();
            channel.write(buf);
            channel.force(true);
        } catch (IOException e) {
            throw new IllegalStateException("failed to append WAL record to " + path, e);
        }
    }

    /** Reads every valid record from the start of the file, in order. Stops (without throwing) at the first truncated or checksum-mismatched record. */
    public List<WalRecord> readAll() {
        List<WalRecord> out = new ArrayList<>();
        if (!java.nio.file.Files.exists(path)) {
            return out;
        }
        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r")) {
            while (true) {
                long recordStart = raf.getFilePointer();
                int len;
                try {
                    len = raf.readInt();
                } catch (EOFException eof) {
                    break; // clean end of file
                }
                if (len < 0 || len > 64 * 1024 * 1024) {
                    Log.warn("wal", "%s: invalid record length %d at offset %d, stopping replay", path, len, recordStart);
                    break;
                }
                byte[] payload = new byte[len];
                try {
                    raf.readFully(payload);
                    int expectedCrc = raf.readInt();
                    int actualCrc = (int) Crc32Util.checksum(payload, 0, payload.length);
                    if (expectedCrc != actualCrc) {
                        Log.warn("wal", "%s: checksum mismatch at offset %d (torn tail write?), stopping replay", path, recordStart);
                        break;
                    }
                } catch (EOFException eof) {
                    Log.warn("wal", "%s: truncated record at offset %d (torn tail write?), stopping replay", path, recordStart);
                    break;
                }
                out.add(WalRecord.decode(payload));
            }
        } catch (IOException e) {
            throw new IllegalStateException("failed to read WAL from " + path, e);
        }
        return out;
    }

    /** Truncates the log to empty -- used by Compactor once a Snapshot has captured everything it contains. */
    public synchronized void truncate() {
        try {
            open();
            channel.truncate(0);
            channel.position(0);
            channel.force(true);
        } catch (IOException e) {
            throw new IllegalStateException("failed to truncate WAL " + path, e);
        }
    }

    @Override
    public synchronized void close() {
        try {
            if (channel != null) {
                channel.close();
            }
            if (file != null) {
                file.close();
            }
        } catch (IOException e) {
            Log.warn("wal", "error closing %s: %s", path, e);
        } finally {
            channel = null;
            file = null;
        }
    }
}
