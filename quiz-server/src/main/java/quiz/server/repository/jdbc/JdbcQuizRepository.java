package quiz.server.repository.jdbc;

import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import quiz.common.dto.ResultDto;
import quiz.common.enums.AntiCheatPolicyType;
import quiz.common.enums.QuizStatus;
import quiz.common.enums.SubmitReason;
import quiz.common.protocol.ErrorCode;
import quiz.server.domain.Quiz;
import quiz.server.exception.ConflictException;
import quiz.server.exception.QuizException;
import quiz.server.repository.QuizRepository;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * PostgreSQL JDBC implementation for QuizRepository.
 */
public final class JdbcQuizRepository implements QuizRepository {
    private static final String FIND_ID = """
        SELECT id, title, description, category, duration_minutes, pass_score, status, pin,
               anticheat_policy, max_violations, created_by, created_at, opened_at, closed_at
        FROM quizzes WHERE id = ?
        """;
    private static final String FIND_ALL = """
        SELECT id, title, description, category, duration_minutes, pass_score, status, pin,
               anticheat_policy, max_violations, created_by, created_at, opened_at, closed_at
        FROM quizzes ORDER BY id DESC
        """;
    private static final String FIND_BY_CREATOR = """
        SELECT id, title, description, category, duration_minutes, pass_score, status, pin,
               anticheat_policy, max_violations, created_by, created_at, opened_at, closed_at
        FROM quizzes WHERE created_by = ? ORDER BY id DESC
        """;
    private static final String INSERT = """
        INSERT INTO quizzes (title, description, category, duration_minutes, pass_score, status, pin,
                             anticheat_policy, max_violations, created_by)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        RETURNING id, title, description, category, duration_minutes, pass_score, status, pin,
                  anticheat_policy, max_violations, created_by, created_at, opened_at, closed_at
        """;
    private static final String UPDATE = """
        UPDATE quizzes
        SET title = ?, description = ?, category = ?, duration_minutes = ?, pass_score = ?,
            anticheat_policy = ?, max_violations = ?
        WHERE id = ? AND status = 'DRAFT'
        """;
    private static final String UPDATE_STATUS = """
        UPDATE quizzes
        SET status = ?, pin = ?, opened_at = ?, closed_at = ?
        WHERE id = ?
        """;
    private static final String DELETE_QUESTIONS = "DELETE FROM quiz_questions WHERE quiz_id = ?";
    private static final String INSERT_QUESTION = """
        INSERT INTO quiz_questions (quiz_id, question_id, position)
        VALUES (?, ?, ?)
        """;
    private static final String GET_QUESTION_IDS = """
        SELECT question_id FROM quiz_questions WHERE quiz_id = ? ORDER BY position ASC
        """;
    private static final String COUNT_QUESTIONS = """
        SELECT COUNT(*) FROM quiz_questions WHERE quiz_id = ?
        """;
    private static final String ASSIGN_CLASS = """
        INSERT INTO quiz_assignments (quiz_id, class_id, assigned_by)
        VALUES (?, ?, ?)
        ON CONFLICT (quiz_id, class_id) DO NOTHING
        """;
    private static final String FIND_ASSIGNED_CLASS_IDS = """
        SELECT class_id FROM quiz_assignments WHERE quiz_id = ? ORDER BY class_id ASC
        """;
    private static final String FIND_ASSIGNED_FOR_STUDENT = """
        SELECT DISTINCT q.id, q.title, q.description, q.category, q.duration_minutes, q.pass_score,
               q.status, q.pin, q.anticheat_policy, q.max_violations, q.created_by, q.created_at,
               q.opened_at, q.closed_at
        FROM quizzes q
        JOIN quiz_assignments qa ON q.id = qa.quiz_id
        JOIN class_members cm ON qa.class_id = cm.class_id
        WHERE cm.student_id = ?
        ORDER BY q.id DESC
        """;
    private static final String FIND_RESULTS_BY_QUIZ = """
        SELECT r.attempt_id, a.quiz_id, q.title AS quiz_title, u.full_name AS student_name,
               r.score, r.correct_count, r.total_questions, r.passed, r.submit_reason,
               a.submitted_at
        FROM results r
        JOIN attempts a ON r.attempt_id = a.id
        JOIN users u ON a.student_id = u.id
        JOIN quizzes q ON a.quiz_id = q.id
        WHERE a.quiz_id = ?
        ORDER BY r.score DESC, a.submitted_at DESC
        """;

    private final DataSource dataSource;

