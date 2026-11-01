package quiz.client.ui.student;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.TilePane;
import javafx.scene.layout.VBox;
import javafx.scene.control.TextField;
import quiz.common.enums.QuestionType;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quiz.client.anticheat.ExamAntiCheatController;
import quiz.client.anticheat.ProcessBlacklistScanner.DetectedProcess;
import quiz.client.anticheat.ProcessScannerService;
import quiz.client.api.ApiException;
import quiz.client.api.QuizApi;
import quiz.client.network.PushListener;
import quiz.client.ui.SceneNavigator;
import quiz.client.ui.Screen;
import quiz.client.ui.ScreenController;
import quiz.client.util.FxAsync;
import quiz.common.dto.AntiCheatWarningDto;
import quiz.common.dto.AttemptStateDto;
import quiz.common.dto.ReportAntiCheatDto;
import quiz.common.dto.StudentQuestionDto;
import quiz.common.enums.AntiCheatEventType;
import quiz.common.protocol.MessageType;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Điều khiển màn hình làm bài thi của thí sinh:
 * - Tích hợp ExamAntiCheatController (Process Blacklist Scanner + Window/Focus Monitor + Grace Period).
 * - Cơ chế chốt chặn nộp bài đơn nhất AtomicBoolean (NFR-03).
 * - Cơ chế Safe Remediation ngay trong Fullscreen (Buộc dừng tiến trình cấm hoặc bật ân hạn 15s).
 * - Cơ chế Cưỡng chế nộp bài (Force Submit) tự động chấm điểm và điều hướng trực tiếp sang ResultController (FR-S11, BR-12, FR-T04).
 */
public final class ExamController extends ScreenController {
    private static final Logger log = LoggerFactory.getLogger(ExamController.class);

    private final QuizApi api;
    private final SceneNavigator navigator;
    private final FxAsync fx;
    private final AttemptStateDto attempt;

    @FXML private Label title, timer, saveStatus, pageIndicator;
    @FXML private ScrollPane questionsScrollPane;
    @FXML private VBox questionsContainer;
    @FXML private TilePane reviewGrid;
    @FXML private Button previous, next, submit;

    // Các thành phần Modal in-scene (tránh thoát Fullscreen hoặc mất window focus)
    @FXML private StackPane examRoot;
    @FXML private StackPane confirmOverlay;
    @FXML private StackPane warningOverlay;
    @FXML private Label warningHeader, warningDetail;
    @FXML private HBox remediationBox;
    @FXML private Button btnKillProcesses, btnGracePeriod, btnDismissWarning;

    private static final int PAGE_SIZE = 10;
    private int currentPage = 0;
    private final Map<Integer, VBox> questionCards = new HashMap<>();

    private final Map<Long, String> answers = new HashMap<>();
    private Timeline countdown;
    private final quiz.client.anticheat.StudentAntiCheatService monitoring = new quiz.client.anticheat.StudentAntiCheatService();
    private double deductedScore;
    private long seconds;
    private int index;

    // Chốt chặn trùng lặp nộp bài (NFR-03): dùng chung cho Manual Submit, Auto Submit Timer, và Force Submit
    private final AtomicBoolean isSubmitting = new AtomicBoolean(false);
    private volatile boolean isDialogOpen = false;

    // Bộ điều phối chống gian lận
    private final ExamAntiCheatController antiCheatController;
    private final List<DetectedProcess> lastDetectedProcesses = new ArrayList<>();

    public ExamController(QuizApi api, SceneNavigator navigator, FxAsync fx, AttemptStateDto attempt) {
        this(api, navigator, fx, attempt, null);
    }

