package quiz.client.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Immutable client configuration; an external file overrides bundled defaults. */
public record ClientConfig(String host, int port, int connectTimeoutMs, int handshakeTimeoutMs,
                           int requestTimeoutMs, int maxPendingRequests, int shutdownTimeoutMs) {
    public ClientConfig {
        if (host == null || host.isBlank()) {
            throw invalid("server.host");
        }
        host = host.strip();
        if (port < 1 || port > 65535) {
            throw invalid("server.port");
        }
        positive(connectTimeoutMs, "connect.timeout.ms");
        positive(handshakeTimeoutMs, "handshake.timeout.ms");
        positive(requestTimeoutMs, "request.timeout.ms");
        positive(maxPendingRequests, "max.pending.requests");
        positive(shutdownTimeoutMs, "shutdown.timeout.ms");
    }

    public static ClientConfig load() throws IOException {
        return load((Path) null);
    }

    public static ClientConfig load(Path override) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = ClientConfig.class.getResourceAsStream("/client.properties")) {
            if (in == null) {
                throw new IOException("Missing client.properties resource");
            }
            properties.load(in);
        }
        if (override != null) {
            try (InputStream in = Files.newInputStream(override)) {
                properties.load(in);
            }
        }
        return from(properties);
    }

    /** Accepts either no arguments or exactly --config path. */
    public static ClientConfig fromArgs(String... args) throws IOException {
        if (args.length == 0) {
            return load();
        }
        if (args.length == 2 && "--config".equals(args[0])) {
            return load(Path.of(args[1]));
        }
        throw new IllegalArgumentException("Usage: --config <path>");
    }

    public static ClientConfig from(Properties properties) {
        return new ClientConfig(properties.getProperty("server.host"), number(properties, "server.port"),
            number(properties, "connect.timeout.ms"), number(properties, "handshake.timeout.ms"),
            number(properties, "request.timeout.ms"), number(properties, "max.pending.requests"),
            number(properties, "shutdown.timeout.ms"));
    }

    private static int number(Properties properties, String key) {
        try {
            return Integer.parseInt(properties.getProperty(key, "").strip());
        } catch (NumberFormatException invalid) {
            throw invalid(key);
        }
    }

    private static void positive(int value, String key) {
        if (value <= 0) {
            throw invalid(key);
        }
    }

    private static IllegalArgumentException invalid(String key) {
        return new IllegalArgumentException("Invalid client configuration: " + key);
    }
}
