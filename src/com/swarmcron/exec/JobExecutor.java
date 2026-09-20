package com.swarmcron.exec;

import com.swarmcron.clock.Clock;
import com.swarmcron.clock.Scheduler;
import com.swarmcron.cluster.MemberInfo;
import com.swarmcron.cluster.Membership;
import com.swarmcron.config.JobSpec;
import com.swarmcron.election.RaftLite;
import com.swarmcron.hash.ConsistentHashRing;
import com.swarmcron.hash.RingManager;
import com.swarmcron.net.MessageType;
import com.swarmcron.net.PeerAddress;
import com.swarmcron.net.Transport;
import com.swarmcron.schedule.CronScheduleException;
import com.swarmcron.schedule.NextFireCalculator;
import com.swarmcron.schedule.TimerWheel;
import com.swarmcron.state.JobEntry;
import com.swarmcron.state.JobRegistry;
import com.swarmcron.store.Recovery;
import com.swarmcron.store.WalRecord;
import com.swarmcron.store.WriteAheadLog;
import com.swarmcron.util.Json;
import com.swarmcron.util.Log;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Ties every M8 piece together: for each active JobSpec, keeps a TimerWheel
 * entry armed for its next cron occurrence; at fire time (or immediately on
 * a ring change that hands this node ownership of an overdue job -- a
 * takeover), checks whether this node is the ring's owner and, if so and the
 * cluster isn't DEGRADED, requests a fencing-token-backed RunLease from the
 * current Raft-lite leader before actually running the job's command via
 * ProcessRunner, journaling STARTED/COMPLETED/FAILED/SKIPPED to the WAL the
 * whole way.
 *
 * Ownership is a *hint*, not the authority: SWIM's eventually-consistent
 * membership view means two nodes can briefly disagree about who the ring
 * says owns a job right after a membership change. The lease is what makes
 * that safe to tolerate -- its fencing token comes from a single elected
 * leader (a much more stable source of truth than raw ring agreement), and
 * ClaimRegistry's highest-token-wins rule means whichever side learns about
 * the other first backs off. A missed announcement is an accepted
 * at-least-once cost (documented on ClaimRegistry), not a correctness gap:
 * duplicate execution is rare and bounded, silent job loss is not tolerated.
 *
 * WAL record identity uses the wall-clock instant a firing was detected as
 * due, not a precise cron-boundary timestamp -- TimerWheel's API only carries
 * a task id, not an arbitrary payload, so there is no natural place to carry
 * the exact intended fire instant through to the handler. This is an honest
 * simplification: it's enough to answer "did job X's most recent firing
 * complete" for recovery/dedup purposes, which is all Scenario 2 and 4
 * require, without a wheel API change this milestone doesn't otherwise need.
 */
public final class JobExecutor {

    private static final long CLAIM_TIMEOUT_MILLIS = 2000;
    private static final long LEASE_SLACK_MILLIS = 5000;
    private static final long DEFAULT_LEASE_WINDOW_MILLIS = 5 * 60_000; // cap for jobs with no configured timeoutSeconds

    private final String selfId;
    private final Membership membership;
    private final RingManager ringManager;
    private final RaftLite raftLite;
    private final JobRegistry jobRegistry;
    private final Clock clock;
    private final Scheduler scheduler;
    private final Transport transport;
    private final WriteAheadLog wal;
    private final ZoneId zone;
    private final ExecutorService workerPool;
    private final Recovery.Recovered recovered;

    private final ClaimRegistry claimRegistry = new ClaimRegistry();
    private final ProcessRunner processRunner = new ProcessRunner();
    private final NextFireCalculator nextFireCalculator;
    private final TimerWheel timerWheel;

    private final java.util.Set<String> armedJobIds = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, Long> lastFireMillisByJobId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> activeRunCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> queuedCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Consumer<RunLease>> pendingClaimCallbacks = new ConcurrentHashMap<>();
    private final AtomicLong requestIdGenerator = new AtomicLong();
    private volatile boolean stopped = false;

