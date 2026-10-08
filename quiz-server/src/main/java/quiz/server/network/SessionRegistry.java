package quiz.server.network;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Quản lý phiên làm việc đang hoạt động (in-memory Session Registry)
 * sử dụng ConcurrentHashMap đảm bảo thread-safe.
 */
public class SessionRegistry {
    private final Map<String, UserSession> tokenSessions = new ConcurrentHashMap<>();

    /**
     * Đăng ký một phiên làm việc mới với token.
     */
    public void register(String token, UserSession session) {
        Objects.requireNonNull(token, "Token cannot be null");
        Objects.requireNonNull(session, "UserSession cannot be null");
        tokenSessions.put(token, session);
    }

    /**
     * Lấy thông tin phiên làm việc theo token.
     */
    public Optional<UserSession> get(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(tokenSessions.get(token));
    }

    /**
     * Kiểm tra token có hợp lệ và còn phiên hoạt động hay không.
     */
    public boolean isValid(String token) {
        return token != null && tokenSessions.containsKey(token);
    }

    /**
     * Hủy phiên làm việc theo token (dùng khi LOGOUT hoặc ngắt kết nối).
     */
    public Optional<UserSession> remove(String token) {
        if (token == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(tokenSessions.remove(token));
    }

    /**
     * Hủy toàn bộ phiên làm việc của một userId cụ thể.
     */
    public void removeByUserId(long userId) {
        tokenSessions.entrySet().removeIf(entry -> entry.getValue().userId() == userId);
    }

    /**
     * Tìm token đầu tiên liên kết với userId.
     */
    public Optional<String> findTokenByUserId(long userId) {
        return tokenSessions.entrySet().stream()
            .filter(entry -> entry.getValue().userId() == userId)
            .map(Map.Entry::getKey)
            .findFirst();
    }

    /**
     * Đếm số lượng phiên đang hoạt động.
     */
    public int size() {
        return tokenSessions.size();
    }

    /**
     * Xóa toàn bộ phiên làm việc (khi tắt server).
     */
    public void clear() {
        tokenSessions.clear();
    }
}
