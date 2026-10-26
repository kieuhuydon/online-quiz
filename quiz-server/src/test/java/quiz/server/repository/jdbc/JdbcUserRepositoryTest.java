package quiz.server.repository.jdbc;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import quiz.common.enums.Role;
import quiz.common.protocol.ErrorCode;
import quiz.server.domain.User;
import quiz.server.exception.ConflictException;
import quiz.server.exception.QuizException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class JdbcUserRepositoryTest {
    private static final OffsetDateTime CREATED_AT = OffsetDateTime.parse("2026-10-10T10:00:00+07:00");
    private DataSource dataSource;
    private Connection connection;
    private PreparedStatement statement;
    private ResultSet rows;
    private JdbcUserRepository repository;

    @BeforeEach
    void setUp() throws SQLException {
        dataSource = mock(DataSource.class);
        connection = mock(Connection.class);
        statement = mock(PreparedStatement.class);
        rows = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);
        repository = new JdbcUserRepository(dataSource);
    }

    @Test
    void bindsUsernameAsDataAndMapsInactiveUserAndTimezone() throws SQLException {
        String username = "Robert' OR '1'='1";
        stubUser(42, username, false, null);
        when(rows.next()).thenReturn(true);

        User user = repository.findByUsername(username).orElseThrow();

        assertEquals(username, user.username());
        assertEquals("stored-hash", user.passwordHash());
        assertEquals("Nguyễn Văn Việt", user.fullName());
        assertEquals("User@Example.com", user.email());
        assertEquals(Role.STUDENT, user.role());
        assertFalse(user.active());
        assertNull(user.createdBy());
        assertEquals(CREATED_AT.toInstant(), user.createdAt());
        verify(statement).setString(1, username);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sql.capture());
        assertTrue(sql.getValue().contains("WHERE username = ?"));
        assertFalse(sql.getValue().contains(username));
        verifyClosedResources(true);
    }

    @Test
    void findByIdUsesSuppliedConnectionAndPreservesTransactionOwnership() throws SQLException {
        stubUser(42, "Student", true, 7L);
        when(rows.next()).thenReturn(true);

        User user = repository.findById(connection, 42).orElseThrow();

        assertEquals(42, user.id());
        assertEquals(7L, user.createdBy());
        verify(statement).setLong(1, 42);
        verifyClosedResources(false);
        verifyNoInteractions(dataSource);
        verifyTransactionUntouched();
    }

    @Test
    void missingUserReturnsEmptyAndClosesOwnedConnection() throws SQLException {
        assertTrue(repository.findById(123).isEmpty());
        verify(statement).setLong(1, 123);
        verifyClosedResources(true);
    }

    @Test
    void nullEmailSkipsDatabaseEntirely() {
        assertFalse(repository.existsByEmail(null));
        verifyNoInteractions(dataSource, connection, statement, rows);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void existenceQueriesRespectResultsAndCase(boolean exists) throws SQLException {
        when(rows.next()).thenReturn(exists);

        assertEquals(exists, repository.existsByUsername("Student"));
        assertEquals(exists, repository.existsByEmail("User@Example.com"));

        verify(statement).setString(1, "Student");
        verify(statement).setString(1, "User@Example.com");
        verify(connection, times(2)).close();
        verify(statement, times(2)).close();
        verify(rows, times(2)).close();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void insertBindsAllFieldsAndReturnsDatabaseMetadata(boolean bootstrap) throws SQLException {
        User input = User.createNew("Student", "stored-hash", "Nguyễn Văn Việt",
            bootstrap ? null : "User@Example.com", Role.STUDENT, bootstrap ? null : 7L);
        stubUser(42, input.username(), true, input.createdBy());
        when(rows.getString("email")).thenReturn(input.email());
        when(rows.next()).thenReturn(true);

        User saved = repository.save(input);

        assertEquals(42, saved.id());
        assertEquals(CREATED_AT.toInstant(), saved.createdAt());
        assertEquals(input.email(), saved.email());
        assertEquals(input.createdBy(), saved.createdBy());
        assertEquals(0, input.id());
        assertNull(input.createdAt());
        verify(statement).setString(1, input.username());
        verify(statement).setString(2, input.passwordHash());
        verify(statement).setString(3, input.fullName());
        verify(statement).setString(5, "STUDENT");
        verify(statement).setBoolean(6, true);
        if (bootstrap) {
            verify(statement).setNull(4, Types.VARCHAR);
            verify(statement).setNull(7, Types.BIGINT);
        } else {
            verify(statement).setString(4, input.email());
            verify(statement).setLong(7, 7);
        }
        verify(connection).prepareStatement(contains("RETURNING id"));
        verifyClosedResources(true);
    }

    @Test
    void insertOnCallerConnectionNeverAcquiresOrFinalizesConnection() throws SQLException {
        stubUser(42, "Student", true, null);
        when(rows.next()).thenReturn(true);

        repository.save(connection, newUser());

        verifyClosedResources(false);
        verifyNoInteractions(dataSource);
        verifyTransactionUntouched();
    }

    @ParameterizedTest
    @CsvSource({"0, false", "1, true"})
    void statusUpdateReportsMatchedRowWithoutTouchingOtherFields(int count, boolean expected)
        throws SQLException {
        when(statement.executeUpdate()).thenReturn(count);

        assertEquals(expected, repository.updateActiveStatus(42, false));

        verify(connection).prepareStatement("UPDATE users SET active = ? WHERE id = ?");
        verify(statement).setBoolean(1, false);
        verify(statement).setLong(2, 42);
        verify(statement).close();
        verify(connection).close();
        verifyNoInteractions(rows);
    }

    @Test
    void statusUpdateOnCallerConnectionLeavesLifecycleAlone() throws SQLException {
        when(statement.executeUpdate()).thenReturn(1);

        assertTrue(repository.updateActiveStatus(connection, 42, true));

        verify(statement).close();
        verify(connection, never()).close();
        verifyNoInteractions(dataSource);
        verifyTransactionUntouched();
    }

    @Test
    void listQueriesByIdOrderAndIncludesInactiveUsers() throws SQLException {
        stubUser(42, "Student", false, 7L);
        when(rows.next()).thenReturn(true, true, false);
        when(rows.getLong("id")).thenReturn(42L, 43L);
        when(rows.getBoolean("active")).thenReturn(false, true);

        var users = repository.findAll();

        assertEquals(2, users.size());
        assertEquals(42, users.get(0).id());
        assertEquals(43, users.get(1).id());
        assertFalse(users.get(0).active());
        assertTrue(users.get(1).active());
        verify(connection).prepareStatement(contains("ORDER BY id ASC"));
        verifyClosedResources(true);
    }

    @Test
    void emptyListIsReturnedOnlyForEmptyQuery() throws SQLException {
        assertTrue(repository.findAll().isEmpty());
        verifyClosedResources(true);
    }

    @ParameterizedTest
    @CsvSource({"users_username_key, Username", "users_email_key, Email"})
    void translatesOnlyRecognizedUniqueConstraints(String constraint, String field) throws SQLException {
        PSQLException cause = postgresError("23505", constraint);
        when(statement.executeQuery()).thenThrow(cause);

        ConflictException error = assertThrows(ConflictException.class,
            () -> repository.save(connection, newUser()));

        assertEquals("Email".equals(field) ? ErrorCode.EMAIL_ALREADY_EXISTS : ErrorCode.USERNAME_ALREADY_EXISTS, error.getErrorCode());
        assertSame(cause, error.getCause());
        assertEquals(field + " is already in use", error.getMessage());
        assertFalse(error.getMessage().contains("private"));
        verify(statement).close();
        verify(connection, never()).close();
        verifyTransactionUntouched();
    }

    @ParameterizedTest
    @CsvSource({"23505, users_pkey", "23503, users_created_by_fkey", "08006, users_username_key"})
    void otherDatabaseErrorsStayDatabaseErrors(String state, String constraint) throws SQLException {
        PSQLException cause = postgresError(state, constraint);
        when(statement.executeQuery()).thenThrow(cause);

        QuizException error = assertThrows(QuizException.class, () -> repository.save(newUser()));

        assertDatabaseError(error, cause);
        verify(statement).close();
        verify(connection).close();
    }

    @Test
    void connectionAcquisitionFailureIsNotAnEmptyResult() throws SQLException {
        SQLException cause = new SQLException("private connection details");
        when(dataSource.getConnection()).thenThrow(cause);

        assertDatabaseError(assertThrows(QuizException.class, repository::findAll), cause);
        verifyNoInteractions(statement, rows);
    }

    @Test
    void queryFailureClosesStatementAndOwnedConnection() throws SQLException {
        SQLException cause = new SQLException("private SQL details");
        when(statement.executeQuery()).thenThrow(cause);

        assertDatabaseError(assertThrows(QuizException.class,
            () -> repository.findByUsername("Student")), cause);
        verify(statement).close();
        verify(connection).close();
    }

    @Test
    void mappingSqlFailureClosesAllResourcesWithoutClosingCallerConnection() throws SQLException {
        SQLException cause = new SQLException("private mapping details");
        when(rows.next()).thenReturn(true);
        when(rows.getObject("created_at", OffsetDateTime.class)).thenThrow(cause);

        assertDatabaseError(assertThrows(QuizException.class,
            () -> repository.findById(connection, 42)), cause);
        verifyClosedResources(false);
        verifyTransactionUntouched();
    }

    @ParameterizedTest
    @ValueSource(strings = {"role", "timestamp", "username"})
    void corruptStoredDataIsDatabaseErrorRatherThanInputValidation(String invalidField) throws SQLException {
        stubUser(42, "Student", true, null);
        when(rows.next()).thenReturn(true);
        switch (invalidField) {
            case "role" -> when(rows.getString("role")).thenReturn("INVALID");
            case "timestamp" -> when(rows.getObject("created_at", OffsetDateTime.class)).thenReturn(null);
            case "username" -> when(rows.getString("username")).thenReturn(" ");
            default -> throw new IllegalArgumentException("Unknown fixture");
        }

        QuizException error = assertThrows(QuizException.class, () -> repository.findById(42));

        assertEquals(ErrorCode.DATABASE_ERROR, error.getErrorCode());
        assertInstanceOf(SQLException.class, error.getCause());
        assertInstanceOf(IllegalArgumentException.class, error.getCause().getCause());
        verifyClosedResources(true);
    }

    @Test
    void insertWithoutReturnedRowIsNotSuccessful() throws SQLException {
        QuizException error = assertThrows(QuizException.class, () -> repository.save(newUser()));

        assertEquals(ErrorCode.DATABASE_ERROR, error.getErrorCode());
        verifyClosedResources(true);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n", "\u2003"})
    void invalidUsernamesAreRejectedBeforeDatabaseAccess(String value) {
        assertThrows(IllegalArgumentException.class, () -> repository.findByUsername(value));
        assertThrows(IllegalArgumentException.class, () -> repository.existsByUsername(value));
        verifyNoInteractions(dataSource, connection);
    }

    @Test
    void validatesLengthsIdsNewUserAndCallerConnectionBeforeDatabaseAccess() {
        User persisted = User.restore(42, "Student", "stored-hash", "Full Name", null,
            Role.STUDENT, true, null, CREATED_AT.toInstant());
        assertAll(
            () -> assertThrows(IllegalArgumentException.class, () -> new JdbcUserRepository(null)),
            () -> assertThrows(IllegalArgumentException.class, () -> repository.findById(0)),
            () -> assertThrows(IllegalArgumentException.class, () -> repository.findById(connection, -1)),
            () -> assertThrows(IllegalArgumentException.class, () -> repository.updateActiveStatus(0, true)),
            () -> assertThrows(IllegalArgumentException.class,
                () -> repository.updateActiveStatus(connection, -1, true)),
            () -> assertThrows(IllegalArgumentException.class, () -> repository.findById(null, 42)),
            () -> assertThrows(IllegalArgumentException.class, () -> repository.save(null)),
            () -> assertThrows(IllegalArgumentException.class, () -> repository.save(persisted)),
            () -> assertThrows(IllegalArgumentException.class, () -> repository.save(connection, persisted)),
            () -> assertThrows(IllegalArgumentException.class, () -> repository.save(null, newUser())),
            () -> assertThrows(IllegalArgumentException.class, () -> repository.updateActiveStatus(null, 42, true)),
            () -> assertThrows(IllegalArgumentException.class, () -> repository.existsByEmail(" ")),
            () -> assertThrows(IllegalArgumentException.class, () -> repository.existsByEmail("a".repeat(101))),
            () -> assertThrows(IllegalArgumentException.class,
                () -> repository.findByUsername("\uD83D\uDE00".repeat(51)))
        );
        verifyNoInteractions(dataSource, connection);
    }

    private void stubUser(long id, String username, boolean active, Long creator) throws SQLException {
        when(rows.getLong("id")).thenReturn(id);
        when(rows.getString("username")).thenReturn(username);
        when(rows.getString("password_hash")).thenReturn("stored-hash");
        when(rows.getString("full_name")).thenReturn("Nguyễn Văn Việt");
        when(rows.getString("email")).thenReturn("User@Example.com");
        when(rows.getString("role")).thenReturn("STUDENT");
        when(rows.getBoolean("active")).thenReturn(active);
        when(rows.getObject("created_by", Long.class)).thenReturn(creator);
        when(rows.getObject("created_at", OffsetDateTime.class)).thenReturn(CREATED_AT);
    }

    private User newUser() {
        return User.createNew("Student", "stored-hash", "Full Name", null, Role.STUDENT, null);
    }

    private PSQLException postgresError(String state, String constraint) {
        return new PSQLException(new ServerErrorMessage("SERROR\0C" + state + "\0n" + constraint
            + "\0Mprivate database details\0\0"));
    }

    private void assertDatabaseError(QuizException error, SQLException cause) {
        assertEquals(ErrorCode.DATABASE_ERROR, error.getErrorCode());
        assertSame(cause, error.getCause());
        assertEquals("Database operation failed", error.getMessage());
    }

    private void verifyClosedResources(boolean ownedConnection) throws SQLException {
        verify(rows).close();
        verify(statement).close();
        if (ownedConnection) {
            verify(connection).close();
        } else {
            verify(connection, never()).close();
        }
    }

    private void verifyTransactionUntouched() throws SQLException {
        verify(connection, never()).commit();
        verify(connection, never()).rollback();
        verify(connection, never()).setAutoCommit(anyBoolean());
    }
}
