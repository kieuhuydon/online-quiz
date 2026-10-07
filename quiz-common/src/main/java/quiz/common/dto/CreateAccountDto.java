package quiz.common.dto;

import quiz.common.enums.Role;
import java.io.Serializable;

/**
 * DTO yêu cầu tạo tài khoản mới (dành cho ADMIN).
 */
public record CreateAccountDto(
    String username,
    String password,
    String fullName,
    String email,
    Role role
) implements Serializable {
}
