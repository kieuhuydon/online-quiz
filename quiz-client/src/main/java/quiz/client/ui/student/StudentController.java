package quiz.client.ui.student;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import quiz.client.api.QuizApi;
import quiz.client.ui.SceneNavigator;
import quiz.client.ui.ScreenController;
import quiz.client.util.FxAsync;
import quiz.common.dto.QuizInfoDto;
import quiz.common.dto.ClassDto;
import quiz.common.dto.ResultDto;
import quiz.common.enums.QuizStatus;
import javafx.application.Platform;
import quiz.client.anticheat.ProcessBlacklistScanner.DetectedProcess;
import quiz.client.anticheat.ProcessScannerService;
import java.util.HashMap;
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

    // KPI Summary Labels
    @FXML private Label lblTotalAssigned;
    @FXML private Label lblReadyExams;
    @FXML private Label lblCompletedExams;

    // Quiz Info Details Panel (FR-S04)
    @FXML private VBox quizInfoPanel;
    @FXML private Label infoTitle;
    @FXML private Label infoCategory;
    @FXML private Label infoDuration;
    @FXML private Label infoPassScore;
    @FXML private Label infoQuestionCount;
    @FXML private Label infoStatus;
    @FXML private Label infoDescription;
    @FXML private Label infoDescriptionLabel;
    @FXML private Button btnCloseInfo;
    @FXML private Button btnStartExam;

    private final ObservableList<QuizInfoDto> quizList = FXCollections.observableArrayList();
    private final Set<Long> completedQuizIds = new HashSet<>();
    private final Map<Long, ResultDto> completedResultsByQuizId = new HashMap<>();
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
                } else {
                    hideQuizInfo();
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
        Timeline autoSync = new Timeline(new KeyFrame(Duration.seconds(3), e -> syncSilently()));
        autoSync.setCycleCount(Animation.INDEFINITE);
        autoSync.play();
        own(autoSync::stop);
    }

    private void loadHistoryAndClasses() {
        fx.observe(api.listMyHistory(), this::isActive,
            results -> {
                completedQuizIds.clear();
                completedResultsByQuizId.clear();
                for (ResultDto r : results) {
                    completedQuizIds.add(r.quizId());
                    completedResultsByQuizId.put(r.quizId(), r);
                }
                historyLoaded = true;
                assignedQuizTable.refresh();
                updateStudentKpis();
                QuizInfoDto selected = assignedQuizTable.getSelectionModel().getSelectedItem();
                if (selected != null) showQuizInfo(selected);
            },
            err -> {
                historyLoaded = true;
                updateStudentKpis();
                QuizInfoDto selected = assignedQuizTable.getSelectionModel().getSelectedItem();
                if (selected != null) showQuizInfo(selected);
            },
            () -> { historyLoaded = true; });
        fx.observe(api.listMyClasses(), this::isActive,
            classes -> studentClassLabel.setText("Lớp học: " + (classes.isEmpty() ? "Chưa được xếp lớp" : String.join(", ", classes.stream().map(ClassDto::name).toList()))),
            err -> studentClassLabel.setText("Không tải được thông tin lớp học"),
            () -> {});
    }

    private void updateStudentKpis() {
        int total = quizList.size();
        long completed = quizList.stream().filter(q -> completedQuizIds.contains(q.id())).count();
        long ready = quizList.stream().filter(q -> q.status() == QuizStatus.OPEN && !completedQuizIds.contains(q.id())).count();
        if (lblTotalAssigned != null) lblTotalAssigned.setText(String.valueOf(total));
        if (lblReadyExams != null) lblReadyExams.setText(String.valueOf(ready));
        if (lblCompletedExams != null) lblCompletedExams.setText(String.valueOf(completed));
    }

    private void syncSilently() {
        if (!isActive() || studentProgress.isVisible()) return;
        loadHistoryAndClasses();
        fx.observe(api.listAssignedQuizzes(), this::isActive,
            this::updateQuizItemsPreservingSelection,
            err -> {},
            () -> {}
        );
    }

    private void updateQuizItemsPreservingSelection(List<QuizInfoDto> quizzes) {
        QuizInfoDto currentSelected = assignedQuizTable.getSelectionModel().getSelectedItem();
        Long selectedId = currentSelected != null ? currentSelected.id() : null;
        quizList.setAll(quizzes);
        updateStudentKpis();
        if (selectedId != null) {
            quizzes.stream()
                .filter(q -> q.id() == selectedId)
                .findFirst()
                .ifPresent(updated -> {
                    assignedQuizTable.getSelectionModel().select(updated);
                    showQuizInfo(updated);
                });
        }
    }

    private void configureTable() {
        TableColumn<QuizInfoDto, String> colTitle = new TableColumn<>("Tiêu đề bài kiểm tra");
        colTitle.setPrefWidth(250);
        colTitle.setCellValueFactory(q -> new SimpleStringProperty(q.getValue().title()));
        colTitle.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setTooltip(null);
                } else {
                    setText(item);
                    setTooltip(new Tooltip(item));
                }
            }
        });

        TableColumn<QuizInfoDto, String> colCategory = new TableColumn<>("Chủ đề");
        colCategory.setPrefWidth(120);
        colCategory.setCellValueFactory(q -> new SimpleStringProperty(q.getValue().category() == null ? "—" : q.getValue().category()));
        colCategory.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setTooltip(null);
                } else {
                    setText(item);
                    setTooltip(new Tooltip(item));
                }
            }
        });

        TableColumn<QuizInfoDto, String> colDuration = new TableColumn<>("Thời lượng");
        colDuration.setPrefWidth(95);
        colDuration.setCellValueFactory(q -> new SimpleStringProperty(q.getValue().durationMinutes() + " phút"));

        TableColumn<QuizInfoDto, String> colQuestions = new TableColumn<>("Số câu");
        colQuestions.setPrefWidth(80);
        colQuestions.setCellValueFactory(q -> new SimpleStringProperty(q.getValue().questionCount() + " câu"));

        TableColumn<QuizInfoDto, String> colPass = new TableColumn<>("Điểm đạt");
        colPass.setPrefWidth(80);
        colPass.setCellValueFactory(q -> new SimpleStringProperty(String.format("%.0f%%", q.getValue().passScore())));

        TableColumn<QuizInfoDto, String> colStatus = new TableColumn<>("Trạng thái");
        colStatus.setPrefWidth(125);
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
                    QuizInfoDto quiz = getTableView().getItems().get(getIndex());
                    if ("OPEN".equals(item) && completedQuizIds.contains(quiz.id())) {
                        badge.setText("ĐÃ HOÀN THÀNH");
                        badge.getStyleClass().add("badge-completed");
                    } else if ("OPEN".equals(item)) {
                        badge.setText("SẴN SÀNG THI");
                        badge.getStyleClass().add("badge-open");
                    } else if ("CLOSED".equals(item)) {
                        badge.setText("ĐÃ KẾT THÚC");
                        badge.getStyleClass().add("badge-closed");
                    } else {
                        badge.setText("CHƯA MỞ");
                        badge.getStyleClass().add("badge-draft");
                    }
                    setGraphic(badge);
                    setText(null);
                }
            }
        });

        TableColumn<QuizInfoDto, Void> colAction = new TableColumn<>("Thao tác");
        colAction.setPrefWidth(130);
        colAction.setCellFactory(col -> new TableCell<>() {
            private final Button btn = new Button();
            private final HBox box = new HBox(btn);
            {
                box.setAlignment(Pos.CENTER_LEFT);
                btn.setOnAction(e -> {
                    QuizInfoDto row = getTableRow() != null ? getTableRow().getItem() : null;
                    if (row == null) return;
                    assignedQuizTable.getSelectionModel().select(row);
                    if (row.status() == QuizStatus.OPEN && !completedQuizIds.contains(row.id()) && historyLoaded) {
                        onStartExamClick();
                    } else if (completedQuizIds.contains(row.id())) {
                        openReviewForQuiz(row.id());
                    }
                });
            }
            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                QuizInfoDto row = (!empty && getTableRow() != null) ? getTableRow().getItem() : null;
                if (row == null) {
                    setGraphic(null);
                } else {
                    btn.getStyleClass().removeAll("primary-button", "secondary-button", "accent-outline-button", "btn-small");
                    boolean canStart = row.status() == QuizStatus.OPEN && !completedQuizIds.contains(row.id());
                    if (canStart) {
                        btn.setText("🚀 Vào thi");
                        btn.getStyleClass().addAll("primary-button", "btn-small");
                    } else if (completedQuizIds.contains(row.id())) {
                        btn.setText("📝 Xem review");
                        btn.getStyleClass().addAll("accent-outline-button", "btn-small");
                    } else {
                        btn.setText("Xem chi tiết");
                        btn.getStyleClass().addAll("secondary-button", "btn-small");
                    }
                    setGraphic(box);
                }
            }
        });

        assignedQuizTable.getColumns().setAll(List.of(colTitle, colCategory, colDuration, colQuestions, colPass, colStatus, colAction));
        assignedQuizTable.setItems(quizList);
        assignedQuizTable.setPlaceholder(new Label("Hiện tại bạn chưa có bài kiểm tra nào được giao."));
    }

    private void openReviewForQuiz(long quizId) {
        ResultDto res = completedResultsByQuizId.get(quizId);
        if (res != null) {
            navigator.showReview(res);
        } else {
            fx.observe(api.listMyHistory(), this::isActive,
                results -> {
                    for (ResultDto r : results) {
                        completedResultsByQuizId.put(r.quizId(), r);
                        if (r.quizId() == quizId) {
                            navigator.showReview(r);
                            return;
                        }
                    }
                },
                err -> showNotice("Không thể tải kết quả bài thi: " + err.getMessage(), true),
                () -> {}
            );
        }
    }

    @FXML
    private void loadAssignedQuizzes() {
        studentProgress.setVisible(true);
        btnRefresh.setDisable(true);
        loadHistoryAndClasses();
        fx.observe(api.listAssignedQuizzes(), this::isActive,
            quizzes -> {
                updateQuizItemsPreservingSelection(quizzes);
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
        boolean alreadySubmitted = completedQuizIds.contains(sel.id());

        infoStatus.getStyleClass().removeAll("badge-open", "badge-completed", "badge-closed", "badge-draft");
        if (alreadySubmitted) {
            infoStatus.setText("ĐÃ HOÀN THÀNH");
            infoStatus.getStyleClass().add("badge-completed");
        } else if (sel.status() == QuizStatus.OPEN) {
            infoStatus.setText("ĐANG MỞ THI");
            infoStatus.getStyleClass().add("badge-open");
        } else if (sel.status() == QuizStatus.CLOSED) {
            infoStatus.setText("ĐÃ ĐÓNG");
            infoStatus.getStyleClass().add("badge-closed");
        } else {
            infoStatus.setText("CHƯA MỞ");
            infoStatus.getStyleClass().add("badge-draft");
        }

        if (alreadySubmitted) {
            btnStartExam.setText("📝 Xem review đáp án");
            btnStartExam.getStyleClass().removeAll("primary-button");
            if (!btnStartExam.getStyleClass().contains("accent-outline-button")) {
                btnStartExam.getStyleClass().add("accent-outline-button");
            }
            btnStartExam.setDisable(!historyLoaded);
            btnStartExam.setOnAction(e -> openReviewForQuiz(sel.id()));
            showNotice("Bạn đã hoàn thành bài kiểm tra này. Bấm 'Xem review đáp án' để xem lại bài làm và kết quả.", false);
        } else {
            btnStartExam.setText("🚀 Bắt đầu làm bài");
            btnStartExam.getStyleClass().removeAll("accent-outline-button");
            if (!btnStartExam.getStyleClass().contains("primary-button")) {
                btnStartExam.getStyleClass().add("primary-button");
            }
            btnStartExam.setDisable(!historyLoaded || sel.status() != QuizStatus.OPEN);
            btnStartExam.setOnAction(e -> onStartExamClick());
            if (!historyLoaded) {
                showNotice("Đang kiểm tra lịch sử làm bài…", false);
            } else if (studentNotice.getText() != null && studentNotice.getText().startsWith("Bạn đã hoàn thành bài kiểm tra này")) {
                studentNotice.setVisible(false);
                studentNotice.setManaged(false);
            }
        }
        String description = sel.description() == null ? "" : sel.description().strip();
        boolean autoGeneratedDescription = description.toLowerCase(java.util.Locale.ROOT).startsWith("tạo tự động");
        infoDescriptionLabel.setVisible(!autoGeneratedDescription);
        infoDescriptionLabel.setManaged(!autoGeneratedDescription);
        infoDescription.setVisible(!autoGeneratedDescription);
        infoDescription.setManaged(!autoGeneratedDescription);
        infoDescription.setText(description.isBlank() ? "Không có mô tả chi tiết." : description);
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
            dialog.setTitle("Tham gia bài kiểm tra"); dialog.setHeaderText("Nhập mã PIN phòng thi");
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

        ButtonBar bar = findButtonBar(dialogPane);
        if (bar != null) {
            bar.setButtonOrder(ButtonBar.BUTTON_ORDER_NONE);
        }

        Button autoCloseBtn = (Button) dialogPane.lookupButton(btnAutoClose);
        if (autoCloseBtn != null) {
            ButtonBar.setButtonUniformSize(autoCloseBtn, false);
            autoCloseBtn.setMinWidth(140);
            autoCloseBtn.setPrefWidth(140);
            autoCloseBtn.setStyle("-fx-background-color: #4f46e5; -fx-text-fill: white; -fx-font-weight: bold; -fx-cursor: hand; -fx-padding: 6 12; -fx-min-width: 140px; -fx-pref-width: 140px;");
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
