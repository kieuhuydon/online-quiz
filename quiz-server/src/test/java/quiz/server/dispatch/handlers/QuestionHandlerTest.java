package quiz.server.dispatch.handlers;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import quiz.common.dto.QuestionDto;
import quiz.common.enums.Difficulty;
import quiz.common.enums.Role;
import quiz.common.protocol.MessageType;
import quiz.common.protocol.Request;
import quiz.server.network.UserSession;
import quiz.server.service.QuestionService;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

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
