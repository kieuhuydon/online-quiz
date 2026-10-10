package quiz.server.service;

import quiz.common.protocol.ErrorCode;
import quiz.server.domain.User;
import quiz.server.exception.QuizException;
import quiz.server.exception.UnauthorizedException;
import quiz.server.network.UserSession;
import quiz.server.repository.UserRepository;
import quiz.server.security.PasswordHasher;

import java.util.Objects;

/** Authentication business logic. Knows neither protocol envelopes nor sockets. */
public final class AuthService {
    private final UserRepository users;
    private final SessionCoordinator sessions;

    public AuthService(UserRepository users, SessionCoordinator sessions) {
        this.users = Objects.requireNonNull(users, "UserRepository cannot be null");
        this.sessions = Objects.requireNonNull(sessions, "SessionCoordinator cannot be null");
    }

    /** Verifies credentials before publishing a single current session for the user. */
    public AuthResult login(String username, String password) {
        String normalized = ServiceValidation.requiredText(username, "Username", 50);
        ServiceValidation.password(password, false);
        User verified = users.findByUsername(normalized).orElseThrow(this::invalidCredentials);
        if (!PasswordHasher.checkPassword(password, verified.passwordHash())) {
            throw invalidCredentials();
        }
        // BCrypt is outside the lock; re-read the account before publishing the token.
        return sessions.withUserLock(verified.id(), () -> {
            User current = users.findById(verified.id()).orElseThrow(this::invalidCredentials);
            if (!current.passwordHash().equals(verified.passwordHash())) {
                throw invalidCredentials();
            }
            if (!current.active()) {
                throw new QuizException(ErrorCode.ACCOUNT_DISABLED, "Account is disabled");
            }
            String token = sessions.openSession(new UserSession(current.id(), current.role(), current.username()));
            return new AuthResult(token, current);
        });
    }

    /** Idempotent internal logout; the network guard later rejects invalid client tokens. */
    public void logout(String token) {
        sessions.logout(token);
    }

    /** Returns current server-side account identity, rejecting revoked tokens and inactive accounts. */
    public UserSession authenticate(String token) {
        return sessions.withCurrentSession(token, session -> {
            User current = users.findById(session.userId()).orElse(null);
            if (current == null || !current.active()) {
                sessions.revokeUser(session.userId());
                throw new UnauthorizedException("Session is not valid");
            }
            return new UserSession(current.id(), current.role(), current.username());
        });
    }

    private QuizException invalidCredentials() {
        return new QuizException(ErrorCode.INVALID_CREDENTIALS, "Username or password is incorrect");
    }
}
