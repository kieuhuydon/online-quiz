package quiz.common.dto;

import java.io.Serializable;

/**
 * DTO yêu cầu thêm học viên vào lớp.
 */
public record AddClassMemberDto(
    long classId,
    long studentId
) implements Serializable {
}
