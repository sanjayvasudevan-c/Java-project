package com.swarmcron.exec;

import com.swarmcron.config.JobSpec;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Actually runs a job's command as a real OS process. This is deliberately
 * the one component in the whole system NOT driven by the injected Clock/
 * Scheduler abstraction: a subprocess's wall-clock duration can't be
 * simulated, only waited on for real. Sim-based tests therefore use trivial,
 * near-instant commands ("true", "echo") so the real wall-clock time they
 * burn stays negligible -- everything else about scheduling, ownership, and
 * claims around the run is still fully deterministic and VirtualClock-driven.
 */
public final class ProcessRunner {

    public record RunOutcome(boolean launched, int exitCode, boolean timedOut, long durationMillis, String error) {
        public boolean succeeded() {
            return launched && !timedOut && exitCode == 0;
        }
    }

    public RunOutcome run(JobSpec spec) {
        long startNanos = System.nanoTime();
        ProcessBuilder pb = new ProcessBuilder(spec.command());
        pb.directory(new File(spec.workingDir()));
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            return new RunOutcome(false, -1, false, elapsedMillis(startNanos), e.getMessage());
        }

        try {
            boolean finished = spec.timeoutSeconds() <= 0
                    ? process.waitFor(365, TimeUnit.DAYS) // no configured timeout: wait as long as it takes
                    : process.waitFor(spec.timeoutSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new RunOutcome(true, -1, true, elapsedMillis(startNanos),
                        "timed out after " + spec.timeoutSeconds() + "s");
            }
            return new RunOutcome(true, process.exitValue(), false, elapsedMillis(startNanos), null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return new RunOutcome(true, -1, false, elapsedMillis(startNanos), "interrupted");
        }
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
