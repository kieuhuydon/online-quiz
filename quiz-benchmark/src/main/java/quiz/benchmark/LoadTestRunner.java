package quiz.benchmark;

import quiz.common.dto.QuestionDto;
import quiz.common.enums.Difficulty;
import quiz.common.protocol.MessageCodec;
import quiz.common.protocol.MessageType;
import quiz.common.protocol.Request;
import quiz.common.protocol.Response;

import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thực nghiệm đo tải giao thức TCP + MessageCodec + mô hình Virtual Thread / Worker Pool.
 */
public final class LoadTestRunner {
    private static final int REQUESTS_PER_CLIENT = 100;
    private static final int WARMUP_REQUESTS = 50;

    public static void main(String[] args) throws Exception {
        int[] clientCounts = {1, 5, 10, 20};
        int workerThreads = 8;
        int queueCapacity = 200;

        System.out.println("=== ONLINE QUIZ TCP BENCHMARK (Worker=" + workerThreads + ", Queue=" + queueCapacity + ") ===");
        System.out.println("Clients | Total Req | Avg (ms) | P95 (ms) | Throughput (req/s) | Errors");
        for (int clients : clientCounts) {
            runScenario(clients, workerThreads, queueCapacity);
        }
    }

    private static void runScenario(int clientCount, int workerThreads, int queueCapacity) throws Exception {
        ArrayList<QuestionDto> samplePayload = new ArrayList<>();
        Instant now = Instant.now();
        for (int i = 1; i <= 10; i++) {
            samplePayload.add(new QuestionDto(
                i, "Sample grammar question #" + i + " for load benchmark?",
                "Option A", "Option B", "Option C", "Option D",
                'B', "Grammar", Difficulty.MEDIUM, true, 1L, now
            ));
        }

        Semaphore connectionLimit = new Semaphore(100);
        ExecutorService readerPool = Executors.newVirtualThreadPerTaskExecutor();
        ThreadPoolExecutor workerPool = new ThreadPoolExecutor(
            workerThreads, workerThreads, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(queueCapacity)
        );

        try (ServerSocket serverSocket = new ServerSocket(0)) {
            int port = serverSocket.getLocalPort();
            Thread acceptThread = Thread.ofVirtual().start(() -> {
                while (!serverSocket.isClosed()) {
                    try {
                        Socket client = serverSocket.accept();
                        connectionLimit.acquire();
                        readerPool.submit(() -> handleClient(client, workerPool, samplePayload, connectionLimit));
                    } catch (Exception ignored) {
                        break;
                    }
                }
            });

            // Warm-up
            runClientBatch(port, WARMUP_REQUESTS);

            List<Double> latenciesMs = Collections.synchronizedList(new ArrayList<>());
            AtomicInteger errors = new AtomicInteger();
            ExecutorService clientExec = Executors.newFixedThreadPool(clientCount);
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int c = 0; c < clientCount; c++) {
                tasks.add(() -> {
                    try (Socket socket = new Socket()) {
                        socket.connect(new InetSocketAddress("127.0.0.1", port), 5000);
                        socket.setTcpNoDelay(true);
                        ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
                        out.flush();
                        ObjectInputStream in = MessageCodec.createFilteredInputStream(socket.getInputStream());
                        for (int r = 1; r <= REQUESTS_PER_CLIENT; r++) {
                            long startNs = System.nanoTime();
                            MessageCodec.write(out, new Request(r, MessageType.LIST_QUESTIONS, "bench-token", null));
                            Response resp = (Response) MessageCodec.read(in);
                            long elapsedNs = System.nanoTime() - startNs;
                            if (resp != null && resp.success()) {
                                latenciesMs.add(elapsedNs / 1_000_000.0);
                            } else {
                                errors.incrementAndGet();
                            }
                        }
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    }
                    return null;
                });
            }

            long batchStartNs = System.nanoTime();
            List<Future<Void>> futures = clientExec.invokeAll(tasks);
            for (Future<Void> f : futures) {
                f.get();
            }
            long batchElapsedNs = System.nanoTime() - batchStartNs;
            clientExec.shutdownNow();

            Collections.sort(latenciesMs);
            int total = clientCount * REQUESTS_PER_CLIENT;
            double avg = latenciesMs.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            double p95 = latenciesMs.isEmpty() ? 0.0
                : latenciesMs.get(Math.min(latenciesMs.size() - 1, (int) Math.ceil(latenciesMs.size() * 0.95) - 1));
            double throughput = (latenciesMs.size() * 1_000_000_000.0) / batchElapsedNs;

            System.out.printf(Locale.US, "%7d | %9d | %8.2f | %8.2f | %18.1f | %6d%n",
                clientCount, total, avg, p95, throughput, errors.get());

            serverSocket.close();
            acceptThread.join(1000);
        } finally {
            workerPool.shutdownNow();
            readerPool.shutdownNow();
        }
    }

    private static void runClientBatch(int port, int requests) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setTcpNoDelay(true);
            ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
            out.flush();
            ObjectInputStream in = MessageCodec.createFilteredInputStream(socket.getInputStream());
            for (int i = 1; i <= requests; i++) {
                MessageCodec.write(out, new Request(i, MessageType.HEARTBEAT, "warmup", null));
                MessageCodec.read(in);
            }
        }
    }

    private static void handleClient(Socket socket,
                                     ThreadPoolExecutor workerPool,
                                     ArrayList<QuestionDto> samplePayload,
                                     Semaphore connectionLimit) {
        try (socket) {
            socket.setTcpNoDelay(true);
            ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
            out.flush();
            ObjectInputStream in = MessageCodec.createFilteredInputStream(socket.getInputStream());
            while (!socket.isClosed()) {
                Request req = (Request) MessageCodec.read(in);
                if (req == null) {
                    break;
                }
                Future<Response> task = workerPool.submit(() -> {
                    if (req.type() == MessageType.HEARTBEAT) {
                        return Response.success(req.requestId(), null);
                    }
                    return Response.success(req.requestId(), samplePayload);
                });
                Response resp = task.get();
                synchronized (out) {
                    MessageCodec.write(out, resp);
                }
            }
        } catch (Exception ignored) {
        } finally {
            connectionLimit.release();
        }
    }
}
