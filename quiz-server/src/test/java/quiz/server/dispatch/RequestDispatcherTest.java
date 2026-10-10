package quiz.server.dispatch;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import quiz.common.dto.CreateAccountDto;
import quiz.common.dto.LoginRequestDto;
import quiz.common.dto.SetAccountActiveDto;
import quiz.common.enums.Role;
import quiz.common.protocol.*;
import quiz.server.dispatch.handlers.AccountHandler;
import quiz.server.dispatch.handlers.LoginHandler;
import quiz.server.exception.QuizException;
import quiz.server.network.UserSession;
import quiz.server.service.AccountService;
import quiz.server.service.AuthResult;
import quiz.server.service.AuthService;

import java.io.File;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RequestDispatcherTest {
    private AuthService auth;
    private AccountService accounts;
    private AuthGuard guard;
    private RequestDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        auth = mock(AuthService.class);
        accounts = mock(AccountService.class);
        guard = new AuthGuard(auth);
        when(auth.authenticate("admin")).thenReturn(new UserSession(7, Role.ADMIN, "User7"));
        dispatcher = new RequestDispatcher(guard, List.of(new LoginHandler(auth), new AccountHandler(accounts)));
    }

    @Test
    void routesAllFiveCommandsAndPreservesCorrelationAndWireEnvelope() throws Exception {
        when(auth.login("User7", "secret")).thenReturn(new AuthResult("issued", DispatchTestUsers.user(7, Role.ADMIN)));
        CreateAccountDto dto = new CreateAccountDto("student", "secret", "Name", null, Role.STUDENT);
        when(accounts.createAccount(dto, 7)).thenReturn(DispatchTestUsers.user(8, Role.STUDENT));
        when(accounts.listAccounts(7)).thenReturn(List.of(DispatchTestUsers.user(8, Role.STUDENT)));
        List<Request> requests = List.of(
            new Request(1, MessageType.LOGIN, null, new LoginRequestDto("User7", "secret")),
            new Request(2, MessageType.CREATE_ACCOUNT, "admin", dto),
            new Request(3, MessageType.LIST_ACCOUNTS, "admin", null),
            new Request(4, MessageType.SET_ACCOUNT_ACTIVE, "admin", new SetAccountActiveDto(8, false)),
            new Request(5, MessageType.LOGOUT, "admin", null));
        for (Request request : requests) {
            Response response = dispatcher.dispatch(request);
            assertTrue(response.success());
            assertEquals(request.requestId(), response.requestId());
            assertNull(response.errorCode());
            assertNull(response.pushType());
            assertEquals(response, MessageCodec.deserialize(MessageCodec.serialize(response)));
        }
        verify(accounts).createAccount(dto, 7);
        verify(accounts).setAccountActive(8, false, 7);
        verify(auth).logout("admin");
    }

    @Test
    void rejectsBadEnvelopeAndLoginTokenWithoutAuthenticating() {
        assertError(dispatcher.dispatch(null), 0, ErrorCode.INVALID_REQUEST);
        assertError(dispatcher.dispatch(new Request(0, MessageType.LOGIN, null, null)), 0, ErrorCode.INVALID_REQUEST);
        assertError(dispatcher.dispatch(new Request(-1, MessageType.LOGIN, null, null)), -1, ErrorCode.INVALID_REQUEST);
        assertError(dispatcher.dispatch(new Request(42, null, null, null)), 42, ErrorCode.INVALID_REQUEST);
        assertError(dispatcher.dispatch(new Request(42, MessageType.LOGIN, "token", null)), 42, ErrorCode.INVALID_REQUEST);
        verifyNoInteractions(auth, accounts);
    }

    @ParameterizedTest
    @EnumSource(value = MessageType.class, names = {"CREATE_QUIZ", "CREATE_QUESTION", "PUSH_FORCE_SUBMITTED", "PUSH_ANTICHEAT_WARNING"})
    void unregisteredCommandsAndClientPushAreInvalid(MessageType type) {
        assertError(dispatcher.dispatch(new Request(42, type, "admin", null)), 42, ErrorCode.INVALID_REQUEST);
        verifyNoInteractions(auth, accounts);
    }

    @Test
    void unauthorizedAndWrongRoleCannotReachAccountHandler() {
        assertError(dispatcher.dispatch(new Request(42, MessageType.LIST_ACCOUNTS, null, null)), 42, ErrorCode.UNAUTHENTICATED);
        when(auth.authenticate("student")).thenReturn(new UserSession(8, Role.STUDENT, "User8"));
        assertError(dispatcher.dispatch(new Request(43, MessageType.LIST_ACCOUNTS, "student", null)), 43, ErrorCode.FORBIDDEN);
        verifyNoInteractions(accounts);
    }

    @Test
    void payloadShapeIsCheckedBeforeServiceAndBusinessValuesUseValidationCode() {
        assertError(dispatcher.dispatch(new Request(42, MessageType.CREATE_ACCOUNT, "admin", "wrong")), 42, ErrorCode.INVALID_REQUEST);
        CreateAccountDto dto = new CreateAccountDto(null, null, null, null, null);
        when(accounts.createAccount(dto, 7)).thenThrow(new quiz.server.exception.ValidationException("Account fields are invalid"));
        assertError(dispatcher.dispatch(new Request(43, MessageType.CREATE_ACCOUNT, "admin", dto)), 43, ErrorCode.VALIDATION_ERROR);
    }

    @ParameterizedTest
    @EnumSource(ErrorCode.class)
    void mapsBusinessErrorCodesButSanitizesDatabaseAndInternalMessages(ErrorCode code) {
        RequestDispatcher custom = custom((request, session) -> { throw new QuizException(code, "business-message"); });
        Response response = custom.dispatch(login());
        assertError(response, 42, code);
        String expected = switch (code) {
            case DATABASE_ERROR -> "Database operation failed";
            case INTERNAL_ERROR -> "Internal server error";
            default -> "business-message";
        };
        assertEquals(expected, response.message());
    }

    @Test
    void unexpectedFailureDoesNotBreakSubsequentDispatch() {
        when(accounts.listAccounts(7)).thenThrow(new IllegalStateException("private details"))
            .thenReturn(List.of());
        Request request = new Request(42, MessageType.LIST_ACCOUNTS, "admin", null);
        assertError(dispatcher.dispatch(request), 42, ErrorCode.INTERNAL_ERROR);
        assertTrue(dispatcher.dispatch(request).success());
    }

    @Test
    void sqlCausesAndLogsNeverExposeTokenPayloadPasswordHashOrExceptionMessages() {
        String secret = "private-token-password-details";
        when(auth.authenticate(secret)).thenReturn(new UserSession(7, Role.ADMIN, "User7"));
        CommandHandler command = handler(MessageType.LIST_ACCOUNTS, Set.of(Role.ADMIN), (request, session) -> {
            throw new IllegalStateException(secret, new SQLException(secret + DispatchTestUsers.HASH));
        });
        Logger logger = (Logger) LoggerFactory.getLogger(RequestDispatcher.class);
        ListAppender<ILoggingEvent> capture = new ListAppender<>();
        capture.start();
        logger.addAppender(capture);
        try {
            Request request = new Request(42, MessageType.LIST_ACCOUNTS, secret,
                new CreateAccountDto("private-user", secret, DispatchTestUsers.HASH, null, Role.ADMIN));
            Response response = new RequestDispatcher(guard, List.of(command)).dispatch(request);
            assertError(response, 42, ErrorCode.DATABASE_ERROR);
            assertEquals("Database operation failed", response.message());
            assertFalse(capture.list.isEmpty());
            for (ILoggingEvent event : capture.list) {
                assertFalse(event.getFormattedMessage().contains(secret));
                assertFalse(event.getFormattedMessage().contains(DispatchTestUsers.HASH));
                assertFalse(event.getFormattedMessage().contains("private-user"));
                assertNull(event.getThrowableProxy());
            }
        } finally {
            logger.detachAppender(capture);
            capture.stop();
        }
    }

    @Test
    void fatalVmErrorsAreNotConvertedIntoResponses() {
        RequestDispatcher custom = custom((request, session) -> { throw new InternalError("simulated fatal failure"); });
        assertThrows(InternalError.class, () -> custom.dispatch(login()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "id", "push", "successError", "failureNoError"})
    void invalidHandlerResponseBecomesInternalError(String kind) {
        RequestDispatcher custom = custom((request, session) -> switch (kind) {
            case "null" -> null;
            case "id" -> Response.success(100, null);
            case "push" -> Response.push(MessageType.PUSH_FORCE_SUBMITTED, null);
            case "successError" -> new Response(42, true, ErrorCode.CONFLICT, null, null, null);
            case "failureNoError" -> new Response(42, false, null, null, null, null);
            default -> throw new IllegalArgumentException("Unknown fixture");
        });
        assertError(custom.dispatch(login()), 42, ErrorCode.INTERNAL_ERROR);
    }

    @Test
    void returnedErrorResponsesAreAlsoSanitized() {
        RequestDispatcher custom = custom((request, session) -> Response.error(42, ErrorCode.DATABASE_ERROR, "private SQL details"));
        assertEquals("Database operation failed", custom.dispatch(login()).message());
    }

    @Test
    void oversizedBusinessErrorMessageAlsoBecomesBoundedInternalError() {
        RequestDispatcher custom = custom((request, session) -> {
            throw new QuizException(ErrorCode.VALIDATION_ERROR, "x".repeat(MessageCodec.MAX_MESSAGE_BYTES));
        });
        assertError(custom.dispatch(login()), 42, ErrorCode.INTERNAL_ERROR);
    }

    @Test
    void oversizedDisallowedAndReferenceLimitedPayloadsProduceBoundedErrors() throws Exception {
        RequestDispatcher oversized = custom((request, session) -> Response.success(42, "x".repeat(MessageCodec.MAX_MESSAGE_BYTES)));
        assertError(oversized.dispatch(login()), 42, ErrorCode.INTERNAL_ERROR);
        RequestDispatcher disallowed = custom((request, session) -> Response.success(42, new File("unused-file")));
        assertError(disallowed.dispatch(login()), 42, ErrorCode.INTERNAL_ERROR);
        var tooManyReferences = new ArrayList<>(Collections.nCopies(10_001, "entry"));
        assertTrue(MessageCodec.serialize(Response.success(42, tooManyReferences)).length < MessageCodec.MAX_MESSAGE_BYTES);
        RequestDispatcher references = custom((request, session) -> Response.success(42, tooManyReferences));
        assertError(references.dispatch(login()), 42, ErrorCode.INTERNAL_ERROR);
        when(accounts.listAccounts(7)).thenReturn(Collections.nCopies(10_001, DispatchTestUsers.user(8, Role.STUDENT)));
        assertError(dispatcher.dispatch(new Request(42, MessageType.LIST_ACCOUNTS, "admin", null)), 42, ErrorCode.INTERNAL_ERROR);
    }

    @Test
    void registryRejectsDuplicatesPublicNonLoginNullPoliciesAndPush() {
        CommandHandler login = handler(MessageType.LOGIN, Set.of(), (request, session) -> Response.success(request.requestId(), null));
        assertThrows(IllegalArgumentException.class, () -> new RequestDispatcher(guard, List.of(login, login)));
        for (CommandHandler invalid : List.of(
            handler(MessageType.LOGOUT, Set.of(), (request, session) -> null),
            handler(MessageType.LOGIN, Set.of(Role.ADMIN), (request, session) -> null),
            handler(MessageType.LOGIN, null, (request, session) -> null),
            handler(MessageType.LOGOUT, Collections.singleton(null), (request, session) -> null),
            handler(MessageType.PUSH_FORCE_SUBMITTED, Set.of(Role.ADMIN), (request, session) -> null))) {
            assertThrows(IllegalArgumentException.class, () -> new RequestDispatcher(guard, List.of(invalid)));
        }
        CommandHandler invalidTypes = mock(CommandHandler.class);
        when(invalidTypes.supportedTypes()).thenReturn(Set.of());
        assertThrows(IllegalArgumentException.class, () -> new RequestDispatcher(guard, List.of(invalidTypes)));
        when(invalidTypes.supportedTypes()).thenReturn(Collections.singleton(null));
        assertThrows(IllegalArgumentException.class, () -> new RequestDispatcher(guard, List.of(invalidTypes)));
    }

    @Test
    void registryCopiesInputCollectionInsteadOfKeepingMutableRegistrationList() {
        List<CommandHandler> input = new ArrayList<>(List.of(handler(MessageType.LOGIN, Set.of(),
            (request, session) -> Response.success(request.requestId(), null))));
        RequestDispatcher custom = new RequestDispatcher(guard, input);
        input.clear();
        assertTrue(custom.dispatch(login()).success());
    }

    private Request login() {
        return new Request(42, MessageType.LOGIN, null, new LoginRequestDto("User7", "secret"));
    }

    private RequestDispatcher custom(BiFunction<Request, UserSession, Response> operation) {
        return new RequestDispatcher(guard, List.of(handler(MessageType.LOGIN, Set.of(), operation)));
    }

    private CommandHandler handler(MessageType type, Set<Role> roles, BiFunction<Request, UserSession, Response> operation) {
        return new CommandHandler() {
            public Set<MessageType> supportedTypes() { return Set.of(type); }
            public Set<Role> allowedRoles(MessageType requested) { return roles; }
            public Response handle(Request request, UserSession session) { return operation.apply(request, session); }
        };
    }

    private void assertError(Response response, long id, ErrorCode code) {
        assertFalse(response.success());
        assertEquals(id, response.requestId());
        assertEquals(code, response.errorCode());
        assertNull(response.payload());
        assertNull(response.pushType());
    }
}
