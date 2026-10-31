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
                long otherStudent = insert(connection,
                    "INSERT INTO users (username, password_hash, full_name, role) VALUES (?, ?, ?, ?) RETURNING id",
                    "s2_" + suffix, "fixture-hash", "Other Student", "STUDENT");
                long classId = repository.save(Classroom.createNew("delete_" + suffix, null, teacher)).id();
                long otherClass = repository.save(Classroom.createNew("keep_" + suffix, null, teacher)).id();
                repository.addMember(classId, student);
                repository.addMember(otherClass, otherStudent);
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
                assertTrue(repository.isMember(otherClass, otherStudent));
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

    @Test void concurrentEnrollmentAllowsOnlyOneClassAndStudentsCanMoveAfterRemoval() throws Exception {
        DataSource source = TestDbConfig.load().dataSource();
        var users = new JdbcUserRepository(source);
        var classes = new JdbcClassRepository(source);
        String suffix = UUID.randomUUID().toString();
        var userIds = new java.util.ArrayList<Long>();
        var classIds = new java.util.ArrayList<Long>();
        try {
            long teacher = users.save(quiz.server.domain.User.createNew("t_" + suffix, "hash", "Teacher", null,
                quiz.common.enums.Role.TEACHER, null)).id();
            userIds.add(teacher);
            long student = users.save(quiz.server.domain.User.createNew("s_" + suffix, "hash", "Student", null,
                quiz.common.enums.Role.STUDENT, null)).id();
            userIds.add(student);
            long disabled = users.save(quiz.server.domain.User.createNew("d_" + suffix, "hash", "Disabled", null,
                quiz.common.enums.Role.STUDENT, null)).id();
            userIds.add(disabled);
            users.updateActiveStatus(disabled, false);
            long firstClass = classes.save(Classroom.createNew("first_" + suffix, null, teacher)).id();
            classIds.add(firstClass);
            long secondClass = classes.save(Classroom.createNew("second_" + suffix, null, teacher)).id();
            classIds.add(secondClass);
            var candidateIds = users.findUnenrolledStudents().stream().map(quiz.server.domain.User::id).toList();
            assertTrue(candidateIds.contains(student));
            assertFalse(candidateIds.contains(teacher));
            assertFalse(candidateIds.contains(disabled));

            var start = new java.util.concurrent.CountDownLatch(1);
            try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
                var first = executor.submit(() -> enrollAfter(start, classes, firstClass, student));
                var second = executor.submit(() -> enrollAfter(start, classes, secondClass, student));
                start.countDown();
                var firstResult = first.get(10, java.util.concurrent.TimeUnit.SECONDS);
                var secondResult = second.get(10, java.util.concurrent.TimeUnit.SECONDS);
                assertEquals(1, java.util.stream.Stream.of(firstResult, secondResult).filter(java.util.Objects::isNull).count());
                assertEquals(1, java.util.stream.Stream.of(firstResult, secondResult)
                    .filter(code -> code == quiz.common.protocol.ErrorCode.STUDENT_ALREADY_ENROLLED).count());
            }
            long current = classes.findClassIdsForStudent(student).getFirst();
            assertEquals(1, classes.findClassIdsForStudent(student).size());
            assertFalse(users.findUnenrolledStudents().stream().anyMatch(u -> u.id() == student));
            var duplicate = assertThrows(quiz.server.exception.ConflictException.class,
                () -> classes.addMember(current, student));
            assertEquals(quiz.common.protocol.ErrorCode.STUDENT_ALREADY_ENROLLED, duplicate.getErrorCode());
            classes.removeMember(current, student);
            assertTrue(users.findUnenrolledStudents().stream().anyMatch(u -> u.id() == student));
            long target = current == firstClass ? secondClass : firstClass;
            classes.addMember(target, student);
            assertEquals(java.util.List.of(target), classes.findClassIdsForStudent(student));
        } finally {
            try (Connection connection = source.getConnection()) {
                for (long id : classIds) execute(connection, "DELETE FROM classes WHERE id = ?", id);
                for (long id : userIds.reversed()) execute(connection, "DELETE FROM users WHERE id = ?", id);
            }
        }
    }

    private static quiz.common.protocol.ErrorCode enrollAfter(java.util.concurrent.CountDownLatch start,
            JdbcClassRepository repository, long classId, long studentId) throws Exception {
        assertTrue(start.await(5, java.util.concurrent.TimeUnit.SECONDS));
        try {
            repository.addMember(classId, studentId);
            return null;
        } catch (quiz.server.exception.ConflictException error) {
            return error.getErrorCode();
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
