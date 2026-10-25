package quiz.server.repository.jdbc;

import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import quiz.common.dto.ResultDto;
import quiz.common.enums.AntiCheatPolicyType;
import quiz.common.enums.QuizStatus;
import quiz.common.enums.SubmitReason;
import quiz.common.protocol.ErrorCode;
import quiz.server.domain.Quiz;
import quiz.server.exception.QuizException;
import quiz.server.repository.QuizRepository;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * PostgreSQL quiz persistence. Caller-owned connections retain their transaction lifecycle.
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
        FROM quizzes ORDER BY id ASC
        """;
    private static final String FIND_BY_STATUS = """
        SELECT id, title, description, category, duration_minutes, pass_score, status, pin,
               anticheat_policy, max_violations, created_by, created_at, opened_at, closed_at
        FROM quizzes WHERE status = ? ORDER BY id ASC
        """;
    private static final String FIND_BY_CREATOR = """
        SELECT id, title, description, category, duration_minutes, pass_score, status, pin,
               anticheat_policy, max_violations, created_by, created_at, opened_at, closed_at
        FROM quizzes WHERE created_by = ? ORDER BY id DESC
        """;
    private static final String INSERT = """
        INSERT INTO quizzes (title, description, category, duration_minutes, pass_score,
                             status, pin, anticheat_policy, max_violations, created_by)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        RETURNING id, title, description, category, duration_minutes, pass_score, status,
                  pin, anticheat_policy, max_violations, created_by, created_at, opened_at, closed_at
        """;
    private static final String UPDATE = """
        UPDATE quizzes SET title = ?, description = ?, category = ?, duration_minutes = ?,
                           pass_score = ?, status = ?, pin = ?, anticheat_policy = ?,
                           max_violations = ?
        WHERE id = ?
        RETURNING id, title, description, category, duration_minutes, pass_score, status,
                  pin, anticheat_policy, max_violations, created_by, created_at, opened_at, closed_at
        """;
    private static final String UPDATE_STATUS = """
        UPDATE quizzes SET status = ?, pin = ?, opened_at = ?, closed_at = ?
        WHERE id = ?
        """;
    private static final String EXISTS_ID = "SELECT 1 FROM quizzes WHERE id = ?";
    private static final String FIND_QUESTION_IDS = """
        SELECT question_id FROM quiz_questions WHERE quiz_id = ? ORDER BY position ASC
        """;
    private static final String SET_QUESTIONS_DELETE = "DELETE FROM quiz_questions WHERE quiz_id = ?";
    private static final String SET_QUESTIONS_INSERT = """
        INSERT INTO quiz_questions (quiz_id, question_id, position) VALUES (?, ?, ?)
        """;
    private static final String COUNT_QUESTIONS = "SELECT COUNT(*) FROM quiz_questions WHERE quiz_id = ?";
    private static final String ASSIGN_CLASS = """
        INSERT INTO quiz_assignments (quiz_id, class_id, assigned_by)
        VALUES (?, ?, ?)
        ON CONFLICT (quiz_id, class_id) DO NOTHING
        """;
    private static final String FIND_ASSIGNED_CLASS_IDS = "SELECT class_id FROM quiz_assignments WHERE quiz_id = ?";
    private static final String FIND_ASSIGNED_FOR_STUDENT = """
        SELECT DISTINCT q.id, q.title, q.description, q.category, q.duration_minutes, q.pass_score,
               q.status, q.pin, q.anticheat_policy, q.max_violations, q.created_by,
               q.created_at, q.opened_at, q.closed_at
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
    public Optional<Quiz> findById(long id) {
        validateId(id);
        return withConnection(connection -> findOne(connection, FIND_ID,
            statement -> statement.setLong(1, id)));
    }

    @Override
    public Optional<Quiz> findById(Connection connection, long id) {
        validateId(id);
        return onConnection(connection, () -> findOne(connection, FIND_ID,
            statement -> statement.setLong(1, id)));
    }

    @Override
    public List<Quiz> findAll() {
        return withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(FIND_ALL);
                 ResultSet rows = statement.executeQuery()) {
                List<Quiz> quizzes = new ArrayList<>();
                while (rows.next()) {
                    quizzes.add(mapQuiz(rows));
                }
                return quizzes;
            }
        });
    }

    @Override
    public List<Quiz> findByStatus(QuizStatus status) {
        if (status == null) {
            return findAll();
        }
        return withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(FIND_BY_STATUS)) {
                statement.setString(1, status.name());
                try (ResultSet rows = statement.executeQuery()) {
                    List<Quiz> quizzes = new ArrayList<>();
                    while (rows.next()) {
                        quizzes.add(mapQuiz(rows));
                    }
                    return quizzes;
                }
            }
        });
    }

    @Override
    public List<Quiz> findByCreator(long teacherId) {
        validateId(teacherId);
        return withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(FIND_BY_CREATOR)) {
                statement.setLong(1, teacherId);
                try (ResultSet rows = statement.executeQuery()) {
                    List<Quiz> quizzes = new ArrayList<>();
                    while (rows.next()) {
                        quizzes.add(mapQuiz(rows));
                    }
                    return List.copyOf(quizzes);
                }
            }
        });
    }

    @Override
    public Quiz save(Quiz quiz) {
        validateNewQuiz(quiz);
        return withConnection(connection -> insert(connection, quiz));
    }

    @Override
    public Quiz save(Connection connection, Quiz quiz) {
        validateNewQuiz(quiz);
        return onConnection(connection, () -> insert(connection, quiz));
    }

    @Override
    public boolean update(Quiz quiz) {
        validatePersistedQuiz(quiz);
        return withConnection(connection -> updateQuiz(connection, quiz));
    }

    @Override
    public boolean update(Connection connection, Quiz quiz) {
        validatePersistedQuiz(quiz);
        return onConnection(connection, () -> updateQuiz(connection, quiz));
    }

    @Override
    public boolean updateStatus(long quizId, QuizStatus status, String pin, Instant openedAt, Instant closedAt) {
        validateId(quizId);
        if (status == null) throw new IllegalArgumentException("Status cannot be null");
        return withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(UPDATE_STATUS)) {
                statement.setString(1, status.name());
                if (pin != null) statement.setString(2, pin);
                else statement.setNull(2, Types.VARCHAR);
                if (openedAt != null) statement.setObject(3, OffsetDateTime.ofInstant(openedAt, ZoneOffset.UTC));
                else statement.setNull(3, Types.TIMESTAMP_WITH_TIMEZONE);
                if (closedAt != null) statement.setObject(4, OffsetDateTime.ofInstant(closedAt, ZoneOffset.UTC));
                else statement.setNull(4, Types.TIMESTAMP_WITH_TIMEZONE);
                statement.setLong(5, quizId);
                return statement.executeUpdate() > 0;
            }
        });
    }

    @Override
    public boolean setQuestions(long quizId, List<Long> questionIds) {
        validateId(quizId);
        return withConnection(connection -> replaceQuestions(connection, quizId, questionIds));
    }

    @Override
    public boolean setQuestions(Connection connection, long quizId, List<Long> questionIds) {
        validateId(quizId);
        return onConnection(connection, () -> replaceQuestions(connection, quizId, questionIds));
    }

    @Override
    public List<Long> findQuestionIdsByQuiz(long quizId) {
        validateId(quizId);
        return withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(FIND_QUESTION_IDS)) {
                statement.setLong(1, quizId);
                try (ResultSet rows = statement.executeQuery()) {
                    List<Long> ids = new ArrayList<>();
                    while (rows.next()) {
                        ids.add(rows.getLong(1));
                    }
                    return ids;
                }
            }
        });
    }

    @Override
    public int countQuestions(long quizId) {
        validateId(quizId);
        return withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(COUNT_QUESTIONS)) {
                statement.setLong(1, quizId);
                try (ResultSet rows = statement.executeQuery()) {
                    return rows.next() ? rows.getInt(1) : 0;
                }
            }
        });
    }

    @Override
    public void assignToClass(long quizId, long classId, long assignedBy) {
        validateId(quizId);
        validateId(classId);
        validateId(assignedBy);
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
        validateId(quizId);
        return withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(FIND_ASSIGNED_CLASS_IDS)) {
                statement.setLong(1, quizId);
                try (ResultSet rows = statement.executeQuery()) {
                    List<Long> ids = new ArrayList<>();
                    while (rows.next()) {
                        ids.add(rows.getLong(1));
                    }
                    return List.copyOf(ids);
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
                try (ResultSet rows = statement.executeQuery()) {
                    List<Quiz> quizzes = new ArrayList<>();
                    while (rows.next()) {
                        quizzes.add(mapQuiz(rows));
                    }
                    return List.copyOf(quizzes);
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
    }

    private Quiz insert(Connection connection, Quiz quiz) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT)) {
            statement.setString(1, quiz.title());
            if (quiz.description() == null) {
                statement.setNull(2, Types.VARCHAR);
            } else {
                statement.setString(2, quiz.description());
            }
            if (quiz.category() == null) {
                statement.setNull(3, Types.VARCHAR);
            } else {
                statement.setString(3, quiz.category());
            }
            statement.setInt(4, quiz.durationMinutes());
            statement.setDouble(5, quiz.passScore());
            statement.setString(6, quiz.status().name());
            if (quiz.pin() == null) {
                statement.setNull(7, Types.VARCHAR);
            } else {
                statement.setString(7, quiz.pin());
            }
            statement.setString(8, quiz.anticheatPolicy().name());
            statement.setInt(9, quiz.maxViolations());
            statement.setLong(10, quiz.createdBy());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("Quiz insertion returned no row");
                }
                return mapQuiz(rows);
            }
        } catch (SQLException error) {
            throw databaseError(error);
        }
    }

    private boolean updateQuiz(Connection connection, Quiz quiz) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(UPDATE)) {
            statement.setString(1, quiz.title());
            if (quiz.description() == null) {
                statement.setNull(2, Types.VARCHAR);
            } else {
                statement.setString(2, quiz.description());
            }
            if (quiz.category() == null) {
                statement.setNull(3, Types.VARCHAR);
            } else {
                statement.setString(3, quiz.category());
            }
            statement.setInt(4, quiz.durationMinutes());
            statement.setDouble(5, quiz.passScore());
            statement.setString(6, quiz.status().name());
            if (quiz.pin() == null) {
                statement.setNull(7, Types.VARCHAR);
            } else {
                statement.setString(7, quiz.pin());
            }
            statement.setString(8, quiz.anticheatPolicy().name());
            statement.setInt(9, quiz.maxViolations());
            statement.setLong(10, quiz.id());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        } catch (SQLException error) {
            throw databaseError(error);
        }
    }

    private boolean replaceQuestions(Connection connection, long quizId, List<Long> questionIds)
        throws SQLException {
        if (!exists(connection, EXISTS_ID, quizId)) {
            return false;
        }
        try (PreparedStatement delete = connection.prepareStatement(SET_QUESTIONS_DELETE)) {
            delete.setLong(1, quizId);
            delete.executeUpdate();
        }
        if (questionIds == null || questionIds.isEmpty()) {
            return true;
        }
        try (PreparedStatement insert = connection.prepareStatement(SET_QUESTIONS_INSERT)) {
            int position = 1;
            for (Long questionId : questionIds) {
                insert.setLong(1, quizId);
                insert.setLong(2, questionId);
                insert.setInt(3, position++);
                insert.addBatch();
            }
            insert.executeBatch();
        }
        return true;
    }

    private Quiz mapQuiz(ResultSet rows) throws SQLException {
        try {
            OffsetDateTime createdAt = rows.getObject("created_at", OffsetDateTime.class);
            OffsetDateTime openedAt = rows.getObject("opened_at", OffsetDateTime.class);
            OffsetDateTime closedAt = rows.getObject("closed_at", OffsetDateTime.class);
            return Quiz.restore(rows.getLong("id"), rows.getString("title"),
                rows.getString("description"), rows.getString("category"),
                rows.getInt("duration_minutes"), rows.getDouble("pass_score"),
                QuizStatus.valueOf(rows.getString("status")),
                rows.getString("pin"),
                AntiCheatPolicyType.valueOf(rows.getString("anticheat_policy")),
                rows.getInt("max_violations"), rows.getLong("created_by"),
                createdAt == null ? null : createdAt.toInstant(),
                openedAt == null ? null : openedAt.toInstant(),
                closedAt == null ? null : closedAt.toInstant());
        } catch (IllegalArgumentException | NullPointerException error) {
            throw new SQLException("Invalid stored quiz data", error);
        }
    }

    private QuizException databaseError(SQLException error) {
        return new QuizException(quiz.common.protocol.ErrorCode.DATABASE_ERROR,
            "Database operation failed", error);
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

    private void validateNewQuiz(Quiz quiz) {
        if (quiz == null || quiz.id() != 0 || quiz.createdAt() != null) {
            throw new IllegalArgumentException("Only a new quiz can be inserted");
        }
    }

    private void validatePersistedQuiz(Quiz quiz) {
        if (quiz == null || quiz.id() <= 0) {
            throw new IllegalArgumentException("Only a persisted quiz can be updated");
        }
    }

    private void validateId(long id) {
        if (id <= 0) {
            throw new IllegalArgumentException("Quiz ID must be positive");
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
