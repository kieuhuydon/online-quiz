package quiz.client.ui.student;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.value.ChangeListener;
import javafx.event.EventHandler;
import javafx.fxml.FXML;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quiz.client.api.ApiException;
import quiz.client.api.QuizApi;
import quiz.client.network.PushListener;
import quiz.client.ui.SceneNavigator;
import quiz.client.ui.ScreenController;
import quiz.client.util.FxAsync;
import quiz.common.dto.AntiCheatWarningDto;
import quiz.common.dto.AttemptStateDto;
import quiz.common.dto.ReportAntiCheatDto;
import quiz.common.dto.StudentQuestionDto;
import quiz.common.enums.AntiCheatEventType;
import quiz.common.protocol.MessageType;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Điều khiển màn hình làm bài thi của thí sinh:
 * - Giám sát và chống gian lận (FR-S13, FR-S14, NFR-09)
 * - Toàn màn hình bắt buộc, chống Alt+Tab và theo dõi mất focus
 * - Chặn sao chép / dán (Copy / Paste / Cut), chuột phải ContextMenu
 * - Giao diện popup/modal in-scene không làm mất FullScreen hay phát sinh vi phạm giả khi nộp bài
 * - Lắng nghe cảnh báo và tự động nộp bài khi vi phạm vượt ngưỡng
 */
public final class ExamController extends ScreenController {
    private static final Logger log = LoggerFactory.getLogger(ExamController.class);

    private final QuizApi api;
    private final SceneNavigator navigator;
    private final FxAsync fx;
    private final AttemptStateDto attempt;

    @FXML private Label title, timer, questionPosition, saveStatus;
    @FXML private TextArea questionText;
    @FXML private RadioButton optionA, optionB, optionC, optionD;
    @FXML private ToggleGroup options;
    @FXML private GridPane reviewGrid;
    @FXML private Button previous, next, submit;

    // Các thành phần Modal in-scene (tránh thoát Fullscreen hoặc mất window focus)
    @FXML private StackPane examRoot;
    @FXML private StackPane confirmOverlay;
    @FXML private StackPane warningOverlay;
    @FXML private Label warningHeader, warningDetail;

    private final Map<Long, Character> answers = new HashMap<>();
    private Timeline countdown;
    private long seconds;
    private int index;

    // Trạng thái nộp bài và mở hộp thoại
    private volatile boolean isSubmitting = false;
    private volatile boolean isDialogOpen = false;

    // Quản lý listeners & filters chống gian lận
    private ChangeListener<Boolean> fsListener;
    private ChangeListener<Boolean> focusListener;
    private ChangeListener<Boolean> iconListener;
    private EventHandler<KeyEvent> keyFilter;
    private EventHandler<ContextMenuEvent> contextMenuFilter;

    // Throttle gửi báo cáo vi phạm
    private long lastReportTime = 0;
    private AntiCheatEventType lastReportType = null;

    public ExamController(QuizApi api, SceneNavigator navigator, FxAsync fx, AttemptStateDto attempt) {
        this.api = api;
        this.navigator = navigator;
        this.fx = fx;
        this.attempt = attempt;
        answers.putAll(attempt.savedAnswers());
        seconds = attempt.remainingSeconds();
    }

    @FXML
    private void initialize() {
        title.setText(attempt.quizTitle());
        render();
        refreshReview();
    }

