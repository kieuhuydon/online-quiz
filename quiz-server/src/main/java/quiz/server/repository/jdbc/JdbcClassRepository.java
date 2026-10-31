package quiz.server.repository.jdbc;

import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import quiz.common.dto.ClassMemberDto;
import quiz.common.protocol.ErrorCode;
import quiz.server.domain.Classroom;
import quiz.server.exception.ConflictException;
import quiz.server.exception.QuizException;
import quiz.server.repository.ClassRepository;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * PostgreSQL JDBC implementation for ClassRepository.
 */
public final class JdbcClassRepository implements ClassRepository {
    private static final String FIND_ID = """
        SELECT id, name, description, teacher_id, created_at
        FROM classes WHERE id = ?
        """;
    private static final String FIND_NAME = """
        SELECT id, name, description, teacher_id, created_at
        FROM classes WHERE name = ?
        """;
    private static final String FIND_ALL = """
        SELECT id, name, description, teacher_id, created_at
        FROM classes ORDER BY id ASC
        """;
    private static final String FIND_TEACHER = """
        SELECT id, name, description, teacher_id, created_at
        FROM classes WHERE teacher_id = ? ORDER BY id ASC
        """;
    private static final String INSERT = """
        INSERT INTO classes (name, description, teacher_id)
        VALUES (?, ?, ?)
        RETURNING id, name, description, teacher_id, created_at
        """;
    private static final String INSERT_MEMBER = """
        INSERT INTO class_members (class_id, student_id)
        VALUES (?, ?)
        """;
    private static final String DELETE_CLASS = "DELETE FROM classes WHERE id = ?";
    private static final String DELETE_MEMBER = """
        DELETE FROM class_members WHERE class_id = ? AND student_id = ?
        """;
    private static final String FIND_MEMBERS = """
        SELECT cm.class_id, cm.student_id, u.username, u.full_name, cm.enrolled_at
        FROM class_members cm
        JOIN users u ON cm.student_id = u.id
        WHERE cm.class_id = ?
        ORDER BY cm.enrolled_at ASC
        """;
    private static final String IS_MEMBER = """
        SELECT 1 FROM class_members WHERE class_id = ? AND student_id = ?
        """;
    private static final String FIND_STUDENT_CLASS_IDS = """
        SELECT class_id FROM class_members WHERE student_id = ?
        """;

    private final DataSource dataSource;

