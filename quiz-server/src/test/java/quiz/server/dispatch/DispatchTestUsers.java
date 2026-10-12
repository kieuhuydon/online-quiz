package quiz.server.dispatch;

import quiz.common.enums.Role;
import quiz.server.domain.User;

import java.time.Instant;

public final class DispatchTestUsers {
    public static final String HASH = "private-stored-password-hash";

    private DispatchTestUsers() {
    }

    public static User user(long id, Role role) {
        return User.restore(id, "User" + id, HASH, "Nguyễn Văn Việt", null, role,
            true, id == 7 ? null : 7L, Instant.parse("2026-10-10T00:00:00Z"));
    }
}
