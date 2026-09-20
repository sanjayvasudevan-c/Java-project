package com.swarmcron;

import com.swarmcron.clock.Clock;
import com.swarmcron.clock.HybridClock;
import com.swarmcron.clock.SystemClock;
import com.swarmcron.clock.SystemScheduler;
import com.swarmcron.cluster.FailureDetector;
import com.swarmcron.cluster.GossipEngine;
import com.swarmcron.cluster.Membership;
import com.swarmcron.config.ConfigParser;
import com.swarmcron.config.JobSpec;
import com.swarmcron.config.JobsFile;
import com.swarmcron.config.NodeConfig;
import com.swarmcron.net.PeerAddress;
import com.swarmcron.net.SyncChannel;
import com.swarmcron.net.TcpSyncChannel;
import com.swarmcron.net.Transport;
import com.swarmcron.net.UdpTransport;
import com.swarmcron.state.AntiEntropySync;
import com.swarmcron.state.JobRegistry;
import com.swarmcron.util.Log;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Entry point. Parses CLI args, loads config, and brings up the gossip layer
 * (UdpTransport + Membership + GossipEngine + FailureDetector) and the job
 * registry's anti-entropy sync layer (TcpSyncChannel + JobRegistry +
 * AntiEntropySync). Scheduling and execution (M6-M8) and the HTTP dashboard
 * (M9) attach here in later milestones.
 */
public final class Main {

    public static void main(String[] args) throws Exception {
        Args parsed = Args.parse(args);
        Log.setDebug(parsed.debug);

        NodeConfig config = ConfigParser.parseFile(Path.of(parsed.configPath));
        Files.createDirectories(Path.of(config.dataDir()));

        List<JobSpec> seedJobs = parsed.jobsPath != null
                ? JobsFile.load(Path.of(parsed.jobsPath))
                : List.of();

        Log.info("main", "SwarmCron node '%s' starting: bind=%s:%d http=%d seeds=%s dataDir=%s virtualNodes=%d replicas=%d",
                config.nodeId(), config.bindHost(), config.bindPort(), config.httpPort(),
                config.seeds(), config.dataDir(), config.virtualNodes(), config.jobReplicas());

        PeerAddress selfAddress = new PeerAddress(config.bindHost(), config.bindPort());
        PeerAddress syncAddress = new PeerAddress(config.bindHost(), config.bindPort() + 1);
        List<PeerAddress> seedAddresses = new ArrayList<>();
        for (String seed : config.seeds()) {
            seedAddresses.add(PeerAddress.parse(seed));
        }

        Clock clock = SystemClock.INSTANCE;
        SystemScheduler scheduler = new SystemScheduler(config.nodeId());

        Transport transport = new UdpTransport(selfAddress);
        Membership membership = new Membership(config.nodeId(), selfAddress, clock);
        GossipEngine gossipEngine = new GossipEngine(membership);
        FailureDetector.Config fdConfig = new FailureDetector.Config(
                config.protocolPeriodMs(), config.pingTimeoutMillis(), config.indirectProbeCount(), config.suspicionMultiplier());
        FailureDetector failureDetector = new FailureDetector(
                membership, transport, gossipEngine, scheduler, clock, fdConfig, seedAddresses,
                new SecureRandom().nextLong());
        transport.start(failureDetector::onMessage);
        failureDetector.start();

        HybridClock hybridClock = new HybridClock(clock);
        JobRegistry jobRegistry = new JobRegistry(config.nodeId(), hybridClock);
        SyncChannel syncChannel = new TcpSyncChannel(syncAddress, config.syncRequestTimeoutMillis());
        AntiEntropySync antiEntropySync = new AntiEntropySync(
                membership, jobRegistry, syncChannel, scheduler, config.antiEntropyIntervalMillis(),
                new SecureRandom().nextLong(),
                gossipAddr -> new PeerAddress(gossipAddr.host(), gossipAddr.port() + 1));
        antiEntropySync.start();

        // TODO(M8): jobs.json should only seed the registry on a truly first
        // boot (once the WAL can tell us that); for now it re-applies every
        // start, which is harmless (put() is idempotent per job id) but not
        // yet what the design doc promises.
        for (JobSpec job : seedJobs) {
            jobRegistry.put(job);
            Log.debug("main", "seeded job: %s schedule='%s' command=%s", job.id(), job.schedule(), job.command());
        }
        Log.info("main", "Loaded %d seed job(s) from %s", seedJobs.size(),
                parsed.jobsPath == null ? "(none given)" : parsed.jobsPath);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Log.info("main", "SwarmCron node '%s' shutting down", config.nodeId());
            transport.stop();
            syncChannel.stop();
            scheduler.shutdown();
        }, "shutdown-hook"));

        Log.info("main", "Node '%s' is up: gossip on %s, anti-entropy sync on %s (scheduling/execution/HTTP land in later milestones)",
                config.nodeId(), selfAddress, syncAddress);

        // Main thread just stays alive; the selector thread (UdpTransport) and
        // the scheduler/sync-worker threads do all the real work.
        new CountDownLatch(1).await();
    }

    private static final class Args {
        String configPath;
        String jobsPath;
        boolean debug;

        static Args parse(String[] argv) {
            Args a = new Args();
            for (int i = 0; i < argv.length; i++) {
                switch (argv[i]) {
                    case "--config" -> a.configPath = requireValue(argv, ++i, "--config");
                    case "--jobs" -> a.jobsPath = requireValue(argv, ++i, "--jobs");
                    case "--debug" -> a.debug = true;
                    default -> throw new IllegalArgumentException("Unknown argument: " + argv[i]);
                }
            }
            if (a.configPath == null) {
                System.err.println("Usage: java -jar swarmcron.jar --config <path> [--jobs <path>] [--debug]");
                System.exit(1);
            }
            return a;
        }

        static String requireValue(String[] argv, int i, String flag) {
            if (i >= argv.length) {
                throw new IllegalArgumentException(flag + " requires a value");
            }
            return argv[i];
        }
    }
}
