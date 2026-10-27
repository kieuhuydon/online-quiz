package quiz.server.dispatch;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import quiz.common.enums.Role;
import quiz.common.protocol.MessageType;
import quiz.common.protocol.Request;
import quiz.server.dispatch.handlers.AccountHandler;
import quiz.server.dispatch.handlers.LoginHandler;
import quiz.server.exception.ForbiddenException;
import quiz.server.exception.UnauthorizedException;
import quiz.server.network.UserSession;
import quiz.server.service.AccountService;
import quiz.server.service.AuthService;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthGuardTest {
    @ParameterizedTest
    @EnumSource(value = MessageType.class, names = {"CREATE_CLASS", "ADD_CLASS_MEMBER", "REMOVE_CLASS_MEMBER"})
    void classMutationsCannotBeRegisteredForTeacherOrStudent(MessageType type) {
        assertDoesNotThrow(() -> AuthGuard.validatePolicy(type, Set.of(Role.ADMIN)));
        assertThrows(IllegalArgumentException.class,
            () -> AuthGuard.validatePolicy(type, Set.of(Role.TEACHER, Role.ADMIN)));
        assertThrows(IllegalArgumentException.class,
            () -> AuthGuard.validatePolicy(type, Set.of(Role.STUDENT)));
        CommandHandler handler = mock(CommandHandler.class);
        when(handler.allowedRoles(type)).thenReturn(Set.of(Role.ADMIN));
        when(auth.authenticate("token")).thenReturn(new UserSession(7, Role.TEACHER, "Teacher"));
        assertThrows(ForbiddenException.class, () -> guard.check(new Request(1, type, "token", null), handler));
        UserSession admin = new UserSession(8, Role.ADMIN, "Admin");
        when(auth.authenticate("token")).thenReturn(admin);
        assertEquals(admin, guard.check(new Request(2, type, "token", null), handler));
    }
    private AuthService auth;
    private AuthGuard guard;

    @BeforeEach
    void setUp() {
        auth = mock(AuthService.class);
        guard = new AuthGuard(auth);
    }

    @Test
    void loginIsTheOnlyPublicCommand() {
        assertNull(guard.check(new Request(1, MessageType.LOGIN, null, null), new LoginHandler(auth)));
        verifyNoInteractions(auth);
        assertThrows(IllegalArgumentException.class, () -> AuthGuard.validatePolicy(MessageType.LOGOUT, Set.of()));
        assertThrows(IllegalArgumentException.class, () -> AuthGuard.validatePolicy(MessageType.LOGIN, Set.of(Role.ADMIN)));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\u2003"})
    void rejectsMissingTokensBeforeAuthenticating(String token) {
        assertThrows(UnauthorizedException.class,
            () -> guard.check(new Request(1, MessageType.LOGOUT, token, null), new LoginHandler(auth)));
        verifyNoInteractions(auth);
    }

    @ParameterizedTest
    @ValueSource(strings = {"fake", "revoked", "inactive"})
    void rejectsTokensRejectedByTheService(String token) {
        when(auth.authenticate(token)).thenThrow(new UnauthorizedException("Session is not valid"));
        assertThrows(UnauthorizedException.class,
            () -> guard.check(new Request(1, MessageType.LOGOUT, token, null), new LoginHandler(auth)));
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void logoutAcceptsEveryAuthenticatedRole(Role role) {
        UserSession identity = new UserSession(7, role, "CurrentName");
        when(auth.authenticate("token")).thenReturn(identity);
        assertEquals(identity,
            guard.check(new Request(1, MessageType.LOGOUT, "token", null), new LoginHandler(auth)));
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"STUDENT", "TEACHER"})
    void accountCommandsRejectCurrentNonAdminRole(Role role) {
        when(auth.authenticate("token")).thenReturn(new UserSession(7, role, "User7"));
        assertThrows(ForbiddenException.class, () -> guard.check(
            new Request(1, MessageType.LIST_ACCOUNTS, "token", null), new AccountHandler(mock(AccountService.class))));
    }

    @Test
    void accountCommandsAcceptCurrentAdminIdentity() {
        UserSession identity = new UserSession(7, Role.ADMIN, "User7");
        when(auth.authenticate("token")).thenReturn(identity);
        assertEquals(identity, guard.check(new Request(1, MessageType.LIST_ACCOUNTS, "token", null),
            new AccountHandler(mock(AccountService.class))));
    }
}
