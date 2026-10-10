package quiz.server.dispatch.handlers;

import quiz.common.protocol.ErrorCode;
import quiz.common.protocol.Request;
import quiz.server.exception.QuizException;
import quiz.server.exception.UnauthorizedException;
import quiz.server.network.UserSession;

final class HandlerPayload {
    private HandlerPayload() {
    }

    static <T> T required(Request request, Class<T> type) {
        if (!type.isInstance(request.payload())) {
            throw new QuizException(ErrorCode.INVALID_REQUEST, "Payload type is invalid");
        }
        return type.cast(request.payload());
    }

    static void absent(Request request) {
        if (request.payload() != null) {
            throw new QuizException(ErrorCode.INVALID_REQUEST, "This command requires a null payload");
        }
    }

    static UserSession authenticated(UserSession session) {
        if (session == null) {
            throw new UnauthorizedException("Session is not valid");
        }
        return session;
    }
}
