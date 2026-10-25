package quiz.client.ui.teacher.question;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.control.ButtonType;
import javafx.scene.layout.*;
import javafx.beans.property.ReadOnlyStringWrapper;
import quiz.client.api.ApiException;
import quiz.client.api.ClientValidation;
import quiz.client.api.QuizApi;
import quiz.client.ui.SceneNavigator;
import quiz.client.ui.ScreenController;
import quiz.client.util.FxAsync;
import quiz.common.dto.CreateQuestionDto;
import quiz.common.dto.QuestionDto;
import quiz.common.dto.UpdateQuestionDto;
import quiz.common.enums.Difficulty;
import quiz.common.protocol.ErrorCode;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Function;

/** FX thread owns the question list and form. Responses update only the mounted session. */
public final class QuestionController extends ScreenController {
    private static final ZoneId DISPLAY_ZONE = ZoneId.systemDefault();
    private static final DateTimeFormatter CREATED_AT_FORMAT =
        DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss").withZone(DISPLAY_ZONE);
    private final QuizApi api;
    private final SceneNavigator navigator;
    private final FxAsync fx;
    @FXML private GridPane questionPanels;
    @FXML private VBox questionForm;
    @FXML private TableView<QuestionDto> questionTable;
    @FXML private TextField questionContent;
    @FXML private TextField questionOptionA;
    @FXML private TextField questionOptionB;
    @FXML private TextField questionOptionC;
    @FXML private TextField questionOptionD;
    @FXML private ComboBox<Character> questionCorrectOption;
    @FXML private TextField questionTopic;
    @FXML private ComboBox<Difficulty> questionDifficulty;
    @FXML private Button refreshQuestions;
    @FXML private Button createQuestion;
    @FXML private Button updateQuestion;
    @FXML private Button deactivateQuestion;
    @FXML private Button cancelEdit;
    @FXML private Button copySelected;
    @FXML private Label listStatus;
    @FXML private Label questionNotice;
    @FXML private TextArea selectedQuestion;
    @FXML private Label formError;
    @FXML private ProgressIndicator questionProgress;
    private long loadVersion;
    private boolean loading;
    private boolean writing;
    private boolean hasLoadedQuestions;
    private boolean listFresh;
    private QuestionDto editTarget;

    public QuestionController(QuizApi api, SceneNavigator navigator, FxAsync fx) {
        this.api = api;
        this.navigator = navigator;
        this.fx = fx;
    }

    @FXML
    private void initialize() {
        configureTable();
        questionCorrectOption.getItems().setAll('A', 'B', 'C', 'D');
        questionCorrectOption.setValue('A');
        questionDifficulty.getItems().setAll(Difficulty.EASY, Difficulty.MEDIUM, Difficulty.HARD);
        questionDifficulty.setValue(Difficulty.MEDIUM);
        questionPanels.widthProperty().addListener((observable, oldWidth, width) ->
            layoutPanels(width.doubleValue()));
        layoutPanels(0);
        updateControls();
    }

    private void configureTable() {
        addColumn("ID", 55, q -> Long.toString(q.id()));
        addColumn("Nội dung câu hỏi", 260, QuestionDto::content);
        addColumn("Đáp án A", 120, QuestionDto::optionA);
        addColumn("Đáp án B", 120, QuestionDto::optionB);
        addColumn("Đáp án C", 120, QuestionDto::optionC);
        addColumn("Đáp án D", 120, QuestionDto::optionD);
        addColumn("Đáp án đúng", 70, q -> String.valueOf(q.correctOption()));
        addColumn("Chủ đề", 150, QuestionDto::topic);
        addColumn("Độ khó", 80, q -> q.difficulty().name());
        addColumn("Trạng thái", 90, q -> q.active() ? "Hoạt động" : "Đã vô hiệu");
        addColumn("Ngày tạo", 160, q -> CREATED_AT_FORMAT.format(q.createdAt()));
        questionTable.setPlaceholder(new Label("Chưa tải danh sách câu hỏi."));
        questionTable.getSelectionModel().selectedItemProperty()
            .addListener((observable, previous, selected) -> cancelEdit());
    }

