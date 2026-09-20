# SwarmCron: Design

## Goals and constraints

SwarmCron is a peer-to-peer cron scheduler: any node can go down, jobs
keep running somewhere, and there's no separate coordinator process to
depend on. The hard constraint driving every design choice here is
**zero external dependencies** — pure Java 21, standard library only.
That's not a style preference; it means every distributed-systems
primitive a framework would normally give you for free had to be built
and independently verified:

- gossip-based failure detection (SWIM)
- vector clocks + a hybrid logical clock, backing a CRDT job registry
- consistent hashing with virtual nodes
- leader election with fencing tokens (Raft-lite)
- a write-ahead log with crash recovery
- a hand-rolled JSON reader/writer and binary wire codec
- an HTTP API and Server-Sent Events on `com.sun.net.httpserver`

## Architecture

Each node runs, over the same process:

```
UdpTransport ──┬── FailureDetector (SWIM) ──── Membership
               ├── RaftLite (election, fencing tokens)
               └── JobExecutor (claims, run dispatch)

TcpSyncChannel ── AntiEntropySync ──── JobRegistry (CRDT)

Membership ──── RingManager ──── ConsistentHashRing

JobExecutor ──── WriteAheadLog / Snapshot / Compactor / Recovery

HttpApiServer ──── dashboard + JSON API + SSE (reads all of the above)
```

One `UdpTransport` carries three protocols at once (SWIM PING/ACK/
PING_REQ, Raft REQUEST_VOTE/VOTE/HEARTBEAT, and job-claim traffic
RUN_CLAIM/CLAIM_GRANT/RUN_RESULT), dispatched by message type — there's
exactly one socket, one selector thread, per node. A second channel
(`TcpSyncChannel`, gossip port + 1) carries the job registry's
anti-entropy sync independently, since that's a request/response
exchange rather than gossip's fire-and-forget pings.

### Gossip and failure detection (SWIM)

`cluster/` implements SWIM: random-peer PING with an indirect PING_REQ
fallback via 3 relays, an incarnation number per member, and a
SUSPECT → DEAD escalation on a suspicion timeout. State changes piggyback
onto routine gossip traffic (`GossipBuffer`, budgeted retransmits) and
are additionally pushed eagerly to a random fanout the instant they
happen, so real failures propagate in low single-digit seconds rather
than waiting for gossip's normal cadence.

Two properties this makes deliberately hard to get wrong:

- **A node only ever refutes suspicion of *itself*** by bumping its own
  incarnation — no third party can ever force another node's state on
  its behalf, which is what makes the SWIM incarnation scheme resistant
  to stale/replayed gossip.
- **Direct contact is definitive.** If a PING or ACK physically arrives
  from a node currently on file as SUSPECT/DEAD, the *observer* force-bumps
  that node's recorded incarnation so an ALIVE correction is guaranteed to
  win the merge — this is what lets a node recover from a DEAD mark after
  an extended partition heals, rather than staying permanently
  (and incorrectly) excluded because the gossip entry announcing its
  death has already exhausted its retransmit budget by the time
  reachability returns (see "Known limitations" below for the budget
  itself).

### Vector clocks, HLC, and the job registry CRDT

`state/JobRegistry` is an observed-remove map (`jobId -> JobEntry`),
merged with pure vector-clock causality: an incoming write that's
provably *before* or *after* the local one is a trivial accept/ignore.
Two writes that are genuinely **concurrent** (neither vector clock
dominates) fall back to a deterministic tie-break on a hybrid logical
timestamp (wall-clock-anchored but causally monotonic) plus node id as
a final tiebreaker — every node computes the identical winner
independently, with no coordinator and no manual conflict resolution.
Deletes are tombstones, not removals, so a delete propagates through
the CRDT exactly like any other write.

