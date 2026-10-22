package quiz.server.dispatch.handlers;

import quiz.common.enums.Role;
import quiz.common.protocol.ErrorCode;
import quiz.common.protocol.MessageType;
import quiz.common.protocol.Request;
import quiz.common.protocol.Response;
import quiz.server.dispatch.CommandHandler;
import quiz.server.exception.QuizException;
import quiz.server.network.UserSession;
import quiz.server.service.QuestionService;

import java.util.Objects;
import java.util.Set;

/**
 * Xử lý các thông điệp liên quan đến Ngân hàng câu hỏi (FR-A05).
 */
public final class QuestionHandler implements CommandHandler {
    private final QuestionService questionService;

    public QuestionHandler(QuestionService questionService) {
        this.questionService = Objects.requireNonNull(questionService, "QuestionService cannot be null");
    }

    @Override
    public Set<MessageType> supportedTypes() {
        return Set.of(
            MessageType.LIST_QUESTIONS,
            MessageType.GET_QUESTION
        );
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
        HandlerPayload.authenticated(session);
        return switch (request.type()) {
            case LIST_QUESTIONS -> {
                HandlerPayload.absent(request);
                yield Response.success(request.requestId(), questionService.listQuestions());
            }
            case GET_QUESTION -> {
                Long questionId = HandlerPayload.required(request, Long.class);
                yield Response.success(request.requestId(), questionService.getQuestion(questionId));
            }
            default -> throw new QuizException(ErrorCode.INVALID_REQUEST, "Unsupported question command");
        };
    }
}
