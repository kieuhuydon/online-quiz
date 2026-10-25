package quiz.server.dispatch.handlers;

import quiz.common.enums.Role;
import quiz.common.protocol.*;
import quiz.server.dispatch.CommandHandler;
import quiz.server.exception.QuizException;
import quiz.server.network.UserSession;
import quiz.server.service.AttemptService;
import java.util.*;

public final class ResultHandler implements CommandHandler {
    private final AttemptService service;
    public ResultHandler(AttemptService service){this.service=Objects.requireNonNull(service);}
    @Override public Set<MessageType> supportedTypes(){return Set.of(MessageType.GET_MY_RESULT,MessageType.LIST_MY_HISTORY);}
    @Override public Set<Role> allowedRoles(MessageType type){if(!supportedTypes().contains(type))throw new IllegalArgumentException("Unsupported result command");return Set.of(Role.STUDENT);}
    @Override public Response handle(Request r,UserSession session){UserSession auth=HandlerPayload.authenticated(session);return switch(r.type()){
        case GET_MY_RESULT->{Long id=HandlerPayload.required(r,Long.class);yield Response.success(r.requestId(),service.getResult(id,auth.userId()));}
        case LIST_MY_HISTORY->{HandlerPayload.absent(r);yield Response.success(r.requestId(),new java.util.ArrayList<>(service.listHistory(auth.userId())));}
        default->throw new QuizException(ErrorCode.INVALID_REQUEST,"Unsupported result command");
    };}
}
