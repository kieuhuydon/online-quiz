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
                execute(connection, "SELECT set_config('quiz.allow_demo_seed', 'on', true)");
                execute(connection, insideFixtureTransaction("seed.sql"));
                assertEquals(50, count(connection));
                try (var statement = connection.prepareStatement("SELECT count(DISTINCT student_id) FROM class_members");
                     var rows = statement.executeQuery()) {
                    assertTrue(rows.next());
                    assertEquals(50, rows.getLong(1));
                }
                try (var statement = connection.prepareStatement("SELECT class_id, count(*) FROM class_members GROUP BY class_id ORDER BY class_id");
                     var rows = statement.executeQuery()) {
                    for (int classId = 1; classId <= 3; classId++) {
                        assertTrue(rows.next());
                        assertEquals(classId, rows.getLong(1));
                        assertEquals(classId == 3 ? 10 : 20, rows.getLong(2));
                    }
                    assertFalse(rows.next());
                }
            } finally { connection.rollback(); }
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
