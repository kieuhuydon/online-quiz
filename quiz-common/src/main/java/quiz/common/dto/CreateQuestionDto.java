package quiz.common.dto;

import quiz.common.enums.Difficulty;
import java.io.Serializable;

/**
 * DTO để tạo câu hỏi mới (dành cho Giáo viên / Admin).
 * Không có id hay createdAt – do server sinh và gán.
 */
public record CreateQuestionDto(
    String content,
    String optionA,
    String optionB,
    String optionC,
    String optionD,
    char correctOption,
    String topic,
    Difficulty difficulty
) implements Serializable {
}
