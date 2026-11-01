package quiz.client.network;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quiz.client.config.ClientConfig;
import quiz.common.protocol.*;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import static quiz.client.network.TransportException.Kind.*;

/**
 * One reader and bounded serial writer per connection generation. No network I/O on the
 * calling thread except closing the local socket. Future completions and subscribers run
 * on separate virtual threads, never under the state lock or on the socket reader.
 */
public final class ServerConnection implements AutoCloseable {
    public int heartbeatIntervalMs() { return config.heartbeatIntervalMs(); }
    public int monitoringIntervalMs() { return config.monitoringIntervalMs(); }
    /** Snapshot only; interface discovery is performed by the caller off the FX thread. */
    public InetAddress localAddress() {
        synchronized(lock) {
            return current!=null && current.state==State.CONNECTED ? current.socket.getLocalAddress() : null;
        }
    }
    public enum State { DISCONNECTED, CONNECTING, CONNECTED, CLOSING, CLOSED }
    private static final Logger log = LoggerFactory.getLogger(ServerConnection.class);
    private final ClientConfig config;
    private final Object lock = new Object();
    private final List<PushListener> pushListeners = new CopyOnWriteArrayList<>();
    private final List<BiConsumer<Long, TransportException>> disconnectListeners = new CopyOnWriteArrayList<>();
    private final AtomicLong generations = new AtomicLong();
    private final Set<CompletableFuture<Void>> stopping = new HashSet<>();
    private Context current;
    private boolean closed;
    private CompletableFuture<Void> shutdown;

    public ServerConnection(ClientConfig config) {
        this.config = Objects.requireNonNull(config);
    }

    /** Concurrent callers share the in-progress handshake; the returned future is a detached view. */
    public CompletableFuture<Long> connect() {
        synchronized (lock) {
            if (closed) {
                return CompletableFuture.failedFuture(new TransportException(CLOSED));
            }
            if (current != null) {
                return current.connected.copy();
            }
            Context context = new Context(generations.incrementAndGet());
            current = context;
            context.handshakeDeadline = context.timer.schedule(() -> terminate(context, new TransportException(TIMEOUT)),
                (long) config.connectTimeoutMs() + config.handshakeTimeoutMs(), TimeUnit.MILLISECONDS);
            context.reader = Thread.ofVirtual().name("quiz-client-reader-" + context.generation).start(() -> read(context));
            return context.connected.copy();
        }
    }

    public State state() {
        synchronized (lock) {
            if (closed) {
                return State.CLOSED;
            }
            return current == null ? State.DISCONNECTED : current.state;
        }
    }

    public boolean isConnected(long generation) {
        synchronized (lock) {
            return current != null && current.generation == generation && current.state == State.CONNECTED;
        }
    }

    /** Zero means there is no established connection. */
    public long connectedGeneration() {
        synchronized (lock) {
            return current != null && current.state == State.CONNECTED ? current.generation : 0;
        }
    }

    /** Atomic, short in-memory session update. Action must not block, do I/O or invoke user callbacks. */
    public <T> T whileConnected(long generation, Supplier<T> action) {
        synchronized (lock) {
            if (!isConnected(generation)) {
                throw new TransportException(CONNECTION_LOST);
            }
            return action.get();
        }
    }

    /** Enqueues only on the specified generation; never silently reconnects or retries. */
    public CompletableFuture<Response> send(long generation, MessageType type, String token, Serializable payload) {
        Objects.requireNonNull(type, "Message type");
        synchronized (lock) {
            if (!isConnected(generation)) {
                return CompletableFuture.failedFuture(new TransportException(CONNECTION_LOST));
            }
            Context context = current;
            if (context.pending.size() >= config.maxPendingRequests()) {
                return CompletableFuture.failedFuture(new TransportException(OVERLOADED));
            }
            long id = context.ids.incrementAndGet();
            if (id <= 0) {
                terminate(context, new TransportException(PROTOCOL));
                return CompletableFuture.failedFuture(new TransportException(PROTOCOL));
            }
            Pending pending = new Pending();
            context.pending.put(id, pending);
            pending.deadline = context.timer.schedule(() -> expire(context, id, pending),
                config.requestTimeoutMs(), TimeUnit.MILLISECONDS);
            pending.future.whenComplete((result, error) -> {
                if (pending.future.isCancelled()) {
                    cancel(context, id, pending);
                }
            });
            try {
                context.writer.execute(() -> write(context, new Request(id, type, token, payload), pending));
            } catch (RejectedExecutionException full) {
                context.pending.remove(id);
                pending.deadline.cancel(false);
                complete(() -> pending.future.completeExceptionally(new TransportException(OVERLOADED)));
            }
            return pending.future;
        }
    }

