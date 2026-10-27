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
        requireAdmin(session);
        if (dto == null || dto.name() == null || dto.name().strip().isBlank()) {
            throw new ValidationException("Tên lớp học không được để trống");
        }
        if (dto.name().strip().length() > Classroom.MAX_NAME_LENGTH
            || dto.description() != null && dto.description().strip().length() > Classroom.MAX_DESCRIPTION_LENGTH
            || dto.teacherId() <= 0) throw new ValidationException("Thông tin lớp học không hợp lệ");
        User teacher = userRepository.findById(dto.teacherId())
            .orElseThrow(() -> new NotFoundException("Giáo viên không tồn tại"));
        if (teacher.role() != Role.TEACHER || !teacher.active())
            throw new ValidationException("Chọn tài khoản giáo viên đang hoạt động");
        if (classRepository.findByName(dto.name().strip()).isPresent()) {
            throw new ConflictException("Tên lớp học đã tồn tại");
        }
        Classroom created = classRepository.save(Classroom.createNew(
            dto.name().strip(),
            dto.description() == null ? null : dto.description().strip(),
            dto.teacherId()
        ));
        String teacherName = teacher.fullName();
        return new ClassDto(created.id(), created.name(), created.description(), created.teacherId(), teacherName, created.createdAt());
    }

    public void deleteClass(long classId, UserSession session) {
        requireAdmin(session);
        if (classId <= 0) throw new ValidationException("ID lớp học phải là số dương");
        if (!classRepository.deleteById(classId)) {
            throw new NotFoundException("Lớp học không tồn tại");
        }
    }

    public ArrayList<ClassDto> listClasses(UserSession session) {
        requireReader(session);
        List<Classroom> classrooms = classRepository.findAll();
        Map<Long, String> teacherNames = userRepository.findAll().stream()
            .collect(Collectors.toMap(User::id, User::fullName, (a, b) -> a));

        ArrayList<ClassDto> dtos = new ArrayList<>(classrooms.size());
        for (Classroom c : classrooms) {
            if (session.role() != Role.ADMIN && c.teacherId() != session.userId()) continue;
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

    public ArrayList<ClassDto> listStudentClasses(UserSession session) {
        if (session == null || session.role() != Role.STUDENT) {
            throw new ForbiddenException("Chỉ học viên mới có thể xem lớp của mình");
        }
        Map<Long, User> teachers = userRepository.findAll().stream()
            .collect(Collectors.toMap(User::id, u -> u, (a, b) -> a));
        ArrayList<ClassDto> result = new ArrayList<>();
        for (Long id : classRepository.findClassIdsForStudent(session.userId())) {
            classRepository.findById(id).ifPresent(c -> {
                User teacher = teachers.get(c.teacherId());
                result.add(new ClassDto(c.id(), c.name(), c.description(), c.teacherId(),
                    teacher == null ? "Giáo viên" : teacher.fullName(), c.createdAt()));
            });
        }
        return result;
    }

    public void addClassMember(long classId, long studentId, UserSession session) {
        requireAdmin(session);
        Classroom classroom = classRepository.findById(classId)
            .orElseThrow(() -> new NotFoundException("Lớp học không tồn tại"));


        User student = userRepository.findById(studentId)
            .orElseThrow(() -> new NotFoundException("Học viên không tồn tại"));
        if (student.role() != Role.STUDENT || !student.active()) {
            throw new ValidationException("Chỉ có thể thêm tài khoản có vai trò STUDENT vào lớp");
        }
        if (classRepository.isMember(classId, studentId)) {
            throw new ConflictException("Học viên đã ở trong lớp học này");
        }
        classRepository.addMember(classId, studentId);
    }

    public void removeClassMember(long classId, long studentId, UserSession session) {
        requireAdmin(session);
        Classroom classroom = classRepository.findById(classId)
            .orElseThrow(() -> new NotFoundException("Lớp học không tồn tại"));

        classRepository.removeMember(classId, studentId);
    }

    public ArrayList<ClassMemberDto> listClassMembers(long classId, UserSession session) {
        requireReader(session);
        Classroom classroom = classRepository.findById(classId).orElseThrow(() -> new NotFoundException("Lớp học không tồn tại"));
        if (session.role() != Role.ADMIN && classroom.teacherId() != session.userId())
            throw new ForbiddenException("Bạn không phụ trách lớp học này");
        return new ArrayList<>(classRepository.findMembers(classId));
    }

    private void requireReader(UserSession session) {
        if (session == null) throw new quiz.server.exception.UnauthorizedException("Vui lòng đăng nhập");
        User actor = userRepository.findById(session.userId())
            .orElseThrow(() -> new quiz.server.exception.UnauthorizedException("Tài khoản không tồn tại"));
        if (!actor.active() || actor.role() != session.role())
            throw new quiz.server.exception.UnauthorizedException("Quyền đăng nhập đã thay đổi");
        if (actor.role() != Role.ADMIN && actor.role() != Role.TEACHER)
            throw new ForbiddenException("Không có quyền quản lý lớp");
    }

    private void requireAdmin(UserSession session) {
        requireReader(session);
        if (session.role() != Role.ADMIN) throw new ForbiddenException("Chỉ Admin được thay đổi lớp và học viên");
    }
}
