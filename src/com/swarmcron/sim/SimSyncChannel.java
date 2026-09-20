package com.swarmcron.sim;

import com.swarmcron.net.MessageType;
import com.swarmcron.net.PeerAddress;
import com.swarmcron.net.SyncChannel;

import java.util.Map;

/**
 * Simulated SyncChannel: models a TCP round trip as two scheduled hops on the
 * same SimWorld the gossip layer uses, so a scenario's network partition
 * (SimNetwork.blockPair) affects anti-entropy exactly like it affects gossip.
 * Per-packet loss/reordering aren't modeled here -- that's precisely what TCP
 * hides from the application in the real system too. A blocked pair fails
 * the whole exchange (represents "can't establish a connection"); otherwise
 * it always completes after a fixed round-trip latency.
 */
public final class SimSyncChannel implements SyncChannel {

    private static final long ROUND_TRIP_LEG_MILLIS = 5;

    private final PeerAddress address;
    private final SimWorld world;
    private final Map<PeerAddress, SimSyncChannel> registry;
    private RequestHandler handler;

    SimSyncChannel(PeerAddress address, SimWorld world, Map<PeerAddress, SimSyncChannel> registry) {
        this.address = address;
        this.world = world;
        this.registry = registry;
    }

    @Override
    public void start(RequestHandler handler) {
        this.handler = handler;
        registry.put(address, this);
    }

    @Override
    public void request(PeerAddress to, MessageType requestType, byte[] payload, ResponseCallback callback) {
        // isBlocked is checked at each hop's delivery time, not at request-
        // initiation time: a partition imposed after the request started but
        // before a hop would have landed must still fail it, exactly like
        // SimNetwork does for gossip (see enqueueSend).
        world.scheduler().scheduleOnce(() -> {
            if (world.network().isBlocked(address, to)) {
                callback.onResponse(null);
                return;
            }
            SimSyncChannel target = registry.get(to);
            if (target == null || target.handler == null) {
                callback.onResponse(null);
                return;
            }
            byte[] response = target.handler.handle(address, requestType, payload);
            world.scheduler().scheduleOnce(() -> {
                if (world.network().isBlocked(to, address)) {
                    callback.onResponse(null);
                    return;
                }
                callback.onResponse(response);
            }, ROUND_TRIP_LEG_MILLIS);
        }, ROUND_TRIP_LEG_MILLIS);
    }

    @Override
    public void stop() {
        registry.remove(address);
    }
}
