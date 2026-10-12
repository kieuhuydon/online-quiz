package quiz.server.integration;

import org.postgresql.ds.PGSimpleDataSource;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Explicit local test credentials; never falls back to the application's database. */
final class PostgreSqlTestConfig {
    private static final Pattern TEST_URL = Pattern.compile(
        "jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost):([0-9]{1,5})/quizdb_test");
    private final String url;
    private final String user;
    private final String password;

    private PostgreSqlTestConfig(String url, String user, String password) {
        this.url = url;
        this.user = user;
        this.password = password;
    }

    static PostgreSqlTestConfig load() throws IOException {
        Properties properties = new Properties();
        Path config = Path.of(System.getProperty("quiz.test.config", "db-test-local.properties"));
        if (Files.exists(config)) {
            try (Reader reader = Files.newBufferedReader(config, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
        }
        override(properties, "db.url", "QUIZ_TEST_DB_URL");
        override(properties, "db.user", "QUIZ_TEST_DB_USER");
        override(properties, "db.password", "QUIZ_TEST_DB_PASSWORD");
        return fromProperties(properties);
    }

    static PostgreSqlTestConfig fromProperties(Properties properties) {
        String url = properties.getProperty("db.url", "");
        Matcher match = TEST_URL.matcher(url);
        if (!match.matches() || Integer.parseInt(match.group(1)) < 1
            || Integer.parseInt(match.group(1)) > 65535) {
            throw new IllegalArgumentException("DB tests require a local quizdb_test JDBC URL with a valid port");
        }
        String user = properties.getProperty("db.user", "");
        if (!"quiz_test".equals(user)) {
            throw new IllegalArgumentException("DB tests require role quiz_test");
        }
        String password = properties.getProperty("db.password", "");
        if (password.isBlank() || "change-me".equals(password)) {
            throw new IllegalArgumentException("Set db.password in the ignored test config or QUIZ_TEST_DB_PASSWORD");
        }
        return new PostgreSqlTestConfig(url, user, password);
    }

    PGSimpleDataSource dataSource() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(url);
        dataSource.setUser(user);
        dataSource.setPassword(password);
        dataSource.setConnectTimeout(5);
        dataSource.setSocketTimeout(10);
        return dataSource;
    }

    private static void override(Properties properties, String key, String environmentKey) {
        String value = System.getenv(environmentKey);
        if (value != null) {
            properties.setProperty(key, value);
        }
    }
}
