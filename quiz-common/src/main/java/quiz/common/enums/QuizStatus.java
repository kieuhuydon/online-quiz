package quiz.common.enums;

import java.io.Serializable;

/**
 * Trạng thái của bài kiểm tra (Quiz).
 * Vòng đời một chiều: DRAFT -> OPEN -> CLOSED (không quay lại).
 */
public enum QuizStatus implements Serializable {
    DRAFT,
    OPEN,
    CLOSED
}
