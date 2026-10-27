package quiz.server.dispatch.handlers;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import quiz.common.dto.AddClassMemberDto;
import quiz.common.dto.ClassDto;
import quiz.common.dto.CreateClassDto;
import quiz.common.enums.Role;
import quiz.common.protocol.MessageType;
import quiz.common.protocol.Request;
import quiz.server.network.UserSession;
import quiz.server.service.ClassService;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClassHandlerTest {
    private ClassService classService;
    private ClassHandler handler;
    private final UserSession teacherSession = new UserSession(2, Role.TEACHER, "teacher1");

    @BeforeEach
    void setUp() {
        classService = mock(ClassService.class);
        handler = new ClassHandler(classService);
    }

    @Test
    void supportedTypesAndRoles() {
        assertEquals(Set.of(Role.ADMIN), handler.allowedRoles(MessageType.CREATE_CLASS));
        assertEquals(Set.of(Role.ADMIN), handler.allowedRoles(MessageType.DELETE_CLASS));
        assertEquals(Set.of(Role.TEACHER, Role.ADMIN), handler.allowedRoles(MessageType.LIST_CLASSES));
        assertEquals(Set.of(Role.ADMIN), handler.allowedRoles(MessageType.ADD_CLASS_MEMBER));
    }

    @Test
    void createClassDispatchesToService() {
        CreateClassDto dto = new CreateClassDto("ENG-B1-01", "Morning class", 2);
        ClassDto expected = new ClassDto(10, "ENG-B1-01", "Morning class", 2, "teacher1", Instant.now());
        when(classService.createClass(dto, teacherSession)).thenReturn(expected);

        var res = handler.handle(new Request(1, MessageType.CREATE_CLASS, "token", dto), teacherSession);
        assertTrue(res.success());
        assertEquals(1, res.requestId());
        assertEquals(expected, res.payload());
    }

    @Test
    void deleteClassDispatchesToService() {
        var admin = new UserSession(1, Role.ADMIN, "admin");
        var response = handler.handle(new Request(7, MessageType.DELETE_CLASS, "token", 10L), admin);
        assertTrue(response.success());
        assertEquals(7, response.requestId());
        assertNull(response.payload());
        verify(classService).deleteClass(10, admin);
    }

    @Test
    void deleteRejectsMalformedPayloadBeforeService() {
        for (java.io.Serializable payload : new java.io.Serializable[]{null, "10", 10, 0L, -1L}) {
            var error = assertThrows(quiz.server.exception.QuizException.class,
                () -> handler.handle(new Request(1, MessageType.DELETE_CLASS, "token", payload), teacherSession));
            assertEquals(quiz.common.protocol.ErrorCode.INVALID_REQUEST, error.getErrorCode());
        }
        verifyNoInteractions(classService);
    }

    @Test
    void listClassesDispatchesToService() {
        when(classService.listClasses(teacherSession)).thenReturn(new ArrayList<>());
        var res = handler.handle(new Request(2, MessageType.LIST_CLASSES, "token", null), teacherSession);
        assertTrue(res.success());
        assertInstanceOf(ArrayList.class, res.payload());
    }

    @Test
    void addMemberDispatchesToService() {
        AddClassMemberDto dto = new AddClassMemberDto(10, 5);
        var res = handler.handle(new Request(3, MessageType.ADD_CLASS_MEMBER, "token", dto), teacherSession);
        assertTrue(res.success());
        assertNull(res.payload());
        verify(classService).addClassMember(10, 5, teacherSession);
    }
}
