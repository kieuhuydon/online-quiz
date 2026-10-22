package quiz.server.repository.jdbc;

import quiz.common.enums.QuizStatus;
import quiz.server.domain.Quiz;
import quiz.server.exception.QuizException;
import quiz.server.repository.QuizRepository;
import quiz.common.enums.AntiCheatPolicyType;
import quiz.common.enums.QuizStatus;

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

/**
 * PostgreSQL quiz persistence. Caller-owned connections retain their transaction lifecycle.
 */
public final class JdbcQuizRepository implements QuizRepository {
    private static final String FIND_ID = """
        SELECT id, title, description, category, duration_minutes, pass_score,
               status, pin, anticheat_policy, max_violations, created_by,
               created_at, opened_at, closed_at
        FROM quizzes WHERE id = ?
        """;
    private static final String FIND_ALL = """
        SELECT id, title, description, category, duration_minutes, pass_score,
               status, pin, anticheat_policy, max_violations, created_by,
               created_at, opened_at, closed_at
        FROM quizzes ORDER BY id ASC
        """;
    private static final String FIND_BY_STATUS = """
        SELECT id, title, description, category, duration_minutes, pass_score,
               status, pin, anticheat_policy, max_violations, created_by,
               created_at, opened_at, closed_at
        FROM quizzes WHERE status = ? ORDER BY id ASC
        """;
    private static final String INSERT = """
        INSERT INTO quizzes (title, description, category, duration_minutes,
                             pass_score, status, pin, anticheat_policy, max_violations, created_by)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        RETURNING id, title, description, category, duration_minutes, pass_score,
                  status, pin, anticheat_policy, max_violations, created_by,
                  created_at, opened_at, closed_at
        """;
    private static final String UPDATE = """
        UPDATE quizzes SET title = ?, description = ?, category = ?,
                           duration_minutes = ?, pass_score = ?,
                           status = ?, pin = ?, anticheat_policy = ?,
                           max_violations = ?
        WHERE id = ?
        RETURNING id, title, description, category, duration_minutes, pass_score,
                  status, pin, anticheat_policy, max_violations, created_by,
                  created_at, opened_at, closed_at
        """;
    private static final String SET_QUESTIONS_DELETE = "DELETE FROM quiz_questions WHERE quiz_id = ?";
    private static final String SET_QUESTIONS_INSERT = """
        INSERT INTO quiz_questions (quiz_id, question_id, position)
        VALUES (?, ?, ?)
        """;
    private static final String FIND_QUESTION_IDS = """
        SELECT question_id FROM quiz_questions WHERE quiz_id = ? ORDER BY position ASC
        """;
    private static final String EXISTS_ID = "SELECT 1 FROM quizzes WHERE id = ?";
    private final DataSource dataSource;

    public JdbcQuizRepository(DataSource dataSource) {
        if (dataSource == null) {
            throw new IllegalArgumentException("DataSource cannot be null");
        }
        this.dataSource = dataSource;
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
    public boolean setQuestions(long quizId, List<Long> questionIds) {
        validateId(quizId);
        return withConnection(connection -> setQuestions(connection, quizId, questionIds));
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
    public boolean existsById(long id) {
        validateId(id);
        return withConnection(connection -> exists(connection, EXISTS_ID, id));
    }

    private Optional<Quiz> findOne(Connection connection, String sql,
                                   StatementBinder binder) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(mapQuiz(rows)) : Optional.empty();
            }
        }
    }

    private boolean exists(Connection connection, String sql, long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
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
        // Verify the quiz exists first.
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