    public AutoCloseable addPushListener(PushListener listener) {
        pushListeners.add(Objects.requireNonNull(listener));
        return () -> pushListeners.remove(listener);
    }

    /** Once per disconnected generation; subscriber exceptions are isolated. */
    public AutoCloseable addDisconnectListener(BiConsumer<Long, TransportException> listener) {
        disconnectListeners.add(Objects.requireNonNull(listener));
        return () -> disconnectListeners.remove(listener);
    }

    /** Closes only this generation. A delayed caller cannot disconnect a replacement socket. */
    public void disconnect(long generation, TransportException reason) {
        synchronized (lock) {
            if (current != null && current.generation == generation) {
                terminate(current, reason);
            }
        }
    }

    /** Permanently closes this client; use disconnect for a later reconnect. Does not wait on workers. */
    @Override
    public void close() {
        closeAsync();
    }

    /** Awaitable shutdown, bounded by shutdown.timeout.ms, without blocking JavaFX. */
    public CompletableFuture<Void> closeAsync() {
        synchronized (lock) {
            if (shutdown != null) {
                return shutdown.copy();
            }
            closed = true;
            if (current != null) {
                terminate(current, new TransportException(CLOSED));
            }
            pushListeners.clear();
            disconnectListeners.clear();
            shutdown = CompletableFuture.allOf(stopping.toArray(CompletableFuture[]::new));
            return shutdown.copy();
        }
    }

    private void read(Context context) {
        try {
            context.socket.connect(new InetSocketAddress(config.host(), config.port()), config.connectTimeoutMs());
            context.socket.setSoTimeout(config.handshakeTimeoutMs());
            context.out = new ObjectOutputStream(context.socket.getOutputStream());
            context.out.flush();
            ObjectInputStream in = MessageCodec.createFilteredInputStream(context.socket.getInputStream());
            context.socket.setSoTimeout(0);
            synchronized (lock) {
                if (current != context) {
                    return;
                }
                context.state = State.CONNECTED;
                context.handshakeDeadline.cancel(false);
            }
            complete(() -> context.connected.complete(context.generation));
            while (!context.socket.isClosed()) {
                Object message = MessageCodec.read(in);
                if (!(message instanceof Response response)) {
                    throw new TransportException(PROTOCOL);
                }
                receive(context, response);
            }
        } catch (SocketTimeoutException timeout) {
            terminate(context, new TransportException(TIMEOUT));
        } catch (TransportException invalid) {
            terminate(context, invalid);
        } catch (ObjectStreamException | ClassNotFoundException invalid) {
            terminate(context, new TransportException(PROTOCOL));
        } catch (IOException | RuntimeException lost) {
            log.debug("Connection ended: {}", lost.getClass().getSimpleName());
            terminate(context, new TransportException(CONNECTION_LOST));
        }
    }

    private void write(Context context, Request request, Pending pending) {
        synchronized (lock) {
            if (current != context || context.pending.get(request.requestId()) != pending) {
                return;
            }
        }
        try {
            MessageCodec.write(context.out, request);
        } catch (IOException | RuntimeException failed) {
            terminate(context, new TransportException(CONNECTION_LOST));
        }
    }

    private void receive(Context context, Response response) {
        validateEnvelope(response);
        synchronized (lock) {
            if (current != context) {
                return;
            }
            if (response.isPush()) {
                List<PushListener> subscribers = List.copyOf(pushListeners);
                try {
                    context.push.execute(() -> deliverPush(context, response, subscribers));
                } catch (RejectedExecutionException full) {
                    terminate(context, new TransportException(CONNECTION_LOST));
                }
                return;
            }
            if (response.requestId() > context.ids.get()) {
                throw new TransportException(PROTOCOL);
            }
            Pending pending = context.pending.remove(response.requestId());
            if (pending == null) {
                if (!context.duplicateLogged) {
                    log.debug("Ignored completed/cancelled response in generation {}", context.generation);
                }
                context.duplicateLogged = true; // Log at most once per generation, without payload/token.
                return;
            }
            pending.deadline.cancel(false);
            complete(() -> pending.future.complete(response));
        }
    }

    private void deliverPush(Context context, Response response, List<PushListener> subscribers) {
        for (PushListener listener : subscribers) {
            if (isConnected(context.generation) && pushListeners.contains(listener)) {
                notifySafely(() -> listener.onPush(context.generation, response));
            }
        }
    }

