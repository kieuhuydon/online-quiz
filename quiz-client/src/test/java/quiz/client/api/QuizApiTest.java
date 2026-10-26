package quiz.client.api;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import quiz.client.network.*;
import quiz.client.support.SocketFixture;
import quiz.common.dto.*;
import quiz.common.enums.Role;
import quiz.common.protocol.*;
import java.io.Serializable;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static quiz.client.support.SocketFixture.*;

@Timeout(15)
class QuizApiTest {
    @Test void heartbeatRunsOnlyInAuthenticatedSessionAndStopsOnLogout() throws Exception {
        try (var server = new SocketFixture()) {
            var base = server.config();
            var config = new quiz.client.config.ClientConfig(base.host(), base.port(), 1000, 1000, 1000, 16, 1000, 80);
            try (var api = new QuizApi(new ServerConnection(config))) {
                api.connect().get(3, TimeUnit.SECONDS);
                var peer = server.take();
                login(api, peer, Role.ADMIN);
                Request beat = peer.read();
                assertEquals(MessageType.HEARTBEAT, beat.type());
                assertEquals(TOKEN, beat.token());
                assertNull(beat.payload());
                // A delayed heartbeat cannot cause an unbounded queue of further heartbeats.
                Thread.sleep(250);
                var logout = api.logout();
                Request request = peer.read();
                assertEquals(MessageType.LOGOUT, request.type());
                peer.success(beat, null);
                peer.success(request, null);
                logout.get(3, TimeUnit.SECONDS);
                assertTrue(api.session().current().isEmpty());
                peer.socket.setSoTimeout(250);
                assertThrows(java.net.SocketTimeoutException.class, peer::read);
            }
        }
    }

    @Test void rejectedHeartbeatClearsSessionAndNotifiesOnce() throws Exception {
        try (var server = new SocketFixture()) {
            var base = server.config();
            var config = new quiz.client.config.ClientConfig(base.host(), base.port(), 1000, 1000, 1000, 16, 1000, 80);
            try (var api = new QuizApi(new ServerConnection(config))) {
                api.connect().get(3, TimeUnit.SECONDS);
                var peer = server.take();
                login(api, peer, Role.STUDENT);
                var events = new LinkedBlockingQueue<QuizApi.SessionLost>();
                api.addSessionLostListener(events::add);
                var beat = peer.read();
                peer.send(Response.error(beat.requestId(), ErrorCode.UNAUTHENTICATED, "revoked"));
                assertNotNull(events.poll(3, TimeUnit.SECONDS));
                assertTrue(api.session().current().isEmpty());
                assertTrue(events.isEmpty());
            }
        }
    }
    private static final String TOKEN = "secret-fixture-token";
    private static final AccountDto ACCOUNT = new AccountDto(8, "student", "Student", null, Role.STUDENT, true, 1L, Instant.EPOCH);

    @Test void fiveApiContractsAndReloginOnSameSocket() throws Exception {
        try (var server = new SocketFixture(); var transport = new ServerConnection(server.config()); var api = new QuizApi(transport)) {
            long generation = api.connect().get(3, TimeUnit.SECONDS);
            var peer = server.take();
            login(api, peer, Role.ADMIN);
            assertEquals(Role.ADMIN, api.session().current().orElseThrow().role());
            assertFalse(api.session().current().orElseThrow().toString().contains(TOKEN));
            assertCode(ErrorCode.CONFLICT, failure(api.login("admin", "password")));
            var create = api.createAccount(new CreateAccountDto(" student ", " pass123 ", " Student ", " ", Role.STUDENT));
            Request request = peer.read();
            assertEquals(MessageType.CREATE_ACCOUNT, request.type()); assertEquals(TOKEN, request.token());
            var dto = assertInstanceOf(CreateAccountDto.class, request.payload());
            assertEquals("student", dto.username()); assertEquals(" pass123 ", dto.password()); assertNull(dto.email());
            peer.success(request, ACCOUNT); assertEquals(ACCOUNT, create.get(3, TimeUnit.SECONDS));
            var list = api.listAccounts(); request = peer.read();
            assertEquals(MessageType.LIST_ACCOUNTS, request.type()); assertNull(request.payload()); assertEquals(TOKEN, request.token());
            peer.success(request, new ArrayList<>(List.of(ACCOUNT)));
            var accounts = list.get(3, TimeUnit.SECONDS);
            assertEquals(List.of(ACCOUNT), accounts); assertThrows(UnsupportedOperationException.class, () -> accounts.clear());
            var active = api.setAccountActive(8, false); request = peer.read();
            assertEquals(MessageType.SET_ACCOUNT_ACTIVE, request.type()); assertEquals(TOKEN, request.token());
            assertEquals(new SetAccountActiveDto(8, false), request.payload());
            peer.success(request, null); assertNull(active.get(3, TimeUnit.SECONDS));
            var logout = api.logout(); request = peer.read();
            assertEquals(MessageType.LOGOUT, request.type()); assertEquals(TOKEN, request.token()); assertNull(request.payload());
            assertCode(ErrorCode.CONFLICT, failure(api.listAccounts()));
            peer.success(request, null); logout.get(3, TimeUnit.SECONDS);
            assertTrue(api.session().current().isEmpty()); assertTrue(transport.isConnected(generation));
            login(api, peer, Role.STUDENT);
            assertEquals(Role.STUDENT, api.session().current().orElseThrow().role());
        }
    }

