package com.swarmcron.net;

/**
 * Wire message types. Codec encodes MessageType.ordinal() as a single byte,
 * so new types must be appended at the end, never inserted  -  inserting would
 * silently reinterpret every type after it on any frame already in flight or
 * on disk.
 */
public enum MessageType {
    PING,
    ACK,
    PING_REQ,
    SYNC_DIGEST,
    SYNC_DELTA,
    JOB_UPDATE,
    RUN_CLAIM,
    RUN_RESULT,
    REQUEST_VOTE,
    VOTE,
    HEARTBEAT
}
