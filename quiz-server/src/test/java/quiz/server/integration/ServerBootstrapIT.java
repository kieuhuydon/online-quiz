package quiz.server.integration;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.postgresql.ds.PGSimpleDataSource;
import quiz.common.dto.*;
import quiz.common.enums.Role;
import quiz.common.protocol.*;
import quiz.server.ServerMain;
import quiz.server.config.ServerConfig;
import quiz.server.db.DataSourceProvider;
import quiz.server.domain.User;
import quiz.server.network.QuizServer;
import quiz.server.repository.jdbc.JdbcUserRepository;
import quiz.server.security.PasswordHasher;
import java.io.*;
import java.net.*;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual bootstrap, TCP framing, services and PostgreSQL on the guarded isolated test database. */
@Timeout(30)
class ServerBootstrapIT {
    static final String PASSWORD = "socket-fixture-password";
    PGSimpleDataSource dataSource;
    final List<String> names = new ArrayList<>();
    QuizServer server;
    @BeforeEach void setUp() throws Exception {
        dataSource = PostgreSqlTestConfig.load().dataSource();
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT current_database(), current_user")) {
            assertTrue(r.next()); assertEquals("quizdb_test", r.getString(1)); assertEquals("quiz_test", r.getString(2));
        }
    }
    ServerConfig config(int port) {
        return new ServerConfig(port, 10, 2, 10, 3, dataSource.getURL(), dataSource.getUser(), dataSource.getPassword(),
            2, 0, 0, 1000, 1000, 3000, 2000);
    }
    String name() { String value = "socket_" + UUID.randomUUID().toString().replace("-", ""); names.add(value); return value; }
    @AfterEach void cleanup() throws Exception {
        if (server != null) server.close();
        DataSourceProvider.close();
        if (dataSource == null) return;
        try (Connection c = dataSource.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM users WHERE username = ?")) {
            for (int i = names.size() - 1; i >= 0; i--) { s.setString(1, names.get(i)); s.executeUpdate(); }
        }
    }
    @Test void realBootstrapHandlesFiveMessagesAndShutsDown() throws Exception {
        var users = new JdbcUserRepository(dataSource);
        User admin = users.save(User.createNew(name(), PasswordHasher.hashPassword(PASSWORD), "Socket Fixture Admin", null, Role.ADMIN, null));
        int port;
        try (ServerSocket reservation = new ServerSocket(0)) { port = reservation.getLocalPort(); }
        server = ServerMain.start(config(port));
        try (Socket socket = new Socket("127.0.0.1", server.getPort())) {
            socket.setSoTimeout(5000);
            ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream()); out.flush();
            ObjectInputStream in = MessageCodec.createFilteredInputStream(socket.getInputStream());
            Response login = exchange(out, in, new Request(1, MessageType.LOGIN, null, new LoginRequestDto(admin.username(), PASSWORD)));
            String token = ((LoginResultDto) login.payload()).token();
            String username = name();
            AccountDto created = (AccountDto) exchange(out, in, new Request(2, MessageType.CREATE_ACCOUNT, token,
                new CreateAccountDto(username, PASSWORD, "Nguyễn Việt", null, Role.STUDENT))).payload();
            assertEquals(admin.id(), created.createdBy());
            List<?> list = (List<?>) exchange(out, in, new Request(3, MessageType.LIST_ACCOUNTS, token, null)).payload();
            assertTrue(list.contains(created));
            exchange(out, in, new Request(4, MessageType.SET_ACCOUNT_ACTIVE, token, new SetAccountActiveDto(created.id(), false)));
            assertFalse(users.findById(created.id()).orElseThrow().active());
            exchange(out, in, new Request(5, MessageType.LOGOUT, token, null));
        }
        server.close(); DataSourceProvider.close();
        assertFalse(DataSourceProvider.isInitialized());
    }
    @Test void bindFailureClosesInitializedPool() throws Exception {
        try (ServerSocket occupied = new ServerSocket(0)) {
            assertThrows(BindException.class, () -> ServerMain.start(config(occupied.getLocalPort())));
            assertFalse(DataSourceProvider.isInitialized());
        }
    }
    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"TEACHER", "STUDENT"})
    void rolePermissionsAndDisableReenableRequireFreshSocketSession(Role role) throws Exception {
        var users = new JdbcUserRepository(dataSource);
        User admin = users.save(User.createNew(name(), PasswordHasher.hashPassword(PASSWORD), "Handoff Admin", null, Role.ADMIN, null));
        int port;
        try (ServerSocket reservation = new ServerSocket(0)) { port = reservation.getLocalPort(); }
        server = ServerMain.start(config(port));
        try (Peer adminPeer = new Peer(); Peer memberPeer = new Peer()) {
            String adminToken = adminPeer.login(admin.username());
            String username = name();
            AccountDto member = (AccountDto) adminPeer.success(MessageType.CREATE_ACCOUNT, adminToken,
                new CreateAccountDto(username, PASSWORD, "Handoff " + role, null, role)).payload();
            assertEquals(role, member.role()); assertTrue(member.active()); assertEquals(admin.id(), member.createdBy());
            assertNotEquals(PASSWORD, users.findById(member.id()).orElseThrow().passwordHash());
            String memberToken = memberPeer.login(username);
            assertEquals(ErrorCode.FORBIDDEN, memberPeer.send(MessageType.LIST_ACCOUNTS, memberToken, null).errorCode());
            assertEquals(ErrorCode.FORBIDDEN, memberPeer.send(MessageType.CREATE_ACCOUNT, memberToken,
                new CreateAccountDto(name(), PASSWORD, "Forbidden", null, Role.STUDENT)).errorCode());
            assertEquals(ErrorCode.FORBIDDEN, memberPeer.send(MessageType.SET_ACCOUNT_ACTIVE, memberToken,
                new SetAccountActiveDto(member.id(), false)).errorCode());
            assertEquals(ErrorCode.FORBIDDEN, adminPeer.send(MessageType.SET_ACCOUNT_ACTIVE, adminToken,
                new SetAccountActiveDto(admin.id(), false)).errorCode());

            adminPeer.success(MessageType.SET_ACCOUNT_ACTIVE, adminToken, new SetAccountActiveDto(member.id(), false));
            assertFalse(users.findById(member.id()).orElseThrow().active());
            assertEquals(ErrorCode.UNAUTHENTICATED, memberPeer.send(MessageType.LOGOUT, memberToken, null).errorCode());
            try (Peer disabled = new Peer()) {
                assertEquals(ErrorCode.ACCOUNT_DISABLED, disabled.send(MessageType.LOGIN, null,
                    new LoginRequestDto(username, PASSWORD)).errorCode());
            }
            adminPeer.success(MessageType.SET_ACCOUNT_ACTIVE, adminToken, new SetAccountActiveDto(member.id(), true));
            assertTrue(users.findById(member.id()).orElseThrow().active());
            assertEquals(ErrorCode.UNAUTHENTICATED, memberPeer.send(MessageType.LOGOUT, memberToken, null).errorCode());
            // Binding remains until disconnect: client must reconnect on UNAUTHENTICATED.
            assertEquals(ErrorCode.CONFLICT, memberPeer.send(MessageType.LOGIN, null,
                new LoginRequestDto(username, PASSWORD)).errorCode());
            try (Peer replacement = new Peer()) {
                String newToken = replacement.login(username); assertNotEquals(memberToken, newToken);
                assertThrows(IOException.class, () -> MessageCodec.read(memberPeer.in));
                replacement.success(MessageType.LOGOUT, newToken, null);
            }
            adminPeer.success(MessageType.LOGOUT, adminToken, null);
        }
    }
    @Test void missingUsersTableFailsReadinessAndClosesPool() throws Exception {
        // Connection still targets the guarded test database; an absent search_path has no tables.
        dataSource.setCurrentSchema("missing_socket_fixture_" + UUID.randomUUID().toString().replace("-", ""));
        int port;
        try (ServerSocket reservation = new ServerSocket(0)) { port = reservation.getLocalPort(); }
        var failure = assertThrows(quiz.server.exception.QuizException.class, () -> ServerMain.start(config(port)));
        assertEquals(ErrorCode.DATABASE_ERROR, failure.getErrorCode());
        assertFalse(DataSourceProvider.isInitialized());
        dataSource.setCurrentSchema(null);
    }
    Response exchange(ObjectOutputStream out, ObjectInputStream in, Request request) throws Exception {
        MessageCodec.write(out, request); Response response = (Response) MessageCodec.read(in);
        assertEquals(request.requestId(), response.requestId()); assertTrue(response.success(), response.message()); return response;
    }

    /** Synchronous fixture client only; production asynchronous JavaFX client belongs to stage 5. */
    final class Peer implements AutoCloseable {
        final Socket socket;
        final ObjectOutputStream out;
        final ObjectInputStream in;
        long requestId;
        Peer() throws IOException {
            socket = new Socket("127.0.0.1", server.getPort()); socket.setSoTimeout(5000);
            out = new ObjectOutputStream(socket.getOutputStream()); out.flush();
            in = MessageCodec.createFilteredInputStream(socket.getInputStream());
        }
        Response send(MessageType type, String token, Serializable payload) throws Exception {
            long id = ++requestId;
            MessageCodec.write(out, new Request(id, type, token, payload));
            Response response = (Response) MessageCodec.read(in);
            assertEquals(id, response.requestId()); assertNull(response.pushType());
            assertEquals(response.success(), response.errorCode() == null);
            return response;
        }
        Response success(MessageType type, String token, Serializable payload) throws Exception {
            Response response = send(type, token, payload); assertTrue(response.success(), response.message()); return response;
        }
        String login(String username) throws Exception {
            return ((LoginResultDto) success(MessageType.LOGIN, null, new LoginRequestDto(username, PASSWORD)).payload()).token();
        }
        public void close() throws IOException { socket.close(); }
    }
}
