package quiz.server.domain;

import java.time.Instant;

public record AnswerRecord(long attemptId, long questionId, Character selectedOption, Instant savedAt) { }
