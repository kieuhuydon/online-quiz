package quiz.server.exception;

import quiz.common.protocol.ErrorCode;

/**
 * Ngoại lệ khi dữ liệu đầu vào không hợp lệ (mã lỗi VALIDATION_ERROR).
 */
public class ValidationException extends QuizException {
    public ValidationException(String message) {
        super(ErrorCode.VALIDATION_ERROR, message);
    }

    public ValidationException(String message, Throwable cause) {
        super(ErrorCode.VALIDATION_ERROR, message, cause);
    }
}
