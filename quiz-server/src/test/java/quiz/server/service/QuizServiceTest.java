package quiz.server.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import quiz.common.dto.CreateQuizDto;
import quiz.common.dto.QuizDto;
import quiz.common.dto.QuizInfoDto;
import quiz.common.dto.QuizQuestionsDto;
import quiz.common.dto.UpdateQuizDto;
import quiz.common.enums.AntiCheatPolicyType;
import quiz.common.enums.QuizStatus;
import quiz.common.enums.Role;
import quiz.common.protocol.ErrorCode;
import quiz.server.db.TransactionManager;
import quiz.server.domain.Classroom;
import quiz.server.domain.Quiz;
import quiz.server.domain.User;
import quiz.server.exception.ConflictException;
import quiz.server.exception.ForbiddenException;
import quiz.server.exception.NotFoundException;
import quiz.server.exception.UnauthorizedException;
import quiz.server.exception.ValidationException;
import quiz.server.network.UserSession;
import quiz.server.repository.ClassRepository;
import quiz.server.repository.QuestionRepository;
import quiz.server.repository.QuizRepository;
import quiz.server.repository.UserRepository;

import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
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
    private ClassRepository classRepo;
    private QuizService service;

    private final UserSession teacherSession = new UserSession(2, Role.TEACHER, "teacher1");
    private final UserSession otherTeacherSession = new UserSession(3, Role.TEACHER, "teacher2");

    @BeforeEach
    void setUp() {
        quizzes = mock(QuizRepository.class);
        questions = mock(QuestionRepository.class);
        users = mock(UserRepository.class);
        classRepo = mock(ClassRepository.class);
        TransactionManager passThrough = new TransactionManager() {
            @Override
            public <T> T executeInTransaction(Function<Connection, T> action) {
                return action.apply(null);
            }
        };
        service = new QuizService(quizzes, questions, users, passThrough, classRepo);
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
        when(quizzes.setQuestions(null, 1L, List.of(10L, 11L))).thenReturn(true);

        var dto = new QuizQuestionsDto(1, List.of(10L, 11L));
        Quiz updated = service.setQuestions(1, dto, TEACHER_ID);
        assertNotNull(updated);
        verify(quizzes).setQuestions(null, 1L, List.of(10L, 11L));
    }

    @Test
    void adminCanSetQuestionsOnAnyQuiz() {
        activeActor(ADMIN_ID, Role.ADMIN);
        Quiz draft = Quiz.restore(1, "Quiz 1", null, null, 15, 50.0, QuizStatus.DRAFT,
            null, AntiCheatPolicyType.WARN_THEN_AUTO_SUBMIT, 3, TEACHER_ID, TS, TS, null);
        when(quizzes.findById(null, 1L)).thenReturn(Optional.of(draft));
        when(questions.existsById(10L)).thenReturn(true);
        when(quizzes.setQuestions(null, 1L, List.of(10L))).thenReturn(true);

        var dto = new QuizQuestionsDto(1, List.of(10L));
        assertNotNull(service.setQuestions(1, dto, ADMIN_ID));
    }

    @Test
    void studentCannotSetQuestions() {
        activeActor(STUDENT_ID, Role.STUDENT);
        var dto = new QuizQuestionsDto(1, List.of(10L));
        assertThrows(ForbiddenException.class, () -> service.setQuestions(1, dto, STUDENT_ID));
    }

    @Test
    void inactiveActorRejected() {
        User inactive = User.restore(TEACHER_ID, "actor", "hash", "Actor", null, Role.TEACHER, false, null, TS);
        when(users.findById(null, TEACHER_ID)).thenReturn(Optional.of(inactive));
        when(users.findById(TEACHER_ID)).thenReturn(Optional.of(inactive));

        var dto = new QuizQuestionsDto(1, List.of(10L));
        assertThrows(UnauthorizedException.class, () -> service.setQuestions(1, dto, TEACHER_ID));
    }

    @Test
    void nonExistentActorRejected() {
        when(users.findById(null, 999L)).thenReturn(Optional.empty());
        when(users.findById(999L)).thenReturn(Optional.empty());

        var dto = new QuizQuestionsDto(1, List.of(10L));
        assertThrows(UnauthorizedException.class, () -> service.setQuestions(1, dto, 999L));
    }

    @Test
    void settingQuestionsOnNonDraftQuizFails() {
        activeActor(TEACHER_ID, Role.TEACHER);
        Quiz open = Quiz.restore(1, "Quiz 1", null, null, 15, 50.0, QuizStatus.OPEN,
            "PIN", AntiCheatPolicyType.WARN_THEN_AUTO_SUBMIT, 3, TEACHER_ID, TS, TS, null);
        when(quizzes.findById(null, 1L)).thenReturn(Optional.of(open));

        var dto = new QuizQuestionsDto(1, List.of(10L));
        assertThrows(ValidationException.class, () -> service.setQuestions(1, dto, TEACHER_ID));
    }

    @Test
    void settingNonExistentQuestionIdFails() {
        activeActor(TEACHER_ID, Role.TEACHER);
        Quiz draft = Quiz.restore(1, "Quiz 1", null, null, 15, 50.0, QuizStatus.DRAFT,
            null, AntiCheatPolicyType.WARN_THEN_AUTO_SUBMIT, 3, TEACHER_ID, TS, TS, null);
        when(quizzes.findById(null, 1L)).thenReturn(Optional.of(draft));
        when(questions.existsById(99L)).thenReturn(false);

        var dto = new QuizQuestionsDto(1, List.of(99L));
        assertThrows(NotFoundException.class, () -> service.setQuestions(1, dto, TEACHER_ID));
    }

    @Test
    void settingQuestionsOnMissingQuizFails() {
        activeActor(TEACHER_ID, Role.TEACHER);
        when(quizzes.findById(null, 999L)).thenReturn(Optional.empty());

        var dto = new QuizQuestionsDto(999, List.of(10L));
        assertThrows(NotFoundException.class, () -> service.setQuestions(999, dto, TEACHER_ID));
    }

    @Test
    void getQuizReturnsDetails() {
        activeActor(TEACHER_ID, Role.TEACHER);
        Quiz quiz = Quiz.restore(1, "Quiz 1", null, null, 15, 50.0, QuizStatus.DRAFT,
            null, AntiCheatPolicyType.WARN_THEN_AUTO_SUBMIT, 3, TEACHER_ID, TS, TS, null);
        when(quizzes.findById(1L)).thenReturn(Optional.of(quiz));

        assertEquals(1, service.getQuiz(1, TEACHER_ID).id());
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

    @Test
    void createQuizSetsDraftStatusAndReturnsDto() {
        CreateQuizDto dto = new CreateQuizDto("Grammar Test", "Desc", "Grammar", 30, 50.0,
            AntiCheatPolicyType.WARN_THEN_AUTO_SUBMIT, 3);
        Instant now = Instant.now();
        Quiz persisted = Quiz.restore(1, "Grammar Test", "Desc", "Grammar", 30, 50.0,
            QuizStatus.DRAFT, null, AntiCheatPolicyType.WARN_THEN_AUTO_SUBMIT, 3, 2, now, null, null);

        when(quizzes.save(any(Quiz.class))).thenReturn(persisted);

        QuizDto result = service.createQuiz(dto, teacherSession);
        assertEquals(1, result.id());
        assertEquals("Grammar Test", result.title());
        assertEquals(QuizStatus.DRAFT, result.status());
        assertNull(result.pin());
    }

    @Test
    void updateQuizSucceedsWhenDraft() {
        Instant now = Instant.now();
        Quiz existing = Quiz.restore(1, "Old Title", "Desc", "Grammar", 30, 50.0,
            QuizStatus.DRAFT, null, AntiCheatPolicyType.WARN_THEN_AUTO_SUBMIT, 3, 2, now, null, null);
        when(quizzes.findById(1)).thenReturn(Optional.of(existing));

        UpdateQuizDto updateDto = new UpdateQuizDto(1, "New Title", "New Desc", "Vocab", 45, 60.0,
            AntiCheatPolicyType.WARN_THEN_AUTO_SUBMIT, 3);

        QuizDto updated = service.updateQuiz(updateDto, teacherSession);
        assertEquals("New Title", updated.title());
        assertEquals(45, updated.durationMinutes());
        verify(quizzes).update(any(Quiz.class));
    }

    @Test
    void updateQuizThrowsConflictWhenNotDraft() {
        Instant now = Instant.now();
        Quiz openQuiz = Quiz.restore(1, "Title", "Desc", "Grammar", 30, 50.0,
            QuizStatus.OPEN, "123456", AntiCheatPolicyType.WARN_THEN_AUTO_SUBMIT, 3, 2, now, now, null);
        when(quizzes.findById(1)).thenReturn(Optional.of(openQuiz));

        UpdateQuizDto updateDto = new UpdateQuizDto(1, "New Title", "Desc", "Grammar", 30, 50.0,
            AntiCheatPolicyType.WARN_THEN_AUTO_SUBMIT, 3);

        assertThrows(ConflictException.class, () -> service.updateQuiz(updateDto, teacherSession));
    }

    @Test
    void openQuizThrowsValidationExceptionWhenNoQuestions() {
        Instant now = Instant.now();
        Quiz draftQuiz = Quiz.restore(1, "Title", "Desc", "Grammar", 30, 50.0,
            QuizStatus.DRAFT, null, AntiCheatPolicyType.WARN_THEN_AUTO_SUBMIT, 3, 2, now, null, null);
        when(quizzes.findById(1)).thenReturn(Optional.of(draftQuiz));
        when(quizzes.countQuestions(1)).thenReturn(0);

        assertThrows(ValidationException.class, () -> service.openQuiz(1, teacherSession));
    }

    @Test
    void openQuizGeneratesPinAndSetsStatusOpen() {
        Instant now = Instant.now();
        Quiz draftQuiz = Quiz.restore(1, "Title", "Desc", "Grammar", 30, 50.0,
            QuizStatus.DRAFT, null, AntiCheatPolicyType.WARN_THEN_AUTO_SUBMIT, 3, 2, now, null, null);
        when(quizzes.findById(1)).thenReturn(Optional.of(draftQuiz));
        when(quizzes.countQuestions(1)).thenReturn(10);

        QuizDto opened = service.openQuiz(1, teacherSession);
        assertEquals(QuizStatus.OPEN, opened.status());
        assertNotNull(opened.pin());
        assertEquals(6, opened.pin().length());
        assertNotNull(opened.openedAt());
        verify(quizzes).updateStatus(eq(1L), eq(QuizStatus.OPEN), anyString(), any(Instant.class), isNull());
    }

    @Test
    void closeQuizSetsStatusClosed() {
        Instant now = Instant.now();
        Quiz openQuiz = Quiz.restore(1, "Title", "Desc", "Grammar", 30, 50.0,
            QuizStatus.OPEN, "123456", AntiCheatPolicyType.WARN_THEN_AUTO_SUBMIT, 3, 2, now, now, null);
        when(quizzes.findById(1)).thenReturn(Optional.of(openQuiz));

        QuizDto closed = service.closeQuiz(1, teacherSession);
        assertEquals(QuizStatus.CLOSED, closed.status());
        assertNotNull(closed.closedAt());
        verify(quizzes).updateStatus(eq(1L), eq(QuizStatus.CLOSED), eq("123456"), eq(now), any(Instant.class));
    }

    @Test
    void assignQuizToClassSucceeds() {
        Instant now = Instant.now();
        Quiz quiz = Quiz.restore(1, "Title", "Desc", "Grammar", 30, 50.0,
            QuizStatus.OPEN, "123456", AntiCheatPolicyType.WARN_THEN_AUTO_SUBMIT, 3, 2, now, now, null);
        when(quizzes.findById(1)).thenReturn(Optional.of(quiz));
        when(classRepo.findById(10)).thenReturn(Optional.of(new Classroom(10, "Class 1", "Desc", 2, now)));

        service.assignQuiz(1, 10, teacherSession);
        verify(quizzes).assignToClass(1, 10, 2);
    }

    @Test
    void studentViewsAssignedQuizzes() {
        Instant now = Instant.now();
        Quiz assigned = Quiz.restore(1, "English Test", "Desc", "Grammar", 30, 50.0,
            QuizStatus.OPEN, "123456", AntiCheatPolicyType.WARN_THEN_AUTO_SUBMIT, 3, 2, now, now, null);
        when(quizzes.findAssignedQuizzesForStudent(5)).thenReturn(List.of(assigned));
        when(quizzes.countQuestions(1)).thenReturn(20);

        List<QuizInfoDto> list = service.listAssignedQuizzesForStudent(5);
        assertEquals(1, list.size());
        assertEquals("English Test", list.get(0).title());
        assertEquals(20, list.get(0).questionCount());
        assertNull(list.get(0).pin());
        assertTrue(list.get(0).pinRequired());
    }

    @Test
    void rejectsOperationFromUnauthorizedTeacher() {
        Instant now = Instant.now();
        Quiz quiz = Quiz.restore(1, "Title", "Desc", "Grammar", 30, 50.0,
            QuizStatus.DRAFT, null, AntiCheatPolicyType.WARN_THEN_AUTO_SUBMIT, 3, 2, now, null, null);
        when(quizzes.findById(1)).thenReturn(Optional.of(quiz));

        assertThrows(ForbiddenException.class, () -> service.openQuiz(1, otherTeacherSession));
    }

    @Test
    void listQuizResultsReturnsResultsForAuthorizedTeacher() {
        Instant now = Instant.now();
        Quiz quiz = Quiz.restore(1, "Title", "Desc", "Grammar", 30, 50.0,
            QuizStatus.OPEN, "123456", AntiCheatPolicyType.WARN_THEN_AUTO_SUBMIT, 3, 2, now, now, null);
        when(quizzes.findById(1)).thenReturn(Optional.of(quiz));
        when(quizzes.findResultsByQuizId(1)).thenReturn(List.of());

        var list = service.listQuizResults(1, teacherSession);
        assertNotNull(list);
        verify(quizzes).findResultsByQuizId(1);
    }

    @Test
    void getClassQuizResultsEnforcesRoleAndClassOwnership() {
        Instant now = Instant.now();
        Classroom ownClass = Classroom.restore(10, "ENG-01", "Desc", 2, now);
        Quiz assignedQuiz = Quiz.restore(1, "Quiz 1", "Desc", "Grammar", 30, 50.0,
            QuizStatus.OPEN, "123456", AntiCheatPolicyType.WARN_THEN_AUTO_SUBMIT, 3, 2, now, now, null);
        quiz.common.dto.ResultDto resultDto = new quiz.common.dto.ResultDto(
            100, 1, "Quiz 1", "Student A", 8.0, 8, 10, true, quiz.common.enums.SubmitReason.MANUAL, now
        );

        when(classRepo.findById(10)).thenReturn(Optional.of(ownClass));
        when(quizzes.findAssignedQuizzesForClass(10)).thenReturn(List.of(assignedQuiz));
        when(quizzes.findResultsByQuizAndClass(1, 10)).thenReturn(List.of(resultDto));

        var teacherResult = service.getClassQuizResults(10, teacherSession);
        assertEquals(1, teacherResult.quizzes().size());
        assertEquals(1, teacherResult.results().size());

        UserSession adminSession = new UserSession(1, Role.ADMIN, "admin");
        var adminResult = service.getClassQuizResults(10, adminSession);
        assertEquals(1, adminResult.quizzes().size());

        assertThrows(ForbiddenException.class, () -> service.getClassQuizResults(10, otherTeacherSession));
        assertThrows(ForbiddenException.class,
            () -> service.getClassQuizResults(10, new UserSession(5, Role.STUDENT, "student")));

        when(classRepo.findById(99)).thenReturn(Optional.empty());
        assertThrows(NotFoundException.class, () -> service.getClassQuizResults(99, adminSession));
    }

    @Test
    void getQuizDetailSetQuizQuestionsGetQuizInfoAndListQuizzesBySession() {
        Instant now = Instant.now();
        Quiz draft = Quiz.restore(1, "Quiz 1", "Desc", "Grammar", 30, 50.0,
            QuizStatus.DRAFT, null, AntiCheatPolicyType.WARN_THEN_AUTO_SUBMIT, 3, 2, now, null, null);
        quiz.server.domain.Question q1 = quiz.server.domain.Question.restore(
            10L, "Q1?", "A", "B", "C", "D", 'A', "Grammar", quiz.common.enums.Difficulty.EASY, true, 2L, now, now
        );

        when(quizzes.findById(1L)).thenReturn(Optional.of(draft));
        when(questions.findQuestionsByQuizId(1L)).thenReturn(List.of(q1));
        when(quizzes.findAssignedClassIds(1L)).thenReturn(List.of(10L));

        var detail = service.getQuizDetail(1L);
        assertEquals(1L, detail.quiz().id());
        assertEquals(1, detail.questions().size());
        assertEquals(List.of(10L), detail.assignedClassIds());

        when(questions.findByIds(List.of(10L))).thenReturn(List.of(q1));
        service.setQuizQuestions(1L, List.of(10L), teacherSession);
        verify(quizzes).setQuestions(1L, List.of(10L));

        when(questions.findByIds(List.of(10L, 99L))).thenReturn(List.of(q1));
        assertThrows(ValidationException.class, () -> service.setQuizQuestions(1L, List.of(10L, 99L), teacherSession));

        when(quizzes.countQuestions(1L)).thenReturn(5);
        QuizInfoDto info = service.getQuizInfo(1L, 5L);
        assertEquals(5, info.questionCount());

        when(quizzes.findByCreator(2L)).thenReturn(List.of(draft));
        assertEquals(1, service.listQuizzes(teacherSession).size());
        when(quizzes.findAll()).thenReturn(List.of(draft));
        assertEquals(1, service.listQuizzes(new UserSession(1, Role.ADMIN, "admin")).size());
    }
}
