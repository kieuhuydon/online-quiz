package quiz.server.repository.jdbc;

import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import quiz.common.enums.Difficulty;
import quiz.common.enums.QuestionType;
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
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * PostgreSQL question persistence.
 * Hỗ trợ tương thích cả khi database có hoặc chưa có cột question_type.
 */
public final class JdbcQuestionRepository implements QuestionRepository {
    private static final String COUNT_ACTIVE = "SELECT COUNT(*) FROM questions WHERE active = TRUE";
    private static final String DEACTIVATE = "UPDATE questions SET active = FALSE, updated_at = now() WHERE id = ?";
    private static final String DEACTIVATE_BATCH = "UPDATE questions SET active = FALSE, updated_at = now() WHERE id = ANY(?)";
    private static final String EXISTS_ID = "SELECT 1 FROM questions WHERE id = ?";

    private final DataSource dataSource;
    private final boolean hasQuestionTypeColumn;
    private final boolean hasExplanationColumn;

    private final String findIdSql;
    private final String findAllSql;
    private final String findActiveSql;
    private final String findByFilterSql;
    private final String insertSql;
    private final String updateSql;
    private final String findByQuizIdSql;

    public JdbcQuestionRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "DataSource cannot be null");
        ensureSchema();
        this.hasQuestionTypeColumn = checkColumnExists("question_type");
        this.hasExplanationColumn = checkColumnExists("explanation");

        if (hasQuestionTypeColumn) {
            findIdSql = "SELECT id, content, option_a, option_b, option_c, option_d, correct_option, "
                + "topic, difficulty, question_type" + (hasExplanationColumn ? ", explanation" : "") + ", active, created_by, created_at, updated_at "
                + "FROM questions WHERE id = ?";
            findAllSql = "SELECT id, content, option_a, option_b, option_c, option_d, correct_option, "
                + "topic, difficulty, question_type" + (hasExplanationColumn ? ", explanation" : "") + ", active, created_by, created_at, updated_at "
                + "FROM questions ORDER BY id ASC";
            findActiveSql = "SELECT id, content, option_a, option_b, option_c, option_d, correct_option, "
                + "topic, difficulty, question_type" + (hasExplanationColumn ? ", explanation" : "") + ", active, created_by, created_at, updated_at "
                + "FROM questions WHERE active = TRUE ORDER BY id ASC";
            findByFilterSql = "SELECT id, content, option_a, option_b, option_c, option_d, correct_option, "
                + "topic, difficulty, question_type" + (hasExplanationColumn ? ", explanation" : "") + ", active, created_by, created_at, updated_at "
                + "FROM questions WHERE active = TRUE "
                + "AND (? IS NULL OR topic = ?) AND (? IS NULL OR difficulty = ?) ORDER BY id ASC";
            insertSql = hasExplanationColumn
                ? "INSERT INTO questions (content, option_a, option_b, option_c, option_d, correct_option, topic, difficulty, question_type, explanation, created_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id, content, option_a, option_b, option_c, option_d, correct_option, topic, difficulty, question_type, explanation, active, created_by, created_at, updated_at"
                : "INSERT INTO questions (content, option_a, option_b, option_c, option_d, correct_option, topic, difficulty, question_type, created_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id, content, option_a, option_b, option_c, option_d, correct_option, topic, difficulty, question_type, active, created_by, created_at, updated_at";
            updateSql = hasExplanationColumn
                ? "UPDATE questions SET content = ?, option_a = ?, option_b = ?, option_c = ?, option_d = ?, correct_option = ?, topic = ?, difficulty = ?, question_type = ?, explanation = ?, updated_at = now() WHERE id = ? AND active = TRUE RETURNING id, content, option_a, option_b, option_c, option_d, correct_option, topic, difficulty, question_type, explanation, active, created_by, created_at, updated_at"
                : "UPDATE questions SET content = ?, option_a = ?, option_b = ?, option_c = ?, option_d = ?, correct_option = ?, topic = ?, difficulty = ?, question_type = ?, updated_at = now() WHERE id = ? AND active = TRUE RETURNING id, content, option_a, option_b, option_c, option_d, correct_option, topic, difficulty, question_type, active, created_by, created_at, updated_at";
            findByQuizIdSql = "SELECT q.id, q.content, q.option_a, q.option_b, q.option_c, q.option_d, q.correct_option, "
                + "q.topic, q.difficulty, q.question_type" + (hasExplanationColumn ? ", q.explanation" : "") + ", q.active, q.created_by, q.created_at, q.updated_at "
                + "FROM quiz_questions qq "
                + "JOIN questions q ON qq.question_id = q.id "
                + "WHERE qq.quiz_id = ? ORDER BY qq.position ASC";
        } else {
            findIdSql = "SELECT id, content, option_a, option_b, option_c, option_d, correct_option, "
                + "topic, difficulty" + (hasExplanationColumn ? ", explanation" : "") + ", active, created_by, created_at, updated_at "
                + "FROM questions WHERE id = ?";
            findAllSql = "SELECT id, content, option_a, option_b, option_c, option_d, correct_option, "
                + "topic, difficulty" + (hasExplanationColumn ? ", explanation" : "") + ", active, created_by, created_at, updated_at "
                + "FROM questions ORDER BY id ASC";
            findActiveSql = "SELECT id, content, option_a, option_b, option_c, option_d, correct_option, "
                + "topic, difficulty" + (hasExplanationColumn ? ", explanation" : "") + ", active, created_by, created_at, updated_at "
                + "FROM questions WHERE active = TRUE ORDER BY id ASC";
            findByFilterSql = "SELECT id, content, option_a, option_b, option_c, option_d, correct_option, "
                + "topic, difficulty" + (hasExplanationColumn ? ", explanation" : "") + ", active, created_by, created_at, updated_at "
                + "FROM questions WHERE active = TRUE "
                + "AND (? IS NULL OR topic = ?) AND (? IS NULL OR difficulty = ?) ORDER BY id ASC";
            insertSql = "INSERT INTO questions (content, option_a, option_b, option_c, option_d, "
                + "correct_option, topic, difficulty, created_by) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) "
                + "RETURNING id, content, option_a, option_b, option_c, option_d, correct_option, "
                + "topic, difficulty, active, created_by, created_at, updated_at";
            updateSql = "UPDATE questions SET content = ?, option_a = ?, option_b = ?, option_c = ?, "
                + "option_d = ?, correct_option = ?, topic = ?, difficulty = ?, updated_at = now() "
                + "WHERE id = ? AND active = TRUE "
                + "RETURNING id, content, option_a, option_b, option_c, option_d, correct_option, "
                + "topic, difficulty, active, created_by, created_at, updated_at";
            findByQuizIdSql = "SELECT q.id, q.content, q.option_a, q.option_b, q.option_c, q.option_d, q.correct_option, "
                + "q.topic, q.difficulty" + (hasExplanationColumn ? ", q.explanation" : "") + ", q.active, q.created_by, q.created_at, q.updated_at "
                + "FROM quiz_questions qq "
                + "JOIN questions q ON qq.question_id = q.id "
                + "WHERE qq.quiz_id = ? ORDER BY qq.position ASC";
        }
    }

    public void ensureSchema() {
        String addColumn = "ALTER TABLE questions ADD COLUMN IF NOT EXISTS question_type VARCHAR(20) NOT NULL DEFAULT 'MULTIPLE_CHOICE'";
        String addExplanation = "ALTER TABLE questions ADD COLUMN IF NOT EXISTS explanation TEXT";
        try (Connection c = dataSource.getConnection();
             java.sql.Statement s = c.createStatement()) {
            s.execute(addColumn);
            s.execute(addExplanation);
        } catch (SQLException ignored) {
            // Có thể xảy ra nếu user kết nối không phải superuser/owner; repository sẽ tự động dùng fallback mode.
        }
    }

    private boolean checkColumnExists(String colName) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT 1 FROM information_schema.columns WHERE table_name = 'questions' AND column_name = ?")) {
            ps.setString(1, colName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            return false;
        }
    }

    @Override
    public Optional<Question> findById(long id) {
        validateId(id);
        return withConnection(connection -> findOne(connection, findIdSql,
            statement -> statement.setLong(1, id)));
    }

    @Override
    public Optional<Question> findById(Connection connection, long id) {
        validateId(id);
        return onConnection(connection, () -> findOne(connection, findIdSql,
            statement -> statement.setLong(1, id)));
    }

    @Override
    public List<Question> findAll() {
        return withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(findAllSql);
                 ResultSet rows = statement.executeQuery()) {
                List<Question> questions = new ArrayList<>();
                while (rows.next()) {
                    questions.add(mapQuestion(rows));
                }
                return questions;
            }
        });
    }

    @Override
    public List<Question> findActive() {
        return withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(findActiveSql);
                 ResultSet rows = statement.executeQuery()) {
                List<Question> questions = new ArrayList<>();
                while (rows.next()) {
                    questions.add(mapQuestion(rows));
                }
                return questions;
            }
        });
    }

    @Override
    public List<Question> findAllActive() {
        return findActive();
    }

    @Override
    public List<Question> findByFilter(String topic, Difficulty difficulty) {
        return withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(findByFilterSql)) {
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
            }
        });
    }

    @Override
    public List<Question> findByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return withConnection(connection -> {
            String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
            String colPart = hasQuestionTypeColumn
                ? "id, content, option_a, option_b, option_c, option_d, correct_option, topic, difficulty, question_type" + (hasExplanationColumn ? ", explanation" : "") + ", active, created_by, created_at, updated_at "
                : "id, content, option_a, option_b, option_c, option_d, correct_option, topic, difficulty" + (hasExplanationColumn ? ", explanation" : "") + ", active, created_by, created_at, updated_at ";
            String sql = "SELECT " + colPart + "FROM questions WHERE id IN (" + placeholders + ") ORDER BY id ASC";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                for (int i = 0; i < ids.size(); i++) {
                    statement.setLong(i + 1, ids.get(i));
                }
                try (ResultSet rows = statement.executeQuery()) {
                    List<Question> questions = new ArrayList<>();
                    while (rows.next()) {
                        questions.add(mapQuestion(rows));
                    }
                    return List.copyOf(questions);
                }
            }
        });
    }

    @Override
    public List<Question> findQuestionsByQuizId(long quizId) {
        validateId(quizId);
        return withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(findByQuizIdSql)) {
                statement.setLong(1, quizId);
                try (ResultSet rows = statement.executeQuery()) {
                    List<Question> questions = new ArrayList<>();
                    while (rows.next()) {
                        questions.add(mapQuestion(rows));
                    }
                    return List.copyOf(questions);
                }
            }
        });
    }

    @Override
    public int countActive() {
        return withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(COUNT_ACTIVE);
                 ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getInt(1) : 0;
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
    public int deactivate(Connection connection, List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        ids.forEach(this::validateId);
        return onConnection(connection, () -> deactivateQuestions(connection, ids));
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
        try (PreparedStatement statement = connection.prepareStatement(insertSql)) {
            statement.setString(1, question.content());
            statement.setString(2, question.optionA());
            statement.setString(3, question.optionB());
            statement.setString(4, question.optionC());
            statement.setString(5, question.optionD());
            statement.setString(6, String.valueOf(question.correctOption()));
            statement.setString(7, question.topic());
            statement.setString(8, question.difficulty().name());
            int idx = 9;
            if (hasQuestionTypeColumn) {
                statement.setString(idx++, (question.questionType() != null ? question.questionType() : QuestionType.MULTIPLE_CHOICE).name());
            }
            if (hasExplanationColumn) {
                statement.setString(idx++, question.explanation());
            }
            statement.setLong(idx++, question.createdBy());
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
        try (PreparedStatement statement = connection.prepareStatement(updateSql)) {
            statement.setString(1, question.content());
            statement.setString(2, question.optionA());
            statement.setString(3, question.optionB());
            statement.setString(4, question.optionC());
            statement.setString(5, question.optionD());
            statement.setString(6, String.valueOf(question.correctOption()));
            statement.setString(7, question.topic());
            statement.setString(8, question.difficulty().name());
            int idx = 9;
            if (hasQuestionTypeColumn) {
                statement.setString(idx++, (question.questionType() != null ? question.questionType() : QuestionType.MULTIPLE_CHOICE).name());
            }
            if (hasExplanationColumn) {
                statement.setString(idx++, question.explanation());
            }
            statement.setLong(idx++, question.id());
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

    private int deactivateQuestions(Connection connection, List<Long> ids) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(DEACTIVATE_BATCH)) {
            // PostgreSQL array parameter for bigint[]
            java.sql.Array idArray = connection.createArrayOf("bigint", ids.toArray());
            statement.setArray(1, idArray);
            return statement.executeUpdate();
        } catch (SQLException error) {
            throw databaseError(error);
        }
    }

    private Question mapQuestion(ResultSet rows) throws SQLException {
        try {
            OffsetDateTime createdAt = rows.getObject("created_at", OffsetDateTime.class);
            OffsetDateTime updatedAt = rows.getObject("updated_at", OffsetDateTime.class);
            String content = rows.getString("content");
            String optionA = rows.getString("option_a");
            String optionB = rows.getString("option_b");
            String optionC = rows.getString("option_c");
            String optionD = rows.getString("option_d");
            QuestionType questionType = QuestionType.MULTIPLE_CHOICE;
            if (hasQuestionTypeColumn) {
                try {
                    String typeStr = rows.getString("question_type");
                    if (typeStr != null && !typeStr.isBlank()) {
                        questionType = QuestionType.valueOf(typeStr);
                    }
                } catch (SQLException | IllegalArgumentException ignored) {
                }
            } else {
                questionType = inferQuestionType(content, optionA, optionB, optionC, optionD);
            }
            String explanation = null;
            if (hasExplanationColumn) {
                try {
                    explanation = rows.getString("explanation");
                } catch (SQLException ignored) {}
            }
            return Question.restore(rows.getLong("id"), content,
                optionA, optionB, optionC, optionD,
                rows.getString("correct_option").charAt(0),
                rows.getString("topic"), Difficulty.valueOf(rows.getString("difficulty")),
                questionType,
                rows.getBoolean("active"), rows.getLong("created_by"),
                createdAt == null ? null : createdAt.toInstant(),
                updatedAt == null ? null : updatedAt.toInstant(),
                safeString(rows, "answer_key"),
                explanation != null ? explanation : safeString(rows, "explanation"));
        } catch (IllegalArgumentException | NullPointerException error) {
            throw new SQLException("Invalid stored question data", error);
        }
    }

    private static String safeString(ResultSet rows, String column) {
        try { return rows.getString(column); } catch (SQLException ignored) { return ""; }
    }

    private static QuestionType inferQuestionType(String content, String optA, String optB, String optC, String optD) {
        if (content != null && (content.contains("___") || content.toLowerCase().contains("[blank]"))) {
            return QuestionType.FILL_BLANK;
        }
        if (isTrueFalseOption(optA) && isTrueFalseOption(optB)) {
            return QuestionType.TRUE_FALSE;
        }
        if (isEmptyOrDash(optC) && isEmptyOrDash(optD)) {
            return QuestionType.SHORT_ANSWER;
        }
        return QuestionType.MULTIPLE_CHOICE;
    }

    private static boolean isTrueFalseOption(String opt) {
        if (opt == null) return false;
        String s = opt.trim().toLowerCase();
        return s.equals("đúng") || s.equals("sai") || s.equals("true") || s.equals("false");
    }

    private static boolean isEmptyOrDash(String opt) {
        return opt == null || opt.isBlank() || opt.trim().equals("-") || opt.trim().equals("—");
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
