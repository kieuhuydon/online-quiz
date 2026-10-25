package quiz.server.repository;

import quiz.common.dto.AntiCheatEventDto;
import quiz.common.enums.AntiCheatEventType;

import java.sql.Connection;
import java.time.Instant;
import java.util.List;

/**
 * Repository interface quản lý nhật ký vi phạm chống gian lận (anticheat_events).
 */
public interface AntiCheatRepository {
    void recordEvent(Connection connection, long attemptId, AntiCheatEventType eventType,
                     String detail, Instant clientTime, Instant serverTime,
                     int violationCountAfter, String actionTaken);

    int countViolations(Connection connection, long attemptId);

    List<AntiCheatEventDto> findEventsByAttemptId(long attemptId);
}
