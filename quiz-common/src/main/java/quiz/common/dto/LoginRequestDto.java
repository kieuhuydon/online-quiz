package quiz.common.dto;

import java.io.Serializable;

/**
 * DTO yêu cầu đăng nhập từ Client.
 */
public record LoginRequestDto(
    String username,
    String password
) implements Serializable {
}
