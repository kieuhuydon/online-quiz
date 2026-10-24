package quiz.common.dto;

import quiz.common.enums.Difficulty;
import java.io.Serializable;

/**
 * DTO để cập nhật câu hỏi hiện có (dành cho Giáo viên / Admin).
 * Chỉ cho phép sửa khi câu hỏi chưa bị dùng trong quiz nào (active=true).
 */
public record UpdateQuestionDto(
    long id,
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
