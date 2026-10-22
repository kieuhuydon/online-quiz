package quiz.server.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import quiz.common.dto.QuizQuestionsDto;
import quiz.common.enums.AntiCheatPolicyType;
import quiz.common.enums.QuizStatus;
import quiz.common.enums.Role;
import quiz.common.protocol.ErrorCode;
import quiz.server.db.TransactionManager;
import quiz.server.domain.Quiz;
import quiz.server.domain.User;
import quiz.server.exception.ForbiddenException;
import quiz.server.exception.NotFoundException;
import quiz.server.exception.UnauthorizedException;
import quiz.server.exception.ValidationException;
import quiz.server.repository.QuestionRepository;
import quiz.server.repository.QuizRepository;
import quiz.server.repository.UserRepository;

import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Unit tests for QuizService using mocked repository and pass-through transaction manager. */
class QuizServiceTest {
    private static final long ADMIN_ID = 7;
    private static final long TEACHER_ID = 8;
    private static final long STUDENT_ID = 9;
    private static final Instant TS = Instant.parse("2026-10-10T00:00:00Z");

    private QuizRepository quizzes;
    private QuestionRepository questions;
    private UserRepository users;
    private QuizService service;

    @BeforeEach
    void setUp() {
        quizzes = mock(QuizRepository.class);
        questions = mock(QuestionRepository.class);
        users = mock(UserRepository.class);
        TransactionManager passThrough = new TransactionManager() {
            @Override
            public <T> T executeInTransaction(Function<Connection, T> action) {
                return action.apply(null);
            }
        };
        service = new QuizService(quizzes, questions, users, passThrough);
    }

    private User activeActor(long id, Role role) {
        User actor = User.restore(id, "actor", "hash", "Actor", null, role, true, null, TS);
        when(users.findById(null, id)).thenReturn(Optional.of(actor));
        when(users.findById(id)).thenReturn(Optional.of(actor));
        return actor;
    }

    @Test
    void teacherCanSetQuestionsOnDraftQuiz() {
        activeActor(TEACHER_ID, Role.TEACHER);
        Quiz draft = Quiz.restore(1, "Quiz 1", null, null, 15, 50.0, QuizStatus.DRAFT,
            null, AntiCheatPolicyType.WARN_THEN_AUTO_SUBMIT, 3, TEACHER_ID, TS, TS, null);
        when(quizzes.findById(null, 1L)).thenReturn(Optional.of(draft));
        when(questions.existsById(10L)).thenReturn(true);
        when(questions.existsById(11L)).thenReturn(true);
        Quiz updated = Quiz.restore(1, "Quiz 1", null, null, 15, 50.0, QuizStatus.DRAFT,
            null, AntiCheatPolicyType.WARN_THEN_AUTO_SUBMIT, 3, TEACHER_ID, TS, TS, null);
        when(quizzes.setQuestions(null, 1L, List.of(10L, 11L))).thenReturn(true);
        when(quizzes.findById(null, 1L)).thenReturn(Optional.of(updated));

        var dto = new QuizQuestionsDto(1, List.of(10L, 11L));
        Quiz result = service.setQuestions(1, dto, TEACHER_ID);

        assertEquals(1, result.id());
        verify(quizzes).setQuestions(null, 1L, List.of(10L, 11L));
    }

    @Test
    void studentCannotSetQuestions() {
        activeActor(STUDENT_ID, Role.STUDENT);
        var dto = new QuizQuestionsDto(1, List.of(10L));
        assertThrows(ForbiddenException.class, () -> service.setQuestions(1, dto, STUDENT_ID));
        verifyNoInteractions(quizzes);
    }

    @Test
    void inactiveActorCannotSetQuestions() {
        User actor = User.restore(ADMIN_ID, "actor", "hash", "Actor", null, Role.ADMIN, false, null, TS);
        when(users.findById(null, ADMIN_ID)).thenReturn(Optional.of(actor));
        var dto = new QuizQuestionsDto(1, List.of(10L));
        assertThrows(UnauthorizedException.class, () -> service.setQuestions(1, dto, ADMIN_ID));
    }

