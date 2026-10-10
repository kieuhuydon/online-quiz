package quiz.server.service;

import quiz.server.domain.User;

/** Internal login outcome; handlers map it to a DTO and must not log its token or password hash. */
public record AuthResult(String token, User user) {
    public AuthResult {
        if (token == null || token.isBlank() || user == null || user.id() <= 0 || !user.active()) {
            throw new IllegalArgumentException("Login result requires a token and a persisted active user");
        }
    }

    @Override
    public String toString() {
        return "AuthResult[userId=" + user.id() + ", role=" + user.role() + "]";
    }
}
