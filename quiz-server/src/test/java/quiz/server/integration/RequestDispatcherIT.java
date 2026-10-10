package quiz.server.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import quiz.common.dto.AccountDto;
import quiz.common.dto.CreateAccountDto;
import quiz.common.dto.LoginRequestDto;
import quiz.common.dto.LoginResultDto;
import quiz.common.dto.SetAccountActiveDto;
import quiz.common.enums.Role;
import quiz.common.protocol.ErrorCode;
import quiz.common.protocol.MessageCodec;
import quiz.common.protocol.MessageType;
import quiz.common.protocol.Request;
import quiz.common.protocol.Response;
import quiz.server.db.TransactionManager;
import quiz.server.dispatch.AuthGuard;
import quiz.server.dispatch.RequestDispatcher;
import quiz.server.dispatch.handlers.AccountHandler;
import quiz.server.dispatch.handlers.LoginHandler;
import quiz.server.domain.User;
import quiz.server.network.SessionRegistry;
import quiz.server.repository.UserRepository;
import quiz.server.repository.jdbc.JdbcUserRepository;
import quiz.server.security.PasswordHasher;
import quiz.server.service.AccountService;
import quiz.server.service.AuthService;
import quiz.server.service.SessionCoordinator;

import javax.sql.DataSource;
import java.io.Serializable;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Serialized envelope -> dispatcher -> real services/PostgreSQL. Socket bootstrap is a later task. */
class RequestDispatcherIT {
    private static final String PASSWORD = "dispatcher-fixture-password";
    private final List<String> fixtureNames = new ArrayList<>();
    private DataSource dataSource;
    private UserRepository users;
    private SessionRegistry registry;
    private RequestDispatcher dispatcher;
    private User admin;
    private long requestId;

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
        SessionCoordinator coordinator = new SessionCoordinator(registry);
        AuthService auth = new AuthService(users, coordinator);
        AccountService accounts = new AccountService(users, new TransactionManager(dataSource), coordinator);
        dispatcher = new RequestDispatcher(new AuthGuard(auth), List.of(new LoginHandler(auth), new AccountHandler(accounts)));
        admin = users.save(User.createNew(nextUsername(), PasswordHasher.hashPassword(PASSWORD),
            "Dispatcher Fixture Admin", null, Role.ADMIN, null));
    }

    @AfterEach
    void cleanupOnlyOwnFixtures() throws SQLException {
        if (dataSource == null || fixtureNames.isEmpty()) {
            return;
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM users WHERE username = ?")) {
            for (int index = fixtureNames.size() - 1; index >= 0; index--) {
                statement.setString(1, fixtureNames.get(index));
                statement.executeUpdate();
            }
        }
    }

    @Test
    void adminHandlesAllFiveMessagesWithPublicDtoPayloadsAndRevokedSessions() throws Exception {
        String adminToken = login(admin.username());
        String username = nextUsername();
        Response created = exchange(MessageType.CREATE_ACCOUNT, adminToken,
            new CreateAccountDto(username, PASSWORD, "Nguyễn Văn Việt 😀", null, Role.STUDENT));
        assertTrue(created.success());
        AccountDto account = assertInstanceOf(AccountDto.class, created.payload());
        assertEquals(username, account.username());
        assertEquals(admin.id(), account.createdBy());
        assertEquals(Role.STUDENT, account.role());
        String studentToken = login(username);

        Response listed = exchange(MessageType.LIST_ACCOUNTS, adminToken, null);
        List<?> accounts = assertInstanceOf(ArrayList.class, listed.payload());
        assertTrue(accounts.contains(account));
        assertTrue(accounts.stream().allMatch(AccountDto.class::isInstance));
        assertFalse(new String(MessageCodec.serialize(listed), java.nio.charset.StandardCharsets.ISO_8859_1)
            .contains(users.findById(account.id()).orElseThrow().passwordHash()));
        assertTrue(exchange(MessageType.SET_ACCOUNT_ACTIVE, adminToken,
            new SetAccountActiveDto(account.id(), false)).success());
        assertFalse(users.findById(account.id()).orElseThrow().active());
        assertEquals(ErrorCode.UNAUTHENTICATED, exchange(MessageType.LOGOUT, studentToken, null).errorCode());
        assertEquals(ErrorCode.ACCOUNT_DISABLED, exchange(MessageType.LOGIN, null,
            new LoginRequestDto(username, PASSWORD)).errorCode());
        assertTrue(exchange(MessageType.LOGOUT, adminToken, null).success());
        assertEquals(0, registry.size());
        assertEquals(ErrorCode.UNAUTHENTICATED, exchange(MessageType.LIST_ACCOUNTS, adminToken, null).errorCode());
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"STUDENT", "TEACHER"})
    void nonAdminCannotCreateListOrChangeAccountsEvenWithAdminRoleInPayload(Role role) throws Exception {
        String adminToken = login(admin.username());
        String username = nextUsername();
        AccountDto account = (AccountDto) exchange(MessageType.CREATE_ACCOUNT, adminToken,
            new CreateAccountDto(username, PASSWORD, "Role Fixture", null, role)).payload();
        String token = login(username);
        String forbiddenName = nextUsername();
        assertEquals(ErrorCode.FORBIDDEN, exchange(MessageType.CREATE_ACCOUNT, token,
            new CreateAccountDto(forbiddenName, PASSWORD, "Forbidden Fixture", null, Role.ADMIN)).errorCode());
        assertEquals(ErrorCode.FORBIDDEN, exchange(MessageType.LIST_ACCOUNTS, token, null).errorCode());
        assertEquals(ErrorCode.FORBIDDEN, exchange(MessageType.SET_ACCOUNT_ACTIVE, token,
            new SetAccountActiveDto(account.id(), false)).errorCode());
        assertTrue(users.findByUsername(forbiddenName).isEmpty());
        assertTrue(users.findById(account.id()).orElseThrow().active());
    }

    @Test
    void malformedPayloadBusinessErrorsAndDuplicateDoNotBreakNextRequest() throws Exception {
        String token = login(admin.username());
        assertEquals(ErrorCode.INVALID_REQUEST, exchange(MessageType.CREATE_ACCOUNT, token, "wrong type").errorCode());
        assertEquals(ErrorCode.INVALID_REQUEST, exchange(MessageType.LIST_ACCOUNTS, token, "unexpected payload").errorCode());
        assertEquals(ErrorCode.VALIDATION_ERROR, exchange(MessageType.CREATE_ACCOUNT, token,
            new CreateAccountDto(null, null, null, null, null)).errorCode());
        assertEquals(ErrorCode.FORBIDDEN, exchange(MessageType.CREATE_ACCOUNT, token,
            new CreateAccountDto(nextUsername(), PASSWORD, "Admin Fixture", null, Role.ADMIN)).errorCode());
        assertEquals(ErrorCode.NOT_FOUND, exchange(MessageType.SET_ACCOUNT_ACTIVE, token,
            new SetAccountActiveDto(Long.MAX_VALUE, false)).errorCode());
        CreateAccountDto dto = new CreateAccountDto(nextUsername(), PASSWORD, "Duplicate Fixture", null, Role.STUDENT);
        assertTrue(exchange(MessageType.CREATE_ACCOUNT, token, dto).success());
        assertEquals(ErrorCode.CONFLICT, exchange(MessageType.CREATE_ACCOUNT, token, dto).errorCode());
        Response wrongPassword = exchange(MessageType.LOGIN, null, new LoginRequestDto(admin.username(), "incorrect"));
        Response missingUser = exchange(MessageType.LOGIN, null, new LoginRequestDto(nextUsername(), PASSWORD));
        assertEquals(ErrorCode.INVALID_CREDENTIALS, wrongPassword.errorCode());
        assertEquals(wrongPassword.errorCode(), missingUser.errorCode());
        assertEquals(wrongPassword.message(), missingUser.message());
        assertTrue(exchange(MessageType.LIST_ACCOUNTS, token, null).success());
    }

    @Test
    void replacedLoginTokenCannotAuthorizeOrLogOutTheNewSession() throws Exception {
        String oldToken = login(admin.username());
        String currentToken = login(admin.username());
        assertNotEquals(oldToken, currentToken);
        assertEquals(ErrorCode.UNAUTHENTICATED, exchange(MessageType.LIST_ACCOUNTS, oldToken, null).errorCode());
        assertEquals(ErrorCode.UNAUTHENTICATED, exchange(MessageType.LOGOUT, oldToken, null).errorCode());
        assertTrue(exchange(MessageType.LIST_ACCOUNTS, currentToken, null).success());
        assertEquals(1, registry.size());
        assertTrue(exchange(MessageType.LOGOUT, currentToken, null).success());
        assertEquals(0, registry.size());
    }

    private String login(String username) throws Exception {
        Response response = exchange(MessageType.LOGIN, null, new LoginRequestDto(username, PASSWORD));
        assertTrue(response.success());
        LoginResultDto result = assertInstanceOf(LoginResultDto.class, response.payload());
        assertEquals(username, result.username());
        return result.token();
    }

    private Response exchange(MessageType type, String token, Serializable payload) throws Exception {
        Request request = new Request(++requestId, type, token, payload);
        Request decoded = (Request) MessageCodec.deserialize(MessageCodec.serialize(request));
        Response response = dispatcher.dispatch(decoded);
        assertEquals(request.requestId(), response.requestId());
        assertNull(response.pushType());
        if (response.success()) {
            assertNull(response.errorCode());
        } else {
            assertNotNull(response.errorCode());
            assertNull(response.payload());
        }
        return (Response) MessageCodec.deserialize(MessageCodec.serialize(response));
    }

    private String nextUsername() {
        String username = "dispatch_" + UUID.randomUUID().toString().replace("-", "");
        fixtureNames.add(username);
        return username;
    }
}
