package quiz.client.api;

import quiz.common.protocol.ErrorCode;
import java.util.Objects;

/** Business error mapped by code, never by untrusted server message text. */
public final class ApiException extends RuntimeException {
    private final ErrorCode errorCode;

    public ApiException(ErrorCode errorCode) {
        super("Request failed: " + Objects.requireNonNull(errorCode).name());
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() { return errorCode; }
}