    public JobExecutor(String selfId, Membership membership, RingManager ringManager, RaftLite raftLite,
                        JobRegistry jobRegistry, Clock clock, Scheduler scheduler, Transport transport,
                        WriteAheadLog wal, Recovery.Recovered recovered, ZoneId zone, ExecutorService workerPool) {
        this.selfId = selfId;
        this.membership = membership;
        this.ringManager = ringManager;
        this.raftLite = raftLite;
        this.jobRegistry = jobRegistry;
        this.clock = clock;
        this.scheduler = scheduler;
        this.transport = transport;
        this.wal = wal;
        this.recovered = recovered;
        this.zone = zone;
        this.workerPool = workerPool;
        this.nextFireCalculator = new NextFireCalculator(zone);
        this.timerWheel = new TimerWheel(scheduler, this::onTimerFire);
    }

    public void start() {
        if (!recovered.jobsWithIncompleteRun().isEmpty()) {
            Log.warn("exec", "[%s] recovered %d job(s) with a run interrupted by the previous crash/restart: %s "
                            + "-- not auto-resuming (job commands aren't resumable); the next scheduled fire or a "
                            + "ring-triggered takeover will re-attempt them",
                    selfId, recovered.jobsWithIncompleteRun().size(), recovered.jobsWithIncompleteRun());
        }
        lastFireMillisByJobId.putAll(recovered.lastFireMillisByJobId());
        timerWheel.start();
        for (JobEntry entry : jobRegistry.all()) {
            if (isRunnable(entry)) {
                armTimer(entry.spec());
            }
        }
        jobRegistry.addListener(entry -> {
            if (isRunnable(entry)) {
                armTimer(entry.spec());
            }
        });
        ringManager.addListener(this::onRingChanged);
    }

    /**
     * Makes this executor fully inert: no more scheduled fires, ring-change
     * takeover checks, or wire messages are acted on, and the worker pool
     * stops accepting new submissions. This matters beyond just freeing
     * resources -- TimerWheel and RingManager's listener are driven by the
     * shared Scheduler/Membership, which keep running even after a
     * "killed" node's Transport stops sending/receiving (see
     * sim.SimSwarmNode.kill()'s javadoc), so without this flag a killed
     * node's JobExecutor would keep reopening and appending to its own WAL
     * file in the background -- directly racing a fresh JobExecutor that a
     * simulated restart opens against the very same path.
     */
    public void stop() {
        stopped = true;
        workerPool.shutdown();
    }

    public Map<String, Long> currentLastFireSnapshot() {
        return new LinkedHashMap<>(lastFireMillisByJobId);
    }

