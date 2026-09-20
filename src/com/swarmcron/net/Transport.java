package com.swarmcron.net;

import java.io.IOException;

/**
 * Abstraction over "send framed messages to peers, receive framed messages
 * from peers." UdpTransport implements it over real DatagramChannels;
 * sim.SimTransport implements it in-memory for the deterministic simulator.
 * Nothing above this layer (gossip, membership, election, ...) may depend on
 * which implementation it is talking to.
 */
public interface Transport {

    /** Begins receiving; handler is invoked for every successfully decoded inbound frame. */
    void start(MessageHandler handler) throws IOException;

    /** Best-effort, asynchronous send. May silently drop (bounded queue, transport-level loss, oversize frame). */
    void send(PeerAddress to, MessageType type, byte[] payload);

    PeerAddress localAddress();

    void stop();
}