This CRDT converges via **anti-entropy**, not gossip: each node
periodically pulls a peer's full digest (jobId → vector clock) over TCP
and receives back whatever it's missing or behind on. That's a separate,
slower-cadence channel from SWIM's gossip on purpose — job definitions
change rarely and need eventual, not urgent, convergence, while a lease
(see below) is short-lived and needs the opposite.

### Consistent hashing

`hash/ConsistentHashRing` places 128 virtual nodes per physical node on
a SHA-1 ring (first 8 bytes of the digest as a signed `long` position).
`RingManager` rebuilds the ring automatically whenever `Membership`'s
ALIVE set changes and nothing else — an incarnation bump or a
SUSPECT↔DEAD flip for an already-excluded node doesn't trigger a
rebuild. Removing one node only reassigns the ~1/N keys it owned; this
is the entire reason to use consistent hashing over `hash(key) % N`,
which would reshuffle nearly everything on every membership change.

### Leader election and fencing tokens (Raft-lite)

`election/RaftLite` is Raft's election machinery *without log
replication* — terms, randomized election timeouts, majority-quorum
voting, periodic leader heartbeats — because the only thing a leader is
for here is minting a monotonically increasing `(term, sequence)`
fencing token on request. There's no replicated command log to keep
consistent, so the usual log-matching machinery would be pure overhead.

**Split-brain prevention** is the one place this project spent the most
design effort: majority is computed against `Membership.all()` (every
node ever discovered, which never shrinks) rather than the live ALIVE
count. ALIVE count is exactly what SWIM shrinks to "my own side" during
a partition — using it as the quorum denominator would let an isolated
minority convince itself it has "a majority of what it can see" and keep
operating. A node that can't see a majority of the *known* cluster marks
itself **DEGRADED** and neither starts nor wins an election nor executes
any job, whatever its local ring thinks about ownership. A sitting
leader that loses majority visibility steps down instead of continuing
to heartbeat into a void.

### Job execution: ownership, claims, and leases

Ring ownership is a **hint**, not the authority. Every node keeps a
timer armed for every active job regardless of whether it currently
believes it owns that job; at fire time, a node only proceeds if its
*own* ring view names it the owner. But two nodes' SWIM-derived
membership views can briefly disagree right after a membership change,
so ring agreement alone isn't a safe enough basis for "don't run this
job twice." Before actually executing, the node requests a fencing-token-
backed `RunLease` from the current Raft-lite leader (`RUN_CLAIM` /
`CLAIM_GRANT`); the leased owner broadcasts the finalized claim to every
alive peer (best-effort, like a heartbeat — a lease's entire lifetime is
shorter than the CRDT's anti-entropy interval, so that slower channel
would be the wrong tool). `ClaimRegistry`'s rule is simply "the highest
fencing token wins," and since tokens are minted from one leader's
strictly-increasing counter, two tokens are never equal — there's no
concurrent-write case to arbitrate, unlike the job registry's CRDT.

A missed claim broadcast is an accepted risk, not a correctness gap:
worst case, two nodes both believe they've claimed a job and both run
it once. **Delivery semantics are at-least-once, not exactly-once** —
duplicate execution is rare (requires a specific race plus a lost
broadcast) and bounded (at most a handful of nodes could ever
duplicate a single firing), while silently losing a job entirely is not
tolerated. See "Known limitations" for what determines that rarity in
practice.

**Takeover.** When ring ownership changes (a node died, a new one
joined), `JobExecutor` re-evaluates every active job it now owns: if the
job's next occurrence *since its last known completion* is already due,
that's a takeover, and the new owner attempts it immediately rather than
waiting on its own routine timer (which might not fire again for a long
time on a sparse schedule). This is what turns "the owner died mid-run"
into "a survivor runs it within one failure-detection-plus-election
window," not "up to a full period late."

### Write-ahead log, snapshots, recovery

