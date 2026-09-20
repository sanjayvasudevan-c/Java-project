package com.swarmcron.test;

import com.swarmcron.config.ConfigParser;
import com.swarmcron.config.JobSpec;
import com.swarmcron.config.JobsFile;
import com.swarmcron.config.NodeConfig;
import com.swarmcron.util.Assert;
import com.swarmcron.util.TestSuite;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

final class ConfigParserTest {

    static boolean run() {
        return new TestSuite("ConfigParserTest")
                .test("parses a full node.conf", ConfigParserTest::parsesFullConfig)
                .test("applies defaults for optional keys", ConfigParserTest::appliesDefaults)
                .test("rejects a config missing node.id", ConfigParserTest::rejectsMissingRequired)
                .test("parses jobs.json seed file", ConfigParserTest::parsesJobsFile)
                .run();
    }

    private static void parsesFullConfig() throws IOException {
        Path tmp = Files.createTempFile("node", ".conf");
        try {
            Files.writeString(tmp, """
                    # comment line
                    node.id = alpha
                    node.bind = 127.0.0.1:7946
                    node.seeds = 127.0.0.1:7947,127.0.0.1:7948
                    http.port = 8081
                    data.dir = ./data/alpha
                    gossip.protocolPeriodMs = 1000
                    gossip.suspicionMultiplier = 5
                    ring.virtualNodes = 128
                    job.replicas = 2
                    """);
            NodeConfig config = ConfigParser.parseFile(tmp);
            Assert.equals("alpha", config.nodeId(), "nodeId");
            Assert.equals("127.0.0.1", config.bindHost(), "bindHost");
            Assert.equals(7946, config.bindPort(), "bindPort");
            Assert.equals(List.of("127.0.0.1:7947", "127.0.0.1:7948"), config.seeds(), "seeds");
            Assert.equals(8081, config.httpPort(), "httpPort");
            Assert.equals(128, config.virtualNodes(), "virtualNodes");
            Assert.equals(2, config.jobReplicas(), "jobReplicas");
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static void appliesDefaults() throws IOException {
        Path tmp = Files.createTempFile("node-min", ".conf");
        try {
            Files.writeString(tmp, "node.id = solo\nnode.bind = 0.0.0.0:9000\n");
            NodeConfig config = ConfigParser.parseFile(tmp);
            Assert.equals(8080, config.httpPort(), "default httpPort");
            Assert.equals(1000L, config.protocolPeriodMs(), "default protocolPeriodMs");
            Assert.equals(300L, config.pingTimeoutMillis(), "default pingTimeoutMillis");
            Assert.equals(3, config.indirectProbeCount(), "default indirectProbeCount");
            Assert.equals(5, config.suspicionMultiplier(), "default suspicionMultiplier");
            Assert.equals(30000L, config.antiEntropyIntervalMillis(), "default antiEntropyIntervalMillis");
            Assert.equals(5000L, config.syncRequestTimeoutMillis(), "default syncRequestTimeoutMillis");
            Assert.equals(8, config.execWorkerThreads(), "default execWorkerThreads");
            Assert.equals(60000L, config.compactionIntervalMillis(), "default compactionIntervalMillis");
            Assert.equals(List.of(), config.seeds(), "default seeds empty");
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static void rejectsMissingRequired() throws IOException {
        Path tmp = Files.createTempFile("node-bad", ".conf");
        try {
            Files.writeString(tmp, "node.bind = 0.0.0.0:9000\n");
            boolean threw = false;
            try {
                ConfigParser.parseFile(tmp);
            } catch (IllegalArgumentException e) {
                threw = true;
            }
            Assert.that(threw, "missing node.id should raise IllegalArgumentException");
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static void parsesJobsFile() throws IOException {
        Path tmp = Files.createTempFile("jobs", ".json");
        try {
            Files.writeString(tmp, """
                    [
                      {
                        "id": "nightly-backup",
                        "schedule": "0 2 * * *",
                        "command": ["/usr/bin/rsync", "-a", "/srv", "/backup"],
                        "workingDir": "/",
                        "timeoutSeconds": 3600,
                        "maxRetries": 2,
                        "backoffMillis": 30000,
                        "overlapPolicy": "SKIP",
                        "enabled": true
                      }
                    ]
                    """);
            List<JobSpec> jobs = JobsFile.load(tmp);
            Assert.equals(1, jobs.size(), "job count");
            JobSpec job = jobs.get(0);
            Assert.equals("nightly-backup", job.id(), "job id");
            Assert.equals("0 2 * * *", job.schedule(), "job schedule");
            Assert.equals(List.of("/usr/bin/rsync", "-a", "/srv", "/backup"), job.command(), "job command");
            Assert.equals(3600, job.timeoutSeconds(), "job timeoutSeconds");
            Assert.equals(true, job.enabled(), "job enabled");
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
