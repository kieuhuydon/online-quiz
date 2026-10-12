package quiz.server.dispatch.handlers;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import quiz.common.dto.AccountDto;
import quiz.common.dto.CreateAccountDto;
import quiz.common.dto.SetAccountActiveDto;
import quiz.common.enums.Role;
import quiz.common.protocol.ErrorCode;
import quiz.common.protocol.MessageType;
import quiz.common.protocol.Request;
import quiz.server.dispatch.DispatchTestUsers;
import quiz.server.exception.QuizException;
import quiz.server.network.UserSession;
import quiz.server.service.AccountService;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AccountHandlerTest {
    private AccountService accounts;
    private AccountHandler handler;
    private final UserSession session = new UserSession(7, Role.ADMIN, "User7");

    @BeforeEach
    void setUp() {
        accounts = mock(AccountService.class);
        handler = new AccountHandler(accounts);
    }

    @Test
    void createUsesSessionActorAndMapsAccountWithoutHash() {
        var dto = new CreateAccountDto("student", "secret", "Name", null, Role.STUDENT);
        when(accounts.createAccount(dto, 7)).thenReturn(DispatchTestUsers.user(8, Role.STUDENT));
        var response = handler.handle(new Request(42, MessageType.CREATE_ACCOUNT, "token", dto), session);
        assertTrue(response.success());
        assertEquals(42, response.requestId());
        assertInstanceOf(AccountDto.class, response.payload());
        assertEquals(7L, ((AccountDto) response.payload()).createdBy());
        verify(accounts).createAccount(dto, 7);
    }

    @Test
    void listReturnsConcreteSerializableCollectionAndSetActiveReturnsNull() {
        when(accounts.listAccounts(7)).thenReturn(List.of(DispatchTestUsers.user(8, Role.STUDENT)));
        var list = handler.handle(new Request(42, MessageType.LIST_ACCOUNTS, "token", null), session);
        assertInstanceOf(ArrayList.class, list.payload());
        var update = handler.handle(new Request(43, MessageType.SET_ACCOUNT_ACTIVE, "token", new SetAccountActiveDto(8, false)), session);
        assertTrue(update.success());
        assertNull(update.payload());
        verify(accounts).setAccountActive(8, false, 7);
    }

    @ParameterizedTest
    @EnumSource(value = MessageType.class, names = {"CREATE_ACCOUNT", "LIST_ACCOUNTS", "SET_ACCOUNT_ACTIVE"})
    void accountPoliciesRequireAdminAndRejectWrongOrMissingPayload(MessageType type) {
        assertEquals(Set.of(Role.ADMIN), handler.allowedRoles(type));
        assertEquals(ErrorCode.INVALID_REQUEST, assertThrows(QuizException.class,
            () -> handler.handle(new Request(42, type, "token", "wrong"), session)).getErrorCode());
        if (type != MessageType.LIST_ACCOUNTS) {
            assertEquals(ErrorCode.INVALID_REQUEST, assertThrows(QuizException.class,
                () -> handler.handle(new Request(42, type, "token", null), session)).getErrorCode());
        }
        verifyNoInteractions(accounts);
    }

    @Test
    void noSessionAndUnsupportedTypeCannotReachService() {
        assertEquals(ErrorCode.UNAUTHENTICATED, assertThrows(QuizException.class,
            () -> handler.handle(new Request(42, MessageType.LIST_ACCOUNTS, "token", null), null)).getErrorCode());
        assertEquals(ErrorCode.INVALID_REQUEST, assertThrows(QuizException.class,
            () -> handler.handle(new Request(42, MessageType.CREATE_QUIZ, "token", null), session)).getErrorCode());
        assertThrows(IllegalArgumentException.class, () -> handler.allowedRoles(MessageType.CREATE_QUIZ));
        verifyNoInteractions(accounts);
    }
}
