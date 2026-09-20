# SwarmCron

A local-first, peer-to-peer distributed cron scheduler. Pure Java 21,
standard library only — no Maven, no Gradle, no Spring, no Netty, no
Jackson, no ZooKeeper. Every distributed-systems primitive (gossip
failure detection, vector clocks, a CRDT job registry, consistent
hashing, leader election, a WAL) is hand-rolled from `java.net`,
`java.nio`, `java.util.concurrent`, `java.security.MessageDigest`,
`java.time`, and `com.sun.net.httpserver.HttpServer`.

Nodes discover each other via SWIM gossip, agree on job ownership via a
consistent hash ring, elect a leader via a Raft-lite election (used only
to mint fencing tokens, not to replicate a log), and execute jobs
at-least-once with a hand-rolled write-ahead log for crash recovery. Each
node serves a live dashboard over plain HTTP + Server-Sent Events.

For the distributed-systems design, delivery-semantics guarantees, and
known limitations, see **[DESIGN.md](DESIGN.md)**.

## Quickstart

```bash
./build.sh                                    # compiles src/ -> dist/swarmcron.jar
java -jar dist/swarmcron.jar --config conf/node1.conf --jobs conf/jobs.json
```

Or bring up a real local 3-node cluster in one command:

```bash
./demo.sh
```

This builds the jar if needed, starts three real OS processes (`alpha`,
`beta`, `gamma`, from `conf/node{1,2,3}.conf`) seeded from
`conf/jobs.json`, and prints each node's dashboard URL
(`http://127.0.0.1:8081`, `8082`, `8083`). Every dashboard shows the
*whole* cluster's state — membership, ring ownership, the current Raft
leader, every job, and recent run history from every node — not just
that one process's own view. Ctrl+C stops all three cleanly.

## CLI

```
java -jar swarmcron.jar --config <path> [--jobs <path>] [--debug]
```

- `--config` (required): path to a `node.conf` file (see below).
- `--jobs`: path to a `jobs.json` seed file, applied idempotently on
  every boot. After boot, jobs are also managed live via the HTTP API or
  dashboard — `--jobs` is only how a cluster gets its *first* jobs.
- `--debug`: verbose (DEBUG-level) logging.

## Config format

`node.conf` is a flat `key = value` file:

```
node.id = alpha
node.bind = 127.0.0.1:7946
node.seeds = 127.0.0.1:7947,127.0.0.1:7948
http.port = 8081
data.dir = ./data/alpha
gossip.protocolPeriodMs = 1000
gossip.suspicionMultiplier = 5
ring.virtualNodes = 128
job.replicas = 2
```

Every key besides `node.id` and `node.bind` has a sane default; see
`ConfigParser` for the full list (gossip timing, anti-entropy interval,
executor thread pool size, compaction interval, etc.). `data.dir` holds
the Raft term file, the write-ahead log, and periodic snapshots — it's a
real node's durable state across restarts.

`jobs.json` is an array of job specs:

```json
[
  {
    "id": "heartbeat-log",
    "schedule": "*/5 * * * * *",
    "command": ["/bin/echo", "swarmcron heartbeat"],
    "workingDir": ".",
    "timeoutSeconds": 10,
    "maxRetries": 2,
    "backoffMillis": 5000,
    "overlapPolicy": "SKIP",
    "enabled": true
  }
]
```

`schedule` accepts standard 5-field cron (`m h dom mon dow`) or 6-field
with a leading seconds field, plus `@hourly`/`@daily`/`@weekly`/`@monthly`.
`overlapPolicy` is one of `SKIP`, `QUEUE`, `PARALLEL`.

## Dashboard / HTTP API

Every node exposes, on its configured `http.port`:

| Route | Method | Description |
|---|---|---|
| `/` | GET | the live dashboard (single static HTML page) |
| `/api/status` | GET | this node's Raft role/term/leader/degraded + ring size |
| `/api/nodes` | GET | the full SWIM membership table |
| `/api/jobs` | GET | active jobs, each with current ring owner + last run |
| `/api/jobs` | POST | create/update a job (JSON body = a job spec) |
| `/api/jobs/{id}` | DELETE | delete a job |
| `/api/runs` | GET | recent run history, newest first |
| `/events` | GET | Server-Sent Events stream of live cluster changes |

## Testing

```bash
./run-tests.sh              # compiles src/+test/, runs the full hand-rolled test suite
./run-sim.sh <scenario>      # runs one deterministic simulation scenario
```

`run-sim.sh` scenarios (see `ClusterSim`):

| Scenario | Proves |
|---|---|
| `ping-pong` | basic simulated transport |
| `swim-failure-detection` | bootstrap discovery + a real kill detected DEAD within 6s |
| `concurrent-job-edits` | the job registry CRDT converges after a partition heals |
| `ring-rebalance` | the ring rebuilds automatically and only reassigns the dead node's keys |
| `symmetric-partition` | a 4/3 split: minority goes DEGRADED, majority keeps a leader, both converge after healing |
| `job-takeover` | a job's owner dies before running it; a survivor takes over via the ring |
| `packet-loss-soak` | 5 nodes, 15% packet loss on every link, 5 simulated minutes: no false-permanent DEAD, one stable leader, jobs still get executed |

There is no JUnit and no mocking framework: `util.TestSuite`/`util.Assert`
are the entire test harness, and every scenario in `sim/` runs the *exact
same production classes* against a deterministic, seeded, in-memory
network and virtual clock instead of real sockets/threads — see
DESIGN.md's "Testing methodology" section for why.

## Package layout

```
com.swarmcron.config    node.conf / jobs.json parsing
com.swarmcron.net       wire codec, UDP transport, TCP sync channel
com.swarmcron.cluster   SWIM membership, gossip, failure detection
com.swarmcron.clock     Clock/Scheduler abstractions, vector clocks, HLC
com.swarmcron.state     the JobRegistry CRDT and its anti-entropy sync
com.swarmcron.hash      consistent hash ring
com.swarmcron.election  Raft-lite leader election + fencing tokens
com.swarmcron.schedule  cron parsing, the timer wheel
com.swarmcron.exec      job execution, claims/leases, retries
com.swarmcron.store     write-ahead log, snapshots, compaction, recovery
com.swarmcron.http      dashboard, JSON API, SSE
com.swarmcron.sim       the deterministic simulator + scenario suite
com.swarmcron.util      hand-rolled JSON, logging, CRC32, the test harness
```
