package com.swarmcron.election;

import com.swarmcron.clock.Scheduler;
import com.swarmcron.cluster.MemberInfo;
import com.swarmcron.cluster.Membership;
import com.swarmcron.net.MessageType;
import com.swarmcron.net.PeerAddress;
import com.swarmcron.net.Transport;
import com.swarmcron.util.Json;
import com.swarmcron.util.Log;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Raft-lite: leader election and fencing tokens only, no log replication.
 * The leader exists purely to break ties and hand out fencing tokens for job
 * claims (a later milestone); nothing here replicates a command log.
 *
 * Majority is computed against Membership.all().size() (every node ever
 * discovered, regardless of current ALIVE/SUSPECT/DEAD state) rather than
 * the current ALIVE count. This is deliberate and load-bearing: ALIVE count
 * is exactly what SWIM shrinks to just "my own side" during a partition, so
 * using it as the majority denominator would let an isolated minority
 * convince itself it has "a majority of what I can see" and keep operating
 * -- precisely the split-brain this component exists to prevent. all()
 * never shrinks (Membership never forgets a node once discovered), giving a
 * stable stand-in for the true cluster size without needing a separately
 * configured cluster roster.
 *
 * All state mutation is synchronized on this instance: onMessage runs on the
 * transport's thread (selector thread in production, sim-driving thread in
 * the simulator) while election timeouts and heartbeats run on the
 * Scheduler's thread -- two different threads in production that both touch
 * role/currentTerm/votedFor/leaderId/votesReceived. role/currentTerm/
 * leaderId are also volatile so read-only accessors (role(), isDegraded(),
 * the future dashboard) don't need to synchronize for a simple point-in-time
 * read.
 */
public final class RaftLite {

    public enum Role { FOLLOWER, CANDIDATE, LEADER }

    private static final long MIN_ELECTION_TIMEOUT_MILLIS = 1500;
    private static final long MAX_ELECTION_TIMEOUT_MILLIS = 3000;
    // The first election timeout gets extra headroom so SWIM bootstrap discovery
    // has a chance to complete first -- otherwise a node could win an "election"
    // against a majority of the tiny slice of the cluster it has met so far.
    private static final long INITIAL_MIN_ELECTION_TIMEOUT_MILLIS = 3000;
    private static final long INITIAL_MAX_ELECTION_TIMEOUT_MILLIS = 6000;
    private static final long HEARTBEAT_INTERVAL_MILLIS = 500;

    private final Membership membership;
    private final Transport transport;
    private final Scheduler scheduler;
    private final TermStore termStore;
    private final Random random;
    private final Set<String> votesReceived = ConcurrentHashMap.newKeySet();
    private final AtomicLong fencingSequence = new AtomicLong(0);
    private final CopyOnWriteArrayList<RoleChangeListener> listeners = new CopyOnWriteArrayList<>();

    private volatile Role role = Role.FOLLOWER;
    private volatile long currentTerm;
    private volatile String votedFor;
    private volatile String leaderId;

    private Scheduler.Cancellable electionTimer;
    private Scheduler.Cancellable heartbeatTimer;

    public RaftLite(Membership membership, Transport transport, Scheduler scheduler, TermStore termStore, long randomSeed) {
        this.membership = membership;
        this.transport = transport;
        this.scheduler = scheduler;
        this.termStore = termStore;
        this.random = new Random(randomSeed);
        RaftState loaded = termStore.load();
        this.currentTerm = loaded.currentTerm();
        this.votedFor = loaded.votedFor();
    }

    public void start() {
        resetElectionTimer(true);
    }

    public void addListener(RoleChangeListener l) {
        listeners.add(l);
    }

    public Role role() {
        return role;
    }

    public long currentTerm() {
        return currentTerm;
    }

    public String leaderId() {
        return leaderId;
    }

    /** True if this node cannot currently see a majority of the known cluster -- must refuse to execute jobs (see exec/ in a later milestone). */
    public boolean isDegraded() {
        return membership.aliveCount() < majorityThreshold();
    }

    /** Mints a fencing token for the current term. Meaningful only when role()==LEADER; the request-from-leader wire protocol for non-leaders arrives with the job claim path in M8. */
    public FencingToken mintFencingToken() {
        return new FencingToken(currentTerm, fencingSequence.incrementAndGet());
    }

