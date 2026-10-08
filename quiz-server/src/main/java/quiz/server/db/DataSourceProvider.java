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
        if (dataSource != null && !dataSource.isClosed()) {
            log.warn("DataSourceProvider already initialized, closing previous pool");
            dataSource.close();
        }

        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(config.getDbUrl());
        hikariConfig.setUsername(config.getDbUser());
        hikariConfig.setPassword(config.getDbPassword());
        hikariConfig.setMaximumPoolSize(config.getDbPoolMaxSize());
        hikariConfig.setMinimumIdle(config.getDbPoolMinIdle());
        hikariConfig.setIdleTimeout(config.getDbPoolIdleTimeoutMs());
        hikariConfig.setConnectionTimeout(config.getDbPoolConnectionTimeoutMs());
        hikariConfig.setPoolName("QuizServerHikariPool");

        // Performance optimizations recommended for PostgreSQL
        hikariConfig.addDataSourceProperty("cachePrepStmts", "true");
        hikariConfig.addDataSourceProperty("prepStmtCacheSize", "250");
        hikariConfig.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");

        log.info("Initializing HikariCP with URL: {}, PoolMaxSize: {}", config.getDbUrl(), config.getDbPoolMaxSize());
        dataSource = new HikariDataSource(hikariConfig);
    }

    /**
     * Khởi tạo pool trực tiếp từ HikariConfig (hữu ích cho custom config hoặc kiểm thử).
     */
    public static synchronized void init(HikariConfig hikariConfig) {
        Objects.requireNonNull(hikariConfig, "HikariConfig cannot be null");
        if (dataSource != null && !dataSource.isClosed()) {
            log.warn("DataSourceProvider already initialized, closing previous pool");
            dataSource.close();
        }
        dataSource = new HikariDataSource(hikariConfig);
    }

    /**
     * Lấy HikariDataSource hiện tại.
     */
    public static HikariDataSource getDataSource() {
        if (dataSource == null || dataSource.isClosed()) {
            throw new IllegalStateException("DataSourceProvider has not been initialized or is closed");
        }
        return dataSource;
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
        return dataSource != null && !dataSource.isClosed();
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
