package quiz.server.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quiz.server.config.ServerConfig;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

/**
 * Quản lý vòng đời của HikariDataSource Connection Pool.
 */
public final class DataSourceProvider {
    private static final Logger log = LoggerFactory.getLogger(DataSourceProvider.class);
    private static volatile HikariDataSource dataSource;

    private DataSourceProvider() {
    }

    /**
     * Khởi tạo pool dựa trên ServerConfig.
     */
    public static synchronized void init(ServerConfig config) {
        Objects.requireNonNull(config, "ServerConfig cannot be null");
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(config.getDbUrl());
        hikariConfig.setUsername(config.getDbUser());
        hikariConfig.setPassword(config.getDbPassword());
        hikariConfig.setMaximumPoolSize(config.getDbPoolMaxSize());
        hikariConfig.setMinimumIdle(config.getDbPoolMinIdle());
        hikariConfig.setIdleTimeout(config.getDbPoolIdleTimeoutMs());
        hikariConfig.setConnectionTimeout(config.getDbPoolConnectionTimeoutMs());
        hikariConfig.setPoolName("QuizServerHikariPool");

        // pgJDBC already maintains a per-connection prepared statement cache.
        log.info("Initializing HikariCP, PoolMaxSize: {}", config.getDbPoolMaxSize());
        init(hikariConfig);
    }

    /**
     * Khởi tạo pool trực tiếp từ HikariConfig (hữu ích cho custom config hoặc kiểm thử).
     */
    public static synchronized void init(HikariConfig hikariConfig) {
        Objects.requireNonNull(hikariConfig, "HikariConfig cannot be null");
        HikariDataSource replacement = new HikariDataSource(hikariConfig);
        HikariDataSource previous = dataSource;
        dataSource = replacement;
        if (previous != null && !previous.isClosed()) {
            log.warn("DataSourceProvider already initialized, closing previous pool");
            previous.close();
        }
    }

    /**
     * Lấy HikariDataSource hiện tại.
     */
    public static HikariDataSource getDataSource() {
        HikariDataSource current = dataSource;
        if (current == null || current.isClosed()) {
            throw new IllegalStateException("DataSourceProvider has not been initialized or is closed");
        }
        return current;
    }

    /**
     * Lấy một kết nối từ pool.
     */
    public static Connection getConnection() throws SQLException {
        return getDataSource().getConnection();
    }

    /**
     * Kiểm tra trạng thái khởi tạo của pool.
     */
    public static boolean isInitialized() {
        HikariDataSource current = dataSource;
        return current != null && !current.isClosed();
    }

    /**
     * Đóng pool an toàn khi shutdown server.
     */
    public static synchronized void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            log.info("Closing HikariCP connection pool...");
            dataSource.close();
            dataSource = null;
        }
    }
}
