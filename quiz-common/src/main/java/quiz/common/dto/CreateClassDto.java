package quiz.common.dto;

import java.io.Serializable;

/**
 * DTO yêu cầu tạo lớp học mới.
 */
public record CreateClassDto(
    String name,
    String description
) implements Serializable {
}
