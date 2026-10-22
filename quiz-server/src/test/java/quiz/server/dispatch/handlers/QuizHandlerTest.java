package quiz.server.dispatch.handlers;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import quiz.common.dto.QuizDto;
import quiz.common.dto.QuizQuestionsDto;
import quiz.common.enums.AntiCheatPolicyType;
import quiz.common.enums.QuizStatus;
import quiz.common.enums.Role;
import quiz.common.protocol.MessageType;
import quiz.common.protocol.Request;
import quiz.common.protocol.Response;
import quiz.server.dispatch.DispatchTestUsers;
import quiz.server.exception.ForbiddenException;
import quiz.server.exception.QuizException;
import quiz.server.network.UserSession;
import quiz.server.service.QuizService;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Unit tests for QuizHandler using mocked QuizService. */
class QuizHandlerTest {
    private QuizService quizzes;
    private QuizHandler handler;
    private final UserSession teacher = new UserSession(8, Role.TEACHER, "User8");
    private final UserSession admin = new UserSession(7, Role.ADMIN, "User7");
    private final UserSession student = new UserSession(9, Role.STUDENT, "User9");
    private final Instant TS = Instant.parse("2026-10-10T00:00:00Z");

    @BeforeEach
    void setUp() {
        quizzes = mock(QuizService.class);
        handler = new QuizHandler(quizzes);
    }

    @Test
    void setQuestionsMapsDomainToDto() {
        var dto = new QuizQuestionsDto(1, List.of(10L, 11L));
        QuizDto updated = new QuizDto(1, "Quiz 1", null, null, 15, 50.0, QuizStatus.DRAFT,
            null, null, 3, 8, TS, TS, null);
        when(quizzes.setQuestions(1, dto, 8)).thenReturn(
            new quiz.server.domain.Quiz(1, "Quiz 1", null, null, 15, 50.0,
                QuizStatus.DRAFT, null, AntiCheatPolicyType.WARN_THEN_AUTO_SUBMIT,
                3, 8, TS, TS, null));

        Response response = handler.handle(new Request(1, MessageType.SET_QUIZ_QUESTIONS, "token", dto), teacher);

        assertTrue(response.success());
        assertEquals(1, response.requestId());
        assertInstanceOf(QuizDto.class, response.payload());
        assertEquals(1L, ((QuizDto) response.payload()).id());
        verify(quizzes).setQuestions(1, dto, 8);
    }

    @Test
    void getQuizReturnsDto() {
        when(quizzes.getQuiz(1, 7)).thenReturn(
            new quiz.server.domain.Quiz(1, "Quiz 1", null, null, 15, 50.0,
                QuizStatus.DRAFT, null, AntiCheatPolicyType.WARN_THEN_AUTO_SUBMIT,
                3, 7, TS, TS, null));

        Response response = handler.handle(new Request(2, MessageType.GET_QUIZ, "token", 1L), admin);

        assertTrue(response.success());
        assertInstanceOf(QuizDto.class, response.payload());
        verify(quizzes).getQuiz(1, 7);
    }

    @Test
    void listQuizzesReturnsList() {
        when(quizzes.listQuizzes(8)).thenReturn(List.of(
            new quiz.server.domain.Quiz(1, "Quiz 1", null, null, 15, 50.0,
                QuizStatus.DRAFT, null, AntiCheatPolicyType.WARN_THEN_AUTO_SUBMIT,
                3, 8, TS, TS, null)));

        Response response = handler.handle(new Request(3, MessageType.LIST_QUIZZES, "token", null), teacher);

        assertTrue(response.success());
        assertInstanceOf(List.class, response.payload());
        verify(quizzes).listQuizzes(8);
    }

    @Test
    void policiesAreTeacherAndAdmin() {
        for (MessageType type : new MessageType[]{
            MessageType.SET_QUIZ_QUESTIONS, MessageType.GET_QUIZ, MessageType.LIST_QUIZZES}) {
            assertEquals(Set.of(Role.TEACHER, Role.ADMIN), handler.allowedRoles(type));
        }
    }

    @Test
    void studentIsForbidden() {
        var dto = new QuizQuestionsDto(1, List.of(10L));
        doThrow(new ForbiddenException("Chỉ Giáo viên và Quản trị viên được quản lý quiz"))
            .when(quizzes).setQuestions(anyLong(), any(QuizQuestionsDto.class), anyLong());
        assertEquals(quiz.common.protocol.ErrorCode.FORBIDDEN, assertThrows(QuizException.class,
            () -> handler.handle(new Request(1, MessageType.SET_QUIZ_QUESTIONS, "token", dto), student)).getErrorCode());
    }

    @Test
    void invalidPayloadRejected() {
        assertEquals(quiz.common.protocol.ErrorCode.INVALID_REQUEST, assertThrows(QuizException.class,
            () -> handler.handle(new Request(1, MessageType.SET_QUIZ_QUESTIONS, "token", "wrong"), teacher)).getErrorCode());
        assertEquals(quiz.common.protocol.ErrorCode.INVALID_REQUEST, assertThrows(QuizException.class,
            () -> handler.handle(new Request(2, MessageType.GET_QUIZ, "token", null), teacher)).getErrorCode());
    }

    @Test
    void unsupportedTypeThrows() {
        assertThrows(IllegalArgumentException.class,
            () -> handler.allowedRoles(MessageType.CREATE_QUIZ));
    }
}
