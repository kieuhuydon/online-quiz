package quiz.common.dto;

import java.io.Serializable;

/**
 * DTO yêu cầu bắt đầu làm bài kiểm tra kèm mã PIN.
 */
public record StartAttemptRequestDto(
    long quizId,
    String pin,
    String ipAddress
) implements Serializable {
    public StartAttemptRequestDto(long quizId, String pin) { this(quizId, pin, null); }
}
