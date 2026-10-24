package quiz.server.network;

import quiz.server.config.ServerConfig;
import quiz.server.dispatch.RequestDispatcher;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns every accepted socket, including clients that have not sent a stream header. */
public final class QuizServer implements AutoCloseable {
    private final ServerSocket listener;
    private final ExecutorService readers = Executors.newVirtualThreadPerTaskExecutor();
    private final ThreadPoolExecutor workers;
    private final Semaphore admission;
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object acceptance = new Object();
    private final ServerConfig config;
    private final RequestDispatcher dispatcher;
    private final SessionLifecycle lifecycle;
    private final SessionRegistry sessions;
    private final ConnectionRegistry connections;
    private final Thread acceptThread;
    private final ScheduledExecutorService backgroundTasks;

    public QuizServer(int port, ServerConfig config, RequestDispatcher dispatcher,
                      SessionLifecycle lifecycle, SessionRegistry sessions, ConnectionRegistry connections) throws IOException {
        this(port, config, dispatcher, lifecycle, sessions, connections, () -> { });
    }

    public QuizServer(int port, ServerConfig config, RequestDispatcher dispatcher,
                      SessionLifecycle lifecycle, SessionRegistry sessions, ConnectionRegistry connections,
                      Runnable periodicTask) throws IOException {
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("Invalid bind port");
        }
        this.config = config;
        this.dispatcher = dispatcher;
        this.lifecycle = lifecycle;
        this.sessions = sessions;
        this.connections = connections;
        admission = new Semaphore(config.getMaxConnections());
        listener = new ServerSocket(port);
        workers = new ThreadPoolExecutor(config.getWorkerPoolSize(), config.getWorkerPoolSize(), 0,
            TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(config.getWorkerQueueCapacity()),
            new ThreadPoolExecutor.AbortPolicy());
        backgroundTasks = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "quiz-background-tasks");
            thread.setDaemon(true);
            return thread;
        });
        backgroundTasks.scheduleWithFixedDelay(() -> {
            try { periodicTask.run(); }
            catch (RuntimeException failure) { org.slf4j.LoggerFactory.getLogger(QuizServer.class)
                .warn("Background task failed exception={}", failure.getClass().getSimpleName()); }
        }, 1, 1, TimeUnit.SECONDS);
        acceptThread = new Thread(this::accept, "quiz-accept");
        acceptThread.start();
    }

    public int getPort() {
        return listener.getLocalPort();
    }

    public int getOpenConnectionCount() {
        return sockets.size();
    }

    public boolean isClosed() {
        return closed.get();
    }

    int getQueuedRequestCount() {
        return workers.getQueue().size();
    }

    boolean workersTerminated() {
        return workers.isTerminated();
    }

    public void awaitTermination() throws InterruptedException {
        acceptThread.join();
    }

    private void accept() {
        try {
            while (!closed.get()) {
                Socket socket = listener.accept();
                synchronized (acceptance) {
                    if (closed.get() || !admission.tryAcquire()) {
                        socket.close();
                        continue;
                    }
                    sockets.add(socket);
                    try {
                        readers.execute(new ClientHandler(socket, dispatcher, workers, lifecycle,
                            config.getHandshakeTimeoutMs(), config.getReadTimeoutMs(), () -> releaseConnection(socket)));
                    } catch (RejectedExecutionException stopped) {
                        releaseConnection(socket);
                        socket.close();
                    }
                }
            }
        } catch (IOException failed) {
            if (!closed.get()) {
                close();
            }
        }
    }

    private void releaseConnection(Socket socket) {
        sockets.remove(socket);
        admission.release();
    }

    @Override
    public synchronized void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.getShutdownTimeoutMs());
        backgroundTasks.shutdownNow();
        synchronized (acceptance) {
            try {
                listener.close();
            } catch (IOException ignored) {
                // Continue shutting down clients even if the listening socket fails to close.
            }
            workers.shutdown();
            readers.shutdown();
        }
        // Leave half the budget to unblock transport and release reader threads.
        try {
            workers.awaitTermination(config.getShutdownTimeoutMs() / 2L, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            sockets.forEach(QuizServer::closeSocket);
            workers.shutdownNow().forEach(task -> {
                if (task instanceof Future<?> future) {
                    future.cancel(false);
                }
            });
            readers.shutdownNow();
        }
        try {
            readers.awaitTermination(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        sessions.clear();
        connections.clear();
    }

    private static void closeSocket(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // One broken socket must not prevent the remaining readers from being released.
        }
    }
}
