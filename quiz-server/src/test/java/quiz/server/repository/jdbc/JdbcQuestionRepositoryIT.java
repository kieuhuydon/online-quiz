package quiz.server.repository.jdbc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import quiz.common.enums.Difficulty;
import quiz.common.enums.Role;
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

/** Real PostgreSQL tests for question persistence: only run by the explicit db-it profile. */
class JdbcQuestionRepositoryIT {
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
            assertEquals("quizdb_test", rows.getString(1), "Only the dedicated test database is allowed");
            assertEquals("quiz_test", rows.getString(2), "Only the dedicated test role is allowed");
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
    void cleanupOnlyOwnFixtures() throws SQLException {
        if (creatorId > 0) {
            try (Connection connection = dataSource.getConnection()) {
                // Xóa câu hỏi do creator tạo trước (ràng buộc FK)
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
    void persistsAndReadsQuestionWithMetadata() throws SQLException {
        creatorId = createCreator();
        JdbcQuestionRepository repository = repository();

        Question input = Question.createNew("Câu hỏi mẫu?", "A", "B", "C", "D", 'B',
            "Ngữ pháp", Difficulty.MEDIUM, creatorId);

        Question saved = repository.save(input);

        assertTrue(saved.id() > 0);
        assertNotNull(saved.createdAt());
        assertNotNull(saved.updatedAt());
        assertEquals(0, input.id());
        assertNull(input.createdAt());
        assertTrue(saved.active());
        Question reread = repository.findById(saved.id()).orElseThrow();
        assertEquals(saved.content(), reread.content());
        assertEquals(saved.difficulty(), reread.difficulty());
        assertTrue(saved.active());
    }

    @Test
    void findAllAndFindActiveOrderById() throws SQLException {
        creatorId = createCreator();
        JdbcQuestionRepository repository = repository();

        Question q1 = repository.save(Question.createNew("Câu 1?", "A", "B", "C", "D", 'A',
            "T1", Difficulty.EASY, creatorId));
        Question q2 = repository.save(Question.createNew("Câu 2?", "A", "B", "C", "D", 'B',
            "T2", Difficulty.HARD, creatorId));
        repository.deactivate(q2.id());

        List<Question> all = repository.findAll().stream()
            .filter(q -> q.createdBy() == creatorId)
            .toList();
        List<Question> active = repository.findActive().stream()
            .filter(q -> q.createdBy() == creatorId)
            .toList();

        assertEquals(2, all.size());
        assertEquals(1, active.size());
        assertEquals(q1.id(), active.get(0).id());
        assertTrue(all.get(0).id() < all.get(1).id());
    }

    @Test
    void findByFilterTopicAndDifficulty() throws SQLException {
        creatorId = createCreator();
        JdbcQuestionRepository repository = repository();
        String grammarTopic = "Ngữ pháp " + creatorId;
        String vocabTopic = "Từ vựng " + creatorId;

        repository.save(Question.createNew("Câu ngữ pháp dễ?", "A", "B", "C", "D", 'A',
            grammarTopic, Difficulty.EASY, creatorId));
        repository.save(Question.createNew("Câu từ vựng khó?", "A", "B", "C", "D", 'B',
            vocabTopic, Difficulty.HARD, creatorId));

        List<Question> byTopic = repository.findByFilter(grammarTopic, null).stream()
            .filter(q -> q.createdBy() == creatorId)
            .toList();
        List<Question> byDiff = repository.findByFilter(null, Difficulty.HARD).stream()
            .filter(q -> q.createdBy() == creatorId)
            .toList();
        List<Question> byBoth = repository.findByFilter(vocabTopic, Difficulty.HARD).stream()
            .filter(q -> q.createdBy() == creatorId)
            .toList();

        assertEquals(1, byTopic.size());
        assertEquals(1, byDiff.size());
        assertEquals(1, byBoth.size());
        assertEquals(grammarTopic, byTopic.get(0).topic());
        assertEquals(Difficulty.HARD, byDiff.get(0).difficulty());
    }

    @Test
    void updateOnlyActiveQuestion() throws SQLException {
        creatorId = createCreator();
        JdbcQuestionRepository repository = repository();

        Question saved = repository.save(Question.createNew("Cũ?", "A", "B", "C", "D", 'A',
            "T", Difficulty.EASY, creatorId));
        Question updated = new Question(saved.id(), "Mới?", "A", "B", "C", "D", 'B',
            "T", Difficulty.HARD, true, creatorId, saved.createdAt(), saved.updatedAt());

        boolean ok = repository.update(updated);

        assertTrue(ok);
        Question reread = repository.findById(saved.id()).orElseThrow();
        assertEquals("Mới?", reread.content());
        assertEquals('B', reread.correctOption());
        assertEquals(Difficulty.HARD, reread.difficulty());
        assertNotNull(reread.updatedAt());
    }

    @Test
    void updateInactiveQuestionReturnsFalse() throws SQLException {
        creatorId = createCreator();
        JdbcQuestionRepository repository = repository();

        Question saved = repository.save(Question.createNew("Cũ?", "A", "B", "C", "D", 'A',
            "T", Difficulty.EASY, creatorId));
        repository.deactivate(saved.id());

        Question updated = new Question(saved.id(), "Mới?", "A", "B", "C", "D", 'B',
            "T", Difficulty.HARD, true, creatorId, saved.createdAt(), saved.updatedAt());

        boolean ok = repository.update(updated);

        assertFalse(ok);
    }

    @Test
    void deactivateSoftDeletesWithoutRemovingRow() throws SQLException {
        creatorId = createCreator();
        JdbcQuestionRepository repository = repository();

        Question saved = repository.save(Question.createNew("Câu?", "A", "B", "C", "D", 'A',
            "T", Difficulty.EASY, creatorId));

        boolean ok = repository.deactivate(saved.id());

        assertTrue(ok);
        Question reread = repository.findById(saved.id()).orElseThrow();
        assertFalse(reread.active());
        assertNotNull(reread.updatedAt());
        assertEquals(1, repository.findAll().stream().filter(q -> q.createdBy() == creatorId).count());
        assertEquals(0, repository.findActive().stream().filter(q -> q.createdBy() == creatorId).count());
    }

    @Test
    void missingIdReturnsFalse() {
        JdbcQuestionRepository repository = repository();
        assertFalse(repository.deactivate(999999L));
        assertFalse(repository.existsById(999999L));
    }

    @Test
    void callerConnectionKeepsTransaction() throws SQLException {
        creatorId = createCreator();
        JdbcQuestionRepository repository = repository();

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                Question saved = repository.save(connection, Question.createNew(
                    "Tx câu hỏi?", "A", "B", "C", "D", 'C', "T", Difficulty.EASY, creatorId));
                assertTrue(repository.findById(connection, saved.id()).isPresent());
                assertFalse(repository.findById(saved.id()).isPresent());
                connection.rollback();
            } finally {
                if (!connection.getAutoCommit()) {
                    connection.rollback();
                }
            }
        }
        assertFalse(repository.findAll().stream().anyMatch(q -> q.content().equals("Tx câu hỏi?")));
    }

    private long createCreator() throws SQLException {
        String hash = PasswordHasher.hashPassword("fixture-password");
        String username = "qc_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "INSERT INTO users (username, password_hash, full_name, email, role, active, created_by, created_at) "
                 + "VALUES (?, ?, ?, ?, ?, ?, ?, now()) RETURNING id")) {
            statement.setString(1, username);
            statement.setString(2, hash);
            statement.setString(3, "Question Fixture Creator");
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

    private JdbcQuestionRepository repository() {
        return new JdbcQuestionRepository(dataSource);
    }
}
