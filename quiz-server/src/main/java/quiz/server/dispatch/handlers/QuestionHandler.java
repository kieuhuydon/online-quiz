package quiz.server.dispatch.handlers;

import quiz.common.dto.CreateQuestionDto;
import quiz.common.dto.QuestionDto;
import quiz.common.dto.UpdateQuestionDto;
import quiz.common.enums.Role;
import quiz.common.protocol.ErrorCode;
import quiz.common.protocol.MessageType;
import quiz.common.protocol.Request;
import quiz.common.protocol.Response;
import quiz.server.dispatch.CommandHandler;
import quiz.server.dispatch.DtoMapper;
import quiz.server.exception.QuizException;
import quiz.server.network.UserSession;
import quiz.server.service.QuestionService;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Question bank commands; actor IDs always come from the authenticated session. */
public final class QuestionHandler implements CommandHandler {
    private final QuestionService questions;

    public QuestionHandler(QuestionService questions) {
        this.questions = Objects.requireNonNull(questions, "QuestionService cannot be null");
    }

    @Override
    public Set<MessageType> supportedTypes() {
        return Set.of(MessageType.CREATE_QUESTION, MessageType.UPDATE_QUESTION,
            MessageType.GET_QUESTION, MessageType.LIST_QUESTIONS,
            MessageType.DEACTIVATE_QUESTION, MessageType.BATCH_DEACTIVATE_QUESTIONS);
    }

    @Override
    public Set<Role> allowedRoles(MessageType type) {
        if (!supportedTypes().contains(type)) {
            throw new IllegalArgumentException("Unsupported question command");
        }
        return Set.of(Role.TEACHER, Role.ADMIN);
    }

    @Override
    public Response handle(Request request, UserSession session) {
        long actorId = HandlerPayload.authenticated(session).userId();
        return switch (request.type()) {
            case CREATE_QUESTION -> {
                CreateQuestionDto dto = HandlerPayload.required(request, CreateQuestionDto.class);
                yield Response.success(request.requestId(),
                    DtoMapper.question(questions.createQuestion(dto, actorId)));
            }
            case GET_QUESTION -> {
                long questionId = HandlerPayload.requiredLong(request);
                yield Response.success(request.requestId(),
                    DtoMapper.question(questions.getQuestion(questionId, actorId)));
            }
            case LIST_QUESTIONS -> {
                HandlerPayload.absent(request);
                yield Response.success(request.requestId(),
                    DtoMapper.questions(questions.listQuestions(actorId)));
            }
            case UPDATE_QUESTION -> {
                UpdateQuestionDto dto = HandlerPayload.required(request, UpdateQuestionDto.class);
                yield Response.success(request.requestId(),
                    DtoMapper.question(questions.updateQuestion(dto, actorId)));
            }
            case DEACTIVATE_QUESTION -> {
                long questionId = HandlerPayload.requiredLong(request);
                questions.deactivateQuestion(questionId, actorId);
                yield Response.success(request.requestId(), null);
            }
            case BATCH_DEACTIVATE_QUESTIONS -> {
                @SuppressWarnings("unchecked")
                List<Long> ids = (List<Long>) HandlerPayload.required(request, List.class);
                int deactivated = questions.deactivateQuestions(ids, actorId);
                yield Response.success(request.requestId(), deactivated);
            }
            default -> throw new QuizException(quiz.common.protocol.ErrorCode.INVALID_REQUEST,
                "Unsupported question command");
        };
    }
}
