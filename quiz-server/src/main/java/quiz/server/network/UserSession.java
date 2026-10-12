package quiz.server.network;

import quiz.common.enums.Role;

import java.io.Serializable;
import java.util.Objects;

/**
 * Thông tin phiên làm việc của người dùng sau khi xác thực thành công.
 *
 * @param userId   Mã ID người dùng trong CSDL
 * @param role     Vai trò của người dùng (STUDENT, TEACHER, ADMIN)
 * @param username Tên đăng nhập
 */
public record UserSession(
    long userId,
    Role role,
    String username
) implements Serializable {
    public UserSession {
        Objects.requireNonNull(role, "Role cannot be null");
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("Username cannot be null or blank");
        }
    }
}
