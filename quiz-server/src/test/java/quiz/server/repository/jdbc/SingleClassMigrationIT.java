package quiz.server.repository.jdbc;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

/** Runs the migration against a temporary table, without changing application or shared test data. */
class SingleClassMigrationIT {
    @Test void freshSchemaAndDemoSeedGiveEachStudentOnlyOneClass() throws Exception {
        try (Connection connection = TestDbConfig.load().dataSource().getConnection()) {
            connection.setAutoCommit(false);
            try {
                String schema = "single_class_" + java.util.UUID.randomUUID().toString().replace("-", "");
                execute(connection, "CREATE SCHEMA " + schema);
                try (var statement = connection.prepareStatement("SELECT set_config('search_path', ?, true)")) {
                    statement.setString(1, schema);
                    statement.execute();
                }
                // Keep both source scripts inside this fixture transaction so every object is rolled back.
                execute(connection, insideFixtureTransaction("schema.sql"));
                // IDs deliberately do not start at 1: the seed must resolve all relationships.
                execute(connection, "ALTER SEQUENCE users_id_seq RESTART WITH 101");
                execute(connection, "ALTER SEQUENCE classes_id_seq RESTART WITH 201");
                execute(connection, "ALTER SEQUENCE questions_id_seq RESTART WITH 301");
                execute(connection, "ALTER SEQUENCE quizzes_id_seq RESTART WITH 401");
                execute(connection, "SELECT set_config('quiz.allow_demo_seed', 'off', true)");
                var optInCheck = connection.setSavepoint();
                assertThrows(SQLException.class, () -> execute(connection, insideFixtureTransaction("seed.sql")));
                connection.rollback(optInCheck);
                assertEquals(0, scalar(connection, "SELECT count(*) FROM users"));
                execute(connection, "SELECT set_config('quiz.allow_demo_seed', 'on', true)");
                execute(connection, insideFixtureTransaction("seed.sql"));
                assertEquals(45, count(connection));
                try (var statement = connection.prepareStatement("SELECT count(DISTINCT student_id) FROM class_members");
                     var rows = statement.executeQuery()) {
                    assertTrue(rows.next());
                    assertEquals(45, rows.getLong(1));
                }
                try (var statement = connection.prepareStatement("SELECT c.name, count(*) FROM class_members cm JOIN classes c ON c.id = cm.class_id GROUP BY c.id, c.name ORDER BY c.id");
                     var rows = statement.executeQuery()) {
                    for (int classId = 1; classId <= 3; classId++) {
                        assertTrue(rows.next());
                        assertEquals(new String[]{"ENG-B1-01", "ENG-B2-01", "TOEIC-650"}[classId - 1], rows.getString(1));
                        assertEquals(new int[]{20, 15, 10}[classId - 1], rows.getLong(2));
                    }
                    assertFalse(rows.next());
                }
                assertSeedConsistency(connection);
                var repeatCheck = connection.setSavepoint();
                assertThrows(SQLException.class, () -> execute(connection, insideFixtureTransaction("seed.sql")));
                connection.rollback(repeatCheck);
                assertEquals(53, scalar(connection, "SELECT count(*) FROM users"));
            } finally { connection.rollback(); }
        }
    }

