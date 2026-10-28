package quiz.client.ui.student;

import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import quiz.client.api.QuizApi;
import quiz.client.api.ApiException;
import quiz.client.ui.SceneNavigator;
import quiz.client.ui.ScreenController;
import quiz.client.util.FxAsync;
import quiz.common.dto.QuizInfoDto;
import quiz.common.dto.ClassDto;
import quiz.common.dto.ResultDto;
import quiz.common.protocol.ErrorCode;
import quiz.common.enums.QuizStatus;
import javafx.application.Platform;
import quiz.client.anticheat.ProcessBlacklistScanner.DetectedProcess;
import quiz.client.anticheat.ProcessScannerService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.stream.Collectors;

/**
 * Controller cho Học viên xem các bài kiểm tra được giao (FR-S02) và thông tin bài kiểm tra (FR-S04).
 */
public final class StudentController extends ScreenController {
    private final QuizApi api;
    private final SceneNavigator navigator;
    private final FxAsync fx;

    @FXML private TableView<QuizInfoDto> assignedQuizTable;
    @FXML private Label studentWelcome;
    @FXML private Label studentClassLabel;
    @FXML private Label studentNotice;
    @FXML private ProgressIndicator studentProgress;
    @FXML private Button btnRefresh;
    @FXML private HBox actionToolbar;

    // Quiz Info Details Panel (FR-S04)
    @FXML private VBox quizInfoPanel;
    @FXML private Label infoTitle;
    @FXML private Label infoCategory;
    @FXML private Label infoDuration;
    @FXML private Label infoPassScore;
    @FXML private Label infoQuestionCount;
    @FXML private Label infoStatus;
    @FXML private Label infoDescription;
    @FXML private Button btnCloseInfo;
    @FXML private Button btnStartExam;

    private final ObservableList<QuizInfoDto> quizList = FXCollections.observableArrayList();
    private final Set<Long> completedQuizIds = new HashSet<>();
    private boolean historyLoaded;

    private final ProcessScannerService preExamScanner;

    public StudentController(QuizApi api, SceneNavigator navigator, FxAsync fx) {
        this(api, navigator, fx, new ProcessScannerService());
    }

    StudentController(QuizApi api, SceneNavigator navigator, FxAsync fx, ProcessScannerService preExamScanner) {
        this.api = Objects.requireNonNull(api);
        this.navigator = Objects.requireNonNull(navigator);
        this.fx = Objects.requireNonNull(fx);
        this.preExamScanner = Objects.requireNonNull(preExamScanner, "preExamScanner cannot be null");
    }

    @FXML
    private void initialize() {
        configureTable();
        assignedQuizTable.getSelectionModel().selectedItemProperty()
            .addListener((obs, oldVal, selected) -> {
                if (selected != null) {
                    showQuizInfo(selected);
                    assignedQuizTable.setVisible(false);
                    assignedQuizTable.setManaged(false);
                    actionToolbar.setVisible(false);
                    actionToolbar.setManaged(false);
                } else {
                    hideQuizInfo();
                    assignedQuizTable.setVisible(true);
                    assignedQuizTable.setManaged(true);
                    actionToolbar.setVisible(true);
                    actionToolbar.setManaged(true);
                }
            });
        quizInfoPanel.setVisible(false);
        quizInfoPanel.setManaged(false);

        api.session().current().ifPresent(snap -> {
            studentWelcome.setText("Xin chào, " + snap.fullName() + "!");
        });
    }

    @Override
    protected void onMount() {
        loadAssignedQuizzes();
        fx.observe(api.listMyHistory(), this::isActive,
            results -> {
                completedQuizIds.clear();
                results.stream().map(ResultDto::quizId).forEach(completedQuizIds::add);
                historyLoaded = true;
                QuizInfoDto selected = assignedQuizTable.getSelectionModel().getSelectedItem();
                if (selected != null) showQuizInfo(selected);
            },
            err -> {
                historyLoaded = true;
                QuizInfoDto selected = assignedQuizTable.getSelectionModel().getSelectedItem();
                if (selected != null) showQuizInfo(selected);
            },
            () -> { historyLoaded = true; });
        fx.observe(api.listMyClasses(), this::isActive,
            classes -> studentClassLabel.setText("Lớp: " + (classes.isEmpty() ? "chưa được xếp lớp" : String.join(", ", classes.stream().map(ClassDto::name).toList()))),
            err -> studentClassLabel.setText("Không tải được thông tin lớp học"),
            () -> {});
    }

