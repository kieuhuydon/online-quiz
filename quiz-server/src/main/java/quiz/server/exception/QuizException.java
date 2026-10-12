package quiz.server.exception;

import quiz.common.protocol.ErrorCode;

import java.util.Objects;

/**
 * Base RuntimeException cho toàn bộ ngoại lệ nghiệp vụ của quiz-server.
 * Mỗi ngoại lệ luôn gắn kèm một ErrorCode chuẩn hóa.
 */
public class QuizException extends RuntimeException {
    private final ErrorCode errorCode;

    public QuizException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = Objects.requireNonNull(errorCode, "ErrorCode cannot be null");
    }

    public QuizException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = Objects.requireNonNull(errorCode, "ErrorCode cannot be null");
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
