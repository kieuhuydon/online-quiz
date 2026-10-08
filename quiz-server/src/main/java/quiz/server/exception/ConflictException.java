package quiz.server.exception;

import quiz.common.protocol.ErrorCode;

/**
 * Ngoại lệ khi có xung đột dữ liệu (ví dụ: trùng username/email, nộp bài đã nộp) (mã lỗi CONFLICT).
 */
public class ConflictException extends QuizException {
    public ConflictException(String message) {
        super(ErrorCode.CONFLICT, message);
    }

    public ConflictException(String message, Throwable cause) {
        super(ErrorCode.CONFLICT, message, cause);
    }
}
