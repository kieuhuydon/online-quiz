package quiz.client.session;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import quiz.common.dto.LoginResultDto;
import quiz.common.enums.Role;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ClientSession Unit Tests")
class ClientSessionTest {

    @Test
    @DisplayName("Snapshot kiểm tra tính hợp lệ của các tham số và ẩn token trong toString()")
    void snapshotValidationAndSafeToString() {
        ClientSession.Snapshot valid = new ClientSession.Snapshot(1L, "secret-token-123", 10L, "student1", "Student One", Role.STUDENT);
        assertEquals(1L, valid.generation());
        assertEquals(10L, valid.userId());
        assertFalse(valid.toString().contains("secret-token-123"));

        assertThrows(IllegalArgumentException.class,
            () -> new ClientSession.Snapshot(0L, "token", 10L, "u", "Full", Role.STUDENT));
        assertThrows(IllegalArgumentException.class,
            () -> new ClientSession.Snapshot(1L, "   ", 10L, "u", "Full", Role.STUDENT));
        assertThrows(IllegalArgumentException.class,
            () -> new ClientSession.Snapshot(1L, "token", 0L, "u", "Full", Role.STUDENT));
        assertThrows(IllegalArgumentException.class,
            () -> new ClientSession.Snapshot(1L, "token", 10L, "", "Full", Role.STUDENT));
        assertThrows(IllegalArgumentException.class,
            () -> new ClientSession.Snapshot(1L, "token", 10L, "u", " ", Role.STUDENT));
        assertThrows(IllegalArgumentException.class,
            () -> new ClientSession.Snapshot(1L, "token", 10L, "u", "Full", null));
    }

    @Test
    @DisplayName("establish, current và clear theo generation bảo vệ phiên mới")
    void establishCurrentAndGenerationScopedClear() {
        AtomicBoolean connected = new AtomicBoolean(true);
        ClientSession session = new ClientSession(gen -> connected.get() && gen == 2L);

        session.establish(2L, new LoginResultDto("tok-2", 5L, "teacher1", "Teacher One", Role.TEACHER));
        assertTrue(session.current().isPresent());
        assertEquals("teacher1", session.current().get().username());

        // Old generation disconnect does not clear generation 2
        assertFalse(session.clear(1L));
        assertTrue(session.current().isPresent());

        // When transport reports disconnected, current() hides snapshot immediately
        connected.set(false);
        assertTrue(session.current().isEmpty());

        // Clearing matching generation returns true once
        assertTrue(session.clear(2L));
        assertFalse(session.clear(2L));

        // Unconditional clear
        connected.set(true);
        session.establish(2L, new LoginResultDto("tok-2", 5L, "teacher1", "Teacher One", Role.TEACHER));
        session.clear();
        assertTrue(session.current().isEmpty());
    }
}
