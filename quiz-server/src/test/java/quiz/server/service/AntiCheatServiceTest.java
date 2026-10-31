package quiz.server.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import quiz.common.dto.AntiCheatEventDto;
import quiz.common.dto.ReportAntiCheatDto;
import quiz.common.enums.*;
import quiz.common.protocol.MessageType;
import quiz.common.protocol.Response;
import quiz.server.db.TransactionManager;
import quiz.server.domain.Attempt;
import quiz.server.domain.Quiz;
import quiz.server.exception.ForbiddenException;
import quiz.server.exception.NotFoundException;
import quiz.server.network.ConnectionRegistry;
import quiz.server.network.UserSession;
import quiz.server.repository.AntiCheatRepository;
import quiz.server.repository.AttemptRepository;
import quiz.server.repository.QuizRepository;

import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("AntiCheatService Unit Tests")
class AntiCheatServiceTest {
    private AntiCheatRepository antiCheatRepo;
    private AttemptRepository attemptRepo;
    private QuizRepository quizRepo;
    private AttemptService attemptService;
    private ConnectionRegistry connectionRegistry;
    private TransactionManager transactionManager;
    private Clock clock;
    private AntiCheatService service;

    private final Instant now = Instant.parse("2026-10-26T00:00:00Z");
    private final long studentId = 100L;
    private final long attemptId = 1L;
    private final long quizId = 10L;

    @BeforeEach
    void setUp() {
        antiCheatRepo = mock(AntiCheatRepository.class);
        attemptRepo = mock(AttemptRepository.class);
        quizRepo = mock(QuizRepository.class);
        attemptService = mock(AttemptService.class);
        connectionRegistry = mock(ConnectionRegistry.class);
        transactionManager = mock(TransactionManager.class);
        clock = Clock.fixed(now, ZoneId.of("UTC"));

        when(attemptRepo.findByIdForUpdate(any(),eq(attemptId))).thenAnswer(i -> attemptRepo.findById(attemptId));
        when(quizRepo.findById(quizId)).thenReturn(Optional.of(createMockQuiz(AntiCheatPolicyType.LOG_ONLY,3)));
        // By default, executeWithException executes the callback directly with null or mock Connection
        when(transactionManager.executeWithException(any())).thenAnswer(invocation -> {
            TransactionManager.TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(Connection.class));
        });

