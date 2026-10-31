package quiz.server.domain;

import java.time.Instant;

public record AnswerRecord(long attemptId, long questionId, String answer, Instant savedAt) {
    public AnswerRecord(long attemptId, long questionId, char option, Instant savedAt) {
        this(attemptId, questionId, String.valueOf(option), savedAt);
    }
}
