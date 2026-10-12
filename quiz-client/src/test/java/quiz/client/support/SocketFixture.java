package quiz.client.support;

import quiz.client.config.ClientConfig;
import quiz.common.protocol.*;
import java.io.*;
import java.net.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

/** Real loopback sockets and the shared codec, deliberately independent of quiz-server and a DB. */
public final class SocketFixture implements AutoCloseable {
    private final ServerSocket listener = new ServerSocket(0, 10, InetAddress.getLoopbackAddress());
    private final BlockingQueue<Peer> peers = new LinkedBlockingQueue<>();
    private final List<Socket> sockets = new CopyOnWriteArrayList<>();
    private final CompletableFuture<Void> acceptResult = new CompletableFuture<>();

    public SocketFixture() throws IOException {
        Thread.ofVirtual().start(() -> {
            try {
                while (!listener.isClosed()) {
                    Socket socket = listener.accept();
                    sockets.add(socket);
                    peers.add(new Peer(socket));
                }
                acceptResult.complete(null);
            } catch (IOException failed) {
                if (listener.isClosed()) acceptResult.complete(null); else acceptResult.completeExceptionally(failed);
            }
        });
    }

    public ClientConfig config() { return config(3000, 16); }
    public ClientConfig config(int requestTimeout, int capacity) {
        return new ClientConfig(listener.getInetAddress().getHostAddress(), listener.getLocalPort(),
            1000, 1000, requestTimeout, capacity, 1000);
    }

    public Peer take() throws Exception {
        Peer peer = peers.poll(3, TimeUnit.SECONDS);
        if (peer == null && acceptResult.isCompletedExceptionally()) acceptResult.get();
        assertNotNull(peer, "No client handshake");
        return peer;
    }

    public static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) LockSupport.parkNanos(1_000_000);
        assertTrue(condition.getAsBoolean(), "Condition did not complete within deadline");
    }

    public static Throwable failure(CompletableFuture<?> future) {
        return assertThrows(ExecutionException.class, () -> future.get(4, TimeUnit.SECONDS)).getCause();
    }

    @Override public void close() throws Exception {
        listener.close();
        for (Socket socket : sockets) socket.close();
        acceptResult.get(3, TimeUnit.SECONDS);
    }

    public static final class Peer implements AutoCloseable {
        public final Socket socket;
        public final ObjectOutputStream out;
        private final ObjectInputStream in;

        Peer(Socket socket) throws IOException {
            this.socket = socket;
            socket.setSoTimeout(3000);
            out = new ObjectOutputStream(socket.getOutputStream());
            out.flush();
            in = MessageCodec.createFilteredInputStream(socket.getInputStream());
        }

        public Request read() throws Exception { return assertInstanceOf(Request.class, MessageCodec.read(in)); }
        public synchronized void send(Response response) throws IOException { MessageCodec.write(out, response); }
        public void success(Request request, Serializable payload) throws IOException { send(Response.success(request.requestId(), payload)); }
        @Override public void close() throws IOException { socket.close(); }
    }
}
