package quiz.server.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import quiz.common.dto.CreateAccountDto;
import quiz.common.enums.Role;
import quiz.common.protocol.ErrorCode;
import quiz.server.db.TransactionManager;
import quiz.server.domain.User;
import quiz.server.exception.ConflictException;
import quiz.server.exception.ForbiddenException;
import quiz.server.exception.QuizException;
import quiz.server.exception.UnauthorizedException;
import quiz.server.network.SessionRegistry;
import quiz.server.repository.UserRepository;
import quiz.server.repository.jdbc.JdbcUserRepository;
import quiz.server.security.PasswordHasher;
import quiz.server.service.AccountService;
import quiz.server.service.AuthService;
import quiz.server.service.SessionCoordinator;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Service flows using real transactions on the dedicated test database, without socket/UI code. */
class AuthAccountServiceIT {
    private static final String PASSWORD = "service-fixture-password";
    private final List<String> names = new ArrayList<>();
    private DataSource dataSource;
    private UserRepository users;
    private SessionRegistry registry;
    private AccountService accounts;
    private AuthService auth;
    private SessionCoordinator coordinator;
    private User admin;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = PostgreSqlTestConfig.load().dataSource();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT current_database(), current_user");
             ResultSet rows = statement.executeQuery()) {
            assertTrue(rows.next());
            assertEquals("quizdb_test", rows.getString(1));
            assertEquals("quiz_test", rows.getString(2));
        }
        users = new JdbcUserRepository(dataSource);
        registry = new SessionRegistry();
        coordinator = new SessionCoordinator(registry);
        accounts = new AccountService(users, new TransactionManager(dataSource), coordinator);
        auth = new AuthService(users, coordinator);
        admin = users.save(User.createNew(nextName(), PasswordHasher.hashPassword(PASSWORD),
            "Service Fixture Admin", null, Role.ADMIN, null));
    }

    @AfterEach
    void cleanupOnlyOwnFixtures() throws SQLException {
        if (dataSource == null || names.isEmpty()) {
            return;
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM users WHERE username = ?")) {
            for (int index = names.size() - 1; index >= 0; index--) {
                statement.setString(1, names.get(index));
                statement.executeUpdate();
            }
        }
    }

    @Test
    void createLoginListDisableAndReenableRequireNewToken() {
        User student = createStudent();
        assertEquals(admin.id(), student.createdBy());
        assertTrue(PasswordHasher.checkPassword(PASSWORD, student.passwordHash()));
        assertTrue(accounts.listAccounts(admin.id()).stream().anyMatch(user -> user.id() == student.id()));
        String token = auth.login(student.username(), PASSWORD).token();
        assertEquals(Role.STUDENT, auth.authenticate(token).role());

        accounts.setAccountActive(student.id(), false, admin.id());

        assertFalse(users.findById(student.id()).orElseThrow().active());
        assertThrows(UnauthorizedException.class, () -> auth.authenticate(token));
        assertEquals(ErrorCode.ACCOUNT_DISABLED, assertThrows(QuizException.class,
            () -> auth.login(student.username(), PASSWORD)).getErrorCode());
        accounts.setAccountActive(student.id(), true, admin.id());
        assertThrows(UnauthorizedException.class, () -> auth.authenticate(token));
        String replacement = auth.login(student.username(), PASSWORD).token();
        assertNotEquals(token, replacement);
        auth.logout(replacement);
        assertEquals(0, registry.size());
    }

    @Test
    void nonAdminCannotBypassGuardByCallingServiceDirectly() {
        User student = createStudent();
        CreateAccountDto dto = new CreateAccountDto(nextName(), PASSWORD, "Unauthorized Fixture", null, Role.TEACHER);
        assertThrows(ForbiddenException.class, () -> accounts.createAccount(dto, student.id()));
        assertThrows(ForbiddenException.class, () -> accounts.listAccounts(student.id()));
        assertThrows(ForbiddenException.class, () -> accounts.setAccountActive(student.id(), false, student.id()));
        assertTrue(users.findByUsername(dto.username()).isEmpty());
        assertTrue(users.findById(student.id()).orElseThrow().active());
        assertThrows(ForbiddenException.class, () -> accounts.setAccountActive(admin.id(), false, admin.id()));
        assertTrue(users.findById(admin.id()).orElseThrow().active());
    }

    @Test
    void failedWriteRollsBackStatusAndPreservesSession() throws Exception {
        User student = createStudent();
        String token = auth.login(student.username(), PASSWORD).token();
        // Force a real UPDATE failure using a read-only transaction.
        DataSource readOnly = PostgreSqlTestConfig.load().dataSource();
        AccountService failingAccounts = new AccountService(users, new TransactionManager(readOnly) {
            @Override
            public <T> T executeInTransaction(java.util.function.Function<Connection, T> operation) {
                return super.executeInTransaction(connection -> {
                    try (PreparedStatement statement = connection.prepareStatement("SET TRANSACTION READ ONLY")) {
                        statement.execute();
                    } catch (SQLException error) {
                        throw new QuizException(ErrorCode.DATABASE_ERROR, "Database operation failed", error);
                    }
                    return operation.apply(connection);
                });
            }
        }, coordinator);

        QuizException error = assertThrows(QuizException.class,
            () -> failingAccounts.setAccountActive(student.id(), false, admin.id()));

        assertEquals(ErrorCode.DATABASE_ERROR, error.getErrorCode());
        assertTrue(users.findById(student.id()).orElseThrow().active());
        assertEquals(student.id(), auth.authenticate(token).userId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"username", "email"})
    void concurrentCreatesPersistExactlyOneAccount(String field) throws Exception {
        String firstName = nextName();
        String secondName = "username".equals(field) ? firstName : nextName();
        String email = nextEmail();
        CreateAccountDto first = new CreateAccountDto(firstName, PASSWORD, "Race Fixture", email, Role.STUDENT);
        CreateAccountDto second = new CreateAccountDto(secondName, PASSWORD, "Race Fixture",
            "email".equals(field) ? email : nextEmail(), Role.STUDENT);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var left = executor.submit(() -> createAfterBarrier(first, ready, start));
            var right = executor.submit(() -> createAfterBarrier(second, ready, start));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            assertEquals(1, left.get(15, TimeUnit.SECONDS) + right.get(15, TimeUnit.SECONDS));
            assertEquals(1, accounts.listAccounts(admin.id()).stream()
                .filter(user -> user.username().equals(firstName) || user.username().equals(secondName)).count());
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS));
        }
    }

    @Test
    void failedLoginDoesNotInvalidateAnExistingSuccessfulSession() {
        User student = createStudent();
        String token = auth.login(student.username(), PASSWORD).token();
        assertEquals(ErrorCode.INVALID_CREDENTIALS, assertThrows(QuizException.class,
            () -> auth.login(student.username(), "wrong-password")).getErrorCode());
        assertEquals(student.id(), auth.authenticate(token).userId());
    }

    private int createAfterBarrier(CreateAccountDto dto, CountDownLatch ready, CountDownLatch start)
        throws InterruptedException {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Race start timed out");
        }
        try {
            accounts.createAccount(dto, admin.id());
            return 1;
        } catch (ConflictException error) {
            return 0;
        }
    }

    private User createStudent() {
        return accounts.createAccount(new CreateAccountDto(nextName(), PASSWORD,
            "Nguyễn Văn Việt 😀", nextEmail(), Role.STUDENT), admin.id());
    }

    private String nextName() {
        String name = "svc_" + UUID.randomUUID().toString().replace("-", "");
        names.add(name);
        return name;
    }

    private String nextEmail() {
        return "svc_" + UUID.randomUUID().toString().replace("-", "") + "@quiz.test";
    }
}
