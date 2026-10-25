package quiz.common.dto;

import quiz.common.enums.QuizStatus;
import java.io.Serializable;
import java.time.Instant;

/**
 * DTO thông tin tóm tắt đề kiểm tra dành cho học viên (không chứa đáp án).
 */
public record QuizInfoDto(
    long id,
    String title,
    String description,
    String category,
    int durationMinutes,
    double passScore,
    int questionCount,
    QuizStatus status,
    String pin,
    boolean pinRequired,
    Instant openedAt,
    Instant closedAt
) implements Serializable {
}
