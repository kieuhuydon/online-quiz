package quiz.common.dto;

import java.io.Serializable;
import java.time.Instant;

/**
 * DTO thông tin lớp học.
 */
public record ClassDto(
    long id,
    String name,
    String description,
    long teacherId,
    String teacherName,
    Instant createdAt
) implements Serializable {
}
