package quiz.server.repository.jdbc;

import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import quiz.common.enums.Role;
import quiz.common.protocol.ErrorCode;
import quiz.server.domain.User;
import quiz.server.exception.ConflictException;
import quiz.server.exception.QuizException;
import quiz.server.repository.UserRepository;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** PostgreSQL user persistence. Caller-owned connections retain their transaction lifecycle. */
public final class JdbcUserRepository implements UserRepository {
    private static final String FIND_USERNAME = """
        SELECT id, username, password_hash, full_name, email, role, active, created_by, created_at
        FROM users WHERE username = ?
        """;
    private static final String FIND_ID = """
        SELECT id, username, password_hash, full_name, email, role, active, created_by, created_at
        FROM users WHERE id = ?
        """;
    private static final String FIND_ALL = """
        SELECT id, username, password_hash, full_name, email, role, active, created_by, created_at
        FROM users ORDER BY id ASC
        """;
    private static final String FIND_UNENROLLED_STUDENTS = """
        SELECT id, username, password_hash, full_name, email, role, active, created_by, created_at
        FROM users u
        WHERE role = 'STUDENT' AND active = TRUE
          AND NOT EXISTS (SELECT 1 FROM class_members cm WHERE cm.student_id = u.id)
        ORDER BY id ASC
        """;
    private static final String INSERT = """
        INSERT INTO users (username, password_hash, full_name, email, role, active, created_by)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        RETURNING id, username, password_hash, full_name, email, role, active, created_by, created_at
        """;
    private static final String UPDATE_ACTIVE = "UPDATE users SET active = ? WHERE id = ?";
    private final DataSource dataSource;

    public JdbcUserRepository(DataSource dataSource) {
        if (dataSource == null) {
            throw new IllegalArgumentException("DataSource cannot be null");
        }
        this.dataSource = dataSource;
    }

    @Override
    public Optional<User> findByUsername(String username) {
        validateText(username, "Username", 50);
        return withConnection(connection -> findOne(connection, FIND_USERNAME,
            statement -> statement.setString(1, username)));
    }

    @Override
    public Optional<User> findById(long id) {
        validateId(id);
        return withConnection(connection -> findOne(connection, FIND_ID,
            statement -> statement.setLong(1, id)));
    }

    @Override
    public Optional<User> findById(Connection connection, long id) {
        validateId(id);
        return onConnection(connection, () -> findOne(connection, FIND_ID,
            statement -> statement.setLong(1, id)));
    }

    @Override
    public boolean existsByUsername(String username) {
        validateText(username, "Username", 50);
        return withConnection(connection -> exists(connection,
            "SELECT 1 FROM users WHERE username = ?", username));
    }

    @Override
    public boolean existsByEmail(String email) {
        if (email == null) {
            return false;
        }
        validateText(email, "Email", 100);
        return withConnection(connection -> exists(connection,
            "SELECT 1 FROM users WHERE email = ?", email));
    }

    @Override
    public User save(User user) {
        validateNewUser(user);
        return withConnection(connection -> insert(connection, user));
    }

    @Override
    public User save(Connection connection, User user) {
        validateNewUser(user);
        return onConnection(connection, () -> insert(connection, user));
    }

    @Override
    public List<User> findAll() {
        return findUsers(FIND_ALL);
    }

    @Override
    public List<User> findUnenrolledStudents() {
        return findUsers(FIND_UNENROLLED_STUDENTS);
    }

