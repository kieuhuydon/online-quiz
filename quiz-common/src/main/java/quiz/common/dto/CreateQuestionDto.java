package quiz.common.dto;

import quiz.common.enums.Difficulty;
import quiz.common.enums.QuestionType;
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
    Difficulty difficulty,
    QuestionType questionType,
    String explanation
) implements Serializable {
    public CreateQuestionDto {
        if (questionType == null) {
            questionType = QuestionType.MULTIPLE_CHOICE;
        }
    }

    public CreateQuestionDto(String content, String optionA, String optionB,
                             String optionC, String optionD, char correctOption,
                             String topic, Difficulty difficulty, QuestionType questionType) {
        this(content, optionA, optionB, optionC, optionD, correctOption,
             topic, difficulty, questionType, null);
    }

    public CreateQuestionDto(String content, String optionA, String optionB,
                             String optionC, String optionD, char correctOption,
                             String topic, Difficulty difficulty) {
        this(content, optionA, optionB, optionC, optionD, correctOption,
             topic, difficulty, QuestionType.MULTIPLE_CHOICE, null);
    }
}
