package quiz.common.dto;

import java.io.Serializable;

/** Student heartbeat also refreshes the current client-reported network address. */
public record ExamSessionRequestDto(long sessionId, String ipAddress) implements Serializable { }