`store/WriteAheadLog` is a hand-rolled append-only log: each record is
`[length:4][JSON payload][crc32:4]`, fsync'd after every append. A torn
tail record from a crash mid-write is detected by checksum and simply
dropped on replay — everything before it stays valid, which is the
standard, correct way to handle a WAL surviving an unclean shutdown.
`store/Compactor` periodically snapshots the in-memory "last completed
fire time per job" map and truncates the WAL, but only when nothing is
currently in flight — truncating out from under an active run would
erase the STARTED record `Recovery` needs to even notice a crash
happened mid-run.

`Recovery` deliberately does **not** auto-resume an interrupted run: job
commands are opaque shell invocations with no resumability contract, so
the only thing "resuming" one can mean is re-running it, which is
exactly what the job's next scheduled fire (or a ring-triggered
takeover, if the crash happened on the owner) does naturally anyway.
Recovery's job is honest bookkeeping — knowing what happened, logging
an interrupted run clearly — not guessing whether a re-run is safe.

### HTTP dashboard and API

`http/HttpApiServer` sits on `com.sun.net.httpserver.HttpServer`, the
only HTTP server in the JDK standard library. `/events` is a Server-Sent
Events stream: each connected browser holds its HTTP response open
indefinitely, blocking that connection's handler thread in a queue
`take()` for the connection's lifetime — which is why the server is
given an unbounded cached thread pool rather than HttpServer's
single-threaded default; with the default, one open dashboard tab would
starve every other request the instant a browser connected.

A dashboard opened against *any* node shows cluster-wide activity, not
just that node's own work: `JobExecutor` records a `RunSummary` both for
its own completions and for `RUN_RESULT` broadcasts received from peers,
so alpha's dashboard correctly shows gamma's job completions even though
alpha never itself runs that job.

## Testing methodology: TDD via deterministic simulation

`sim/` is not a simplified stand-in for the production classes — it's
the *same* `Membership`, `FailureDetector`, `RaftLite`, `JobRegistry`,
`RingManager`, and `JobExecutor` that `Main` constructs, wired to a
`VirtualClock` and an in-memory `SimNetwork` instead of `SystemClock`
and real sockets. `SimWorld.advanceTo()` drives a single shared
discrete-event queue, processing network deliveries and timer callbacks
in true chronological order, so a whole cluster's worth of gossip,
elections, claims, and job runs over minutes of simulated time execute
in a fraction of a second of real time, with a fixed random seed making
every run reproducible bit-for-bit.

This is what makes scenarios like `symmetric-partition` (a 4/3 split
held for 60 simulated seconds, then healed) and `packet-loss-soak` (5
nodes, 15% loss on every link, for 5 simulated minutes) practical to run
on every test invocation rather than being expensive, flaky, hours-long
integration tests. It's also what caught the real bugs listed below —
several of them are exactly the kind of narrow timing/ordering race that
a short, hand-run manual test would never happen to hit, but a
long-duration simulated soak run reliably does.

The one deliberate seam: `exec/ProcessRunner` always spawns a real OS
process, regardless of which `Clock`/`Scheduler` a node was built with —
a subprocess's actual runtime can't be simulated, only waited on for
real. Sim-based tests that exercise execution use trivial, near-instant
commands (`/bin/true`, `/bin/echo`) and a short bounded real-time poll
for their result, keeping this the one intentionally non-deterministic
corner of an otherwise fully deterministic test suite.

## Known limitations and deliberate tradeoffs

Documented here rather than hidden, in the order a reader would hit
them:

- **`FailureDetector.tick()` pings every configured seed every protocol
  period**, which burns a gossip entry's retransmit budget faster than
  necessary in a larger cluster. Left unaddressed: the direct-contact
  incarnation-bump fix (above) closes the correctness gap this caused;
  what's left is a minor efficiency concern, not a bug, and revisiting
  the retransmit budget itself isn't justified without a failing test
  motivating it.
- **No log replication in Raft-lite.** It exists solely to elect a
  leader and mint fencing tokens; there is no replicated command log, no
  linearizable writes through it, and it is not a general-purpose
  consensus module.
