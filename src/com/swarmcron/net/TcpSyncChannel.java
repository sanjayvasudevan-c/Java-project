package com.swarmcron.net;

import com.swarmcron.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * TCP-backed SyncChannel for bulk anti-entropy exchanges. Anti-entropy runs
 * on a long period (default 30s) and each exchange is small and infrequent,
 * so plain blocking I/O on a small bounded worker pool is simpler than a
 * second selector loop and costs nothing in practice -- unlike UdpTransport's
 * gossip traffic, this isn't a hot path.
 */
public final class TcpSyncChannel implements SyncChannel {

    private final PeerAddress bindAddress;
    private final long requestTimeoutMillis;
    private ServerSocketChannel serverChannel;
    private ExecutorService acceptExecutor;
    private ExecutorService workerExecutor;
    private volatile boolean running;
    private volatile PeerAddress actualAddress;

    public TcpSyncChannel(PeerAddress bindAddress, long requestTimeoutMillis) {
        this.bindAddress = bindAddress;
        this.requestTimeoutMillis = requestTimeoutMillis;
    }

    /** The actual bound address, which may differ from the requested one if port 0 (an ephemeral port) was requested. */
    public PeerAddress boundAddress() {
        PeerAddress a = actualAddress;
        return a != null ? a : bindAddress;
    }

    @Override
    public void start(RequestHandler handler) {
        try {
            serverChannel = ServerSocketChannel.open();
            serverChannel.bind(new InetSocketAddress(bindAddress.host(), bindAddress.port()));
            InetSocketAddress bound = (InetSocketAddress) serverChannel.getLocalAddress();
            actualAddress = new PeerAddress(bound.getHostString(), bound.getPort());
        } catch (IOException e) {
            throw new UncheckedIOException("failed to bind sync channel on " + bindAddress, e);
        }
        running = true;
        workerExecutor = Executors.newFixedThreadPool(4, daemonFactory("sync-worker-" + bindAddress));
        acceptExecutor = Executors.newSingleThreadExecutor(daemonFactory("sync-accept-" + bindAddress));
        acceptExecutor.submit(() -> acceptLoop(handler));
        Log.info("sync", "listening on %s", actualAddress);
    }

    private void acceptLoop(RequestHandler handler) {
        while (running) {
            try {
                SocketChannel client = serverChannel.accept();
                workerExecutor.submit(() -> serve(client, handler));
            } catch (IOException e) {
                if (running) {
                    Log.error("sync", "accept failed on %s: %s", bindAddress, e);
                }
            }
        }
    }

    private void serve(SocketChannel clientChannel, RequestHandler handler) {
        try (Socket client = clientChannel.socket()) {
            client.setSoTimeout((int) requestTimeoutMillis);
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();
            Codec.DecodedFrame req = Codec.readFrame(in);
            if (req == null) {
                return;
            }
            PeerAddress from = new PeerAddress(client.getInetAddress().getHostAddress(), client.getPort());
            byte[] response = handler.handle(from, req.type(), req.payload());
            Codec.writeFrame(out, MessageType.SYNC_DELTA, response);
        } catch (IOException e) {
            Log.warn("sync", "connection failed: %s", e);
        }
    }

    @Override
    public void request(PeerAddress to, MessageType requestType, byte[] payload, ResponseCallback callback) {
        workerExecutor.execute(() -> {
            byte[] response = null;
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(to.host(), to.port()), (int) requestTimeoutMillis);
                socket.setSoTimeout((int) requestTimeoutMillis);
                Codec.writeFrame(socket.getOutputStream(), requestType, payload);
                Codec.DecodedFrame resp = Codec.readFrame(socket.getInputStream());
                response = resp != null ? resp.payload() : null;
            } catch (IOException e) {
                Log.warn("sync", "request to %s failed: %s", to, e);
            }
            callback.onResponse(response);
        });
    }

    @Override
    public void stop() {
        running = false;
        try {
            if (serverChannel != null) {
                serverChannel.close();
            }
        } catch (IOException ignored) {
            // best-effort close
        }
        if (acceptExecutor != null) {
            acceptExecutor.shutdownNow();
        }
        if (workerExecutor != null) {
            workerExecutor.shutdownNow();
        }
    }

    private static ThreadFactory daemonFactory(String prefix) {
        return r -> {
            Thread t = new Thread(r, prefix);
            t.setDaemon(true);
            return t;
        };
    }
}
