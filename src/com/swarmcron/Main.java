package com.swarmcron;

import com.swarmcron.config.ConfigParser;
import com.swarmcron.config.JobSpec;
import com.swarmcron.config.JobsFile;
import com.swarmcron.config.NodeConfig;
import com.swarmcron.util.Log;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Entry point. Parses CLI args, loads config, and (for now, M1) just brings a
 * node up and idles. Networking, gossip, scheduling etc. are wired in by
 * later milestones and started from here.
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
        Log.info("main", "Loaded %d seed job(s) from %s", seedJobs.size(),
                parsed.jobsPath == null ? "(none given)" : parsed.jobsPath);
        for (JobSpec job : seedJobs) {
            Log.debug("main", "seed job: %s schedule='%s' command=%s", job.id(), job.schedule(), job.command());
        }

        Runtime.getRuntime().addShutdownHook(new Thread(
                () -> Log.info("main", "SwarmCron node '%s' shutting down", config.nodeId()),
                "shutdown-hook"));

        Log.info("main", "Node '%s' is up. (networking/gossip/scheduling land in later milestones)", config.nodeId());

        // Nothing to do yet in M1 beyond staying alive until killed; later
        // milestones replace this with the gossip/scheduler/http threads
        // joining on their own lifecycles.
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
