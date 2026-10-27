package quiz.client.anticheat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import quiz.client.anticheat.ProcessBlacklistScanner.DetectedProcess;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ProcessScannerService Unit Tests (FR-S13 nâng cấp, BR-13, NFR-09)")
class ProcessScannerServiceTest {

    @Test
    @DisplayName("Pre-Exam Scan: Quét đồng bộ và bất đồng bộ các tiến trình vi phạm")
    void testPreExamScan() throws Exception {
        ProcessBlacklistScanner mockScanner = new ProcessBlacklistScanner() {
            @Override
            public List<DetectedProcess> scan() {
                return List.of(
                    new DetectedProcess(101L, "Discord", "Chat", "Discord.exe"),
                    new DetectedProcess(102L, "Zalo", "Chat", "Zalo.exe")
                );
            }
        };

        ProcessScannerService service = new ProcessScannerService(mockScanner);
        try {
            // Quét đồng bộ
            List<DetectedProcess> syncList = service.scanViolations();
            assertEquals(2, syncList.size());

            // Quét bất đồng bộ
            List<DetectedProcess> asyncList = service.scanViolationsAsync().get(2, TimeUnit.SECONDS);
            assertEquals(2, asyncList.size());
            assertEquals("Discord", asyncList.get(0).appName());
            assertEquals("Zalo", asyncList.get(1).appName());
        } finally {
            service.stop();
        }
    }

    @Test
    @DisplayName("Stateful Deduplication: Chỉ phát hiện và gửi cảnh báo khi ứng dụng cấm MỚI xuất hiện (BR-13 & NFR-09)")
    void testStatefulDeduplication() throws Exception {
        AtomicInteger scanCycle = new AtomicInteger(0);

        ProcessBlacklistScanner mockScanner = new ProcessBlacklistScanner() {
            @Override
            public List<DetectedProcess> scan() {
                int cycle = scanCycle.get();
                if (cycle == 1) {
                    // Chu kỳ 1: Discord đang chạy
                    return List.of(new DetectedProcess(101L, "Discord", "Chat", "Discord.exe"));
                } else if (cycle == 2) {
                    // Chu kỳ 2: Discord vẫn đang chạy (không có app mới)
                    return List.of(new DetectedProcess(101L, "Discord", "Chat", "Discord.exe"));
                } else if (cycle == 3) {
                    // Chu kỳ 3: Discord đã tắt
                    return List.of();
                } else if (cycle == 4) {
                    // Chu kỳ 4: Zalo mới mở
                    return List.of(new DetectedProcess(102L, "Zalo", "Chat", "Zalo.exe"));
                }
                return List.of();
            }
        };

        ScheduledExecutorService directScheduler = Executors.newSingleThreadScheduledExecutor();
        ProcessScannerService service = new ProcessScannerService(mockScanner, directScheduler);

        List<String> newViolationEvents = new CopyOnWriteArrayList<>();
        List<Set<String>> resolvedEvents = new CopyOnWriteArrayList<>();

        ProcessScannerService.ViolationListener listener = new ProcessScannerService.ViolationListener() {
            @Override
            public void onNewViolation(List<DetectedProcess> newViolations, String formattedSummary) {
                newViolationEvents.add(formattedSummary);
            }

            @Override
            public void onViolationResolved(Set<String> resolvedApps) {
                resolvedEvents.add(new HashSet<>(resolvedApps));
            }
        };

        try {
            // Chu kỳ 1: Discord xuất hiện
            scanCycle.set(1);
            service.startPeriodicScan(1, listener);
            Thread.sleep(200);

            assertEquals(1, newViolationEvents.size());
            assertTrue(newViolationEvents.get(0).contains("Discord"));
            assertEquals(0, resolvedEvents.size());
            assertEquals(Set.of("Discord"), service.getActiveViolatingApps());

            // Chu kỳ 2: Discord vẫn chạy -> KHÔNG bắn sự kiện trùng lặp (Deduplication)
            scanCycle.set(2);
            Thread.sleep(1100);

            assertEquals(1, newViolationEvents.size(), "Không được gửi lại event cho ứng dụng chưa tắt");
            assertEquals(0, resolvedEvents.size());

            // Chu kỳ 3: Discord bị tắt -> kích hoạt onViolationResolved
            scanCycle.set(3);
            Thread.sleep(1100);

            assertEquals(1, newViolationEvents.size());
            assertEquals(1, resolvedEvents.size());
            assertTrue(resolvedEvents.get(0).contains("Discord"));
            assertTrue(service.getActiveViolatingApps().isEmpty());

            // Chu kỳ 4: Zalo mới xuất hiện -> kích hoạt onNewViolation cho Zalo
            scanCycle.set(4);
            Thread.sleep(1100);

            assertEquals(2, newViolationEvents.size());
            assertTrue(newViolationEvents.get(1).contains("Zalo"));
            assertEquals(Set.of("Zalo"), service.getActiveViolatingApps());

        } finally {
            service.stop();
        }
    }

    @Test
    @DisplayName("Dừng service giải phóng tài nguyên hoàn toàn")
    void testStopService() {
        ProcessBlacklistScanner mockScanner = new ProcessBlacklistScanner();
        ProcessScannerService service = new ProcessScannerService(mockScanner);

        service.startPeriodicScan(5, null);
        assertTrue(service.isRunning());

        service.stop();
        assertFalse(service.isRunning());
        assertTrue(service.getActiveViolatingApps().isEmpty());
    }
}

