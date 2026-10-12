package quiz.common.dto;

import java.io.Serializable;

/**
 * DTO thống kê điểm số bài kiểm tra dành cho Giáo viên.
 */
public record QuizStatisticsDto(
    long quizId,
    String quizTitle,
    int totalAttempts,
    double averageScore,
    double passRate,
    double maxScore,
    double minScore
) implements Serializable {
}
