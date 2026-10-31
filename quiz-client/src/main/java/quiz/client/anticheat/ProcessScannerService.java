package quiz.client.anticheat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quiz.client.anticheat.ProcessBlacklistScanner.DetectedProcess;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Service quản lý quét tiến trình ngầm (FR-S13 nâng cấp, BR-13, NFR-09).
 * Hỗ trợ:
 * 1. Pre-Exam Environment Check: Quét trước khi bắt đầu thi, tự động đóng phần mềm cấm.
 * 2. Stateful Deduplication: Lưu trạng thái các tiến trình đã phát hiện, chống spam log mỗi 10 giây.
 * 3. In-Exam Safe Remediation: Buộc tắt tiến trình bằng ProcessHandle.destroyForcibly() ngay trong ứng dụng.
 */
public class ProcessScannerService {
    private static final Logger log = LoggerFactory.getLogger(ProcessScannerService.class);

    public interface ViolationListener {
        /**
         * Được gọi khi phát hiện các ứng dụng cấm MỚI xuất hiện so với lần quét trước.
         */
        void onNewViolation(List<DetectedProcess> newViolations, String formattedSummary);

        /**
         * Được gọi khi các ứng dụng cấm trước đó đã được tắt hoàn toàn.
         */
        void onViolationResolved(Set<String> resolvedApps);
    }

    private final ProcessBlacklistScanner scanner;
    private final ScheduledExecutorService scheduler;
    private final Set<String> activeViolatingApps = ConcurrentHashMap.newKeySet();
    private ScheduledFuture<?> scheduledTask;
    private volatile boolean isRunning = false;

    public ProcessScannerService() {
        this(new ProcessBlacklistScanner());
    }

