package quiz.server.exception;

import quiz.common.protocol.ErrorCode;

/**
 * Ngoại lệ khi người dùng chưa đăng nhập hoặc token không hợp lệ (mã lỗi UNAUTHENTICATED).
 */
public class UnauthorizedException extends QuizException {
    public UnauthorizedException(String message) {
        super(ErrorCode.UNAUTHENTICATED, message);
    }

    public UnauthorizedException(String message, Throwable cause) {
        super(ErrorCode.UNAUTHENTICATED, message, cause);
    }
}
