package quiz.server.dispatch.handlers;

import quiz.common.dto.*;
import quiz.common.enums.*;
import quiz.common.protocol.*;
import quiz.server.dispatch.CommandHandler;
import quiz.server.exception.QuizException;
import quiz.server.network.UserSession;
import quiz.server.service.AttemptService;
import java.util.*;

public final class AttemptHandler implements CommandHandler {
    private final AttemptService service;
    public AttemptHandler(AttemptService service){this.service=Objects.requireNonNull(service);}
    @Override public Set<MessageType> supportedTypes(){return Set.of(MessageType.START_ATTEMPT,MessageType.SAVE_ANSWER,MessageType.SUBMIT_ATTEMPT,MessageType.GET_ATTEMPT_STATE);}
    @Override public Set<Role> allowedRoles(MessageType type){if(!supportedTypes().contains(type))throw new IllegalArgumentException("Unsupported attempt command");return Set.of(Role.STUDENT);}
    @Override public Response handle(Request r,UserSession session){UserSession auth=HandlerPayload.authenticated(session);return switch(r.type()){
        case START_ATTEMPT->{StartAttemptRequestDto d=HandlerPayload.required(r,StartAttemptRequestDto.class);yield Response.success(r.requestId(),service.startAttempt(d.quizId(),d.pin(),auth.userId()));}
        case SAVE_ANSWER->{SaveAnswerDto d=HandlerPayload.required(r,SaveAnswerDto.class);if(d.selectedOption()!=null)service.saveAnswer(d.attemptId(),d.questionId(),d.selectedOption(),auth.userId());else service.saveAnswer(d.attemptId(),d.questionId(),d.answer(),auth.userId());yield Response.success(r.requestId(),null);}
        case SUBMIT_ATTEMPT->{SubmitAttemptDto d=HandlerPayload.required(r,SubmitAttemptDto.class);yield Response.success(r.requestId(),service.submitAttempt(d.attemptId(),auth.userId(),SubmitReason.MANUAL));}
        case GET_ATTEMPT_STATE->{Long id=HandlerPayload.required(r,Long.class);yield Response.success(r.requestId(),service.getAttemptState(id,auth.userId()));}
        default->throw new QuizException(ErrorCode.INVALID_REQUEST,"Unsupported attempt command");
    };}
}
