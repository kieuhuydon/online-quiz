package quiz.common.dto;

import java.io.Serializable;
import java.util.List;

/**
 * DTO để gán / lấy danh sách câu hỏi của một bài kiểm tra (Quiz).
 * Dùng cho MessageType.SET_QUIZ_QUESTIONS (chọn câu hỏi đưa vào bài test)
 * và GET_QUIZ (xem câu hỏi đã gán).
 */
public record QuizQuestionsDto(
    long quizId,
    List<Long> questionIds
) implements Serializable {
}
