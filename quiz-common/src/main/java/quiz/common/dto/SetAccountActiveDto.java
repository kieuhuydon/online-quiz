package quiz.common.dto;

import java.io.Serializable;

/**
 * DTO yêu cầu bật hoặc khóa tài khoản người dùng (dành cho ADMIN).
 */
public record SetAccountActiveDto(
    long userId,
    boolean active
) implements Serializable {
}