    private void addColumn(String title, double width, Function<QuestionDto, String> text) {
        TableColumn<QuestionDto, String> column = new TableColumn<>(title);
        column.setPrefWidth(width);
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(text.apply(cell.getValue())));
        column.setCellFactory(ignored -> new TableCell<>() {
            @Override
            protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                setText(empty ? null : value);
                setTooltip(empty || value == null ? null : new Tooltip(value));
            }
        });
        questionTable.getColumns().add(column);
    }

    private void layoutPanels(double width) {
        boolean wide = width >= 900;
        ColumnConstraints list = new ColumnConstraints();
        list.setPercentWidth(wide ? 60 : 100);
        ColumnConstraints form = new ColumnConstraints();
        form.setPercentWidth(wide ? 40 : 0);
        questionPanels.getColumnConstraints().setAll(wide ? List.of(list, form) : List.of(list));
        GridPane.setColumnIndex(questionForm, wide ? 1 : 0);
        GridPane.setRowIndex(questionForm, wide ? 0 : 1);
    }

    @Override
    protected void onMount() {
        reload();
    }

    @FXML
    private void reload() {
        if (!isActive() || writing) {
            return;
        }
        long version = ++loadVersion;
        loading = true;
        listFresh = false;
        listStatus.setText("Đang tải danh sách…");
        questionTable.setPlaceholder(new Label("Đang tải danh sách…"));
        updateControls();
        fx.observe(api.listQuestions(), () -> isActive() && version == loadVersion,
            this::showQuestions, this::showLoadFailure, () -> {
                loading = false;
                updateControls();
            });
    }

    private void showQuestions(List<QuestionDto> questions) {
        QuestionDto selected = questionTable.getSelectionModel().getSelectedItem();
        ObservableList<QuestionDto> items = FXCollections.observableArrayList(questions);
        questionTable.setItems(items);
        if (selected != null) {
            items.stream()
                .filter(q -> q.id() == selected.id())
                .findFirst()
                .ifPresent(q -> questionTable.getSelectionModel().select(q));
        }
        hasLoadedQuestions = true;
        listFresh = true;
        questionTable.setPlaceholder(new Label("Chưa có câu hỏi nào."));
        listStatus.setText(questions.isEmpty() ? "Danh sách trống." : questions.size() + " câu hỏi • Đã cập nhật");
    }

    private void showLoadFailure(Throwable failure) {
        questionTable.setPlaceholder(new Label("Không tải được danh sách. Chọn Tải lại để thử lại."));
        listStatus.setText("Không tải được danh sách. " + QuestionMessages.message(failure)
            + (hasLoadedQuestions ? " Dữ liệu đang hiển thị là lần tải trước." : ""));
    }

    @FXML
    private void newQuestion() {
        if (!isActive() || writing || loading) {
            return;
        }
        editTarget = null;
        clearForm();
        questionForm.setVisible(true);
        questionForm.setManaged(true);
        questionContent.requestFocus();
        updateControls();
    }

    @FXML
    private void editSelected() {
        QuestionDto selected = questionTable.getSelectionModel().getSelectedItem();
        if (!isActive() || writing || loading || selected == null || !selected.active()) {
            return;
        }
        editTarget = selected;
        fillForm(selected);
        questionForm.setVisible(true);
        questionForm.setManaged(true);
        questionContent.requestFocus();
        updateControls();
    }

    @FXML
    private void cancelEdit() {
        editTarget = null;
        clearForm();
        questionForm.setVisible(false);
        questionForm.setManaged(false);
        updateControls();
    }

    @FXML
    private void create() {
        if (!isActive() || writing || loading) {
            return;
        }
        CreateQuestionDto input;
        try {
            input = ClientValidation.question(new CreateQuestionDto(
                questionContent.getText(), questionOptionA.getText(), questionOptionB.getText(),
                questionOptionC.getText(), questionOptionD.getText(),
                questionCorrectOption.getValue(), questionTopic.getText(), questionDifficulty.getValue()));
        } catch (ApiException invalid) {
            show(formError, "Kiểm tra thông tin: nội dung tối đa 1000 ký tự, mỗi đáp án tối đa 255 ký tự, chủ đề 1–100 ký tự.", true);
            return;
        }
        show(formError, "", false);
        beginWrite("Đang tạo câu hỏi…");
        fx.observe(api.createQuestion(input), () -> isActive(),
            this::questionCreated, this::showWriteFailure, this::finishWrite);
    }

    @FXML
    private void update() {
        if (!isActive() || writing || loading || editTarget == null) {
            return;
        }
        UpdateQuestionDto input;
        try {
            input = ClientValidation.updateQuestion(new UpdateQuestionDto(
                editTarget.id(), questionContent.getText(), questionOptionA.getText(),
                questionOptionB.getText(), questionOptionC.getText(), questionOptionD.getText(),
                questionCorrectOption.getValue(), questionTopic.getText(), questionDifficulty.getValue()));
        } catch (ApiException invalid) {
            show(formError, "Kiểm tra thông tin: nội dung tối đa 1000 ký tự, mỗi đáp án tối đa 255 ký tự, chủ đề 1–100 ký tự.", true);
            return;
        }
        show(formError, "", false);
        beginWrite("Đang cập nhật câu hỏi…");
        fx.observe(api.updateQuestion(input), () -> isActive(),
            this::questionUpdated, this::showWriteFailure, this::finishWrite);
    }

    @FXML
    private void deactivate() {
        QuestionDto selected = questionTable.getSelectionModel().getSelectedItem();
        if (!isActive() || writing || loading || selected == null || !selected.active()) {
            return;
        }
        if (!confirm("Vô hiệu hóa câu hỏi “" + truncate(selected.content(), 60) + "”?",
            "Câu hỏi đã vô hiệu hóa sẽ không thể chọn khi tạo quiz. Hành động không thể khôi phục.")) {
            return;
        }
        beginWrite("Đang vô hiệu hóa câu hỏi…");
        fx.observe(api.deactivateQuestion(selected.id()), () -> isActive(),
            ignored -> {
                show(questionNotice, "Đã vô hiệu hóa câu hỏi “" + truncate(selected.content(), 60) + "”.", false);
                reload();
                cancelEdit();
            }, this::showWriteFailure, this::finishWrite);
    }

    private void questionCreated(QuestionDto created) {
        clearForm();
        writing = false;
        show(questionNotice, "Đã tạo câu hỏi “" + truncate(created.content(), 60) + "”.", false);
        show(formError, "Đã tạo câu hỏi mới. Xem trạng thái tải lại trong bảng câu hỏi.", false);
        reload();
    }

    private void questionUpdated(QuestionDto updated) {
        writing = false;
        show(questionNotice, "Đã cập nhật câu hỏi “" + truncate(updated.content(), 60) + "”.", false);
        show(formError, "Đã cập nhật câu hỏi. Xem trạng thái tải lại trong bảng câu hỏi.", false);
        reload();
        cancelEdit();
    }

    private void showWriteFailure(Throwable failure) {
        String message = QuestionMessages.message(failure);
        show(questionNotice, message, true);
        show(formError, message, true);
        if (failure instanceof ApiException business
            && business.errorCode() == ErrorCode.NOT_FOUND) {
            writing = false;
            reload();
        }
    }

    private void beginWrite(String message) {
        writing = true;
        cancelEdit();
        show(questionNotice, message, false);
    }

    private void finishWrite() {
        writing = false;
        updateControls();
    }

    private void updateControls() {
        boolean busy = writing || loading;
        QuestionDto selected = questionTable.getSelectionModel().getSelectedItem();
        createQuestion.setDisable(busy || editTarget != null);
        updateQuestion.setDisable(busy || editTarget == null);
        deactivateQuestion.setDisable(busy || !listFresh || selected == null || !selected.active());
        refreshQuestions.setDisable(writing);
        questionContent.setDisable(writing);
        questionOptionA.setDisable(writing);
        questionOptionB.setDisable(writing);
        questionOptionC.setDisable(writing);
        questionOptionD.setDisable(writing);
        questionCorrectOption.setDisable(writing);
        questionTopic.setDisable(writing);
        questionDifficulty.setDisable(writing);
        selectedQuestion.setText(selected == null ? "Chọn một câu hỏi trong bảng để xem/sao chép nội dung."
            : String.format("Câu hỏi: %s\nA. %s\nB. %s\nC. %s\nD. %s\nĐáp án đúng: %s",
                selected.content(), selected.optionA(), selected.optionB(), selected.optionC(), selected.optionD(), selected.correctOption()));
        if (copySelected != null) copySelected.setDisable(selected == null);
        cancelEdit.setDisable(busy || editTarget == null);
        questionProgress.setVisible(busy);
        questionProgress.setManaged(busy);
    }

    @FXML
    private void copySelected() {
        QuestionDto selected = questionTable.getSelectionModel().getSelectedItem();
        if (selected == null) return;
        javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
        String formatted = String.format("Câu hỏi: %s\nA. %s\nB. %s\nC. %s\nD. %s\nĐáp án đúng: %s",
            selected.content(), selected.optionA(), selected.optionB(), selected.optionC(), selected.optionD(), selected.correctOption());
        content.putString(formatted);
        javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
        show(questionNotice, "Đã sao chép nội dung câu hỏi và các đáp án vào clipboard!", false);
    }

    private void fillForm(QuestionDto q) {
        questionContent.setText(q.content());
        questionOptionA.setText(q.optionA());
        questionOptionB.setText(q.optionB());
        questionOptionC.setText(q.optionC());
        questionOptionD.setText(q.optionD());
        questionCorrectOption.setValue(q.correctOption());
        questionTopic.setText(q.topic());
        questionDifficulty.setValue(q.difficulty());
    }

    private void clearForm() {
        questionContent.clear();
        questionOptionA.clear();
        questionOptionB.clear();
        questionOptionC.clear();
        questionOptionD.clear();
        questionCorrectOption.setValue('A');
        questionTopic.clear();
        questionDifficulty.setValue(Difficulty.MEDIUM);
        show(formError, "", false);
        show(questionNotice, "", false);
    }

    private static void show(Label label, String message, boolean error) {
        label.setText(message);
        label.setVisible(!message.isEmpty());
        label.setManaged(!message.isEmpty());
        label.getStyleClass().remove("error-notice");
        if (error) {
            label.getStyleClass().add("error-notice");
        }
    }

    private boolean confirm(String title, String detail) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(detail);
        alert.initOwner(null);
        return alert.showAndWait().filter(ButtonType.OK::equals).isPresent();
    }

    private static String truncate(String text, int max) {
        if (text == null || text.length() <= max) {
            return text == null ? "" : text;
        }
        return text.substring(0, max - 1) + "…";
    }

    @Override
    protected void onDispose() {
        ++loadVersion;
        clearForm();
        editTarget = null;
    }
}
