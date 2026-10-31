package quiz.common.dto;

import quiz.common.enums.Difficulty;
import quiz.common.enums.QuestionType;
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
    Difficulty difficulty,
    QuestionType questionType,
    String answerKey,
    String explanation
) implements Serializable {
    public UpdateQuestionDto {
        if (questionType == null) {
            questionType = QuestionType.MULTIPLE_CHOICE;
        }
    }

    public UpdateQuestionDto(long id, String content, String optionA, String optionB,
                             String optionC, String optionD, char correctOption,
                             String topic, Difficulty difficulty) {
        this(id, content, optionA, optionB, optionC, optionD, correctOption,
             topic, difficulty, QuestionType.MULTIPLE_CHOICE, null, null);
    }
    public UpdateQuestionDto(long id, String content, String optionA, String optionB, String optionC, String optionD,
                             char correctOption, String topic, Difficulty difficulty, QuestionType questionType) {
        this(id, content, optionA, optionB, optionC, optionD, correctOption, topic, difficulty, questionType, null, null);
    }
}