    private static void validateEnvelope(Response response) {
        boolean invalidErrorFields = response.success() ? response.errorCode() != null
            : response.errorCode() == null || response.payload() != null;
        boolean invalidPush = response.isPush() && (response.requestId() != 0 || !response.success()
            || (response.pushType() != MessageType.PUSH_ANTICHEAT_WARNING
                && response.pushType() != MessageType.PUSH_FORCE_SUBMITTED));
        if (invalidErrorFields || invalidPush || (!response.isPush() && response.requestId() <= 0)) {
            throw new TransportException(PROTOCOL);
        }
    }

    private void expire(Context context, long id, Pending pending) {
        synchronized (lock) {
            if (current == context && context.pending.get(id) == pending) {
                terminate(context, new TransportException(TIMEOUT));
            }
        }
    }

    private void cancel(Context context, long id, Pending pending) {
        synchronized (lock) {
            if (context.pending.remove(id, pending)) {
                pending.deadline.cancel(false);
            }
        }
    }

    private void terminate(Context context, TransportException reason) {
        synchronized (lock) {
            if (current != context) {
                return;
            }
            context.state = State.CLOSING;
            current = null;
            try {
                context.socket.close();
            } catch (IOException ignored) {
                log.debug("Socket close failed");
            }
            context.writer.shutdownNow();
            context.timer.shutdownNow();
            context.push.shutdownNow();
            if (context.reader != null) {
                context.reader.interrupt();
            }
            List<Pending> pending = new ArrayList<>(context.pending.values());
            context.pending.clear();
            complete(() -> context.connected.completeExceptionally(reason));
            for (Pending item : pending) {
                item.deadline.cancel(false);
                complete(() -> item.future.completeExceptionally(reason));
            }
            for (var listener : disconnectListeners) {
                complete(() -> notifySafely(() -> listener.accept(context.generation, reason)));
            }
            trackShutdown(context);
        }
    }

    private void trackShutdown(Context context) {
        CompletableFuture<Void> stopped = new CompletableFuture<>();
        stopping.add(stopped);
        complete(() -> {
            try {
                awaitStopped(context);
                stopped.complete(null);
                synchronized (lock) {
                    stopping.remove(stopped);
                }
            } catch (RuntimeException failed) {
                stopped.completeExceptionally(failed); // Preserve failure for the final closeAsync caller.
            }
        });
    }

    private void awaitStopped(Context context) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.shutdownTimeoutMs());
        try {
            context.writer.awaitTermination(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            context.timer.awaitTermination(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            context.push.awaitTermination(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            if (context.reader != null) {
                context.reader.join(java.time.Duration.ofNanos(Math.max(0, deadline - System.nanoTime())));
            }
            if (!context.writer.isTerminated() || !context.timer.isTerminated() || !context.push.isTerminated()
                || (context.reader != null && context.reader.isAlive())) {
                throw new TransportException(TIMEOUT);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new TransportException(CLOSED);
        }
    }

    private static void complete(Runnable task) {
        Thread.ofVirtual().name("quiz-client-callback").start(task);
    }

    private static void notifySafely(Runnable notification) {
        try {
            notification.run();
        } catch (RuntimeException failure) {
            log.warn("Client listener failed: {}", failure.getClass().getSimpleName());
        }
    }

    // Package-private diagnostics for deterministic resource assertions, never exposing payloads.
    int pendingCount() {
        synchronized (lock) {
            return current == null ? 0 : current.pending.size();
        }
    }

    int timerCount() {
        synchronized (lock) {
            return current == null ? 0 : current.timer.getQueue().size();
        }
    }

    private static final class Pending {
        final CompletableFuture<Response> future = new CompletableFuture<>();
        ScheduledFuture<?> deadline;
    }

    private final class Context {
        final long generation;
        final Socket socket = new Socket();
        final CompletableFuture<Long> connected = new CompletableFuture<>();
        final AtomicLong ids = new AtomicLong();
        final Map<Long, Pending> pending = new HashMap<>();
        final ThreadPoolExecutor writer = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(config.maxPendingRequests()),
            Thread.ofPlatform().daemon().name("quiz-client-writer-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());
        final ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1,
            Thread.ofPlatform().daemon().name("quiz-client-timeout-", 0).factory());
        final ThreadPoolExecutor push = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(config.maxPendingRequests()),
            Thread.ofPlatform().daemon().name("quiz-client-push-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());
        State state = State.CONNECTING;
        ScheduledFuture<?> handshakeDeadline;
        ObjectOutputStream out;
        Thread reader;
        boolean duplicateLogged;

        Context(long generation) {
            this.generation = generation;
            timer.setRemoveOnCancelPolicy(true);
        }
    }
}
