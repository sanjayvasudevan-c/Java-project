package com.swarmcron.sim;

import com.swarmcron.net.MessageHandler;
import com.swarmcron.net.MessageType;
import com.swarmcron.net.PeerAddress;
import com.swarmcron.net.Transport;

/**
 * In-memory Transport backed by a shared SimNetwork. All methods are called
 * from the single thread driving the simulation (the test/scenario thread)  - 
 * there is no selector, no background thread, nothing async here beyond the
 * SimNetwork's own deliver-on-advance event queue.
 */
public final class SimTransport implements Transport {

    private final PeerAddress address;
    private final SimNetwork network;
    private MessageHandler handler;
    private boolean started;

    public SimTransport(PeerAddress address, SimNetwork network) {
        this.address = address;
        this.network = network;
    }

    @Override
    public void start(MessageHandler handler) {
        this.handler = handler;
        this.started = true;
        network.register(address, this);
    }

    @Override
    public void send(PeerAddress to, MessageType type, byte[] payload) {
        if (!started) {
            return;
        }
        network.enqueueSend(address, to, type, payload);
    }

    @Override
    public PeerAddress localAddress() {
        return address;
    }

    @Override
    public void stop() {
        started = false;
        network.unregister(address);
    }

    /** Invoked by SimNetwork.advanceTo when a queued send's delivery time has arrived. */
    void deliver(PeerAddress from, MessageType type, byte[] payload) {
        if (started && handler != null) {
            handler.onMessage(from, type, payload);
        }
    }
}
