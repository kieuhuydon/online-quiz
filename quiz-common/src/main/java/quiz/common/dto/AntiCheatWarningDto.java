package quiz.common.dto;

import java.io.Serializable;

/**
 * DTO cảnh báo vi phạm từ Server PUSH xuống Client.
 */
public record AntiCheatWarningDto(
    int violationCount,
    int maxViolations,
    String message
) implements Serializable {
}
