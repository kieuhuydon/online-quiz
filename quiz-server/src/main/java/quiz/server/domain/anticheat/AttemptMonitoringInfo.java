package quiz.server.domain.anticheat;

import quiz.common.enums.AntiCheatPolicyType;

/**
 * Thông tin Attempt và cấu hình chống gian lận của Quiz phục vụ giám sát và kiểm tra vi phạm.
 */
public record AttemptMonitoringInfo(
    long attemptId,
    long quizId,
    long studentId,
    String status,
    AntiCheatPolicyType policyType,
    int maxViolations
) {
}

