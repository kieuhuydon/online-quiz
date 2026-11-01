package quiz.client.ui.student;

import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import quiz.client.api.QuizApi;
import quiz.client.ui.*;
import quiz.client.util.FxAsync;
import quiz.common.dto.ResultDto;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.function.Function;

public final class HistoryController extends ScreenController {
    private final QuizApi api;
    private final SceneNavigator navigator;
    private final FxAsync fx;

    @FXML private TableView<ResultDto> table;
    @FXML private Label notice;
    @FXML private ProgressIndicator progress;

    public HistoryController(QuizApi api, SceneNavigator navigator, FxAsync fx) {
        this.api = api;
        this.navigator = navigator;
        this.fx = fx;
    }

    @FXML
    private void initialize() {
        addColumn("Bài kiểm tra", ResultDto::quizTitle, 220);
        addColumn("Điểm số", r -> String.format(Locale.ROOT, "%.2f / 10", r.score()), 95);
        addColumn("Đúng / Tổng", r -> r.correctCount() + " / " + r.totalQuestions() + " câu", 110);
        addStatusColumn();
        addColumn("Nộp lúc", r -> DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
            .withZone(ZoneId.systemDefault()).format(r.submittedAt()), 145);
        addColumn("Hình thức nộp", r -> switch (r.submitReason()) {
            case MANUAL -> "Nộp thủ công";
            case TIMEOUT -> "Tự động nộp — Hết giờ";
            case ANTICHEAT -> "Tự động nộp — Vi phạm";
        }, 185);
        addReviewAction();
        table.setPlaceholder(new Label("Chưa có kết quả bài thi nào."));
        table.setFixedCellSize(46);
    }

    private void addColumn(String title, Function<ResultDto, String> value, int width) {
        TableColumn<ResultDto, String> column = new TableColumn<>(title);
        column.setPrefWidth(width);
        column.setSortable(false);
        column.setCellValueFactory(cell -> new SimpleStringProperty(value.apply(cell.getValue())));
        table.getColumns().add(column);
    }

    private void addStatusColumn() {
        TableColumn<ResultDto, String> column = new TableColumn<>("Kết quả");
        column.setPrefWidth(115);
        column.setSortable(false);
        column.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().passed() ? "ĐẠT" : "CHƯA ĐẠT"));
        column.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    setText(null);
                } else {
                    Label badge = new Label(item);
                    badge.getStyleClass().addAll("badge", "ĐẠT".equals(item) ? "badge-pass" : "badge-fail");
                    setGraphic(badge);
                    setText(null);
                }
            }
        });
        table.getColumns().add(column);
    }

    private void addReviewAction() {
        TableColumn<ResultDto, Void> column = new TableColumn<>("Chi tiết");
        column.setPrefWidth(135);
        column.setSortable(false);
        column.setCellFactory(ignored -> new TableCell<>() {
            private final Button button = new Button("🔍 Xem đáp án");
            {
                button.getStyleClass().addAll("accent-outline-button", "btn-small");
                button.setOnAction(event -> {
                    ResultDto selected = getTableRow() == null ? null : getTableRow().getItem();
                    if (selected != null) navigator.showReview(selected);
                });
            }
            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                setGraphic(empty ? null : button);
            }
        });
        table.getColumns().add(column);
    }

    @Override
    protected void onMount() {
        reload();
    }

    @FXML
    private void reload() {
        progress.setVisible(true);
        fx.observe(api.listMyHistory(), this::isActive, items -> {
            table.setItems(FXCollections.observableArrayList(items));
            notice.setText("");
            notice.setVisible(false);
            notice.setManaged(false);
            progress.setVisible(false);
        }, failure -> {
            notice.setText("Không tải được lịch sử: " + failure.getMessage());
            notice.setVisible(true);
            notice.setManaged(true);
            progress.setVisible(false);
        }, () -> progress.setVisible(false));
    }

    @FXML
    private void exams() {
        navigator.show(Screen.STUDENT_DASHBOARD);
    }
}