    public void onMessage(PeerAddress from, MessageType type, byte[] payload) {
        try {
            switch (type) {
                case REQUEST_VOTE -> handleRequestVote(from, payload);
                case VOTE -> handleVote(payload);
                case HEARTBEAT -> handleHeartbeat(payload);
                default -> { /* not ours */ }
            }
        } catch (RuntimeException e) {
            Log.error("raft", "failed to handle %s from %s: %s", type, from, e);
        }
    }

    private int majorityThreshold() {
        return membership.all().size() / 2 + 1;
    }

    private String selfId() {
        return membership.selfId();
    }

    private synchronized void resetElectionTimer(boolean initial) {
        if (electionTimer != null) {
            electionTimer.cancel();
        }
        long min = initial ? INITIAL_MIN_ELECTION_TIMEOUT_MILLIS : MIN_ELECTION_TIMEOUT_MILLIS;
        long max = initial ? INITIAL_MAX_ELECTION_TIMEOUT_MILLIS : MAX_ELECTION_TIMEOUT_MILLIS;
        long timeout = min + (long) (random.nextDouble() * (max - min));
        electionTimer = scheduler.scheduleOnce(this::onElectionTimeout, timeout);
    }

    private synchronized void onElectionTimeout() {
        if (role == Role.LEADER) {
            return; // leaders don't time out; losing a majority is handled in sendHeartbeats instead
        }
        if (isDegraded()) {
            // Starting (and losing) an election here would only inflate our term for
            // nothing, since we structurally cannot reach majorityThreshold with the
            // peers we can currently see -- and that inflated term would later force
            // the real leader's side to needlessly re-elect once we reconnect. Just
            // keep waiting.
            Log.debug("raft", "[%s] election timeout while unable to see a majority -- not calling an election", selfId());
            resetElectionTimer(false);
            return;
        }
        startElection();
    }

    private synchronized void startElection() {
        Role oldRole = role;
        long oldTerm = currentTerm;
        String oldLeaderId = leaderId;

        currentTerm++;
        votedFor = selfId();
        role = Role.CANDIDATE;
        leaderId = null;
        termStore.save(new RaftState(currentTerm, votedFor));
        votesReceived.clear();
        votesReceived.add(selfId());
        Log.info("raft", "[%s] starting election for term %d", selfId(), currentTerm);
        resetElectionTimer(false);
        maybeBecomeLeader(); // covers the degenerate single-node-cluster case

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("term", currentTerm);
        body.put("candidateId", selfId());
        byte[] payload = Json.write(body).getBytes(StandardCharsets.UTF_8);
        for (MemberInfo m : membership.aliveMembers()) {
            if (!m.nodeId().equals(selfId())) {
                transport.send(m.address(), MessageType.REQUEST_VOTE, payload);
            }
        }
        fireIfChanged(oldRole, oldTerm, oldLeaderId);
    }

