package quiz.server.dispatch.handlers;

import quiz.common.dto.QuizQuestionsDto;
import quiz.common.dto.QuizDto;
import quiz.common.enums.Role;
import quiz.common.protocol.MessageType;
import quiz.common.protocol.Request;
import quiz.common.protocol.Response;
import quiz.server.dispatch.CommandHandler;
import quiz.server.dispatch.DtoMapper;
import quiz.server.network.UserSession;
import quiz.server.service.QuizService;

import java.util.ArrayList;
import java.util.Objects;
import java.util.Set;

/** Quiz management commands; actor IDs always come from the authenticated session.
 * <p>Only SET_QUIZ_QUESTIONS, GET_QUIZ and LIST_QUIZZES are implemented here.
 * CREATE_QUIZ / UPDATE_QUIZ / OPEN_QUIZ / CLOSE_QUIZ / ASSIGN_QUIZ belong to the
 * Quiz & Assignment milestone and are intentionally left unsupported.</p> */
public final class QuizHandler implements CommandHandler {
    private final QuizService quizzes;

    public QuizHandler(QuizService quizzes) {
        this.quizzes = Objects.requireNonNull(quizzes, "QuizService cannot be null");
    }

    @Override
    public Set<MessageType> supportedTypes() {
        return Set.of(MessageType.SET_QUIZ_QUESTIONS, MessageType.GET_QUIZ,
            MessageType.LIST_QUIZZES);
    }

    @Override
    public Set<Role> allowedRoles(MessageType type) {
        if (!supportedTypes().contains(type)) {
            throw new IllegalArgumentException("Unsupported quiz command");
        }
        return Set.of(Role.TEACHER, Role.ADMIN);
    }

    @Override
    public Response handle(Request request, UserSession session) {
        long actorId = HandlerPayload.authenticated(session).userId();
        return switch (request.type()) {
            case SET_QUIZ_QUESTIONS -> {
                QuizQuestionsDto dto = HandlerPayload.required(request, QuizQuestionsDto.class);
                yield Response.success(request.requestId(),
                    DtoMapper.quiz(quizzes.setQuestions(dto.quizId(), dto, actorId)));
            }
            case GET_QUIZ -> {
                long quizId = HandlerPayload.requiredLong(request);
                yield Response.success(request.requestId(),
                    DtoMapper.quiz(quizzes.getQuiz(quizId, actorId)));
            }
            case LIST_QUIZZES -> {
                HandlerPayload.absent(request);
                yield Response.success(request.requestId(),
                    DtoMapper.quizzes(quizzes.listQuizzes(actorId)));
            }
            default -> throw new quiz.server.exception.QuizException(
                quiz.common.protocol.ErrorCode.INVALID_REQUEST, "Unsupported quiz command");
        };
    }
}
