package quiz.server.dispatch.handlers;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import quiz.common.dto.AttemptStateDto;
import quiz.common.dto.ResultDto;
import quiz.common.dto.SaveAnswerDto;
import quiz.common.dto.StartAttemptRequestDto;
import quiz.common.dto.SubmitAttemptDto;
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
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("AttemptHandler Unit Tests")
class AttemptHandlerTest {

    private AttemptService service;
    private AttemptHandler handler;
    private final UserSession studentSession = new UserSession(10L, Role.STUDENT, "student1");

    @BeforeEach
    void setUp() {
        service = mock(AttemptService.class);
        handler = new AttemptHandler(service);
    }

    @Test
    @DisplayName("supportedTypes và allowedRoles chỉ cho phép STUDENT trên 4 lệnh Attempt")
    void supportedTypesAndRoles() {
        assertEquals(Set.of(
            MessageType.START_ATTEMPT,
            MessageType.SAVE_ANSWER,
            MessageType.SUBMIT_ATTEMPT,
            MessageType.GET_ATTEMPT_STATE
        ), handler.supportedTypes());

        for (MessageType type : handler.supportedTypes()) {
            assertEquals(Set.of(Role.STUDENT), handler.allowedRoles(type));
        }
        assertThrows(IllegalArgumentException.class, () -> handler.allowedRoles(MessageType.LOGIN));
    }

    @Test
    @DisplayName("Xử lý START_ATTEMPT, SAVE_ANSWER, SUBMIT_ATTEMPT, GET_ATTEMPT_STATE thành công")
    void handleAllAttemptCommands() {
        AttemptStateDto stateDto = new AttemptStateDto(1L, 2L, "Quiz", 1800L, List.of(), Map.of());
        when(service.startAttempt(2L, "123456", 10L)).thenReturn(stateDto);

        Response startResp = handler.handle(
            new Request(1L, MessageType.START_ATTEMPT, "t", new StartAttemptRequestDto(2L, "123456")),
            studentSession
        );
        assertTrue(startResp.success());
        assertEquals(stateDto, startResp.payload());

        Response saveResp = handler.handle(
            new Request(2L, MessageType.SAVE_ANSWER, "t", new SaveAnswerDto(1L, 100L, 'A')),
            studentSession
        );
        assertTrue(saveResp.success());
        assertNull(saveResp.payload());
        verify(service).saveAnswer(1L, 100L, 'A', 10L);

        ResultDto resultDto = new ResultDto(1L, 2L, "Quiz", "Student", 9.0, 9, 10, true, SubmitReason.MANUAL, Instant.now());
        when(service.submitAttempt(1L, 10L, SubmitReason.MANUAL)).thenReturn(resultDto);
        Response submitResp = handler.handle(
            new Request(3L, MessageType.SUBMIT_ATTEMPT, "t", new SubmitAttemptDto(1L)),
            studentSession
        );
        assertTrue(submitResp.success());
        assertEquals(resultDto, submitResp.payload());

        when(service.getAttemptState(1L, 10L)).thenReturn(stateDto);
        Response stateResp = handler.handle(
            new Request(4L, MessageType.GET_ATTEMPT_STATE, "t", 1L),
            studentSession
        );
        assertTrue(stateResp.success());
        assertEquals(stateDto, stateResp.payload());
    }

    @Test
    @DisplayName("Từ chối khi session null, sai kiểu payload hoặc lệnh không hỗ trợ")
    void rejectsInvalidSessionPayloadOrType() {
        assertThrows(UnauthorizedException.class, () -> handler.handle(
            new Request(1L, MessageType.START_ATTEMPT, null, new StartAttemptRequestDto(2L, "123456")),
            null
        ));

        QuizException badPayload = assertThrows(QuizException.class, () -> handler.handle(
            new Request(2L, MessageType.START_ATTEMPT, "t", "wrong-payload"),
            studentSession
        ));
        assertEquals(ErrorCode.INVALID_REQUEST, badPayload.getErrorCode());

        QuizException badType = assertThrows(QuizException.class, () -> handler.handle(
            new Request(3L, MessageType.LOGIN, "t", null),
            studentSession
        ));
        assertEquals(ErrorCode.INVALID_REQUEST, badType.getErrorCode());
    }
}
