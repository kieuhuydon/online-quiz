package quiz.common.dto;

import java.io.Serializable;

/**
 * DTO yêu cầu nộp bài kiểm tra.
 */
public record SubmitAttemptDto(
    long attemptId
) implements Serializable {
}
