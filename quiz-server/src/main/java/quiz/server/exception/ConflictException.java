package quiz.server.exception;

import quiz.common.protocol.ErrorCode;

/**
 * Data conflict, with dedicated account codes for duplicate username and email.
 */
public class ConflictException extends QuizException {
    public ConflictException(String message) {
        super(ErrorCode.CONFLICT, message);
    }

    public ConflictException(String message, Throwable cause) {
        super(ErrorCode.CONFLICT, message, cause);
    }

    public ConflictException(ErrorCode code, String message) {
        this(code, message, null);
    }

    public ConflictException(ErrorCode code, String message, Throwable cause) {
        super(code, message, cause);
        if (code != ErrorCode.CONFLICT && code != ErrorCode.USERNAME_ALREADY_EXISTS
            && code != ErrorCode.EMAIL_ALREADY_EXISTS) {
            throw new IllegalArgumentException("Expected an account conflict code");
        }
    }
}
