package quiz.server.dispatch.handlers;

import quiz.common.dto.LoginRequestDto;
import quiz.common.enums.Role;
import quiz.common.protocol.ErrorCode;
import quiz.common.protocol.MessageType;
import quiz.common.protocol.Request;
import quiz.common.protocol.Response;
import quiz.server.dispatch.CommandHandler;
import quiz.server.dispatch.DtoMapper;
import quiz.server.exception.ConflictException;
import quiz.server.exception.QuizException;
import quiz.server.network.UserSession;
import quiz.server.service.AuthService;

import java.util.Objects;
import java.util.Set;

/** LOGIN/LOGOUT commands; socket binding belongs to the network lifecycle. */
public final class LoginHandler implements CommandHandler {
    private final AuthService auth;

    public LoginHandler(AuthService auth) {
        this.auth = Objects.requireNonNull(auth, "AuthService cannot be null");
    }

    @Override
    public Set<MessageType> supportedTypes() {
        return Set.of(MessageType.LOGIN, MessageType.LOGOUT);
    }

    @Override
    public Set<Role> allowedRoles(MessageType type) {
        return switch (type) {
            case LOGIN -> Set.of();
            case LOGOUT -> Set.of(Role.ADMIN, Role.TEACHER, Role.STUDENT);
            default -> throw new IllegalArgumentException("Unsupported auth command");
        };
    }

    @Override
    public Response handle(Request request, UserSession session) {
        return switch (request.type()) {
            case LOGIN -> login(request, session);
            case LOGOUT -> {
                HandlerPayload.authenticated(session);
                HandlerPayload.absent(request);
                auth.logout(request.token());
                yield Response.success(request.requestId(), null);
            }
            default -> throw new QuizException(ErrorCode.INVALID_REQUEST, "Unsupported auth command");
        };
    }

    private Response login(Request request, UserSession session) {
        if (session != null) {
            throw new ConflictException("Connection is already authenticated");
        }
        if (request.token() != null) {
            throw new QuizException(ErrorCode.INVALID_REQUEST, "LOGIN must not carry a token");
        }
        LoginRequestDto dto = HandlerPayload.required(request, LoginRequestDto.class);
        return Response.success(request.requestId(), DtoMapper.login(auth.login(dto.username(), dto.password())));
    }
}
