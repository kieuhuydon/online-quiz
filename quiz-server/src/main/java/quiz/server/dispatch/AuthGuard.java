package quiz.server.dispatch;

import quiz.common.enums.Role;
import quiz.common.protocol.MessageType;
import quiz.common.protocol.Request;
import quiz.server.exception.ForbiddenException;
import quiz.server.exception.UnauthorizedException;
import quiz.server.network.UserSession;
import quiz.server.service.AuthService;

import java.util.Objects;
import java.util.Set;

/** Authentication and command-role checks based on current server account data. */
public final class AuthGuard {
    private final AuthService auth;

    public AuthGuard(AuthService auth) {
        this.auth = Objects.requireNonNull(auth, "AuthService cannot be null");
    }

    /** Returns the authenticated identity, or null for public LOGIN only. */
    public UserSession check(Request request, CommandHandler handler) {
        Set<Role> roles = handler.allowedRoles(request.type());
        validatePolicy(request.type(), roles);
        if (request.type() == MessageType.LOGIN) {
            return null;
        }
        if (request.token() == null || request.token().isBlank()) {
            throw new UnauthorizedException("Session is not valid");
        }
        UserSession session = auth.authenticate(request.token());
        if (!roles.contains(session.role())) {
            throw new ForbiddenException("Role is not allowed for this command");
        }
        return session;
    }

    static void validatePolicy(MessageType type, Set<Role> roles) {
        if (roles == null || roles.stream().anyMatch(Objects::isNull)
            || (type == MessageType.LOGIN) != roles.isEmpty()) {
            throw new IllegalArgumentException("Only LOGIN may have an empty role policy");
        }
    }
}
