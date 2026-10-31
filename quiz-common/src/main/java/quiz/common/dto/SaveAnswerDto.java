package quiz.common.dto;

import java.io.Serializable;

/**
 * DTO lưu câu trả lời cho một câu hỏi trong khi đang thi.
 */
public record SaveAnswerDto(
    long attemptId,
    long questionId,
    String answer
) implements Serializable {
    public SaveAnswerDto(long attemptId, long questionId, Character selectedOption) {
        this(attemptId, questionId, selectedOption == null ? null : selectedOption.toString());
    }
    public Character selectedOption() {
        return answer != null && answer.length() == 1 ? answer.charAt(0) : null;
    }
}
