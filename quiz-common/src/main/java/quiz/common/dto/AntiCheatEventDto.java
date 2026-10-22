package quiz.common.dto;

import quiz.common.enums.AntiCheatEventType;

import java.io.Serializable;
import java.time.Instant;

/**
 * DTO đại diện cho một bản ghi sự kiện vi phạm chống gian lận (Anti-Cheat Event).
 * Dùng cho Giáo viên / Quản trị viên xem bằng chứng vi phạm của học viên (FR-T09, BR-13).
 */
public record AntiCheatEventDto(
    long id,
    long attemptId,
    AntiCheatEventType eventType,
    String detail,
    Instant clientTime,
    Instant serverTime,
    int violationCountAfter,
    String actionTaken
) implements Serializable {
    @java.io.Serial
    private static final long serialVersionUID = 1L;
}

