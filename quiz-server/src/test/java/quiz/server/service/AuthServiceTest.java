package quiz.server.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import quiz.common.enums.Role;
import quiz.common.protocol.ErrorCode;
import quiz.server.domain.User;
import quiz.server.exception.QuizException;
import quiz.server.exception.UnauthorizedException;
import quiz.server.exception.ValidationException;
import quiz.server.network.SessionRegistry;
import quiz.server.network.UserSession;
import quiz.server.repository.UserRepository;
import quiz.server.security.PasswordHasher;

import java.io.Serializable;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthServiceTest {
    private UserRepository users;
    private SessionRegistry registry;
    private AuthService auth;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        registry = new SessionRegistry();
        auth = new AuthService(users, new SessionCoordinator(registry));
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void logsInEveryRoleWithFreshTokenAndServerIdentity(Role role) {
        User user = ServiceTestUsers.user(2, role, true);
        stubLogin(user);

        AuthResult result = auth.login("  User2  ", ServiceTestUsers.PASSWORD);

        assertEquals(user, result.user());
        assertEquals(32, result.token().length());
        assertEquals(new UserSession(2, role, "User2"), auth.authenticate(result.token()));
        assertEquals(1, registry.size());
        assertFalse(result.toString().contains(result.token()));
        assertFalse(result.toString().contains(user.passwordHash()));
        assertFalse((Object) result instanceof Serializable);
    }

    @Test
    void unknownUserAndWrongPasswordHaveSameErrorAndMessage() {
        User user = ServiceTestUsers.user(2, Role.STUDENT, true);
        stubLogin(user);

        QuizException missing = assertThrows(QuizException.class,
            () -> auth.login("missing", ServiceTestUsers.PASSWORD));
        QuizException incorrect = assertThrows(QuizException.class,
            () -> auth.login(user.username(), "wrong-password"));

        assertEquals(ErrorCode.INVALID_CREDENTIALS, missing.getErrorCode());
        assertEquals(missing.getErrorCode(), incorrect.getErrorCode());
        assertEquals(missing.getMessage(), incorrect.getMessage());
        assertEquals(0, registry.size());
    }

    @Test
    void reportsDisabledOnlyAfterCorrectCredentials() {
        User user = ServiceTestUsers.user(2, Role.STUDENT, false);
        stubLogin(user);

        assertEquals(ErrorCode.INVALID_CREDENTIALS, assertThrows(QuizException.class,
            () -> auth.login(user.username(), "wrong-password")).getErrorCode());
        assertEquals(ErrorCode.ACCOUNT_DISABLED, assertThrows(QuizException.class,
            () -> auth.login(user.username(), ServiceTestUsers.PASSWORD)).getErrorCode());
        assertEquals(0, registry.size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"corrupt-hash", "$2a$10$short", "$"})
    void corruptHashRejectsLoginWithoutRegisteringSession(String hash) {
        User user = ServiceTestUsers.user(2, Role.STUDENT, true, hash);
        stubLogin(user);

        assertEquals(ErrorCode.INVALID_CREDENTIALS, assertThrows(QuizException.class,
            () -> auth.login(user.username(), ServiceTestUsers.PASSWORD)).getErrorCode());
        assertEquals(0, registry.size());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\u2003"})
    void rejectsMissingUsernameBeforeReadingDatabase(String username) {
        assertThrows(ValidationException.class, () -> auth.login(username, ServiceTestUsers.PASSWORD));
        verifyNoInteractions(users);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void rejectsMissingPasswordBeforeReadingDatabase(String password) {
        assertThrows(ValidationException.class, () -> auth.login("User2", password));
        verifyNoInteractions(users);
    }

    @Test
    void rejectsLongUsernameAndPasswordsByUtf8Bytes() {
        assertThrows(ValidationException.class, () -> auth.login("😀".repeat(51), ServiceTestUsers.PASSWORD));
        assertThrows(ValidationException.class, () -> auth.login("User2", "a".repeat(73)));
        assertThrows(ValidationException.class, () -> auth.login("User2", "ế".repeat(25)));
        verifyNoInteractions(users);
    }

    @ParameterizedTest
    @ValueSource(strings = {"short", " password123 ", "ếếếếếếếếếếếếếếếếếếếếếếếế"})
    void loginPreservesWhitespaceAndAcceptsLegacyShortOr72BytePasswords(String password) {
        User user = ServiceTestUsers.user(2, Role.STUDENT, true, PasswordHasher.hashPassword(password));
        stubLogin(user);
        assertNotNull(auth.login(user.username(), password));
        if (!password.equals(password.strip())) {
            assertEquals(ErrorCode.INVALID_CREDENTIALS, assertThrows(QuizException.class,
                () -> auth.login(user.username(), password.strip())).getErrorCode());
        }
    }

    @Test
    void laterLoginReplacesOldTokenAndOldCleanupDoesNotRemoveNewToken() {
        User user = ServiceTestUsers.user(2, Role.STUDENT, true);
        stubLogin(user);
        String oldToken = auth.login(user.username(), ServiceTestUsers.PASSWORD).token();
        String newToken = auth.login(user.username(), ServiceTestUsers.PASSWORD).token();

        assertNotEquals(oldToken, newToken);
        assertThrows(UnauthorizedException.class, () -> auth.authenticate(oldToken));
        auth.logout(oldToken);
        assertTrue(registry.isValid(newToken));
        assertEquals(1, registry.size());
        auth.logout(newToken);
        auth.logout(newToken);
        auth.logout(null);
        assertEquals(0, registry.size());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "unknown"})
    void authenticateRejectsMissingTokensWithoutDatabaseLookup(String token) {
        assertThrows(UnauthorizedException.class, () -> auth.authenticate(token));
        verifyNoInteractions(users);
    }

    @Test
    void authenticationUsesCurrentDbRoleRatherThanCachedSessionRole() {
        registry.register("token", new UserSession(2, Role.ADMIN, "OldName"));
        when(users.findById(2)).thenReturn(Optional.of(ServiceTestUsers.user(2, Role.TEACHER, true)));

        assertEquals(new UserSession(2, Role.TEACHER, "User2"), auth.authenticate("token"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void missingOrDisabledUserInvalidatesExistingSession(boolean exists) {
        registry.register("token", new UserSession(2, Role.STUDENT, "User2"));
        when(users.findById(2)).thenReturn(exists
            ? Optional.of(ServiceTestUsers.user(2, Role.STUDENT, false)) : Optional.empty());

        assertThrows(UnauthorizedException.class, () -> auth.authenticate("token"));
        assertEquals(0, registry.size());
    }

    @Test
    void accountChangedBetweenPasswordVerificationAndPublicationCannotLogin() {
        User original = ServiceTestUsers.user(2, Role.STUDENT, true);
        when(users.findByUsername("User2")).thenReturn(Optional.of(original));
        when(users.findById(2)).thenReturn(Optional.empty());
        assertEquals(ErrorCode.INVALID_CREDENTIALS, assertThrows(QuizException.class,
            () -> auth.login("User2", ServiceTestUsers.PASSWORD)).getErrorCode());
        when(users.findById(2)).thenReturn(Optional.of(
            ServiceTestUsers.user(2, Role.STUDENT, true, PasswordHasher.hashPassword("changed-password"))));
        assertEquals(ErrorCode.INVALID_CREDENTIALS, assertThrows(QuizException.class,
            () -> auth.login("User2", ServiceTestUsers.PASSWORD)).getErrorCode());
        assertEquals(0, registry.size());
    }

    @Test
    void transientDatabaseFailurePreservesSessionAndOriginalError() {
        registry.register("token", new UserSession(2, Role.STUDENT, "User2"));
        QuizException failure = new QuizException(ErrorCode.DATABASE_ERROR, "Database operation failed");
        when(users.findById(2)).thenThrow(failure);

        assertSame(failure, assertThrows(QuizException.class, () -> auth.authenticate("token")));
        assertTrue(registry.isValid("token"));
    }

    private void stubLogin(User user) {
        when(users.findByUsername(user.username())).thenReturn(Optional.of(user));
        when(users.findById(user.id())).thenReturn(Optional.of(user));
    }
}
