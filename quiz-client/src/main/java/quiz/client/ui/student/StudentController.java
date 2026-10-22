package quiz.client.ui.student;

import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import quiz.client.api.ApiException;
import quiz.client.api.QuizApi;
import quiz.client.ui.SceneNavigator;
import quiz.client.ui.ScreenController;
import quiz.client.util.FxAsync;
import quiz.common.dto.AntiCheatWarningDto;
import quiz.common.dto.QuizInfoDto;
import quiz.common.protocol.MessageType;
import quiz.common.enums.QuizStatus;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

/**
 * Controller cho Học viên xem các bài kiểm tra được giao (FR-S02) và thông tin bài kiểm tra (FR-S04).
 */
public final class StudentController extends ScreenController {
    private static final DateTimeFormatter DATE_FORMAT =
        DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault());

    private final QuizApi api;
    private final SceneNavigator navigator;
    private final FxAsync fx;

    @FXML private TableView<QuizInfoDto> assignedQuizTable;
    @FXML private Label studentWelcome;
    @FXML private Label assignedCountLabel;
    @FXML private Label studentNotice;
    @FXML private ProgressIndicator studentProgress;
    @FXML private Button btnRefresh;
    @FXML private Button btnViewInfo;

    // Quiz Info Details Panel (FR-S04)
    @FXML private VBox quizInfoPanel;
    @FXML private Label infoTitle;
    @FXML private Label infoCategory;
    @FXML private Label infoDuration;
    @FXML private Label infoPassScore;
    @FXML private Label infoQuestionCount;
    @FXML private Label infoStatus;
    @FXML private Label infoPin;
    @FXML private Label infoDescription;
    @FXML private Button btnCloseInfo;

    private final ObservableList<QuizInfoDto> quizList = FXCollections.observableArrayList();

    public StudentController(QuizApi api, SceneNavigator navigator, FxAsync fx) {
        this.api = Objects.requireNonNull(api);
        this.navigator = Objects.requireNonNull(navigator);
        this.fx = Objects.requireNonNull(fx);
    }

    @FXML
    private void initialize() {
        configureTable();
        assignedQuizTable.getSelectionModel().selectedItemProperty()
            .addListener((obs, oldVal, selected) -> btnViewInfo.setDisable(selected == null));
        btnViewInfo.setDisable(true);
        quizInfoPanel.setVisible(false);
        quizInfoPanel.setManaged(false);

        api.session().current().ifPresent(snap -> {
            studentWelcome.setText("Xin chào, " + snap.fullName() + "!");
        });
    }

    @Override
    protected void onMount() {
        loadAssignedQuizzes();

        // FR-S13: Đăng ký lắng nghe thông báo PUSH Anti-Cheat từ Server
        own(api.addPushListener((generation, response) -> {
            if (response.pushType() == MessageType.PUSH_ANTICHEAT_WARNING) {
                if (response.payload() instanceof AntiCheatWarningDto warning) {
                    javafx.application.Platform.runLater(() -> {
                        showWarningDialog("CẢNH BÁO VI PHẠM QUY CHẾ THI", warning.message());
                    });
                }
            } else if (response.pushType() == MessageType.PUSH_FORCE_SUBMITTED) {
                String message = response.payload() instanceof String str ? str : "Bài thi đã bị hệ thống tự động thu hồi do vi phạm quy chế!";
                javafx.application.Platform.runLater(() -> {
                    showForceSubmittedDialog(message);
                    loadAssignedQuizzes();
                });
            }
        }));
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

        TableColumn<QuizInfoDto, String> colPin = new TableColumn<>("Mã PIN");
        colPin.setPrefWidth(100);
        colPin.setCellValueFactory(q -> new SimpleStringProperty(q.getValue().pin() == null ? "—" : q.getValue().pin()));
        colPin.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null || "—".equals(item)) {
                    setText(item);
                    setGraphic(null);
                } else {
                    Label pinLabel = new Label(item);
                    pinLabel.getStyleClass().add("badge-pin");
                    setGraphic(pinLabel);
                    setText(null);
                }
            }
        });

        assignedQuizTable.getColumns().setAll(List.of(colTitle, colCategory, colDuration, colQuestions, colPass, colStatus, colPin));
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
                assignedCountLabel.setText("Bạn có " + quizzes.size() + " bài kiểm tra được giao");
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

    @FXML
    private void onViewInfoClick() {
        QuizInfoDto sel = assignedQuizTable.getSelectionModel().getSelectedItem();
        if (sel == null) return;

        infoTitle.setText(sel.title());
        infoCategory.setText(sel.category() != null ? sel.category() : "Tiếng Anh");
        infoDuration.setText(sel.durationMinutes() + " phút");
        infoPassScore.setText(String.format("%.0f%%", sel.passScore()));
        infoQuestionCount.setText(sel.questionCount() + " câu hỏi");
        infoStatus.setText(sel.status() == QuizStatus.OPEN ? "Đang mở thi" : (sel.status() == QuizStatus.CLOSED ? "Đã đóng" : "Chưa mở"));
        infoPin.setText(sel.pin() != null ? sel.pin() : "Chưa cấp");
        infoDescription.setText(sel.description() != null && !sel.description().isBlank() ? sel.description() : "Không có mô tả chi tiết.");

        quizInfoPanel.setVisible(true);
        quizInfoPanel.setManaged(true);
    }

    @FXML
    private void onCloseInfoClick() {
        quizInfoPanel.setVisible(false);
        quizInfoPanel.setManaged(false);
    }

    private void showWarningDialog(String title, String message) {
        showNotice(message, true);
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle(title);
        alert.setHeaderText("Hệ thống phát hiện hành vi bất thường!");
        alert.setContentText(message);
        alert.show();
    }

    private void showForceSubmittedDialog(String message) {
        showNotice(message, true);
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("THU HỒI BÀI THI");
        alert.setHeaderText("Bài thi đã bị tự động nộp!");
        alert.setContentText(message);
        alert.show();
    }

    private void showNotice(String msg, boolean isError) {
        studentNotice.setText(msg);
        studentNotice.getStyleClass().removeAll("notice", "error-notice");
        studentNotice.getStyleClass().add(isError ? "error-notice" : "notice");
        studentNotice.setVisible(true);
        studentNotice.setManaged(true);
    }
}
