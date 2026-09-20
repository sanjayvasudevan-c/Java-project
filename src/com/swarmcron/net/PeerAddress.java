package com.swarmcron.net;

/** A peer's gossip endpoint, host:port. Used as-is for both UdpTransport and SimTransport addressing. */
public record PeerAddress(String host, int port) {

    public static PeerAddress parse(String hostPort) {
        int colon = hostPort.lastIndexOf(':');
        if (colon < 0) {
            throw new IllegalArgumentException("Expected host:port, got: " + hostPort);
        }
        return new PeerAddress(hostPort.substring(0, colon), Integer.parseInt(hostPort.substring(colon + 1)));
    }

    @Override
    public String toString() {
        return host + ":" + port;
    }
}
