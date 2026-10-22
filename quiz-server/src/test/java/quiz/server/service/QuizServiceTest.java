package quiz.server.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import quiz.common.dto.CreateQuizDto;
import quiz.common.dto.QuizDto;
import quiz.common.dto.QuizInfoDto;
import quiz.common.dto.UpdateQuizDto;
import quiz.common.enums.AntiCheatPolicyType;
import quiz.common.enums.QuizStatus;
import quiz.common.enums.Role;
import quiz.server.domain.Quiz;
import quiz.server.exception.ConflictException;
import quiz.server.exception.ForbiddenException;
import quiz.server.exception.ValidationException;
import quiz.server.network.UserSession;
import quiz.server.repository.ClassRepository;
import quiz.server.repository.QuestionRepository;
import quiz.server.repository.QuizRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class QuizServiceTest {
    private QuizRepository quizRepo;
    private QuestionRepository questionRepo;
    private ClassRepository classRepo;
    private QuizService service;
    private final UserSession teacherSession = new UserSession(2, Role.TEACHER, "teacher1");
    private final UserSession otherTeacherSession = new UserSession(3, Role.TEACHER, "teacher2");

    @BeforeEach
    void setUp() {
        quizRepo = mock(QuizRepository.class);
        questionRepo = mock(QuestionRepository.class);
        classRepo = mock(ClassRepository.class);
        service = new QuizService(quizRepo, questionRepo, classRepo);
    }

    @Test
    void createQuizSetsDraftStatusAndReturnsDto() {
        CreateQuizDto dto = new CreateQuizDto("Grammar Test", "Desc", "Grammar", 30, 50.0,
            AntiCheatPolicyType.WARN_THEN_AUTO_SUBMIT, 3);
        Instant now = Instant.now();
        Quiz persisted = Quiz.restore(1, "Grammar Test", "Desc", "Grammar", 30, 50.0,
            QuizStatus.DRAFT, null, AntiCheatPolicyType.WARN_THEN_AUTO_SUBMIT, 3, 2, now, null, null);

        when(quizRepo.save(any(Quiz.class))).thenReturn(persisted);

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
        when(quizRepo.findById(1)).thenReturn(Optional.of(existing));

        UpdateQuizDto updateDto = new UpdateQuizDto(1, "New Title", "New Desc", "Vocab", 45, 60.0,
            AntiCheatPolicyType.WARN_THEN_AUTO_SUBMIT, 3);

        QuizDto updated = service.updateQuiz(updateDto, teacherSession);
        assertEquals("New Title", updated.title());
        assertEquals(45, updated.durationMinutes());
        verify(quizRepo).update(any(Quiz.class));
    }

    @Test
    void updateQuizThrowsConflictWhenNotDraft() { // TC-QZ-02
        Instant now = Instant.now();
        Quiz openQuiz = Quiz.restore(1, "Title", "Desc", "Grammar", 30, 50.0,
            QuizStatus.OPEN, "123456", AntiCheatPolicyType.WARN_THEN_AUTO_SUBMIT, 3, 2, now, now, null);
        when(quizRepo.findById(1)).thenReturn(Optional.of(openQuiz));

        UpdateQuizDto updateDto = new UpdateQuizDto(1, "New Title", "Desc", "Grammar", 30, 50.0,
            AntiCheatPolicyType.WARN_THEN_AUTO_SUBMIT, 3);

        assertThrows(ConflictException.class, () -> service.updateQuiz(updateDto, teacherSession));
    }

    @Test
    void openQuizThrowsValidationExceptionWhenNoQuestions() { // TC-QZ-01
        Instant now = Instant.now();
        Quiz draftQuiz = Quiz.restore(1, "Title", "Desc", "Grammar", 30, 50.0,
            QuizStatus.DRAFT, null, AntiCheatPolicyType.WARN_THEN_AUTO_SUBMIT, 3, 2, now, null, null);
        when(quizRepo.findById(1)).thenReturn(Optional.of(draftQuiz));
        when(quizRepo.countQuestions(1)).thenReturn(0);

        assertThrows(ValidationException.class, () -> service.openQuiz(1, teacherSession));
    }

    @Test
    void openQuizGeneratesPinAndSetsStatusOpen() { // FR-A04, FR-A09
        Instant now = Instant.now();
        Quiz draftQuiz = Quiz.restore(1, "Title", "Desc", "Grammar", 30, 50.0,
            QuizStatus.DRAFT, null, AntiCheatPolicyType.WARN_THEN_AUTO_SUBMIT, 3, 2, now, null, null);
        when(quizRepo.findById(1)).thenReturn(Optional.of(draftQuiz));
        when(quizRepo.countQuestions(1)).thenReturn(10);

        QuizDto opened = service.openQuiz(1, teacherSession);
        assertEquals(QuizStatus.OPEN, opened.status());
        assertNotNull(opened.pin());
        assertEquals(6, opened.pin().length());
        assertNotNull(opened.openedAt());
        verify(quizRepo).updateStatus(eq(1L), eq(QuizStatus.OPEN), anyString(), any(Instant.class), isNull());
    }

    @Test
    void closeQuizSetsStatusClosed() { // FR-A04
        Instant now = Instant.now();
        Quiz openQuiz = Quiz.restore(1, "Title", "Desc", "Grammar", 30, 50.0,
            QuizStatus.OPEN, "123456", AntiCheatPolicyType.WARN_THEN_AUTO_SUBMIT, 3, 2, now, now, null);
        when(quizRepo.findById(1)).thenReturn(Optional.of(openQuiz));

        QuizDto closed = service.closeQuiz(1, teacherSession);
        assertEquals(QuizStatus.CLOSED, closed.status());
        assertNotNull(closed.closedAt());
        verify(quizRepo).updateStatus(eq(1L), eq(QuizStatus.CLOSED), eq("123456"), eq(now), any(Instant.class));
    }

    @Test
    void assignQuizToClassSucceeds() { // FR-A08
        Instant now = Instant.now();
        Quiz quiz = Quiz.restore(1, "Title", "Desc", "Grammar", 30, 50.0,
            QuizStatus.OPEN, "123456", AntiCheatPolicyType.WARN_THEN_AUTO_SUBMIT, 3, 2, now, now, null);
        when(quizRepo.findById(1)).thenReturn(Optional.of(quiz));
        when(classRepo.findById(10)).thenReturn(Optional.of(new quiz.server.domain.Classroom(10, "Class 1", "Desc", 2, now)));

        service.assignQuiz(1, 10, teacherSession);
        verify(quizRepo).assignToClass(1, 10, 2);
    }

    @Test
    void studentViewsAssignedQuizzes() { // FR-S02
        Instant now = Instant.now();
        Quiz assigned = Quiz.restore(1, "English Test", "Desc", "Grammar", 30, 50.0,
            QuizStatus.OPEN, "123456", AntiCheatPolicyType.WARN_THEN_AUTO_SUBMIT, 3, 2, now, now, null);
        when(quizRepo.findAssignedQuizzesForStudent(5)).thenReturn(List.of(assigned));
        when(quizRepo.countQuestions(1)).thenReturn(20);

        List<QuizInfoDto> list = service.listAssignedQuizzesForStudent(5);
        assertEquals(1, list.size());
        assertEquals("English Test", list.get(0).title());
        assertEquals(20, list.get(0).questionCount());
        assertEquals("123456", list.get(0).pin());
    }

    @Test
    void rejectsOperationFromUnauthorizedTeacher() {
        Instant now = Instant.now();
        Quiz quiz = Quiz.restore(1, "Title", "Desc", "Grammar", 30, 50.0,
            QuizStatus.DRAFT, null, AntiCheatPolicyType.WARN_THEN_AUTO_SUBMIT, 3, 2, now, null, null);
        when(quizRepo.findById(1)).thenReturn(Optional.of(quiz));

        assertThrows(ForbiddenException.class, () -> service.openQuiz(1, otherTeacherSession));
    }
}
