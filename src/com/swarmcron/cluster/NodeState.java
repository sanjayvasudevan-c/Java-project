package com.swarmcron.cluster;

/**
 * SWIM membership states. LEFT is the graceful-shutdown counterpart to DEAD
 * (crash/timeout) -- both are terminal for ring/ownership purposes, but the
 * dashboard distinguishes them.
 */
public enum NodeState {
    ALIVE,
    SUSPECT,
    DEAD,
    LEFT
}
