package quiz.server.domain.anticheat;

import quiz.common.enums.AntiCheatEventType;

import java.time.Instant;
import java.util.Objects;

/**
 * Bản ghi sự kiện vi phạm chống gian lận trong cơ sở dữ liệu (FR-T09).
 */
public record AntiCheatRecord(
    long id,
    long attemptId,
    AntiCheatEventType eventType,
    String detail,
    Instant clientTime,
    Instant serverTime,
    int violationCountAfter,
    String actionTaken
) {
    public AntiCheatRecord {
        Objects.requireNonNull(eventType, "eventType cannot be null");
        if (violationCountAfter < 0) {
            throw new IllegalArgumentException("violationCountAfter must be non-negative");
        }
    }

    public static AntiCheatRecord createNew(
        long attemptId,
        AntiCheatEventType eventType,
        String detail,
        Instant clientTime,
        int violationCountAfter,
        String actionTaken
    ) {
        return new AntiCheatRecord(
            0L,
            attemptId,
            eventType,
            detail,
            clientTime,
            Instant.now(),
            violationCountAfter,
            actionTaken
        );
    }
}

