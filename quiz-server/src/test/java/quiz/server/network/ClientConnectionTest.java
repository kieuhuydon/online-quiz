package quiz.server.network;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import quiz.common.protocol.Response;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.net.Socket;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("ClientConnection Unit Tests")
class ClientConnectionTest {

    @Test
    @DisplayName("Should send response through ObjectOutputStream safely")
    void testSendResponse() throws IOException {
        Socket mockSocket = mock(Socket.class);
        when(mockSocket.isConnected()).thenReturn(true);
        when(mockSocket.isClosed()).thenReturn(false);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(baos);

        ClientConnection connection = new ClientConnection(mockSocket, oos);
        connection.setUserId(42L);

        assertEquals(42L, connection.getUserId());
        assertFalse(connection.isClosed());
        assertEquals(mockSocket, connection.getSocket());

        Response response = Response.success(1L, "OK");
        assertDoesNotThrow(() -> connection.send(response));
        assertTrue(baos.size() > 0, "Bytes must be written to the output stream");
    }

    @Test
    @DisplayName("Should throw IOException when attempting to send over closed connection")
    void testSendOnClosedConnection() throws IOException {
        Socket mockSocket = mock(Socket.class);
        when(mockSocket.isClosed()).thenReturn(true);

        ObjectOutputStream mockOut = mock(ObjectOutputStream.class);
        ClientConnection connection = new ClientConnection(mockSocket, mockOut);

        Response response = Response.success(1L, "OK");
        assertThrows(IOException.class, () -> connection.send(response));
    }

    @Test
    @DisplayName("Should close socket without touching a potentially blocked writer")
    void testClose() throws IOException {
        Socket mockSocket = mock(Socket.class);
        when(mockSocket.isClosed()).thenReturn(false);

        ObjectOutputStream mockOut = mock(ObjectOutputStream.class);
        ClientConnection connection = new ClientConnection(mockSocket, mockOut);

        connection.close();

        verifyNoInteractions(mockOut);
        verify(mockSocket).close();
    }
}
