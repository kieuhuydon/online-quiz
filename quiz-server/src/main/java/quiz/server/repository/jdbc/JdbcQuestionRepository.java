package quiz.server.repository.jdbc;

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
    private static final String FIND_ALL_ACTIVE = """
        SELECT id, content, option_a, option_b, option_c, option_d, correct_option,
               topic, difficulty, active, created_by, created_at, updated_at
        FROM questions WHERE active = TRUE ORDER BY id ASC
        """;
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
            throw new QuizException(ErrorCode.DATABASE_ERROR, "Database operation failed", error);
        }
    }

    @FunctionalInterface
    private interface ConnectionAction<T> {
        T execute(Connection connection) throws SQLException;
    }
}
