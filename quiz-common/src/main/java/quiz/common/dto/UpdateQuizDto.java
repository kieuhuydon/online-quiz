package quiz.common.dto;

import quiz.common.enums.AntiCheatPolicyType;
import java.io.Serializable;

/**
 * DTO yêu cầu cập nhật đề kiểm tra (chỉ cho phép khi ở trạng thái DRAFT).
 */
public record UpdateQuizDto(
    long id,
    String title,
    String description,
    String category,
    int durationMinutes,
    double passScore,
    AntiCheatPolicyType anticheatPolicy,
    int maxViolations
) implements Serializable {
}
