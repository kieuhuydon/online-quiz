package quiz.server.dispatch.handlers;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import quiz.common.dto.AntiCheatEventDto;
import quiz.common.dto.ReportAntiCheatDto;
import quiz.common.enums.AntiCheatEventType;
import quiz.common.enums.Role;
import quiz.common.protocol.MessageType;
import quiz.common.protocol.Request;
import quiz.common.protocol.Response;
import quiz.server.network.UserSession;
import quiz.server.service.AntiCheatService;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AntiCheatHandlerTest {
    private AntiCheatService service;
    private AntiCheatHandler handler;

    private final UserSession studentSession = new UserSession(10L, Role.STUDENT, "student1");
    private final UserSession teacherSession = new UserSession(2L, Role.TEACHER, "teacher1");

    @BeforeEach
    void setUp() {
        service = mock(AntiCheatService.class);
        handler = new AntiCheatHandler(service);
    }

    @Test
    void supportedTypesAndRoles() {
        assertEquals(Set.of(MessageType.REPORT_ANTICHEAT_EVENT, MessageType.LIST_ATTEMPT_EVENTS), handler.supportedTypes());
        assertEquals(Set.of(Role.STUDENT), handler.allowedRoles(MessageType.REPORT_ANTICHEAT_EVENT));
        assertEquals(Set.of(Role.TEACHER, Role.ADMIN), handler.allowedRoles(MessageType.LIST_ATTEMPT_EVENTS));
    }

    @Test
    void handleReportAntiCheatEvent() {
        ReportAntiCheatDto dto = new ReportAntiCheatDto(1L, AntiCheatEventType.COPY_ATTEMPT, "Ctrl+C", Instant.now());
        Request request = new Request(123L, MessageType.REPORT_ANTICHEAT_EVENT, "token", dto);

        Response response = handler.handle(request, studentSession);

        assertTrue(response.success());
        assertEquals(123L, response.requestId());
        verify(service).reportEvent(eq(dto), eq(10L));
    }

    @Test
    void handleListAttemptEvents() {
        Request request = new Request(456L, MessageType.LIST_ATTEMPT_EVENTS, "token", 1L);
        when(service.listAttemptEvents(1L, teacherSession)).thenReturn(List.of(
            new AntiCheatEventDto(1L, 1L, AntiCheatEventType.FOCUS_LOST, "detail", Instant.now(), Instant.now(), 1, "WARNED")
        ));

        Response response = handler.handle(request, teacherSession);

        assertTrue(response.success());
        assertEquals(456L, response.requestId());
        assertNotNull(response.payload());
    }
}