    ExamController(QuizApi api, SceneNavigator navigator, FxAsync fx, AttemptStateDto attempt, ExamAntiCheatController customController) {
        this.api = api;
        this.navigator = navigator;
        this.fx = fx;
        this.attempt = attempt;
        attempt.savedAnswers().forEach((questionId, answer) ->
            answers.put(questionId, answer == null ? null : String.valueOf(answer)));
        seconds = attempt.remainingSeconds();

        if (customController != null) {
            this.antiCheatController = customController;
        } else {
            this.antiCheatController = new ExamAntiCheatController(
                new ProcessScannerService(),
                new ExamAntiCheatController.AntiCheatCallback() {
                    @Override
                    public void onReportViolation(AntiCheatEventType type, String detail) {
                        sendViolationReport(type, detail);
                    }

                    @Override
                    public void onProhibitedProcessDetected(List<DetectedProcess> newViolations, String formattedSummary) {
                        showProhibitedProcessOverlay(newViolations, formattedSummary);
                    }

                    @Override
                    public void onViolationsResolved(Set<String> resolvedApps) {
                        log.info("Thí sinh đã tắt ứng dụng cấm: {}", resolvedApps);
                        fx.run(() -> saveStatus.setText("Đã tắt ứng dụng cấm: " + String.join(", ", resolvedApps)));
                    }

                    @Override
                    public void onGracePeriodTick(int secondsRemaining) {
                        fx.run(() -> saveStatus.setText("⚠️ Thời gian ân hạn tắt ứng dụng ngoài Desktop: còn " + secondsRemaining + "s"));
                    }

                    @Override
                    public void onGracePeriodEnded() {
                        fx.run(() -> saveStatus.setText("Hết thời gian ân hạn. Hệ thống tiếp tục giám sát bài thi."));
                    }
                },
                () -> !isActive() || isSubmitting.get()
            );
        }
    }

    @FXML
    private void initialize() {
        title.setText(attempt.quizTitle());
        render();
        refreshReview();
    }

    @Override
    protected void onMount() {
        // 1. Khởi động đồng hồ đếm ngược bài thi
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

        // 2. Lắng nghe thông báo PUSH từ Server (Đăng ký trước khi bật Anti-Cheat)
        PushListener pushListener = (generation, response) -> {
            if (response.pushType() == MessageType.PUSH_FORCE_SUBMITTED) {
                fx.run(() -> {
                    if (isActive()) {
                        String reason = response.payload() instanceof String str
                            ? str
                            : "Bài thi bị cưỡng chế nộp do vi phạm quy chế thi vượt ngưỡng!";
                        handleForceSubmit(reason);
                    }
                });
            } else if (response.pushType() == MessageType.PUSH_ANTICHEAT_WARNING) {
                fx.run(() -> {
                    if (isActive() && !isSubmitting.get()) {
                        String msg = "Bạn vừa vi phạm quy chế thi!";
                        int count = 1, max = 3;
                        if (response.payload() instanceof AntiCheatWarningDto w) {
                            msg = w.message();
                            count = w.violationCount();
                            max = w.maxViolations();
                        }
                        showWarningDialog(count, max, msg);
                    }
                });
            }
        };
        own(api.addPushListener(pushListener));
        own(monitoring);
        monitoring.start(api,attempt.attemptId(),this::applyMonitoringState,
            error->{if(isActive()&&!isSubmitting.get()) saveStatus.setText("Chưa đồng bộ được giám sát: "+FxAsync.unwrap(error).getMessage());});

        // 3. Kích hoạt giám sát FullScreen & Chống gian lận (WindowFocusMonitor + ProcessScanner 10s)
        Stage stage = navigator.stage();
        if (stage != null) {
            antiCheatController.startMonitoring(stage, examRoot,Math.max(1,(api.monitoringIntervalMs()+999)/1000));
        }
    }

    private void applyMonitoringState(quiz.common.dto.ExamSession state) {
        if(!isActive()||isSubmitting.get()) return;
        seconds=state.remainingSeconds();
        updateTimer();
        if(state.forceStopped()) {
            handleForceSubmit("Bài thi đã bị kết thúc bởi giảng viên coi thi");
            return;
        }
        if(state.status()==quiz.common.enums.MonitoringStatus.SUBMITTED) { executeSubmit();return; }
        double scoreChange=state.deductedScore()-deductedScore;
        deductedScore=state.deductedScore();
        if(scoreChange>0) {
            String message="Giảng viên xử phạt: trừ "+String.format("%.2f",scoreChange)
                +" điểm. Tổng điểm trừ: "+String.format("%.2f",deductedScore);
            saveStatus.setText(message);
            warningHeader.setText("THÔNG BÁO XỬ PHẠT");warningDetail.setText(message);
            remediationBox.setVisible(false);remediationBox.setManaged(false);
            warningOverlay.setVisible(true);warningOverlay.toFront();
        }
        if(seconds==0) executeSubmit();
    }