    private static void assertSeedConsistency(Connection connection) throws SQLException {
        assertEquals(53, scalar(connection, "SELECT count(*) FROM users"));
        assertEquals(1, scalar(connection, "SELECT count(*) FROM users WHERE NOT active AND username = 'student50'"));
        assertEquals(4, scalar(connection, "SELECT count(*) FROM users u WHERE role = 'STUDENT' AND active AND NOT EXISTS (SELECT 1 FROM class_members cm WHERE cm.student_id = u.id)"));
        assertEquals(30, scalar(connection, "SELECT count(*) FROM questions"));
        assertEquals(6, scalar(connection, "SELECT count(*) FROM quizzes"));
        assertEquals(3, scalar(connection, "SELECT count(*) FROM quizzes WHERE status = 'OPEN' AND pin IN ('123456', '234567', '345678')"));
        assertEquals(1, scalar(connection, "SELECT count(*) FROM quizzes WHERE status = 'DRAFT' AND pin IS NULL"));
        assertEquals(65, scalar(connection, "SELECT count(*) FROM quiz_questions"));
        assertEquals(5, scalar(connection, "SELECT count(*) FROM quiz_assignments"));
        assertEquals(8, scalar(connection, "SELECT count(*) FROM attempts WHERE status = 'SUBMITTED'"));
        assertEquals(80, scalar(connection, "SELECT count(*) FROM attempt_answers"));
        assertEquals(8, scalar(connection, "SELECT count(*) FROM results"));
        assertEquals(6, scalar(connection, "SELECT count(*) FROM anticheat_events"));
        assertEquals(2, scalar(connection, "SELECT count(*) FROM anticheat_events WHERE action_taken = 'AUTO_SUBMITTED' AND violation_count_after = 3"));
        assertEquals(0, scalar(connection, """
            SELECT count(*) FROM attempts a JOIN quizzes q ON q.id = a.quiz_id
            WHERE q.status <> 'CLOSED' OR a.submitted_at < a.started_at
               OR a.submitted_at > a.deadline_at OR a.submitted_at > q.closed_at
               OR NOT EXISTS (SELECT 1 FROM quiz_assignments qa JOIN class_members cm ON cm.class_id = qa.class_id
                              WHERE qa.quiz_id = a.quiz_id AND cm.student_id = a.student_id)
            """));
        assertEquals(0, scalar(connection, """
            SELECT count(*) FROM results r
            JOIN attempts a ON a.id = r.attempt_id JOIN quizzes q ON q.id = a.quiz_id
            JOIN (SELECT aa.attempt_id, count(*) AS total,
                         count(*) FILTER (WHERE aa.selected_option = question.correct_option) AS correct
                  FROM attempt_answers aa JOIN questions question ON question.id = aa.question_id
                  GROUP BY aa.attempt_id) answers ON answers.attempt_id = a.id
            WHERE r.correct_count <> answers.correct OR r.total_questions <> answers.total
               OR r.score <> round(answers.correct * 10.0 / answers.total, 2)
               OR r.passed <> (answers.correct * 100.0 / answers.total >= q.pass_score)
            """));
        assertEquals(0, scalar(connection, """
            SELECT count(*) FROM attempt_answers aa JOIN questions q ON q.id = aa.question_id
            WHERE aa.is_correct <> (aa.selected_option = q.correct_option)
            """));
        try (var statement = connection.prepareStatement("SELECT password_hash FROM users WHERE username IN ('admin', 'teacher1', 'student01', 'student50')");
             var rows = statement.executeQuery()) {
            int checked = 0;
            while (rows.next()) {
                assertTrue(quiz.server.security.PasswordHasher.checkPassword("password123", rows.getString(1)));
                checked++;
            }
            assertEquals(4, checked);
        }
    }

    private static long scalar(Connection connection, String query) throws SQLException {
        try (var statement = connection.prepareStatement(query); var rows = statement.executeQuery()) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    private static String insideFixtureTransaction(String filename) throws Exception {
        return Files.readString(Path.of("..", "database", filename))
            .replaceAll("(?m)^BEGIN;\\r?$", "")
            .replaceAll("(?m)^COMMIT;\\r?$", "");
    }

    @Test void migrationPreservesMembershipsAndCanRunTwice() throws Exception {
        try (Connection connection = TestDbConfig.load().dataSource().getConnection()) {
            execute(connection, "CREATE TEMP TABLE class_members (class_id bigint, student_id bigint, PRIMARY KEY (class_id, student_id))");
            execute(connection, "INSERT INTO class_members VALUES (10, 1), (20, 2)");
            execute(connection, migration());
            execute(connection, migration());
            assertEquals(2, count(connection));
            SQLException error = assertThrows(SQLException.class,
                () -> execute(connection, "INSERT INTO class_members VALUES (30, 1)"));
            assertEquals("23505", error.getSQLState());
            assertEquals(2, count(connection));
        }
    }

    @Test void migrationRefusesDuplicatesWithoutChoosingOrDeletingMemberships() throws Exception {
        try (Connection connection = TestDbConfig.load().dataSource().getConnection()) {
            execute(connection, "CREATE TEMP TABLE class_members (class_id bigint, student_id bigint, PRIMARY KEY (class_id, student_id))");
            execute(connection, "INSERT INTO class_members VALUES (10, 1), (20, 1)");
            SQLException error = assertThrows(SQLException.class, () -> execute(connection, migration()));
            assertEquals("23505", error.getSQLState());
            execute(connection, "ROLLBACK");
            assertEquals(2, count(connection));
            // The failed transaction did not partially add the constraint.
            execute(connection, "INSERT INTO class_members VALUES (30, 1)");
            assertEquals(3, count(connection));
        }
    }

    private static String migration() throws Exception {
        return Files.readString(Path.of("..", "database", "migrations", "001_single_class_per_student.sql"));
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) { statement.execute(); }
    }

    private static long count(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT count(*) FROM class_members");
             var rows = statement.executeQuery()) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }
}