    @ParameterizedTest @EnumSource(value = ErrorCode.class, names = {"INVALID_CREDENTIALS", "ACCOUNT_DISABLED", "SERVER_BUSY"})
    void failedLoginNeverStoresSessionAndCanRetry(ErrorCode error) throws Exception {
        try (var server = new SocketFixture(); var transport = new ServerConnection(server.config()); var api = new QuizApi(transport)) {
            api.connect().get(3, TimeUnit.SECONDS); var peer = server.take();
            var result = api.login("user", "password"); var request = peer.read();
            assertCode(ErrorCode.CONFLICT, failure(api.login("other", "password")));
            peer.send(Response.error(request.requestId(), error, "do not expose password or SQL"));
            Throwable failure = failure(result); assertCode(error, failure);
            assertFalse(failure.getMessage().contains("SQL")); assertTrue(api.session().current().isEmpty());
            login(api, peer, Role.TEACHER);
        }
    }

    @Test void forbiddenKeepsSessionButUnauthenticatedDisconnectsAndNotifiesOnce() throws Exception {
        try (var server = new SocketFixture(); var transport = new ServerConnection(server.config()); var api = new QuizApi(transport)) {
            long old = api.connect().get(3, TimeUnit.SECONDS); var peer = server.take(); login(api, peer, Role.TEACHER);
            var events = new LinkedBlockingQueue<QuizApi.SessionLost>(); api.addSessionLostListener(events::add);
            var forbidden = api.listAccounts(); var request = peer.read();
            peer.send(Response.error(request.requestId(), ErrorCode.FORBIDDEN, "denied"));
            assertCode(ErrorCode.FORBIDDEN, failure(forbidden)); assertTrue(api.session().current().isPresent());
            var expired = api.listAccounts(); var another = api.listAccounts();
            request = peer.read(); peer.read();
            peer.send(Response.error(request.requestId(), ErrorCode.UNAUTHENTICATED, "expired"));
            assertCode(ErrorCode.UNAUTHENTICATED, failure(expired));
            assertInstanceOf(TransportException.class, failure(another));
            var event = events.poll(3, TimeUnit.SECONDS);
            assertNotNull(event); assertTrue(api.isCurrent(event)); assertTrue(events.isEmpty());
            assertTrue(api.session().current().isEmpty()); assertFalse(transport.isConnected(old));
            assertInstanceOf(TransportException.class, failure(api.login("user", "password")));
            long fresh = api.connect().get(3, TimeUnit.SECONDS); var replacement = server.take();
            login(api, replacement, Role.ADMIN);
            assertFalse(api.isCurrent(event));
            transport.disconnect(old, new TransportException(TransportException.Kind.CLOSED));
            assertEquals(fresh, api.session().current().orElseThrow().generation());
        }
    }

    @Test void malformedLoginDoesNotPublishSession() throws Exception {
        for (Serializable payload : new Serializable[] {"wrong", new LoginResultDto("", 1, "admin", "Admin", Role.ADMIN),
                new LoginResultDto(TOKEN, 0, "admin", "Admin", Role.ADMIN), new LoginResultDto(TOKEN, 1, "admin", "Admin", null)}) {
            try (var server = new SocketFixture(); var transport = new ServerConnection(server.config()); var api = new QuizApi(transport)) {
                api.connect().get(3, TimeUnit.SECONDS); var peer = server.take();
                var result = api.login("user", "password"); peer.success(peer.read(), payload);
                assertProtocol(failure(result)); assertTrue(api.session().current().isEmpty());
                assertEquals(ServerConnection.State.DISCONNECTED, transport.state());
            }
        }
    }

    @Test void malformedListAndVoidPayloadInvalidateConnection() throws Exception {
        for (int variant = 0; variant < 3; variant++) {
            try (var server = new SocketFixture(); var transport = new ServerConnection(server.config()); var api = new QuizApi(transport)) {
                api.connect().get(3, TimeUnit.SECONDS); var peer = server.take(); login(api, peer, Role.ADMIN);
                CompletableFuture<?> result = switch (variant) {
                    case 0 -> api.listAccounts(); case 1 -> api.setAccountActive(8, false); default -> api.logout();
                };
                peer.success(peer.read(), variant == 0 ? new ArrayList<>(List.of(ACCOUNT, "wrong")) : "not-null");
                assertProtocol(failure(result)); assertTrue(api.session().current().isEmpty());
            }
        }
    }

