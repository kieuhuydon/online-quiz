package quiz.server.exception;

import quiz.common.protocol.ErrorCode;

/**
 * Ngoại lệ khi không tìm thấy tài nguyên yêu cầu (mã lỗi NOT_FOUND).
 */
public class NotFoundException extends QuizException {
    public NotFoundException(String message) {
        super(ErrorCode.NOT_FOUND, message);
    }

    public NotFoundException(String message, Throwable cause) {
        super(ErrorCode.NOT_FOUND, message, cause);
    }
}
