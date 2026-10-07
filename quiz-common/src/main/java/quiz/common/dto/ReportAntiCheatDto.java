package quiz.common.dto;

import quiz.common.enums.AntiCheatEventType;
import java.io.Serializable;
import java.time.Instant;

/**
 * DTO báo cáo vi phạm gian lận từ Client gửi lên Server.
 */
public record ReportAntiCheatDto(
    long attemptId,
    AntiCheatEventType eventType,
    String detail,
    Instant clientTime
) implements Serializable {
}