    @Test void logoutFailureStillDiscardsLocalSession() throws Exception {
        try (var server = new SocketFixture(); var transport = new ServerConnection(server.config()); var api = new QuizApi(transport)) {
            api.connect().get(3, TimeUnit.SECONDS); var peer = server.take(); login(api, peer, Role.ADMIN);
            var logout = api.logout(); var request = peer.read();
            peer.send(Response.error(request.requestId(), ErrorCode.SERVER_BUSY, "busy"));
            assertCode(ErrorCode.SERVER_BUSY, failure(logout)); assertTrue(api.session().current().isEmpty());
            assertEquals(ServerConnection.State.DISCONNECTED, transport.state());
        }
    }

    @Test void timeoutOfLoginCannotRestoreSessionAfterReconnect() throws Exception {
        try (var server = new SocketFixture(); var transport = new ServerConnection(server.config(250, 8)); var api = new QuizApi(transport)) {
            long old = api.connect().get(3, TimeUnit.SECONDS); var peer = server.take();
            var result = api.login("old", "password"); var request = peer.read();
            assertInstanceOf(TransportException.class, failure(result)); assertTrue(api.session().current().isEmpty());
            long fresh = api.connect().get(3, TimeUnit.SECONDS); var newPeer = server.take();
            login(api, newPeer, Role.STUDENT);
            try { peer.success(request, new LoginResultDto("old", 99, "old", "Old", Role.ADMIN)); }
            catch (java.io.IOException expectedClosedSocket) { assertFalse(transport.isConnected(old)); }
            assertFalse(transport.isConnected(old));
            assertEquals(fresh, api.session().current().orElseThrow().generation());
            assertEquals(Role.STUDENT, api.session().current().orElseThrow().role());
        }
    }

    @Test void oldAccountResponseIsRejectedAfterLogoutStarts() throws Exception {
        try (var server = new SocketFixture(); var transport = new ServerConnection(server.config()); var api = new QuizApi(transport)) {
            api.connect().get(3, TimeUnit.SECONDS); var peer = server.take(); login(api, peer, Role.ADMIN);
            var list = api.listAccounts(); var listRequest = peer.read();
            var logout = api.logout(); var logoutRequest = peer.read();
            peer.success(listRequest, new ArrayList<>(List.of(ACCOUNT)));
            assertInstanceOf(TransportException.class, failure(list));
            peer.success(logoutRequest, null); logout.get(3, TimeUnit.SECONDS);
            login(api, peer, Role.TEACHER);
        }
    }

    @Test void cancellingLoginOrWriteClosesSocketAndNeverRetries() throws Exception {
        try (var server = new SocketFixture(); var transport = new ServerConnection(server.config()); var api = new QuizApi(transport)) {
            api.connect().get(3, TimeUnit.SECONDS); var peer = server.take();
            var login = api.login("user", "password"); peer.read(); assertTrue(login.cancel(false));
            assertEquals(ServerConnection.State.DISCONNECTED, transport.state()); assertTrue(api.session().current().isEmpty());
            api.connect().get(3, TimeUnit.SECONDS); peer = server.take(); login(api, peer, Role.ADMIN);
            var write = api.setAccountActive(8, false); peer.read(); assertTrue(write.cancel(false));
            assertTrue(api.session().current().isEmpty()); assertEquals(ServerConnection.State.DISCONNECTED, transport.state());
        }
    }

    @Test void disconnectedSessionIsImmediatelyHiddenAndApiCloseIsIdempotent() throws Exception {
        try (var server = new SocketFixture(); var transport = new ServerConnection(server.config()); var api = new QuizApi(transport)) {
            long generation = api.connect().get(3, TimeUnit.SECONDS); var peer = server.take(); login(api, peer, Role.ADMIN);
            transport.disconnect(generation, new TransportException(TransportException.Kind.CONNECTION_LOST));
            assertTrue(api.session().current().isEmpty());
            assertCode(ErrorCode.UNAUTHENTICATED, failure(api.listAccounts()));
            api.close(); api.close(); assertInstanceOf(TransportException.class, failure(api.connect()));
        }
    }

    private static void login(QuizApi api, SocketFixture.Peer peer, Role role) throws Exception {
        var future = api.login(" admin ", " password ");
        Request request = peer.read(); assertEquals(MessageType.LOGIN, request.type()); assertNull(request.token());
        assertEquals(new LoginRequestDto("admin", " password "), request.payload());
        var login = new LoginResultDto(TOKEN, 1, "admin", "Admin", role);
        peer.success(request, login); assertEquals(login, future.get(3, TimeUnit.SECONDS));
    }

    private static void assertCode(ErrorCode code, Throwable failure) {
        assertEquals(code, assertInstanceOf(ApiException.class, failure).errorCode());
    }
    private static void assertProtocol(Throwable failure) {
        assertEquals(TransportException.Kind.PROTOCOL, assertInstanceOf(TransportException.class, failure).kind());
    }
}
