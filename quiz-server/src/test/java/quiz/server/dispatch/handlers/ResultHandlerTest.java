package quiz.server.dispatch.handlers;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import quiz.common.dto.ResultDto;
import quiz.common.enums.Role;
import quiz.common.enums.SubmitReason;
import quiz.common.protocol.ErrorCode;
import quiz.common.protocol.MessageType;
import quiz.common.protocol.Request;
import quiz.common.protocol.Response;
import quiz.server.exception.QuizException;
import quiz.server.exception.UnauthorizedException;
import quiz.server.network.UserSession;
import quiz.server.service.AttemptService;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("ResultHandler Unit Tests")
class ResultHandlerTest {

    private AttemptService service;
    private ResultHandler handler;
    private final UserSession studentSession = new UserSession(10L, Role.STUDENT, "student1");

    @BeforeEach
    void setUp() {
        service = mock(AttemptService.class);
        handler = new ResultHandler(service);
    }

    @Test
    @DisplayName("supportedTypes và allowedRoles chỉ cho phép STUDENT trên GET_MY_RESULT và LIST_MY_HISTORY")
    void supportedTypesAndRoles() {
        assertEquals(Set.of(MessageType.GET_MY_RESULT, MessageType.LIST_MY_HISTORY), handler.supportedTypes());
        assertEquals(Set.of(Role.STUDENT), handler.allowedRoles(MessageType.GET_MY_RESULT));
        assertEquals(Set.of(Role.STUDENT), handler.allowedRoles(MessageType.LIST_MY_HISTORY));
        assertThrows(IllegalArgumentException.class, () -> handler.allowedRoles(MessageType.LOGIN));
    }

    @Test
    @DisplayName("Xử lý GET_MY_RESULT và LIST_MY_HISTORY thành công")
    void handleGetMyResultAndListMyHistory() {
        ResultDto dto = new ResultDto(1L, 2L, "Java Quiz", "Student", 8.0, 8, 10, true, SubmitReason.MANUAL, Instant.now());
        when(service.getResult(1L, 10L)).thenReturn(dto);
        when(service.listHistory(10L)).thenReturn(List.of(dto));

        Response res1 = handler.handle(new Request(10L, MessageType.GET_MY_RESULT, "t", 1L), studentSession);
        assertTrue(res1.success());
        assertEquals(dto, res1.payload());

        Response res2 = handler.handle(new Request(11L, MessageType.LIST_MY_HISTORY, "t", null), studentSession);
        assertTrue(res2.success());
        List<?> list = assertInstanceOf(List.class, res2.payload());
        assertEquals(1, list.size());
        assertEquals(dto, list.get(0));
    }

    @Test
    @DisplayName("Từ chối khi session null, LIST_MY_HISTORY có payload thừa, hoặc GET_MY_RESULT sai kiểu")
    void rejectsInvalidSessionOrPayload() {
        assertThrows(UnauthorizedException.class, () -> handler.handle(
            new Request(1L, MessageType.LIST_MY_HISTORY, null, null),
            null
        ));

        QuizException extraPayload = assertThrows(QuizException.class, () -> handler.handle(
            new Request(2L, MessageType.LIST_MY_HISTORY, "t", 1L),
            studentSession
        ));
        assertEquals(ErrorCode.INVALID_REQUEST, extraPayload.getErrorCode());

        QuizException wrongPayload = assertThrows(QuizException.class, () -> handler.handle(
            new Request(3L, MessageType.GET_MY_RESULT, "t", "not-a-long"),
            studentSession
        ));
        assertEquals(ErrorCode.INVALID_REQUEST, wrongPayload.getErrorCode());

        QuizException unsupported = assertThrows(QuizException.class, () -> handler.handle(
            new Request(4L, MessageType.LOGIN, "t", null),
            studentSession
        ));
        assertEquals(ErrorCode.INVALID_REQUEST, unsupported.getErrorCode());
    }
}
