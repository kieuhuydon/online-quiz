package quiz.client.anticheat;

import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.event.EventHandler;
import javafx.scene.Parent;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quiz.client.anticheat.ProcessBlacklistScanner.DetectedProcess;
import quiz.common.enums.AntiCheatEventType;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * Controller điều phối toàn bộ luồng giám sát chống gian lận trong lúc thi (FR-S13, FR-S14, BR-12, BR-13, NFR-09).
 * Kết nối chặt chẽ giữa:
 * 1. Window & Focus Monitor (Fullscreen, Focus, Minimize, Copy/Paste/Cut, Context Menu).
 * 2. ProcessScannerService (Quét ứng dụng cấm, Stateful Deduplication 10s, Buộc dừng).
 * 3. Remediation Grace Period (Thời gian ân hạn 15s để tắt phần mềm ngoài Desktop mà không bị phạt).
 */
public class ExamAntiCheatController {
    private static final Logger log = LoggerFactory.getLogger(ExamAntiCheatController.class);

    public interface AntiCheatCallback {
        /** Báo cáo một vi phạm về Server và kích hoạt cảnh báo thông thường */
        void onReportViolation(AntiCheatEventType type, String detail);

        /** Phát hiện ứng dụng cấm ngầm mới xuất hiện, mở popup xử lý an toàn in-scene */
        void onProhibitedProcessDetected(List<DetectedProcess> newViolations, String formattedSummary);

        /** Các ứng dụng cấm đã được tắt thành công */
        void onViolationsResolved(Set<String> resolvedApps);

        /** Cập nhật số giây còn lại của thời gian ân hạn 15s */
        void onGracePeriodTick(int secondsRemaining);

        /** Thời gian ân hạn 15s đã kết thúc */
        void onGracePeriodEnded();
    }

    private final ProcessScannerService processScannerService;
    private final AntiCheatCallback callback;
    private final BooleanSupplier isSuppressed; // true khi đang nộp bài hoặc đang mở dialog
    private final ScheduledExecutorService graceScheduler;

    // Cơ chế Grace Period (Thời gian ân hạn 15 giây - Yêu cầu 3)
    private final AtomicBoolean isRemediationGracePeriod = new AtomicBoolean(false);
    private ScheduledFuture<?> gracePeriodTask;

    // Quản lý Stage, Root và các Listener
    private Stage stage;
    private Parent examRoot;
    private ChangeListener<Boolean> fsListener;
    private ChangeListener<Boolean> focusListener;
    private ChangeListener<Boolean> iconListener;
    private EventHandler<KeyEvent> keyFilter;
    private EventHandler<ContextMenuEvent> contextMenuFilter;

    // Chống spam log cùng loại sự kiện
    private long lastReportTime = 0;
    private AntiCheatEventType lastReportType = null;

    public ExamAntiCheatController(AntiCheatCallback callback, BooleanSupplier isSuppressed) {
        this(new ProcessScannerService(), callback, isSuppressed);
    }

