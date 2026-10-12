package quiz.common.enums;

/**
 * Trạng thái của bài kiểm tra (Quiz).
 * Vòng đời một chiều: DRAFT -> OPEN -> CLOSED (không quay lại).
 */
public enum QuizStatus {
    DRAFT,
    OPEN,
    CLOSED
}
