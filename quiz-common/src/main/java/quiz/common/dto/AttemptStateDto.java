package quiz.common.dto;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * DTO trạng thái phiên làm bài hiện tại (dùng khi bắt đầu làm bài hoặc reconnect).
 */
public record AttemptStateDto(
    long attemptId,
    long quizId,
    String quizTitle,
    long remainingSeconds,
    List<StudentQuestionDto> questions,
    Map<Long, ?> savedAnswers
) implements Serializable {
}
