package quiz.common.dto;

import java.io.Serializable;
import java.util.List;

/**
 * DTO gán danh sách câu hỏi vào đề kiểm tra theo thứ tự vị trí.
 */
public record QuizQuestionsDto(
    long quizId,
    List<Long> questionIds
) implements Serializable {
}
