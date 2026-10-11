package quiz.server.integration;

import org.junit.jupiter.api.*;
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
}
