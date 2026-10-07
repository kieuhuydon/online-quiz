package quiz.common.dto;

import quiz.common.enums.Difficulty;
import java.io.Serializable;
import java.time.Instant;

/**
 * DTO câu hỏi trắc nghiệm đầy đủ (dành cho Giáo viên / Admin, có correctOption).
 */
public record QuestionDto(
    long id,
    String content,
    String optionA,
    String optionB,
    String optionC,
    String optionD,
    char correctOption,
    String topic,
    Difficulty difficulty,
    boolean active,
    long createdBy,
    Instant createdAt
) implements Serializable {
}
