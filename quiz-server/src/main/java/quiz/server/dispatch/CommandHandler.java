package quiz.server.dispatch;

import quiz.common.enums.Role;
import quiz.common.protocol.MessageType;
import quiz.common.protocol.Request;
import quiz.common.protocol.Response;
import quiz.server.network.UserSession;

import java.util.Set;

/** Routes supported commands to application services without accessing sockets or SQL. */
public interface CommandHandler {
    Set<MessageType> supportedTypes();

    /** Empty only for LOGIN; policies must be non-null and contain no null roles. */
    Set<Role> allowedRoles(MessageType type);

    /** Session is null only for public LOGIN. Business errors are thrown as QuizException. */
    Response handle(Request request, UserSession session);
}
