package com.swarmcron.config;

import java.util.List;

/**
 * Parsed node.conf. Immutable; one instance per running node, built once at
 * startup by ConfigParser and threaded through the rest of the system.
 */
public record NodeConfig(
        String nodeId,
        String bindHost,
        int bindPort,
        List<String> seeds,
        int httpPort,
        String dataDir,
        long protocolPeriodMs,
        long pingTimeoutMillis,
        int indirectProbeCount,
        int suspicionMultiplier,
        int virtualNodes,
        int jobReplicas,
        long antiEntropyIntervalMillis,
        long syncRequestTimeoutMillis,
        int execWorkerThreads,
        long compactionIntervalMillis
) {
}
