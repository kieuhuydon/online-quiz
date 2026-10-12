package quiz.client.network;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import quiz.client.config.ClientConfig;
import quiz.client.support.SocketFixture;
import quiz.common.protocol.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;
import static quiz.client.support.SocketFixture.*;
import static quiz.client.network.TransportException.Kind.*;

@Timeout(15)
class ServerConnectionTest {
    @Test void correlatesOutOfOrderPushAndDuplicateWithoutBlockingReader() throws Exception {
        try (var server = new SocketFixture(); var client = new ServerConnection(server.config())) {
            long generation = client.connect().get(3, TimeUnit.SECONDS);
            var peer = server.take();
            var pushed = new CompletableFuture<Response>();
            var releaseListener = new CountDownLatch(1);
            var releaseCompletion = new CountDownLatch(1);
            var completionStarted = new CountDownLatch(1);
            try (var ignored = client.addPushListener((g, response) -> {
                assertEquals(generation, g); pushed.complete(response);
                try { releaseListener.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            })) {
                var first = client.send(generation, MessageType.LIST_ACCOUNTS, "fixture-token", null);
                var second = client.send(generation, MessageType.LIST_ACCOUNTS, "fixture-token", null);
                first.thenRun(() -> {
                    completionStarted.countDown();
                    try { releaseCompletion.await(5, TimeUnit.SECONDS); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                });
                Request a = peer.read(), b = peer.read();
                assertTrue(a.requestId() > 0); assertTrue(b.requestId() > a.requestId());
                peer.send(Response.push(MessageType.PUSH_ANTICHEAT_WARNING, "push"));
                assertEquals("push", pushed.get(3, TimeUnit.SECONDS).payload());
                peer.success(b, "second"); peer.success(a, "first");
                assertEquals("second", second.get(3, TimeUnit.SECONDS).payload());
                assertTrue(completionStarted.await(3, TimeUnit.SECONDS));
                peer.success(a, "duplicate");
                var third = client.send(generation, MessageType.LIST_ACCOUNTS, null, null);
                peer.success(peer.read(), "third");
                assertEquals("third", third.get(3, TimeUnit.SECONDS).payload());
                assertEquals("first", first.get().payload());
                assertEquals(0, client.pendingCount()); assertEquals(0, client.timerCount());
            } finally { releaseListener.countDown(); releaseCompletion.countDown(); }
        }
    }

    @Test void timeoutFailsAllPendingAndReconnectRejectsOldGeneration() throws Exception {
        try (var server = new SocketFixture(); var client = new ServerConnection(server.config(250, 8))) {
            long old = client.connect().get(3, TimeUnit.SECONDS);
            var peer = server.take();
            var a = client.send(old, MessageType.LOGIN, null, null);
            var b = client.send(old, MessageType.LIST_ACCOUNTS, null, null);
            peer.read(); peer.read();
            assertKind(TIMEOUT, failure(a)); assertKind(TIMEOUT, failure(b));
            assertEquals(0, client.pendingCount()); assertEquals(0, client.timerCount());
            long fresh = client.connect().get(3, TimeUnit.SECONDS);
            var newPeer = server.take();
            client.disconnect(old, new TransportException(CONNECTION_LOST));
            assertTrue(client.isConnected(fresh));
            assertKind(CONNECTION_LOST, failure(client.send(old, MessageType.LOGIN, null, null)));
            var result = client.send(fresh, MessageType.LOGIN, null, null);
            newPeer.success(newPeer.read(), "new"); assertEquals("new", result.get(3, TimeUnit.SECONDS).payload());
        }
    }

    @Test void cancellationFreesCapacityAndLateResponseIsIgnored() throws Exception {
        try (var server = new SocketFixture(); var client = new ServerConnection(server.config(3000, 1))) {
            long generation = client.connect().get(3, TimeUnit.SECONDS);
            var peer = server.take();
            var first = client.send(generation, MessageType.LIST_ACCOUNTS, null, null);
            var request = peer.read();
            assertKind(OVERLOADED, failure(client.send(generation, MessageType.LOGIN, null, null)));
            assertTrue(first.cancel(false));
            assertEquals(0, client.pendingCount()); assertEquals(0, client.timerCount());
            peer.success(request, "cancelled");
            var next = client.send(generation, MessageType.LIST_ACCOUNTS, null, null);
            peer.success(peer.read(), "next");
            assertEquals("next", next.get(3, TimeUnit.SECONDS).payload());
            assertTrue(first.isCancelled());
        }
    }

    static Stream<Response> invalidEnvelopes() {
        return Stream.of(Response.success(999, null), Response.success(0, null),
            new Response(1, true, ErrorCode.CONFLICT, null, null, null),
            new Response(1, false, null, null, null, null),
            new Response(1, false, ErrorCode.CONFLICT, null, null, "unexpected"),
            Response.push(MessageType.LOGIN, null),
            new Response(1, true, null, null, MessageType.PUSH_FORCE_SUBMITTED, null),
            new Response(0, false, ErrorCode.CONFLICT, null, MessageType.PUSH_FORCE_SUBMITTED, null));
    }

    @ParameterizedTest @MethodSource("invalidEnvelopes")
    void invalidEnvelopeClosesConnection(Response invalid) throws Exception {
        try (var server = new SocketFixture(); var client = new ServerConnection(server.config())) {
            long generation = client.connect().get(3, TimeUnit.SECONDS);
            var peer = server.take();
            var result = client.send(generation, MessageType.LOGIN, null, null);
            peer.read(); peer.send(invalid);
            assertKind(PROTOCOL, failure(result)); assertFalse(client.isConnected(generation));
        }
    }

    @Test void malformedFrameAndWrongEnvelopeFailPending() throws Exception {
        for (int variant = 0; variant < 2; variant++) {
            try (var server = new SocketFixture(); var client = new ServerConnection(server.config())) {
                long generation = client.connect().get(3, TimeUnit.SECONDS);
                var peer = server.take();
                var result = client.send(generation, MessageType.LOGIN, null, null); peer.read();
                if (variant == 0) { peer.out.writeInt(MessageCodec.MAX_MESSAGE_BYTES + 1); peer.out.flush(); }
                else MessageCodec.write(peer.out, new Request(1, MessageType.LOGIN, null, null));
                assertKind(PROTOCOL, failure(result));
            }
        }
    }

    @Test void eofNotifiesOnceAndFailsEveryRequest() throws Exception {
        try (var server = new SocketFixture(); var client = new ServerConnection(server.config())) {
            var notifications = new LinkedBlockingQueue<Long>();
            client.addDisconnectListener((g, error) -> notifications.add(g));
            long generation = client.connect().get(3, TimeUnit.SECONDS);
            var peer = server.take();
            var first = client.send(generation, MessageType.LOGIN, null, null);
            var second = client.send(generation, MessageType.LOGOUT, null, null);
            peer.read(); peer.close();
            assertKind(CONNECTION_LOST, failure(first)); assertKind(CONNECTION_LOST, failure(second));
            assertEquals(generation, notifications.poll(3, TimeUnit.SECONDS));
            client.disconnect(generation, new TransportException(CLOSED)); client.close();
            assertTrue(notifications.isEmpty()); assertEquals(0, client.pendingCount());
        }
    }

    @Test void handshakeTimeoutAndCloseDuringHandshakeDoNotHang() throws Exception {
        try (var listener = new ServerSocket(0, 10, InetAddress.getLoopbackAddress())) {
            listener.setSoTimeout(3000);
            var config = new ClientConfig(listener.getInetAddress().getHostAddress(), listener.getLocalPort(), 1000, 150, 1000, 2, 1000);
            try (var client = new ServerConnection(config)) {
                var connecting = client.connect();
                try (var peer = listener.accept()) {
                    assertKind(TIMEOUT, failure(connecting));
                }
                var reconnect = client.connect();
                try (var peer = listener.accept()) {
                    var shutdown = client.closeAsync();
                    assertKind(CLOSED, failure(reconnect)); shutdown.get(3, TimeUnit.SECONDS);
                    assertKind(CLOSED, failure(client.connect()));
                }
            }
        }
    }

    @Test void refusedConnectionFailsPromptly() throws Exception {
        int port;
        try (var listener = new ServerSocket(0)) { port = listener.getLocalPort(); }
        try (var client = new ServerConnection(new ClientConfig("127.0.0.1", port, 200, 200, 1000, 2, 1000))) {
            assertKind(CONNECTION_LOST, failure(client.connect()));
        }
    }

    @Test void pushDeliveryIsOrderedUnsubscribableAndListenerFailureIsIsolated() throws Exception {
        try (var server = new SocketFixture(); var client = new ServerConnection(server.config())) {
            long generation = client.connect().get(3, TimeUnit.SECONDS); var peer = server.take();
            var delivered = new LinkedBlockingQueue<Integer>();
            var removedCalls = new java.util.concurrent.atomic.AtomicInteger();
            var removed = client.addPushListener((g, response) -> removedCalls.incrementAndGet());
            removed.close();
            client.addPushListener((g, response) -> { throw new IllegalStateException("fixture failure"); });
            client.addPushListener((g, response) -> delivered.add((Integer) response.payload()));
            peer.send(Response.push(MessageType.PUSH_ANTICHEAT_WARNING, 1));
            peer.send(Response.push(MessageType.PUSH_FORCE_SUBMITTED, 2));
            assertEquals(1, delivered.poll(3, TimeUnit.SECONDS)); assertEquals(2, delivered.poll(3, TimeUnit.SECONDS));
            assertEquals(0, removedCalls.get()); assertTrue(client.isConnected(generation));
        }
    }

    @Test void pushQueueOverflowClosesInsteadOfGrowingWithoutLimit() throws Exception {
        try (var server = new SocketFixture(); var client = new ServerConnection(server.config(3000, 1))) {
            long generation = client.connect().get(3, TimeUnit.SECONDS); var peer = server.take();
            var started = new CountDownLatch(1);
            client.addPushListener((g, response) -> {
                started.countDown();
                try { new CountDownLatch(1).await(5, TimeUnit.SECONDS); }
                catch (InterruptedException closed) { Thread.currentThread().interrupt(); }
            });
            var pending = client.send(generation, MessageType.LIST_ACCOUNTS, null, null); peer.read();
            peer.send(Response.push(MessageType.PUSH_ANTICHEAT_WARNING, 1));
            assertTrue(started.await(3, TimeUnit.SECONDS));
            peer.send(Response.push(MessageType.PUSH_ANTICHEAT_WARNING, 2));
            peer.send(Response.push(MessageType.PUSH_ANTICHEAT_WARNING, 3));
            assertKind(CONNECTION_LOST, failure(pending));
            client.closeAsync().get(3, TimeUnit.SECONDS);
        }
    }

    @Test void shutdownReportsCallbacksThatIgnoreInterruptAndIsIdempotent() throws Exception {
        try (var server = new SocketFixture()) {
            ClientConfig base = server.config();
            var config = new ClientConfig(base.host(), base.port(), 1000, 1000, 3000, 2, 100);
            try (var client = new ServerConnection(config)) {
                client.connect().get(3, TimeUnit.SECONDS); var peer = server.take();
                var started = new CountDownLatch(1); var release = new CompletableFuture<Void>();
                client.addPushListener((g, response) -> { started.countDown(); release.join(); });
                peer.send(Response.push(MessageType.PUSH_ANTICHEAT_WARNING, null));
                assertTrue(started.await(3, TimeUnit.SECONDS));
                try {
                    var closing = client.closeAsync();
                    assertKind(TIMEOUT, failure(closing));
                    assertKind(TIMEOUT, failure(client.closeAsync()));
                } finally { release.complete(null); }
            }
        }
    }

    @Test void concurrentConnectSharesHandshakeAndCloseReleasesBlockedWriter() throws Exception {
        try (var server = new SocketFixture(); var client = new ServerConnection(server.config(10000, 32))) {
            var first = client.connect(); var second = client.connect();
            long generation = first.get(3, TimeUnit.SECONDS);
            assertEquals(generation, second.get(3, TimeUnit.SECONDS));
            server.take(); // Deliberately never read requests; eventually fills the TCP send buffer.
            List<CompletableFuture<Response>> pending = new ArrayList<>();
            String payload = "x".repeat(900_000);
            for (int i = 0; i < 24; i++) pending.add(client.send(generation, MessageType.LOGIN, null, payload));
            client.closeAsync().get(3, TimeUnit.SECONDS);
            for (var future : pending) assertKind(CLOSED, failure(future));
            assertEquals(0, client.pendingCount());
        }
    }

    @Test void completionCancelAndDisconnectRaceNeverLeavePending() throws Exception {
        try (var server = new SocketFixture(); var client = new ServerConnection(server.config())) {
            for (int i = 0; i < 12; i++) {
                long generation = client.connect().get(3, TimeUnit.SECONDS);
                var peer = server.take();
                var result = client.send(generation, MessageType.LIST_ACCOUNTS, null, null);
                Request request = peer.read();
                var gate = new CountDownLatch(1);
                var cancel = CompletableFuture.runAsync(() -> { waitFor(gate); result.cancel(false); });
                var close = CompletableFuture.runAsync(() -> { waitFor(gate); client.disconnect(generation, new TransportException(CLOSED)); });
                gate.countDown();
                try { peer.success(request, null); }
                catch (java.io.IOException expectedDisconnect) { assertTrue(result.isDone() || !client.isConnected(generation)); }
                CompletableFuture.allOf(cancel, close).get(3, TimeUnit.SECONDS);
                await(result::isDone); assertEquals(0, client.pendingCount());
            }
        }
    }

    private static void waitFor(CountDownLatch gate) {
        try { assertTrue(gate.await(3, TimeUnit.SECONDS)); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }

    private static void assertKind(TransportException.Kind kind, Throwable error) {
        assertEquals(kind, assertInstanceOf(TransportException.class, error).kind());
    }
}
