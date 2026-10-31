package quiz.common.dto;

import quiz.common.enums.SubmitReason;
import java.io.Serializable;
import java.time.Instant;

/**
 * DTO kết quả bài thi trả về cho Học viên hoặc Giáo viên.
 */
public record ResultDto(
    long attemptId,
    long quizId,
    String quizTitle,
    String studentName,
    double score,
    int correctCount,
    int totalQuestions,
    boolean passed,
    SubmitReason submitReason,
    Instant submittedAt,
    java.util.List<QuestionReviewDto> review
) implements Serializable {
    public ResultDto(long attemptId, long quizId, String quizTitle, String studentName, double score,
                     int correctCount, int totalQuestions, boolean passed, SubmitReason submitReason, Instant submittedAt) {
        this(attemptId, quizId, quizTitle, studentName, score, correctCount, totalQuestions, passed,
            submitReason, submittedAt, java.util.List.of());
    }
}
