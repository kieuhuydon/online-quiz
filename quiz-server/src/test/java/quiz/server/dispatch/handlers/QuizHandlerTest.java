package quiz.server.dispatch.handlers;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import quiz.common.dto.AssignQuizDto;
import quiz.common.dto.CreateQuizDto;
import quiz.common.dto.QuizDetailDto;
import quiz.common.dto.QuizDto;
import quiz.common.dto.QuizQuestionsDto;
import quiz.common.dto.UpdateQuizDto;
import quiz.common.enums.AntiCheatPolicyType;
import quiz.common.enums.QuizStatus;
import quiz.common.enums.Role;
import quiz.common.protocol.MessageType;
import quiz.common.protocol.Request;
import quiz.common.protocol.Response;
import quiz.server.exception.QuizException;
import quiz.server.network.UserSession;
import quiz.server.service.QuizService;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Unit tests for QuizHandler using mocked QuizService. */
class QuizHandlerTest {
    private QuizService quizService;
    private QuizHandler handler;
    private final UserSession teacherSession = new UserSession(2, Role.TEACHER, "teacher1");
    private final UserSession adminSession = new UserSession(1, Role.ADMIN, "admin1");
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
        assertEquals(Set.of(Role.TEACHER, Role.ADMIN), handler.allowedRoles(MessageType.CLOSE_QUIZ));
        assertEquals(Set.of(Role.TEACHER, Role.ADMIN), handler.allowedRoles(MessageType.ASSIGN_QUIZ));
        assertEquals(Set.of(Role.TEACHER, Role.ADMIN), handler.allowedRoles(MessageType.LIST_QUIZ_RESULTS));
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

