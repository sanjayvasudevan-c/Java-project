package com.swarmcron.net;

/**
 * Request/response channel for bulk anti-entropy exchanges that could exceed
 * a single UDP datagram (job registry digest/delta today). TcpSyncChannel
 * backs it with real TCP sockets; sim.SimSyncChannel models the same
 * round-trip in-memory against a SimWorld, so anti-entropy is as
 * simulatable as gossip is via Transport/SimTransport.
 *
 * Asynchronous like Transport/MessageHandler, not blocking: the real
 * implementation does the actual socket I/O on a background thread and
 * invokes the callback when done; callers must not assume they're on any
 * particular thread.
 */
public interface SyncChannel {

    interface RequestHandler {
        /** Computes the response payload for an inbound request. Runs on whatever thread is serving the connection (or the sim-driving thread). */
        byte[] handle(PeerAddress from, MessageType requestType, byte[] requestPayload);
    }

    interface ResponseCallback {
        /** Called with the response payload, or null if the request failed or timed out. */
        void onResponse(byte[] responsePayload);
    }

    void start(RequestHandler handler);

    void request(PeerAddress to, MessageType requestType, byte[] payload, ResponseCallback callback);

    void stop();
}
