package com.swarmcron.test;

import com.swarmcron.store.Compactor;
import com.swarmcron.store.Recovery;
import com.swarmcron.store.Snapshot;
import com.swarmcron.store.WalRecord;
import com.swarmcron.store.WriteAheadLog;
import com.swarmcron.util.Assert;
import com.swarmcron.util.TestSuite;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

final class WriteAheadLogTest {

    static boolean run() {
        return new TestSuite("WriteAheadLogTest")
                .test("appended records read back in order", WriteAheadLogTest::appendAndReadAll)
                .test("a torn tail record is dropped, everything before it survives", WriteAheadLogTest::tornTailIsTruncated)
                .test("recovery combines a snapshot with newer WAL records", WriteAheadLogTest::recoveryMergesSnapshotAndWal)
                .test("recovery reports a job whose latest record is STARTED as interrupted", WriteAheadLogTest::recoveryDetectsIncompleteRun)
                .test("compactor snapshots and truncates when nothing is in flight", WriteAheadLogTest::compactorTruncatesWhenIdle)
                .test("compactor snapshots but leaves the WAL alone while a run is in flight", WriteAheadLogTest::compactorSkipsTruncateWhileInFlight)
                .run();
    }

    private static WalRecord record(String jobId, long scheduledFireMillis, int attempt, WalRecord.Event event) {
        return new WalRecord(jobId, scheduledFireMillis, attempt, event, scheduledFireMillis, 0, 1L, 1L, null);
    }

    private static void appendAndReadAll() throws IOException {
        Path dir = Files.createTempDirectory("wal-test");
        WriteAheadLog wal = new WriteAheadLog(dir.resolve("run.wal"));
        wal.append(record("job-a", 1000, 1, WalRecord.Event.STARTED));
        wal.append(record("job-a", 1000, 1, WalRecord.Event.COMPLETED));
        wal.append(record("job-b", 2000, 1, WalRecord.Event.STARTED));
        wal.close();

        WriteAheadLog reopened = new WriteAheadLog(dir.resolve("run.wal"));
        List<WalRecord> records = reopened.readAll();
        Assert.equals(3, records.size(), "record count");
        Assert.equals("job-a", records.get(0).jobId(), "record 0 job");
        Assert.equals(WalRecord.Event.STARTED, records.get(0).event(), "record 0 event");
        Assert.equals(WalRecord.Event.COMPLETED, records.get(1).event(), "record 1 event");
        Assert.equals("job-b", records.get(2).jobId(), "record 2 job");
    }

    private static void tornTailIsTruncated() throws IOException {
        Path dir = Files.createTempDirectory("wal-test");
        Path path = dir.resolve("run.wal");
        WriteAheadLog wal = new WriteAheadLog(path);
        wal.append(record("job-a", 1000, 1, WalRecord.Event.STARTED));
        wal.append(record("job-a", 1000, 1, WalRecord.Event.COMPLETED));
        wal.close();
        long goodLength = Files.size(path);

        // Simulate a crash mid-write: append a well-formed length prefix for a
        // record whose body never actually made it to disk.
        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "rw")) {
            raf.seek(goodLength);
            raf.writeInt(100); // claims a 100-byte payload follows
            raf.write(new byte[]{1, 2, 3}); // only 3 bytes actually written
        }

        List<WalRecord> records = new WriteAheadLog(path).readAll();
        Assert.equals(2, records.size(), "only the two complete records before the torn tail should survive");
    }

    private static void recoveryMergesSnapshotAndWal() throws IOException {
        Path dir = Files.createTempDirectory("wal-test");
        Snapshot snapshot = new Snapshot(dir.resolve("run.snapshot"));
        snapshot.save(Map.of("job-a", 1000L, "job-b", 500L));

        WriteAheadLog wal = new WriteAheadLog(dir.resolve("run.wal"));
        wal.append(record("job-a", 2000, 1, WalRecord.Event.COMPLETED)); // newer than the snapshot's job-a entry
        wal.close();

        Recovery.Recovered recovered = Recovery.recover(snapshot, new WriteAheadLog(dir.resolve("run.wal")));
        Assert.equals(2000L, recovered.lastFireMillisByJobId().get("job-a"), "WAL's newer record should win over the stale snapshot value");
        Assert.equals(500L, recovered.lastFireMillisByJobId().get("job-b"), "job-b keeps its snapshot value (no newer WAL record)");
        Assert.that(recovered.jobsWithIncompleteRun().isEmpty(), "no incomplete runs expected");
    }

    private static void recoveryDetectsIncompleteRun() throws IOException {
        Path dir = Files.createTempDirectory("wal-test");
        Snapshot snapshot = new Snapshot(dir.resolve("run.snapshot"));
        WriteAheadLog wal = new WriteAheadLog(dir.resolve("run.wal"));
        wal.append(record("job-a", 1000, 1, WalRecord.Event.STARTED));
        wal.append(record("job-a", 1000, 1, WalRecord.Event.COMPLETED));
        wal.append(record("job-a", 5000, 1, WalRecord.Event.STARTED)); // crash happens right here, no terminal record follows
        wal.close();

        Recovery.Recovered recovered = Recovery.recover(snapshot, new WriteAheadLog(dir.resolve("run.wal")));
        Assert.that(recovered.jobsWithIncompleteRun().contains("job-a"), "job-a's latest attempt has no terminal record");
        Assert.equals(1000L, recovered.lastFireMillisByJobId().get("job-a"), "last COMPLETED fire time should still be the earlier, finished one");
    }

    private static void compactorTruncatesWhenIdle() throws IOException {
        Path dir = Files.createTempDirectory("wal-test");
        WriteAheadLog wal = new WriteAheadLog(dir.resolve("run.wal"));
        wal.append(record("job-a", 1000, 1, WalRecord.Event.COMPLETED));
        Snapshot snapshot = new Snapshot(dir.resolve("run.snapshot"));

        Compactor compactor = new Compactor(snapshot, wal, () -> Map.of("job-a", 1000L), () -> false,
                new NoopScheduler(), 60_000);
        compactor.compact();

        Assert.equals(1000L, snapshot.load().get("job-a"), "snapshot should capture the supplied state");
        Assert.that(wal.readAll().isEmpty(), "WAL should be truncated once nothing is in flight");
    }

    private static void compactorSkipsTruncateWhileInFlight() throws IOException {
        Path dir = Files.createTempDirectory("wal-test");
        WriteAheadLog wal = new WriteAheadLog(dir.resolve("run.wal"));
        wal.append(record("job-a", 1000, 1, WalRecord.Event.STARTED));
        Snapshot snapshot = new Snapshot(dir.resolve("run.snapshot"));

        Compactor compactor = new Compactor(snapshot, wal, () -> Map.of(), () -> true,
                new NoopScheduler(), 60_000);
        compactor.compact();

        Assert.equals(1, wal.readAll().size(), "the in-flight run's STARTED record must survive an in-flight compaction");
    }

    /** Compactor only needs start()'s callback registered; these tests call compact() directly. */
    private static final class NoopScheduler implements com.swarmcron.clock.Scheduler {
        @Override
        public Cancellable scheduleAtFixedRate(Runnable task, long initialDelayMillis, long periodMillis) {
            return () -> { };
        }

        @Override
        public Cancellable scheduleOnce(Runnable task, long delayMillis) {
            return () -> { };
        }
    }
}
