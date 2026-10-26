package quiz.server.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import quiz.common.dto.ClassDto;
import quiz.common.dto.ClassMemberDto;
import quiz.common.dto.CreateClassDto;
import quiz.common.enums.Role;
import quiz.server.domain.Classroom;
import quiz.server.domain.User;
import quiz.server.exception.ConflictException;
import quiz.server.exception.ForbiddenException;
import quiz.server.exception.ValidationException;
import quiz.server.network.UserSession;
import quiz.server.repository.ClassRepository;
import quiz.server.repository.UserRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ClassServiceTest {
    private ClassRepository classRepo;
    private UserRepository userRepo;
    private ClassService service;
    private final UserSession adminSession = new UserSession(1, Role.ADMIN, "admin");
    private final UserSession teacherSession = new UserSession(2, Role.TEACHER, "teacher1");
    private final UserSession otherSession = new UserSession(3, Role.TEACHER, "teacher2");

    @BeforeEach
    void setUp() {
        classRepo = mock(ClassRepository.class);
        userRepo = mock(UserRepository.class);
        service = new ClassService(classRepo, userRepo);
        when(userRepo.findById(1)).thenReturn(Optional.of(User.restore(1, "admin", "hash", "Admin", null, Role.ADMIN, true, null, Instant.now())));
        when(userRepo.findById(2)).thenReturn(Optional.of(User.restore(2, "teacher1", "hash", "Teacher", null, Role.TEACHER, true, null, Instant.now())));
        when(userRepo.findById(3)).thenReturn(Optional.of(User.restore(3, "teacher2", "hash", "Teacher 2", null, Role.TEACHER, true, null, Instant.now())));
    }

    @Test void assignedTeacherCannotWriteEvenOwnClass() {
        assertThrows(ForbiddenException.class, () -> service.createClass(new CreateClassDto("Class", null, 2), teacherSession));
        assertThrows(ForbiddenException.class, () -> service.addClassMember(10, 5, teacherSession));
        assertThrows(ForbiddenException.class, () -> service.removeClassMember(10, 5, teacherSession));
        verifyNoInteractions(classRepo);
    }

    @Test void teacherReadsOnlyAssignedClassesAndCannotInspectAnotherClass() {
        var own = Classroom.restore(10, "Own", null, 2, Instant.now());
        var other = Classroom.restore(11, "Other", null, 3, Instant.now());
        when(classRepo.findAll()).thenReturn(List.of(own, other));
        when(userRepo.findAll()).thenReturn(List.of());
        when(classRepo.findById(11)).thenReturn(Optional.of(other));
        assertEquals(List.of(10L), service.listClasses(teacherSession).stream().map(ClassDto::id).toList());
        assertEquals(2, service.listClasses(adminSession).size());
        assertThrows(ForbiddenException.class, () -> service.listClassMembers(11, teacherSession));
        verify(classRepo, never()).findMembers(11);
    }

    @Test void adminCannotBeAssignedAsTeacher() {
        assertThrows(ValidationException.class, () -> service.createClass(new CreateClassDto("Class", null, 1), adminSession));
        verify(classRepo, never()).save(any());
    }

    @Test
    void createClassSuccess() {
        CreateClassDto dto = new CreateClassDto("ENG-B1-01", "Morning class", 2);
        when(classRepo.findByName("ENG-B1-01")).thenReturn(Optional.empty());
        Instant now = Instant.now();
        Classroom created = Classroom.restore(10, "ENG-B1-01", "Morning class", 2, now);
        when(classRepo.save(any(Classroom.class))).thenReturn(created);
        when(userRepo.findById(2)).thenReturn(Optional.of(User.restore(2, "teacher1", "hash", "Nguyen Van A", "t1@quiz.center", Role.TEACHER, true, 1L, now)));

        ClassDto result = service.createClass(dto, adminSession);
        assertEquals(10, result.id());
        assertEquals("ENG-B1-01", result.name());
        assertEquals("Nguyen Van A", result.teacherName());
    }

    @Test
    void createClassThrowsConflictIfNameExists() {
        CreateClassDto dto = new CreateClassDto("ENG-B1-01", "Desc", 2);
        Classroom existing = Classroom.restore(1, "ENG-B1-01", "Desc", 2, Instant.now());
        when(classRepo.findByName("ENG-B1-01")).thenReturn(Optional.of(existing));

        assertThrows(ConflictException.class, () -> service.createClass(dto, adminSession));
    }

    @Test
    void addClassMemberAddsStudent() {
        Instant now = Instant.now();
        Classroom classroom = Classroom.restore(10, "ENG-B1-01", "Desc", 2, now);
        when(classRepo.findById(10)).thenReturn(Optional.of(classroom));
        when(userRepo.findById(5)).thenReturn(Optional.of(User.restore(5, "student01", "hash", "Student 01", "s1@quiz.center", Role.STUDENT, true, 1L, now)));
        when(classRepo.isMember(10, 5)).thenReturn(false);

        service.addClassMember(10, 5, adminSession);
        verify(classRepo).addMember(10, 5);
    }

    @Test
    void addClassMemberRejectsNonStudentRole() {
        Instant now = Instant.now();
        Classroom classroom = Classroom.restore(10, "ENG-B1-01", "Desc", 2, now);
        when(classRepo.findById(10)).thenReturn(Optional.of(classroom));
        when(userRepo.findById(3)).thenReturn(Optional.of(User.restore(3, "teacher2", "hash", "Teacher 2", "t2@quiz.center", Role.TEACHER, true, 1L, now)));

        assertThrows(ValidationException.class, () -> service.addClassMember(10, 3, adminSession));
    }

    @Test
    void addClassMemberRejectsUnauthorizedTeacher() {
        Instant now = Instant.now();
        Classroom classroom = Classroom.restore(10, "ENG-B1-01", "Desc", 2, now);
        when(classRepo.findById(10)).thenReturn(Optional.of(classroom));

        assertThrows(ForbiddenException.class, () -> service.addClassMember(10, 5, otherSession));
    }
}