    @Override
    protected void onMount() {
        // 1. Đồng hồ đếm ngược
        if (seconds <= 0) {
            executeSubmit();
        } else {
            countdown = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
                seconds = Math.max(0, seconds - 1);
                updateTimer();
                if (seconds == 0) executeSubmit();
            }));
            countdown.setCycleCount(Animation.INDEFINITE);
            countdown.play();
            updateTimer();
        }

        // 2. Kích hoạt giám sát FullScreen & Chống gian lận
        setupAntiCheatMonitoring();

        // 3. Lắng nghe thông báo PUSH từ Server
        PushListener pushListener = (generation, response) -> {
            if (response.pushType() == MessageType.PUSH_FORCE_SUBMITTED) {
                fx.run(() -> {
                    if (isActive()) {
                        isSubmitting = true;
                        removeAntiCheatListeners();
                        String reason = response.payload() instanceof String str
                            ? str
                            : "Bài thi của bạn đã bị thu hồi do vi phạm quy chế thi!";
                        if (warningOverlay != null && warningHeader != null && warningDetail != null) {
                            warningHeader.setText("🛑 BÀI THI ĐÃ BỊ THU HỒI");
                            warningDetail.setText(reason);
                            warningOverlay.setVisible(true);
                            warningOverlay.toFront();
                        }
                        executeSubmit();
                    }
                });
            } else if (response.pushType() == MessageType.PUSH_ANTICHEAT_WARNING) {
                fx.run(() -> {
                    if (isActive() && !isSubmitting) {
                        String msg = "Bạn vừa vi phạm quy chế thi!";
                        int count = 1, max = 3;
                        if (response.payload() instanceof AntiCheatWarningDto w) {
                            msg = w.message();
                            count = w.violationCount();
                            max = w.maxViolations();
                        }
                        if (warningOverlay != null && warningHeader != null && warningDetail != null) {
                            warningHeader.setText(String.format("⚠️ CẢNH BÁO VI PHẠM (LẦN %d / %d)", count, max));
                            warningDetail.setText(msg);
                            isDialogOpen = true;
                            warningOverlay.setVisible(true);
                            warningOverlay.toFront();
                        } else {
                            isDialogOpen = true;
                            Alert alert = new Alert(Alert.AlertType.WARNING, msg, ButtonType.OK);
                            alert.setTitle("CẢNH BÁO VI PHẠM");
                            alert.setHeaderText(String.format("CẢNH BÁO VI PHẠM LẦN %d / %d", count, max));
                            if (navigator.stage() != null) alert.initOwner(navigator.stage());
                            alert.showAndWait();
                            isDialogOpen = false;
                        }
                    }
                });
            }
        };
        own(api.addPushListener(pushListener));
    }

    private void setupAntiCheatMonitoring() {
        Stage stage = navigator.stage();
        if (stage == null) return;

        // Vô hiệu hóa phím Esc thoát toàn màn hình mặc định của JavaFX
        stage.setFullScreenExitKeyCombination(KeyCombination.NO_MATCH);
        stage.setFullScreenExitHint("CHẾ ĐỘ THI TOÀN MÀN HÌNH — KHÔNG ĐƯỢC THOÁT HOẶC RỜI KHỎI CỬA SỔ");
        stage.setFullScreen(true);

        // Theo dõi thoát toàn màn hình
        fsListener = (obs, oldVal, newVal) -> {
            if (!newVal && isActive() && !isSubmitting && !isDialogOpen) {
                triggerAntiCheat(AntiCheatEventType.FOCUS_LOST, "Thoát chế độ toàn màn hình");
                stage.setFullScreen(true); // Tự động bật lại FullScreen
            }
        };
        stage.fullScreenProperty().addListener(fsListener);

        // Theo dõi mất focus / chuyển tab / Alt+Tab
        focusListener = (obs, oldVal, newVal) -> {
            if (!newVal && isActive() && !isSubmitting && !isDialogOpen) {
                triggerAntiCheat(AntiCheatEventType.FOCUS_LOST, "Rời khỏi cửa sổ bài thi (Alt+Tab / Mất focus)");
            }
        };
        stage.focusedProperty().addListener(focusListener);

        // Theo dõi minimize cửa sổ
        iconListener = (obs, oldVal, newVal) -> {
            if (newVal && isActive() && !isSubmitting && !isDialogOpen) {
                triggerAntiCheat(AntiCheatEventType.FOCUS_LOST, "Thu nhỏ cửa sổ thi (Minimize)");
            }
        };
        stage.iconifiedProperty().addListener(iconListener);

        // Chặn phím Copy / Paste / Cut và chuột phải CHỈ TRONG PHẠM VI BÀI THI (examRoot)
        // Tuyệt đối không can thiệp vào các màn hình khác của giáo viên hoặc học sinh
        if (examRoot != null) {
            keyFilter = event -> {
                if (!isActive() || isSubmitting) return;
                if (event.isControlDown() && (event.getCode() == KeyCode.C || event.getCode() == KeyCode.INSERT)) {
                    event.consume();
                    triggerAntiCheat(AntiCheatEventType.COPY_ATTEMPT, "Cố gắng sao chép nội dung (Ctrl+C)");
                } else if ((event.isControlDown() && event.getCode() == KeyCode.V) || (event.isShiftDown() && event.getCode() == KeyCode.INSERT)) {
                    event.consume();
                    triggerAntiCheat(AntiCheatEventType.PASTE_ATTEMPT, "Cố gắng dán nội dung (Ctrl+V)");
                } else if (event.isControlDown() && event.getCode() == KeyCode.X) {
                    event.consume();
                    triggerAntiCheat(AntiCheatEventType.CUT_ATTEMPT, "Cố gắng cắt nội dung (Ctrl+X)");
                }
            };
            examRoot.addEventFilter(KeyEvent.KEY_PRESSED, keyFilter);

            contextMenuFilter = event -> {
                if (!isActive() || isSubmitting) return;
                event.consume();
                triggerAntiCheat(AntiCheatEventType.CONTEXT_MENU_ATTEMPT, "Nhấp chuột phải mở menu ngữ cảnh");
            };
            examRoot.addEventFilter(ContextMenuEvent.CONTEXT_MENU_REQUESTED, contextMenuFilter);
        }
    }

    private void removeAntiCheatListeners() {
        if (examRoot != null) {
            if (keyFilter != null) examRoot.removeEventFilter(KeyEvent.KEY_PRESSED, keyFilter);
            if (contextMenuFilter != null) examRoot.removeEventFilter(ContextMenuEvent.CONTEXT_MENU_REQUESTED, contextMenuFilter);
        }
        Stage stage = navigator.stage();
        if (stage != null) {
            if (fsListener != null) stage.fullScreenProperty().removeListener(fsListener);
            if (focusListener != null) stage.focusedProperty().removeListener(focusListener);
            if (iconListener != null) stage.iconifiedProperty().removeListener(iconListener);
            Scene scene = stage.getScene();
            if (scene != null) {
                if (keyFilter != null) scene.removeEventFilter(KeyEvent.KEY_PRESSED, keyFilter);
                if (contextMenuFilter != null) scene.removeEventFilter(ContextMenuEvent.CONTEXT_MENU_REQUESTED, contextMenuFilter);
            }
        }
    }

    private void triggerAntiCheat(AntiCheatEventType type, String detail) {
        if (!isActive() || isSubmitting || isDialogOpen) {
            return;
        }
        long now = System.currentTimeMillis();
        // Cooldown 1.5 giây cho cùng loại sự kiện để tránh spam
        if (type == lastReportType && (now - lastReportTime < 1500)) {
            return;
        }
        lastReportTime = now;
        lastReportType = type;

        log.warn("Client AntiCheat phát hiện: type={}, detail={}", type, detail);
        ReportAntiCheatDto dto = new ReportAntiCheatDto(attempt.attemptId(), type, detail, Instant.now());
        api.reportAntiCheat(dto).exceptionally(err -> {
            log.error("Gửi sự kiện chống gian lận lên Server thất bại: {}", err.getMessage());
            return null;
        });
    }

    private StudentQuestionDto current() {
        return attempt.questions().get(index);
    }

    private void render() {
        if (attempt.questions().isEmpty()) {
            questionText.setText("Bài kiểm tra không có câu hỏi.");
            return;
        }
        StudentQuestionDto q = current();
        questionPosition.setText("Câu " + (index + 1) + " / " + attempt.questions().size());
        questionText.setText(q.content());
        optionA.setText("A. " + q.optionA());
        optionB.setText("B. " + q.optionB());
        optionC.setText("C. " + q.optionC());
        optionD.setText("D. " + q.optionD());
        Character selected = answers.get(q.id());
        options.selectToggle(selected == null ? null : switch (selected) {
            case 'A' -> optionA;
            case 'B' -> optionB;
            case 'C' -> optionC;
            case 'D' -> optionD;
            default -> null;
        });
        previous.setDisable(index == 0);
        next.setDisable(index >= attempt.questions().size() - 1);
        refreshReview();
    }

    @FXML
    private void selectAnswer() {
        if (!isActive() || isSubmitting || attempt.questions().isEmpty()) return;
        Toggle t = options.getSelectedToggle();
        if (t == null) return;
        char option = t == optionA ? 'A' : t == optionB ? 'B' : t == optionC ? 'C' : 'D';
        long questionId = current().id();
        answers.put(questionId, option);
        refreshReview();
        saveStatus.setText("Đang lưu…");
        fx.observe(api.saveAnswer(attempt.attemptId(), questionId, option), this::isActive,
            v -> saveStatus.setText(""),
            e -> saveStatus.setText("Chưa lưu được — hãy thử chọn lại"),
            () -> {});
    }

    @FXML
    private void previous() {
        if (index > 0) {
            index--;
            render();
        }
    }

    @FXML
    private void next() {
        if (index + 1 < attempt.questions().size()) {
            index++;
            render();
        }
    }

    private void refreshReview() {
        reviewGrid.getChildren().clear();
        for (int i = 0; i < attempt.questions().size(); i++) {
            final int n = i;
            StudentQuestionDto q = attempt.questions().get(i);
            Button b = new Button(Integer.toString(i + 1));
            b.setPrefSize(72, 44);
            String background = answers.containsKey(q.id()) ? "#b7e4c7" : "#e5e7eb";
            String border = n == index ? "-fx-border-color: #2563eb; -fx-border-width: 2;" : "-fx-border-color: transparent;";
            b.setStyle("-fx-background-color: " + background + "; " + border + " -fx-background-radius: 6; -fx-border-radius: 6;");
            b.setOnAction(e -> {
                index = n;
                render();
            });
            reviewGrid.add(b, i % 3, i / 3);
        }
    }

    private void updateTimer() {
        long m = seconds / 60, s = seconds % 60;
        timer.setText(String.format("%02d:%02d", m, s));
        timer.setStyle(seconds < 60
            ? "-fx-text-fill: #c0392b; -fx-font-size: 20px; -fx-font-weight: bold;"
            : "-fx-font-size: 20px; -fx-font-weight: bold;");
    }

    @FXML
    private void manualSubmit() {
        if (!isActive() || isSubmitting || submit.isDisabled()) return;

        if (confirmOverlay != null) {
            isDialogOpen = true;
            confirmOverlay.setVisible(true);
            confirmOverlay.toFront();
        } else {
            // Trường hợp dự phòng nếu FXML không có overlay
            isDialogOpen = true;
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, "Bạn chắc chắn muốn nộp bài?", ButtonType.CANCEL, ButtonType.OK);
            confirm.setHeaderText("Nộp bài");
            if (navigator.stage() != null) confirm.initOwner(navigator.stage());
            Optional<ButtonType> res = confirm.showAndWait();
            isDialogOpen = false;
            if (res.orElse(ButtonType.CANCEL) == ButtonType.OK) {
                executeSubmit();
            }
        }
    }

    @FXML
    private void cancelSubmit() {
        if (confirmOverlay != null) {
            confirmOverlay.setVisible(false);
        }
        isDialogOpen = false;
    }

    @FXML
    private void confirmSubmit() {
        if (confirmOverlay != null) {
            confirmOverlay.setVisible(false);
        }
        isDialogOpen = false;
        executeSubmit();
    }

    @FXML
    private void dismissWarning() {
        if (warningOverlay != null) {
            warningOverlay.setVisible(false);
        }
        isDialogOpen = false;
    }

    private void executeSubmit() {
        if (!isActive() || isSubmitting) return;
        isSubmitting = true;
        submit.setDisable(true);
        if (countdown != null) countdown.stop();

        // Gỡ bỏ ngay lập tức các listener chống gian lận để tránh kích hoạt khi đóng cửa sổ / chuyển cảnh
        removeAntiCheatListeners();

        fx.observe(api.submitAttempt(attempt.attemptId()), this::isActive,
            navigator::showResult,
            e -> {
                if (e instanceof ApiException) {
                    fx.observe(api.getMyResult(attempt.attemptId()), this::isActive, navigator::showResult, x -> {
                        saveStatus.setText("Không thể tải kết quả. Vui lòng thử lại từ lịch sử.");
                        submit.setDisable(false);
                        isSubmitting = false;
                    }, () -> {});
                } else {
                    saveStatus.setText("Không xác nhận được việc nộp bài. Hãy kết nối lại để kiểm tra trạng thái.");
                    submit.setDisable(false);
                    isSubmitting = false;
                }
            }, () -> {});
    }

    @Override
    protected void onDispose() {
        isSubmitting = true;
        if (countdown != null) countdown.stop();

        removeAntiCheatListeners();

        Stage stage = navigator.stage();
        if (stage != null) {
            stage.setFullScreen(false);
        }
    }
}
