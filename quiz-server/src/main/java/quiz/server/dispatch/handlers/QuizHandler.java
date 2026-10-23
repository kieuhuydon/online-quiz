package quiz.server.dispatch.handlers;

import quiz.common.dto.QuizQuestionsDto;
import quiz.common.dto.QuizDto;
import quiz.common.enums.Role;
import quiz.common.dto.AssignQuizDto;
import quiz.common.dto.CreateQuizDto;
import quiz.common.dto.QuizQuestionsDto;
import quiz.common.dto.UpdateQuizDto;
import quiz.common.enums.Role;
import quiz.common.protocol.ErrorCode;
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
import quiz.server.exception.QuizException;
import quiz.server.network.UserSession;
import quiz.server.service.QuizService;

import java.util.Objects;
import java.util.Set;

/**
 * Xử lý các thông điệp quản lý đề kiểm tra và giao bài (FR-A04, FR-A05, FR-A06, FR-A08, FR-A09, FR-S02, FR-S04).
 */
public final class QuizHandler implements CommandHandler {
    private final QuizService quizService;

    public QuizHandler(QuizService quizService) {
        this.quizService = Objects.requireNonNull(quizService, "QuizService cannot be null");
    }

    @Override
    public Set<MessageType> supportedTypes() {
        return Set.of(MessageType.SET_QUIZ_QUESTIONS, MessageType.GET_QUIZ,
            MessageType.LIST_QUIZZES);
        return Set.of(
            MessageType.CREATE_QUIZ,
            MessageType.UPDATE_QUIZ,
            MessageType.GET_QUIZ,
            MessageType.LIST_QUIZZES,
            MessageType.SET_QUIZ_QUESTIONS,
            MessageType.OPEN_QUIZ,
            MessageType.CLOSE_QUIZ,
            MessageType.ASSIGN_QUIZ,
            MessageType.LIST_ASSIGNED_QUIZZES,
            MessageType.GET_QUIZ_INFO
        );
    }

    @Override
    public Set<Role> allowedRoles(MessageType type) {
        if (!supportedTypes().contains(type)) {
            throw new IllegalArgumentException("Unsupported quiz command");
        }
        return Set.of(Role.TEACHER, Role.ADMIN);
        return switch (type) {
            case CREATE_QUIZ, UPDATE_QUIZ, GET_QUIZ, LIST_QUIZZES,
                 SET_QUIZ_QUESTIONS, OPEN_QUIZ, CLOSE_QUIZ, ASSIGN_QUIZ -> Set.of(Role.TEACHER, Role.ADMIN);
            case LIST_ASSIGNED_QUIZZES, GET_QUIZ_INFO -> Set.of(Role.STUDENT);
            default -> throw new IllegalArgumentException("Unsupported quiz command");
        };
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
        UserSession authSession = HandlerPayload.authenticated(session);
        return switch (request.type()) {
            case CREATE_QUIZ -> {
                CreateQuizDto dto = HandlerPayload.required(request, CreateQuizDto.class);
                yield Response.success(request.requestId(), quizService.createQuiz(dto, authSession));
            }
            case UPDATE_QUIZ -> {
                UpdateQuizDto dto = HandlerPayload.required(request, UpdateQuizDto.class);
                yield Response.success(request.requestId(), quizService.updateQuiz(dto, authSession));
            }
            case GET_QUIZ -> {
                Long quizId = HandlerPayload.required(request, Long.class);
                yield Response.success(request.requestId(), quizService.getQuizDetail(quizId));
            }
            case LIST_QUIZZES -> {
                HandlerPayload.absent(request);
                yield Response.success(request.requestId(), quizService.listQuizzes(authSession));
            }
            case SET_QUIZ_QUESTIONS -> {
                QuizQuestionsDto dto = HandlerPayload.required(request, QuizQuestionsDto.class);
                quizService.setQuizQuestions(dto.quizId(), dto.questionIds(), authSession);
                yield Response.success(request.requestId(), null);
            }
            case OPEN_QUIZ -> {
                Long quizId = HandlerPayload.required(request, Long.class);
                yield Response.success(request.requestId(), quizService.openQuiz(quizId, authSession));
            }
            case CLOSE_QUIZ -> {
                Long quizId = HandlerPayload.required(request, Long.class);
                yield Response.success(request.requestId(), quizService.closeQuiz(quizId, authSession));
            }
            case ASSIGN_QUIZ -> {
                AssignQuizDto dto = HandlerPayload.required(request, AssignQuizDto.class);
                quizService.assignQuiz(dto.quizId(), dto.classId(), authSession);
                yield Response.success(request.requestId(), null);
            }
            case LIST_ASSIGNED_QUIZZES -> {
                HandlerPayload.absent(request);
                yield Response.success(request.requestId(), quizService.listAssignedQuizzesForStudent(authSession.userId()));
            }
            case GET_QUIZ_INFO -> {
                Long quizId = HandlerPayload.required(request, Long.class);
                yield Response.success(request.requestId(), quizService.getQuizInfo(quizId, authSession.userId()));
            }
            default -> throw new QuizException(ErrorCode.INVALID_REQUEST, "Unsupported quiz command");
        };
    }
}
