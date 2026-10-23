package quiz.server.dispatch.handlers;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import quiz.common.dto.CreateQuestionDto;
import quiz.common.dto.QuestionDto;
import quiz.common.dto.UpdateQuestionDto;
import quiz.common.dto.QuestionDto;
import quiz.common.enums.Difficulty;
import quiz.common.enums.Role;
import quiz.common.protocol.MessageType;
import quiz.common.protocol.Request;
import quiz.common.protocol.Response;
import quiz.server.dispatch.DispatchTestUsers;
import quiz.server.exception.ForbiddenException;
import quiz.server.exception.QuizException;
import quiz.server.network.UserSession;
import quiz.server.service.QuestionService;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Unit tests for QuestionHandler using mocked QuestionService. */
class QuestionHandlerTest {
    private QuestionService questions;
    private QuestionHandler handler;
    private final UserSession teacher = new UserSession(8, Role.TEACHER, "User8");
    private final UserSession admin = new UserSession(7, Role.ADMIN, "User7");
    private final UserSession student = new UserSession(9, Role.STUDENT, "User9");

    @BeforeEach
    void setUp() {
        questions = mock(QuestionService.class);
        handler = new QuestionHandler(questions);
    }

    @Test
    void createQuestionMapsDomainToDto() {
        var dto = new CreateQuestionDto("Nội dung?", "A", "B", "C", "D", 'A', "Chủ đề", Difficulty.EASY);
        Instant ts = Instant.parse("2026-10-10T00:00:00Z");
        QuestionDto created = new QuestionDto(1, "Nội dung?", "A", "B", "C", "D", 'A',
            "Chủ đề", Difficulty.EASY, true, 8, ts);
        when(questions.createQuestion(dto, 8)).thenReturn(
            quiz.server.domain.Question.restore(1, "Nội dung?", "A", "B", "C", "D", 'A',
                "Chủ đề", Difficulty.EASY, true, 8, ts, ts));

        Response response = handler.handle(new Request(1, MessageType.CREATE_QUESTION, "token", dto), teacher);

        assertTrue(response.success());
        assertEquals(1, response.requestId());
        assertInstanceOf(QuestionDto.class, response.payload());
        assertEquals(1L, ((QuestionDto) response.payload()).id());
        verify(questions).createQuestion(dto, 8);
    }

    @Test
    void getQuestionReturnsDto() {
        Instant ts = Instant.parse("2026-10-10T00:00:00Z");
        when(questions.getQuestion(1, 7)).thenReturn(
            quiz.server.domain.Question.restore(1, "Nội dung?", "A", "B", "C", "D", 'A',
                "Chủ đề", Difficulty.EASY, true, 7, ts, ts));

        Response response = handler.handle(new Request(2, MessageType.GET_QUESTION, "token", 1L), admin);

        assertTrue(response.success());
        assertInstanceOf(QuestionDto.class, response.payload());
        verify(questions).getQuestion(1, 7);
    }

    @Test
    void listQuestionsReturnsList() {
        Instant ts = Instant.parse("2026-10-10T00:00:00Z");
        when(questions.listQuestions(8)).thenReturn(List.of(
            quiz.server.domain.Question.restore(1, "Câu 1?", "A", "B", "C", "D", 'A',
                "T1", Difficulty.EASY, true, 8, ts, ts)));

        Response response = handler.handle(new Request(3, MessageType.LIST_QUESTIONS, "token", null), teacher);

        assertTrue(response.success());
        assertInstanceOf(List.class, response.payload());
        verify(questions).listQuestions(8);
    }

    @Test
    void updateQuestionMapsDto() {
        Instant ts = Instant.parse("2026-10-10T00:00:00Z");
        Instant ts2 = Instant.parse("2026-10-11T00:00:00Z");
        var dto = new UpdateQuestionDto(1, "Mới?", "A", "B", "C", "D", 'B', "Chủ đề", Difficulty.HARD);
        QuestionDto updated = new QuestionDto(1, "Mới?", "A", "B", "C", "D", 'B',
            "Chủ đề", Difficulty.HARD, true, 7, ts);
        when(questions.updateQuestion(dto, 7)).thenReturn(
            quiz.server.domain.Question.restore(1, "Mới?", "A", "B", "C", "D", 'B',
                "Chủ đề", Difficulty.HARD, true, 7, ts, ts2));

        Response response = handler.handle(new Request(4, MessageType.UPDATE_QUESTION, "token", dto), admin);

        assertTrue(response.success());
        assertInstanceOf(QuestionDto.class, response.payload());
        assertEquals('B', ((QuestionDto) response.payload()).correctOption());
        verify(questions).updateQuestion(dto, 7);
    }