    // ========================================================
    // XỬ LÝ CẢNH BÁO & REMEDIATION (YÊU CẦU 2 & 3)
    // ========================================================

    private void sendViolationReport(AntiCheatEventType type, String detail) {
        if (!isActive() || isSubmitting.get()) {
            return;
        }
        log.warn("Client AntiCheat ghi nhận vi phạm: type={}, detail={}", type, detail);
        ReportAntiCheatDto dto = new ReportAntiCheatDto(attempt.attemptId(), type, detail, Instant.now());
        api.reportAntiCheat(dto).exceptionally(err -> {
            log.error("Gửi sự kiện chống gian lận lên Server thất bại: {}", err.getMessage());
            return null;
        });
    }

    private void showProhibitedProcessOverlay(List<DetectedProcess> newViolations, String formattedSummary) {
        lastDetectedProcesses.clear();
        lastDetectedProcesses.addAll(newViolations);

        if (warningOverlay != null && warningHeader != null && warningDetail != null) {
            warningHeader.setText("⚠️ PHÁT HIỆN ỨNG DỤNG BỊ CẤM");
            warningDetail.setText(formattedSummary + "\n\nBạn có thể nhấn 'Buộc dừng ứng dụng vi phạm' để tắt ngay, hoặc chọn 'Tắt bên ngoài (Ân hạn 15s)'.");
            if (remediationBox != null) {
                remediationBox.setVisible(true);
                remediationBox.setManaged(true);
            }
            if (btnKillProcesses != null) {
                btnKillProcesses.setDisable(false);
            }
            isDialogOpen = true;
            warningOverlay.setVisible(true);
            warningOverlay.toFront();
        }
    }

