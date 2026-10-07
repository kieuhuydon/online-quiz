package quiz.common.protocol;

import java.io.Serializable;

/**
 * Mã lỗi chuẩn hóa cho toàn bộ ứng dụng.
 */
public enum ErrorCode implements Serializable {
    INVALID_REQUEST,
    VALIDATION_ERROR,
    UNAUTHENTICATED,
    FORBIDDEN,
    NOT_FOUND,
    CONFLICT,
    INVALID_CREDENTIALS,
    ACCOUNT_DISABLED,
    QUIZ_NOT_OPEN,
    QUIZ_CLOSED,
    WRONG_PIN,
    NOT_ASSIGNED,
    ATTEMPT_ALREADY_SUBMITTED,
    ATTEMPT_EXPIRED,
    ATTEMPT_NOT_FOUND,
    QUESTION_IN_USE,
    SERVER_BUSY,
    DATABASE_ERROR,
    INTERNAL_ERROR
}
