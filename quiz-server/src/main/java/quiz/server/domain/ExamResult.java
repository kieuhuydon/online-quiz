package quiz.server.domain;

import quiz.common.enums.SubmitReason;
import java.time.Instant;

public record ExamResult(long attemptId, long quizId, long studentId, String studentName, double score,
                         int correctCount, int totalQuestions, boolean passed, SubmitReason submitReason,
                         Instant submittedAt) { }
