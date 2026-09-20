package com.swarmcron.election;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Persists Raft's currentTerm/votedFor to a small file, fsync'd before
 * returning -- this is what makes "never vote twice in the same term"
 * survive a crash and restart. A full generalized WriteAheadLog arrives in
 * M8 for the job registry and run ledger; Raft's own state is two small
 * values, so a plain fsync'd file is enough on its own and doesn't need
 * that machinery. Written via a temp-file-then-atomic-rename so a crash
 * mid-write can never leave a torn/partial state file behind for the next
 * boot to read.
 */
public final class FileTermStore implements TermStore {

    private final Path path;

    public FileTermStore(Path path) {
        this.path = path;
    }

    @Override
    public RaftState load() {
        if (!Files.exists(path)) {
            return RaftState.INITIAL;
        }
        try {
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            long term = 0;
            String votedFor = null;
            for (String line : lines) {
                if (line.startsWith("term=")) {
                    term = Long.parseLong(line.substring("term=".length()));
                } else if (line.startsWith("votedFor=")) {
                    String v = line.substring("votedFor=".length());
                    votedFor = v.isEmpty() ? null : v;
                }
            }
            return new RaftState(term, votedFor);
        } catch (IOException | NumberFormatException e) {
            throw new IllegalStateException("failed to read Raft term state from " + path, e);
        }
    }

    @Override
    public void save(RaftState state) {
        try {
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            String content = "term=" + state.currentTerm() + "\nvotedFor=" + (state.votedFor() == null ? "" : state.votedFor()) + "\n";
            try (RandomAccessFile raf = new RandomAccessFile(tmp.toFile(), "rw");
                 FileChannel channel = raf.getChannel()) {
                raf.setLength(0);
                raf.write(content.getBytes(StandardCharsets.UTF_8));
                channel.force(true); // fsync: this must hit disk before we act on it (e.g. respond to a vote request)
            }
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new IllegalStateException("failed to persist Raft term state to " + path, e);
        }
    }
}
