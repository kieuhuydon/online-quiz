package quiz.server.dispatch.handlers;

import quiz.common.dto.CreateAccountDto;
import quiz.common.dto.SetAccountActiveDto;
import quiz.common.enums.Role;
import quiz.common.protocol.ErrorCode;
import quiz.common.protocol.MessageType;
import quiz.common.protocol.Request;
import quiz.common.protocol.Response;
import quiz.server.dispatch.CommandHandler;
import quiz.server.dispatch.DtoMapper;
import quiz.server.exception.QuizException;
import quiz.server.network.UserSession;
import quiz.server.service.AccountService;

import java.util.Objects;
import java.util.Set;

/** Admin commands; actor IDs always come from the authenticated session. */
public final class AccountHandler implements CommandHandler {
    private final AccountService accounts;

    public AccountHandler(AccountService accounts) {
        this.accounts = Objects.requireNonNull(accounts, "AccountService cannot be null");
    }

    @Override
    public Set<MessageType> supportedTypes() {
        return Set.of(MessageType.CREATE_ACCOUNT, MessageType.LIST_ACCOUNTS, MessageType.SET_ACCOUNT_ACTIVE);
    }

    @Override
    public Set<Role> allowedRoles(MessageType type) {
        if (!supportedTypes().contains(type)) {
            throw new IllegalArgumentException("Unsupported account command");
        }
        return Set.of(Role.ADMIN);
    }

    @Override
    public Response handle(Request request, UserSession session) {
        long adminId = HandlerPayload.authenticated(session).userId();
        return switch (request.type()) {
            case CREATE_ACCOUNT -> {
                CreateAccountDto dto = HandlerPayload.required(request, CreateAccountDto.class);
                yield Response.success(request.requestId(), DtoMapper.account(accounts.createAccount(dto, adminId)));
            }
            case LIST_ACCOUNTS -> {
                HandlerPayload.absent(request);
                yield Response.success(request.requestId(), DtoMapper.accounts(accounts.listAccounts(adminId)));
            }
            case SET_ACCOUNT_ACTIVE -> {
                SetAccountActiveDto dto = HandlerPayload.required(request, SetAccountActiveDto.class);
                accounts.setAccountActive(dto.userId(), dto.active(), adminId);
                yield Response.success(request.requestId(), null);
            }
            default -> throw new QuizException(ErrorCode.INVALID_REQUEST, "Unsupported account command");
        };
    }
}
