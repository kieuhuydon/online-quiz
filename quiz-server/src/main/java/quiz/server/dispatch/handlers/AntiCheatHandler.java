package quiz.server.dispatch.handlers;

import quiz.common.dto.ReportAntiCheatDto;
import quiz.common.enums.Role;
import quiz.common.protocol.ErrorCode;
import quiz.common.protocol.MessageType;
import quiz.common.protocol.Request;
import quiz.common.protocol.Response;
import quiz.server.dispatch.CommandHandler;
import quiz.server.exception.QuizException;
import quiz.server.network.UserSession;
import quiz.server.service.AntiCheatService;

import java.util.ArrayList;
import java.util.Objects;
import java.util.Set;

/**
 * Điều hướng xử lý các thông điệp giám sát chống gian lận (REPORT_ANTICHEAT_EVENT, LIST_ATTEMPT_EVENTS).
 */
public final class AntiCheatHandler implements CommandHandler {
    private final AntiCheatService service;

    public AntiCheatHandler(AntiCheatService service) {
        this.service = Objects.requireNonNull(service, "AntiCheatService cannot be null");
    }

    @Override
    public Set<MessageType> supportedTypes() {
        return Set.of(MessageType.REPORT_ANTICHEAT_EVENT, MessageType.LIST_ATTEMPT_EVENTS);
    }

    @Override
    public Set<Role> allowedRoles(MessageType type) {
        return switch (type) {
            case REPORT_ANTICHEAT_EVENT -> Set.of(Role.STUDENT);
            case LIST_ATTEMPT_EVENTS -> Set.of(Role.TEACHER, Role.ADMIN);
            default -> throw new IllegalArgumentException("Unsupported anti-cheat command: " + type);
        };
    }

    @Override
    public Response handle(Request r, UserSession session) {
        UserSession auth = HandlerPayload.authenticated(session);
        return switch (r.type()) {
            case REPORT_ANTICHEAT_EVENT -> {
                ReportAntiCheatDto dto = HandlerPayload.required(r, ReportAntiCheatDto.class);
                service.reportEvent(dto, auth.userId());
                yield Response.success(r.requestId(), null);
            }
            case LIST_ATTEMPT_EVENTS -> {
                Long attemptId = HandlerPayload.required(r, Long.class);
                yield Response.success(r.requestId(), new ArrayList<>(service.listAttemptEvents(attemptId, auth)));
            }
            default -> throw new QuizException(ErrorCode.INVALID_REQUEST, "Unsupported anti-cheat command");
        };
    }
}