        service = new AntiCheatService(
            antiCheatRepo, attemptRepo, quizRepo, attemptService,
            connectionRegistry, transactionManager, clock
        );
    }

    private Attempt createMockAttempt(AttemptStatus status) {
        return new Attempt(attemptId, quizId, studentId, status, now.minusSeconds(60), now.plusSeconds(3600), null, null);
    }

    private Quiz createMockQuiz(AntiCheatPolicyType policy, int maxViolations) {
        return new Quiz(quizId, "Quiz 1", "Desc", "Java", 60, 50.0, QuizStatus.OPEN, "PIN123",
            policy, maxViolations, 1L, now, now, null);
    }

    @Test
    @DisplayName("Ghi nhận vi phạm thành công và gửi cảnh báo khi số lần vi phạm chưa vượt ngưỡng")
    void reportEvent_WarnsStudentWhenBelowMaxViolations() {
        when(attemptRepo.findById(attemptId)).thenReturn(Optional.of(createMockAttempt(AttemptStatus.IN_PROGRESS)));
        when(quizRepo.findById(quizId)).thenReturn(Optional.of(createMockQuiz(AntiCheatPolicyType.WARN_THEN_AUTO_SUBMIT, 3)));
        when(antiCheatRepo.countViolations(any(), eq(attemptId))).thenReturn(1); // Lần vi phạm thứ 2

        ReportAntiCheatDto dto = new ReportAntiCheatDto(attemptId, AntiCheatEventType.FOCUS_LOST, "Alt+Tab", now);
        service.reportEvent(dto, studentId);

        verify(antiCheatRepo).recordEvent(any(), eq(attemptId), eq(AntiCheatEventType.FOCUS_LOST), eq("Alt+Tab"), eq(now), eq(now), eq(2), eq("WARNED"));
        verify(connectionRegistry).sendToUser(eq(studentId), argThat(r -> r.pushType() == MessageType.PUSH_ANTICHEAT_WARNING));
        verify(attemptService, never()).submitAttempt(anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("Tự động nộp bài và đình chỉ thi khi số lần vi phạm đạt hoặc vượt maxViolations")
    void reportEvent_AutoSubmitsWhenReachingMaxViolations() throws Exception {
        when(attemptRepo.findById(attemptId)).thenReturn(Optional.of(createMockAttempt(AttemptStatus.IN_PROGRESS)));
        when(quizRepo.findById(quizId)).thenReturn(Optional.of(createMockQuiz(AntiCheatPolicyType.WARN_THEN_AUTO_SUBMIT, 3)));
        when(antiCheatRepo.countViolations(any(), eq(attemptId))).thenReturn(2); // Lần vi phạm thứ 3 (đạt max 3)

        ReportAntiCheatDto dto = new ReportAntiCheatDto(attemptId, AntiCheatEventType.COPY_ATTEMPT, "Ctrl+C", now);
        service.reportEvent(dto, studentId);

        verify(antiCheatRepo).recordEvent(any(), eq(attemptId), eq(AntiCheatEventType.COPY_ATTEMPT), eq("Ctrl+C"), eq(now), eq(now), eq(3), eq("AUTO_SUBMITTED"));
        verify(attemptService).finishLocked(any(), eq(createMockAttempt(AttemptStatus.IN_PROGRESS)), eq(SubmitReason.ANTICHEAT));
        verify(connectionRegistry).sendToUser(eq(studentId), argThat(r -> r.pushType() == MessageType.PUSH_FORCE_SUBMITTED));
    }

    @Test
    @DisplayName("Chính sách LOG_ONLY chỉ ghi nhận nhật ký vào DB, không cảnh báo hoặc tự nộp bài")
    void reportEvent_PolicyLogOnly_RecordsWithoutWarningOrSubmit() {
        when(attemptRepo.findById(attemptId)).thenReturn(Optional.of(createMockAttempt(AttemptStatus.IN_PROGRESS)));
        when(quizRepo.findById(quizId)).thenReturn(Optional.of(createMockQuiz(AntiCheatPolicyType.LOG_ONLY, 3)));
        when(antiCheatRepo.countViolations(any(), eq(attemptId))).thenReturn(5);

        ReportAntiCheatDto dto = new ReportAntiCheatDto(attemptId, AntiCheatEventType.PASTE_ATTEMPT, "Ctrl+V", now);
        service.reportEvent(dto, studentId);

        verify(antiCheatRepo).recordEvent(any(), eq(attemptId), eq(AntiCheatEventType.PASTE_ATTEMPT), eq("Ctrl+V"), eq(now), eq(now), eq(6), eq("NONE"));
        verify(connectionRegistry, never()).sendToUser(anyLong(), any());
        verify(attemptService, never()).submitAttempt(anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("Bỏ qua sự kiện nếu Attempt không còn ở trạng thái IN_PROGRESS (Reliability NFR-09)")
    void reportEvent_IgnoredIfAttemptNotActive() {
        when(attemptRepo.findById(attemptId)).thenReturn(Optional.of(createMockAttempt(AttemptStatus.SUBMITTED)));

        ReportAntiCheatDto dto = new ReportAntiCheatDto(attemptId, AntiCheatEventType.FOCUS_LOST, "Lost focus", now);
        service.reportEvent(dto, studentId);

        verify(antiCheatRepo, never()).recordEvent(any(), anyLong(), any(), any(), any(), any(), anyInt(), any());
        verify(connectionRegistry, never()).sendToUser(anyLong(), any());
    }

    @Test
    @DisplayName("Từ chối nếu thí sinh báo cáo cho bài thi của thí sinh khác")
    void reportEvent_RejectsUnauthorizedStudent() {
        when(attemptRepo.findById(attemptId)).thenReturn(Optional.of(createMockAttempt(AttemptStatus.IN_PROGRESS)));

        ReportAntiCheatDto dto = new ReportAntiCheatDto(attemptId, AntiCheatEventType.FOCUS_LOST, "Lost focus", now);
        assertThrows(ForbiddenException.class, () -> service.reportEvent(dto, 999L));
    }

    @Test
    @DisplayName("Giáo viên sở hữu đề và Admin được phép xem danh sách vi phạm; giáo viên khác bị từ chối")
    void listAttemptEvents_AuthorizedForTeacherAndAdmin() {
        when(attemptRepo.findById(attemptId)).thenReturn(Optional.of(createMockAttempt(AttemptStatus.SUBMITTED)));
        when(quizRepo.findById(quizId)).thenReturn(Optional.of(createMockQuiz(AntiCheatPolicyType.WARN_THEN_AUTO_SUBMIT, 3)));
        List<AntiCheatEventDto> mockEvents = List.of(
            new AntiCheatEventDto(1, attemptId, AntiCheatEventType.FOCUS_LOST, "Alt+Tab", now, now, 1, "WARNED")
        );
        when(antiCheatRepo.findEventsByAttemptId(attemptId)).thenReturn(mockEvents);

        UserSession ownerTeacher = new UserSession(1L, Role.TEACHER, "owner");
        List<AntiCheatEventDto> result = service.listAttemptEvents(attemptId, ownerTeacher);
        assertEquals(1, result.size());
        assertEquals(AntiCheatEventType.FOCUS_LOST, result.get(0).eventType());

        UserSession otherTeacher = new UserSession(99L, Role.TEACHER, "other");
        assertThrows(ForbiddenException.class, () -> service.listAttemptEvents(attemptId, otherTeacher));

        UserSession adminSession = new UserSession(50L, Role.ADMIN, "admin");
        assertEquals(1, service.listAttemptEvents(attemptId, adminSession).size());
    }

    @Test
    @DisplayName("Học sinh không được phép xem nhật ký vi phạm qua API quản trị")
    void listAttemptEvents_ForbiddenForStudent() {
        UserSession studentSession = new UserSession(studentId, Role.STUDENT, "student");
        assertThrows(ForbiddenException.class, () -> service.listAttemptEvents(attemptId, studentSession));
    }

    @Test
    @DisplayName("Ghi nhận sự kiện vi phạm chạy ứng dụng cấm PROHIBITED_PROCESS thành công")
    void reportEvent_ProhibitedProcess() {
        when(attemptRepo.findById(attemptId)).thenReturn(Optional.of(createMockAttempt(AttemptStatus.IN_PROGRESS)));
        when(quizRepo.findById(quizId)).thenReturn(Optional.of(createMockQuiz(AntiCheatPolicyType.WARN_THEN_AUTO_SUBMIT, 3)));
        when(antiCheatRepo.countViolations(any(), eq(attemptId))).thenReturn(0);

        String detail = "Phát hiện ứng dụng cấm: Discord (PID: 1234)";
        ReportAntiCheatDto dto = new ReportAntiCheatDto(attemptId, AntiCheatEventType.PROHIBITED_PROCESS, detail, now);
        service.reportEvent(dto, studentId);

        verify(antiCheatRepo).recordEvent(any(), eq(attemptId), eq(AntiCheatEventType.PROHIBITED_PROCESS), eq(detail), eq(now), eq(now), eq(1), eq("WARNED"));
        verify(connectionRegistry).sendToUser(eq(studentId), argThat(r -> r.pushType() == MessageType.PUSH_ANTICHEAT_WARNING));
    }

    @Test
    @DisplayName("Cắt chuỗi detail vượt quá 255 ký tự, gán clientTime mặc định nếu null và kiểm tra NotFoundException")
    void reportEvent_TruncatesLongDetailAndHandlesMissingAttempt() {
        when(attemptRepo.findById(attemptId)).thenReturn(Optional.of(createMockAttempt(AttemptStatus.IN_PROGRESS)));
        when(quizRepo.findById(quizId)).thenReturn(Optional.of(createMockQuiz(AntiCheatPolicyType.LOG_ONLY, 3)));
        when(antiCheatRepo.countViolations(any(), eq(attemptId))).thenReturn(0);

        String overlongDetail = "X".repeat(300);
        ReportAntiCheatDto dto = new ReportAntiCheatDto(attemptId, AntiCheatEventType.FOCUS_LOST, overlongDetail, null);
        service.reportEvent(dto, studentId);

        verify(antiCheatRepo).recordEvent(
            any(), eq(attemptId), eq(AntiCheatEventType.FOCUS_LOST),
            argThat(d -> d != null && d.length() == 255),
            eq(now), eq(now), eq(1), eq("NONE")
        );

        when(attemptRepo.findById(999L)).thenReturn(Optional.empty());
        assertThrows(NotFoundException.class, () -> service.reportEvent(
            new ReportAntiCheatDto(999L, AntiCheatEventType.FOCUS_LOST, "detail", now), studentId
        ));
        assertThrows(NotFoundException.class, () -> service.listAttemptEvents(
            999L, new UserSession(2L, Role.TEACHER, "teacher")
        ));
    }
}