    private List<User> findUsers(String query) {
        return withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(query);
                 ResultSet rows = statement.executeQuery()) {
                List<User> users = new ArrayList<>();
                while (rows.next()) {
                    users.add(mapUser(rows));
                }
                return users;
            }
        });
    }

    @Override
    public boolean updateActiveStatus(long id, boolean active) {
        validateId(id);
        return withConnection(connection -> updateActive(connection, id, active));
    }

    @Override
    public boolean updateActiveStatus(Connection connection, long id, boolean active) {
        validateId(id);
        return onConnection(connection, () -> updateActive(connection, id, active));
    }

    @Override
    public boolean deleteById(long id, long adminId) {
        validateId(id);
        validateId(adminId);
        return withConnection(connection -> delete(connection, id, adminId));
    }

    @Override
    public boolean deleteById(Connection connection, long id, long adminId) {
        validateId(id);
        validateId(adminId);
        return onConnection(connection, () -> delete(connection, id, adminId));
    }

    private Optional<User> findOne(Connection connection, String sql, StatementBinder binder)
        throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(mapUser(rows)) : Optional.empty();
            }
        }
    }

    private boolean exists(Connection connection, String sql, String value) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, value);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    private User insert(Connection connection, User user) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT)) {
            statement.setString(1, user.username());
            statement.setString(2, user.passwordHash());
            statement.setString(3, user.fullName());
            if (user.email() == null) {
                statement.setNull(4, Types.VARCHAR);
            } else {
                statement.setString(4, user.email());
            }
            statement.setString(5, user.role().name());
            statement.setBoolean(6, user.active());
            if (user.createdBy() == null) {
                statement.setNull(7, Types.BIGINT);
            } else {
                statement.setLong(7, user.createdBy());
            }
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("User insertion returned no row");
                }
                return mapUser(rows);
            }
        } catch (SQLException error) {
            throw translateInsertError(error);
        }
    }

    private boolean updateActive(Connection connection, long id, boolean active) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(UPDATE_ACTIVE)) {
            statement.setBoolean(1, active);
            statement.setLong(2, id);
            return statement.executeUpdate() > 0;
        }
    }

    private boolean delete(Connection connection, long id, long adminId) throws SQLException {
        try (PreparedStatement checkClass = connection.prepareStatement("SELECT 1 FROM classes WHERE teacher_id = ? LIMIT 1")) {
            checkClass.setLong(1, id);
            try (ResultSet rs = checkClass.executeQuery()) {
                if (rs.next()) {
                    throw new ConflictException(ErrorCode.CONFLICT, "Không thể xóa giáo viên đang phụ trách lớp học. Vui lòng chuyển hoặc xóa lớp học trước.");
                }
            }
        }
        try (PreparedStatement s = connection.prepareStatement("UPDATE users SET created_by = NULL WHERE created_by = ?")) {
            s.setLong(1, id);
            s.executeUpdate();
        }
        try (PreparedStatement s = connection.prepareStatement("UPDATE quiz_assignments SET assigned_by = NULL WHERE assigned_by = ?")) {
            s.setLong(1, id);
            s.executeUpdate();
        }
        try (PreparedStatement s = connection.prepareStatement("UPDATE questions SET created_by = ? WHERE created_by = ?")) {
            s.setLong(1, adminId);
            s.setLong(2, id);
            s.executeUpdate();
        }
        try (PreparedStatement s = connection.prepareStatement("UPDATE quizzes SET created_by = ? WHERE created_by = ?")) {
            s.setLong(1, adminId);
            s.setLong(2, id);
            s.executeUpdate();
        }
        try (PreparedStatement s = connection.prepareStatement("DELETE FROM monitoring_penalties WHERE session_id IN (SELECT session_id FROM exam_sessions WHERE student_id = ?)")) {
            s.setLong(1, id);
            s.executeUpdate();
        }
        try (PreparedStatement s = connection.prepareStatement("DELETE FROM exam_sessions WHERE student_id = ?")) {
            s.setLong(1, id);
            s.executeUpdate();
        }
        try (PreparedStatement s = connection.prepareStatement("UPDATE monitoring_penalties SET teacher_id = ? WHERE teacher_id = ?")) {
            s.setLong(1, adminId);
            s.setLong(2, id);
            s.executeUpdate();
        }
        try (PreparedStatement s = connection.prepareStatement("DELETE FROM attempts WHERE student_id = ?")) {
            s.setLong(1, id);
            s.executeUpdate();
        }
        try (PreparedStatement s = connection.prepareStatement("DELETE FROM users WHERE id = ?")) {
            s.setLong(1, id);
            return s.executeUpdate() > 0;
        }
    }

    private User mapUser(ResultSet rows) throws SQLException {
        try {
            OffsetDateTime createdAt = rows.getObject("created_at", OffsetDateTime.class);
            return User.restore(rows.getLong("id"), rows.getString("username"),
                rows.getString("password_hash"), rows.getString("full_name"),
                rows.getString("email"), Role.valueOf(rows.getString("role")),
                rows.getBoolean("active"), rows.getObject("created_by", Long.class),
                createdAt == null ? null : createdAt.toInstant());
        } catch (IllegalArgumentException | NullPointerException error) {
            throw new SQLException("Invalid stored user data", error);
        }
    }

    private SQLException translateInsertError(SQLException error) {
        if (error instanceof PSQLException postgres && "23505".equals(error.getSQLState())) {
            ServerErrorMessage detail = postgres.getServerErrorMessage();
            if (detail != null && "users_username_key".equals(detail.getConstraint())) {
                throw new ConflictException(ErrorCode.USERNAME_ALREADY_EXISTS, "Username is already in use", error);
            }
            if (detail != null && "users_email_key".equals(detail.getConstraint())) {
                throw new ConflictException(ErrorCode.EMAIL_ALREADY_EXISTS, "Email is already in use", error);
            }
        }
        return error;
    }

    private <T> T withConnection(ConnectionAction<T> action) {
        try (Connection connection = dataSource.getConnection()) {
            return action.execute(connection);
        } catch (SQLException error) {
            throw databaseError(error);
        }
    }

    private <T> T onConnection(Connection connection, SqlAction<T> action) {
        if (connection == null) {
            throw new IllegalArgumentException("Connection cannot be null");
        }
        try {
            return action.execute();
        } catch (SQLException error) {
            throw databaseError(error);
        }
    }

    private QuizException databaseError(SQLException error) {
        return new QuizException(ErrorCode.DATABASE_ERROR, "Database operation failed", error);
    }

    private void validateNewUser(User user) {
        if (user == null || user.id() != 0 || user.createdAt() != null) {
            throw new IllegalArgumentException("Only a new user can be inserted");
        }
    }

    private void validateId(long id) {
        if (id <= 0) {
            throw new IllegalArgumentException("User ID must be positive");
        }
    }

    private void validateText(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.codePointCount(0, value.length()) > maxLength) {
            throw new IllegalArgumentException(field + " must be nonblank and at most "
                + maxLength + " characters");
        }
    }

    @FunctionalInterface
    private interface ConnectionAction<T> {
        T execute(Connection connection) throws SQLException;
    }

    @FunctionalInterface
    private interface SqlAction<T> {
        T execute() throws SQLException;
    }

    @FunctionalInterface
    private interface StatementBinder {
        void bind(PreparedStatement statement) throws SQLException;
    }
}
