package quiz.common.dto;

import java.io.Serializable;

/**
 * DTO lưu câu trả lời cho một câu hỏi trong khi đang thi.
 */
public record SaveAnswerDto(
    long attemptId,
    long questionId,
    Character selectedOption
) implements Serializable {
}
