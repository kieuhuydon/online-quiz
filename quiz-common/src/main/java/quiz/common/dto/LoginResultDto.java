package quiz.common.dto;

import quiz.common.enums.Role;
import java.io.Serializable;

/**
 * DTO kết quả đăng nhập thành công trả về cho Client.
 */
public record LoginResultDto(
    String token,
    long userId,
    String username,
    String fullName,
    Role role
) implements Serializable {
}
