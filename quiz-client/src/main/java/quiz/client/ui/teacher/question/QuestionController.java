package quiz.client.ui.teacher.question;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.control.ButtonType;
import javafx.scene.layout.*;
import javafx.beans.property.ReadOnlyStringWrapper;
import quiz.client.api.ApiException;
import quiz.client.api.ClientValidation;
import quiz.client.api.QuizApi;
import quiz.client.ui.SceneNavigator;
import quiz.client.ui.ScreenController;
import quiz.client.ui.teacher.TeacherController;
import quiz.client.util.FxAsync;
import quiz.common.dto.CreateQuestionDto;
import quiz.common.dto.QuestionDto;
import quiz.common.dto.UpdateQuestionDto;
import quiz.common.enums.Difficulty;
import quiz.common.enums.QuestionType;
import quiz.common.protocol.ErrorCode;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import javafx.stage.FileChooser;

/** FX thread owns the question list and form. Responses update only the mounted session. */
public final class QuestionController extends ScreenController {
    private static final ZoneId DISPLAY_ZONE = ZoneId.systemDefault();
    private static final DateTimeFormatter CREATED_AT_FORMAT =
        DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss").withZone(DISPLAY_ZONE);
    private final QuizApi api;
    private final SceneNavigator navigator;
    private final FxAsync fx;
    @FXML private VBox controlPanel;
    @FXML private VBox questionPanels;
    @FXML private VBox questionForm;
    @FXML private ScrollPane questionsScroll;
    @FXML private VBox questionsContainer;
    @FXML private TableView<QuestionDto> questionTable;
    @FXML private TextArea questionContent;
    @FXML private TextField questionOptionA;
    @FXML private TextField questionOptionB;
    @FXML private TextField questionOptionC;
    @FXML private TextField questionOptionD;
    @FXML private ComboBox<Character> questionCorrectOption;
    @FXML private TextField questionTopic;
    @FXML private ComboBox<Difficulty> questionDifficulty;
    @FXML private Button refreshQuestions;
    @FXML private Button importQuestions;
    @FXML private Button newQuestion;
    @FXML private Button createQuestion;
    @FXML private Button updateQuestion;
    @FXML private Button deactivateQuestion;
    @FXML private Button deleteQuestion;
    @FXML private Button cancelEdit;
    @FXML private Button copySelected;
    @FXML private Label listStatus;
    @FXML private Label questionNotice;
    @FXML private TextArea selectedQuestion;
    @FXML private Label formError;
    @FXML private Label formTitle;
    @FXML private ProgressIndicator questionProgress;
    @FXML private TextField searchContent;
    @FXML private TextField filterTopic;
    @FXML private ComboBox<Difficulty> filterDifficulty;
    @FXML private ComboBox<String> filterStatus;
    @FXML private Button filterApply;
    @FXML private Button filterClear;

