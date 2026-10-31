package quiz.server.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import quiz.common.dto.AttemptStateDto;
import quiz.common.dto.ResultDto;
import quiz.common.enums.AntiCheatPolicyType;
import quiz.common.enums.AttemptStatus;
import quiz.common.enums.Difficulty;
import quiz.common.enums.QuizStatus;
import quiz.common.enums.Role;
import quiz.common.enums.SubmitReason;
import quiz.server.db.TransactionManager;
import quiz.server.domain.AnswerRecord;
import quiz.server.domain.Attempt;
import quiz.server.domain.ExamResult;
import quiz.server.domain.Question;
import quiz.server.domain.Quiz;
import quiz.server.domain.User;
import quiz.server.exception.ConflictException;
import quiz.server.exception.ForbiddenException;
import quiz.server.exception.NotFoundException;
import quiz.server.exception.ValidationException;
import quiz.server.repository.AttemptRepository;
import quiz.server.repository.QuestionRepository;
import quiz.server.repository.QuizRepository;
import quiz.server.repository.UserRepository;

import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("AttemptService Unit Tests")
class AttemptServiceTest {

    private AttemptRepository attemptRepo;
    private QuizRepository quizRepo;
    private QuestionRepository questionRepo;
    private UserRepository userRepo;
    private TransactionManager transactionManager;
    private GradingService gradingService;
    private Clock clock;
    private AttemptService service;

    private final Instant now = Instant.parse("2026-10-30T08:00:00Z");
    private final long studentId = 10L;
    private final long quizId = 20L;
    private final long attemptId = 30L;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        attemptRepo = mock(AttemptRepository.class);
        quizRepo = mock(QuizRepository.class);
        questionRepo = mock(QuestionRepository.class);
        userRepo = mock(UserRepository.class);
        transactionManager = mock(TransactionManager.class);
        gradingService = new GradingService();
        clock = Clock.fixed(now, ZoneOffset.UTC);

        when(transactionManager.executeWithException(any())).thenAnswer(inv -> {
            TransactionManager.TransactionCallback<?> cb = inv.getArgument(0);
            return cb.doInTransaction(mock(Connection.class));
        });
        when(transactionManager.executeInTransaction(any())).thenAnswer(inv -> {
            Function<Connection, ?> fn = inv.getArgument(0);
            return fn.apply(mock(Connection.class));
        });

