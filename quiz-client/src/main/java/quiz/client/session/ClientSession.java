package quiz.client.session;

import quiz.common.dto.LoginResultDto;
import quiz.common.enums.Role;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongPredicate;

/** Memory-only snapshot. A disconnected generation is invisible even before its event is delivered. */
public final class ClientSession {
    private final AtomicReference<Snapshot> value = new AtomicReference<>();
    private final LongPredicate connected;

    public ClientSession(LongPredicate connected) { this.connected = Objects.requireNonNull(connected); }

    public Optional<Snapshot> current() {
        Snapshot snapshot = value.get();
        return snapshot != null && connected.test(snapshot.generation()) ? Optional.of(snapshot) : Optional.empty();
    }

    /** Called by QuizApi under the connection's atomic generation guard. */
    public void establish(long generation, LoginResultDto login) {
        value.set(new Snapshot(generation, login.token(), login.userId(), login.username(), login.fullName(), login.role()));
    }

    /** Clears only the old generation; delayed disconnects cannot clear a new login. */
    public boolean clear(long generation) {
        Snapshot snapshot = value.get();
        return snapshot != null && snapshot.generation() == generation && value.compareAndSet(snapshot, null);
    }

    /** For disposal of the owning API only. */
    public void clear() { value.set(null); }

    public record Snapshot(long generation, String token, long userId, String username, String fullName, Role role) {
        public Snapshot {
            if (generation <= 0 || userId <= 0 || token == null || token.isBlank()
                || username == null || username.isBlank() || fullName == null || fullName.isBlank() || role == null) {
                throw new IllegalArgumentException("Invalid session snapshot");
            }
        }

        @Override public String toString() {
            return "ClientSession[generation=" + generation + ", userId=" + userId + ", role=" + role + "]";
        }
    }
}
