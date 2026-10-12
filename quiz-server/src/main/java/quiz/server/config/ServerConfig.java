package quiz.server.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Cấu hình hệ thống Server đọc từ file server.properties,
 * hỗ trợ ghi đè qua System Properties hoặc biến môi trường.
 */
public class ServerConfig {
    private static final Logger log = LoggerFactory.getLogger(ServerConfig.class);
    private static final String DEFAULT_PROPERTIES_FILE = "/server.properties";

    private final int port;
    private final int maxConnections;
    private final int workerPoolSize;
    private final int workerQueueCapacity;
    private final int requestGraceSeconds;
    private final int handshakeTimeoutMs;
    private final int readTimeoutMs;
    private final int shutdownTimeoutMs;

    private final String dbUrl;
    private final String dbUser;
    private final String dbPassword;
    private final int dbPoolMaxSize;
    private final int dbPoolMinIdle;
    private final long dbPoolIdleTimeoutMs;
    private final long dbPoolConnectionTimeoutMs;

    public ServerConfig(
        int port,
        int maxConnections,
        int workerPoolSize,
        int workerQueueCapacity,
        int requestGraceSeconds,
        String dbUrl,
        String dbUser,
        String dbPassword,
        int dbPoolMaxSize,
        int dbPoolMinIdle,
        long dbPoolIdleTimeoutMs,
        long dbPoolConnectionTimeoutMs
    ) {
        this(port, maxConnections, workerPoolSize, workerQueueCapacity, requestGraceSeconds,
            dbUrl, dbUser, dbPassword, dbPoolMaxSize, dbPoolMinIdle, dbPoolIdleTimeoutMs,
            dbPoolConnectionTimeoutMs, 5000, 300000, 10000);
    }

    public ServerConfig(int port, int maxConnections, int workerPoolSize, int workerQueueCapacity,
                        int requestGraceSeconds, String dbUrl, String dbUser, String dbPassword,
                        int dbPoolMaxSize, int dbPoolMinIdle, long dbPoolIdleTimeoutMs,
                        long dbPoolConnectionTimeoutMs, int handshakeTimeoutMs, int readTimeoutMs,
                        int shutdownTimeoutMs) {
        if (handshakeTimeoutMs <= 0 || readTimeoutMs <= 0 || shutdownTimeoutMs <= 0) {
            throw new IllegalArgumentException("Server handshake/read/shutdown timeouts must be positive");
        }
        this.handshakeTimeoutMs = handshakeTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        this.shutdownTimeoutMs = shutdownTimeoutMs;
        validate(port, maxConnections, workerPoolSize, workerQueueCapacity,
            requestGraceSeconds, dbUrl, dbUser, dbPoolMaxSize, dbPoolMinIdle);
        if (dbPassword == null || dbPassword.isBlank() || "change-me".equals(dbPassword)) {
            throw new IllegalArgumentException("Set db.password or DB_PASSWORD to a non-placeholder password");
        }
        if (dbPoolMinIdle > dbPoolMaxSize) {
            throw new IllegalArgumentException("db.pool.min.idle cannot exceed db.pool.max.size");
        }
        if (dbPoolIdleTimeoutMs != 0 && dbPoolIdleTimeoutMs < 10000) {
            throw new IllegalArgumentException("db.pool.idle.timeout.ms must be 0 or at least 10000");
        }
        if (dbPoolConnectionTimeoutMs < 250 || dbPoolConnectionTimeoutMs > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("db.pool.connection.timeout.ms must be between 250 and 2147483647");
        }

        this.port = port;
        this.maxConnections = maxConnections;
        this.workerPoolSize = workerPoolSize;
        this.workerQueueCapacity = workerQueueCapacity;
        this.requestGraceSeconds = requestGraceSeconds;
        this.dbUrl = dbUrl;
        this.dbUser = dbUser;
        this.dbPassword = dbPassword;
        this.dbPoolMaxSize = dbPoolMaxSize;
        this.dbPoolMinIdle = dbPoolMinIdle;
        this.dbPoolIdleTimeoutMs = dbPoolIdleTimeoutMs;
        this.dbPoolConnectionTimeoutMs = dbPoolConnectionTimeoutMs;
    }

    /**
     * Tải cấu hình mặc định từ classpath resource /server.properties.
     */
    public static ServerConfig load() {
        Properties props = new Properties();
        try (InputStream in = ServerConfig.class.getResourceAsStream(DEFAULT_PROPERTIES_FILE)) {
            if (in != null) {
                props.load(in);
            } else {
                log.warn("Resource {} not found on classpath, using defaults", DEFAULT_PROPERTIES_FILE);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load server configuration", e);
        }
        return fromProperties(props);
    }

    /**
     * Tải cấu hình từ đường dẫn file bên ngoài.
     */
    public static ServerConfig load(Path filePath) throws IOException {
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(filePath)) {
            props.load(in);
        }
        return fromProperties(props);
    }

