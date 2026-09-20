package com.swarmcron.store;

import com.swarmcron.util.Json;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * jobId -> the scheduledFireMillis of that job's last known-terminal
 * (COMPLETED or FAILED) run. This is the compaction point: once a snapshot
 * captures a job's last outcome, every WAL record at or before that point is
 * redundant and safe to drop (see Compactor). Written via temp-file-then-
 * atomic-rename, the same pattern election.FileTermStore uses, so a crash
 * mid-write can never leave a torn snapshot for the next boot to read.
 */
public final class Snapshot {

    private final Path path;

    public Snapshot(Path path) {
        this.path = path;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Long> load() {
        if (!Files.exists(path)) {
            return new LinkedHashMap<>();
        }
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            if (content.isBlank()) {
                return new LinkedHashMap<>();
            }
            Map<String, Object> raw = (Map<String, Object>) Json.parse(content);
            Map<String, Long> out = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : raw.entrySet()) {
                out.put(e.getKey(), ((Number) e.getValue()).longValue());
            }
            return out;
        } catch (IOException e) {
            throw new IllegalStateException("failed to read snapshot from " + path, e);
        }
    }

    public void save(Map<String, Long> lastFireMillisByJobId) {
        try {
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            String content = Json.write(lastFireMillisByJobId);
            try (RandomAccessFile raf = new RandomAccessFile(tmp.toFile(), "rw");
                 FileChannel channel = raf.getChannel()) {
                raf.setLength(0);
                raf.write(content.getBytes(StandardCharsets.UTF_8));
                channel.force(true);
            }
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new IllegalStateException("failed to persist snapshot to " + path, e);
        }
    }
}
