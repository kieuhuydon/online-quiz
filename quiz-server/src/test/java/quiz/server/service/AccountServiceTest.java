package quiz.server.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import quiz.common.dto.CreateAccountDto;
import quiz.common.enums.Role;
import quiz.common.protocol.ErrorCode;
import quiz.server.db.TransactionManager;
import quiz.server.domain.User;
import quiz.server.exception.*;
import quiz.server.network.SessionRegistry;
import quiz.server.network.UserSession;
import quiz.server.repository.UserRepository;
import quiz.server.security.PasswordHasher;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AccountServiceTest {
    private UserRepository users;
    private SessionRegistry registry;
    private Connection connection;
    private AccountService accounts;

    @BeforeEach
    void setUp() throws SQLException {
        users = mock(UserRepository.class);
        registry = new SessionRegistry();
        connection = mock(Connection.class);
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        User admin = ServiceTestUsers.user(1, Role.ADMIN, true);
        when(users.findById(1)).thenReturn(Optional.of(admin));
        when(users.findById(connection, 1)).thenReturn(Optional.of(admin));
        when(users.findById(connection, 2)).thenReturn(Optional.of(ServiceTestUsers.user(2, Role.STUDENT, true)));
        when(users.updateActiveStatus(eq(connection), eq(2L), anyBoolean())).thenReturn(true);
        when(users.deleteById(eq(connection), eq(2L), eq(1L))).thenReturn(true);
        when(users.save(eq(connection), any(User.class))).thenAnswer(invocation -> {
            User input = invocation.getArgument(1);
            return User.restore(3, input.username(), input.passwordHash(), input.fullName(), input.email(),
                input.role(), input.active(), input.createdBy(), Instant.parse("2026-10-10T00:00:00Z"));
        });
        accounts = new AccountService(users, new TransactionManager(dataSource), new SessionCoordinator(registry));
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"TEACHER", "STUDENT"})
    void createsAllowedRoleWithActorCreatorAndHashedUntrimmedPassword(Role role) throws SQLException {
        String password = " password123 ";
        User result = accounts.createAccount(new CreateAccountDto("  NewUser ", password,
            "  Nguyễn Văn Việt  ", "  User@Example.com ", role), 1);

        assertEquals("NewUser", result.username());
        assertEquals("Nguyễn Văn Việt", result.fullName());
        assertEquals("User@Example.com", result.email());
        assertEquals(role, result.role());
        assertEquals(1L, result.createdBy());
        assertTrue(result.active());
        assertTrue(PasswordHasher.checkPassword(password, result.passwordHash()));
        assertFalse(PasswordHasher.checkPassword(password.strip(), result.passwordHash()));
        verify(users).findById(connection, 1);
        verify(users, never()).save(any(User.class));
        verify(connection).commit();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\u2003"})
    void optionalEmailNormalizesToNull(String email) {
        User saved = accounts.createAccount(dto(ServiceTestUsers.PASSWORD, email, Role.STUDENT), 1);
        assertNull(saved.email());
        verify(users, never()).existsByEmail(any());
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"STUDENT", "TEACHER"})
    void everyAccountOperationRejectsNonAdminEvenWithoutGuard(Role role) {
        when(users.findById(1)).thenReturn(Optional.of(ServiceTestUsers.user(1, role, true)));
        assertThrows(ForbiddenException.class, () -> accounts.createAccount(dto(), 1));
        assertThrows(ForbiddenException.class, () -> accounts.listAccounts(1));
        assertThrows(ForbiddenException.class, () -> accounts.setAccountActive(2, false, 1));
        verify(users, never()).save(any(Connection.class), any(User.class));
        verify(users, never()).findAll();
        verify(users, never()).updateActiveStatus(any(Connection.class), anyLong(), anyBoolean());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void missingOrInactiveActorCannotOperate(boolean exists) {
        when(users.findById(1)).thenReturn(exists
            ? Optional.of(ServiceTestUsers.user(1, Role.ADMIN, false)) : Optional.empty());
        assertThrows(UnauthorizedException.class, () -> accounts.createAccount(dto(), 1));
        assertThrows(UnauthorizedException.class, () -> accounts.listAccounts(1));
        assertThrows(UnauthorizedException.class, () -> accounts.setAccountActive(2, false, 1));
    }

    @Test
    void rechecksActorInsideWriteTransaction() throws SQLException {
        when(users.findById(connection, 1)).thenReturn(Optional.of(ServiceTestUsers.user(1, Role.STUDENT, true)));
        assertThrows(ForbiddenException.class, () -> accounts.createAccount(dto(), 1));
        assertThrows(ForbiddenException.class, () -> accounts.setAccountActive(2, false, 1));
        verify(users, never()).save(any(Connection.class), any(User.class));
        verify(connection, times(2)).rollback();
    }

    @Test
    void refusesNullDataNullRoleAndCreatingOrChangingAdmin() {
        assertThrows(ValidationException.class, () -> accounts.createAccount(null, 1));
        assertThrows(ValidationException.class, () -> accounts.createAccount(dto(ServiceTestUsers.PASSWORD, null, null), 1));
        assertThrows(ForbiddenException.class, () -> accounts.createAccount(dto(ServiceTestUsers.PASSWORD, null, Role.ADMIN), 1));
        assertThrows(ForbiddenException.class, () -> accounts.setAccountActive(1, false, 1));
        assertThrows(ForbiddenException.class, () -> accounts.setAccountActive(1, true, 1));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "short", "😀😀😀😀"})
    void newPasswordsRequireEightCodePointsAndNonblankContent(String password) {
        assertThrows(ValidationException.class, () -> accounts.createAccount(dto(password, null, Role.STUDENT), 1));
        verify(users, never()).save(any(Connection.class), any(User.class));
    }

    @Test
    void passwordLimitsUseUtf8BytesWithBoundaryAccepted() {
        assertThrows(ValidationException.class,
            () -> accounts.createAccount(dto("a".repeat(73), null, Role.STUDENT), 1));
        assertThrows(ValidationException.class,
            () -> accounts.createAccount(dto("ế".repeat(25), null, Role.STUDENT), 1));
        User saved = accounts.createAccount(dto("ế".repeat(24), null, Role.STUDENT), 1);
        assertTrue(PasswordHasher.checkPassword("ế".repeat(24), saved.passwordHash()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid", "a@b", "a b@c.com", "a@b@c.com", "a\u2003b@c.com"})
    void rejectsInvalidEmail(String email) {
        assertThrows(ValidationException.class,
            () -> accounts.createAccount(dto(ServiceTestUsers.PASSWORD, email, Role.STUDENT), 1));
    }

    @Test
    void validatesRequiredNamesLengthsAndIds() {
        assertThrows(ValidationException.class, () -> accounts.createAccount(
            new CreateAccountDto(null, ServiceTestUsers.PASSWORD, "Name", null, Role.STUDENT), 1));
        assertThrows(ValidationException.class, () -> accounts.createAccount(
            new CreateAccountDto("user", ServiceTestUsers.PASSWORD, " ", null, Role.STUDENT), 1));
        assertThrows(ValidationException.class, () -> accounts.createAccount(
            new CreateAccountDto("😀".repeat(51), ServiceTestUsers.PASSWORD, "Name", null, Role.STUDENT), 1));
        assertThrows(ValidationException.class, () -> accounts.createAccount(
            new CreateAccountDto("user", ServiceTestUsers.PASSWORD, "😀".repeat(101), null, Role.STUDENT), 1));
        assertThrows(ValidationException.class, () -> accounts.createAccount(dto(), 0));
        assertThrows(ValidationException.class, () -> accounts.listAccounts(-1));
        assertThrows(ValidationException.class, () -> accounts.setAccountActive(0, false, 1));
    }

    @Test
    void reportsDuplicatePrechecksAndDatabaseRaceAsConflict() throws SQLException {
        when(users.existsByUsername("newuser")).thenReturn(true);
        assertEquals(quiz.common.protocol.ErrorCode.USERNAME_ALREADY_EXISTS,
            assertThrows(ConflictException.class, () -> accounts.createAccount(dto(), 1)).getErrorCode());
        when(users.existsByUsername("newuser")).thenReturn(false);
        when(users.existsByEmail("user@example.com")).thenReturn(true);
        assertEquals(quiz.common.protocol.ErrorCode.EMAIL_ALREADY_EXISTS, assertThrows(ConflictException.class,
            () -> accounts.createAccount(dto(ServiceTestUsers.PASSWORD, "user@example.com", Role.STUDENT), 1)).getErrorCode());
        ConflictException concurrent = new ConflictException("Username is already in use");
        when(users.save(eq(connection), any(User.class))).thenThrow(concurrent);
        assertSame(concurrent, assertThrows(ConflictException.class, () -> accounts.createAccount(dto(), 1)));
        verify(connection).rollback();
    }

    @Test
    void listsAccountsOnlyAfterAuthorization() {
        User inactive = ServiceTestUsers.user(2, Role.STUDENT, false);
        when(users.findAll()).thenReturn(List.of(inactive));
        assertEquals(List.of(inactive), accounts.listAccounts(1));
    }

    @Test
    void disableRevokesOnlyAfterCommitAndEnableDoesNotRestoreToken() throws SQLException {
        registry.register("token", new UserSession(2, Role.STUDENT, "User2"));
        doAnswer(invocation -> {
            assertTrue(registry.isValid("token"), "Keep tokens until commit succeeds");
            return null;
        }).when(connection).commit();

        accounts.setAccountActive(2, false, 1);
        assertFalse(registry.isValid("token"));
        doNothing().when(connection).commit();
        accounts.setAccountActive(2, true, 1);
        assertFalse(registry.isValid("token"));
        verify(users).updateActiveStatus(connection, 2, false);
        verify(users).updateActiveStatus(connection, 2, true);
    }

    @Test
    void commitFailureRollsBackAndKeepsExistingSession() throws SQLException {
        registry.register("token", new UserSession(2, Role.STUDENT, "User2"));
        doThrow(new SQLException("commit failed")).when(connection).commit();
        assertEquals(ErrorCode.DATABASE_ERROR, assertThrows(QuizException.class,
            () -> accounts.setAccountActive(2, false, 1)).getErrorCode());
        assertTrue(registry.isValid("token"));
        verify(connection).rollback();
    }

    @Test
    void missingTargetOrDisappearingUpdateKeepsSessionAndReportsNotFound() {
        registry.register("token", new UserSession(2, Role.STUDENT, "User2"));
        when(users.findById(connection, 2)).thenReturn(Optional.empty());
        assertThrows(NotFoundException.class, () -> accounts.setAccountActive(2, false, 1));
        when(users.findById(connection, 2)).thenReturn(Optional.of(ServiceTestUsers.user(2, Role.STUDENT, true)));
        when(users.updateActiveStatus(connection, 2, false)).thenReturn(false);
        assertThrows(NotFoundException.class, () -> accounts.setAccountActive(2, false, 1));
        assertTrue(registry.isValid("token"));
    }


    @Test
    void deletesAccountAndRevokesSession() throws SQLException {
        registry.register("token", new UserSession(2, Role.STUDENT, "student"));
        assertTrue(registry.isValid("token"));

        accounts.deleteAccount(2, 1);

        verify(users).deleteById(connection, 2L, 1L);
        verify(connection).commit();
        assertFalse(registry.isValid("token"));
        assertEquals(0, registry.size());
    }

    @Test
    void cannotDeleteSelfOrAdmin() {
        assertThrows(ForbiddenException.class, () -> accounts.deleteAccount(1, 1));
        when(users.findById(connection, 3)).thenReturn(Optional.of(ServiceTestUsers.user(3, Role.ADMIN, true)));
        assertThrows(ForbiddenException.class, () -> accounts.deleteAccount(3, 1));
    }

    private CreateAccountDto dto() {
        return dto(ServiceTestUsers.PASSWORD, null, Role.STUDENT);
    }

    private CreateAccountDto dto(String password, String email, Role role) {
        return new CreateAccountDto("newuser", password, "Full Name", email, role);
    }
}
