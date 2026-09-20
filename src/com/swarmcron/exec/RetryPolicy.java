package com.swarmcron.exec;

import com.swarmcron.config.JobSpec;

import java.util.concurrent.TimeUnit;

/**
 * Pure functions over JobSpec's maxRetries/backoffMillis fields. attemptNumber
 * is 1 for the first (non-retry) attempt, 2 for the first retry, and so on --
 * a job configured with maxRetries=N is allowed attempts 1..N+1 in total.
 * Backoff is exponential and capped at 5 minutes so a misconfigured job can't
 * push a retry out to somewhere unreasonable; backoffMillis<=0 means "retry
 * immediately." The first retry (attemptNumber=2) waits exactly backoffMillis
 * (2^0), the second waits 2x, the third 4x, and so on -- attemptNumber=2 is
 * deliberately the shift-0 case, not attemptNumber=1, since attempt 1 is the
 * original try and was never itself a backoff delay.
 */
public final class RetryPolicy {

    private static final long MAX_BACKOFF_MILLIS = TimeUnit.MINUTES.toMillis(5);

    private RetryPolicy() {}

    public static boolean shouldRetry(JobSpec spec, int attemptNumber) {
        return attemptNumber <= spec.maxRetries();
    }

    public static long backoffMillisBeforeAttempt(JobSpec spec, int attemptNumber) {
        if (spec.backoffMillis() <= 0) {
            return 0;
        }
        int priorRetries = attemptNumber - 2;
        long shift = Math.max(0, Math.min(priorRetries, 20)); // guards against overflow well before it could matter
        long delay = spec.backoffMillis() * (1L << shift);
        return Math.min(delay, MAX_BACKOFF_MILLIS);
    }
}
