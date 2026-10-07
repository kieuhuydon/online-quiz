package quiz.common.dto;

import quiz.common.enums.Difficulty;
import java.io.Serializable;

/**
 * DTO câu hỏi gửi xuống cho Học viên làm bài.
 * TUYỆT ĐỐI KHÔNG chứa trường đáp án đúng (correctOption) để đảm bảo bảo mật.
 */
public record StudentQuestionDto(
    long id,
    int position,
    String content,
    String optionA,
    String optionB,
    String optionC,
    String optionD,
    String topic,
    Difficulty difficulty
) implements Serializable {
}