    @Test
    void deactivateQuestionReturnsNullPayload() {
        doNothing().when(questions).deactivateQuestion(1, 7);

        Response response = handler.handle(new Request(5, MessageType.DEACTIVATE_QUESTION, "token", 1L), admin);

        assertTrue(response.success());
        assertNull(response.payload());
        verify(questions).deactivateQuestion(1, 7);
    }

    @Test
    void policiesAreTeacherAndAdmin() {
        for (MessageType type : new MessageType[]{
            MessageType.CREATE_QUESTION, MessageType.UPDATE_QUESTION,
            MessageType.GET_QUESTION, MessageType.LIST_QUESTIONS, MessageType.DEACTIVATE_QUESTION}) {
            assertEquals(Set.of(Role.TEACHER, Role.ADMIN), handler.allowedRoles(type));
        }
    }

    @Test
    void studentIsForbidden() {
        var dto = new CreateQuestionDto("Câu?", "A", "B", "C", "D", 'A', "T", Difficulty.EASY);
        doThrow(new ForbiddenException("Chỉ Giáo viên và Quản trị viên được quản lý câu hỏi"))
            .when(questions).createQuestion(any(), anyLong());
        assertEquals(quiz.common.protocol.ErrorCode.FORBIDDEN, assertThrows(QuizException.class,
            () -> handler.handle(new Request(1, MessageType.CREATE_QUESTION, "token", dto), student)).getErrorCode());
    }

    @Test
    void invalidPayloadRejected() {
        assertEquals(quiz.common.protocol.ErrorCode.INVALID_REQUEST, assertThrows(QuizException.class,
            () -> handler.handle(new Request(1, MessageType.CREATE_QUESTION, "token", "wrong"), teacher)).getErrorCode());
        assertEquals(quiz.common.protocol.ErrorCode.INVALID_REQUEST, assertThrows(QuizException.class,
            () -> handler.handle(new Request(2, MessageType.GET_QUESTION, "token", null), teacher)).getErrorCode());
    }

    @Test
    void unsupportedTypeThrows() {
        assertThrows(IllegalArgumentException.class,
            () -> handler.allowedRoles(MessageType.LOGIN));
class QuestionHandlerTest {
    private QuestionService questionService;
    private QuestionHandler handler;
    private final UserSession teacherSession = new UserSession(2, Role.TEACHER, "teacher1");

    @BeforeEach
    void setUp() {
        questionService = mock(QuestionService.class);
        handler = new QuestionHandler(questionService);
    }

    @Test
    void supportedTypesAndRoles() {
        assertEquals(Set.of(Role.TEACHER, Role.ADMIN), handler.allowedRoles(MessageType.LIST_QUESTIONS));
        assertEquals(Set.of(Role.TEACHER, Role.ADMIN), handler.allowedRoles(MessageType.GET_QUESTION));
    }

    @Test
    void listQuestionsDispatchesToService() {
        when(questionService.listQuestions()).thenReturn(new ArrayList<>());
        var res = handler.handle(new Request(1, MessageType.LIST_QUESTIONS, "token", null), teacherSession);
        assertTrue(res.success());
        assertInstanceOf(ArrayList.class, res.payload());
    }

    @Test
    void getQuestionDispatchesToService() {
        QuestionDto dto = new QuestionDto(10, "Content", "A", "B", "C", "D", 'A', "Grammar", Difficulty.EASY, true, 2, Instant.now());
        when(questionService.getQuestion(10)).thenReturn(dto);
        var res = handler.handle(new Request(2, MessageType.GET_QUESTION, "token", 10L), teacherSession);
        assertTrue(res.success());
        assertEquals(dto, res.payload());
    }
}
