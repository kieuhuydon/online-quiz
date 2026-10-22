package quiz.server.dispatch.handlers;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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

    @BeforeEach
    void setUp() {
        service = mock(AntiCheatService.class);
        handler = new AntiCheatHandler(service);
    }

    @Test
    @DisplayName("Supported types and allowed roles conform to RBAC specification")
    void supportedTypesAndRoles() {
        assertTrue(handler.supportedTypes().contains(MessageType.REPORT_ANTICHEAT_EVENT));
        assertTrue(handler.supportedTypes().contains(MessageType.LIST_ATTEMPT_EVENTS));

        assertEquals(Set.of(Role.STUDENT), handler.allowedRoles(MessageType.REPORT_ANTICHEAT_EVENT));
        assertEquals(Set.of(Role.TEACHER, Role.ADMIN), handler.allowedRoles(MessageType.LIST_ATTEMPT_EVENTS));
    }

    @Test
    @DisplayName("Handles REPORT_ANTICHEAT_EVENT successfully")
    void handlesReportAntiCheat() {
        UserSession session = new UserSession(5L, Role.STUDENT, "student1");
        ReportAntiCheatDto dto = new ReportAntiCheatDto(100L, AntiCheatEventType.FOCUS_LOST, "Lost focus", Instant.now());
        Request req = new Request(123L, MessageType.REPORT_ANTICHEAT_EVENT, "tok", dto);

        Response resp = handler.handle(req, session);

        assertTrue(resp.success());
        assertEquals(123L, resp.requestId());
        verify(service).reportViolation(5L, dto);
    }

    @Test
    @DisplayName("Handles LIST_ATTEMPT_EVENTS successfully")
    void handlesListAttemptEvents() {
        UserSession session = new UserSession(2L, Role.TEACHER, "teacher1");
        Request req = new Request(456L, MessageType.LIST_ATTEMPT_EVENTS, "tok", 100L);

        AntiCheatEventDto eventDto = new AntiCheatEventDto(
            1L, 100L, AntiCheatEventType.FOCUS_LOST, "Detail", Instant.now(), Instant.now(), 1, "WARNED"
        );
        when(service.listAttemptEvents(100L, session)).thenReturn(List.of(eventDto));

        Response resp = handler.handle(req, session);

        assertTrue(resp.success());
        assertEquals(456L, resp.requestId());
        assertNotNull(resp.payload());
        assertTrue(resp.payload() instanceof List<?>);
    }
}