    private void showWarningDialog(int count, int max, String msg) {
        // Quét ngay xem có ứng dụng cấm nào đang chạy trên máy không (ví dụ Zalo, Discord vừa mở ngoài Desktop)
        List<DetectedProcess> runningProhibited = antiCheatController.getProcessScannerService().scanViolations();

        if (warningOverlay != null && warningHeader != null && warningDetail != null) {
            isDialogOpen = true;
            if (!runningProhibited.isEmpty()) {
                // Có ứng dụng cấm đang chạy: kích hoạt ngay các nút remediation
                lastDetectedProcesses.clear();
                lastDetectedProcesses.addAll(runningProhibited);

                warningHeader.setText(String.format("⚠️ CẢNH BÁO VI PHẠM (LẦN %d / %d)", count, max));
                String processDetail = quiz.client.anticheat.ProcessBlacklistScanner.formatViolationDetail(runningProhibited);
                warningDetail.setText(msg + "\n\nPhát hiện ứng dụng không được phép đang chạy: " + processDetail
                    + ".\nBạn có thể nhấn 'Buộc dừng ứng dụng vi phạm' để tắt ngay, hoặc chọn 'Tắt bên ngoài (Ân hạn 15s)'.");

                if (remediationBox != null) {
                    remediationBox.setVisible(true);
                    remediationBox.setManaged(true);
                }
                if (btnKillProcesses != null) {
                    btnKillProcesses.setDisable(false);
                }
            } else {
                warningHeader.setText(String.format("⚠️ CẢNH BÁO VI PHẠM (LẦN %d / %d)", count, max));
                warningDetail.setText(msg);
                if (remediationBox != null) {
                    remediationBox.setVisible(false);
                    remediationBox.setManaged(false);
                }
            }
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

    /**
     * Nút "Buộc dừng ứng dụng vi phạm": gọi ProcessHandle.destroyForcibly() trực tiếp từ UI (Yêu cầu 3).
     */
    @FXML
    private void killProhibitedProcesses() {
        if (btnKillProcesses != null) {
            btnKillProcesses.setDisable(true);
        }
        // Quét lại danh sách tiến trình vi phạm thực tế mới nhất
        List<DetectedProcess> toKill = antiCheatController.getProcessScannerService().scanViolations();
        if (toKill.isEmpty()) {
            toKill = new ArrayList<>(lastDetectedProcesses);
        }
        antiCheatController.killProcesses(toKill).thenAccept(ignored -> {
            Platform.runLater(() -> {
                List<DetectedProcess> remaining = antiCheatController.getProcessScannerService().scanViolations();
                if (remaining.isEmpty()) {
                    saveStatus.setText("Đã buộc dừng các ứng dụng cấm thành công.");
                    dismissWarning();
                } else {
                    String remainingDetail = quiz.client.anticheat.ProcessBlacklistScanner.formatViolationDetail(remaining);
                    warningDetail.setText("Không thể tự động dừng một số tiến trình: " + remainingDetail
                        + " (thiếu quyền Admin).\nVui lòng chọn 'Tắt bên ngoài (Ân hạn 15s)' để tắt thủ công.");
                    if (btnKillProcesses != null) {
                        btnKillProcesses.setDisable(false);
                    }
                }
            });
        });
    }

    /**
     * Nút "Tắt bên ngoài (Ân hạn 15s)": bật thời gian ân hạn 15s cho phép ra Desktop mà không bị phạt (Yêu cầu 3).
     */
    @FXML
    private void requestGracePeriod() {
        dismissWarning();
        antiCheatController.startGracePeriod(15, () -> {
            log.info("Thời gian ân hạn 15s kết thúc, đã bật lại Fullscreen");
        });
    }

    @FXML
    private void dismissWarning() {
        if (warningOverlay != null) {
            warningOverlay.setVisible(false);
        }
        if (remediationBox != null) {
            remediationBox.setVisible(false);
            remediationBox.setManaged(false);
        }
        isDialogOpen = false;
    }

    // =========================================================================
    // 4. LUỒNG THU HỒI BÀI THI VÀ ĐƯA HỌC VIÊN RA KHỎI MÀN HÌNH THI
    // =========================================================================

    /**
     * Phương thức cưỡng chế nộp bài (Force Submit):
     * - Chốt chặn trùng lặp bằng AtomicBoolean (NFR-03).
     * - Dọn dẹp tài nguyên giám sát ngay lập tức (dừng Scanner, gỡ Window listeners, dừng Timer).
     * - Server đã tự nộp bài trước khi gửi PUSH_FORCE_SUBMITTED.
     * - Chuyển ngay về không gian học tập để học viên không tiếp tục ở màn hình thi.
     */
    public void handleForceSubmit(String violationReason) {
        if (!isSubmitting.compareAndSet(false, true)) {
            return;
        }
        log.warn("Kích hoạt cưỡng chế nộp bài (Force Submit): {}", violationReason);
        monitoring.close();
        if (questionsContainer != null) questionsContainer.setDisable(true);
        previous.setDisable(true);next.setDisable(true);reviewGrid.setDisable(true);

        // 1. Dọn dẹp tài nguyên giám sát ngay lập tức
        if (countdown != null) {
            countdown.stop();
        }
        if (antiCheatController != null) {
            antiCheatController.stopMonitoring();
        }
        submit.setDisable(true);

        // 2. Tắt chế độ toàn màn hình và đóng các hộp thoại trên UI thread
        log.warn("Đưa học viên ra khỏi bài thi đã bị thu hồi: {}", violationReason);
        fx.run(() -> {
            Stage stage = navigator.stage();
            if (stage != null) {
                stage.setFullScreen(false);
            }
            if (warningOverlay != null) warningOverlay.setVisible(false);
            if (confirmOverlay != null) confirmOverlay.setVisible(false);
            isDialogOpen = false;
            // Server đã chốt lượt thi và lưu kết quả trước khi đẩy sự kiện này.
            navigator.show(Screen.STUDENT_DASHBOARD);
        });
    }

    /**
     * Nộp bài bình thường (thí sinh tự bấm nộp hoặc hết thời gian làm bài).
     */
    private void executeSubmit() {
        if (!isSubmitting.compareAndSet(false, true)) {
            return;
        }
        submit.setDisable(true);
        if (countdown != null) countdown.stop();

        // Gỡ bỏ ngay lập tức các listener chống gian lận
        if (antiCheatController != null) {
            antiCheatController.stopMonitoring();
        }

        fx.run(() -> {
            Stage stage = navigator.stage();
            if (stage != null) {
                stage.setFullScreen(false);
            }
            if (confirmOverlay != null) confirmOverlay.setVisible(false);
            if (warningOverlay != null) warningOverlay.setVisible(false);
            isDialogOpen = false;
        });

        fx.observe(api.submitAttempt(attempt.attemptId()), this::isActive,
            navigator::showResult,
            e -> {
                if (e instanceof ApiException) {
                    fx.observe(api.getMyResult(attempt.attemptId()), this::isActive, navigator::showResult, x -> {
                        saveStatus.setText("Không thể tải kết quả. Vui lòng thử lại từ lịch sử.");
                        submit.setDisable(false);
                        isSubmitting.set(false);
                    }, () -> {});
                } else {
                    saveStatus.setText("Không xác nhận được việc nộp bài. Hãy kết nối lại để kiểm tra trạng thái.");
                    submit.setDisable(false);
                    isSubmitting.set(false);
                }
            }, () -> {});
    }

    // ==========================================
    // CÁC HÀM ĐIỀU HƯỚNG & LÀM BÀI THI (10 CÂU MỖI TRANG)
    // ==========================================

    private StudentQuestionDto current() {
        if (attempt.questions().isEmpty()) return null;
        int safeIndex = Math.min(Math.max(0, index), attempt.questions().size() - 1);
        return attempt.questions().get(safeIndex);
    }

    private void render() {
        if (attempt.questions().isEmpty()) {
            if (questionsContainer != null) {
                questionsContainer.getChildren().clear();
                Label emptyLbl = new Label("Bài kiểm tra không có câu hỏi.");
                emptyLbl.setStyle("-fx-font-size: 15px; -fx-text-fill: #64748b;");
                questionsContainer.getChildren().add(emptyLbl);
            }
            if (pageIndicator != null) pageIndicator.setText("Trang 0 / 0");
            if (previous != null) previous.setDisable(true);
            if (next != null) next.setDisable(true);
            return;
        }

        int total = attempt.questions().size();
        int totalPages = Math.max(1, (int) Math.ceil((double) total / PAGE_SIZE));
        if (currentPage >= totalPages) currentPage = totalPages - 1;
        if (currentPage < 0) currentPage = 0;

        int startIdx = currentPage * PAGE_SIZE;
        int endIdx = Math.min(startIdx + PAGE_SIZE, total);

        if (pageIndicator != null) {
            pageIndicator.setText("Trang " + (currentPage + 1) + " / " + totalPages + " (Câu " + (startIdx + 1) + " - " + endIdx + ")");
        }
        if (previous != null) previous.setDisable(currentPage == 0);
        if (next != null) next.setDisable(currentPage >= totalPages - 1);

        if (questionsContainer != null) {
            questionsContainer.getChildren().clear();
            questionCards.clear();

            for (int i = startIdx; i < endIdx; i++) {
                StudentQuestionDto q = attempt.questions().get(i);
                VBox card = buildQuestionCard(i, q, total);
                questionCards.put(i, card);
                questionsContainer.getChildren().add(card);
            }
        }

        refreshReview();
    }

    private VBox buildQuestionCard(int qIndex, StudentQuestionDto q, int total) {
        VBox card = new VBox(10);
        card.setId("question-card-" + qIndex);
        card.setStyle("-fx-background-color: #f8fafc; -fx-border-color: #e2e8f0; -fx-border-width: 1.5px; -fx-border-radius: 8px; -fx-background-radius: 8px; -fx-padding: 14px 18px;");

        Label lblPos = new Label("Câu " + (qIndex + 1) + " / " + total);
        lblPos.setStyle("-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: #4338ca;");

        Label lblContent = new Label(q.content());
        lblContent.setWrapText(true);
        lblContent.setMaxWidth(Double.MAX_VALUE);
        lblContent.setStyle("-fx-font-size: 14.5px; -fx-font-weight: 600; -fx-text-fill: #1e293b; -fx-line-spacing: 3px;");

        VBox answerBox = new VBox(8);
        answerBox.setStyle("-fx-padding: 4 0 0 0;");
        renderAnswerInput(q, answerBox);

        card.getChildren().addAll(lblPos, lblContent, answerBox);
        return card;
    }

    private void renderAnswerInput(StudentQuestionDto q, VBox answerBox) {
        String saved = answers.get(q.id());
        if (q.questionType() == QuestionType.MULTIPLE_CHOICE || q.questionType() == QuestionType.TRUE_FALSE) {
            ToggleGroup tg = new ToggleGroup();
            if (q.questionType() == QuestionType.TRUE_FALSE) {
                RadioButton rTrue = new RadioButton("Đúng");
                rTrue.setUserData("TRUE");
                rTrue.setToggleGroup(tg);
                rTrue.setWrapText(true);
                rTrue.setMaxWidth(Double.MAX_VALUE);

                RadioButton rFalse = new RadioButton("Sai");
                rFalse.setUserData("FALSE");
                rFalse.setToggleGroup(tg);
                rFalse.setWrapText(true);
                rFalse.setMaxWidth(Double.MAX_VALUE);

                if ("TRUE".equalsIgnoreCase(saved)) tg.selectToggle(rTrue);
                else if ("FALSE".equalsIgnoreCase(saved)) tg.selectToggle(rFalse);

                answerBox.getChildren().addAll(rTrue, rFalse);
            } else {
                List<RadioButton> radios = new ArrayList<>();
                if (q.optionA() != null && !q.optionA().isBlank()) {
                    RadioButton r = new RadioButton("A. " + q.optionA());
                    r.setUserData("A");
                    radios.add(r);
                }
                if (q.optionB() != null && !q.optionB().isBlank()) {
                    RadioButton r = new RadioButton("B. " + q.optionB());
                    r.setUserData("B");
                    radios.add(r);
                }
                if (q.optionC() != null && !q.optionC().isBlank()) {
                    RadioButton r = new RadioButton("C. " + q.optionC());
                    r.setUserData("C");
                    radios.add(r);
                }
                if (q.optionD() != null && !q.optionD().isBlank()) {
                    RadioButton r = new RadioButton("D. " + q.optionD());
                    r.setUserData("D");
                    radios.add(r);
                }
                for (RadioButton r : radios) {
                    r.setToggleGroup(tg);
                    r.setWrapText(true);
                    r.setMaxWidth(Double.MAX_VALUE);
                    if (r.getUserData().equals(saved)) {
                        tg.selectToggle(r);
                    }
                    answerBox.getChildren().add(r);
                }
            }
            tg.selectedToggleProperty().addListener((obs, oldT, newT) -> {
                if (newT != null && isActive() && !isSubmitting.get()) {
                    saveAnswer(q.id(), String.valueOf(newT.getUserData()));
                }
            });
        } else {
            String[] values = saved == null ? new String[0] : saved.split("\\|", -1);
            int count = q.questionType() == QuestionType.FILL_BLANK ? Math.max(1, q.blankCount()) : 1;
            List<TextField> fields = new ArrayList<>();
            for (int k = 0; k < count; k++) {
                TextField field = new TextField(k < values.length ? values[k].strip() : "");
                field.setPromptText(q.questionType() == QuestionType.FILL_BLANK ? "Từ cho ô trống " + (k + 1) : "Nhập câu trả lời ngắn");
                field.setStyle("-fx-font-size: 13.5px; -fx-padding: 7 10;");
                fields.add(field);
                answerBox.getChildren().add(field);
            }
            for (TextField f : fields) {
                f.textProperty().addListener((o, old, value) -> {
                    if (!isActive() || isSubmitting.get()) return;
                    String combined = fields.stream().map(TextField::getText).reduce((a, b) -> a + " | " + b).orElse("");
                    saveAnswer(q.id(), combined);
                });
            }
        }
    }

    private void saveAnswer(long questionId, String answer) {
        answers.put(questionId, answer);
        if (saveStatus != null) saveStatus.setText("Đang lưu câu trả lời...");
        fx.observe(api.saveAnswer(attempt.attemptId(), questionId, answer), this::isActive,
            ignored -> { if (saveStatus != null) saveStatus.setText(""); refreshReview(); },
            e -> { if (saveStatus != null) saveStatus.setText("Không lưu được đáp án: " + e.getMessage()); }, () -> {});
    }

    @FXML
    private void selectAnswer() {
        // Handled directly via toggleGroup listener in each question card
    }

    @FXML
    private void previous() {
        if (currentPage > 0) {
            currentPage--;
            render();
            scrollToTopQuestions();
        }
    }

    @FXML
    private void next() {
        int totalPages = Math.max(1, (int) Math.ceil((double) attempt.questions().size() / PAGE_SIZE));
        if (currentPage < totalPages - 1) {
            currentPage++;
            render();
            scrollToTopQuestions();
        }
    }

    private void scrollToTopQuestions() {
        javafx.application.Platform.runLater(() -> {
            if (questionsScrollPane != null) {
                questionsScrollPane.setVvalue(0.0);
            }
        });
    }

    private void updateTimer() {
        long min = seconds / 60;
        long sec = seconds % 60;
        timer.setText(String.format("%02d:%02d", min, sec));
        if (seconds <= 60) {
            timer.setStyle("-fx-text-fill: #dc2626; -fx-font-weight: bold; -fx-font-size: 16px;");
        } else {
            timer.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        }
    }

    private void refreshReview() {
        if (reviewGrid == null) return;
        reviewGrid.getChildren().clear();
        int startIdx = currentPage * PAGE_SIZE;
        int endIdx = Math.min(startIdx + PAGE_SIZE, attempt.questions().size());

        for (int i = 0; i < attempt.questions().size(); i++) {
            StudentQuestionDto q = attempt.questions().get(i);
            Button b = new Button(String.valueOf(i + 1));
            b.setMinSize(48, 42);
            b.setPrefSize(48, 42);
            b.setMaxSize(48, 42);
            b.getStyleClass().add("question-index-button");

            boolean isCurrentPage = (i >= startIdx && i < endIdx);
            boolean isAnswered = answers.containsKey(q.id()) && !answers.get(q.id()).isBlank();

            if (isAnswered) {
                b.getStyleClass().add("question-index-answered");
            } else {
                b.getStyleClass().add("question-index-unanswered");
            }
            if (isCurrentPage) {
                b.getStyleClass().add("question-index-current");
            }

            int targetIndex = i;
            b.setOnAction(e -> jumpToQuestion(targetIndex));
            reviewGrid.getChildren().add(b);
        }
    }

    private void jumpToQuestion(int targetIndex) {
        index = targetIndex;
        int targetPage = targetIndex / PAGE_SIZE;
        if (targetPage != currentPage) {
            currentPage = targetPage;
            render();
        } else {
            refreshReview();
        }

        javafx.application.Platform.runLater(() -> {
            VBox card = questionCards.get(targetIndex);
            if (card != null && questionsScrollPane != null) {
                double cardY = card.getBoundsInParent().getMinY();
                double containerHeight = questionsContainer.getBoundsInLocal().getHeight();
                if (containerHeight > 0) {
                    questionsScrollPane.setVvalue(Math.max(0.0, Math.min(1.0, cardY / containerHeight)));
                }
                card.setStyle("-fx-background-color: #f0fdf4; -fx-border-color: #4f46e5; -fx-border-width: 2px; -fx-border-radius: 8px; -fx-background-radius: 8px; -fx-padding: 14px 18px;");
            }
        });
    }

    @FXML
    private void manualSubmit() {
        if (!isActive() || isSubmitting.get() || submit.isDisabled()) return;
        if (confirmOverlay != null) {
            isDialogOpen = true;
            confirmOverlay.setVisible(true);
            confirmOverlay.toFront();
        } else {
            isDialogOpen = true;
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, "Bạn có chắc chắn muốn nộp bài không?", ButtonType.YES, ButtonType.NO);
            confirm.setTitle("Xác nhận nộp bài");
            confirm.setHeaderText("Nộp bài thi");
            if (navigator.stage() != null) confirm.initOwner(navigator.stage());
            confirm.showAndWait().ifPresent(ans -> {
                isDialogOpen = false;
                if (ans == ButtonType.YES) executeSubmit();
            });
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

    @Override
    protected void onDispose() {
        isSubmitting.set(true);
        if (countdown != null) countdown.stop();

        if (antiCheatController != null) {
            antiCheatController.stopMonitoring();
        }

        Stage stage = navigator.stage();
        if (stage != null) {
            stage.setFullScreen(false);
        }
    }
}