    public boolean hasInFlightRuns() {
        for (AtomicInteger count : activeRunCounts.values()) {
            if (count.get() > 0) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    public void onMessage(PeerAddress from, MessageType type, byte[] payload) {
        if (stopped) {
            return;
        }
        try {
            switch (type) {
                case RUN_CLAIM -> handleRunClaim(from, payload);
                case CLAIM_GRANT -> handleClaimGrant(payload);
                case RUN_RESULT -> handleRunResult(payload);
                default -> { /* not ours */ }
            }
        } catch (RuntimeException e) {
            Log.error("exec", "[%s] failed to handle %s from %s: %s", selfId, type, from, e);
        }
    }

    // ---- scheduling ----

    private static boolean isRunnable(JobEntry entry) {
        return entry != null && !entry.tombstone() && entry.spec() != null && entry.spec().enabled();
    }

    private void armTimer(JobSpec spec) {
        if (!armedJobIds.add(spec.id())) {
            return; // already armed; TimerWheel has no cancel-by-id, so never double-schedule
        }
        scheduleNext(spec.id());
    }

    private void scheduleNext(String jobId) {
        JobEntry entry = jobRegistry.get(jobId);
        if (!isRunnable(entry)) {
            armedJobIds.remove(jobId); // deleted/disabled; a future re-enable will re-arm from scratch
            return;
        }
        ZonedDateTime now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(clock.nowMillis()), zone);
        try {
            ZonedDateTime next = nextFireCalculator.nextFireTime(entry.spec(), now);
            long delay = Math.max(1, next.toInstant().toEpochMilli() - clock.nowMillis());
            timerWheel.schedule(jobId, delay);
        } catch (CronScheduleException e) {
            Log.error("exec", "[%s] job '%s': cannot compute next fire time: %s", selfId, jobId, e.getMessage());
            armedJobIds.remove(jobId);
        }
    }

    private void onTimerFire(String jobId) {
        if (stopped) {
            return;
        }
        scheduleNext(jobId); // re-arm for the following occurrence before doing anything else
        JobEntry entry = jobRegistry.get(jobId);
        if (isRunnable(entry)) {
            attemptRun(entry.spec());
        }
    }

    /** Re-evaluates every active job's ownership after a ring rebuild. A job that just became ours and is already overdue (its owner died before or during handling it) is a takeover: attempt it immediately rather than waiting on this node's own wheel entry. */
    private void onRingChanged(ConsistentHashRing ring) {
        if (stopped) {
            return;
        }
        for (JobEntry entry : jobRegistry.all()) {
            if (!isRunnable(entry)) {
                continue;
            }
            String jobId = entry.jobId();
            if (!selfId.equals(ring.owner(jobId))) {
                continue;
            }
            long now = clock.nowMillis();
            if (claimRegistry.activeLease(jobId, now) != null) {
                continue; // already covered, by us or by a claim we've observed via broadcast
            }
            ZonedDateTime lastKnown = ZonedDateTime.ofInstant(
                    Instant.ofEpochMilli(lastFireMillisByJobId.getOrDefault(jobId, 0L)), zone);
            try {
                ZonedDateTime dueAt = nextFireCalculator.nextFireTime(entry.spec(), lastKnown);
                if (!dueAt.toInstant().isAfter(Instant.ofEpochMilli(now))) {
                    Log.info("exec", "[%s] taking over job '%s' after a ring change (was due at %s)", selfId, jobId, dueAt);
                    attemptRun(entry.spec());
                }
            } catch (CronScheduleException e) {
                Log.error("exec", "[%s] job '%s': cannot compute due time during takeover check: %s", selfId, jobId, e.getMessage());
            }
        }
    }

    // ---- execution ----

    private void attemptRun(JobSpec spec) {
        if (stopped) {
            return;
        }
        String jobId = spec.id();
        String owner = ringManager.currentRing().owner(jobId);
        if (!selfId.equals(owner)) {
            return;
        }
        if (raftLite.isDegraded()) {
            Log.warn("exec", "[%s] skipping job '%s': degraded (no majority visibility)", selfId, jobId);
            appendSkipped(jobId, "degraded");
            return;
        }
        AtomicInteger active = activeRunCounts.get(jobId);
        if (active != null && active.get() > 0) {
            switch (spec.overlapPolicy()) {
                case JobSpec.OVERLAP_QUEUE -> {
                    queuedCounts.computeIfAbsent(jobId, k -> new AtomicInteger()).incrementAndGet();
                    Log.info("exec", "[%s] queuing job '%s': previous run still in flight (overlapPolicy=QUEUE)", selfId, jobId);
                    return;
                }
                case JobSpec.OVERLAP_PARALLEL -> { /* fall through and launch another run anyway */ }
                default -> {
                    Log.info("exec", "[%s] skipping job '%s': previous run still in flight (overlapPolicy=%s)",
                            selfId, jobId, spec.overlapPolicy());
                    appendSkipped(jobId, "overlap-skip");
                    return;
                }
            }
        }
        long scheduledFireMillis = clock.nowMillis();
        requestClaim(jobId, spec, lease -> {
            if (lease == null) {
                Log.warn("exec", "[%s] could not obtain a claim for job '%s' (no reachable leader, or the request timed out)", selfId, jobId);
                appendSkipped(jobId, "no claim granted");
                return;
            }
            claimRegistry.adopt(lease);
            broadcastClaim(lease);
            beginRun(jobId, spec, lease, scheduledFireMillis, 1);
        });
    }

    private void requestClaim(String jobId, JobSpec spec, Consumer<RunLease> callback) {
        long leaseWindowMillis = leaseWindowMillis(spec);
        if (raftLite.role() == RaftLite.Role.LEADER) {
            callback.accept(new RunLease(jobId, selfId, raftLite.mintFencingToken(), clock.nowMillis() + leaseWindowMillis));
            return;
        }
        String leaderId = raftLite.leaderId();
        MemberInfo leaderInfo = leaderId == null ? null : membership.get(leaderId);
        if (leaderInfo == null) {
            callback.accept(null);
            return;
        }
        long requestId = requestIdGenerator.incrementAndGet();
        pendingClaimCallbacks.put(requestId, callback);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kind", "REQUEST");
        body.put("requestId", requestId);
        body.put("jobId", jobId);
        body.put("requesterId", selfId);
        body.put("leaseWindowMillis", leaseWindowMillis);
        transport.send(leaderInfo.address(), MessageType.RUN_CLAIM, Json.write(body).getBytes(StandardCharsets.UTF_8));
        scheduler.scheduleOnce(() -> {
            Consumer<RunLease> pending = pendingClaimCallbacks.remove(requestId);
            if (pending != null) {
                pending.accept(null);
            }
        }, CLAIM_TIMEOUT_MILLIS);
    }

    private long leaseWindowMillis(JobSpec spec) {
        if (spec.timeoutSeconds() > 0) {
            return spec.timeoutSeconds() * 1000L + LEASE_SLACK_MILLIS;
        }
        return DEFAULT_LEASE_WINDOW_MILLIS;
    }

    private void beginRun(String jobId, JobSpec spec, RunLease lease, long scheduledFireMillis, int attempt) {
        if (stopped) {
            return;
        }
        activeRunCounts.computeIfAbsent(jobId, k -> new AtomicInteger()).incrementAndGet();
        wal.append(new WalRecord(jobId, scheduledFireMillis, attempt, WalRecord.Event.STARTED,
                clock.nowMillis(), null, lease.token().term(), lease.token().sequence(), null));
        Log.debug("exec", "[%s] job '%s' started (attempt %d, fencing token %d/%d)",
                selfId, jobId, attempt, lease.token().term(), lease.token().sequence());
        workerPool.submit(() -> runAttempt(jobId, spec, lease, scheduledFireMillis, attempt));
    }

    private void runAttempt(String jobId, JobSpec spec, RunLease lease, long scheduledFireMillis, int attempt) {
        ProcessRunner.RunOutcome outcome = processRunner.run(spec);
        if (outcome.succeeded()) {
            wal.append(new WalRecord(jobId, scheduledFireMillis, attempt, WalRecord.Event.COMPLETED,
                    clock.nowMillis(), outcome.exitCode(), lease.token().term(), lease.token().sequence(), null));
            lastFireMillisByJobId.put(jobId, scheduledFireMillis);
            Log.info("exec", "[%s] job '%s' completed in %dms (attempt %d)", selfId, jobId, outcome.durationMillis(), attempt);
            finishRun(jobId);
            broadcastResult(jobId, scheduledFireMillis, true, outcome);
            return;
        }
        String note = outcome.error() != null ? outcome.error() : ("exit code " + outcome.exitCode());
        wal.append(new WalRecord(jobId, scheduledFireMillis, attempt, WalRecord.Event.FAILED,
                clock.nowMillis(), outcome.launched() ? outcome.exitCode() : null,
                lease.token().term(), lease.token().sequence(), note));
        if (RetryPolicy.shouldRetry(spec, attempt)) {
            int nextAttempt = attempt + 1;
            long backoff = RetryPolicy.backoffMillisBeforeAttempt(spec, nextAttempt);
            Log.info("exec", "[%s] job '%s' attempt %d failed (%s); retrying in %dms", selfId, jobId, attempt, note, backoff);
            scheduler.scheduleOnce(() -> retryOrReclaim(jobId, spec, lease, scheduledFireMillis, nextAttempt), backoff);
        } else {
            Log.warn("exec", "[%s] job '%s' failed permanently after %d attempt(s): %s", selfId, jobId, attempt, note);
            lastFireMillisByJobId.put(jobId, scheduledFireMillis);
            finishRun(jobId);
            broadcastResult(jobId, scheduledFireMillis, false, outcome);
        }
    }

    private void retryOrReclaim(String jobId, JobSpec spec, RunLease lease, long scheduledFireMillis, int attempt) {
        if (stopped) {
            return;
        }
        long now = clock.nowMillis();
        if (lease.isHeldBy(selfId) && !lease.isExpired(now)) {
            wal.append(new WalRecord(jobId, scheduledFireMillis, attempt, WalRecord.Event.STARTED,
                    now, null, lease.token().term(), lease.token().sequence(), null));
            workerPool.submit(() -> runAttempt(jobId, spec, lease, scheduledFireMillis, attempt));
            return;
        }
        requestClaim(jobId, spec, freshLease -> {
            if (freshLease == null) {
                Log.warn("exec", "[%s] job '%s': could not reacquire a claim for retry attempt %d", selfId, jobId, attempt);
                wal.append(new WalRecord(jobId, scheduledFireMillis, attempt, WalRecord.Event.SKIPPED,
                        clock.nowMillis(), null, null, null, "retry could not reacquire claim"));
                finishRun(jobId);
                return;
            }
            claimRegistry.adopt(freshLease);
            broadcastClaim(freshLease);
            wal.append(new WalRecord(jobId, scheduledFireMillis, attempt, WalRecord.Event.STARTED,
                    clock.nowMillis(), null, freshLease.token().term(), freshLease.token().sequence(), null));
            workerPool.submit(() -> runAttempt(jobId, spec, freshLease, scheduledFireMillis, attempt));
        });
    }

    private void finishRun(String jobId) {
        AtomicInteger active = activeRunCounts.get(jobId);
        if (active != null) {
            active.decrementAndGet();
        }
        AtomicInteger queued = queuedCounts.get(jobId);
        if (queued != null) {
            int previous = queued.getAndUpdate(v -> v > 0 ? v - 1 : v);
            if (previous > 0) {
                JobEntry entry = jobRegistry.get(jobId);
                if (isRunnable(entry)) {
                    attemptRun(entry.spec());
                }
            }
        }
    }

    private void appendSkipped(String jobId, String note) {
        wal.append(new WalRecord(jobId, clock.nowMillis(), 0, WalRecord.Event.SKIPPED, clock.nowMillis(), null, null, null, note));
    }

    // ---- wire protocol ----

    @SuppressWarnings("unchecked")
    private void handleRunClaim(PeerAddress from, byte[] payload) {
        Map<String, Object> body = (Map<String, Object>) Json.parse(new String(payload, StandardCharsets.UTF_8));
        String kind = (String) body.get("kind");
        if ("REQUEST".equals(kind)) {
            if (raftLite.role() != RaftLite.Role.LEADER) {
                return; // requester's routing was stale; it will time out and retry once it learns the real leader
            }
            long requestId = ((Number) body.get("requestId")).longValue();
            String jobId = (String) body.get("jobId");
            String requesterId = (String) body.get("requesterId");
            long leaseWindowMillis = ((Number) body.get("leaseWindowMillis")).longValue();
            RunLease lease = new RunLease(jobId, requesterId, raftLite.mintFencingToken(), clock.nowMillis() + leaseWindowMillis);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("requestId", requestId);
            response.put("lease", lease.toJson());
            transport.send(from, MessageType.CLAIM_GRANT, Json.write(response).getBytes(StandardCharsets.UTF_8));
        } else if ("ANNOUNCE".equals(kind)) {
            claimRegistry.adopt(RunLease.fromJson(body.get("lease")));
        }
    }

    @SuppressWarnings("unchecked")
    private void handleClaimGrant(byte[] payload) {
        Map<String, Object> body = (Map<String, Object>) Json.parse(new String(payload, StandardCharsets.UTF_8));
        long requestId = ((Number) body.get("requestId")).longValue();
        RunLease lease = RunLease.fromJson(body.get("lease"));
        Consumer<RunLease> callback = pendingClaimCallbacks.remove(requestId);
        if (callback != null) {
            callback.accept(lease);
        }
    }

    @SuppressWarnings("unchecked")
    private void handleRunResult(byte[] payload) {
        Map<String, Object> body = (Map<String, Object>) Json.parse(new String(payload, StandardCharsets.UTF_8));
        Log.debug("exec", "[%s] observed peer run result: %s", selfId, body);
    }

    private void broadcastClaim(RunLease lease) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kind", "ANNOUNCE");
        body.put("lease", lease.toJson());
        byte[] payload = Json.write(body).getBytes(StandardCharsets.UTF_8);
        for (MemberInfo m : membership.aliveMembers()) {
            if (!m.nodeId().equals(selfId)) {
                transport.send(m.address(), MessageType.RUN_CLAIM, payload);
            }
        }
    }

    private void broadcastResult(String jobId, long scheduledFireMillis, boolean success, ProcessRunner.RunOutcome outcome) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jobId", jobId);
        body.put("scheduledFireMillis", scheduledFireMillis);
        body.put("success", success);
        body.put("exitCode", outcome.exitCode());
        body.put("durationMillis", outcome.durationMillis());
        byte[] payload = Json.write(body).getBytes(StandardCharsets.UTF_8);
        for (MemberInfo m : membership.aliveMembers()) {
            if (!m.nodeId().equals(selfId)) {
                transport.send(m.address(), MessageType.RUN_RESULT, payload);
            }
        }
    }
}
