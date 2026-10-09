package quiz.server.db;

import com.zaxxer.hikari.HikariConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("DataSourceProvider Unit Tests")
class DataSourceProviderTest {

    @Test
    void invalidReplacementDoesNotDestroyExistingPool() {
        HikariConfig initial = new HikariConfig();
        initial.setJdbcUrl("jdbc:postgresql://localhost:5432/nonexistent");
        initial.setInitializationFailTimeout(-1);
        initial.setMinimumIdle(0);
        DataSourceProvider.init(initial);
        var original = DataSourceProvider.getDataSource();
        assertThrows(IllegalArgumentException.class, () -> DataSourceProvider.init(new HikariConfig()));
        assertSame(original, DataSourceProvider.getDataSource());
        assertFalse(original.isClosed());
    }

    @AfterEach
    void tearDown() {
        DataSourceProvider.close();
    }

    @Test
    @DisplayName("Should initialize and close HikariDataSource properly")
    void testInitAndClose() {
        assertFalse(DataSourceProvider.isInitialized());

        HikariConfig config = new HikariConfig();
        // Use in-memory or mock driver url that doesn't require live postgres
        config.setJdbcUrl("jdbc:postgresql://localhost:5432/nonexistent");
        config.setUsername("test");
        config.setPassword("test");
        config.setInitializationFailTimeout(-1); // Don't fail immediately on connect
        config.setMaximumPoolSize(2);

        DataSourceProvider.init(config);
        assertTrue(DataSourceProvider.isInitialized());
        assertNotNull(DataSourceProvider.getDataSource());

        DataSourceProvider.close();
        assertFalse(DataSourceProvider.isInitialized());
        assertThrows(IllegalStateException.class, DataSourceProvider::getDataSource);
    }
}
