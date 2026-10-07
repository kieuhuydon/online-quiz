package quiz.common.dto;

import java.io.Serializable;
import java.time.Instant;

/**
 * DTO thành viên trong lớp học.
 */
public record ClassMemberDto(
    long classId,
    long studentId,
    String studentUsername,
    String studentFullName,
    Instant enrolledAt
) implements Serializable {
}
