package quiz.server.network;

import org.junit.jupiter.api.*;
import quiz.common.dto.*;
import quiz.common.enums.Role;
import quiz.common.protocol.*;
import quiz.server.config.ServerConfig;
import quiz.server.dispatch.*;
import quiz.server.dispatch.handlers.*;
import quiz.server.service.*;
import java.io.*;
import java.net.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Localhost transport with real dispatcher/lifecycle and fake business services, no database. */
@Timeout(15)
class QuizServerTest {
    final SessionRegistry sessions = new SessionRegistry();
    final ConnectionRegistry connections = new ConnectionRegistry();
    final SessionCoordinator coordinator = new SessionCoordinator(sessions);
    final AuthService auth = mock(AuthService.class);
    final AccountService accounts = mock(AccountService.class);
    QuizServer server;

    AuthResult loginResult(long id) {
        var user = DispatchTestUsers.user(id, Role.ADMIN);
        return new AuthResult(coordinator.openSession(new UserSession(id, user.role(), user.username())), user);
    }
    @BeforeEach void configure() {
        when(auth.login(anyString(), anyString())).thenAnswer(call -> loginResult(call.<String>getArgument(0).equals("other") ? 8 : 7));
        when(auth.authenticate(anyString())).thenAnswer(call -> coordinator.withCurrentSession(call.getArgument(0), s -> s));
        doAnswer(call -> { coordinator.logout(call.getArgument(0)); return null; }).when(auth).logout(anyString());
        when(accounts.listAccounts(anyLong())).thenReturn(List.of(DispatchTestUsers.user(7, Role.ADMIN)));
        when(accounts.createAccount(any(), anyLong())).thenReturn(DispatchTestUsers.user(8, Role.STUDENT));
    }
    @AfterEach void stop() { if (server != null) server.close(); }
    void start(int max, int workers, int queue, int handshake, int read) throws IOException {
        var config = new ServerConfig(5555, max, workers, queue, 3, "jdbc:postgresql://localhost/unused",
            "unused", "test-only", 2, 0, 0, 250, handshake, read, 1000);
        var dispatcher = new RequestDispatcher(new AuthGuard(auth), List.of(new LoginHandler(auth), new AccountHandler(accounts)));
        server = new QuizServer(0, config, dispatcher, new SessionLifecycle(coordinator, connections), sessions, connections);
    }

