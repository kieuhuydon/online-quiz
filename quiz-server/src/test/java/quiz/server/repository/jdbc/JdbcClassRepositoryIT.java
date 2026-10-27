package quiz.server.repository.jdbc;

import org.junit.jupiter.api.Test;
import quiz.server.domain.Classroom;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Exercises real PostgreSQL cascades in an isolated, rolled-back fixture transaction. */
class JdbcClassRepositoryIT {
    @Test
    void deletingClassRemovesOnlyItsMembershipsAndAssignments() throws Exception {
        try (Connection connection = TestDbConfig.load().dataSource().getConnection()) {
            try (PreparedStatement statement = connection.prepareStatement("SELECT current_database(), current_user");
                 ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertEquals("quizdb_test", rows.getString(1));
                assertEquals("quiz_test", rows.getString(2));
            }
            connection.setAutoCommit(false);
            try {
                // Repository closes its borrowed connection; keep this fixture transaction open for rollback.
                Connection borrowed = spy(connection);
                doNothing().when(borrowed).close();
                DataSource source = mock(DataSource.class);
                when(source.getConnection()).thenReturn(borrowed);
                var repository = new JdbcClassRepository(source);
                String suffix = UUID.randomUUID().toString();
                long teacher = insert(connection,
                    "INSERT INTO users (username, password_hash, full_name, role) VALUES (?, ?, ?, ?) RETURNING id",
                    "t_" + suffix, "fixture-hash", "Teacher", "TEACHER");
                long student = insert(connection,
                    "INSERT INTO users (username, password_hash, full_name, role) VALUES (?, ?, ?, ?) RETURNING id",
                    "s_" + suffix, "fixture-hash", "Student", "STUDENT");
                long classId = repository.save(Classroom.createNew("delete_" + suffix, null, teacher)).id();
                long otherClass = repository.save(Classroom.createNew("keep_" + suffix, null, teacher)).id();
                repository.addMember(classId, student);
                repository.addMember(otherClass, student);
                long quiz = insert(connection,
                    "INSERT INTO quizzes (title, duration_minutes, created_by) VALUES (?, ?, ?) RETURNING id",
                    "Delete class fixture", 10, teacher);
                execute(connection, "INSERT INTO quiz_assignments (quiz_id, class_id) VALUES (?, ?)", quiz, classId);
                execute(connection, "INSERT INTO quiz_assignments (quiz_id, class_id) VALUES (?, ?)", quiz, otherClass);
                long attempt = insert(connection, """
                    INSERT INTO attempts (quiz_id, student_id, status, started_at, deadline_at, submitted_at)
                    VALUES (?, ?, 'SUBMITTED', now(), now() + interval '10 minutes', now()) RETURNING id
                    """, quiz, student);
                execute(connection, """
                    INSERT INTO results (attempt_id, score, correct_count, total_questions, passed, submit_reason)
                    VALUES (?, 10, 1, 1, true, 'MANUAL')
                    """, attempt);

                assertTrue(repository.deleteById(classId));
                assertFalse(repository.deleteById(classId));
                assertTrue(repository.findById(classId).isEmpty());
                assertTrue(repository.findMembers(classId).isEmpty());
                assertEquals(0, count(connection, "SELECT count(*) FROM quiz_assignments WHERE class_id = ?", classId));
                assertTrue(repository.findById(otherClass).isPresent());
                assertTrue(repository.isMember(otherClass, student));
                assertEquals(1, count(connection, "SELECT count(*) FROM quiz_assignments WHERE class_id = ?", otherClass));
                assertEquals(1, count(connection, "SELECT count(*) FROM users WHERE id = ?", teacher));
                assertEquals(1, count(connection, "SELECT count(*) FROM users WHERE id = ?", student));
                assertEquals(1, count(connection, "SELECT count(*) FROM quizzes WHERE id = ?", quiz));
                assertEquals(1, count(connection, "SELECT count(*) FROM attempts WHERE id = ?", attempt));
                assertEquals(1, count(connection, "SELECT count(*) FROM results WHERE attempt_id = ?", attempt));
                assertThrows(IllegalArgumentException.class, () -> repository.deleteById(0));
            } finally {
                connection.rollback();
            }
        }
    }

    private static long insert(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = prepare(connection, sql, values);
             ResultSet rows = statement.executeQuery()) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    private static long count(Connection connection, String sql, long id) throws SQLException {
        return insert(connection, sql, id);
    }

    private static void execute(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = prepare(connection, sql, values)) {
            statement.executeUpdate();
        }
    }

    private static PreparedStatement prepare(Connection connection, String sql, Object... values) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
        return statement;
    }
}
