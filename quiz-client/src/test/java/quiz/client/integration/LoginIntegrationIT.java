package quiz.client.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.postgresql.ds.PGSimpleDataSource;
import quiz.client.api.ApiException;
import quiz.client.api.QuizApi;
import quiz.client.config.ClientConfig;
import quiz.client.network.ServerConnection;
import quiz.client.support.ClientTestDatabase;
import quiz.common.dto.CreateAccountDto;
import quiz.common.enums.Role;
import quiz.common.protocol.ErrorCode;
import quiz.common.protocol.MessageType;
import quiz.server.ServerMain;
import quiz.server.config.ServerConfig;
import quiz.server.db.DataSourceProvider;
import quiz.server.domain.User;
import quiz.server.network.QuizServer;
import quiz.server.repository.jdbc.JdbcUserRepository;
import quiz.server.security.PasswordHasher;

import java.net.ServerSocket;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Production client and server over TCP/PostgreSQL, without starting the JavaFX toolkit. */
@Timeout(30)
class LoginIntegrationIT {
    @Test void adminCreatesAssignedClassAndManagesStudentsWhileTeacherIsReadOnly() throws Exception {
        User owner = fixture(Role.TEACHER), other = fixture(Role.TEACHER), student = fixture(Role.STUDENT);
        QuizApi admin = loginClient(fixture(Role.ADMIN));
        var created = await(admin.createClass(new quiz.common.dto.CreateClassDto(reserveUsername(), "Class test", owner.id())));
        try {
            assertEquals(owner.id(), created.teacherId());
            assertEquals(owner.fullName(), created.teacherName());
            await(admin.addClassMember(created.id(), student.id()));
            assertEquals(student.id(), await(admin.listClassMembers(created.id())).getFirst().studentId());
            QuizApi teacher = loginClient(owner), outsider = loginClient(other), learner = loginClient(student);
            assertTrue(await(teacher.listClasses()).stream().anyMatch(c -> c.id() == created.id()));
            assertTrue(await(outsider.listClasses()).stream().noneMatch(c -> c.id() == created.id()));
            assertError(ErrorCode.FORBIDDEN, outsider.listClassMembers(created.id()));
            assertError(ErrorCode.FORBIDDEN, learner.listClasses());
            assertEquals(1, await(teacher.listClassMembers(created.id())).size());
            assertError(ErrorCode.FORBIDDEN, teacher.createClass(new quiz.common.dto.CreateClassDto("Forbidden", null, owner.id())));
            assertError(ErrorCode.FORBIDDEN, teacher.addClassMember(created.id(), student.id()));
            assertError(ErrorCode.FORBIDDEN, teacher.removeClassMember(created.id(), student.id()));
            await(admin.removeClassMember(created.id(), student.id()));
            assertTrue(await(admin.listClassMembers(created.id())).isEmpty());
        } finally {
            try (Connection connection = dataSource.getConnection(); PreparedStatement delete = connection.prepareStatement("DELETE FROM classes WHERE id = ?")) {
                delete.setLong(1, created.id()); delete.executeUpdate();
            }
        }
    }
    private static final String PASSWORD = " e2e-password-123 ";
    private final List<QuizApi> clients = new ArrayList<>();
    private final List<String> fixtureNames = new ArrayList<>();
    private PGSimpleDataSource dataSource;
    private JdbcUserRepository users;
    private QuizServer server;
    private boolean databaseVerified;

    @BeforeEach
    void startServer() throws Exception {
        dataSource = ClientTestDatabase.load();
        try (Connection connection = dataSource.getConnection();
             Statement query = connection.createStatement();
             ResultSet result = query.executeQuery("SELECT current_database(), current_user")) {
            assertTrue(result.next());
            assertEquals("quizdb_test", result.getString(1));
            assertEquals("quiz_test", result.getString(2));
            databaseVerified = true;
        }
        users = new JdbcUserRepository(dataSource);
        int port;
        try (ServerSocket reservation = new ServerSocket(0)) {
            port = reservation.getLocalPort();
        }
        server = ServerMain.start(new ServerConfig(port, 10, 2, 16, 3,
            dataSource.getURL(), dataSource.getUser(), dataSource.getPassword(),
            2, 0, 0, 1000, 2000, 10000, 2000));
    }

