package com.swarmcron.net;

/**
 * Callback for inbound messages. Invoked on the transport's own thread (the
 * selector thread for UdpTransport, the simulation-driving thread for
 * SimTransport)  -  implementations must not block or perform slow work here;
 * enqueue and return.
 */
@FunctionalInterface
public interface MessageHandler {
    void onMessage(PeerAddress from, MessageType type, byte[] payload);
}
