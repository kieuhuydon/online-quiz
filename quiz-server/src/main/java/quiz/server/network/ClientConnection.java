package quiz.server.network;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quiz.common.protocol.MessageCodec;
import quiz.common.protocol.Response;

import java.io.IOException;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.util.Objects;

/**
 * Đại diện cho kết nối Socket của một Client.
 * Bọc ObjectOutputStream và cung cấp phương thức send() được đồng bộ hóa (synchronized)
 * để đảm bảo an toàn đa luồng giữa Worker threads (xử lý Request) và Scheduler/Event threads (gửi PUSH).
 */
public class ClientConnection implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ClientConnection.class);

    private final Socket socket;
    private final ObjectOutputStream out;
    private volatile Long userId;
    private volatile String token;

    public String getToken() { return token; }

    void setToken(String token) { this.token = token; }

    public ClientConnection(Socket socket, ObjectOutputStream out) {
        this.socket = Objects.requireNonNull(socket, "Socket cannot be null");
        this.out = Objects.requireNonNull(out, "ObjectOutputStream cannot be null");
    }

    /**
     * Gửi Response (hoặc PUSH notification) xuống Client.
     * Phương thức bắt buộc phải synchronized để tránh lỗi CorruptedStream khi nhiều thread
     * ghi đồng thời vào cùng một ObjectOutputStream.
     */
    public synchronized void send(Response response) throws IOException {
        Objects.requireNonNull(response, "Response cannot be null");
        if (isClosed()) {
            throw new IOException("Cannot send message: socket is closed or disconnected");
        }
        MessageCodec.write(out, response);
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Socket getSocket() {
        return socket;
    }

    public boolean isClosed() {
        return socket == null || socket.isClosed() || !socket.isConnected();
    }

    @Override
    public void close() {
        // Closing the socket must unblock send(), without locking or flushing its writer.
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException e) {
            log.debug("Error while closing client socket: {}", e.getMessage());
        }
    }
}