    @Test void fiveCommandsAndRelogin() throws Exception {
        start(10, 2, 10, 1000, 3000);
        try (Peer p = new Peer(server.getPort())) {
            String token = p.login("admin");
            assertTrue(p.exchange(MessageType.LIST_ACCOUNTS, token, null).success());
            assertTrue(p.exchange(MessageType.CREATE_ACCOUNT, token, new CreateAccountDto("student", "password", "Student", null, Role.STUDENT)).success());
            assertTrue(p.exchange(MessageType.SET_ACCOUNT_ACTIVE, token, new SetAccountActiveDto(8, false)).success());
            verify(accounts).setAccountActive(8, false, 7);
            assertEquals(ErrorCode.CONFLICT, p.exchange(MessageType.LOGIN, null, new LoginRequestDto("admin", "password")).errorCode());
            assertTrue(p.exchange(MessageType.LOGOUT, token, null).success());
            assertEquals(0, connections.size()); assertEquals(0, sessions.size());
            assertEquals(ErrorCode.UNAUTHENTICATED, p.exchange(MessageType.LIST_ACCOUNTS, token, null).errorCode());
            assertNotEquals(token, p.login("admin"));
        }
        await(() -> sessions.size() == 0 && connections.size() == 0);
    }
    @Test void stolenTokenRejectedAndReconnectPreservesNewMapping() throws Exception {
        start(10, 2, 10, 1000, 3000);
        try (Peer a = new Peer(server.getPort()); Peer b = new Peer(server.getPort())) {
            String old = a.login("admin");
            assertEquals(ErrorCode.UNAUTHENTICATED, b.exchange(MessageType.LIST_ACCOUNTS, old, null).errorCode());
            String current = b.login("admin");
            assertThrows(IOException.class, () -> MessageCodec.read(a.in));
            assertFalse(sessions.isValid(old));
            assertTrue(b.exchange(MessageType.LIST_ACCOUNTS, current, null).success());
            assertEquals(1, connections.size());
        }
    }
    @Test void pipelinedFramesKeepOrder() throws Exception {
        start(10, 2, 10, 1000, 3000);
        try (Peer p = new Peer(server.getPort())) {
            String t = p.login("admin");
            MessageCodec.write(p.out, new Request(20, MessageType.LIST_ACCOUNTS, t, null));
            MessageCodec.write(p.out, new Request(21, MessageType.LOGOUT, t, null));
            MessageCodec.write(p.out, new Request(22, MessageType.LIST_ACCOUNTS, t, null));
            Response a = (Response) MessageCodec.read(p.in), b = (Response) MessageCodec.read(p.in), c = (Response) MessageCodec.read(p.in);
            assertEquals(20, a.requestId()); assertTrue(a.success());
            assertEquals(21, b.requestId()); assertTrue(b.success());
            assertEquals(22, c.requestId()); assertEquals(ErrorCode.UNAUTHENTICATED, c.errorCode());
        }
    }
    @Test void handshakeAndIdleTimeoutReleaseSlots() throws Exception {
        start(1, 1, 1, 250, 250);
        try (Socket silent = new Socket("127.0.0.1", server.getPort())) {
            await(() -> server.getOpenConnectionCount() == 1);
            await(() -> server.getOpenConnectionCount() == 0);
        }
        try (Peer idle = new Peer(server.getPort())) { assertThrows(IOException.class, () -> MessageCodec.read(idle.in)); }
        await(() -> server.getOpenConnectionCount() == 0);
    }
    @Test void connectionLimitAndShutdownIncludeHandshakeSockets() throws Exception {
        start(1, 1, 1, 10000, 10000);
        try (Socket first = new Socket("127.0.0.1", server.getPort())) {
            await(() -> server.getOpenConnectionCount() == 1);
            try (Socket excess = new Socket("127.0.0.1", server.getPort())) {
                excess.setSoTimeout(2000); assertEquals(-1, excess.getInputStream().read());
            }
            assertTimeoutPreemptively(Duration.ofSeconds(3), server::close);
            first.setSoTimeout(2000); first.getInputStream().readNBytes(4);
            assertEquals(-1, first.getInputStream().read());
            assertEquals(0, server.getOpenConnectionCount()); server.close();
        }
    }
    @Test void malformedAndTruncatedFramesDoNotBreakOtherClients() throws Exception {
        start(10, 2, 10, 1000, 1000);
        for (int length : new int[]{0, -1, MessageCodec.MAX_MESSAGE_BYTES + 1}) {
            try (Peer bad = new Peer(server.getPort())) {
                bad.out.writeInt(length); bad.out.flush();
                assertThrows(IOException.class, () -> MessageCodec.read(bad.in));
            }
        }
        try (Peer bad = new Peer(server.getPort())) {
            bad.out.writeInt(100); bad.out.write(new byte[2]); bad.out.flush(); bad.socket.shutdownOutput();
            assertThrows(IOException.class, () -> MessageCodec.read(bad.in));
        }
        try (Peer good = new Peer(server.getPort())) { assertNotNull(good.login("admin")); }
    }
    @Test void workerOverloadReturnsBusyWithMatchingId() throws Exception {
        start(10, 1, 1, 1000, 5000);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        when(auth.login(eq("blocked"), anyString())).thenAnswer(call -> {
            entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); return loginResult(7);
        });
        try (Peer a = new Peer(server.getPort()); Peer b = new Peer(server.getPort()); Peer c = new Peer(server.getPort())) {
            MessageCodec.write(a.out, new Request(1, MessageType.LOGIN, null, new LoginRequestDto("blocked", "password")));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            MessageCodec.write(b.out, new Request(2, MessageType.LOGIN, null, new LoginRequestDto("other", "password")));
            await(() -> server.getQueuedRequestCount() == 1);
            assertEquals(ErrorCode.SERVER_BUSY, c.exchange(MessageType.LOGIN, null, new LoginRequestDto("admin", "password")).errorCode());
            verify(auth, never()).login("admin", "password"); release.countDown();
            assertTrue(((Response) MessageCodec.read(a.in)).success());
            assertTrue(((Response) MessageCodec.read(b.in)).success());
        } finally { release.countDown(); }
    }
    @Test void lateLoginAfterShutdownRevokesItsOwnToken() throws Exception {
        start(10, 1, 1, 1000, 5000);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        when(auth.login(anyString(), anyString())).thenAnswer(call -> {
            entered.countDown(); boolean done = false;
            while (!done) try { done = release.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
            return loginResult(7);
        });
        try (Peer p = new Peer(server.getPort())) {
            MessageCodec.write(p.out, new Request(1, MessageType.LOGIN, null, new LoginRequestDto("admin", "password")));
            assertTrue(entered.await(2, TimeUnit.SECONDS)); server.close(); release.countDown();
            await(() -> server.workersTerminated());
            assertEquals(0, sessions.size()); assertEquals(0, connections.size());
        } finally { release.countDown(); }
    }
    static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "Condition timed out");
    }
    static final class Peer implements AutoCloseable {
        final Socket socket; final ObjectOutputStream out; final ObjectInputStream in; long id;
        Peer(int port) throws IOException {
            socket = new Socket("127.0.0.1", port); socket.setSoTimeout(3000);
            out = new ObjectOutputStream(socket.getOutputStream()); out.flush();
            in = MessageCodec.createFilteredInputStream(socket.getInputStream());
        }
        Response exchange(MessageType type, String token, Serializable payload) throws Exception {
            long rid = ++id; MessageCodec.write(out, new Request(rid, type, token, payload));
            Response result = (Response) MessageCodec.read(in);
            assertEquals(rid, result.requestId()); assertNull(result.pushType()); return result;
        }
        String login(String name) throws Exception {
            Response result = exchange(MessageType.LOGIN, null, new LoginRequestDto(name, "password"));
            assertTrue(result.success()); return ((LoginResultDto) result.payload()).token();
        }
        public void close() throws IOException { socket.close(); }
    }
}
