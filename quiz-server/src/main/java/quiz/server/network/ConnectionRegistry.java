package quiz.server.network;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quiz.common.protocol.Response;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Quản lý các kết nối Socket đang online của người dùng theo userId
 * phục vụ việc đẩy (PUSH) thông điệp chủ động từ Server (cảnh báo vi phạm, hết giờ force-submit, ...).
 */
public class ConnectionRegistry {
    private static final Logger log = LoggerFactory.getLogger(ConnectionRegistry.class);

    private final Map<Long, ClientConnection> userConnections = new ConcurrentHashMap<>();

    /**
     * Đăng ký liên kết giữa userId và kết nối socket của họ sau khi LOGIN thành công.
     */
    public void register(long userId, ClientConnection connection) {
        Objects.requireNonNull(connection, "ClientConnection cannot be null");
        connection.setUserId(userId);
        userConnections.put(userId, connection);
        log.debug("Registered connection for userId={}, total online: {}", userId, userConnections.size());
    }

    /**
     * Lấy kết nối socket của một user.
     */
    public Optional<ClientConnection> get(long userId) {
        return Optional.ofNullable(userConnections.get(userId));
    }

    /**
     * Kiểm tra người dùng có đang online hay không.
     */
    public boolean isUserOnline(long userId) {
        ClientConnection connection = userConnections.get(userId);
        return connection != null && !connection.isClosed();
    }

    /**
     * Xóa kết nối theo userId (khi LOGOUT hoặc mất kết nối).
     */
    public Optional<ClientConnection> remove(long userId) {
        ClientConnection removed = userConnections.remove(userId);
        if (removed != null) {
            log.debug("Removed connection for userId={}, remaining online: {}", userId, userConnections.size());
        }
        return Optional.ofNullable(removed);
    }

    /**
     * Xóa kết nối theo đối tượng ClientConnection cụ thể (khi socket đóng).
     */
    public void removeConnection(ClientConnection connection) {
        if (connection == null) {
            return;
        }
        userConnections.entrySet().removeIf(entry -> entry.getValue().equals(connection));
    }

    /**
     * Gửi Response/PUSH tới một user cụ thể nếu họ đang online.
     *
     * @return true nếu gửi thành công, false nếu user offline hoặc gửi thất bại
     */
    public boolean sendToUser(long userId, Response response) {
        ClientConnection conn = userConnections.get(userId);
        if (conn == null || conn.isClosed()) {
            return false;
        }
        try {
            conn.send(response);
            return true;
        } catch (IOException e) {
            log.warn("Failed to send message to userId={}, removing connection: {}", userId, e.getMessage());
            userConnections.remove(userId, conn);
            conn.close();
            return false;
        }
    }

    /**
     * Phát sóng thông điệp PUSH tới tất cả người dùng đang kết nối online.
     */
    public void broadcast(Response pushResponse) {
        Objects.requireNonNull(pushResponse, "Push response cannot be null");
        userConnections.forEach((userId, conn) -> {
            try {
                if (!conn.isClosed()) {
                    conn.send(pushResponse);
                }
            } catch (IOException e) {
                log.warn("Failed to broadcast to userId={}, removing connection: {}", userId, e.getMessage());
                userConnections.remove(userId, conn);
                conn.close();
            }
        });
    }

    /**
     * Số lượng người dùng đang online.
     */
    public int size() {
        return userConnections.size();
    }

    /**
     * Xóa toàn bộ kết nối (khi tắt server).
     */
    public void clear() {
        userConnections.clear();
    }
}
