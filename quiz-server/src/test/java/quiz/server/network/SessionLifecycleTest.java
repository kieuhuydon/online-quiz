package quiz.server.network;

import org.junit.jupiter.api.Test;
import quiz.common.dto.LoginResultDto;
import quiz.common.enums.Role;
import quiz.server.service.SessionCoordinator;
import java.io.ObjectOutputStream;
import java.net.*;
import static org.junit.jupiter.api.Assertions.*;

class SessionLifecycleTest {
    @Test void staleBindingNeverReplacesNewConnectionAndCleanupIsIdempotent() throws Exception {
        var sessions = new SessionRegistry(); var connections = new ConnectionRegistry();
        var coordinator = new SessionCoordinator(sessions); var lifecycle = new SessionLifecycle(coordinator, connections);
        try (ServerSocket listener = new ServerSocket(0);
             Socket first = new Socket("127.0.0.1", listener.getLocalPort()); Socket remoteFirst = listener.accept();
             Socket second = new Socket("127.0.0.1", listener.getLocalPort()); Socket remoteSecond = listener.accept()) {
            var a = new ClientConnection(first, new ObjectOutputStream(first.getOutputStream()));
            var b = new ClientConnection(second, new ObjectOutputStream(second.getOutputStream()));
            String old = coordinator.openSession(new UserSession(7, Role.ADMIN, "admin"));
            String current = coordinator.openSession(new UserSession(7, Role.ADMIN, "admin"));
            assertTrue(lifecycle.bind(b, new LoginResultDto(current, 7, "admin", "Admin", Role.ADMIN)));
            assertFalse(lifecycle.bind(a, new LoginResultDto(old, 7, "admin", "Admin", Role.ADMIN)));
            lifecycle.disconnect(a); lifecycle.disconnect(a);
            assertSame(b, connections.get(7).orElseThrow()); assertTrue(lifecycle.owns(b, current));
            lifecycle.unbind(b); lifecycle.unbind(b);
            assertNull(b.getUserId()); assertNull(b.getToken()); assertFalse(b.isClosed());
            assertEquals(0, sessions.size()); assertEquals(0, connections.size());
            String late = coordinator.openSession(new UserSession(7, Role.ADMIN, "admin")); b.close();
            assertFalse(lifecycle.bind(b, new LoginResultDto(late, 7, "admin", "Admin", Role.ADMIN)));
            assertEquals(0, sessions.size());
        }
    }
}