        service = new AttemptService(attemptRepo, quizRepo, questionRepo, userRepo, transactionManager, gradingService, clock);
    }

    private Quiz openQuiz(String pin) {
        return Quiz.restore(quizId, "Java OOP", "Desc", "Java", 30, 50.0,
            QuizStatus.OPEN, pin, AntiCheatPolicyType.WARN_THEN_AUTO_SUBMIT, 3,
            2L, now.minusSeconds(3600), now.minusSeconds(1800), null);
    }

    private Quiz closedQuiz() {
        return Quiz.restore(quizId, "Java OOP", "Desc", "Java", 30, 50.0,
            QuizStatus.CLOSED, null, AntiCheatPolicyType.WARN_THEN_AUTO_SUBMIT, 3,
            2L, now.minusSeconds(3600), now.minusSeconds(1800), now.minusSeconds(60));
    }

    private Question sampleQuestion(long id, char correct) {
        return Question.restore(id, "Content " + id, "A1", "B1", "C1", "D1",
            correct, "OOP", Difficulty.MEDIUM, true, 2L, now, now);
    }

    @Test
    @DisplayName("TC-AT-01: Start attempt với mã PIN sai bị từ chối bằng ForbiddenException")
    void startAttempt_WrongPin_ThrowsForbidden() {
        Quiz quiz = openQuiz("123456");
        when(quizRepo.findById(quizId)).thenReturn(Optional.of(quiz));
        when(quizRepo.findAssignedQuizzesForStudent(studentId)).thenReturn(List.of(quiz));

        assertThrows(ForbiddenException.class, () -> service.startAttempt(quizId, "999999", studentId));
    }

    @Test
    @DisplayName("TC-AT-02: Start attempt khi quiz đã CLOSED hoặc không tồn tại")
    void startAttempt_ClosedOrMissingQuiz() {
        when(quizRepo.findById(999L)).thenReturn(Optional.empty());
        assertThrows(NotFoundException.class, () -> service.startAttempt(999L, "123456", studentId));

        when(quizRepo.findById(quizId)).thenReturn(Optional.of(closedQuiz()));
        assertThrows(ConflictException.class, () -> service.startAttempt(quizId, "123456", studentId));
    }

    @Test
    @DisplayName("TC-AT-03: Học viên không thuộc lớp được giao quiz bị từ chối")
    void startAttempt_StudentNotAssigned_ThrowsForbidden() {
        Quiz quiz = openQuiz("123456");
        when(quizRepo.findById(quizId)).thenReturn(Optional.of(quiz));
        when(quizRepo.findAssignedQuizzesForStudent(studentId)).thenReturn(List.of());

        assertThrows(ForbiddenException.class, () -> service.startAttempt(quizId, "123456", studentId));
    }

    @Test
    @DisplayName("TC-AT-04: Start lại khi đang có attempt IN_PROGRESS trả về state hiện tại và remainingSeconds chính xác")
    void startAttempt_ExistingInProgress_ReturnsCurrentState() {
        Quiz quiz = openQuiz("123456");
        Attempt existing = new Attempt(attemptId, quizId, studentId, AttemptStatus.IN_PROGRESS,
            now.minusSeconds(300), now.plusSeconds(1500), null, null);
        when(quizRepo.findById(quizId)).thenReturn(Optional.of(quiz));
        when(quizRepo.findAssignedQuizzesForStudent(studentId)).thenReturn(List.of(quiz));
        when(attemptRepo.findByStudentAndQuiz(studentId, quizId)).thenReturn(Optional.of(existing));
        when(questionRepo.findQuestionsByQuizId(quizId)).thenReturn(List.of(sampleQuestion(101L, 'A')));
        when(attemptRepo.getAnswers(any(), eq(attemptId))).thenReturn(Map.of(101L, 'B'));

        AttemptStateDto state = service.startAttempt(quizId, "123456", studentId);

        assertEquals(attemptId, state.attemptId());
        assertEquals(quizId, state.quizId());
        assertEquals(1500L, state.remainingSeconds());
        assertEquals(1, state.questions().size());
        assertEquals("B", state.savedAnswers().get(101L));

        // Tiếp tục được attempt IN_PROGRESS ngay cả khi giáo viên đã đóng đề (CLOSED) nhưng chưa quá deadline
        when(quizRepo.findById(quizId)).thenReturn(Optional.of(closedQuiz()));
        AttemptStateDto resumedAfterClose = service.startAttempt(quizId, null, studentId);
        assertEquals(attemptId, resumedAfterClose.attemptId());
        assertEquals(1500L, resumedAfterClose.remainingSeconds());
        verify(attemptRepo, never()).create(any(), any());
    }

    @Test
    @DisplayName("Start attempt mới thành công khi hợp lệ và từ chối nếu đã SUBMITTED trước đó")
    void startAttempt_CreatesNewOrRejectsSubmitted() {
        Quiz quiz = openQuiz("123456");
        when(quizRepo.findById(quizId)).thenReturn(Optional.of(quiz));
        when(quizRepo.findAssignedQuizzesForStudent(studentId)).thenReturn(List.of(quiz));

        Attempt submitted = new Attempt(attemptId, quizId, studentId, AttemptStatus.SUBMITTED,
            now.minusSeconds(600), now.plusSeconds(1200), now.minusSeconds(100), SubmitReason.MANUAL);
        when(attemptRepo.findByStudentAndQuiz(studentId, quizId)).thenReturn(Optional.of(submitted));
        assertThrows(ConflictException.class, () -> service.startAttempt(quizId, "123456", studentId));

        when(attemptRepo.findByStudentAndQuiz(studentId, quizId)).thenReturn(Optional.empty());
        Attempt created = new Attempt(attemptId, quizId, studentId, AttemptStatus.IN_PROGRESS,
            now, now.plusSeconds(1800), null, null);
        when(attemptRepo.create(any(), any())).thenReturn(created);
        when(questionRepo.findQuestionsByQuizId(quizId)).thenReturn(List.of(sampleQuestion(101L, 'A')));
        when(attemptRepo.getAnswers(any(), eq(attemptId))).thenReturn(Map.of());

        AttemptStateDto state = service.startAttempt(quizId, "123456", studentId);
        assertEquals(attemptId, state.attemptId());
        assertEquals(1800L, state.remainingSeconds());
        assertEquals(1, state.questions().size());
    }

    @Test
    @DisplayName("saveAnswer: lưu đáp án hợp lệ và kiểm tra các lỗi validation/quyền/quá hạn (TC-AT-08)")
    void saveAnswer_HandlesValidAndInvalidScenarios() {
        Attempt inProgress = new Attempt(attemptId, quizId, studentId, AttemptStatus.IN_PROGRESS,
            now.minusSeconds(60), now.plusSeconds(600), null, null);
        when(attemptRepo.findByIdForUpdate(any(), eq(attemptId))).thenReturn(Optional.of(inProgress));
        when(questionRepo.findQuestionsByQuizId(quizId)).thenReturn(List.of(sampleQuestion(101L, 'A')));

        // Happy path
        service.saveAnswer(attemptId, 101L, 'C', studentId);
        verify(attemptRepo).saveAnswer(any(), eq(new AnswerRecord(attemptId, 101L, 'C', now)));

        // Invalid option character
        assertThrows(ValidationException.class, () -> service.saveAnswer(attemptId, 101L, 'E', studentId));

        // Question not in quiz
        assertThrows(ValidationException.class, () -> service.saveAnswer(attemptId, 999L, 'A', studentId));

        // Wrong student
        assertThrows(ForbiddenException.class, () -> service.saveAnswer(attemptId, 101L, 'A', 999L));

        // Already submitted
        Attempt submitted = new Attempt(attemptId, quizId, studentId, AttemptStatus.SUBMITTED,
            now.minusSeconds(60), now.plusSeconds(600), now, SubmitReason.MANUAL);
        when(attemptRepo.findByIdForUpdate(any(), eq(attemptId))).thenReturn(Optional.of(submitted));
        assertThrows(ConflictException.class, () -> service.saveAnswer(attemptId, 101L, 'A', studentId));

        // TC-AT-08: Past deadline
        Attempt expired = new Attempt(attemptId, quizId, studentId, AttemptStatus.IN_PROGRESS,
            now.minusSeconds(1800), now, null, null);
        when(attemptRepo.findByIdForUpdate(any(), eq(attemptId))).thenReturn(Optional.of(expired));
        assertThrows(ConflictException.class, () -> service.saveAnswer(attemptId, 101L, 'A', studentId));
    }

    @Test
    @DisplayName("TC-AT-05 & TC-AT-06: submitAttempt chấm điểm đúng, lưu kết quả và trả về kết quả đã có nếu gọi lại")
    void submitAttempt_GradesAndHandlesExistingResult() {
        Attempt inProgress = new Attempt(attemptId, quizId, studentId, AttemptStatus.IN_PROGRESS,
            now.minusSeconds(60), now.plusSeconds(600), null, null);
        Quiz quiz = openQuiz("123456");
        User student = ServiceTestUsers.user(studentId, Role.STUDENT, true);

        when(attemptRepo.findByIdForUpdate(any(), eq(attemptId))).thenReturn(Optional.of(inProgress));
        when(attemptRepo.findResult(attemptId)).thenReturn(Optional.empty());
        when(quizRepo.findById(quizId)).thenReturn(Optional.of(quiz));
        when(userRepo.findById(studentId)).thenReturn(Optional.of(student));
        when(questionRepo.findQuestionsByQuizId(quizId)).thenReturn(List.of(
            sampleQuestion(101L, 'A'),
            sampleQuestion(102L, 'B')
        ));
        when(attemptRepo.getAnswers(any(), eq(attemptId))).thenReturn(Map.of(101L, 'A', 102L, 'C'));

        ResultDto dto = service.submitAttempt(attemptId, studentId, SubmitReason.MANUAL);

        assertEquals(attemptId, dto.attemptId());
        assertEquals(5.0, dto.score());
        assertEquals(1, dto.correctCount());
        assertEquals(2, dto.totalQuestions());
        assertTrue(dto.passed());
        assertEquals(SubmitReason.MANUAL, dto.submitReason());
        verify(attemptRepo).submit(any(), eq(attemptId), eq(now), eq(SubmitReason.MANUAL));
        verify(attemptRepo).saveResult(any(), any(ExamResult.class));

        // TC-AT-06: When result already exists, returns existing idempotently
        ExamResult existingResult = new ExamResult(attemptId, quizId, studentId, student.fullName(),
            5.0, 1, 2, true, SubmitReason.MANUAL, now);
        when(attemptRepo.findResult(attemptId)).thenReturn(Optional.of(existingResult));
        ResultDto secondDto = service.submitAttempt(attemptId, studentId, SubmitReason.MANUAL);
        assertEquals(5.0, secondDto.score());
    }

    @Test
    @DisplayName("TC-AT-07: finalizeExpiredAttempts tự động chấm điểm các attempt hết hạn với lý do TIMEOUT")
    void finalizeExpiredAttempts_SubmitsWithTimeoutReason() {
        Attempt expired = new Attempt(attemptId, quizId, studentId, AttemptStatus.IN_PROGRESS,
            now.minusSeconds(1900), now.minusSeconds(10), null, null);
        Quiz quiz = openQuiz("123456");
        User student = ServiceTestUsers.user(studentId, Role.STUDENT, true);

        when(attemptRepo.findExpiredInProgress(now)).thenReturn(List.of(expired));
        when(attemptRepo.findByIdForUpdate(any(), eq(attemptId))).thenReturn(Optional.of(expired));
        when(attemptRepo.findResult(attemptId)).thenReturn(Optional.empty());
        when(quizRepo.findById(quizId)).thenReturn(Optional.of(quiz));
        when(userRepo.findById(studentId)).thenReturn(Optional.of(student));
        when(questionRepo.findQuestionsByQuizId(quizId)).thenReturn(List.of(sampleQuestion(101L, 'A')));
        when(attemptRepo.getAnswers(any(), eq(attemptId))).thenReturn(Map.of(101L, 'A'));

        service.finalizeExpiredAttempts();

        verify(attemptRepo).submit(any(), eq(attemptId), eq(now), eq(SubmitReason.TIMEOUT));
        verify(attemptRepo).saveResult(any(), argThat(r -> r.submitReason() == SubmitReason.TIMEOUT && r.score() == 10.0));
    }

    @Test
    @DisplayName("getAttemptState, getResult và listHistory kiểm tra quyền sở hữu và hết giờ")
    void getAttemptState_getResult_listHistory() {
        Quiz quiz = openQuiz("123456");
        when(quizRepo.findById(quizId)).thenReturn(Optional.of(quiz));

        // getAttemptState when expired triggers auto-submit and throws ConflictException
        Attempt expired = new Attempt(attemptId, quizId, studentId, AttemptStatus.IN_PROGRESS,
            now.minusSeconds(1900), now.minusSeconds(5), null, null);
        when(attemptRepo.findById(attemptId)).thenReturn(Optional.of(expired));
        when(attemptRepo.findByIdForUpdate(any(), eq(attemptId))).thenReturn(Optional.of(expired));
        when(attemptRepo.findResult(attemptId)).thenReturn(Optional.of(
            new ExamResult(attemptId, quizId, studentId, "User 10", 0.0, 0, 1, false, SubmitReason.TIMEOUT, now)
        ));
        assertThrows(ConflictException.class, () -> service.getAttemptState(attemptId, studentId));

        // getResult checks ownership
        ExamResult r = new ExamResult(attemptId, quizId, studentId, "User 10", 8.0, 4, 5, true, SubmitReason.MANUAL, now);
        when(attemptRepo.findResult(attemptId)).thenReturn(Optional.of(r));
        assertEquals(8.0, service.getResult(attemptId, studentId).score());
        assertThrows(ForbiddenException.class, () -> service.getResult(attemptId, 999L));

        // listHistory
        when(attemptRepo.findResultsByStudent(studentId)).thenReturn(List.of(r));
        List<ResultDto> history = service.listHistory(studentId);
        assertEquals(1, history.size());
        assertEquals("Java OOP", history.get(0).quizTitle());
    }
}
