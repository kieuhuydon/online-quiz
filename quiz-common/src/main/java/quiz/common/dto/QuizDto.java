package quiz.common.dto;

import quiz.common.enums.AntiCheatPolicyType;
import quiz.common.enums.QuizStatus;
import java.io.Serializable;
import java.time.Instant;

/**
 * DTO thông tin bài kiểm tra (Quiz).
 */
public record QuizDto(
    long id,
    String title,
    String description,
    String category,
    int durationMinutes,
    double passScore,
    QuizStatus status,
    String pin,
    AntiCheatPolicyType anticheatPolicy,
    int maxViolations,
    long createdBy,
    Instant createdAt,
    Instant openedAt,
    Instant closedAt
) implements Serializable {
}
