package com.swarmcron.net;

import com.swarmcron.util.Log;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Iterator;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * UDP transport: one DatagramChannel, one selector thread. Only the selector
 * thread ever touches the channel, the selector, or dequeues from
 * outboundQueue  -  that thread owns all of it, so there is no locking beyond
 * the queue itself. Producer threads (gossip engine, etc.) only enqueue onto
 * outboundQueue and call selector.wakeup(); they never touch channel/selector
 * directly. inbound handler callbacks run on the selector thread and must be
 * fast (push-to-queue), never blocking.
 */
public final class UdpTransport implements Transport {

    private static final int MAX_DATAGRAM_SIZE = 65000;
    private static final int OUTBOUND_QUEUE_CAPACITY = 4096;
    private static final int MAX_WRITES_PER_WAKEUP = 256;

    private final PeerAddress bindAddress;
    private final BlockingQueue<Outbound> outboundQueue = new ArrayBlockingQueue<>(OUTBOUND_QUEUE_CAPACITY);
    private final AtomicBoolean running = new AtomicBoolean(false);

    private volatile PeerAddress actualAddress;
    private DatagramChannel channel;
    private Selector selector;
    private Thread selectorThread;
    private MessageHandler handler;

    public UdpTransport(PeerAddress bindAddress) {
        this.bindAddress = bindAddress;
    }

    @Override
    public void start(MessageHandler handler) throws IOException {
        this.handler = handler;
        channel = DatagramChannel.open();
        channel.configureBlocking(false);
        channel.bind(new InetSocketAddress(bindAddress.host(), bindAddress.port()));
        InetSocketAddress bound = (InetSocketAddress) channel.getLocalAddress();
        actualAddress = new PeerAddress(bound.getHostString(), bound.getPort());

        selector = Selector.open();
        channel.register(selector, SelectionKey.OP_READ);
        running.set(true);
        selectorThread = new Thread(this::loop, "udp-transport-" + actualAddress);
        selectorThread.setDaemon(true);
        selectorThread.start();
        Log.info("udp", "listening on %s", actualAddress);
    }

    @Override
    public void send(PeerAddress to, MessageType type, byte[] payload) {
        byte[] frame;
        try {
            frame = Codec.encode(type, payload);
        } catch (RuntimeException e) {
            Log.warn("udp", "failed to encode %s for %s: %s", type, to, e);
            return;
        }
        if (frame.length > MAX_DATAGRAM_SIZE) {
            Log.warn("udp", "dropping oversized frame (%d bytes) to %s", frame.length, to);
            return;
        }
        if (!outboundQueue.offer(new Outbound(to, frame))) {
            Log.warn("udp", "outbound queue full, dropping %s to %s", type, to);
            return;
        }
        Selector sel = selector;
        if (sel != null) {
            sel.wakeup();
        }
    }

    @Override
    public PeerAddress localAddress() {
        PeerAddress a = actualAddress;
        return a != null ? a : bindAddress;
    }

    @Override
    public void stop() {
        running.set(false);
        if (selector != null) {
            selector.wakeup();
        }
        try {
            if (selectorThread != null) {
                selectorThread.join(2000);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        closeQuietly();
    }

    private void loop() {
        ByteBuffer readBuf = ByteBuffer.allocate(MAX_DATAGRAM_SIZE);
        try {
            while (running.get()) {
                SelectionKey key = channel.keyFor(selector);
                if (key != null && key.isValid()) {
                    boolean hasOutbound = !outboundQueue.isEmpty();
                    key.interestOps(hasOutbound ? (SelectionKey.OP_READ | SelectionKey.OP_WRITE) : SelectionKey.OP_READ);
                }
                selector.select(500);
                if (!running.get()) {
                    break;
                }
                Iterator<SelectionKey> it = selector.selectedKeys().iterator();
                while (it.hasNext()) {
                    SelectionKey k = it.next();
                    it.remove();
                    if (!k.isValid()) {
                        continue;
                    }
                    if (k.isReadable()) {
                        readAll(readBuf);
                    }
                    if (k.isValid() && k.isWritable()) {
                        writeSome();
                    }
                }
            }
        } catch (IOException e) {
            if (running.get()) {
                Log.error("udp", "selector loop error: %s", e);
            }
        } finally {
            closeQuietly();
        }
    }

    private void readAll(ByteBuffer buf) throws IOException {
        // Drain everything currently pending so one wakeup empties the socket's receive buffer.
        while (true) {
            buf.clear();
            SocketAddress sender = channel.receive(buf);
            if (sender == null) {
                break;
            }
            buf.flip();
            int len = buf.remaining();
            byte[] copy = new byte[len];
            buf.get(copy);
            Codec.DecodedFrame decoded = Codec.decode(copy, len);
            if (decoded == null) {
                Log.warn("udp", "dropping malformed frame from %s (%d bytes)", sender, len);
                continue;
            }
            InetSocketAddress from = (InetSocketAddress) sender;
            PeerAddress peer = new PeerAddress(from.getHostString(), from.getPort());
            try {
                handler.onMessage(peer, decoded.type(), decoded.payload());
            } catch (RuntimeException e) {
                Log.error("udp", "handler threw for %s from %s: %s", decoded.type(), peer, e);
            }
        }
    }

    private void writeSome() throws IOException {
        // Bounded batch per wakeup so a saturated outbound queue can't starve reads on this thread.
        for (int i = 0; i < MAX_WRITES_PER_WAKEUP; i++) {
            Outbound out = outboundQueue.poll();
            if (out == null) {
                break;
            }
            InetSocketAddress dest = new InetSocketAddress(out.to.host(), out.to.port());
            // UDP send is atomic; a return of 0 means the OS buffer was full and the
            // datagram was NOT sent. We drop it rather than retry  -  the wire is already
            // lossy by nature and higher layers (gossip) are built to tolerate that.
            channel.send(ByteBuffer.wrap(out.frame), dest);
        }
    }

    private void closeQuietly() {
        try {
            if (channel != null) {
                channel.close();
            }
        } catch (IOException ignored) {
            // best-effort close
        }
        try {
            if (selector != null) {
                selector.close();
            }
        } catch (IOException ignored) {
            // best-effort close
        }
    }

    private record Outbound(PeerAddress to, byte[] frame) {}
}
