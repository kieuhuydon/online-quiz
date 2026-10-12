package quiz.common.dto;

import java.io.Serializable;

/**
 * DTO yêu cầu giao bài kiểm tra cho một lớp.
 */
public record AssignQuizDto(
    long quizId,
    long classId
) implements Serializable {
}