    @Test
    void setQuestionsOnNonDraftQuizRejected() {
        activeActor(ADMIN_ID, Role.ADMIN);
        Quiz open = Quiz.restore(1, "Quiz 1", null, null, 15, 50.0, QuizStatus.OPEN,
            null, AntiCheatPolicyType.WARN_THEN_AUTO_SUBMIT, 3, ADMIN_ID, TS, TS, null);
        when(quizzes.findById(null, 1L)).thenReturn(Optional.of(open));
        var dto = new QuizQuestionsDto(1, List.of(10L));
        assertThrows(ValidationException.class, () -> service.setQuestions(1, dto, ADMIN_ID));
    }

    @Test
    void setQuestionsNullDtoRejected() {
        activeActor(ADMIN_ID, Role.ADMIN);
        assertThrows(ValidationException.class, () -> service.setQuestions(1, null, ADMIN_ID));
    }

    @Test
    void setQuestionsUnknownQuestionRejected() {
        activeActor(ADMIN_ID, Role.ADMIN);
        Quiz draft = Quiz.restore(1, "Quiz 1", null, null, 15, 50.0, QuizStatus.DRAFT,
            null, AntiCheatPolicyType.WARN_THEN_AUTO_SUBMIT, 3, ADMIN_ID, TS, TS, null);
        when(quizzes.findById(null, 1L)).thenReturn(Optional.of(draft));
        when(questions.existsById(999L)).thenReturn(false);
        var dto = new QuizQuestionsDto(1, List.of(999L));
        assertThrows(NotFoundException.class, () -> service.setQuestions(1, dto, ADMIN_ID));
    }

    @Test
    void getQuizReturnsQuiz() {
        activeActor(ADMIN_ID, Role.ADMIN);
        Quiz q = Quiz.restore(1, "Quiz 1", null, null, 15, 50.0, QuizStatus.DRAFT,
            null, AntiCheatPolicyType.WARN_THEN_AUTO_SUBMIT, 3, ADMIN_ID, TS, TS, null);
        when(quizzes.findById(1L)).thenReturn(Optional.of(q));
        assertEquals(q, service.getQuiz(1, ADMIN_ID));
    }

    @Test
    void getQuizNotFound() {
        activeActor(ADMIN_ID, Role.ADMIN);
        when(quizzes.findById(1L)).thenReturn(Optional.empty());
        assertThrows(NotFoundException.class, () -> service.getQuiz(1, ADMIN_ID));
    }

    @Test
    void listQuizzesReturnsAll() {
        activeActor(ADMIN_ID, Role.ADMIN);
        when(quizzes.findAll()).thenReturn(List.of(
            Quiz.restore(1, "Quiz 1", null, null, 15, 50.0, QuizStatus.DRAFT,
                null, AntiCheatPolicyType.WARN_THEN_AUTO_SUBMIT, 3, ADMIN_ID, TS, TS, null),
            Quiz.restore(2, "Quiz 2", null, null, 30, 50.0, QuizStatus.OPEN,
                null, AntiCheatPolicyType.WARN_THEN_AUTO_SUBMIT, 3, ADMIN_ID, TS, TS, null)));
        assertEquals(2, service.listQuizzes(ADMIN_ID).size());
    }

    @Test
    void errorCodesArePreserved() {
        activeActor(ADMIN_ID, Role.STUDENT);
        var dto = new QuizQuestionsDto(1, List.of(10L));
        assertEquals(ErrorCode.FORBIDDEN, assertThrows(ForbiddenException.class,
            () -> service.setQuestions(1, dto, ADMIN_ID)).getErrorCode());

        activeActor(ADMIN_ID, Role.ADMIN);
        when(quizzes.findById(1L)).thenReturn(Optional.empty());
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(NotFoundException.class,
            () -> service.getQuiz(1, ADMIN_ID)).getErrorCode());
    }
}