    public JdbcQuizRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "DataSource cannot be null");
    }

    @Override
    public Quiz save(Quiz quiz) {
        if (quiz == null || quiz.id() != 0 || quiz.createdAt() != null) {
            throw new IllegalArgumentException("Only new quiz can be saved");
        }
        return withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(INSERT)) {
                statement.setString(1, quiz.title());
                if (quiz.description() != null) statement.setString(2, quiz.description());
                else statement.setNull(2, Types.VARCHAR);
                if (quiz.category() != null) statement.setString(3, quiz.category());
                else statement.setNull(3, Types.VARCHAR);
                statement.setInt(4, quiz.durationMinutes());
                statement.setBigDecimal(5, BigDecimal.valueOf(quiz.passScore()));
                statement.setString(6, quiz.status().name());
                if (quiz.pin() != null) statement.setString(7, quiz.pin());
                else statement.setNull(7, Types.VARCHAR);
                statement.setString(8, quiz.anticheatPolicy().name());
                statement.setInt(9, quiz.maxViolations());
                statement.setLong(10, quiz.createdBy());
                try (ResultSet results = statement.executeQuery()) {
                    if (!results.next()) {
                        throw new QuizException(ErrorCode.DATABASE_ERROR, "Failed to insert quiz");
                    }
                    return mapRow(results);
                }
            } catch (SQLException error) {
                throw translateException(error);
            }
        });
    }

    @Override
    public boolean update(Quiz quiz) {
        if (quiz == null || quiz.id() <= 0) {
            throw new IllegalArgumentException("Valid persisted quiz required for update");
        }
        return withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(UPDATE)) {
                statement.setString(1, quiz.title());
                if (quiz.description() != null) statement.setString(2, quiz.description());
                else statement.setNull(2, Types.VARCHAR);
                if (quiz.category() != null) statement.setString(3, quiz.category());
                else statement.setNull(3, Types.VARCHAR);
                statement.setInt(4, quiz.durationMinutes());
                statement.setBigDecimal(5, BigDecimal.valueOf(quiz.passScore()));
                statement.setString(6, quiz.anticheatPolicy().name());
                statement.setInt(7, quiz.maxViolations());
                statement.setLong(8, quiz.id());
                return statement.executeUpdate() > 0;
            }
        });
    }

    @Override
    public Optional<Quiz> findById(long id) {
        if (id <= 0) throw new IllegalArgumentException("Quiz ID must be positive");
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
    public List<Quiz> findAll() {
        return withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(FIND_ALL);
                 ResultSet results = statement.executeQuery()) {
                List<Quiz> list = new ArrayList<>();
                while (results.next()) {
                    list.add(mapRow(results));
                }
                return List.copyOf(list);
            }
        });
    }

    @Override
    public List<Quiz> findByCreator(long teacherId) {
        if (teacherId <= 0) throw new IllegalArgumentException("Teacher ID must be positive");
        return withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(FIND_BY_CREATOR)) {
                statement.setLong(1, teacherId);
                try (ResultSet results = statement.executeQuery()) {
                    List<Quiz> list = new ArrayList<>();
                    while (results.next()) {
                        list.add(mapRow(results));
                    }
                    return List.copyOf(list);
                }
            }
        });
    }

    @Override
    public boolean updateStatus(long quizId, QuizStatus status, String pin, Instant openedAt, Instant closedAt) {
        if (quizId <= 0 || status == null) throw new IllegalArgumentException("Invalid status update arguments");
        return withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(UPDATE_STATUS)) {
                statement.setString(1, status.name());
                if (pin != null) statement.setString(2, pin);
                else statement.setNull(2, Types.VARCHAR);
                if (openedAt != null) statement.setTimestamp(3, Timestamp.from(openedAt));
                else statement.setNull(3, Types.TIMESTAMP_WITH_TIMEZONE);
                if (closedAt != null) statement.setTimestamp(4, Timestamp.from(closedAt));
                else statement.setNull(4, Types.TIMESTAMP_WITH_TIMEZONE);
                statement.setLong(5, quizId);
                return statement.executeUpdate() > 0;
            }
        });
    }

    @Override
    public void setQuestions(long quizId, List<Long> questionIds) {
        if (quizId <= 0) throw new IllegalArgumentException("Quiz ID must be positive");
        withTransaction(connection -> {
            try (PreparedStatement deleteStmt = connection.prepareStatement(DELETE_QUESTIONS)) {
                deleteStmt.setLong(1, quizId);
                deleteStmt.executeUpdate();
            }
            if (questionIds != null && !questionIds.isEmpty()) {
                try (PreparedStatement insertStmt = connection.prepareStatement(INSERT_QUESTION)) {
                    int position = 1;
                    for (Long qId : questionIds) {
                        insertStmt.setLong(1, quizId);
                        insertStmt.setLong(2, qId);
                        insertStmt.setInt(3, position++);
                        insertStmt.addBatch();
                    }
                    insertStmt.executeBatch();
                }
            }
            return null;
        });
    }

    @Override
    public List<Long> getQuestionIds(long quizId) {
        if (quizId <= 0) throw new IllegalArgumentException("Quiz ID must be positive");
        return withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(GET_QUESTION_IDS)) {
                statement.setLong(1, quizId);
                try (ResultSet results = statement.executeQuery()) {
                    List<Long> ids = new ArrayList<>();
                    while (results.next()) {
                        ids.add(results.getLong("question_id"));
                    }
                    return List.copyOf(ids);
                }
            }
        });
    }

    @Override
    public int countQuestions(long quizId) {
        if (quizId <= 0) throw new IllegalArgumentException("Quiz ID must be positive");
        return withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(COUNT_QUESTIONS)) {
                statement.setLong(1, quizId);
                try (ResultSet results = statement.executeQuery()) {
                    return results.next() ? results.getInt(1) : 0;
                }
            }
        });
    }

    @Override
    public void assignToClass(long quizId, long classId, long assignedBy) {
        if (quizId <= 0 || classId <= 0) throw new IllegalArgumentException("IDs must be positive");
        withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(ASSIGN_CLASS)) {
                statement.setLong(1, quizId);
                statement.setLong(2, classId);
                statement.setLong(3, assignedBy);
                statement.executeUpdate();
                return null;
            }
        });
    }

    @Override
    public List<Long> findAssignedClassIds(long quizId) {
        if (quizId <= 0) throw new IllegalArgumentException("Quiz ID must be positive");
        return withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(FIND_ASSIGNED_CLASS_IDS)) {
                statement.setLong(1, quizId);
                try (ResultSet results = statement.executeQuery()) {
                    List<Long> list = new ArrayList<>();
                    while (results.next()) {
                        list.add(results.getLong("class_id"));
                    }
                    return List.copyOf(list);
                }
            }
        });
    }

    @Override
    public List<Quiz> findAssignedQuizzesForStudent(long studentId) {
        if (studentId <= 0) return List.of();
        return withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(FIND_ASSIGNED_FOR_STUDENT)) {
                statement.setLong(1, studentId);
                try (ResultSet results = statement.executeQuery()) {
                    List<Quiz> list = new ArrayList<>();
                    while (results.next()) {
                        list.add(mapRow(results));
                    }
                    return List.copyOf(list);
                }
            }
        });
    }

    @Override
    public List<ResultDto> findResultsByQuizId(long quizId) {
        if (quizId <= 0) return List.of();
        return withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(FIND_RESULTS_BY_QUIZ)) {
                statement.setLong(1, quizId);
                try (ResultSet results = statement.executeQuery()) {
                    List<ResultDto> list = new ArrayList<>();
                    while (results.next()) {
                        OffsetDateTime submitted = results.getObject("submitted_at", OffsetDateTime.class);
                        list.add(new ResultDto(
                            results.getLong("attempt_id"),
                            results.getLong("quiz_id"),
                            results.getString("quiz_title"),
                            results.getString("student_name"),
                            results.getBigDecimal("score").doubleValue(),
                            results.getInt("correct_count"),
                            results.getInt("total_questions"),
                            results.getBoolean("passed"),
                            SubmitReason.valueOf(results.getString("submit_reason")),
                            submitted != null ? submitted.toInstant() : Instant.now()
                        ));
                    }
                    return List.copyOf(list);
                }
            }
        });
    }

    private static Quiz mapRow(ResultSet results) throws SQLException {
        OffsetDateTime opened = results.getObject("opened_at", OffsetDateTime.class);
        OffsetDateTime closed = results.getObject("closed_at", OffsetDateTime.class);
        return Quiz.restore(
            results.getLong("id"),
            results.getString("title"),
            results.getString("description"),
            results.getString("category"),
            results.getInt("duration_minutes"),
            results.getBigDecimal("pass_score").doubleValue(),
            QuizStatus.valueOf(results.getString("status")),
            results.getString("pin"),
            AntiCheatPolicyType.valueOf(results.getString("anticheat_policy")),
            results.getInt("max_violations"),
            results.getLong("created_by"),
            results.getObject("created_at", OffsetDateTime.class).toInstant(),
            opened == null ? null : opened.toInstant(),
            closed == null ? null : closed.toInstant()
        );
    }

    private RuntimeException translateException(SQLException error) {
        if (error instanceof PSQLException psql && "23505".equals(psql.getSQLState())) {
            return new ConflictException("Dữ liệu bài kiểm tra bị trùng lặp", error);
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

    private <T> T withTransaction(ConnectionAction<T> action) {
        try (Connection connection = dataSource.getConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                T result = action.execute(connection);
                connection.commit();
                return result;
            } catch (Exception ex) {
                connection.rollback();
                if (ex instanceof RuntimeException re) throw re;
                throw new QuizException(ErrorCode.DATABASE_ERROR, "Transaction failed", ex);
            } finally {
                connection.setAutoCommit(originalAutoCommit);
            }
        } catch (SQLException error) {
            throw new QuizException(ErrorCode.DATABASE_ERROR, "Database transaction failed", error);
        }
    }

    @FunctionalInterface
    private interface ConnectionAction<T> {
        T execute(Connection connection) throws SQLException;
    }
}
