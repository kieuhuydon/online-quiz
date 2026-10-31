package quiz.common.dto;

import quiz.common.enums.PenaltyType;
import java.io.Serializable;
import java.time.Instant;

public record MonitoringPenalty(long teacherId, PenaltyType type, double amount,
                                String reason, Instant appliedAt) implements Serializable { }
