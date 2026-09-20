package com.swarmcron.hash;

/** One point on the consistent-hash ring: a virtual replica of a physical node at a given ring position. */
public record VirtualNode(long position, String nodeId) {}
