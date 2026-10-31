package quiz.common.dto;

import quiz.common.enums.MonitoringStatus;
import java.io.Serializable;
import java.time.Instant;

/** Snapshot from the server; remainingSeconds is calculated using server time. */
public record ExamSession(long sessionId, long examId, long studentId, String studentName,
    String ipAddress, MonitoringStatus status, int violationCount, String violationDetails,
    double deductedScore, boolean forceStopped, Instant lastUpdated,
    long remainingSeconds) implements Serializable { }
