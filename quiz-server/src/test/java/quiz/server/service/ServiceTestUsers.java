package quiz.server.service;

import quiz.common.enums.Role;
import quiz.server.domain.User;

import java.time.Instant;

final class ServiceTestUsers {
    static final String PASSWORD = "password123";
    static final String HASH = "$2a$10$RMj7Oqje6a.XQorzyUTXuuXnV/EKY/.yYf4YaxIMOz67D/tFVZ.0y";

    private ServiceTestUsers() {
    }

    static User user(long id, Role role, boolean active) {
        return user(id, role, active, HASH);
    }

    static User user(long id, Role role, boolean active, String hash) {
        return User.restore(id, "User" + id, hash, "User " + id, null,
            role, active, null, Instant.parse("2026-10-10T00:00:00Z"));
    }
}
