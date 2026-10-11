package quiz.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import quiz.server.config.ServerConfig;
import java.nio.file.*;
import java.util.Properties;
import static org.junit.jupiter.api.Assertions.*;

class ServerMainTest {
    @TempDir Path directory;

    @Test void loadsExternalConfigAndRejectsBadArguments() throws Exception {
        Path file = directory.resolve("test-local.properties");
        Files.writeString(file, "db.password=test-only\nserver.handshake.timeout.ms=321\nserver.read.timeout.ms=654\nserver.shutdown.timeout.ms=987\n");
        var config = ServerMain.loadConfig(new String[]{"--config", file.toString()});
        assertEquals(321, config.getHandshakeTimeoutMs());
        assertEquals(654, config.getReadTimeoutMs());
        assertEquals(987, config.getShutdownTimeoutMs());
        assertThrows(IllegalArgumentException.class, () -> ServerMain.loadConfig(new String[]{"--config"}));
        assertThrows(IllegalArgumentException.class, () -> ServerMain.loadConfig(new String[]{"--unknown", "x"}));
        assertThrows(java.io.IOException.class, () -> ServerMain.loadConfig(new String[]{"--config", directory.resolve("missing").toString()}));
    }
    @Test void validatesTimeoutsAndSupportsSystemOverride() {
        for (String key : new String[]{"server.handshake.timeout.ms", "server.read.timeout.ms", "server.shutdown.timeout.ms"}) {
            Properties props = new Properties(); props.setProperty("db.password", "test-only");
            for (String value : new String[]{"0", "-1", "not-an-int"}) {
                props.setProperty(key, value);
                assertThrows(IllegalArgumentException.class, () -> ServerConfig.fromProperties(props));
            }
        }
        String key = "server.handshake.timeout.ms", previous = System.getProperty(key);
        try {
            System.setProperty(key, "1234");
            Properties props = new Properties(); props.setProperty("db.password", "test-only");
            assertEquals(1234, ServerConfig.fromProperties(props).getHandshakeTimeoutMs());
        } finally {
            if (previous == null) System.clearProperty(key); else System.setProperty(key, previous);
        }
    }
}
