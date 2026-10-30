package quiz.common.dto;

import quiz.common.enums.Difficulty;
import quiz.common.enums.QuestionType;
import java.io.Serializable;
import java.time.Instant;

/**
 * DTO câu hỏi đầy đủ (dành cho Giáo viên / Admin, có correctOption).
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
    QuestionType questionType,
    boolean active,
    long createdBy,
    Instant createdAt
) implements Serializable {
    public QuestionDto {
        if (questionType == null) {
            questionType = QuestionType.MULTIPLE_CHOICE;
        }
    }

    public QuestionDto(long id, String content, String optionA, String optionB,
                       String optionC, String optionD, char correctOption,
                       String topic, Difficulty difficulty, boolean active,
                       long createdBy, Instant createdAt) {
        this(id, content, optionA, optionB, optionC, optionD, correctOption,
             topic, difficulty, QuestionType.MULTIPLE_CHOICE, active, createdBy, createdAt);
    }
}
