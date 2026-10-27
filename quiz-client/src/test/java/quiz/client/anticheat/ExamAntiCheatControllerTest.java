package quiz.client.anticheat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import quiz.client.anticheat.ProcessBlacklistScanner.DetectedProcess;
import quiz.common.enums.AntiCheatEventType;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ExamAntiCheatController Unit Tests (Window & Process Coordinator, Grace Period)")
class ExamAntiCheatControllerTest {

    @Test
    @DisplayName("Grace Period: Kích hoạt thời gian ân hạn và đếm ngược")
    void testGracePeriod() {
        AtomicBoolean graceEnded = new AtomicBoolean(false);
        List<Integer> ticks = new ArrayList<>();

        ExamAntiCheatController.AntiCheatCallback callback = new ExamAntiCheatController.AntiCheatCallback() {
            @Override public void onReportViolation(AntiCheatEventType type, String detail) {}
            @Override public void onProhibitedProcessDetected(List<DetectedProcess> newViolations, String formattedSummary) {}
            @Override public void onViolationsResolved(Set<String> resolvedApps) {}
            @Override public void onGracePeriodTick(int secondsRemaining) { ticks.add(secondsRemaining); }
            @Override public void onGracePeriodEnded() { graceEnded.set(true); }
        };

        ProcessScannerService mockScanner = new ProcessScannerService();
        ExamAntiCheatController controller = new ExamAntiCheatController(mockScanner, callback, () -> false);

        assertFalse(controller.isGracePeriodActive());

        // Bắt đầu ân hạn
        controller.startGracePeriod(15, null);
        assertTrue(controller.isGracePeriodActive());
        assertFalse(ticks.isEmpty());
        assertEquals(15, ticks.get(0));

        // Dừng giám sát
        controller.stopMonitoring();
        assertFalse(controller.isGracePeriodActive());
    }

    @Test
    @DisplayName("Dừng ExamAntiCheatController giải phóng tài nguyên an toàn")
    void testStopMonitoring() {
        ProcessScannerService mockScanner = new ProcessScannerService();
        ExamAntiCheatController controller = new ExamAntiCheatController(mockScanner, new ExamAntiCheatController.AntiCheatCallback() {
            @Override public void onReportViolation(AntiCheatEventType type, String detail) {}
            @Override public void onProhibitedProcessDetected(List<DetectedProcess> newViolations, String formattedSummary) {}
            @Override public void onViolationsResolved(Set<String> resolvedApps) {}
            @Override public void onGracePeriodTick(int secondsRemaining) {}
            @Override public void onGracePeriodEnded() {}
        }, () -> false);

        controller.stopMonitoring();
        assertFalse(controller.isGracePeriodActive());
        assertFalse(mockScanner.isRunning());
    }
}

