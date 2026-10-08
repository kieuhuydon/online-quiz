package quiz.server.network;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import quiz.common.protocol.MessageType;
import quiz.common.protocol.Response;

import java.io.IOException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("ConnectionRegistry Unit Tests")
class ConnectionRegistryTest {

    private ConnectionRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ConnectionRegistry();
    }

    @Test
    @DisplayName("Should register, query and track online connections")
    void testRegisterAndTrackConnection() {
        ClientConnection connection = mock(ClientConnection.class);
        when(connection.isClosed()).thenReturn(false);

        registry.register(100L, connection);

        assertEquals(1, registry.size());
        assertTrue(registry.isUserOnline(100L));
        assertFalse(registry.isUserOnline(999L));

        Optional<ClientConnection> found = registry.get(100L);
        assertTrue(found.isPresent());
        assertEquals(connection, found.get());

        verify(connection).setUserId(100L);
    }

    @Test
    @DisplayName("Should send message to specific online user")
    void testSendToUser() throws IOException {
        ClientConnection connection = mock(ClientConnection.class);
        when(connection.isClosed()).thenReturn(false);

        registry.register(101L, connection);
        Response response = Response.push(MessageType.PUSH_ANTICHEAT_WARNING, "Warning");

        boolean sent = registry.sendToUser(101L, response);
        assertTrue(sent);
        verify(connection).send(response);

        // Sending to offline user
        boolean sentOffline = registry.sendToUser(999L, response);
        assertFalse(sentOffline);
    }

    @Test
    @DisplayName("Should broadcast push notifications to all online connections")
    void testBroadcast() throws IOException {
        ClientConnection conn1 = mock(ClientConnection.class);
        ClientConnection conn2 = mock(ClientConnection.class);
        when(conn1.isClosed()).thenReturn(false);
        when(conn2.isClosed()).thenReturn(false);

        registry.register(1L, conn1);
        registry.register(2L, conn2);

        Response broadcastMsg = Response.push(MessageType.PUSH_FORCE_SUBMITTED, "Time is up");
        registry.broadcast(broadcastMsg);

        verify(conn1).send(broadcastMsg);
        verify(conn2).send(broadcastMsg);
    }

    @Test
    @DisplayName("Should remove connection properly")
    void testRemoveConnection() {
        ClientConnection conn = mock(ClientConnection.class);
        registry.register(5L, conn);
        assertEquals(1, registry.size());

        registry.remove(5L);
        assertEquals(0, registry.size());
        assertFalse(registry.isUserOnline(5L));

        // Test remove by connection instance
        registry.register(6L, conn);
        assertEquals(1, registry.size());
        registry.removeConnection(conn);
        assertEquals(0, registry.size());
    }
}
