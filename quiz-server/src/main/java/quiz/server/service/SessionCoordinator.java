package quiz.server.service;

import quiz.server.exception.UnauthorizedException;
import quiz.server.network.SessionRegistry;
import quiz.server.network.UserSession;
import quiz.server.security.TokenGenerator;

import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Serializes session changes using a fixed number of locks. Share one coordinator
 * across services. Callbacks must not perform socket I/O or acquire another user's lock.
 */
public final class SessionCoordinator {
    private static final int LOCK_COUNT = 128;
    private final ReentrantLock[] locks = new ReentrantLock[LOCK_COUNT];
    private final SessionRegistry sessions;

    public SessionCoordinator(SessionRegistry sessions) {
        this.sessions = Objects.requireNonNull(sessions, "SessionRegistry cannot be null");
        for (int index = 0; index < locks.length; index++) {
            locks[index] = new ReentrantLock();
        }
    }

    /** Executes an operation under the user lock; reentry for the same user is supported. */
    public <T> T withUserLock(long userId, Supplier<T> operation) {
        if (userId <= 0) {
            throw new IllegalArgumentException("User ID must be positive");
        }
        Objects.requireNonNull(operation, "Operation cannot be null");
        ReentrantLock lock = locks[Long.hashCode(userId) & (LOCK_COUNT - 1)];
        lock.lock();
        try {
            return operation.get();
        } finally {
            lock.unlock();
        }
    }

    /** Replaces all previous tokens for this user. Caller must first check the account under its lock. */
    public String openSession(UserSession session) {
        Objects.requireNonNull(session, "Session cannot be null");
        return withUserLock(session.userId(), () -> {
            String token;
            do {
                token = TokenGenerator.generateToken();
            } while (sessions.isValid(token));
            sessions.removeByUserId(session.userId());
            sessions.register(token, session);
            return token;
        });
    }

    /** Validates the token before and after the callback while holding its user's lock. */
    public <T> T withCurrentSession(String token, Function<UserSession, T> operation) {
        Objects.requireNonNull(operation, "Operation cannot be null");
        UserSession session = sessions.get(token).orElseThrow(this::unauthorized);
        if (session.userId() <= 0) {
            throw unauthorized();
        }
        return withUserLock(session.userId(), () -> {
            requireCurrent(token, session);
            T result = operation.apply(session);
            requireCurrent(token, session);
            return result;
        });
    }

    /** Removes only this token, preserving a newer login. Missing tokens are a no-op. */
    public void logout(String token) {
        sessions.get(token).ifPresent(session -> withUserLock(session.userId(), () -> {
            sessions.remove(token);
            return null;
        }));
    }

    /** Revokes a user's tokens. Call after the account change commits under the same user lock. */
    public void revokeUser(long userId) {
        withUserLock(userId, () -> {
            sessions.removeByUserId(userId);
            return null;
        });
    }

    private void requireCurrent(String token, UserSession expected) {
        if (sessions.get(token).filter(expected::equals).isEmpty()) {
            throw unauthorized();
        }
    }

    private UnauthorizedException unauthorized() {
        return new UnauthorizedException("Session is not valid");
    }
}
