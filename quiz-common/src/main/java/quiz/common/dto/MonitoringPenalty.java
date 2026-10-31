package quiz.common.dto;

import quiz.common.enums.PenaltyType;
import java.io.Serializable;
import java.time.Instant;

/** Audit types remain strings so retired actions in existing databases can still be read. */
public record MonitoringPenalty(long teacherId, String type, double amount,
                                String reason, Instant appliedAt) implements Serializable {
    public MonitoringPenalty(long teacherId, PenaltyType type, double amount, String reason, Instant appliedAt) {
        this(teacherId, type.name(), amount, reason, appliedAt);
    }
}
