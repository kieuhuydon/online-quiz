package quiz.common.dto;

import quiz.common.enums.AntiCheatEventType;
import java.io.Serializable;
import java.time.Instant;

/**
 * DTO đại diện cho một bản ghi vi phạm chống gian lận trong Attempt.
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
}
