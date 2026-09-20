package com.swarmcron.test;

import com.swarmcron.config.JobSpec;
import com.swarmcron.exec.ProcessRunner;
import com.swarmcron.exec.RetryPolicy;
import com.swarmcron.util.Assert;
import com.swarmcron.util.TestSuite;

import java.util.List;

final class ProcessRunnerTest {

    static boolean run() {
        return new TestSuite("ProcessRunnerTest")
                .test("a successful command reports exit code 0", ProcessRunnerTest::successfulCommand)
                .test("a failing command reports its nonzero exit code", ProcessRunnerTest::failingCommand)
                .test("a command exceeding its timeout is killed and marked timed out", ProcessRunnerTest::timeoutKillsProcess)
                .test("an unlaunchable command reports launched=false with an error", ProcessRunnerTest::unlaunchableCommand)
                .test("retry policy allows exactly maxRetries retries beyond the first attempt", ProcessRunnerTest::retryPolicyAttemptBudget)
                .test("retry policy backs off exponentially and caps at 5 minutes", ProcessRunnerTest::retryPolicyBackoff)
                .run();
    }

    private static JobSpec spec(List<String> command, int timeoutSeconds, int maxRetries, long backoffMillis) {
        return new JobSpec("job-1", "* * * * *", command, ".", timeoutSeconds, maxRetries, backoffMillis, JobSpec.OVERLAP_SKIP, true);
    }

    private static void successfulCommand() {
        ProcessRunner.RunOutcome outcome = new ProcessRunner().run(spec(List.of("/bin/true"), 0, 0, 0));
        Assert.that(outcome.succeeded(), "true should succeed");
        Assert.equals(0, outcome.exitCode(), "exit code");
        Assert.that(!outcome.timedOut(), "should not be marked timed out");
    }

    private static void failingCommand() {
        ProcessRunner.RunOutcome outcome = new ProcessRunner().run(spec(List.of("/bin/false"), 0, 0, 0));
        Assert.that(!outcome.succeeded(), "false should not succeed");
        Assert.equals(1, outcome.exitCode(), "exit code");
    }

    private static void timeoutKillsProcess() {
        long start = System.currentTimeMillis();
        ProcessRunner.RunOutcome outcome = new ProcessRunner().run(spec(List.of("/bin/sleep", "30"), 1, 0, 0));
        long elapsed = System.currentTimeMillis() - start;
        Assert.that(outcome.timedOut(), "a 30s sleep with a 1s timeout should be marked timed out");
        Assert.that(elapsed < 10_000, "the process should have been killed well before its full 30s duration (took " + elapsed + "ms)");
    }

    private static void unlaunchableCommand() {
        ProcessRunner.RunOutcome outcome = new ProcessRunner().run(spec(List.of("/no/such/binary-xyz"), 0, 0, 0));
        Assert.that(!outcome.launched(), "a nonexistent binary should fail to launch");
        Assert.that(outcome.error() != null, "should carry an error message");
    }

    private static void retryPolicyAttemptBudget() {
        JobSpec spec = spec(List.of("/bin/false"), 0, 2, 1000);
        Assert.that(RetryPolicy.shouldRetry(spec, 1), "attempt 1 failing should be retried (maxRetries=2)");
        Assert.that(RetryPolicy.shouldRetry(spec, 2), "attempt 2 failing should be retried (maxRetries=2)");
        Assert.that(!RetryPolicy.shouldRetry(spec, 3), "attempt 3 failing should NOT be retried (maxRetries=2 means 3 total attempts)");
    }

    private static void retryPolicyBackoff() {
        JobSpec spec = spec(List.of("/bin/false"), 0, 10, 1000);
        Assert.equals(1000L, RetryPolicy.backoffMillisBeforeAttempt(spec, 2), "first retry backoff");
        Assert.equals(2000L, RetryPolicy.backoffMillisBeforeAttempt(spec, 3), "second retry backoff doubles");
        Assert.equals(4000L, RetryPolicy.backoffMillisBeforeAttempt(spec, 4), "third retry backoff doubles again");
        long capped = RetryPolicy.backoffMillisBeforeAttempt(spec, 30);
        Assert.equals(5 * 60_000L, capped, "backoff should cap at 5 minutes for a large attempt number");

        JobSpec noBackoff = spec(List.of("/bin/false"), 0, 3, 0);
        Assert.equals(0L, RetryPolicy.backoffMillisBeforeAttempt(noBackoff, 2), "backoffMillis<=0 means retry immediately");
    }
}
