package com.swarmcron.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses the "key = value" node.conf format (see README). Blank lines and
 * lines starting with '#' are ignored. This is deliberately not a general
 * properties parser: it only understands the keys SwarmCron defines.
 */
public final class ConfigParser {

    private ConfigParser() {}

    public static NodeConfig parseFile(Path path) throws IOException {
        Map<String, String> kv = new LinkedHashMap<>();
        for (String rawLine : Files.readAllLines(path)) {
            String line = rawLine.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq < 0) {
                throw new IllegalArgumentException("Malformed config line (expected 'key = value'): " + rawLine);
            }
            String key = line.substring(0, eq).strip();
            String value = line.substring(eq + 1).strip();
            kv.put(key, value);
        }
        return build(kv);
    }

    private static NodeConfig build(Map<String, String> kv) {
        String nodeId = require(kv, "node.id");
        String bind = require(kv, "node.bind");
        int colon = bind.lastIndexOf(':');
        if (colon < 0) {
            throw new IllegalArgumentException("node.bind must be host:port, got: " + bind);
        }
        String bindHost = bind.substring(0, colon);
        int bindPort = Integer.parseInt(bind.substring(colon + 1));

        List<String> seeds = new ArrayList<>();
        String seedsRaw = kv.getOrDefault("node.seeds", "");
        if (!seedsRaw.isBlank()) {
            for (String s : seedsRaw.split(",")) {
                String trimmed = s.strip();
                if (!trimmed.isEmpty()) {
                    seeds.add(trimmed);
                }
            }
        }

        int httpPort = Integer.parseInt(kv.getOrDefault("http.port", "8080"));
        String dataDir = kv.getOrDefault("data.dir", "./data/" + nodeId);
        long protocolPeriodMs = Long.parseLong(kv.getOrDefault("gossip.protocolPeriodMs", "1000"));
        long pingTimeoutMillis = Long.parseLong(kv.getOrDefault("gossip.pingTimeoutMs", "300"));
        int indirectProbeCount = Integer.parseInt(kv.getOrDefault("gossip.indirectProbeCount", "3"));
        int suspicionMultiplier = Integer.parseInt(kv.getOrDefault("gossip.suspicionMultiplier", "5"));
        int virtualNodes = Integer.parseInt(kv.getOrDefault("ring.virtualNodes", "128"));
        int jobReplicas = Integer.parseInt(kv.getOrDefault("job.replicas", "2"));
        long antiEntropyIntervalMillis = Long.parseLong(kv.getOrDefault("sync.antiEntropyIntervalMs", "30000"));
        long syncRequestTimeoutMillis = Long.parseLong(kv.getOrDefault("sync.requestTimeoutMs", "5000"));

        return new NodeConfig(nodeId, bindHost, bindPort, List.copyOf(seeds), httpPort, dataDir,
                protocolPeriodMs, pingTimeoutMillis, indirectProbeCount, suspicionMultiplier, virtualNodes, jobReplicas,
                antiEntropyIntervalMillis, syncRequestTimeoutMillis);
    }

    private static String require(Map<String, String> kv, String key) {
        String v = kv.get(key);
        if (v == null) {
            throw new IllegalArgumentException("Missing required config key: " + key);
        }
        return v;
    }
}