        Response res = handler.handle(new Request(1, MessageType.CREATE_QUIZ, "token", dto), teacherSession);
        assertTrue(res.success());
        assertEquals(expected, res.payload());
    }

    @Test
    void updateQuizDispatchesToService() {
        UpdateQuizDto dto = new UpdateQuizDto(1, "Title 2", "Desc 2", "Category 2", 45, 60.0,
            AntiCheatPolicyType.LOG_ONLY, 5);
        QuizDto expected = new QuizDto(1, "Title 2", "Desc 2", "Category 2", 45, 60.0,
            QuizStatus.DRAFT, null, AntiCheatPolicyType.LOG_ONLY, 5, 2, Instant.now(), null, null);
        when(quizService.updateQuiz(dto, teacherSession)).thenReturn(expected);

        Response res = handler.handle(new Request(2, MessageType.UPDATE_QUIZ, "token", dto), teacherSession);
        assertTrue(res.success());
        assertEquals(expected, res.payload());
    }

    @Test
    void setQuestionsDispatchesToService() {
        var dto = new QuizQuestionsDto(1, List.of(10L, 11L));
        Response res = handler.handle(new Request(3, MessageType.SET_QUIZ_QUESTIONS, "token", dto), teacherSession);
        assertTrue(res.success());
        assertNull(res.payload());
        verify(quizService).setQuizQuestions(1, List.of(10L, 11L), teacherSession);
    }

    @Test
    void getQuizDispatchesToService() {
        QuizDetailDto detail = mock(QuizDetailDto.class);
        when(quizService.getQuizDetail(1L)).thenReturn(detail);

        Response res = handler.handle(new Request(4, MessageType.GET_QUIZ, "token", 1L), adminSession);
        assertTrue(res.success());
        assertEquals(detail, res.payload());
        verify(quizService).getQuizDetail(1L);
    }

    @Test
    void listQuizzesDispatchesToService() {
        when(quizService.listQuizzes(teacherSession)).thenReturn(new ArrayList<>());
        Response res = handler.handle(new Request(5, MessageType.LIST_QUIZZES, "token", null), teacherSession);
        assertTrue(res.success());
        assertInstanceOf(ArrayList.class, res.payload());
        verify(quizService).listQuizzes(teacherSession);
    }

    @Test
    void openQuizDispatchesToService() {
        QuizDto opened = new QuizDto(1, "Title", "Desc", "Category", 30, 50.0,
            QuizStatus.OPEN, "123456", AntiCheatPolicyType.WARN_THEN_AUTO_SUBMIT, 3, 2, Instant.now(), Instant.now(), null);
        when(quizService.openQuiz(1L, teacherSession)).thenReturn(opened);

        Response res = handler.handle(new Request(6, MessageType.OPEN_QUIZ, "token", 1L), teacherSession);
        assertTrue(res.success());
        assertEquals(opened, res.payload());
    }

    @Test
    void closeQuizDispatchesToService() {
        QuizDto closed = new QuizDto(1, "Title", "Desc", "Category", 30, 50.0,
            QuizStatus.CLOSED, "123456", AntiCheatPolicyType.WARN_THEN_AUTO_SUBMIT, 3, 2, Instant.now(), Instant.now(), Instant.now());
        when(quizService.closeQuiz(1L, teacherSession)).thenReturn(closed);

        Response res = handler.handle(new Request(7, MessageType.CLOSE_QUIZ, "token", 1L), teacherSession);
        assertTrue(res.success());
        assertEquals(closed, res.payload());
    }

    @Test
    void assignQuizDispatchesToService() {
        AssignQuizDto dto = new AssignQuizDto(1L, 10L);
        Response res = handler.handle(new Request(8, MessageType.ASSIGN_QUIZ, "token", dto), teacherSession);
        assertTrue(res.success());
        assertNull(res.payload());
        verify(quizService).assignQuiz(1L, 10L, teacherSession);
    }

    @Test
    void studentListAssignedQuizzesDispatchesToService() {
        when(quizService.listAssignedQuizzesForStudent(5L)).thenReturn(new ArrayList<>());
        Response res = handler.handle(new Request(9, MessageType.LIST_ASSIGNED_QUIZZES, "token", null), studentSession);
        assertTrue(res.success());
        assertInstanceOf(ArrayList.class, res.payload());
    }

    @Test
    void listQuizResultsDispatchesToService() {
        when(quizService.listQuizResults(1L, teacherSession)).thenReturn(new ArrayList<>());
        var res = handler.handle(new Request(5, MessageType.LIST_QUIZ_RESULTS, "token", 1L), teacherSession);
        assertTrue(res.success());
        assertInstanceOf(ArrayList.class, res.payload());
    }

    @Test
    void getQuizInfoAndGetClassQuizResultsDispatchToService() {
        assertEquals(Set.of(Role.TEACHER, Role.ADMIN), handler.allowedRoles(MessageType.GET_CLASS_QUIZ_RESULTS));
        assertThrows(IllegalArgumentException.class, () -> handler.allowedRoles(MessageType.LOGIN));

        quiz.common.dto.QuizInfoDto infoDto = new quiz.common.dto.QuizInfoDto(
            1L, "Title", "Desc", "Cat", 30, 50.0, 10, QuizStatus.OPEN, "123456", true, Instant.now(), null
        );
        when(quizService.getQuizInfo(1L, 5L)).thenReturn(infoDto);
        Response infoRes = handler.handle(new Request(10, MessageType.GET_QUIZ_INFO, "token", 1L), studentSession);
        assertTrue(infoRes.success());
        assertEquals(infoDto, infoRes.payload());

        quiz.common.dto.ClassQuizResultsDto classResults =
            new quiz.common.dto.ClassQuizResultsDto(List.of(), List.of());
        when(quizService.getClassQuizResults(10L, teacherSession)).thenReturn(classResults);
        Response classRes = handler.handle(new Request(11, MessageType.GET_CLASS_QUIZ_RESULTS, "token", 10L), teacherSession);
        assertTrue(classRes.success());
        assertEquals(classResults, classRes.payload());
    }
}