    /**
     * Khởi tạo ServerConfig từ đối tượng Properties với cơ chế override.
     */
    public static ServerConfig fromProperties(Properties props) {
        int port = getInt(props, "server.port", "SERVER_PORT", 5555);
        int maxConnections = getInt(props, "server.max.connections", "SERVER_MAX_CONNECTIONS", 100);
        int workerPoolSize = getInt(props, "server.worker.pool.size", "SERVER_WORKER_POOL_SIZE", 8);
        int workerQueueCapacity = getInt(props, "server.worker.queue.capacity", "SERVER_WORKER_QUEUE_CAPACITY", 200);
        int requestGraceSeconds = getInt(props, "server.request.grace.seconds", "SERVER_REQUEST_GRACE_SECONDS", 3);

        String dbUrl = getString(props, "db.url", "DB_URL", "jdbc:postgresql://localhost:5432/quizdb");
        String dbUser = getString(props, "db.user", "DB_USER", "quiz");
        String dbPassword = getString(props, "db.password", "DB_PASSWORD", null);
        int dbPoolMaxSize = getInt(props, "db.pool.max.size", "DB_POOL_MAX_SIZE", 10);
        int dbPoolMinIdle = getInt(props, "db.pool.min.idle", "DB_POOL_MIN_IDLE", 2);
        long dbPoolIdleTimeoutMs = getLong(props, "db.pool.idle.timeout.ms", "DB_POOL_IDLE_TIMEOUT_MS", 300000L);
        long dbPoolConnectionTimeoutMs = getLong(props, "db.pool.connection.timeout.ms", "DB_POOL_CONNECTION_TIMEOUT_MS", 20000L);

        return new ServerConfig(
            port,
            maxConnections,
            workerPoolSize,
            workerQueueCapacity,
            requestGraceSeconds,
            dbUrl,
            dbUser,
            dbPassword,
            dbPoolMaxSize,
            dbPoolMinIdle,
            dbPoolIdleTimeoutMs,
            dbPoolConnectionTimeoutMs,
            getInt(props, "server.handshake.timeout.ms", "SERVER_HANDSHAKE_TIMEOUT_MS", 5000),
            getInt(props, "server.read.timeout.ms", "SERVER_READ_TIMEOUT_MS", 300000),
            getInt(props, "server.shutdown.timeout.ms", "SERVER_SHUTDOWN_TIMEOUT_MS", 10000)
        );
    }

    private static void validate(
        int port,
        int maxConnections,
        int workerPoolSize,
        int workerQueueCapacity,
        int requestGraceSeconds,
        String dbUrl,
        String dbUser,
        int dbPoolMaxSize,
        int dbPoolMinIdle
    ) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("server.port must be between 1 and 65535, got: " + port);
        }
        if (maxConnections <= 0) {
            throw new IllegalArgumentException("server.max.connections must be positive, got: " + maxConnections);
        }
        if (workerPoolSize <= 0) {
            throw new IllegalArgumentException("server.worker.pool.size must be positive, got: " + workerPoolSize);
        }
        if (workerQueueCapacity <= 0) {
            throw new IllegalArgumentException("server.worker.queue.capacity must be positive, got: " + workerQueueCapacity);
        }
        if (requestGraceSeconds < 0) {
            throw new IllegalArgumentException("server.request.grace.seconds cannot be negative, got: " + requestGraceSeconds);
        }
        if (dbUrl == null || dbUrl.isBlank()) {
            throw new IllegalArgumentException("db.url cannot be null or blank");
        }
        if (dbUser == null || dbUser.isBlank()) {
            throw new IllegalArgumentException("db.user cannot be null or blank");
        }
        if (dbPoolMaxSize <= 0) {
            throw new IllegalArgumentException("db.pool.max.size must be positive, got: " + dbPoolMaxSize);
        }
        if (dbPoolMinIdle < 0) {
            throw new IllegalArgumentException("db.pool.min.idle cannot be negative, got: " + dbPoolMinIdle);
        }
    }

    private static String getString(Properties props, String propKey, String envKey, String defaultValue) {
        String envVal = System.getenv(envKey);
        if (envVal != null && !envVal.isBlank()) {
            return envVal;
        }
        String sysVal = System.getProperty(propKey);
        if (sysVal != null && !sysVal.isBlank()) {
            return sysVal;
        }
        String propVal = props.getProperty(propKey);
        if (propVal != null && !propVal.isBlank()) {
            return propVal;
        }
        return defaultValue;
    }

    private static int getInt(Properties props, String propKey, String envKey, int defaultValue) {
        String val = getString(props, propKey, envKey, null);
        if (val == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(val.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid integer configuration: " + propKey);
        }
    }

    private static long getLong(Properties props, String propKey, String envKey, long defaultValue) {
        String val = getString(props, propKey, envKey, null);
        if (val == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(val.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid long configuration: " + propKey);
        }
    }

    public int getPort() {
        return port;
    }

    public int getHandshakeTimeoutMs() { return handshakeTimeoutMs; }
    public int getReadTimeoutMs() { return readTimeoutMs; }
    public int getShutdownTimeoutMs() { return shutdownTimeoutMs; }

    public int getMaxConnections() {
        return maxConnections;
    }

    public int getWorkerPoolSize() {
        return workerPoolSize;
    }

    public int getWorkerQueueCapacity() {
        return workerQueueCapacity;
    }

    public int getRequestGraceSeconds() {
        return requestGraceSeconds;
    }

    public String getDbUrl() {
        return dbUrl;
    }

    public String getDbUser() {
        return dbUser;
    }

    public String getDbPassword() {
        return dbPassword;
    }

    public int getDbPoolMaxSize() {
        return dbPoolMaxSize;
    }

    public int getDbPoolMinIdle() {
        return dbPoolMinIdle;
    }

    public long getDbPoolIdleTimeoutMs() {
        return dbPoolIdleTimeoutMs;
    }

    public long getDbPoolConnectionTimeoutMs() {
        return dbPoolConnectionTimeoutMs;
    }
}
