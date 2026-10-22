package quiz.common.dto;

import quiz.common.enums.AntiCheatPolicyType;
import java.io.Serializable;

/**
 * DTO yêu cầu tạo đề kiểm tra (Quiz) mới (trạng thái ban đầu luôn là DRAFT).
 */
public record CreateQuizDto(
    String title,
    String description,
    String category,
    int durationMinutes,
    double passScore,
    AntiCheatPolicyType anticheatPolicy,
    int maxViolations
) implements Serializable {
}
