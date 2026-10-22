package quiz.server.dispatch.handlers;

import quiz.common.dto.AntiCheatEventDto;
import quiz.common.dto.ReportAntiCheatDto;
import quiz.common.enums.Role;
import quiz.common.protocol.MessageType;
import quiz.common.protocol.Request;
import quiz.common.protocol.Response;
import quiz.server.dispatch.CommandHandler;
import quiz.server.network.UserSession;
import quiz.server.service.AntiCheatService;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Xử lý các thông điệp giám sát chống gian lận (FR-S13, FR-S14, FR-T09).
 */
public final class AntiCheatHandler implements CommandHandler {
    private final AntiCheatService antiCheatService;

    public AntiCheatHandler(AntiCheatService antiCheatService) {
        this.antiCheatService = Objects.requireNonNull(antiCheatService, "AntiCheatService cannot be null");
    }

    @Override
    public Set<MessageType> supportedTypes() {
        return Set.of(
            MessageType.REPORT_ANTICHEAT_EVENT,
            MessageType.LIST_ATTEMPT_EVENTS
        );
    }

    @Override
    public Set<Role> allowedRoles(MessageType type) {
        if (!supportedTypes().contains(type)) {
            throw new IllegalArgumentException("Unsupported anti-cheat command: " + type);
        }
        return switch (type) {
            case REPORT_ANTICHEAT_EVENT -> Set.of(Role.STUDENT);
            case LIST_ATTEMPT_EVENTS -> Set.of(Role.TEACHER, Role.ADMIN);
            default -> throw new IllegalArgumentException("Unexpected command: " + type);
        };
    }

    @Override
    public Response handle(Request request, UserSession session) {
        UserSession currentSession = HandlerPayload.authenticated(session);

        return switch (request.type()) {
            case REPORT_ANTICHEAT_EVENT -> {
                ReportAntiCheatDto dto = HandlerPayload.required(request, ReportAntiCheatDto.class);
                antiCheatService.reportViolation(currentSession.userId(), dto);
                yield Response.success(request.requestId(), null);
            }
            case LIST_ATTEMPT_EVENTS -> {
                Long attemptId = HandlerPayload.required(request, Long.class);
                List<AntiCheatEventDto> events = antiCheatService.listAttemptEvents(attemptId, currentSession);
                yield Response.success(request.requestId(), new ArrayList<>(events));
            }
            default -> throw new IllegalArgumentException("Unsupported type: " + request.type());
        };
    }
}

