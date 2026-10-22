package quiz.server.dispatch.handlers;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import quiz.common.dto.*;
import quiz.common.enums.AntiCheatPolicyType;
import quiz.common.enums.QuizStatus;
import quiz.common.enums.Role;
import quiz.common.protocol.MessageType;
import quiz.common.protocol.Request;
import quiz.server.network.UserSession;
import quiz.server.service.QuizService;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class QuizHandlerTest {
    private QuizService quizService;
    private QuizHandler handler;
    private final UserSession teacherSession = new UserSession(2, Role.TEACHER, "teacher1");
    private final UserSession studentSession = new UserSession(5, Role.STUDENT, "student01");

    @BeforeEach
    void setUp() {
        quizService = mock(QuizService.class);
        handler = new QuizHandler(quizService);
    }

    @Test
    void allowedRolesDifferentiatesTeacherAndStudent() {
        assertEquals(Set.of(Role.TEACHER, Role.ADMIN), handler.allowedRoles(MessageType.CREATE_QUIZ));
        assertEquals(Set.of(Role.TEACHER, Role.ADMIN), handler.allowedRoles(MessageType.OPEN_QUIZ));
        assertEquals(Set.of(Role.TEACHER, Role.ADMIN), handler.allowedRoles(MessageType.ASSIGN_QUIZ));
        assertEquals(Set.of(Role.STUDENT), handler.allowedRoles(MessageType.LIST_ASSIGNED_QUIZZES));
        assertEquals(Set.of(Role.STUDENT), handler.allowedRoles(MessageType.GET_QUIZ_INFO));
    }

    @Test
    void createQuizDispatchesToService() {
        CreateQuizDto dto = new CreateQuizDto("Title", "Desc", "Category", 30, 50.0,
            AntiCheatPolicyType.WARN_THEN_AUTO_SUBMIT, 3);
        QuizDto expected = new QuizDto(1, "Title", "Desc", "Category", 30, 50.0,
            QuizStatus.DRAFT, null, AntiCheatPolicyType.WARN_THEN_AUTO_SUBMIT, 3, 2, Instant.now(), null, null);
        when(quizService.createQuiz(dto, teacherSession)).thenReturn(expected);

        var res = handler.handle(new Request(1, MessageType.CREATE_QUIZ, "token", dto), teacherSession);
        assertTrue(res.success());
        assertEquals(expected, res.payload());
    }

    @Test
    void openQuizDispatchesToService() {
        QuizDto opened = new QuizDto(1, "Title", "Desc", "Category", 30, 50.0,
            QuizStatus.OPEN, "123456", AntiCheatPolicyType.WARN_THEN_AUTO_SUBMIT, 3, 2, Instant.now(), Instant.now(), null);
        when(quizService.openQuiz(1L, teacherSession)).thenReturn(opened);

        var res = handler.handle(new Request(2, MessageType.OPEN_QUIZ, "token", 1L), teacherSession);
        assertTrue(res.success());
        assertEquals(opened, res.payload());
    }

    @Test
    void assignQuizDispatchesToService() {
        AssignQuizDto dto = new AssignQuizDto(1L, 10L);
        var res = handler.handle(new Request(3, MessageType.ASSIGN_QUIZ, "token", dto), teacherSession);
        assertTrue(res.success());
        assertNull(res.payload());
        verify(quizService).assignQuiz(1L, 10L, teacherSession);
    }

    @Test
    void studentListAssignedQuizzesDispatchesToService() {
        when(quizService.listAssignedQuizzesForStudent(5L)).thenReturn(new ArrayList<>());
        var res = handler.handle(new Request(4, MessageType.LIST_ASSIGNED_QUIZZES, "token", null), studentSession);
        assertTrue(res.success());
        assertInstanceOf(ArrayList.class, res.payload());
    }
}
