package quiz.client.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.IOException;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class ClientConfigTest {
    @TempDir Path temporary;

    @Test void loadsDefaultsAndPartialFileOverride() throws Exception {
        var defaults = ClientConfig.fromArgs();
        assertEquals("localhost", defaults.host()); assertEquals(5555, defaults.port());
        assertEquals(5000, defaults.connectTimeoutMs()); assertEquals(5000, defaults.handshakeTimeoutMs());
        assertEquals(30000, defaults.requestTimeoutMs()); assertEquals(64, defaults.maxPendingRequests());
        assertEquals(3000, defaults.shutdownTimeoutMs()); assertEquals(60000, defaults.heartbeatIntervalMs());
        Path file = temporary.resolve("client.properties");
        Files.writeString(file, "server.host= 127.0.0.1 \nserver.port=65535\n");
        var override = ClientConfig.fromArgs("--config", file.toString());
        assertEquals("127.0.0.1", override.host()); assertEquals(65535, override.port());
        assertEquals(defaults.requestTimeoutMs(), override.requestTimeoutMs());
    }

    @ParameterizedTest @ValueSource(strings = {"server.host=", "server.port=0", "server.port=65536",
        "server.port=not-a-number", "connect.timeout.ms=0", "handshake.timeout.ms=-1",
        "request.timeout.ms=2147483648", "max.pending.requests=0", "shutdown.timeout.ms=0", "heartbeat.interval.ms=0", "heartbeat.interval.ms=abc"})
    void invalidPropertyNamesFieldWithoutEchoingValue(String property) throws Exception {
        Path file = temporary.resolve("invalid.properties");
        Files.writeString(file, property);
        var error = assertThrows(IllegalArgumentException.class, () -> ClientConfig.load(file));
        assertEquals("Invalid client configuration: " + property.split("=", 2)[0], error.getMessage());
    }

    @Test void missingFileAndBadArgumentsFailClearly() {
        assertThrows(IOException.class, () -> ClientConfig.load(temporary.resolve("missing")));
        assertThrows(IllegalArgumentException.class, () -> ClientConfig.fromArgs("--config"));
        assertThrows(IllegalArgumentException.class, () -> ClientConfig.fromArgs("--unknown", "path"));
        assertEquals(1, new ClientConfig("host", 1, 1, 1, 1, 1, 1).port());
    }
}