    @SuppressWarnings("unchecked")
    private synchronized void handleRequestVote(PeerAddress from, byte[] payload) {
        Role oldRole = role;
        long oldTerm = currentTerm;
        String oldLeaderId = leaderId;

        Map<String, Object> body = (Map<String, Object>) Json.parse(new String(payload, StandardCharsets.UTF_8));
        long term = ((Number) body.get("term")).longValue();
        String candidateId = (String) body.get("candidateId");

        boolean granted = false;
        if (term > currentTerm) {
            // Adopting a brand-new term with no vote cast yet -- granting is then
            // unconditional and safe: a duplicate request in this same term will
            // find term > currentTerm false the second time, so we can never grant twice.
            currentTerm = term;
            votedFor = candidateId;
            role = Role.FOLLOWER;
            leaderId = null;
            termStore.save(new RaftState(currentTerm, votedFor));
            granted = true;
            resetElectionTimer(false);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("term", currentTerm);
        response.put("granted", granted);
        response.put("voterId", selfId());
        transport.send(from, MessageType.VOTE, Json.write(response).getBytes(StandardCharsets.UTF_8));
        fireIfChanged(oldRole, oldTerm, oldLeaderId);
    }

    @SuppressWarnings("unchecked")
    private synchronized void handleVote(byte[] payload) {
        Role oldRole = role;
        long oldTerm = currentTerm;
        String oldLeaderId = leaderId;

        Map<String, Object> body = (Map<String, Object>) Json.parse(new String(payload, StandardCharsets.UTF_8));
        long term = ((Number) body.get("term")).longValue();
        boolean granted = (Boolean) body.get("granted");
        String voterId = (String) body.get("voterId");

        if (term > currentTerm) {
            stepDownToFollower(term);
            resetElectionTimer(false);
            fireIfChanged(oldRole, oldTerm, oldLeaderId);
            return; // our candidacy is stale; this vote can't count toward it
        }
        if (role != Role.CANDIDATE || term != currentTerm || !granted) {
            return;
        }
        votesReceived.add(voterId);
        maybeBecomeLeader();
        fireIfChanged(oldRole, oldTerm, oldLeaderId);
    }

    @SuppressWarnings("unchecked")
    private synchronized void handleHeartbeat(byte[] payload) {
        Role oldRole = role;
        long oldTerm = currentTerm;
        String oldLeaderId = leaderId;

        Map<String, Object> body = (Map<String, Object>) Json.parse(new String(payload, StandardCharsets.UTF_8));
        long term = ((Number) body.get("term")).longValue();
        String leader = (String) body.get("leaderId");

        if (term < currentTerm) {
            return; // stale leader from a term we've moved past; ignore
        }
        if (term > currentTerm) {
            stepDownToFollower(term);
        }
        role = Role.FOLLOWER;
        leaderId = leader;
        if (heartbeatTimer != null) {
            heartbeatTimer.cancel();
            heartbeatTimer = null;
        }
        resetElectionTimer(false);
        fireIfChanged(oldRole, oldTerm, oldLeaderId);
    }

    private synchronized void maybeBecomeLeader() {
        if (role != Role.CANDIDATE) {
            return;
        }
        if (votesReceived.size() >= majorityThreshold()) {
            role = Role.LEADER;
            leaderId = selfId();
            if (electionTimer != null) {
                electionTimer.cancel();
            }
            Log.info("raft", "[%s] elected LEADER for term %d with %d vote(s)", selfId(), currentTerm, votesReceived.size());
            startHeartbeats();
        }
    }

    private synchronized void startHeartbeats() {
        if (heartbeatTimer != null) {
            heartbeatTimer.cancel();
        }
        heartbeatTimer = scheduler.scheduleAtFixedRate(this::sendHeartbeats, 0, HEARTBEAT_INTERVAL_MILLIS);
    }

    private synchronized void sendHeartbeats() {
        if (role != Role.LEADER) {
            return;
        }
        if (isDegraded()) {
            // Lost majority visibility while leading (e.g. a partition just opened
            // up under us). Step down rather than keep heartbeating into a void and
            // calling ourselves LEADER with no one left to confirm it.
            Role oldRole = role;
            long oldTerm = currentTerm;
            String oldLeaderId = leaderId;
            Log.warn("raft", "[%s] lost majority visibility while LEADER for term %d -- stepping down", selfId(), currentTerm);
            role = Role.FOLLOWER;
            leaderId = null;
            if (heartbeatTimer != null) {
                heartbeatTimer.cancel();
                heartbeatTimer = null;
            }
            resetElectionTimer(false);
            fireIfChanged(oldRole, oldTerm, oldLeaderId);
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("term", currentTerm);
        body.put("leaderId", selfId());
        byte[] payload = Json.write(body).getBytes(StandardCharsets.UTF_8);
        for (MemberInfo m : membership.aliveMembers()) {
            if (!m.nodeId().equals(selfId())) {
                transport.send(m.address(), MessageType.HEARTBEAT, payload);
            }
        }
    }

    private synchronized void stepDownToFollower(long term) {
        currentTerm = term;
        votedFor = null;
        role = Role.FOLLOWER;
        leaderId = null;
        termStore.save(new RaftState(currentTerm, votedFor));
        if (heartbeatTimer != null) {
            heartbeatTimer.cancel();
            heartbeatTimer = null;
        }
    }

    /** Notifies listeners only when role, term, or leaderId actually differ from the given snapshot -- callers pass the values from before their mutation so a no-op message (e.g. a steady-state heartbeat that changes nothing) never spams a listener like the per-node log line in Main.java. */
    private void fireIfChanged(Role oldRole, long oldTerm, String oldLeaderId) {
        if (oldRole == role && oldTerm == currentTerm && java.util.Objects.equals(oldLeaderId, leaderId)) {
            return;
        }
        for (RoleChangeListener l : listeners) {
            try {
                l.onRoleChanged(role, currentTerm, leaderId);
            } catch (RuntimeException e) {
                Log.error("raft", "listener threw: %s", e);
            }
        }
    }
}
