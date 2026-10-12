package quiz.server.exception;

import quiz.common.protocol.ErrorCode;

/**
 * Ngoại lệ khi người dùng không đủ quyền thực hiện hành động (mã lỗi FORBIDDEN).
 */
public class ForbiddenException extends QuizException {
    public ForbiddenException(String message) {
        super(ErrorCode.FORBIDDEN, message);
    }

    public ForbiddenException(String message, Throwable cause) {
        super(ErrorCode.FORBIDDEN, message, cause);
    }
}
