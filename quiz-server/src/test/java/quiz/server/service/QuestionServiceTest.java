package quiz.server.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import quiz.common.dto.CreateQuestionDto;
import quiz.common.dto.UpdateQuestionDto;
import quiz.common.enums.Difficulty;
import quiz.common.enums.Role;
import quiz.common.protocol.ErrorCode;
import quiz.server.db.TransactionManager;
import quiz.server.domain.Question;
import quiz.server.domain.User;
import quiz.server.exception.ForbiddenException;
import quiz.server.exception.NotFoundException;
import quiz.server.exception.UnauthorizedException;
import quiz.server.exception.ValidationException;
import quiz.server.repository.QuestionRepository;
import quiz.server.repository.UserRepository;

import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Unit tests for QuestionService using mocked repository and pass-through transaction manager. */
class QuestionServiceTest {
    private static final long ADMIN_ID = 7;
    private static final long TEACHER_ID = 8;
    private static final long STUDENT_ID = 9;
    private static final Instant TS = Instant.parse("2026-10-10T00:00:00Z");

    private QuestionRepository questions;
    private UserRepository users;
    private QuestionService service;

    @BeforeEach
    void setUp() {
        questions = mock(QuestionRepository.class);
        users = mock(UserRepository.class);
        TransactionManager passThrough = new TransactionManager() {
            @Override
            public <T> T executeInTransaction(Function<Connection, T> action) {
                return action.apply(null);
            }
        };
        service = new QuestionService(questions, users, passThrough);
    }

    private User activeActor(long id, Role role) {
        User actor = User.restore(id, "actor", "hash", "Actor", null, role, true, null, TS);
        when(users.findById(id)).thenReturn(Optional.of(actor));
        when(users.findById(null, id)).thenReturn(Optional.of(actor));
        return actor;
    }

    @ParameterizedTest
    @ValueSource(longs = {ADMIN_ID, TEACHER_ID})
    void teacherAndAdminCanCreateQuestion(long actorId) {
        activeActor(actorId, actorId == ADMIN_ID ? Role.ADMIN : Role.TEACHER);
        Question saved = Question.restore(1, "Nội dung?", "A", "B", "C", "D", 'A',
            "Chủ đề", Difficulty.EASY, true, actorId, TS, TS);
        when(questions.save(any(), any())).thenReturn(saved);

        var dto = new CreateQuestionDto("Nội dung?", "A", "B", "C", "D", 'A', "Chủ đề", Difficulty.EASY);
        Question result = service.createQuestion(dto, actorId);

        assertEquals(1, result.id());
        assertEquals("Nội dung?", result.content());
        verify(questions).save(any(), any());
    }

    @ParameterizedTest
    @ValueSource(longs = {STUDENT_ID})
    void studentCannotCreateQuestion(long actorId) {
        activeActor(actorId, Role.STUDENT);

        var dto = new CreateQuestionDto("Nội dung?", "A", "B", "C", "D", 'A', "Chủ đề", Difficulty.EASY);
        assertThrows(ForbiddenException.class, () -> service.createQuestion(dto, actorId));
        verifyNoInteractions(questions);
    }

    @Test
    void inactiveActorCannotCreateQuestion() {
        User actor = User.restore(ADMIN_ID, "actor", "hash", "Actor", null, Role.ADMIN, false, null, TS);
        when(users.findById(null, ADMIN_ID)).thenReturn(Optional.of(actor));

        var dto = new CreateQuestionDto("Nội dung?", "A", "B", "C", "D", 'A', "Chủ đề", Difficulty.EASY);
        assertThrows(UnauthorizedException.class, () -> service.createQuestion(dto, ADMIN_ID));
    }

    @Test
    void getQuestionReturnsQuestion() {
        activeActor(ADMIN_ID, Role.ADMIN);
        Question q = Question.restore(1, "Nội dung?", "A", "B", "C", "D", 'A',
            "Chủ đề", Difficulty.EASY, true, ADMIN_ID, TS, TS);
        when(questions.findById(1L)).thenReturn(Optional.of(q));

        Question result = service.getQuestion(1, ADMIN_ID);

        assertEquals(q, result);
    }

    @Test
    void getQuestionNotFound() {
        activeActor(ADMIN_ID, Role.ADMIN);
        when(questions.findById(1L)).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () -> service.getQuestion(1, ADMIN_ID));
    }

    @Test
    void listQuestionsReturnsAll() {
        activeActor(ADMIN_ID, Role.ADMIN);
        Instant ts = TS;
        when(questions.findAll()).thenReturn(List.of(
            Question.restore(1, "Câu 1?", "A", "B", "C", "D", 'A', "T1", Difficulty.EASY, true, 1, ts, ts),
            Question.restore(2, "Câu 2?", "A", "B", "C", "D", 'B', "T2", Difficulty.HARD, false, 1, ts, ts)));

        List<Question> result = service.listQuestions(ADMIN_ID);

        assertEquals(2, result.size());
    }

    @Test
    void updateRejectsInactiveQuestion() {
        activeActor(ADMIN_ID, Role.ADMIN);
        Question existing = Question.restore(1, "Cũ?", "A", "B", "C", "D", 'A',
            "T", Difficulty.EASY, false, 1, TS, TS);
        when(questions.findById(null, 1L)).thenReturn(Optional.of(existing));

        var dto = new UpdateQuestionDto(1, "Mới?", "A", "B", "C", "D", 'B', "T", Difficulty.MEDIUM);
        assertThrows(ValidationException.class, () -> service.updateQuestion(dto, ADMIN_ID));
    }

    @Test
    void deactivateSetsActiveFalse() {
        activeActor(ADMIN_ID, Role.ADMIN);
        Question existing = Question.restore(1, "Câu?", "A", "B", "C", "D", 'A',
            "T", Difficulty.EASY, true, 1, TS, TS);
        when(questions.findById(null, 1L)).thenReturn(Optional.of(existing));
        when(questions.deactivate(null, 1L)).thenReturn(true);

        assertDoesNotThrow(() -> service.deactivateQuestion(1, ADMIN_ID));
        verify(questions).deactivate(null, 1L);
    }

    @Test
    void deactivateAlreadyInactiveRejected() {
        activeActor(ADMIN_ID, Role.ADMIN);
        Question existing = Question.restore(1, "Câu?", "A", "B", "C", "D", 'A',
            "T", Difficulty.EASY, false, 1, TS, TS);
        when(questions.findById(null, 1L)).thenReturn(Optional.of(existing));

        assertThrows(ValidationException.class, () -> service.deactivateQuestion(1, ADMIN_ID));
    }

    @Test
    void invalidCorrectOptionRejected() {
        activeActor(ADMIN_ID, Role.ADMIN);

        var dto = new CreateQuestionDto("Câu?", "A", "B", "C", "D", 'X', "T", Difficulty.EASY);
        assertThrows(ValidationException.class, () -> service.createQuestion(dto, ADMIN_ID));
    }

    @Test
    void errorCodesArePreserved() {
        activeActor(ADMIN_ID, Role.STUDENT);
        var dto = new CreateQuestionDto("Câu?", "A", "B", "C", "D", 'A', "T", Difficulty.EASY);
        assertEquals(ErrorCode.FORBIDDEN, assertThrows(ForbiddenException.class,
            () -> service.createQuestion(dto, ADMIN_ID)).getErrorCode());

        activeActor(ADMIN_ID, Role.ADMIN);
        when(questions.findById(1L)).thenReturn(Optional.empty());
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(NotFoundException.class,
            () -> service.getQuestion(1, ADMIN_ID)).getErrorCode());
    }
}