    public ProcessScannerService(ProcessBlacklistScanner scanner) {
        this(scanner, Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "quiz-process-scanner");
            t.setDaemon(true);
            return t;
        }));
    }

    public ProcessScannerService(ProcessBlacklistScanner scanner, ScheduledExecutorService scheduler) {
        this.scanner = Objects.requireNonNull(scanner, "scanner cannot be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler cannot be null");
    }

    // ==========================================
    // 1. PRE-EXAM ENVIRONMENT CHECK & REMEDIATION
    // ==========================================

    /**
     * Quét đồng bộ danh sách tiến trình vi phạm.
     */
    public List<DetectedProcess> scanViolations() {
        return scanner.scan();
    }

    /**
     * Quét bất đồng bộ danh sách tiến trình vi phạm trên background thread (NFR-09).
     */
    public CompletableFuture<List<DetectedProcess>> scanViolationsAsync() {
        return CompletableFuture.supplyAsync(this::scanViolations, scheduler);
    }

    /**
     * Tự động tắt danh sách tiến trình vi phạm bằng destroy() và fallback sang destroyForcibly().
     * Trả về true nếu tất cả tiến trình đều đã được tắt thành công.
     */
    public boolean terminateProcesses(List<DetectedProcess> processes) {
        if (processes == null || processes.isEmpty()) {
            return true;
        }
        boolean allTerminated = true;
        for (DetectedProcess proc : processes) {
            try {
                Optional<ProcessHandle> handleOpt = ProcessHandle.of(proc.pid());
                if (handleOpt.isPresent()) {
                    ProcessHandle ph = handleOpt.get();
                    if (ph.isAlive()) {
                        try {
                            ph.descendants().forEach(d -> {
                                try { d.destroyForcibly(); } catch (Exception ignored) {}
                            });
                        } catch (Exception ignored) {}
                        ph.destroy();
                        try {
                            Thread.sleep(200);
                        } catch (InterruptedException ignored) {}

                        if (ph.isAlive()) {
                            ph.destroyForcibly();
                            try {
                                Thread.sleep(150);
                            } catch (InterruptedException ignored) {}
                        }

                        if (ph.isAlive()) {
                            allTerminated = false;
                            log.warn("Không thể dừng tiến trình {} (PID {})", proc.appName(), proc.pid());
                        } else {
                            log.info("Đã dừng tiến trình {} (PID {}) thành công", proc.appName(), proc.pid());
                        }
                    }
                }
            } catch (Exception e) {
                allTerminated = false;
                log.warn("Lỗi khi buộc dừng tiến trình {} (PID {}): {}", proc.appName(), proc.pid(), e.getMessage());
            }
        }
        if (allTerminated) {
            Set<String> killedAppNames = processes.stream()
                .map(DetectedProcess::appName)
                .collect(Collectors.toSet());
            activeViolatingApps.removeAll(killedAppNames);
        }
        return allTerminated;
    }

    /**
     * Tắt bất đồng bộ danh sách tiến trình vi phạm (NFR-09).
     */
    public CompletableFuture<Boolean> terminateProcessesAsync(List<DetectedProcess> processes) {
        return CompletableFuture.supplyAsync(() -> terminateProcesses(processes), scheduler);
    }

    /**
     * Quét và tắt bất đồng bộ tất cả tiến trình vi phạm đang chạy.
     */
    public CompletableFuture<Boolean> terminateAllViolationsAsync() {
        return CompletableFuture.supplyAsync(() -> {
            List<DetectedProcess> current = scanViolations();
            return terminateProcesses(current);
        }, scheduler);
    }

    // ========================================================
    // 2. STATEFUL DEDUPLICATION & PERIODIC SCANNING (BR-13)
    // ========================================================

    /**
     * Bắt đầu quét định kỳ với cơ chế Stateful Deduplication (Chống spam log NFR-09).
     * Chỉ kích hoạt onNewViolation khi có tiến trình cấm MỚI xuất hiện.
     */
    public synchronized void startPeriodicScan(long intervalSeconds, ViolationListener listener) {
        if (isRunning) {
            return;
        }
        isRunning = true;
        activeViolatingApps.clear();

        // Quét ngay lần đầu trên background thread
        scheduler.execute(() -> executeScan(listener));

        // Lên lịch định kỳ mỗi intervalSeconds (mặc định 10s)
        scheduledTask = scheduler.scheduleAtFixedRate(
            () -> executeScan(listener),
            intervalSeconds,
            intervalSeconds,
            TimeUnit.SECONDS
        );
        log.info("ProcessScannerService đã khởi động quét định kỳ {}s/lần", intervalSeconds);
    }

    /**
     * Kích hoạt quét ngay lập tức với Stateful Deduplication.
     * Chỉ gọi onNewViolation nếu có ứng dụng cấm MỚI xuất hiện so với activeViolatingApps.
     */
    public synchronized void scanNow(ViolationListener listener) {
        if (!isRunning) return;
        scheduler.execute(() -> executeScan(listener));
    }

    private void executeScan(ViolationListener listener) {
        if (!isRunning) return;
        try {
            List<DetectedProcess> currentViolations = scanner.scan();
            if (!isRunning) return;
            Set<String> currentApps = currentViolations.stream()
                .map(DetectedProcess::appName)
                .collect(Collectors.toSet());

            // 1. Kiểm tra các ứng dụng trước đó đã được tắt (PROCESS_TERMINATED)
            Set<String> resolvedApps = new HashSet<>(activeViolatingApps);
            resolvedApps.removeAll(currentApps);
            if (!resolvedApps.isEmpty()) {
                activeViolatingApps.removeAll(resolvedApps);
                log.info("PROCESS_TERMINATED: Thí sinh đã tắt ứng dụng cấm: {}", resolvedApps);
                if (listener != null) {
                    listener.onViolationResolved(Collections.unmodifiableSet(resolvedApps));
                }
            }

            if (!isRunning) return;
            // 2. Kiểm tra các ứng dụng cấm MỚI xuất hiện (PROCESS_DETECTED)
            List<DetectedProcess> newViolations = currentViolations.stream()
                .filter(p -> !activeViolatingApps.contains(p.appName()))
                .toList();

            if (!newViolations.isEmpty()) {
                Set<String> newAppNames = newViolations.stream()
                    .map(DetectedProcess::appName)
                    .collect(Collectors.toSet());
                activeViolatingApps.addAll(newAppNames);

                String formatted = ProcessBlacklistScanner.formatViolationDetail(newViolations);
                log.warn("PROCESS_DETECTED: Phát hiện ứng dụng cấm mới: {}", formatted);
                if (listener != null) {
                    listener.onNewViolation(Collections.unmodifiableList(newViolations), formatted);
                }
            }
        } catch (Exception e) {
            log.warn("Lỗi trong chu kỳ quét tiến trình cấm: {}", e.getMessage());
        }
    }

    /**
     * Dừng quét định kỳ và dọn dẹp tài nguyên ScheduledExecutorService.
     */
    public synchronized void stop() {
        isRunning = false;
        if (scheduledTask != null) {
            scheduledTask.cancel(true);
            scheduledTask = null;
        }
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(500, TimeUnit.MILLISECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        activeViolatingApps.clear();
        log.info("ProcessScannerService đã dừng");
    }

    public boolean isRunning() {
        return isRunning;
    }

    public Set<String> getActiveViolatingApps() {
        return Collections.unmodifiableSet(activeViolatingApps);
    }
}
