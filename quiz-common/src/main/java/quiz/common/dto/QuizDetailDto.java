package quiz.common.dto;

import java.io.Serializable;
import java.util.List;

/**
 * DTO chi tiết đề kiểm tra dành cho giáo viên (gồm thông tin quiz, danh sách câu hỏi và lớp đã giao).
 */
public record QuizDetailDto(
    QuizDto quiz,
    List<QuestionDto> questions,
    List<Long> assignedClassIds
) implements Serializable {
}
