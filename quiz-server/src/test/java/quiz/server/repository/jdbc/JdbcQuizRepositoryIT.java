package quiz.server.repository.jdbc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import quiz.common.enums.AntiCheatPolicyType;
import quiz.common.enums.Difficulty;
import quiz.common.enums.QuizStatus;
import quiz.common.enums.Role;
import quiz.server.domain.Quiz;
import quiz.server.domain.Question;
import quiz.server.security.PasswordHasher;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Real PostgreSQL tests for quiz persistence: only run by the explicit db-it profile. */
class JdbcQuizRepositoryIT {
    private static DataSource dataSource;
    private long creatorId;

    @BeforeAll
    static void connectOnlyToPreparedTestDatabase() throws Exception {
        dataSource = TestDbConfig.load().dataSource();
        try (Connection connection = dataSource.getConnection()) {
            verifyDatabaseIdentity(connection);
            verifySchema(connection);
        }
    }

    private static void verifyDatabaseIdentity(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT current_database(), current_user");
             ResultSet rows = statement.executeQuery()) {
            assertTrue(rows.next());
            assertEquals("quizdb_test", rows.getString(1));
            assertEquals("quiz_test", rows.getString(2));
        }
    }

    private static void verifySchema(Connection connection) throws SQLException {
        Set<String> expected = Set.of("users", "classes", "class_members", "questions", "quizzes",
            "quiz_questions", "quiz_assignments", "attempts", "attempt_answers", "results", "anticheat_events");
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT table_name FROM information_schema.tables WHERE table_schema = ? AND table_type = 'BASE TABLE'")) {
            statement.setString(1, "public");
            try (ResultSet rows = statement.executeQuery()) {
                Set<String> actual = new HashSet<>();
                while (rows.next()) {
                    actual.add(rows.getString(1));
                }
                assertTrue(actual.containsAll(expected), "Initialize all 11 tables with database/schema.sql first");
            }
        }
    }

    @AfterEach
    void cleanup() throws SQLException {
        if (creatorId > 0) {
            try (Connection connection = dataSource.getConnection()) {
                // Xóa quiz trước để cascade xóa quiz_questions, tránh lỗi FK tới questions
                try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM quizzes WHERE created_by = ?")) {
                    statement.setLong(1, creatorId);
                    statement.executeUpdate();
                }
                // Xóa câu hỏi do creator tạo
                try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM questions WHERE created_by = ?")) {
                    statement.setLong(1, creatorId);
                    statement.executeUpdate();
                }
                // Sau đó xóa user
                try (PreparedStatement statement = connection.prepareStatement("DELETE FROM users WHERE id = ?")) {
                    statement.setLong(1, creatorId);
                    statement.executeUpdate();
                }
            }
        }
    }

    @Test
    void persistsAndReadsQuizWithMetadata() throws SQLException {
        creatorId = createCreator();
        JdbcQuizRepository repository = repository();

        Quiz input = Quiz.createNew("Kiểm tra 15 phút", "Mô tả bài kiểm tra", "Toán",
            15, 50.0, AntiCheatPolicyType.WARN_THEN_AUTO_SUBMIT, 3, creatorId);

        Quiz saved = repository.save(input);

        assertTrue(saved.id() > 0);
        assertNotNull(saved.createdAt());
        assertEquals(QuizStatus.DRAFT, saved.status());
        assertEquals("Kiểm tra 15 phút", saved.title());
        assertEquals("Toán", saved.category());
        assertEquals(15, saved.durationMinutes());
        Quiz reread = repository.findById(saved.id()).orElseThrow();
        assertEquals(saved.title(), reread.title());
        assertEquals(saved.status(), reread.status());
    }

    @Test
    void findAllAndFindByStatusOrderById() throws SQLException {
        creatorId = createCreator();
        JdbcQuizRepository repository = repository();

        Quiz q1 = repository.save(Quiz.createNew("Quiz 1", null, null, 15, 50.0,
            AntiCheatPolicyType.WARN_THEN_AUTO_SUBMIT, 3, creatorId));
        Quiz q2 = repository.save(Quiz.createNew("Quiz 2", null, null, 30, 60.0,
            AntiCheatPolicyType.WARN_THEN_AUTO_SUBMIT, 3, creatorId));

        List<Quiz> all = repository.findAll().stream()
            .filter(q -> q.createdBy() == creatorId)
            .toList();
        List<Quiz> drafts = repository.findByStatus(QuizStatus.DRAFT).stream()
            .filter(q -> q.createdBy() == creatorId)
            .toList();

        assertEquals(2, all.size());
        assertEquals(2, drafts.size());
        assertEquals(q1.id(), all.get(0).id());
        assertEquals(q2.id(), all.get(1).id());
        assertTrue(all.get(0).id() < all.get(1).id());
    }

    @Test
    void setQuestionsReplacesQuizQuestions() throws SQLException {
        creatorId = createCreator();
        JdbcQuizRepository repository = repository();
        JdbcQuestionRepository qRepo = new JdbcQuestionRepository(dataSource);

        Quiz quiz = repository.save(Quiz.createNew("Quiz", null, null, 15, 50.0,
            AntiCheatPolicyType.WARN_THEN_AUTO_SUBMIT, 3, creatorId));

        Long q1 = createQuestion(qRepo, creatorId, "Câu 1?", "A", "B", "C", "D", 'A');
        Long q2 = createQuestion(qRepo, creatorId, "Câu 2?", "A", "B", "C", "D", 'B');

        boolean ok = repository.setQuestions(quiz.id(), List.of(q1, q2));
        assertTrue(ok);

        List<Long> assigned = repository.findQuestionIdsByQuiz(quiz.id());
        assertEquals(List.of(q1, q2), assigned);

        boolean cleared = repository.setQuestions(quiz.id(), List.of());
        assertTrue(cleared);
        assertTrue(repository.findQuestionIdsByQuiz(quiz.id()).isEmpty());
    }

    @Test
    void setQuestionsOnMissingQuizReturnsFalse() throws SQLException {
        creatorId = createCreator();
        JdbcQuizRepository repository = repository();
        JdbcQuestionRepository qRepo = new JdbcQuestionRepository(dataSource);
        Long q1 = createQuestion(qRepo, creatorId, "Câu hỏi kiểm tra?", "A", "B", "C", "D", 'A');
        assertFalse(repository.setQuestions(999999L, List.of(q1)));
    }

    private long createCreator() throws SQLException {
        String hash = PasswordHasher.hashPassword("fixture-password");
        String username = "qu_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "INSERT INTO users (username, password_hash, full_name, email, role, active, created_by, created_at) "
                 + "VALUES (?, ?, ?, ?, ?, ?, ?, now()) RETURNING id")) {
            statement.setString(1, username);
            statement.setString(2, hash);
            statement.setString(3, "Quiz Fixture Creator");
            statement.setNull(4, java.sql.Types.VARCHAR);
            statement.setString(5, Role.ADMIN.name());
            statement.setBoolean(6, true);
            statement.setNull(7, java.sql.Types.BIGINT);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getLong(1);
            }
        }
    }

    private static Long createQuestion(JdbcQuestionRepository repo, long creatorId, String content, String a, String b,
                                       String c, String d, char correct) {
        Question q = Question.createNew(content, a, b, c, d, correct,
            "T", Difficulty.EASY, creatorId);
        return repo.save(q).id();
    }

    private JdbcQuizRepository repository() {
        return new JdbcQuizRepository(dataSource);
    }
}
