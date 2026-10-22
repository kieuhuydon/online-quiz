package quiz.client.anticheat;

import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.event.EventHandler;
import javafx.scene.Scene;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quiz.client.api.QuizApi;
import quiz.common.dto.ReportAntiCheatDto;
import quiz.common.enums.AntiCheatEventType;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Giám sát và ngăn chặn các hành vi gian lận phía Client trong quá trình làm bài thi (FR-S13, FR-S14).
 * 
 * - Hạn chế phím tắt Copy/Paste/Cut (Ctrl+C, Ctrl+V, Ctrl+X, Ctrl+Insert, Shift+Insert).
 * - Chặn context menu chuột phải.
 * - Giám sát việc chuyển tab / rời cửa sổ thi / thu nhỏ ứng dụng (Focus lost, Iconified).
 * - Báo cáo sự kiện vi phạm lên Server theo Attempt (FR-T09).
 */
public final class AntiCheatMonitor implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(AntiCheatMonitor.class);
    private static final long DEBOUNCE_MILLIS = 800; // Tránh flood sự kiện lặp lại trong thời gian ngắn

    private final Consumer<ReportAntiCheatDto> reporter;
    private final Consumer<ReportAntiCheatDto> violationCallback;
    private final Map<AntiCheatEventType, Long> lastReportedTimes = new EnumMap<>(AntiCheatEventType.class);

    private Stage monitoredStage;
    private Scene monitoredScene;
    private long currentAttemptId;
    private boolean active = false;

    private ChangeListener<Boolean> focusListener;
    private ChangeListener<Boolean> iconifiedListener;
    private EventHandler<KeyEvent> keyEventFilter;
    private EventHandler<ContextMenuEvent> contextMenuFilter;

    public AntiCheatMonitor(QuizApi api) {
        this(Objects.requireNonNull(api, "QuizApi cannot be null")::reportAntiCheat, null);
    }

    public AntiCheatMonitor(QuizApi api, Consumer<ReportAntiCheatDto> violationCallback) {
        this(Objects.requireNonNull(api, "QuizApi cannot be null")::reportAntiCheat, violationCallback);
    }

    public AntiCheatMonitor(Consumer<ReportAntiCheatDto> reporter, Consumer<ReportAntiCheatDto> violationCallback) {
        this.reporter = Objects.requireNonNull(reporter, "reporter cannot be null");
        this.violationCallback = violationCallback;
    }

    /**
     * Bắt đầu giám sát cửa sổ và giao diện làm bài thi của học viên.
     */
    public synchronized void start(Stage stage, Scene scene, long attemptId) {
        if (active) {
            stop();
        }
        if (stage == null || scene == null || attemptId <= 0) {
            throw new IllegalArgumentException("Invalid monitor parameters");
        }

        this.monitoredStage = stage;
        this.monitoredScene = scene;
        this.currentAttemptId = attemptId;
        this.active = true;
        this.lastReportedTimes.clear();

        // 1. Giám sát mất focus cửa sổ (FR-S14: monitor test window)
        focusListener = (obs, wasFocused, isNowFocused) -> {
            if (active && !isNowFocused) {
                handleViolation(AntiCheatEventType.FOCUS_LOST, "Rời khỏi cửa sổ bài thi");
            }
        };
        stage.focusedProperty().addListener(focusListener);

        // 2. Giám sát thu nhỏ cửa sổ bài thi
        iconifiedListener = (obs, wasIconified, isNowIconified) -> {
            if (active && isNowIconified) {
                handleViolation(AntiCheatEventType.FOCUS_LOST, "Thu nhỏ cửa sổ bài thi (Minimize)");
            }
        };
        stage.iconifiedProperty().addListener(iconifiedListener);

        // 3. Chặn và phát hiện phím tắt Copy, Paste, Cut (FR-S14: restrict copy/paste)
        keyEventFilter = event -> {
            if (!active) return;

            boolean isCtrlOrMeta = event.isControlDown() || event.isMetaDown();
            KeyCode code = event.getCode();

            if (isCtrlOrMeta && code == KeyCode.C) {
                event.consume();
                handleViolation(AntiCheatEventType.COPY_ATTEMPT, "Cố ý sao chép nội dung (Ctrl+C)");
            } else if (isCtrlOrMeta && code == KeyCode.INSERT) {
                event.consume();
                handleViolation(AntiCheatEventType.COPY_ATTEMPT, "Cố ý sao chép nội dung (Ctrl+Insert)");
            } else if (isCtrlOrMeta && code == KeyCode.V) {
                event.consume();
                handleViolation(AntiCheatEventType.PASTE_ATTEMPT, "Cố ý dán văn bản (Ctrl+V)");
            } else if (event.isShiftDown() && code == KeyCode.INSERT) {
                event.consume();
                handleViolation(AntiCheatEventType.PASTE_ATTEMPT, "Cố ý dán văn bản (Shift+Insert)");
            } else if (isCtrlOrMeta && code == KeyCode.X) {
                event.consume();
                handleViolation(AntiCheatEventType.CUT_ATTEMPT, "Cố ý cắt nội dung (Ctrl+X)");
            }
        };
        scene.addEventFilter(KeyEvent.KEY_PRESSED, keyEventFilter);

        // 4. Chặn và phát hiện chuột phải mở Context Menu
        contextMenuFilter = event -> {
            if (!active) return;
            event.consume();
            handleViolation(AntiCheatEventType.CONTEXT_MENU_ATTEMPT, "Cố ý mở menu ngữ cảnh (chuột phải)");
        };
        scene.addEventFilter(ContextMenuEvent.CONTEXT_MENU_REQUESTED, contextMenuFilter);

        log.info("AntiCheatMonitor started for attemptId={}", attemptId);
    }

    /**
     * Dừng giám sát và gỡ bỏ tất cả các listeners để giải phóng tài nguyên.
     */
    public synchronized void stop() {
        if (!active) {
            return;
        }
        active = false;

        if (monitoredStage != null) {
            if (focusListener != null) {
                monitoredStage.focusedProperty().removeListener(focusListener);
            }
            if (iconifiedListener != null) {
                monitoredStage.iconifiedProperty().removeListener(iconifiedListener);
            }
        }

        if (monitoredScene != null) {
            if (keyEventFilter != null) {
                monitoredScene.removeEventFilter(KeyEvent.KEY_PRESSED, keyEventFilter);
            }
            if (contextMenuFilter != null) {
                monitoredScene.removeEventFilter(ContextMenuEvent.CONTEXT_MENU_REQUESTED, contextMenuFilter);
            }
        }

        monitoredStage = null;
        monitoredScene = null;
        log.info("AntiCheatMonitor stopped for attemptId={}", currentAttemptId);
    }

    @Override
    public void close() {
        stop();
    }

    public boolean isActive() {
        return active;
    }

    public long getCurrentAttemptId() {
        return currentAttemptId;
    }

    void handleViolation(AntiCheatEventType type, String detail) {
        long now = System.currentTimeMillis();
        Long lastTime = lastReportedTimes.get(type);
        if (lastTime != null && (now - lastTime < DEBOUNCE_MILLIS)) {
            return; // Bỏ qua nếu vi phạm cùng loại kích hoạt liên tiếp quá nhanh
        }
        lastReportedTimes.put(type, now);

        ReportAntiCheatDto dto = new ReportAntiCheatDto(currentAttemptId, type, detail, Instant.now());
        log.warn("Detected violation in attempt {}: {} - {}", currentAttemptId, type, detail);

        // Gọi callback cục bộ trên client (để UI thông báo tức thì nếu cần)
        if (violationCallback != null) {
            try {
                if (Platform.isFxApplicationThread()) {
                    violationCallback.accept(dto);
                } else {
                    Platform.runLater(() -> violationCallback.accept(dto));
                }
            } catch (Exception e) {
                log.debug("Local violation callback notification skipped: {}", e.getMessage());
            }
        }

        // Báo cáo về server (FR-T09)
        try {
            reporter.accept(dto);
        } catch (Exception err) {
            log.warn("Failed to report anti-cheat event to server: {}", err.getMessage());
        }
    }
}