    private void configureTable() {
        TableColumn<QuizInfoDto, String> colTitle = new TableColumn<>("Tiêu đề bài kiểm tra");
        colTitle.setPrefWidth(260);
        colTitle.setCellValueFactory(q -> new SimpleStringProperty(q.getValue().title()));

        TableColumn<QuizInfoDto, String> colCategory = new TableColumn<>("Chủ đề");
        colCategory.setPrefWidth(130);
        colCategory.setCellValueFactory(q -> new SimpleStringProperty(q.getValue().category() == null ? "—" : q.getValue().category()));

        TableColumn<QuizInfoDto, String> colDuration = new TableColumn<>("Thời lượng");
        colDuration.setPrefWidth(100);
        colDuration.setCellValueFactory(q -> new SimpleStringProperty(q.getValue().durationMinutes() + " phút"));

        TableColumn<QuizInfoDto, String> colQuestions = new TableColumn<>("Số câu hỏi");
        colQuestions.setPrefWidth(90);
        colQuestions.setCellValueFactory(q -> new SimpleStringProperty(q.getValue().questionCount() + " câu"));

        TableColumn<QuizInfoDto, String> colPass = new TableColumn<>("Điểm đạt");
        colPass.setPrefWidth(85);
        colPass.setCellValueFactory(q -> new SimpleStringProperty(String.format("%.0f%%", q.getValue().passScore())));

        TableColumn<QuizInfoDto, String> colStatus = new TableColumn<>("Trạng thái");
        colStatus.setPrefWidth(120);
        colStatus.setCellValueFactory(q -> new SimpleStringProperty(q.getValue().status().name()));
        colStatus.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    Label badge = new Label();
                    badge.getStyleClass().add("badge");
                    if ("OPEN".equals(item)) {
                        badge.setText("SẴN SÀNG THI");
                        badge.getStyleClass().add("badge-open");
                    } else if ("CLOSED".equals(item)) {
                        badge.setText("ĐÃ KẾT THÚC");
                        badge.getStyleClass().add("badge-closed");
                    } else {
                        badge.setText("BẢN NHÁP");
                        badge.getStyleClass().add("badge-draft");
                    }
                    setGraphic(badge);
                    setText(null);
                }
            }
        });

        assignedQuizTable.getColumns().setAll(List.of(colTitle, colCategory, colDuration, colQuestions, colPass, colStatus));
        assignedQuizTable.setItems(quizList);
        assignedQuizTable.setPlaceholder(new Label("Hiện tại bạn chưa có bài kiểm tra nào được giao."));
    }

    @FXML
    private void loadAssignedQuizzes() {
        studentProgress.setVisible(true);
        btnRefresh.setDisable(true);
        fx.observe(api.listAssignedQuizzes(), this::isActive,
            quizzes -> {
                quizList.setAll(quizzes);
                studentProgress.setVisible(false);
                btnRefresh.setDisable(false);
            },
            err -> {
                showNotice("Lỗi khi tải danh sách bài thi: " + err.getMessage(), true);
                studentProgress.setVisible(false);
                btnRefresh.setDisable(false);
            },
            () -> {
                studentProgress.setVisible(false);
                btnRefresh.setDisable(false);
            }
        );
    }

    private void showQuizInfo(QuizInfoDto sel) {
        infoTitle.setText(sel.title());
        infoCategory.setText(sel.category() != null ? sel.category() : "Tiếng Anh");
        infoDuration.setText(sel.durationMinutes() + " phút");
        infoPassScore.setText(String.format("%.0f%%", sel.passScore()));
        infoQuestionCount.setText(sel.questionCount() + " câu hỏi");
        infoStatus.setText(sel.status() == QuizStatus.OPEN ? "Đang mở thi" : (sel.status() == QuizStatus.CLOSED ? "Đã đóng" : "Chưa mở"));
        boolean alreadySubmitted = completedQuizIds.contains(sel.id());
        btnStartExam.setDisable(!historyLoaded || sel.status() != QuizStatus.OPEN || alreadySubmitted);
        if (alreadySubmitted) {
            showNotice("Bạn đã hoàn thành bài kiểm tra này trước đó nên không thể làm lại.", true);
        } else if (!historyLoaded) {
            showNotice("Đang kiểm tra lịch sử làm bài…", false);
        } else if (studentNotice.getText() != null && studentNotice.getText().startsWith("Bạn đã hoàn thành bài kiểm tra này")) {
            studentNotice.setVisible(false);
            studentNotice.setManaged(false);
        }
        infoDescription.setText(sel.description() != null && !sel.description().isBlank() ? sel.description() : "Không có mô tả chi tiết.");
        quizInfoPanel.setVisible(true);
        quizInfoPanel.setManaged(true);
    }

    @FXML
    private void onCloseInfoClick() {
        assignedQuizTable.getSelectionModel().clearSelection();
        hideQuizInfo();
    }

    private void hideQuizInfo() {
        quizInfoPanel.setVisible(false);
        quizInfoPanel.setManaged(false);
    }

    @FXML private void onHistoryClick() { navigator.show(quiz.client.ui.Screen.STUDENT_HISTORY); }

    @FXML
    private void onStartExamClick() {
        QuizInfoDto selected = assignedQuizTable.getSelectionModel().getSelectedItem();
        if (selected == null || selected.status() != QuizStatus.OPEN) return;
        if (completedQuizIds.contains(selected.id())) {
            showNotice("Bạn đã hoàn thành bài kiểm tra này trước đó nên không thể làm lại.", true);
            return;
        }
        String pin = null;
        if (selected.pinRequired()) {
            TextInputDialog dialog = new TextInputDialog();
            dialog.setTitle("Tham gia bài kiểm tra"); dialog.setHeaderText("Nhập mã PIN");
            dialog.setContentText("Mã PIN:");
            var entered = dialog.showAndWait();
            if (entered.isEmpty()) return;
            pin = entered.get().strip();
        }
        final String code = pin;
        btnStartExam.setDisable(true);

        // Pre-Exam Environment Check (FR-S13 nâng cấp, BR-13, NFR-09)
        checkPreExamEnvironmentThenStart(selected.id(), code);
    }

    private void checkPreExamEnvironmentThenStart(long quizId, String code) {
        showNotice("Đang kiểm tra môi trường thi...", false);
        preExamScanner.scanViolationsAsync().thenAccept(violations -> {
            Platform.runLater(() -> {
                if (!isActive()) {
                    btnStartExam.setDisable(false);
                    return;
                }
                if (violations.isEmpty()) {
                    proceedStartExam(quizId, code);
                } else {
                    btnStartExam.setDisable(false);
                    showPreExamViolationDialog(quizId, code, violations);
                }
            });
        }).exceptionally(err -> {
            Platform.runLater(() -> {
                proceedStartExam(quizId, code);
            });
            return null;
        });
    }

    private void showPreExamViolationDialog(long quizId, String code, List<DetectedProcess> initialViolations) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle("Kiểm tra môi trường thi");
        alert.setHeaderText("Phát hiện ứng dụng không được phép đang chạy");
        if (navigator.stage() != null) {
            alert.initOwner(navigator.stage());
        }

        // Gom nhóm theo tên ứng dụng để tránh spam nhiều dòng khi một ứng dụng mở nhiều tiến trình
        Map<String, List<DetectedProcess>> grouped = initialViolations.stream()
            .collect(Collectors.groupingBy(DetectedProcess::appName, LinkedHashMap::new, Collectors.toList()));

        String appList = grouped.entrySet().stream()
            .map(entry -> {
                String name = entry.getKey();
                List<DetectedProcess> procs = entry.getValue();
                String cat = procs.get(0).category();
                String pids = procs.stream().map(p -> String.valueOf(p.pid())).collect(Collectors.joining(", "));
                if (procs.size() > 1) {
                    return String.format("• %s (%s) — %d tiến trình (PID: %s)", name, cat, procs.size(), pids);
                } else {
                    return String.format("• %s (%s) — PID: %s", name, cat, pids);
                }
            })
            .collect(Collectors.joining("\n"));

        alert.setContentText("Các ứng dụng sau bị cấm trong phòng thi:\n\n" + appList
            + "\n\nVui lòng tắt toàn bộ ứng dụng trên trước khi bắt đầu làm bài.");

        ButtonType btnAutoClose = new ButtonType("Tự động đóng", ButtonBar.ButtonData.OK_DONE);
        ButtonType btnRecheck = new ButtonType("Kiểm tra lại", ButtonBar.ButtonData.OTHER);
        ButtonType btnCancel = new ButtonType("Hủy bỏ", ButtonBar.ButtonData.CANCEL_CLOSE);

        alert.getButtonTypes().setAll(btnAutoClose, btnRecheck, btnCancel);

        DialogPane dialogPane = alert.getDialogPane();
        var cssUrl = getClass().getResource("/quiz/client/ui/app.css");
        if (cssUrl != null) {
            dialogPane.getStylesheets().add(cssUrl.toExternalForm());
        }
        dialogPane.setMinWidth(520);
        dialogPane.setPrefWidth(540);

        // Khóa thứ tự hiển thị nút cố định theo đúng danh sách khai báo: [Tự động đóng] [Kiểm tra lại] [Hủy bỏ]
        ButtonBar bar = findButtonBar(dialogPane);
        if (bar != null) {
            bar.setButtonOrder(ButtonBar.BUTTON_ORDER_NONE);
        }

        // Tắt uniform size và đặt kích thước rộng rãi để tuyệt đối không bị co chữ thành "Tự động đó..."
        Button autoCloseBtn = (Button) dialogPane.lookupButton(btnAutoClose);
        if (autoCloseBtn != null) {
            ButtonBar.setButtonUniformSize(autoCloseBtn, false);
            autoCloseBtn.setMinWidth(140);
            autoCloseBtn.setPrefWidth(140);
            autoCloseBtn.setStyle("-fx-background-color: #2563eb; -fx-text-fill: white; -fx-font-weight: bold; -fx-cursor: hand; -fx-padding: 6 12; -fx-min-width: 140px; -fx-pref-width: 140px;");
        }
        Button recheckBtn = (Button) dialogPane.lookupButton(btnRecheck);
        if (recheckBtn != null) {
            ButtonBar.setButtonUniformSize(recheckBtn, false);
            recheckBtn.setMinWidth(110);
            recheckBtn.setPrefWidth(110);
            recheckBtn.setStyle("-fx-cursor: hand; -fx-padding: 6 12; -fx-min-width: 110px; -fx-pref-width: 110px;");
        }
        Button cancelBtn = (Button) dialogPane.lookupButton(btnCancel);
        if (cancelBtn != null) {
            ButtonBar.setButtonUniformSize(cancelBtn, false);
            cancelBtn.setMinWidth(85);
            cancelBtn.setPrefWidth(85);
            cancelBtn.setStyle("-fx-cursor: hand; -fx-padding: 6 12; -fx-min-width: 85px; -fx-pref-width: 85px;");
        }

        var result = alert.showAndWait();
        if (result.isPresent()) {
            if (result.get() == btnAutoClose) {
                showNotice("Đang tự động đóng các ứng dụng vi phạm...", false);
                btnStartExam.setDisable(true);
                preExamScanner.terminateProcessesAsync(initialViolations).thenAccept(ignored -> {
                    preExamScanner.scanViolationsAsync().thenAccept(remaining -> {
                        Platform.runLater(() -> {
                            btnStartExam.setDisable(false);
                            if (remaining.isEmpty()) {
                                showNotice("Môi trường thi an toàn! Đang vào bài thi...", false);
                                proceedStartExam(quizId, code);
                            } else {
                                showNotice("Một số ứng dụng không thể đóng tự động (thiếu quyền Admin). Vui lòng tắt thủ công.", true);
                                showPreExamViolationDialog(quizId, code, remaining);
                            }
                        });
                    });
                });
            } else if (result.get() == btnRecheck) {
                checkPreExamEnvironmentThenStart(quizId, code);
            } else {
                showNotice("Đã hủy vào bài thi do chưa tắt ứng dụng cấm.", true);
            }
        }
    }

    private ButtonBar findButtonBar(Parent parent) {
        for (Node child : parent.getChildrenUnmodifiable()) {
            if (child instanceof ButtonBar bar) {
                return bar;
            }
            if (child instanceof Parent p) {
                ButtonBar bar = findButtonBar(p);
                if (bar != null) return bar;
            }
        }
        return null;
    }

    private void proceedStartExam(long quizId, String code) {
        btnStartExam.setDisable(true);
        fx.observe(api.startAttempt(quizId, code), this::isActive,
            navigator::showExam,
            err -> {
                showNotice("Không thể bắt đầu bài thi: " + err.getMessage(), true);
                btnStartExam.setDisable(false);
            },
            () -> btnStartExam.setDisable(false));
    }

    @Override
    protected void onDispose() {
        preExamScanner.stop();
    }

    private void showNotice(String msg, boolean isError) {
        studentNotice.setText(msg);
        studentNotice.getStyleClass().removeAll("notice", "error-notice");
        studentNotice.getStyleClass().add(isError ? "error-notice" : "notice");
        studentNotice.setVisible(true);
        studentNotice.setManaged(true);
    }
}
