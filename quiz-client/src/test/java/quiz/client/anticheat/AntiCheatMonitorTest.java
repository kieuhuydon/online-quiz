package quiz.client.anticheat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import quiz.common.dto.ReportAntiCheatDto;
import quiz.common.enums.AntiCheatEventType;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class AntiCheatMonitorTest {

    @Test
    @DisplayName("Initial state is inactive and closing stopped monitor is safe")
    void initialStateAndClose() {
        AntiCheatMonitor monitor = new AntiCheatMonitor(dto -> {}, dto -> {});
        assertFalse(monitor.isActive());
        assertEquals(0L, monitor.getCurrentAttemptId());

        assertDoesNotThrow(monitor::close);
        assertFalse(monitor.isActive());
    }

    @Test
    @DisplayName("Start requires valid parameters")
    void startValidation() {
        AntiCheatMonitor monitor = new AntiCheatMonitor(dto -> {}, dto -> {});

        assertThrows(IllegalArgumentException.class, () -> monitor.start(null, null, 1L));
        assertThrows(IllegalArgumentException.class, () -> monitor.start(null, null, -1L));
    }

    @Test
    @DisplayName("Violations are reported through reporter and local callback")
    void handleViolationReportsSuccessfully() {
        List<ReportAntiCheatDto> reported = new ArrayList<>();
        AtomicBoolean callbackInvoked = new AtomicBoolean(false);

        AntiCheatMonitor monitor = new AntiCheatMonitor(reported::add, dto -> callbackInvoked.set(true));
        monitor.handleViolation(AntiCheatEventType.FOCUS_LOST, "Test lost focus");

        assertEquals(1, reported.size());
        assertEquals(AntiCheatEventType.FOCUS_LOST, reported.get(0).eventType());
        assertEquals("Test lost focus", reported.get(0).detail());
    }

    @Test
    @DisplayName("Subsequent rapid violations of same type are debounced")
    void violationsAreDebounced() {
        List<ReportAntiCheatDto> reported = new ArrayList<>();
        AntiCheatMonitor monitor = new AntiCheatMonitor(reported::add, null);

        monitor.handleViolation(AntiCheatEventType.COPY_ATTEMPT, "Copy 1");
        monitor.handleViolation(AntiCheatEventType.COPY_ATTEMPT, "Copy 2");
        monitor.handleViolation(AntiCheatEventType.COPY_ATTEMPT, "Copy 3");

        // Only first should be reported due to debounce
        assertEquals(1, reported.size());
        assertEquals("Copy 1", reported.get(0).detail());

        // A different event type is not debounced by the first
        monitor.handleViolation(AntiCheatEventType.PASTE_ATTEMPT, "Paste 1");
        assertEquals(2, reported.size());
        assertEquals(AntiCheatEventType.PASTE_ATTEMPT, reported.get(1).eventType());
    }
}
