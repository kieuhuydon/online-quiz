package quiz.server.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import quiz.common.dto.AntiCheatEventDto;
import quiz.common.dto.AntiCheatWarningDto;
import quiz.common.dto.ReportAntiCheatDto;
import quiz.common.enums.AntiCheatEventType;
import quiz.common.enums.AntiCheatPolicyType;
import quiz.common.enums.Role;
import quiz.common.protocol.ErrorCode;
import quiz.common.protocol.MessageType;
import quiz.common.protocol.Response;
import quiz.server.db.TransactionManager;
import quiz.server.domain.anticheat.AntiCheatRecord;
import quiz.server.domain.anticheat.AttemptMonitoringInfo;
import quiz.server.exception.QuizException;
import quiz.server.network.ConnectionRegistry;
import quiz.server.network.UserSession;
import quiz.server.repository.AntiCheatRepository;

import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AntiCheatServiceTest {

    private AntiCheatRepository repository;
    private TransactionManager txManager;
    private ConnectionRegistry connections;
    private AntiCheatService service;

    @BeforeEach
    void setUp() {
        repository = mock(AntiCheatRepository.class);
        txManager = mock(TransactionManager.class);
        connections = mock(ConnectionRegistry.class);

        // Make txManager execute the function immediately with a dummy connection
        Connection dummyConn = mock(Connection.class);
        when(txManager.executeInTransaction(any())).thenAnswer(invocation -> {
            Function<Connection, Object> func = invocation.getArgument(0);
            return func.apply(dummyConn);
        });

        service = new AntiCheatService(repository, txManager, connections);
    }

    @Test
    @DisplayName("Report violation below maxViolations sends warning push notification")
    void reportViolationSendsWarning() {
        long studentId = 10L;
        long attemptId = 100L;
        ReportAntiCheatDto dto = new ReportAntiCheatDto(attemptId, AntiCheatEventType.FOCUS_LOST, "Lost window focus", Instant.now());

        AttemptMonitoringInfo info = new AttemptMonitoringInfo(
            attemptId, 1L, studentId, "IN_PROGRESS", AntiCheatPolicyType.WARN_THEN_AUTO_SUBMIT, 3
        );
        when(repository.findAttemptForMonitoring(eq(attemptId), any())).thenReturn(Optional.of(info));
        when(repository.countViolations(eq(attemptId), any())).thenReturn(0);

        service.reportViolation(studentId, dto);

        verify(repository).save(any(AntiCheatRecord.class), any());
        verify(repository, never()).autoSubmitAttempt(anyLong(), any());

        ArgumentCaptor<Response> pushCaptor = ArgumentCaptor.forClass(Response.class);
        verify(connections).sendToUser(eq(studentId), pushCaptor.capture());
        Response push = pushCaptor.getValue();
        assertEquals(MessageType.PUSH_ANTICHEAT_WARNING, push.pushType());
        assertTrue(push.payload() instanceof AntiCheatWarningDto);
        AntiCheatWarningDto warning = (AntiCheatWarningDto) push.payload();
        assertEquals(1, warning.violationCount());
        assertEquals(3, warning.maxViolations());
    }

    @Test
    @DisplayName("Report violation reaching maxViolations auto-submits attempt and sends force-submitted push")
    void reportViolationReachingMaxAutoSubmits() {
        long studentId = 10L;
        long attemptId = 100L;
        ReportAntiCheatDto dto = new ReportAntiCheatDto(attemptId, AntiCheatEventType.COPY_ATTEMPT, "Tried Ctrl+C", Instant.now());

        AttemptMonitoringInfo info = new AttemptMonitoringInfo(
            attemptId, 1L, studentId, "IN_PROGRESS", AntiCheatPolicyType.WARN_THEN_AUTO_SUBMIT, 3
        );
        when(repository.findAttemptForMonitoring(eq(attemptId), any())).thenReturn(Optional.of(info));
        when(repository.countViolations(eq(attemptId), any())).thenReturn(2); // 3rd violation

        service.reportViolation(studentId, dto);

        verify(repository).save(any(AntiCheatRecord.class), any());
        verify(repository).autoSubmitAttempt(eq(attemptId), any());

        ArgumentCaptor<Response> pushCaptor = ArgumentCaptor.forClass(Response.class);
        verify(connections).sendToUser(eq(studentId), pushCaptor.capture());
        Response push = pushCaptor.getValue();
        assertEquals(MessageType.PUSH_FORCE_SUBMITTED, push.pushType());
    }

    @Test
    @DisplayName("LOG_ONLY policy records event with NONE action and does not send push warning")
    void logOnlyPolicyRecordsWithoutPush() {
        long studentId = 10L;
        long attemptId = 100L;
        ReportAntiCheatDto dto = new ReportAntiCheatDto(attemptId, AntiCheatEventType.PASTE_ATTEMPT, "Paste attempt", Instant.now());

        AttemptMonitoringInfo info = new AttemptMonitoringInfo(
            attemptId, 1L, studentId, "IN_PROGRESS", AntiCheatPolicyType.LOG_ONLY, 3
        );
        when(repository.findAttemptForMonitoring(eq(attemptId), any())).thenReturn(Optional.of(info));
        when(repository.countViolations(eq(attemptId), any())).thenReturn(0);

        service.reportViolation(studentId, dto);

        ArgumentCaptor<AntiCheatRecord> recordCaptor = ArgumentCaptor.forClass(AntiCheatRecord.class);
        verify(repository).save(recordCaptor.capture(), any());
        assertEquals("NONE", recordCaptor.getValue().actionTaken());
        verify(connections, never()).sendToUser(anyLong(), any());
    }

    @Test
    @DisplayName("Already submitted attempt ignores anti-cheat event quietly")
    void alreadySubmittedAttemptIgnoresEventQuietly() {
        long studentId = 10L;
        long attemptId = 100L;
        ReportAntiCheatDto dto = new ReportAntiCheatDto(attemptId, AntiCheatEventType.FOCUS_LOST, "Lost focus", Instant.now());

        AttemptMonitoringInfo info = new AttemptMonitoringInfo(
            attemptId, 1L, studentId, "SUBMITTED", AntiCheatPolicyType.WARN_THEN_AUTO_SUBMIT, 3
        );
        when(repository.findAttemptForMonitoring(eq(attemptId), any())).thenReturn(Optional.of(info));

        assertDoesNotThrow(() -> service.reportViolation(studentId, dto));
        verify(repository, never()).save(any(), any());
        verify(connections, never()).sendToUser(anyLong(), any());
    }

    @Test
    @DisplayName("Teacher can list attempt anti-cheat events for evidence")
    void teacherCanListAttemptEvents() {
        long attemptId = 100L;
        UserSession teacherSession = new UserSession(2L, Role.TEACHER, "teacher1");

        AntiCheatRecord rec = new AntiCheatRecord(
            1L, attemptId, AntiCheatEventType.FOCUS_LOST, "Lost focus", Instant.now(), Instant.now(), 1, "WARNED"
        );
        when(repository.findByAttemptId(attemptId)).thenReturn(List.of(rec));

        List<AntiCheatEventDto> events = service.listAttemptEvents(attemptId, teacherSession);
        assertEquals(1, events.size());
        assertEquals(AntiCheatEventType.FOCUS_LOST, events.get(0).eventType());
    }

    @Test
    @DisplayName("Student cannot list attempt anti-cheat events (forbidden)")
    void studentCannotListAttemptEvents() {
        long attemptId = 100L;
        UserSession studentSession = new UserSession(10L, Role.STUDENT, "student1");

        QuizException ex = assertThrows(QuizException.class, () -> service.listAttemptEvents(attemptId, studentSession));
        assertEquals(ErrorCode.FORBIDDEN, ex.getErrorCode());
    }

    @Test
    @DisplayName("NFR-09 Reliability: Database exception does not escape reportViolation")
    void reliabilitySuppressesException() {
        long studentId = 10L;
        long attemptId = 100L;
        ReportAntiCheatDto dto = new ReportAntiCheatDto(attemptId, AntiCheatEventType.FOCUS_LOST, "Lost focus", Instant.now());

        doThrow(new RuntimeException("DB down")).when(txManager).executeInTransaction(any());

        assertDoesNotThrow(() -> service.reportViolation(studentId, dto));
    }
}