    @AfterEach
    void stopServerAndRemoveFixtures() throws Exception {
        try {
            assertAll("Close every client", clients.stream()
                .map(client -> (org.junit.jupiter.api.function.Executable) () -> await(client.closeAsync())));
        } finally {
            try {
                if (server != null) {
                    server.close();
                }
            } finally {
                DataSourceProvider.close();
                if (databaseVerified) {
                    removeFixtures();
                }
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void loginLogoutAndReloginUseServerIdentity(Role role) throws Exception {
        User account = fixture(role);
        QuizApi client = connectClient();

        var login = await(client.login("  " + account.username() + "  ", PASSWORD));

        assertEquals(account.id(), login.userId());
        assertEquals(account.username(), login.username());
        assertEquals(account.fullName(), login.fullName());
        assertEquals(role, login.role());
        assertFalse(login.token().isBlank());
        long generation = client.session().current().orElseThrow().generation();

        await(client.logout());
        assertTrue(client.session().current().isEmpty());
        assertError(ErrorCode.UNAUTHENTICATED, client.listAccounts());

        var nextLogin = await(client.login(account.username(), PASSWORD));
        assertNotEquals(login.token(), nextLogin.token());
        assertEquals(generation, client.session().current().orElseThrow().generation());
    }

    @Test
    void failedCredentialsAndDisabledAccountNeverPublishSession() throws Exception {
        User account = fixture(Role.STUDENT);
        QuizApi client = connectClient();

        assertError(ErrorCode.INVALID_CREDENTIALS, client.login(account.username(), "incorrect"));
        assertTrue(client.session().current().isEmpty());
        assertTrue(users.updateActiveStatus(account.id(), false));
        assertError(ErrorCode.ACCOUNT_DISABLED, client.login(account.username(), PASSWORD));
        assertTrue(client.session().current().isEmpty());

        assertTrue(users.updateActiveStatus(account.id(), true));
        assertEquals(account.id(), await(client.login(account.username(), PASSWORD)).userId());
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"TEACHER", "STUDENT"})
    void nonAdminsCannotUseAccountCommands(Role role) throws Exception {
        User account = fixture(role);
        QuizApi client = loginClient(account);
        String username = reserveUsername();

        assertError(ErrorCode.FORBIDDEN, client.listAccounts());
        assertError(ErrorCode.FORBIDDEN, client.createAccount(
            new CreateAccountDto(username, PASSWORD, "Unauthorized creation", null, Role.STUDENT)));
        assertError(ErrorCode.FORBIDDEN, client.setAccountActive(account.id(), false));

        assertTrue(users.findByUsername(username).isEmpty());
        assertTrue(users.findById(account.id()).orElseThrow().active());
        assertEquals(account.id(), client.session().current().orElseThrow().userId());
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"TEACHER", "STUDENT"})
    void adminCreatesAndRevokesAccountsThroughThePublicApi(Role role) throws Exception {
        User admin = fixture(Role.ADMIN);
        QuizApi administrator = loginClient(admin);
        String username = reserveUsername();
        var input = new CreateAccountDto(username, PASSWORD, "Tài khoản E2E", null, role);

        var created = await(administrator.createAccount(input));

        assertEquals(admin.id(), created.createdBy());
        assertEquals(role, created.role());
        assertTrue(created.active());
        User stored = users.findById(created.id()).orElseThrow();
        assertNotEquals(PASSWORD, stored.passwordHash());
        assertTrue(PasswordHasher.checkPassword(PASSWORD, stored.passwordHash()));
        assertTrue(await(administrator.listAccounts()).contains(created));
        assertError(ErrorCode.USERNAME_ALREADY_EXISTS, administrator.createAccount(input));
        assertError(ErrorCode.FORBIDDEN, administrator.setAccountActive(admin.id(), false));

        QuizApi member = loginClient(stored);
        String oldToken = member.session().current().orElseThrow().token();
        await(administrator.setAccountActive(created.id(), false));
        assertFalse(users.findById(created.id()).orElseThrow().active());
        assertError(ErrorCode.UNAUTHENTICATED, member.listAccounts());
        assertTrue(member.session().current().isEmpty());

        await(member.connect());
        assertError(ErrorCode.ACCOUNT_DISABLED, member.login(username, PASSWORD));
        await(administrator.setAccountActive(created.id(), true));
        assertTrue(member.session().current().isEmpty());
        assertNotEquals(oldToken, await(member.login(username, PASSWORD)).token());
    }

    @Test
    void secondLoginReplacesOldConnectionWithoutOldCleanupRevokingNewSession() throws Exception {
        User admin = fixture(Role.ADMIN);
        QuizApi first = loginClient(admin);
        var events = new LinkedBlockingQueue<QuizApi.SessionLost>();
        first.addSessionLostListener(events::add);
        String firstToken = first.session().current().orElseThrow().token();

        QuizApi second = loginClient(admin);

        assertNotNull(events.poll(5, TimeUnit.SECONDS), "Old connection must report session loss");
        assertTrue(first.session().current().isEmpty());
        assertNotEquals(firstToken, second.session().current().orElseThrow().token());
        await(first.closeAsync());
        assertTrue(await(second.listAccounts()).stream().anyMatch(account -> account.id() == admin.id()));
        await(second.logout());
    }

    @Test
    void tokenFromAnotherSocketCannotAuthorizeRequests() throws Exception {
        User admin = fixture(Role.ADMIN);
        QuizApi owner = loginClient(admin);
        String token = owner.session().current().orElseThrow().token();
        ServerConnection otherSocket = new ServerConnection(clientConfig());
        try {
            long generation = await(otherSocket.connect());
            var response = await(otherSocket.send(generation, MessageType.LIST_ACCOUNTS, token, null));
            assertFalse(response.success());
            assertEquals(ErrorCode.UNAUTHENTICATED, response.errorCode());
            assertNull(response.payload());
            assertTrue(await(owner.listAccounts()).stream().anyMatch(account -> account.id() == admin.id()));
        } finally {
            await(otherSocket.closeAsync());
        }
    }

    @Test
    void serverShutdownClearsClientSessionAndNotifiesSubscriber() throws Exception {
        QuizApi client = loginClient(fixture(Role.ADMIN));
        var events = new LinkedBlockingQueue<QuizApi.SessionLost>();
        client.addSessionLostListener(events::add);

        server.close();

        var event = events.poll(5, TimeUnit.SECONDS);
        assertNotNull(event);
        assertTrue(client.isCurrent(event));
        assertTrue(client.session().current().isEmpty());
        assertError(ErrorCode.UNAUTHENTICATED, client.listAccounts());
    }

    private QuizApi loginClient(User user) throws Exception {
        QuizApi client = connectClient();
        await(client.login(user.username(), PASSWORD));
        return client;
    }

    @ParameterizedTest @EnumSource(Role.class)
    void heartbeatKeepsIdleConnectionAliveBeyondServerTimeout(Role role) throws Exception {
        server.close();
        DataSourceProvider.close();
        int port;
        try (ServerSocket reservation = new ServerSocket(0)) { port = reservation.getLocalPort(); }
        server = ServerMain.start(new ServerConfig(port, 10, 2, 16, 3,
            dataSource.getURL(), dataSource.getUser(), dataSource.getPassword(),
            2, 0, 0, 1000, 2000, 700, 2000));
        User account = fixture(role);
        QuizApi client = new QuizApi(new ServerConnection(new ClientConfig("127.0.0.1", port,
            1000, 1000, 1000, 16, 1000, 80)));
        clients.add(client);
        await(client.connect());
        await(client.login(account.username(), PASSWORD));
        Thread.sleep(2200); // More than three server idle windows, with no user interaction.
        assertEquals(account.id(), client.session().current().orElseThrow().userId());
        await(client.logout());
        assertTrue(client.session().current().isEmpty());
    }

    @Test void emailConflictIsDistinctFromUsernameAndDoesNotInsertAccount() throws Exception {
        QuizApi admin = loginClient(fixture(Role.ADMIN));
        String first = reserveUsername();
        String second = reserveUsername();
        String email = first + "@example.com";
        await(admin.createAccount(new CreateAccountDto(first, PASSWORD, "First", email, Role.STUDENT)));
        assertError(ErrorCode.EMAIL_ALREADY_EXISTS,
            admin.createAccount(new CreateAccountDto(second, PASSWORD, "Second", email, Role.TEACHER)));
        assertTrue(users.findByUsername(second).isEmpty());
        assertTrue(admin.session().current().isPresent());
    }

    private QuizApi connectClient() throws Exception {
        QuizApi client = new QuizApi(new ServerConnection(clientConfig()));
        clients.add(client);
        await(client.connect());
        return client;
    }

    private ClientConfig clientConfig() {
        return new ClientConfig("127.0.0.1", server.getPort(), 2000, 2000, 5000, 16, 2000);
    }

    private User fixture(Role role) {
        return users.save(User.createNew(reserveUsername(), PasswordHasher.hashPassword(PASSWORD),
            "Người dùng E2E " + role, null, role, null));
    }

    private String reserveUsername() {
        String username = "e2e_" + UUID.randomUUID().toString().replace("-", "");
        fixtureNames.add(username);
        return username;
    }

    private void removeFixtures() throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement delete = connection.prepareStatement("DELETE FROM users WHERE username = ?")) {
            // Children reference their creator, so delete newly created accounts first.
            for (String username : fixtureNames.reversed()) {
                delete.setString(1, username);
                delete.executeUpdate();
            }
        }
    }

    private static void assertError(ErrorCode expected, CompletableFuture<?> request) {
        ExecutionException failure = assertThrows(ExecutionException.class, () -> await(request));
        assertEquals(expected, assertInstanceOf(ApiException.class, failure.getCause()).errorCode());
    }

    private static <T> T await(CompletableFuture<T> request) throws Exception {
        return request.get(7, TimeUnit.SECONDS);
    }
}