    public ExamAntiCheatController(ProcessScannerService processScannerService,
                                   AntiCheatCallback callback,
                                   BooleanSupplier isSuppressed) {
        this.processScannerService = Objects.requireNonNull(processScannerService, "processScannerService cannot be null");
        this.callback = Objects.requireNonNull(callback, "callback cannot be null");
        this.isSuppressed = Objects.requireNonNull(isSuppressed, "isSuppressed cannot be null");
        this.graceScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "quiz-grace-timer");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Bắt đầu toàn bộ luồng giám sát: khóa FullScreen, lắng nghe Window/Focus, và quét định kỳ 10 giây.
     */
    public void startMonitoring(Stage stage, Parent examRoot) {
        this.stage = Objects.requireNonNull(stage, "stage cannot be null");
        this.examRoot = examRoot;

        // 1. Cấu hình chế độ toàn màn hình không cho thoát mặc định bằng phím Esc
        stage.setFullScreenExitKeyCombination(KeyCombination.NO_MATCH);
        stage.setFullScreenExitHint("CHẾ ĐỘ THI TOÀN MÀN HÌNH — KHÔNG ĐƯỢC THOÁT HOẶC RỜI KHỎI CỬA SỔ");
        stage.setFullScreen(true);

        // 2. Window Monitor: Lắng nghe trạng thái thoát Fullscreen (FR-S14)
        fsListener = (obs, oldVal, newVal) -> {
            if (!newVal && !isRemediationGracePeriod.get() && !isSuppressed.getAsBoolean()) {
                reportViolationThrottled(AntiCheatEventType.FOCUS_LOST, "Thoát chế độ toàn màn hình");
                stage.setFullScreen(true);
            }
        };
        stage.fullScreenProperty().addListener(fsListener);

        // 3. Window Monitor: Lắng nghe mất focus / Alt+Tab (FR-S14)
        focusListener = (obs, oldVal, newVal) -> {
            if (!newVal && !isRemediationGracePeriod.get() && !isSuppressed.getAsBoolean()) {
                reportViolationThrottled(AntiCheatEventType.FOCUS_LOST, "Rời khỏi cửa sổ bài thi (Alt+Tab / Mất focus)");
            } else if (newVal && !isSuppressed.getAsBoolean()) {
                checkProcessesOnWindowFocus();
            }
        };
        stage.focusedProperty().addListener(focusListener);

        // 4. Window Monitor: Lắng nghe thu nhỏ cửa sổ (Minimize)
        iconListener = (obs, oldVal, newVal) -> {
            if (newVal && !isRemediationGracePeriod.get() && !isSuppressed.getAsBoolean()) {
                reportViolationThrottled(AntiCheatEventType.FOCUS_LOST, "Thu nhỏ cửa sổ bài thi (Minimize)");
            }
        };
        stage.iconifiedProperty().addListener(iconListener);

        // 5. Chặn phím Copy / Paste / Cut và Menu ngữ cảnh trong phạm vi bài thi
        if (examRoot != null) {
            keyFilter = event -> {
                if (isSuppressed.getAsBoolean()) return;
                if (event.isControlDown() && (event.getCode() == KeyCode.C || event.getCode() == KeyCode.INSERT)) {
                    event.consume();
                    reportViolationThrottled(AntiCheatEventType.COPY_ATTEMPT, "Cố gắng sao chép nội dung (Ctrl+C)");
                } else if ((event.isControlDown() && event.getCode() == KeyCode.V) || (event.isShiftDown() && event.getCode() == KeyCode.INSERT)) {
                    event.consume();
                    reportViolationThrottled(AntiCheatEventType.PASTE_ATTEMPT, "Cố gắng dán nội dung (Ctrl+V)");
                } else if (event.isControlDown() && event.getCode() == KeyCode.X) {
                    event.consume();
                    reportViolationThrottled(AntiCheatEventType.CUT_ATTEMPT, "Cố gắng cắt nội dung (Ctrl+X)");
                }
            };
            examRoot.addEventFilter(KeyEvent.KEY_PRESSED, keyFilter);

            contextMenuFilter = event -> {
                if (isSuppressed.getAsBoolean()) return;
                event.consume();
                reportViolationThrottled(AntiCheatEventType.CONTEXT_MENU_ATTEMPT, "Nhấp chuột phải mở menu ngữ cảnh");
            };
            examRoot.addEventFilter(ContextMenuEvent.CONTEXT_MENU_REQUESTED, contextMenuFilter);
        }

        // Quét ngay lập tức khi bắt đầu bài thi
        checkProcessesOnWindowFocus();

        // 6. Khởi động Process Scanner định kỳ mỗi 10 giây với Stateful Deduplication (Yêu cầu 2 & NFR-09)
        processScannerService.startPeriodicScan(10, new ProcessScannerService.ViolationListener() {
            @Override
            public void onNewViolation(List<DetectedProcess> newViolations, String formattedSummary) {
                Platform.runLater(() -> {
                    if (isSuppressed.getAsBoolean()) return;
                    // Báo cáo vi phạm về Server
                    callback.onReportViolation(AntiCheatEventType.PROHIBITED_PROCESS, formattedSummary);
                    // Hiển thị cảnh báo an toàn in-scene
                    callback.onProhibitedProcessDetected(newViolations, formattedSummary);
                });
            }

            @Override
            public void onViolationResolved(Set<String> resolvedApps) {
                Platform.runLater(() -> callback.onViolationsResolved(resolvedApps));
            }
        });
    }

    /**
     * Kích hoạt thời gian ân hạn (Grace Period - Yêu cầu 3).
     * Cho phép thí sinh ra Desktop tắt ứng dụng mà WindowFocusMonitor không xử phạt.
     * Sử dụng ScheduledExecutorService để đảm bảo bộ đếm giờ hoạt động chính xác khi cửa sổ mất focus.
     */
    public void startGracePeriod(int durationSeconds, Runnable onExpireAction) {
        log.info("Bắt đầu thời gian ân hạn {} giây cho thí sinh tắt ứng dụng ngoài Desktop", durationSeconds);
        isRemediationGracePeriod.set(true);

        if (gracePeriodTask != null) {
            gracePeriodTask.cancel(true);
        }

        final int[] remaining = {durationSeconds};
        notifyGraceTick(remaining[0]);

        gracePeriodTask = graceScheduler.scheduleAtFixedRate(() -> {
            remaining[0]--;
            notifyGraceTick(remaining[0]);
            if (remaining[0] <= 0) {
                endGracePeriod(onExpireAction);
            }
        }, 1, 1, TimeUnit.SECONDS);
    }

