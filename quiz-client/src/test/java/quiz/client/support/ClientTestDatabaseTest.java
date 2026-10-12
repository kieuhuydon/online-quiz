package quiz.client.support;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.Properties;
import static org.junit.jupiter.api.Assertions.*;

class ClientTestDatabaseTest {
    @ParameterizedTest @ValueSource(strings = {"jdbc:postgresql://localhost:5432/quizdb", "jdbc:postgresql://remote:5432/quizdb_test",
        "jdbc:postgresql://localhost:5432/quizdb_test?currentSchema=public", "jdbc:postgresql://localhost:0/quizdb_test"})
    void rejectsUnsafeDbTargetsWithoutEchoingCredentials(String url) {
        var properties = new Properties();
        properties.setProperty("db.url", url); properties.setProperty("db.user", "quiz_test");
        properties.setProperty("db.password", "sensitive-fixture");
        assertFalse(assertThrows(IllegalArgumentException.class, () -> ClientTestDatabase.from(properties)).getMessage().contains("sensitive"));
    }
}
