package quiz.server.service;

import quiz.common.dto.ClassDto;
import quiz.common.dto.ClassMemberDto;
import quiz.common.dto.CreateClassDto;
import quiz.common.enums.Role;
import quiz.server.domain.Classroom;
import quiz.server.domain.User;
import quiz.server.exception.ConflictException;
import quiz.server.exception.ForbiddenException;
import quiz.server.exception.NotFoundException;
import quiz.server.exception.ValidationException;
import quiz.server.network.UserSession;
import quiz.server.repository.ClassRepository;
import quiz.server.repository.UserRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Quản lý nghiệp vụ Lớp học và Thành viên lớp (FR-A07).
 */
public final class ClassService {
    private final ClassRepository classRepository;
    private final UserRepository userRepository;

    public ClassService(ClassRepository classRepository, UserRepository userRepository) {
        this.classRepository = Objects.requireNonNull(classRepository, "ClassRepository cannot be null");
        this.userRepository = Objects.requireNonNull(userRepository, "UserRepository cannot be null");
    }

    public ClassDto createClass(CreateClassDto dto, UserSession session) {
        Objects.requireNonNull(session, "UserSession cannot be null");
        if (dto == null || dto.name() == null || dto.name().strip().isBlank()) {
            throw new ValidationException("Tên lớp học không được để trống");
        }
        if (classRepository.findByName(dto.name().strip()).isPresent()) {
            throw new ConflictException("Tên lớp học đã tồn tại");
        }
        Classroom created = classRepository.save(Classroom.createNew(
            dto.name().strip(),
            dto.description() == null ? null : dto.description().strip(),
            session.userId()
        ));
        User teacher = userRepository.findById(session.userId()).orElse(null);
        String teacherName = teacher != null ? teacher.fullName() : session.username();
        return new ClassDto(created.id(), created.name(), created.description(), created.teacherId(), teacherName, created.createdAt());
    }

    public ArrayList<ClassDto> listClasses(UserSession session) {
        Objects.requireNonNull(session, "UserSession cannot be null");
        List<Classroom> classrooms = classRepository.findAll();
        Map<Long, String> teacherNames = userRepository.findAll().stream()
            .collect(Collectors.toMap(User::id, User::fullName, (a, b) -> a));

        ArrayList<ClassDto> dtos = new ArrayList<>(classrooms.size());
        for (Classroom c : classrooms) {
            dtos.add(new ClassDto(
                c.id(),
                c.name(),
                c.description(),
                c.teacherId(),
                teacherNames.getOrDefault(c.teacherId(), "Giáo viên"),
                c.createdAt()
            ));
        }
        return dtos;
    }

    public void addClassMember(long classId, long studentId, UserSession session) {
        Objects.requireNonNull(session, "UserSession cannot be null");
        Classroom classroom = classRepository.findById(classId)
            .orElseThrow(() -> new NotFoundException("Lớp học không tồn tại"));

        if (session.role() != Role.ADMIN && classroom.teacherId() != session.userId()) {
            throw new ForbiddenException("Chỉ giáo viên quản lý lớp hoặc quản trị viên mới có quyền thêm học viên");
        }

        User student = userRepository.findById(studentId)
            .orElseThrow(() -> new NotFoundException("Học viên không tồn tại"));
        if (student.role() != Role.STUDENT) {
            throw new ValidationException("Chỉ có thể thêm tài khoản có vai trò STUDENT vào lớp");
        }
        if (classRepository.isMember(classId, studentId)) {
            throw new ConflictException("Học viên đã ở trong lớp học này");
        }
        classRepository.addMember(classId, studentId);
    }

    public void removeClassMember(long classId, long studentId, UserSession session) {
        Objects.requireNonNull(session, "UserSession cannot be null");
        Classroom classroom = classRepository.findById(classId)
            .orElseThrow(() -> new NotFoundException("Lớp học không tồn tại"));

        if (session.role() != Role.ADMIN && classroom.teacherId() != session.userId()) {
            throw new ForbiddenException("Chỉ giáo viên quản lý lớp hoặc quản trị viên mới có quyền xóa học viên");
        }
        classRepository.removeMember(classId, studentId);
    }

    public ArrayList<ClassMemberDto> listClassMembers(long classId, UserSession session) {
        Objects.requireNonNull(session, "UserSession cannot be null");
        classRepository.findById(classId).orElseThrow(() -> new NotFoundException("Lớp học không tồn tại"));
        return new ArrayList<>(classRepository.findMembers(classId));
    }
}
