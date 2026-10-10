package quiz.server.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class PostgreSqlTestConfigTest {
    @ParameterizedTest
    @ValueSource(strings = {
        "jdbc:postgresql://127.0.0.1:5432/quizdb",
        "jdbc:postgresql://remote:5432/quizdb_test",
        "jdbc:postgresql://127.0.0.1:0/quizdb_test",
        "jdbc:postgresql://127.0.0.1:65536/quizdb_test",
        "jdbc:postgresql://127.0.0.1:5432/quizdb_test?user=postgres",
        "jdbc:postgresql://127.0.0.1:5432/quizdb_test/extra"
    })
    void rejectsNonTestDatabaseRemoteHostsInvalidPortsAndUrlOverrides(String url) {
        Properties properties = validProperties();
        properties.setProperty("db.url", url);
        assertThrows(IllegalArgumentException.class, () -> PostgreSqlTestConfig.fromProperties(properties));
    }

    @ParameterizedTest
    @ValueSource(strings = {"quiz", "postgres", ""})
    void rejectsApplicationAndPrivilegedRoles(String role) {
        Properties properties = validProperties();
        properties.setProperty("db.user", role);
        assertThrows(IllegalArgumentException.class, () -> PostgreSqlTestConfig.fromProperties(properties));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "change-me"})
    void rejectsMissingAndPlaceholderPasswords(String password) {
        Properties properties = validProperties();
        properties.setProperty("db.password", password);
        assertThrows(IllegalArgumentException.class, () -> PostgreSqlTestConfig.fromProperties(properties));
    }

    @Test
    void acceptsExplicitLocalTestConfigWithoutExposingPassword() {
        PostgreSqlTestConfig config = PostgreSqlTestConfig.fromProperties(validProperties());
        assertEquals("quiz_test", config.dataSource().getUser());
        assertFalse(config.toString().contains("test-only-secret"));
    }

    @Test
    void incompleteConfigFailsRatherThanSkippingTests() {
        assertThrows(IllegalArgumentException.class,
            () -> PostgreSqlTestConfig.fromProperties(new Properties()));
    }

    private Properties validProperties() {
        Properties properties = new Properties();
        properties.setProperty("db.url", "jdbc:postgresql://127.0.0.1:5432/quizdb_test");
        properties.setProperty("db.user", "quiz_test");
        properties.setProperty("db.password", "test-only-secret");
        return properties;
    }
}