- **The job registry's CRDT resolves genuinely concurrent edits by
  deterministic tie-break, not merge.** Two nodes editing the *same*
  job's schedule while partitioned will, after healing, converge on one
  editor's version winning outright — there is no field-level merge.
- **`job.replicas` currently determines failover order, not hot
  standby.** `ConsistentHashRing.replicas()` returns an ordered list of
  candidate owners, but only rank 0 actively executes; a full N-way
  redundant-execution model was not built, since the ring-rebuild +
  fencing-token model already provides correct (if not instant) failover
  without it.
- **Job execution is at-least-once, not exactly-once.** A missed
  `RUN_CLAIM`/`RUN_RESULT` broadcast can result in rare, bounded
  duplicate execution; a job command should be safe to run more than
  once for a given firing if that matters to it.
- **A WAL record's identity is the wall-clock instant a firing was
  detected as due, not the exact cron-boundary timestamp** — the timer
  wheel's API carries only a task id, not an arbitrary payload, so
  there's no natural place to carry the precise intended fire instant
  through to the handler. This is enough to answer "did this job's most
  recent firing complete" for recovery/dedup purposes without a wheel
  API change nothing else currently needs.
- **A job with no configured `timeoutSeconds` still gets a fixed
  5-minute lease cap** for takeover purposes, even though `ProcessRunner`
  itself will wait far longer for it to finish. A genuinely stuck,
  untimed job needs *some* bound for another node to ever safely take
  it over; 5 minutes is a judgment call, not a principled constant.
- **An SSE client that disconnects uncleanly is only noticed on the next
  15-second keep-alive tick**, since nothing proactively probes a
  connection that isn't being written to.
- **The dashboard always re-fetches a whole section on a relevant SSE
  event** rather than applying that event's payload directly — simpler
  than client-side diffing, at the cost of some redundant small
  requests. Fine at this data scale.
- **`SimNetwork`'s link-override and blocked-pair maps are not
  synchronized.** In practice this has never mattered — every scenario
  configures partitions/link overrides from the single sim-driving
  thread before or between `advanceTo()` calls, never concurrently with
  `JobExecutor`'s worker-pool thread — but a scenario that dynamically
  reconfigures links *during* an active soak, at the same time jobs are
  completing on worker threads, would be exercising an unguarded
  concurrent read. `SimEventQueue` and `VirtualClock` (the two structures
  that *are* proven to be touched from a worker thread in practice) are
  synchronized/volatile for exactly this reason; the network's
  configuration maps were judged lower-risk and left as is.

## Milestone history

Built incrementally, each milestone's scenario(s) run and reported
before moving on:

| # | Milestone |
|---|---|
| M1 | project skeleton, config parsing, hand-rolled JSON, logging, the Clock abstraction |
| M2 | wire codec, UDP transport, the simulated transport/network harness |
| M3 | SWIM membership, gossip piggyback, failure detection |
| M4 | vector clocks, the JobRegistry CRDT, anti-entropy sync |
| M5 | consistent hash ring, automatic rebalancing on membership change |
| M6 | hand-rolled cron parser, the hashed timing wheel |
| M7 | Raft-lite leader election, fencing tokens, DEGRADED mode |
| M8 | job execution, claims/leases, takeover, write-ahead log, recovery |
| M9 | HTTP dashboard, JSON API, Server-Sent Events |
| M10 | packet-loss soak scenario, `demo.sh`, this document |

Each milestone's commit message documents the real bugs found while
building and verifying it — several genuinely subtle (a sticky-DEAD bug
under extended partitions, a role-change listener silently missing most
real transitions, a zombie `JobExecutor` corrupting a WAL file after a
simulated "kill," a cross-thread race in the simulator's event queue
surfaced only by a long soak run) — rather than summarizing them again
here; `git log` on this branch is the fuller record.