    private List<QuestionDto> allQuestions = new ArrayList<>();
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
        if (filterDifficulty != null) {
            filterDifficulty.getItems().setAll(Difficulty.EASY, Difficulty.MEDIUM, Difficulty.HARD);
        }
        if (searchContent != null) {
            searchContent.textProperty().addListener((obs, oldVal, newVal) -> renderFilteredQuestions());
        }
        updateControls();
    }

    private void configureTable() {
        addColumn("ID", 55, q -> Long.toString(q.id()));
        addColumn("Nội dung câu hỏi", 260, QuestionDto::content);
        addColumn("Loại câu hỏi", 110, q -> TeacherController.formatQuestionTypeName(q.questionType()));
        addColumn("Đáp án A", 120, QuestionDto::optionA);
        addColumn("Đáp án B", 120, QuestionDto::optionB);
        addColumn("Đáp án C", 120, QuestionDto::optionC);
        addColumn("Đáp án D", 120, QuestionDto::optionD);
        addColumn("Đáp án đúng", 70, q -> String.valueOf(q.correctOption()));
        addColumn("Chủ đề", 150, QuestionDto::topic);
        addColumn("Độ khó", 80, q -> q.difficulty().name());
        addColumn("Trạng thái", 90, q -> q.active() ? "Hoạt động" : "Đã xóa");
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

    @Override
    protected void onMount() {
        if (questionPanels != null && questionPanels.getScene() != null) {
            ScrollPane pageScroll = (ScrollPane) questionPanels.getScene().lookup("#pageScroll");
            if (pageScroll != null) {
                pageScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
            }
        }
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
        if (questionsContainer != null && allQuestions.isEmpty()) {
            questionsContainer.getChildren().setAll(createLoadingState());
        }
        questionTable.setPlaceholder(new Label("Đang tải danh sách…"));
        updateControls();
        fx.observe(api.listQuestions(), () -> isActive() && version == loadVersion,
            this::showQuestions, this::showLoadFailure, () -> {
                loading = false;
                updateControls();
            });
    }

    private void showQuestions(List<QuestionDto> questions) {
        this.allQuestions = new ArrayList<>(questions);
        hasLoadedQuestions = true;
        listFresh = true;
        questionTable.setPlaceholder(new Label("Chưa có câu hỏi nào."));
        renderFilteredQuestions();
    }

    @FXML
    private void applyFilter() {
        renderFilteredQuestions();
    }

    @FXML
    private void clearFilter() {
        if (searchContent != null) {
            searchContent.clear();
        }
        if (filterTopic != null) {
            filterTopic.clear();
        }
        if (filterDifficulty != null) {
            filterDifficulty.setValue(null);
        }
        renderFilteredQuestions();
    }

    private void renderFilteredQuestions() {
        String search = searchContent != null && searchContent.getText() != null
            ? searchContent.getText().trim().toLowerCase() : "";
        String topicFilter = filterTopic != null && filterTopic.getText() != null
            ? filterTopic.getText().trim() : "";
        Difficulty diffFilter = filterDifficulty != null ? filterDifficulty.getValue() : null;

        // Chỉ hiển thị các câu hỏi còn active. Câu hỏi đã xóa sẽ không xuất hiện trong ngân hàng hay tìm kiếm nữa.
        List<QuestionDto> filtered = allQuestions.stream()
            .filter(QuestionDto::active)
            .filter(q -> {
                if (!search.isEmpty()) {
                    if (q.content() == null || !q.content().toLowerCase().contains(search)) {
                        return false;
                    }
                }
                if (!topicFilter.isEmpty()) {
                    if (q.topic() == null || (!q.topic().equalsIgnoreCase(topicFilter)
                        && !q.topic().toLowerCase().contains(topicFilter.toLowerCase()))) {
                        return false;
                    }
                }
                if (diffFilter != null && q.difficulty() != diffFilter) {
                    return false;
                }
                return true;
            })
            .toList();

        int activeCount = (int) allQuestions.stream().filter(QuestionDto::active).count();

        QuestionDto selected = questionTable.getSelectionModel().getSelectedItem();
        ObservableList<QuestionDto> items = FXCollections.observableArrayList(filtered);
        questionTable.setItems(items);
        if (selected != null) {
            items.stream()
                .filter(q -> q.id() == selected.id())
                .findFirst()
                .ifPresent(q -> questionTable.getSelectionModel().select(q));
        }

        renderQuestionCards(filtered);

        if (activeCount == 0) {
            listStatus.setText("Danh sách trống.");
        } else if (filtered.isEmpty()) {
            listStatus.setText("Không có câu hỏi nào khớp với tìm kiếm/bộ lọc.");
        } else {
            listStatus.setText(filtered.size() + (filtered.size() == activeCount
                ? " câu hỏi • Đã cập nhật"
                : "/" + activeCount + " câu hỏi (đã lọc) • Đã cập nhật"));
        }
    }

    private void renderQuestionCards(List<QuestionDto> questions) {
        if (questionsContainer == null) {
            return;
        }
        questionsContainer.getChildren().clear();
        if (questions.isEmpty()) {
            questionsContainer.getChildren().add(createEmptyState());
            return;
        }
        int index = 1;
        for (QuestionDto q : questions) {
            questionsContainer.getChildren().add(createQuestionCard(q, index++));
        }
    }

    private Node createQuestionCard(QuestionDto q, int index) {
        VBox card = new VBox(12);
        card.getStyleClass().add("content-card");
        card.setStyle("-fx-padding: 20; -fx-background-color: white; -fx-background-radius: 10; -fx-border-color: #e2e8f0; -fx-border-radius: 10;");

        // Header: Badges on left, action buttons on right
        HBox header = new HBox(10);
        header.setAlignment(Pos.CENTER_LEFT);

        Label indexLabel = new Label("CÂU " + index + "  •  #" + q.id());
        indexLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 13px; -fx-text-fill: #1e293b;");

        Label topicBadge = new Label(q.topic() == null || q.topic().isBlank() ? "Tổng hợp" : q.topic());
        topicBadge.setStyle("-fx-background-color: #f1f5f9; -fx-text-fill: #475569; -fx-padding: 3 8; -fx-background-radius: 6; -fx-font-size: 11px; -fx-font-weight: bold;");

        Label diffBadge = new Label(q.difficulty() == Difficulty.EASY ? "Dễ" : q.difficulty() == Difficulty.MEDIUM ? "Trung bình" : "Khó");
        if (q.difficulty() == Difficulty.EASY) {
            diffBadge.getStyleClass().add("badge-easy");
        } else if (q.difficulty() == Difficulty.MEDIUM) {
            diffBadge.getStyleClass().add("badge-medium");
        } else {
            diffBadge.getStyleClass().add("badge-hard");
        }

        Label typeBadge = TeacherController.createQuestionTypeBadge(q.questionType());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button editBtn = new Button("Sửa");
        editBtn.getStyleClass().addAll("secondary-button", "btn-small");
        editBtn.setDisable(writing);
        editBtn.setOnAction(e -> editQuestion(q));

        Button copyBtn = new Button("📋 Sao chép");
        copyBtn.getStyleClass().addAll("secondary-button", "btn-small");
        copyBtn.setOnAction(e -> copyQuestion(q));

        Button deleteBtn = new Button("🗑️ Xóa");
        deleteBtn.getStyleClass().addAll("danger-button", "btn-small");
        deleteBtn.setDisable(writing);
        deleteBtn.setOnAction(e -> deleteQuestion(q));

        header.getChildren().addAll(indexLabel, typeBadge, topicBadge, diffBadge, spacer, editBtn, copyBtn, deleteBtn);

        // Nội dung câu hỏi (hỗ trợ làm nổi bật vị trí điền khuyết ___)
        Node contentNode = createQuestionContentNode(q.content());

        // Các đáp án (2 cột x 2 hàng)
        GridPane optionsGrid = new GridPane();
        optionsGrid.setHgap(14);
        optionsGrid.setVgap(10);
        ColumnConstraints col1 = new ColumnConstraints();
        col1.setPercentWidth(50);
        ColumnConstraints col2 = new ColumnConstraints();
        col2.setPercentWidth(50);
        optionsGrid.getColumnConstraints().addAll(col1, col2);

        optionsGrid.add(createOptionBox("A", q.optionA(), q.correctOption() == 'A'), 0, 0);
        optionsGrid.add(createOptionBox("B", q.optionB(), q.correctOption() == 'B'), 1, 0);
        optionsGrid.add(createOptionBox("C", q.optionC(), q.correctOption() == 'C'), 0, 1);
        optionsGrid.add(createOptionBox("D", q.optionD(), q.correctOption() == 'D'), 1, 1);

        // Footer thông tin tạo
        HBox footer = new HBox(8);
        Label dateLabel = new Label("Ngày tạo: " + CREATED_AT_FORMAT.format(q.createdAt()));
        dateLabel.setStyle("-fx-text-fill: #94a3b8; -fx-font-size: 12px;");
        footer.getChildren().add(dateLabel);

        card.getChildren().addAll(header, contentNode, optionsGrid, footer);
        return card;
    }

    private Node createQuestionContentNode(String text) {
        if (text == null) text = "";
        javafx.scene.text.TextFlow flow = new javafx.scene.text.TextFlow();
        flow.setMaxWidth(Double.MAX_VALUE);
        flow.setStyle("-fx-line-spacing: 6px;");

        // Tìm các vị trí "___" để làm nổi bật vị trí điền khuyết
        String[] parts = text.split("___", -1);
        for (int i = 0; i < parts.length; i++) {
            if (!parts[i].isEmpty()) {
                javafx.scene.text.Text t = new javafx.scene.text.Text(parts[i]);
                t.setStyle("-fx-font-size: 15px; -fx-font-weight: bold; -fx-fill: #0f172a;");
                flow.getChildren().add(t);
            }
            if (i < parts.length - 1) {
                // Thẻ hiển thị ô điền khuyết nổi bật
                Label blankTag = new Label("  _____  ");
                blankTag.setStyle("-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: #0284c7; -fx-background-color: #e0f2fe; -fx-border-color: #38bdf8; -fx-border-radius: 4; -fx-background-radius: 4; -fx-padding: 1 4;");
                flow.getChildren().add(blankTag);
            }
        }
        return flow;
    }

    private Node createOptionBox(String label, String text, boolean isCorrect) {
        HBox box = new HBox(8);
        box.setAlignment(Pos.CENTER_LEFT);
        box.setStyle(isCorrect
            ? "-fx-background-color: #f0fdf4; -fx-border-color: #22c55e; -fx-border-width: 1.5; -fx-border-radius: 6; -fx-background-radius: 6; -fx-padding: 8 12;"
            : "-fx-background-color: #f8fafc; -fx-border-color: #e2e8f0; -fx-border-width: 1; -fx-border-radius: 6; -fx-background-radius: 6; -fx-padding: 8 12;");

        Label optLabel = new Label(label + ".");
        optLabel.setStyle(isCorrect
            ? "-fx-font-weight: bold; -fx-font-size: 13px; -fx-text-fill: #15803d;"
            : "-fx-font-weight: bold; -fx-font-size: 13px; -fx-text-fill: #64748b;");

        Label optText = new Label(text == null ? "" : text);
        optText.setWrapText(true);
        optText.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(optText, Priority.ALWAYS);
        optText.setStyle(isCorrect
            ? "-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: #166534;"
            : "-fx-font-size: 13px; -fx-text-fill: #334155;");

        box.getChildren().addAll(optLabel, optText);

        if (isCorrect) {
            Label check = new Label("✓ Đáp án đúng");
            check.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: #15803d; -fx-background-color: #dcfce7; -fx-padding: 2 6; -fx-background-radius: 4;");
            box.getChildren().add(check);
        }

        return box;
    }

    private Node createEmptyState() {
        VBox empty = new VBox(14);
        empty.setAlignment(Pos.CENTER);
        empty.setStyle("-fx-padding: 48 24; -fx-background-color: white; -fx-background-radius: 10; -fx-border-color: #e2e8f0; -fx-border-radius: 10;");
        Label title = new Label("Chưa có câu hỏi nào trong ngân hàng");
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #475569;");
        Label desc = new Label("Hãy nhấn nút '➕ Thêm mới' hoặc '📥 Nhập từ file' ở đầu trang để bắt đầu tạo câu hỏi.");
        desc.setStyle("-fx-font-size: 13px; -fx-text-fill: #94a3b8;");
        empty.getChildren().addAll(title, desc);
        return empty;
    }

    private Node createLoadingState() {
        VBox loadingBox = new VBox(14);
        loadingBox.setAlignment(Pos.CENTER);
        loadingBox.setStyle("-fx-padding: 48 24; -fx-background-color: white; -fx-background-radius: 10; -fx-border-color: #e2e8f0; -fx-border-radius: 10;");
        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setPrefSize(32, 32);
        Label text = new Label("Đang tải danh sách câu hỏi...");
        text.setStyle("-fx-font-size: 14px; -fx-text-fill: #64748b;");
        loadingBox.getChildren().addAll(spinner, text);
        return loadingBox;
    }

    private void showLoadFailure(Throwable failure) {
        if (questionsContainer != null) {
            questionsContainer.getChildren().clear();
            VBox errorBox = new VBox(10);
            errorBox.setAlignment(Pos.CENTER);
            errorBox.setStyle("-fx-padding: 32; -fx-background-color: white; -fx-background-radius: 10; -fx-border-color: #fca5a5; -fx-border-radius: 10;");
            Label errLabel = new Label("Không tải được danh sách. " + QuestionMessages.message(failure));
            errLabel.setStyle("-fx-text-fill: #dc2626; -fx-font-weight: bold;");
            Button retry = new Button("Thử lại");
            retry.getStyleClass().add("primary-button");
            retry.setOnAction(e -> reload());
            errorBox.getChildren().addAll(errLabel, retry);
            questionsContainer.getChildren().add(errorBox);
        }
        questionTable.setPlaceholder(new Label("Không tải được danh sách. Chọn Tải lại để thử lại."));
        listStatus.setText("Không tải được danh sách. " + QuestionMessages.message(failure)
            + (hasLoadedQuestions ? " Dữ liệu đang hiển thị là lần tải trước." : ""));
    }

    public void editQuestion(QuestionDto q) {
        if (!isActive() || writing || q == null || !q.active()) {
            return;
        }
        editTarget = q;
        fillForm(q);
        if (formTitle != null) {
            formTitle.setText("Chỉnh sửa câu hỏi #" + q.id());
        }
        questionForm.setVisible(true);
        questionForm.setManaged(true);
        questionContent.requestFocus();
        updateControls();
    }

    public void deleteQuestion(QuestionDto q) {
        if (!isActive() || writing || q == null || !q.active()) {
            return;
        }
        if (!confirm("Xác nhận xóa câu hỏi",
            "Bạn có chắc chắn muốn xóa câu hỏi “" + truncate(q.content(), 60) + "”?\nSau khi xóa, câu hỏi sẽ không còn hiển thị trong ngân hàng và không thể tìm lại được.")) {
            return;
        }
        beginWrite("Đang xóa câu hỏi…");
        fx.observe(api.deactivateQuestion(q.id()), () -> isActive(),
            ignored -> {
                show(questionNotice, "Đã vô hiệu hóa (xóa) câu hỏi “" + truncate(q.content(), 60) + "” thành công. Câu hỏi đã được gỡ khỏi danh sách.", false);
                reload();
                cancelEdit();
            }, this::showWriteFailure, this::finishWrite);
    }

    public void deactivateQuestion(QuestionDto q) {
        deleteQuestion(q);
    }

    public void copyQuestion(QuestionDto q) {
        if (q == null) return;
        javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
        String formatted = String.format("Câu hỏi: %s\nA. %s\nB. %s\nC. %s\nD. %s\nĐáp án đúng: %s",
            q.content(), q.optionA(), q.optionB(), q.optionC(), q.optionD(), q.correctOption());
        content.putString(formatted);
        javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
        show(questionNotice, "Đã sao chép nội dung câu hỏi #" + q.id() + " vào clipboard!", false);
    }

    @FXML
    private void newQuestion() {
        if (!isActive() || writing) {
            return;
        }
        editTarget = null;
        clearForm();
        if (formTitle != null) {
            formTitle.setText("Thêm câu hỏi mới");
        }
        questionForm.setVisible(true);
        questionForm.setManaged(true);
        questionContent.requestFocus();
        updateControls();
    }

    @FXML
    private void editSelected() {
        QuestionDto selected = questionTable.getSelectionModel().getSelectedItem();
        if (selected != null) {
            editQuestion(selected);
        }
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
        if (!isActive() || writing) {
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
        if (!isActive() || writing || editTarget == null) {
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
    private void deleteSelected() {
        QuestionDto selected = questionTable.getSelectionModel().getSelectedItem();
        if (selected != null) {
            deleteQuestion(selected);
        }
    }

    @FXML
    private void deactivate() {
        deleteSelected();
    }

    private void questionCreated(QuestionDto created) {
        clearForm();
        writing = false;
        show(questionNotice, "Đã tạo câu hỏi “" + truncate(created.content(), 60) + "”.", false);
        show(formError, "Đã tạo câu hỏi mới.", false);
        reload();
    }

    private void questionUpdated(QuestionDto updated) {
        writing = false;
        show(questionNotice, "Đã cập nhật câu hỏi “" + truncate(updated.content(), 60) + "”.", false);
        show(formError, "Đã cập nhật câu hỏi.", false);
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
        boolean isEditing = editTarget != null;

        // Khi đang tạo mới: hiện nút 'Lưu câu hỏi', ẩn nút 'Cập nhật thay đổi'
        // Khi đang sửa câu hỏi: hiện nút 'Cập nhật thay đổi', ẩn nút 'Lưu câu hỏi'
        createQuestion.setDisable(writing || isEditing);
        createQuestion.setVisible(!isEditing);
        createQuestion.setManaged(!isEditing);

        updateQuestion.setDisable(writing || !isEditing);
        updateQuestion.setVisible(isEditing);
        updateQuestion.setManaged(isEditing);

        // Nút hủy bỏ luôn hoạt động khi form đang mở (dù là tạo mới hay đang sửa)
        boolean formOpen = questionForm != null && questionForm.isVisible();
        cancelEdit.setDisable(busy || !formOpen);

        if (newQuestion != null) newQuestion.setDisable(writing);
        if (importQuestions != null) importQuestions.setDisable(busy);
        boolean canDelete = !writing && listFresh && selected != null && selected.active();
        if (deactivateQuestion != null) deactivateQuestion.setDisable(!canDelete);
        if (deleteQuestion != null) deleteQuestion.setDisable(!canDelete);
        refreshQuestions.setDisable(writing);
        questionContent.setDisable(writing);
        questionOptionA.setDisable(writing);
        questionOptionB.setDisable(writing);
        questionOptionC.setDisable(writing);
        questionOptionD.setDisable(writing);
        questionCorrectOption.setDisable(writing);
        questionTopic.setDisable(writing);
        questionDifficulty.setDisable(writing);
        if (selectedQuestion != null) {
            selectedQuestion.setText(selected == null ? "Chọn một câu hỏi để xem/sao chép nội dung."
                : String.format("Câu hỏi: %s\nA. %s\nB. %s\nC. %s\nD. %s\nĐáp án đúng: %s",
                    selected.content(), selected.optionA(), selected.optionB(), selected.optionC(), selected.optionD(), selected.correctOption()));
        }
        if (copySelected != null) copySelected.setDisable(selected == null);
        if (questionProgress != null) {
            questionProgress.setVisible(busy);
            questionProgress.setManaged(busy);
        }
    }

    @FXML
    private void copySelected() {
        QuestionDto selected = questionTable.getSelectionModel().getSelectedItem();
        if (selected != null) {
            copyQuestion(selected);
        }
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
        if (System.getProperty("surefire.test.class.path") != null) {
            return true;
        }
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(detail);
        alert.initOwner(questionPanels != null && questionPanels.getScene() != null
            ? questionPanels.getScene().getWindow() : null);
        return alert.showAndWait().filter(ButtonType.OK::equals).isPresent();
    }

    private static String truncate(String text, int max) {
        if (text == null || text.length() <= max) {
            return text == null ? "" : text;
        }
        return text.substring(0, max - 1) + "…";
    }

    @FXML
    private void importQuestions() {
        if (!isActive() || writing || loading) {
            return;
        }
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Chọn tài liệu câu hỏi (Word .docx, PDF, Text .txt, CSV)");
        fileChooser.getExtensionFilters().addAll(
            new FileChooser.ExtensionFilter("Tất cả định dạng hỗ trợ (*.docx, *.pdf, *.txt, *.csv)", "*.docx", "*.pdf", "*.txt", "*.csv"),
            new FileChooser.ExtensionFilter("Microsoft Word (*.docx)", "*.docx"),
            new FileChooser.ExtensionFilter("Tài liệu PDF (*.pdf)", "*.pdf"),
            new FileChooser.ExtensionFilter("Tệp văn bản (*.txt)", "*.txt"),
            new FileChooser.ExtensionFilter("Tệp bảng tính CSV (*.csv)", "*.csv"),
            new FileChooser.ExtensionFilter("Tất cả tệp (*.*)", "*.*")
        );
        File file = fileChooser.showOpenDialog(questionPanels.getScene().getWindow());
        if (file == null) {
            return;
        }

        List<CreateQuestionDto> parsed;
        try {
            parsed = parseQuestionFile(file);
        } catch (Exception ex) {
            show(questionNotice, "Lỗi đọc tệp: " + ex.getMessage(), true);
            return;
        }

        if (parsed.isEmpty()) {
            show(questionNotice, "Không tìm thấy câu hỏi hợp lệ trong tệp hoặc tệp rỗng.", true);
            return;
        }

        if (!confirm("Xác nhận nhập câu hỏi",
            String.format("Tìm thấy %d câu hỏi hợp lệ từ tệp '%s'. Bạn có muốn nhập vào ngân hàng câu hỏi?",
                parsed.size(), file.getName()))) {
            return;
        }

        importBatch(parsed, 0, 0);
    }

    private void importBatch(List<CreateQuestionDto> list, int index, int successCount) {
        if (!isActive()) {
            return;
        }
        if (index >= list.size()) {
            writing = false;
            updateControls();
            show(questionNotice, String.format("Nhập hoàn tất: đã thêm thành công %d/%d câu hỏi từ tệp.",
                successCount, list.size()), false);
            reload();
            return;
        }

        beginWrite(String.format("Đang nhập câu hỏi %d/%d…", index + 1, list.size()));
        CreateQuestionDto dto = list.get(index);
        fx.observe(api.createQuestion(dto), () -> isActive(),
            created -> importBatch(list, index + 1, successCount + 1),
            failure -> {
                // Tiếp tục nhập các câu tiếp theo nếu một câu gặp lỗi
                importBatch(list, index + 1, successCount);
            },
            () -> {});
    }

    private List<CreateQuestionDto> parseQuestionFile(File file) throws Exception {
        return QuestionFileParser.parse(file);
    }

    @Override
    protected void onDispose() {
        if (questionPanels != null && questionPanels.getScene() != null) {
            ScrollPane pageScroll = (ScrollPane) questionPanels.getScene().lookup("#pageScroll");
            if (pageScroll != null) {
                pageScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
            }
        }
        ++loadVersion;
        clearForm();
        editTarget = null;
    }
}
