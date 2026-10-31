package quiz.server.dispatch.handlers;

import quiz.common.dto.ApplyPenaltyDto;
import quiz.common.enums.Role;
import quiz.common.protocol.*;
import quiz.server.dispatch.CommandHandler;
import quiz.server.network.UserSession;
import quiz.server.service.MonitoringService;
import java.util.Set;

public final class MonitoringHandler implements CommandHandler {
    private final MonitoringService service;
    public MonitoringHandler(MonitoringService service) { this.service=service; }
    public Set<MessageType> supportedTypes() { return Set.of(MessageType.LIST_EXAM_SESSIONS,MessageType.GET_EXAM_SESSION,
        MessageType.APPLY_MONITORING_PENALTY,MessageType.LIST_MONITORING_PENALTIES); }
    public Set<Role> allowedRoles(MessageType type) {
        if(!supportedTypes().contains(type)) throw new IllegalArgumentException("Unsupported monitoring command");
        return type==MessageType.GET_EXAM_SESSION?Set.of(Role.STUDENT):Set.of(Role.TEACHER,Role.ADMIN);
    }
    public Response handle(Request r,UserSession session) {
        UserSession actor=HandlerPayload.authenticated(session);
        return Response.success(r.requestId(),switch(r.type()) {
            case LIST_EXAM_SESSIONS -> new java.util.ArrayList<>(service.list(HandlerPayload.required(r,Long.class),actor));
            case GET_EXAM_SESSION -> service.studentState(HandlerPayload.required(r,Long.class),actor);
            case APPLY_MONITORING_PENALTY -> service.penalize(HandlerPayload.required(r,ApplyPenaltyDto.class),actor);
            case LIST_MONITORING_PENALTIES -> new java.util.ArrayList<>(service.history(HandlerPayload.required(r,Long.class),actor));
            default -> throw new IllegalArgumentException("Unsupported monitoring command");
        });
    }
}
