package quiz.server.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import quiz.common.enums.Role;
import quiz.common.protocol.ErrorCode;
import quiz.server.db.TransactionManager;
import quiz.server.domain.User;
import quiz.server.exception.ConflictException;
import quiz.server.exception.QuizException;
import quiz.server.repository.jdbc.JdbcUserRepository;
import quiz.server.security.PasswordHasher;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Real PostgreSQL tests: only run by the explicit db-it profile. */
class JdbcUserRepositoryIT {
    private static DataSource dataSource;
    private static String passwordHash;
    private final List<String> fixtureNames = new ArrayList<>();

    @BeforeAll
    static void connectOnlyToPreparedTestDatabase() throws Exception {
        dataSource = PostgreSqlTestConfig.load().dataSource();
        try (Connection connection = dataSource.getConnection()) {
            verifyDatabaseIdentity(connection);
            verifySchema(connection);
        }
        passwordHash = PasswordHasher.hashPassword("integration-fixture-password");
    }

    private static void verifyDatabaseIdentity(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT current_database(), current_user");
             ResultSet rows = statement.executeQuery()) {
            assertTrue(rows.next());
            assertEquals("quizdb_test", rows.getString(1), "Only the dedicated test database is allowed");
            assertEquals("quiz_test", rows.getString(2), "Only the dedicated test role is allowed");
        }
    }

    private static void verifySchema(Connection connection) throws SQLException {
        Set<String> expected = Set.of("users", "classes", "class_members", "questions", "quizzes",
            "quiz_questions", "quiz_assignments", "attempts", "attempt_answers", "results", "anticheat_events");
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT table_name FROM information_schema.tables WHERE table_schema = ? AND table_type = 'BASE TABLE'")) {
            statement.setString(1, "public");
            try (ResultSet rows = statement.executeQuery()) {
                Set<String> actual = new HashSet<>();
                while (rows.next()) {
                    actual.add(rows.getString(1));
                }
                assertTrue(actual.containsAll(expected), "Initialize all 11 tables with database/schema.sql first");
            }
        }
    }

    @AfterEach
    void removeOnlyThisTestsFixtures() throws SQLException {
        if (fixtureNames.isEmpty()) {
            return;
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM users WHERE username = ?")) {
            // Children are created after their creator: delete them first to respect the FK.
            for (int index = fixtureNames.size() - 1; index >= 0; index--) {
                statement.setString(1, fixtureNames.get(index));
                statement.executeUpdate();
            }
        }
    }

    @Test
    void persistsAndReadsUnicodeProfileCreatorAndDatabaseMetadata() {
        JdbcUserRepository repository = repository();
        User creator = repository.save(User.createNew(nextUsername(), passwordHash,
            "Fixture Admin", null, Role.ADMIN, null));
        User input = User.createNew(nextUsername(), passwordHash, "Nguyễn Văn Việt 😀",
            nextEmail(), Role.TEACHER, creator.id());

        User saved = repository.save(input);

        assertTrue(saved.id() > creator.id());
        assertNotNull(saved.createdAt());
        assertEquals(0, input.id());
        assertNull(input.createdAt());
        assertEquals(input.username(), saved.username());
        assertEquals(input.fullName(), saved.fullName());
        assertEquals(input.email(), saved.email());
        assertEquals(input.role(), saved.role());
        assertEquals(input.createdBy(), saved.createdBy());
        assertTrue(PasswordHasher.checkPassword("integration-fixture-password", saved.passwordHash()));
        assertEquals(saved, repository.findById(saved.id()).orElseThrow());
        assertEquals(saved, repository.findByUsername(saved.username()).orElseThrow());
    }

    @Test
    void allowsSeveralUsersWithNullEmailAndBootstrapCreator() {
        User first = repository().save(fixture(null, null));
        User second = repository().save(fixture(null, null));

        assertNotEquals(first.id(), second.id());
        assertNull(first.email());
        assertNull(second.email());
        assertNull(first.createdBy());
        assertFalse(repository().existsByEmail(null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"username", "email"})
    void realUniqueViolationMapsToConflict(String field) {
        User existing = repository().save(fixture(nextEmail(), null));
        User duplicate = User.createNew("username".equals(field) ? existing.username() : nextUsername(),
            passwordHash, "Duplicate Fixture", "email".equals(field) ? existing.email() : nextEmail(),
            Role.STUDENT, null);

        ConflictException error = assertThrows(ConflictException.class, () -> repository().save(duplicate));

        assertEquals("email".equals(field) ? ErrorCode.EMAIL_ALREADY_EXISTS : ErrorCode.USERNAME_ALREADY_EXISTS, error.getErrorCode());
        assertEquals("23505", ((SQLException) error.getCause()).getSQLState());
        assertFalse(error.getMessage().contains(existing.username()));
        assertFalse(error.getMessage().contains(existing.email()));
        assertEquals(existing, repository().findById(existing.id()).orElseThrow());
    }

    @Test
    void updatesExistingStatusEvenWhenUnchangedAndReportsMissingIds() throws SQLException {
        User saved = repository().save(fixture(null, null));

        assertTrue(repository().updateActiveStatus(saved.id(), false));
        assertTrue(repository().updateActiveStatus(saved.id(), false));
        assertFalse(repository().findById(saved.id()).orElseThrow().active());
        assertTrue(repository().updateActiveStatus(saved.id(), true));
        deleteFixture(saved);
        assertFalse(repository().updateActiveStatus(saved.id(), true));
        assertTrue(repository().findById(saved.id()).isEmpty());
    }

    @Test
    void findsAllUsersInIdOrderIncludingInactiveAndPreservesCase() {
        User first = repository().save(fixture(nextEmail(), null));
        User second = repository().save(fixture(null, null));
        repository().updateActiveStatus(first.id(), false);

        List<User> all = repository().findAll();

        assertTrue(all.stream().anyMatch(user -> user.id() == first.id() && !user.active()));
        assertTrue(all.stream().anyMatch(user -> user.id() == second.id() && user.active()));
        for (int index = 1; index < all.size(); index++) {
            assertTrue(all.get(index - 1).id() < all.get(index).id());
        }
        assertTrue(repository().existsByUsername(first.username()));
        assertTrue(repository().existsByEmail(first.email()));
        assertFalse(repository().existsByUsername(first.username().toUpperCase(java.util.Locale.ROOT)));
        assertFalse(repository().existsByUsername(nextUsername()));
        assertFalse(repository().existsByEmail(nextEmail()));
    }

    @Test
    void acceptsSupplementaryUnicodeAtDatabaseCharacterLimit() {
        String prefix = nextUsername();
        String username = prefix + "😀".repeat(50 - prefix.length());
        fixtureNames.add(username);
        User saved = repository().save(User.createNew(username, passwordHash, "😀".repeat(100),
            null, Role.STUDENT, null));

        assertEquals(50, saved.username().codePointCount(0, saved.username().length()));
        assertEquals(100, saved.fullName().codePointCount(0, saved.fullName().length()));
        assertEquals(saved, repository().findByUsername(username).orElseThrow());
    }

    @Test
    void usernameWithQuotesRemainsLiteralData() {
        String username = "it" + UUID.randomUUID().toString().substring(0, 8) + "' OR '1'='1";
        fixtureNames.add(username);
        User saved = repository().save(User.createNew(username, passwordHash, "Quoted Username",
            null, Role.STUDENT, null));

        assertEquals(saved, repository().findByUsername(username).orElseThrow());
        assertTrue(repository().existsByUsername(username));
        assertTrue(repository().findByUsername("' OR '1'='1").isEmpty());
    }

    @Test
    void callerConnectionKeepsTransactionAndTimestampAcrossTimezones() throws SQLException {
        User input = fixture(null, null);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                setTimezone(connection, "Asia/Ho_Chi_Minh");
                User saved = repository().save(connection, input);
                assertFalse(connection.isClosed());
                assertFalse(connection.getAutoCommit());
                assertEquals(saved, repository().findById(connection, saved.id()).orElseThrow());
                assertTrue(repository().updateActiveStatus(connection, saved.id(), false));
                setTimezone(connection, "UTC");
                User reread = repository().findById(connection, saved.id()).orElseThrow();
                assertEquals(saved.createdAt(), reread.createdAt());
                assertFalse(reread.active());
                assertTrue(repository().findByUsername(input.username()).isEmpty(), "No implicit commit is allowed");
            } finally {
                connection.rollback();
            }
        }
        assertTrue(repository().findByUsername(input.username()).isEmpty());
    }

    @Test
    void transactionManagerCommitsParentAndChildUsingOneConnection() {
        User parentInput = fixture(null, null);
        String childName = nextUsername();

        User child = new TransactionManager(dataSource).executeInTransaction(connection -> {
            User parent = repository().save(connection, parentInput);
            assertTrue(repository().findById(connection, parent.id()).isPresent());
            return repository().save(connection, User.createNew(childName, passwordHash,
                "Child Fixture", null, Role.STUDENT, parent.id()));
        });

        assertTrue(repository().findByUsername(parentInput.username()).isPresent());
        assertEquals(child, repository().findById(child.id()).orElseThrow());
    }

    @Test
    void transactionManagerRollsBackEarlierInsertAndStatusUpdateOnUniqueFailure() {
        User existing = repository().save(fixture(null, null));
        User pending = fixture(null, null);

        assertThrows(ConflictException.class, () -> new TransactionManager(dataSource)
            .executeInTransaction(connection -> {
                repository().save(connection, pending);
                repository().updateActiveStatus(connection, existing.id(), false);
                return repository().save(connection, User.createNew(existing.username(), passwordHash,
                    "Duplicate Fixture", null, Role.STUDENT, null));
            }));

        assertTrue(repository().findByUsername(pending.username()).isEmpty());
        assertTrue(repository().findById(existing.id()).orElseThrow().active());
    }

    @Test
    void foreignKeyFailureIsDatabaseErrorAndDoesNotCreateUser() throws SQLException {
        User deleted = repository().save(fixture(null, null));
        deleteFixture(deleted);
        User input = fixture(null, deleted.id());

        QuizException error = assertThrows(QuizException.class, () -> repository().save(input));

        assertEquals(ErrorCode.DATABASE_ERROR, error.getErrorCode());
        assertEquals("23503", ((SQLException) error.getCause()).getSQLState());
        assertEquals("Database operation failed", error.getMessage());
        assertTrue(repository().findByUsername(input.username()).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"username", "email"})
    void concurrentDuplicateInsertsCommitExactlyOneUser(String field) throws Exception {
        String firstName = nextUsername();
        String secondName = "username".equals(field) ? firstName : nextUsername();
        String email = nextEmail();
        User first = User.createNew(firstName, passwordHash, "Race Fixture", email, Role.STUDENT, null);
        User second = User.createNew(secondName, passwordHash, "Race Fixture",
            "email".equals(field) ? email : nextEmail(), Role.STUDENT, null);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var left = executor.submit(() -> raceInsert(first, ready, start));
            var right = executor.submit(() -> raceInsert(second, ready, start));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            assertEquals(1, left.get(15, TimeUnit.SECONDS) + right.get(15, TimeUnit.SECONDS));
            long matches = repository().findAll().stream()
                .filter(user -> user.username().equals(firstName) || user.username().equals(secondName)).count();
            assertEquals(1, matches);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS), "Race workers must stop before cleanup");
        }
    }

    private int raceInsert(User user, CountDownLatch ready, CountDownLatch start) throws InterruptedException {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Race start timed out");
        }
        try {
            repository().save(user);
            return 1;
        } catch (ConflictException error) {
            assertEquals("23505", ((SQLException) error.getCause()).getSQLState());
            return 0;
        }
    }

    private void setTimezone(Connection connection, String timezone) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT set_config('TimeZone', ?, false)")) {
            statement.setString(1, timezone);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
            }
        }
    }

    private void deleteFixture(User user) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM users WHERE username = ?")) {
            statement.setString(1, user.username());
            assertEquals(1, statement.executeUpdate());
        }
    }

    private JdbcUserRepository repository() {
        return new JdbcUserRepository(dataSource);
    }

    private User fixture(String email, Long creator) {
        return User.createNew(nextUsername(), passwordHash, "Integration Fixture", email, Role.STUDENT, creator);
    }

    private String nextUsername() {
        String name = "it_" + UUID.randomUUID().toString().replace("-", "");
        fixtureNames.add(name);
        return name;
    }

    private String nextEmail() {
        return "it_" + UUID.randomUUID().toString().replace("-", "") + "@quiz.test";
    }
}