    private void notifyGraceTick(int sec) {
        try {
            Platform.runLater(() -> callback.onGracePeriodTick(sec));
        } catch (IllegalStateException e) {
            // Trường hợp chạy trong unit test không có JavaFX runtime
            callback.onGracePeriodTick(sec);
        }
    }

    private void endGracePeriod(Runnable onExpireAction) {
        if (gracePeriodTask != null) {
            gracePeriodTask.cancel(true);
            gracePeriodTask = null;
        }
        isRemediationGracePeriod.set(false);
        log.info("Thời gian ân hạn đã kết thúc, kích hoạt lại giám sát toàn màn hình");

        Runnable restoreAction = () -> {
            if (stage != null) {
                stage.setFullScreen(true);
            }
            callback.onGracePeriodEnded();
            if (onExpireAction != null) {
                onExpireAction.run();
            }
        };

        try {
            Platform.runLater(restoreAction);
        } catch (IllegalStateException e) {
            restoreAction.run();
        }
    }

    /**
     * Buộc dừng các tiến trình vi phạm bằng destroyForcibly() trực tiếp từ UI thread (Yêu cầu 3).
     */
    public CompletableFuture<Boolean> killProcesses(List<DetectedProcess> processes) {
        return processScannerService.terminateProcessesAsync(processes);
    }

    /**
     * Throttle gửi báo cáo vi phạm, tránh spam nhiều sự kiện giống nhau trong 1.5s.
     */
    private synchronized boolean reportViolationThrottled(AntiCheatEventType type, String detail) {
        long now = System.currentTimeMillis();
        // Cooldown tối thiểu 3 giây giữa 2 lần gửi bất kỳ sự kiện vi phạm nào lên Server
        if (now - lastReportTime < 3000) {
            log.info("Bỏ qua báo cáo vi phạm quá nhanh/trùng lặp (< 3s): type={}, detail={}", type, detail);
            return false;
        }
        lastReportTime = now;
        lastReportType = type;
        callback.onReportViolation(type, detail);
        return true;
    }

    /**
     * Dừng toàn bộ giám sát và giải phóng tài nguyên ngay lập tức (Yêu cầu 4).
     */
    public void checkProcessesOnWindowFocus() {
        if (isSuppressed.getAsBoolean()) return;
        List<DetectedProcess> running = processScannerService.scanViolations();
        if (!running.isEmpty()) {
            String summary = ProcessBlacklistScanner.formatViolationDetail(running);
            Platform.runLater(() -> {
                if (isSuppressed.getAsBoolean()) return;
                // 1. Luôn cập nhật giao diện popup cảnh báo in-scene kèm 2 nút Remediation
                callback.onProhibitedProcessDetected(running, summary);
            });

            // 2. Chỉ gửi sự kiện vi phạm lên Server nếu đây là ứng dụng cấm MỚI (Stateful Deduplication)
            processScannerService.scanNow(new ProcessScannerService.ViolationListener() {
                @Override
                public void onNewViolation(List<DetectedProcess> newViolations, String formattedSummary) {
                    reportViolationThrottled(AntiCheatEventType.PROHIBITED_PROCESS, formattedSummary);
                }

                @Override
                public void onViolationResolved(Set<String> resolvedApps) {
                    Platform.runLater(() -> callback.onViolationsResolved(resolvedApps));
                }
            });
        }
    }

    public void stopMonitoring() {
        // 1. Dừng Grace Period
        if (gracePeriodTask != null) {
            gracePeriodTask.cancel(true);
            gracePeriodTask = null;
        }
        isRemediationGracePeriod.set(false);
        graceScheduler.shutdownNow();

        // 2. Dừng ProcessScannerService
        processScannerService.stop();

        // 3. Gỡ bỏ các Listener trên Stage và Scene
        if (stage != null) {
            if (fsListener != null) stage.fullScreenProperty().removeListener(fsListener);
            if (focusListener != null) stage.focusedProperty().removeListener(focusListener);
            if (iconListener != null) stage.iconifiedProperty().removeListener(iconListener);
        }
        if (examRoot != null) {
            if (keyFilter != null) examRoot.removeEventFilter(KeyEvent.KEY_PRESSED, keyFilter);
            if (contextMenuFilter != null) examRoot.removeEventFilter(ContextMenuEvent.CONTEXT_MENU_REQUESTED, contextMenuFilter);
        }

        log.info("ExamAntiCheatController đã dọn dẹp và dừng toàn bộ giám sát");
    }

    public boolean isGracePeriodActive() {
        return isRemediationGracePeriod.get();
    }

    public ProcessScannerService getProcessScannerService() {
        return processScannerService;
    }
}

