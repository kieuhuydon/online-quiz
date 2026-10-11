package quiz.client.support;

import org.postgresql.ds.PGSimpleDataSource;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.regex.Pattern;

/** Dedicated opt-in DB fixture configuration. Never falls back to application credentials or DB. */
public final class ClientTestDatabase {
    private static final Pattern URL = Pattern.compile("jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost):([0-9]{1,5})/quizdb_test");
    private ClientTestDatabase() { }

    public static PGSimpleDataSource load() throws Exception {
        Properties properties = new Properties();
        String config = System.getProperty("quiz.test.config");
        if (config == null) throw new IllegalArgumentException("Specify quiz.test.config for login-it");
        try (Reader reader = Files.newBufferedReader(Path.of(config), StandardCharsets.UTF_8)) { properties.load(reader); }
        for (String key : new String[] {"URL", "USER", "PASSWORD"}) {
            String value = System.getenv("QUIZ_TEST_DB_" + key);
            if (value != null) properties.setProperty("db." + key.toLowerCase(java.util.Locale.ROOT), value);
        }
        return from(properties);
    }

    public static PGSimpleDataSource from(Properties properties) {
        String url = properties.getProperty("db.url", "");
        var match = URL.matcher(url);
        if (!match.matches() || Integer.parseInt(match.group(1)) < 1 || Integer.parseInt(match.group(1)) > 65535) {
            throw new IllegalArgumentException("Login IT requires a localhost quizdb_test JDBC URL with a valid port");
        }
        String user = properties.getProperty("db.user", "");
        String password = properties.getProperty("db.password", "");
        if (!"quiz_test".equals(user) || password.isBlank() || "change-me".equals(password)) {
            throw new IllegalArgumentException("Login IT requires quiz_test and explicit test password");
        }
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(url); dataSource.setUser(user); dataSource.setPassword(password);
        dataSource.setConnectTimeout(5); dataSource.setSocketTimeout(10);
        return dataSource;
    }
}