    public JdbcClassRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "DataSource cannot be null");
    }

    @Override
    public Classroom save(Classroom classroom) {
        if (classroom == null || classroom.id() != 0 || classroom.createdAt() != null) {
            throw new IllegalArgumentException("Only new classroom can be saved");
        }
        return withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(INSERT)) {
                statement.setString(1, classroom.name());
                if (classroom.description() != null) {
                    statement.setString(2, classroom.description());
                } else {
                    statement.setNull(2, Types.VARCHAR);
                }
                statement.setLong(3, classroom.teacherId());
                try (ResultSet results = statement.executeQuery()) {
                    if (!results.next()) {
                        throw new QuizException(ErrorCode.DATABASE_ERROR, "Failed to insert classroom");
                    }
                    return mapRow(results);
                }
            } catch (SQLException error) {
                throw translateException(error);
            }
        });
    }

    @Override
    public boolean deleteById(long classId) {
        if (classId <= 0) throw new IllegalArgumentException("Class ID must be positive");
        return withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(DELETE_CLASS)) {
                statement.setLong(1, classId);
                return statement.executeUpdate() == 1;
            }
        });
    }

    @Override
    public Optional<Classroom> findById(long id) {
        if (id <= 0) throw new IllegalArgumentException("Class ID must be positive");
        return withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(FIND_ID)) {
                statement.setLong(1, id);
                try (ResultSet results = statement.executeQuery()) {
                    return results.next() ? Optional.of(mapRow(results)) : Optional.empty();
                }
            }
        });
    }

    @Override
    public Optional<Classroom> findByName(String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Name cannot be blank");
        return withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(FIND_NAME)) {
                statement.setString(1, name.strip());
                try (ResultSet results = statement.executeQuery()) {
                    return results.next() ? Optional.of(mapRow(results)) : Optional.empty();
                }
            }
        });
    }

    @Override
    public List<Classroom> findAll() {
        return withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(FIND_ALL);
                 ResultSet results = statement.executeQuery()) {
                List<Classroom> list = new ArrayList<>();
                while (results.next()) {
                    list.add(mapRow(results));
                }
                return List.copyOf(list);
            }
        });
    }

    @Override
    public List<Classroom> findByTeacherId(long teacherId) {
        if (teacherId <= 0) throw new IllegalArgumentException("Teacher ID must be positive");
        return withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(FIND_TEACHER)) {
                statement.setLong(1, teacherId);
                try (ResultSet results = statement.executeQuery()) {
                    List<Classroom> list = new ArrayList<>();
                    while (results.next()) {
                        list.add(mapRow(results));
                    }
                    return List.copyOf(list);
                }
            }
        });
    }

    @Override
    public void addMember(long classId, long studentId) {
        if (classId <= 0 || studentId <= 0) throw new IllegalArgumentException("IDs must be positive");
        withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(INSERT_MEMBER)) {
                statement.setLong(1, classId);
                statement.setLong(2, studentId);
                statement.executeUpdate();
                return null;
            } catch (SQLException error) {
                throw translateException(error);
            }
        });
    }

    @Override
    public void removeMember(long classId, long studentId) {
        if (classId <= 0 || studentId <= 0) throw new IllegalArgumentException("IDs must be positive");
        withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(DELETE_MEMBER)) {
                statement.setLong(1, classId);
                statement.setLong(2, studentId);
                statement.executeUpdate();
                return null;
            }
        });
    }

    @Override
    public List<ClassMemberDto> findMembers(long classId) {
        if (classId <= 0) throw new IllegalArgumentException("Class ID must be positive");
        return withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(FIND_MEMBERS)) {
                statement.setLong(1, classId);
                try (ResultSet results = statement.executeQuery()) {
                    List<ClassMemberDto> members = new ArrayList<>();
                    while (results.next()) {
                        members.add(new ClassMemberDto(
                            results.getLong("class_id"),
                            results.getLong("student_id"),
                            results.getString("username"),
                            results.getString("full_name"),
                            results.getObject("enrolled_at", OffsetDateTime.class).toInstant()
                        ));
                    }
                    return List.copyOf(members);
                }
            }
        });
    }

    @Override
    public boolean isMember(long classId, long studentId) {
        if (classId <= 0 || studentId <= 0) return false;
        return withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(IS_MEMBER)) {
                statement.setLong(1, classId);
                statement.setLong(2, studentId);
                try (ResultSet results = statement.executeQuery()) {
                    return results.next();
                }
            }
        });
    }

    @Override
    public List<Long> findClassIdsForStudent(long studentId) {
        if (studentId <= 0) return List.of();
        return withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(FIND_STUDENT_CLASS_IDS)) {
                statement.setLong(1, studentId);
                try (ResultSet results = statement.executeQuery()) {
                    List<Long> ids = new ArrayList<>();
                    while (results.next()) {
                        ids.add(results.getLong("class_id"));
                    }
                    return List.copyOf(ids);
                }
            }
        });
    }

    private static Classroom mapRow(ResultSet results) throws SQLException {
        return Classroom.restore(
            results.getLong("id"),
            results.getString("name"),
            results.getString("description"),
            results.getLong("teacher_id"),
            results.getObject("created_at", OffsetDateTime.class).toInstant()
        );
    }

    private RuntimeException translateException(SQLException error) {
        if (error instanceof PSQLException psql && "23505".equals(psql.getSQLState())) {
            ServerErrorMessage detail = psql.getServerErrorMessage();
            if (detail != null && "classes_name_key".equals(detail.getConstraint())) {
                return new ConflictException("Tên lớp học đã tồn tại", error);
            }
            if (detail != null && ("class_members_pkey".equals(detail.getConstraint())
                || "uq_class_members_student".equals(detail.getConstraint()))) {
                return new ConflictException(ErrorCode.STUDENT_ALREADY_ENROLLED,
                    "Học viên đã có lớp. Xóa khỏi lớp hiện tại trước khi chuyển lớp.", error);
            }
            return new ConflictException("Dữ liệu bị trùng lặp", error);
        }
        return new QuizException(ErrorCode.DATABASE_ERROR, "Database operation failed", error);
    }

    private <T> T withConnection(ConnectionAction<T> action) {
        try (Connection connection = dataSource.getConnection()) {
            return action.execute(connection);
        } catch (SQLException error) {
            throw new QuizException(ErrorCode.DATABASE_ERROR, "Database operation failed", error);
        }
    }

    @FunctionalInterface
    private interface ConnectionAction<T> {
        T execute(Connection connection) throws SQLException;
    }
}
