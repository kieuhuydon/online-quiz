package quiz.server.dispatch.handlers;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import quiz.common.dto.LoginRequestDto;
import quiz.common.dto.LoginResultDto;
import quiz.common.enums.Role;
import quiz.common.protocol.ErrorCode;
import quiz.common.protocol.MessageType;
import quiz.common.protocol.Request;
import quiz.server.dispatch.DispatchTestUsers;
import quiz.server.exception.ConflictException;
import quiz.server.exception.QuizException;
import quiz.server.network.UserSession;
import quiz.server.service.AuthResult;
import quiz.server.service.AuthService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LoginHandlerTest {
    @Test void heartbeatRequiresIdentityAndEmptyPayload() {
        var auth = mock(AuthService.class);
        var handler = new LoginHandler(auth);
        var identity = new UserSession(1, Role.STUDENT, "student");
        assertTrue(handler.allowedRoles(MessageType.HEARTBEAT).containsAll(java.util.Set.of(Role.values())));
        assertTrue(handler.handle(new Request(1, MessageType.HEARTBEAT, "token", null), identity).success());
        assertEquals(ErrorCode.UNAUTHENTICATED, assertThrows(QuizException.class,
            () -> handler.handle(new Request(2, MessageType.HEARTBEAT, "token", null), null)).getErrorCode());
        assertEquals(ErrorCode.INVALID_REQUEST, assertThrows(QuizException.class,
            () -> handler.handle(new Request(3, MessageType.HEARTBEAT, "token", "extra"), identity)).getErrorCode());
        verifyNoInteractions(auth);
    }
    private AuthService auth;
    private LoginHandler handler;
    private final UserSession session = new UserSession(7, Role.ADMIN, "User7");

    @BeforeEach
    void setUp() {
        auth = mock(AuthService.class);
        handler = new LoginHandler(auth);
    }

    @Test
    void loginMapsResultAndLogoutCallsServiceWithExactToken() {
        when(auth.login("User7", "secret")).thenReturn(new AuthResult("issued", DispatchTestUsers.user(7, Role.ADMIN)));
        var login = handler.handle(new Request(42, MessageType.LOGIN, null, new LoginRequestDto("User7", "secret")), null);
        assertTrue(login.success());
        assertEquals(42, login.requestId());
        assertEquals("issued", ((LoginResultDto) login.payload()).token());
        var logout = handler.handle(new Request(43, MessageType.LOGOUT, "issued", null), session);
        assertTrue(logout.success());
        assertNull(logout.payload());
        assertNull(logout.pushType());
        verify(auth).logout("issued");
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "wrong"})
    void loginRejectsMissingOrWrongPayload(String kind) {
        assertEquals(ErrorCode.INVALID_REQUEST, assertThrows(QuizException.class,
            () -> handler.handle(new Request(42, MessageType.LOGIN, null, "null".equals(kind) ? null : "wrong"), null))
            .getErrorCode());
        verifyNoInteractions(auth);
    }

    @Test
    void nullFieldsArePassedToServiceForBusinessValidation() {
        when(auth.login(null, null)).thenThrow(new quiz.server.exception.ValidationException("Username is required"));
        assertEquals(ErrorCode.VALIDATION_ERROR, assertThrows(QuizException.class,
            () -> handler.handle(new Request(42, MessageType.LOGIN, null, new LoginRequestDto(null, null)), null))
            .getErrorCode());
    }

    @Test
    void logoutRejectsPayloadAndMissingSessionWithoutCallingService() {
        assertEquals(ErrorCode.INVALID_REQUEST, assertThrows(QuizException.class,
            () -> handler.handle(new Request(42, MessageType.LOGOUT, "token", "extra"), session)).getErrorCode());
        assertEquals(ErrorCode.UNAUTHENTICATED, assertThrows(QuizException.class,
            () -> handler.handle(new Request(42, MessageType.LOGOUT, "token", null), null)).getErrorCode());
        verifyNoInteractions(auth);
    }

    @Test
    void directLoginWithSessionOrTokenIsRejectedAndUnsupportedTypeIsInvalid() {
        Request login = new Request(42, MessageType.LOGIN, null, new LoginRequestDto("User7", "secret"));
        assertThrows(ConflictException.class, () -> handler.handle(login, session));
        assertEquals(ErrorCode.INVALID_REQUEST, assertThrows(QuizException.class,
            () -> handler.handle(new Request(42, MessageType.LOGIN, "token", login.payload()), null)).getErrorCode());
        assertEquals(ErrorCode.INVALID_REQUEST, assertThrows(QuizException.class,
            () -> handler.handle(new Request(42, MessageType.CREATE_QUIZ, "token", null), session)).getErrorCode());
        assertThrows(IllegalArgumentException.class, () -> handler.allowedRoles(MessageType.CREATE_QUIZ));
        verifyNoInteractions(auth);
    }
}
