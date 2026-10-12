package quiz.server.dispatch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quiz.common.protocol.ErrorCode;
import quiz.common.protocol.MessageCodec;
import quiz.common.protocol.MessageType;
import quiz.common.protocol.Request;
import quiz.common.protocol.Response;
import quiz.server.exception.QuizException;
import quiz.server.network.UserSession;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** Immutable command routing, fail-closed authorization and safe response/error framing. */
public final class RequestDispatcher {
    private static final Logger log = LoggerFactory.getLogger(RequestDispatcher.class);
    private final AuthGuard guard;
    private final Map<MessageType, CommandHandler> handlers;

    public RequestDispatcher(AuthGuard guard, Collection<? extends CommandHandler> commandHandlers) {
        this.guard = Objects.requireNonNull(guard, "AuthGuard cannot be null");
        Objects.requireNonNull(commandHandlers, "Handlers cannot be null");
        Map<MessageType, CommandHandler> registered = new EnumMap<>(MessageType.class);
        for (CommandHandler handler : commandHandlers) {
            register(registered, Objects.requireNonNull(handler, "Handler cannot be null"));
        }
        handlers = Map.copyOf(registered);
    }

    /** Dispatches one decoded request; malformed frames are handled separately by the network layer. */
    public Response dispatch(Request request) {
        long requestId = request == null ? 0 : request.requestId();
        UserSession session = null;
        try {
            validateRequest(request);
            CommandHandler handler = handlers.get(request.type());
            if (handler == null) {
                throw new QuizException(ErrorCode.INVALID_REQUEST, "Command is not supported");
            }
            session = guard.check(request, handler);
            return validateResponse(requestId, handler.handle(request, session));
        } catch (QuizException error) {
            if (error.getErrorCode() == ErrorCode.DATABASE_ERROR || error.getErrorCode() == ErrorCode.INTERNAL_ERROR) {
                logFailure(request, session, error);
                return safeError(requestId, error.getErrorCode());
            }
            log.debug("Request rejected requestId={} type={} userId={} errorCode={}", requestId,
                request == null ? null : request.type(), session == null ? null : session.userId(), error.getErrorCode());
            return businessError(request, session, error);
        } catch (Exception error) {
            logFailure(request, session, error);
            return safeError(requestId, hasSqlCause(error) ? ErrorCode.DATABASE_ERROR : ErrorCode.INTERNAL_ERROR);
        }
    }

    private void register(Map<MessageType, CommandHandler> registered, CommandHandler handler) {
        if (handler.supportedTypes() == null || handler.supportedTypes().isEmpty()) {
            throw new IllegalArgumentException("Handler must support at least one command");
        }
        for (MessageType type : handler.supportedTypes()) {
            if (type == null || type == MessageType.PUSH_ANTICHEAT_WARNING || type == MessageType.PUSH_FORCE_SUBMITTED) {
                throw new IllegalArgumentException("Client handler cannot support a null or PUSH type");
            }
            AuthGuard.validatePolicy(type, handler.allowedRoles(type));
            if (registered.putIfAbsent(type, handler) != null) {
                throw new IllegalArgumentException("Duplicate command handler for " + type);
            }
        }
    }

    private void validateRequest(Request request) {
        if (request == null || request.requestId() <= 0 || request.type() == null) {
            throw new QuizException(ErrorCode.INVALID_REQUEST, "Request envelope is invalid");
        }
        if (request.type() == MessageType.LOGIN && request.token() != null) {
            throw new QuizException(ErrorCode.INVALID_REQUEST, "LOGIN must not carry a token");
        }
    }

    private Response validateResponse(long requestId, Response response) throws IOException, ClassNotFoundException {
        if (response == null || response.requestId() != requestId || response.pushType() != null
            || response.success() == (response.errorCode() != null)) {
            throw new IllegalStateException("Handler returned an invalid response envelope");
        }
        if (!response.success() && (response.errorCode() == ErrorCode.DATABASE_ERROR
            || response.errorCode() == ErrorCode.INTERNAL_ERROR)) {
            response = safeError(requestId, response.errorCode());
        }
        MessageCodec.deserialize(MessageCodec.serialize(response));
        return response;
    }

    private Response safeError(long requestId, ErrorCode code) {
        String message = code == ErrorCode.DATABASE_ERROR ? "Database operation failed" : "Internal server error";
        return Response.error(requestId, code, message);
    }

    private Response businessError(Request request, UserSession session, QuizException error) {
        long requestId = request == null ? 0 : request.requestId();
        try {
            return validateResponse(requestId, Response.error(requestId, error.getErrorCode(), error.getMessage()));
        } catch (Exception encodingFailure) {
            logFailure(request, session, encodingFailure);
            return safeError(requestId, ErrorCode.INTERNAL_ERROR);
        }
    }

    private boolean hasSqlCause(Throwable error) {
        for (int depth = 0; error != null && depth < 8; depth++, error = error.getCause()) {
            if (error instanceof SQLException) {
                return true;
            }
        }
        return false;
    }

    private void logFailure(Request request, UserSession session, Throwable error) {
        // Never pass Throwable to the logger: its message/cause may contain a password or SQL values.
        for (int depth = 0; error != null && depth < 4; depth++, error = error.getCause()) {
            log.error("Request failed requestId={} type={} userId={} exception={} stack={}",
                request == null ? 0 : request.requestId(), request == null ? null : request.type(),
                session == null ? null : session.userId(), error.getClass().getName(),
                Arrays.stream(error.getStackTrace()).limit(12).toList());
        }
    }
}
