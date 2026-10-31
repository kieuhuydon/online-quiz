package quiz.common.dto;

import quiz.common.enums.PenaltyType;
import java.io.Serializable;

/** operationId is a UUID string, retained when retrying an uncertain request. */
public record ApplyPenaltyDto(String operationId, long sessionId, PenaltyType type,
                              double amount, String reason) implements Serializable { }
