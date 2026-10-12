package quiz.server.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ServerConfig Unit Tests")
class ServerConfigTest {

    @Test
    void rejectsInvalidPoolRelationshipsTimeoutsAndPlaceholderPasswords() {
        assertThrows(IllegalArgumentException.class, () -> config("pass", 2, 10, 300000, 20000));
        assertThrows(IllegalArgumentException.class, () -> config("pass", 10, 2, -1, 20000));
        assertThrows(IllegalArgumentException.class, () -> config("pass", 10, 2, 9999, 20000));
        assertThrows(IllegalArgumentException.class, () -> config("pass", 10, 2, 300000, -1));
        assertThrows(IllegalArgumentException.class, () -> config("pass", 10, 2, 300000, 249));
        assertThrows(IllegalArgumentException.class, () -> config(null, 10, 2, 300000, 20000));
        assertThrows(IllegalArgumentException.class, () -> config("change-me", 10, 2, 300000, 20000));
        assertDoesNotThrow(() -> config("pass", 2, 2, 0, 250));
    }

    @Test
    void invalidNumericSettingsFailInsteadOfUsingDefaults() {
        for (String key : new String[]{"server.port", "db.pool.connection.timeout.ms"}) {
            Properties props = new Properties();
            props.setProperty("db.password", "test-only-password");
            props.setProperty(key, "not-a-number");
            assertThrows(IllegalArgumentException.class, () -> ServerConfig.fromProperties(props));
        }
    }

    private ServerConfig config(String password, int max, int min, long idle, long timeout) {
        return new ServerConfig(5555, 100, 8, 200, 3, "jdbc:postgresql://localhost/db",
            "user", password, max, min, idle, timeout);
    }

    @Test
    @DisplayName("Should load default configuration from server.properties resource")
    void testLoadDefaultConfig() {
        String previous = System.getProperty("db.password");
        ServerConfig config;
        try {
            System.setProperty("db.password", "test-only-password");
            config = ServerConfig.load();
        } finally {
            if (previous == null) {
                System.clearProperty("db.password");
            } else {
                System.setProperty("db.password", previous);
            }
        }

        assertNotNull(config);
        assertEquals(5555, config.getPort());
        assertEquals(100, config.getMaxConnections());
        assertEquals(8, config.getWorkerPoolSize());
        assertEquals(200, config.getWorkerQueueCapacity());
        assertEquals(3, config.getRequestGraceSeconds());
        assertEquals("jdbc:postgresql://localhost:5432/quizdb", config.getDbUrl());
        assertEquals("quiz", config.getDbUser());
        assertEquals("test-only-password", config.getDbPassword());
        assertEquals(10, config.getDbPoolMaxSize());
        assertEquals(2, config.getDbPoolMinIdle());
        assertEquals(300000L, config.getDbPoolIdleTimeoutMs());
        assertEquals(20000L, config.getDbPoolConnectionTimeoutMs());
    }

    @Test
    @DisplayName("Should parse custom properties correctly")
    void testFromCustomProperties() {
        Properties props = new Properties();
        props.setProperty("server.port", "9090");
        props.setProperty("server.max.connections", "50");
        props.setProperty("server.worker.pool.size", "4");
        props.setProperty("server.worker.queue.capacity", "100");
        props.setProperty("server.request.grace.seconds", "5");
        props.setProperty("db.url", "jdbc:postgresql://db.server:5432/proddb");
        props.setProperty("db.user", "admin_user");
        props.setProperty("db.password", "secret");
        props.setProperty("db.pool.max.size", "20");
        props.setProperty("db.pool.min.idle", "5");
        props.setProperty("db.pool.idle.timeout.ms", "600000");
        props.setProperty("db.pool.connection.timeout.ms", "15000");

        ServerConfig config = ServerConfig.fromProperties(props);

        assertEquals(9090, config.getPort());
        assertEquals(50, config.getMaxConnections());
        assertEquals(4, config.getWorkerPoolSize());
        assertEquals(100, config.getWorkerQueueCapacity());
        assertEquals(5, config.getRequestGraceSeconds());
        assertEquals("jdbc:postgresql://db.server:5432/proddb", config.getDbUrl());
        assertEquals("admin_user", config.getDbUser());
        assertEquals("secret", config.getDbPassword());
        assertEquals(20, config.getDbPoolMaxSize());
        assertEquals(5, config.getDbPoolMinIdle());
        assertEquals(600000L, config.getDbPoolIdleTimeoutMs());
        assertEquals(15000L, config.getDbPoolConnectionTimeoutMs());
    }

    @Test
    @DisplayName("Should throw exception for invalid port")
    void testInvalidPort() {
        assertThrows(IllegalArgumentException.class, () -> new ServerConfig(
            0, 100, 8, 200, 3, "jdbc:postgresql://localhost/db", "user", "pass", 10, 2, 300000, 20000
        ));

        assertThrows(IllegalArgumentException.class, () -> new ServerConfig(
            70000, 100, 8, 200, 3, "jdbc:postgresql://localhost/db", "user", "pass", 10, 2, 300000, 20000
        ));
    }

    @Test
    @DisplayName("Should throw exception for invalid pool size or missing db url")
    void testInvalidDbConfig() {
        assertThrows(IllegalArgumentException.class, () -> new ServerConfig(
            5555, 100, 8, 200, 3, "", "user", "pass", 10, 2, 300000, 20000
        ));

        assertThrows(IllegalArgumentException.class, () -> new ServerConfig(
            5555, 100, 8, 200, 3, "jdbc:postgresql://localhost/db", "", "pass", 10, 2, 300000, 20000
        ));

        assertThrows(IllegalArgumentException.class, () -> new ServerConfig(
            5555, 100, 8, 200, 3, "jdbc:postgresql://localhost/db", "user", "pass", 0, 2, 300000, 20000
        ));
    }
}
