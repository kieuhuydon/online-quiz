package quiz.common.dto;

import quiz.common.enums.Role;
import java.io.Serializable;
import java.time.Instant;

/**
 * DTO hiển thị thông tin tài khoản người dùng (cho ADMIN xem danh sách).
 */
public record AccountDto(
    long id,
    String username,
    String fullName,
    String email,
    Role role,
    boolean active,
    Long createdBy,
    Instant createdAt
) implements Serializable {
}
