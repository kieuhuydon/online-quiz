package quiz.server.repository.jdbc;

import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import quiz.common.enums.Difficulty;
import quiz.common.enums.Difficulty;
import quiz.common.protocol.ErrorCode;
import quiz.server.domain.Question;
import quiz.server.exception.QuizException;
import quiz.server.repository.QuestionRepository;

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
 * PostgreSQL question persistence. Caller-owned connections retain their transaction lifecycle.
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * PostgreSQL JDBC implementation for QuestionRepository.
 */
public final class JdbcQuestionRepository implements QuestionRepository {
    private static final String FIND_ID = """
        SELECT id, content, option_a, option_b, option_c, option_d, correct_option,
               topic, difficulty, active, created_by, created_at, updated_at
        FROM questions WHERE id = ?
        """;
    private static final String FIND_ALL = """
        SELECT id, content, option_a, option_b, option_c, option_d, correct_option,
               topic, difficulty, active, created_by, created_at, updated_at
        FROM questions ORDER BY id ASC
        """;
    private static final String FIND_ACTIVE = """
    private static final String FIND_ALL_ACTIVE = """
        SELECT id, content, option_a, option_b, option_c, option_d, correct_option,
               topic, difficulty, active, created_by, created_at, updated_at
        FROM questions WHERE active = TRUE ORDER BY id ASC
        """;
    private static final String FIND_BY_FILTER = """
        SELECT id, content, option_a, option_b, option_c, option_d, correct_option,
               topic, difficulty, active, created_by, created_at, updated_at
        FROM questions WHERE active = TRUE
        AND (? IS NULL OR topic = ?)
        AND (? IS NULL OR difficulty = ?)
        ORDER BY id ASC
        """;
    private static final String INSERT = """
        INSERT INTO questions (content, option_a, option_b, option_c, option_d,
                               correct_option, topic, difficulty, created_by)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        RETURNING id, content, option_a, option_b, option_c, option_d, correct_option,
                  topic, difficulty, active, created_by, created_at, updated_at
        """;
    private static final String UPDATE = """
        UPDATE questions SET content = ?, option_a = ?, option_b = ?, option_c = ?,
                             option_d = ?, correct_option = ?, topic = ?,
                             difficulty = ?, updated_at = now()
        WHERE id = ? AND active = TRUE
        RETURNING id, content, option_a, option_b, option_c, option_d, correct_option,
                  topic, difficulty, active, created_by, created_at, updated_at
        """;
    private static final String DEACTIVATE = "UPDATE questions SET active = FALSE, updated_at = now() WHERE id = ?";
    private static final String EXISTS_ID = "SELECT 1 FROM questions WHERE id = ?";
    private final DataSource dataSource;

    public JdbcQuestionRepository(DataSource dataSource) {
        if (dataSource == null) {
            throw new IllegalArgumentException("DataSource cannot be null");
        }
        this.dataSource = dataSource;
    }

    @Override
    public Optional<Question> findById(long id) {
        validateId(id);
        return withConnection(connection -> findOne(connection, FIND_ID,
            statement -> statement.setLong(1, id)));
    }

    @Override
    public Optional<Question> findById(Connection connection, long id) {
        validateId(id);
        return onConnection(connection, () -> findOne(connection, FIND_ID,
            statement -> statement.setLong(1, id)));
    }

    @Override
    public List<Question> findAll() {
        return withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(FIND_ALL);
                 ResultSet rows = statement.executeQuery()) {
                List<Question> questions = new ArrayList<>();
                while (rows.next()) {
                    questions.add(mapQuestion(rows));
                }
                return questions;
    private static final String FIND_BY_QUIZ_ID = """
        SELECT q.id, q.content, q.option_a, q.option_b, q.option_c, q.option_d, q.correct_option,
               q.topic, q.difficulty, q.active, q.created_by, q.created_at, q.updated_at
        FROM quiz_questions qq
        JOIN questions q ON qq.question_id = q.id
        WHERE qq.quiz_id = ?
        ORDER BY qq.position ASC
        """;
    private static final String INSERT = """
        INSERT INTO questions (content, option_a, option_b, option_c, option_d, correct_option, topic, difficulty, active, created_by)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        RETURNING id, content, option_a, option_b, option_c, option_d, correct_option,
                  topic, difficulty, active, created_by, created_at, updated_at
        """;
    private static final String COUNT_ACTIVE = "SELECT COUNT(*) FROM questions WHERE active = TRUE";

    private final DataSource dataSource;

    public JdbcQuestionRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "DataSource cannot be null");
    }

    @Override
    public Question save(Question question) {
        if (question == null || question.id() != 0 || question.createdAt() != null) {
            throw new IllegalArgumentException("Only new question can be saved");
        }
        return withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(INSERT)) {
                statement.setString(1, question.content());
                statement.setString(2, question.optionA());
                statement.setString(3, question.optionB());
                statement.setString(4, question.optionC());
                statement.setString(5, question.optionD());
                statement.setString(6, String.valueOf(question.correctOption()));
                statement.setString(7, question.topic());
                statement.setString(8, question.difficulty().name());
                statement.setBoolean(9, question.active());
                statement.setLong(10, question.createdBy());
                try (ResultSet results = statement.executeQuery()) {
                    if (!results.next()) {
                        throw new QuizException(ErrorCode.DATABASE_ERROR, "Failed to insert question");
                    }
                    return mapRow(results);
                }
            }
        });
    }

    @Override
    public List<Question> findActive() {
        return withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(FIND_ACTIVE);
                 ResultSet rows = statement.executeQuery()) {
                List<Question> questions = new ArrayList<>();
                while (rows.next()) {
                    questions.add(mapQuestion(rows));
                }
                return questions;
    public Optional<Question> findById(long id) {
        if (id <= 0) throw new IllegalArgumentException("Question ID must be positive");
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
    public List<Question> findByFilter(String topic, Difficulty difficulty) {
        return withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(FIND_BY_FILTER)) {
                statement.setString(1, topic);
                statement.setString(2, topic);
                statement.setString(3, difficulty == null ? null : difficulty.name());
                statement.setString(4, difficulty == null ? null : difficulty.name());
                try (ResultSet rows = statement.executeQuery()) {
                    List<Question> questions = new ArrayList<>();
                    while (rows.next()) {
                        questions.add(mapQuestion(rows));
                    }
                    return questions;
                }
    public List<Question> findAllActive() {
        return withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(FIND_ALL_ACTIVE);
                 ResultSet results = statement.executeQuery()) {
                List<Question> list = new ArrayList<>();
                while (results.next()) {
                    list.add(mapRow(results));
                }
                return List.copyOf(list);
            }
        });
    }

    @Override
    public Question save(Question question) {
        validateNewQuestion(question);
        return withConnection(connection -> insert(connection, question));
    }

    @Override
    public Question save(Connection connection, Question question) {
        validateNewQuestion(question);
        return onConnection(connection, () -> insert(connection, question));
    }

    @Override
    public boolean update(Question question) {
        validatePersistedQuestion(question);
        return withConnection(connection -> updateQuestion(connection, question));
    }

    @Override
    public boolean update(Connection connection, Question question) {
        validatePersistedQuestion(question);
        return onConnection(connection, () -> updateQuestion(connection, question));
    }

    @Override
    public boolean deactivate(long id) {
        validateId(id);
        return withConnection(connection -> deactivateQuestion(connection, id));
    }

    @Override
    public boolean deactivate(Connection connection, long id) {
        validateId(id);
        return onConnection(connection, () -> deactivateQuestion(connection, id));
    }

    @Override
    public boolean existsById(long id) {
        validateId(id);
        return withConnection(connection -> exists(connection, EXISTS_ID, id));
    }

    private Optional<Question> findOne(Connection connection, String sql,
                                       StatementBinder binder) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(mapQuestion(rows)) : Optional.empty();
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

    private Question insert(Connection connection, Question question) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT)) {
            statement.setString(1, question.content());
            statement.setString(2, question.optionA());
            statement.setString(3, question.optionB());
            statement.setString(4, question.optionC());
            statement.setString(5, question.optionD());
            statement.setString(6, String.valueOf(question.correctOption()));
            statement.setString(7, question.topic());
            statement.setString(8, question.difficulty().name());
            statement.setLong(9, question.createdBy());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("Question insertion returned no row");
                }
                return mapQuestion(rows);
            }
        } catch (SQLException error) {
            throw translateInsertError(error);
        }
    }

    private boolean updateQuestion(Connection connection, Question question) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(UPDATE)) {
            statement.setString(1, question.content());
            statement.setString(2, question.optionA());
            statement.setString(3, question.optionB());
            statement.setString(4, question.optionC());
            statement.setString(5, question.optionD());
            statement.setString(6, String.valueOf(question.correctOption()));
            statement.setString(7, question.topic());
            statement.setString(8, question.difficulty().name());
            statement.setLong(9, question.id());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        } catch (SQLException error) {
            throw databaseError(error);
        }
    }

    private boolean deactivateQuestion(Connection connection, long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(DEACTIVATE)) {
            statement.setLong(1, id);
            return statement.executeUpdate() > 0;
        } catch (SQLException error) {
            throw databaseError(error);
        }
    }

    private Question mapQuestion(ResultSet rows) throws SQLException {
        try {
            OffsetDateTime createdAt = rows.getObject("created_at", OffsetDateTime.class);
            OffsetDateTime updatedAt = rows.getObject("updated_at", OffsetDateTime.class);
            return Question.restore(rows.getLong("id"), rows.getString("content"),
                rows.getString("option_a"), rows.getString("option_b"),
                rows.getString("option_c"), rows.getString("option_d"),
                rows.getString("correct_option").charAt(0),
                rows.getString("topic"), Difficulty.valueOf(rows.getString("difficulty")),
                rows.getBoolean("active"), rows.getLong("created_by"),
                createdAt == null ? null : createdAt.toInstant(),
                updatedAt == null ? null : updatedAt.toInstant());
        } catch (IllegalArgumentException | NullPointerException error) {
            throw new SQLException("Invalid stored question data", error);
        }
    }

    private SQLException translateInsertError(SQLException error) {
        if (error instanceof PSQLException postgres && "23505".equals(error.getSQLState())) {
            ServerErrorMessage detail = postgres.getServerErrorMessage();
            if (detail != null && "questions_pkey".equals(detail.getConstraint())) {
                throw new QuizException(quiz.common.protocol.ErrorCode.DATABASE_ERROR,
                    "Database operation failed", error);
            }
        }
        return error;
    }

    private QuizException databaseError(SQLException error) {
        return new QuizException(quiz.common.protocol.ErrorCode.DATABASE_ERROR,
            "Database operation failed", error);
    public List<Question> findByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        StringBuilder sql = new StringBuilder("""
            SELECT id, content, option_a, option_b, option_c, option_d, correct_option,
                   topic, difficulty, active, created_by, created_at, updated_at
            FROM questions WHERE id IN (
            """);
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) sql.append(", ");
            sql.append("?");
        }
        sql.append(")");
        return withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
                for (int i = 0; i < ids.size(); i++) {
                    statement.setLong(i + 1, ids.get(i));
                }
                try (ResultSet results = statement.executeQuery()) {
                    List<Question> list = new ArrayList<>();
                    while (results.next()) {
                        list.add(mapRow(results));
                    }
                    return List.copyOf(list);
                }
            }
        });
    }

    @Override
    public List<Question> findQuestionsByQuizId(long quizId) {
        if (quizId <= 0) throw new IllegalArgumentException("Quiz ID must be positive");
        return withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(FIND_BY_QUIZ_ID)) {
                statement.setLong(1, quizId);
                try (ResultSet results = statement.executeQuery()) {
                    List<Question> list = new ArrayList<>();
                    while (results.next()) {
                        list.add(mapRow(results));
                    }
                    return List.copyOf(list);
                }
            }
        });
    }

    @Override
    public int countActive() {
        return withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(COUNT_ACTIVE);
                 ResultSet results = statement.executeQuery()) {
                return results.next() ? results.getInt(1) : 0;
            }
        });
    }

    private static Question mapRow(ResultSet results) throws SQLException {
        return Question.restore(
            results.getLong("id"),
            results.getString("content"),
            results.getString("option_a"),
            results.getString("option_b"),
            results.getString("option_c"),
            results.getString("option_d"),
            results.getString("correct_option").charAt(0),
            results.getString("topic"),
            Difficulty.valueOf(results.getString("difficulty")),
            results.getBoolean("active"),
            results.getLong("created_by"),
            results.getObject("created_at", OffsetDateTime.class).toInstant(),
            results.getObject("updated_at", OffsetDateTime.class).toInstant()
        );
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

    private void validateNewQuestion(Question question) {
        if (question == null || question.id() != 0 || question.createdAt() != null
            || question.updatedAt() != null) {
            throw new IllegalArgumentException("Only a new question can be inserted");
        }
    }

    private void validatePersistedQuestion(Question question) {
        if (question == null || question.id() <= 0) {
            throw new IllegalArgumentException("Only a persisted question can be updated");
        }
    }

    private void validateId(long id) {
        if (id <= 0) {
            throw new IllegalArgumentException("Question ID must be positive");
            throw new QuizException(ErrorCode.DATABASE_ERROR, "Database operation failed", error);
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
