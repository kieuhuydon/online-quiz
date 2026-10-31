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
    @FXML private ComboBox<String> filterQuestionType;
    @FXML private ComboBox<String> questionType;
    @FXML private TextArea questionExplanation;
    @FXML private Label questionTypeHint;
    @FXML private VBox boxCorrectOption;
    @FXML private VBox boxOptionA;
    @FXML private VBox boxOptionB;
    @FXML private VBox boxOptionC;
    @FXML private VBox boxOptionD;
    @FXML private Label labelOptionA;
    @FXML private Label labelOptionB;
    @FXML private Label labelOptionC;
    @FXML private Label labelOptionD;
    @FXML private GridPane optionsGridContainer;

    // True/False inline selector (created dynamically, inserted into form)
    private HBox trueFalseSelector;
    private ToggleGroup trueFalseGroup;

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
        // Setup filterQuestionType
        if (filterQuestionType != null) {
            filterQuestionType.getItems().setAll("Tất cả", "Trắc nghiệm", "Đúng / Sai", "Điền ô trống", "Trả lời ngắn");
            filterQuestionType.setValue("Tất cả");
            filterQuestionType.valueProperty().addListener((obs, o, n) -> renderFilteredQuestions());
        }
        // Setup questionType
        if (questionType != null) {
            questionType.getItems().setAll(
                "Trac nghiem (4 lua chon)", "Dung / Sai",
                "Dien vao cho trong (1 o)", "Dien vao cho trong (2 o)", "Tra loi ngan"
            );
            questionType.setValue("Trac nghiem (4 lua chon)");
            questionType.valueProperty().addListener((obs, o, n) -> onQuestionTypeChanged(n));
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
        if (filterQuestionType != null) {
            filterQuestionType.setValue("Tất cả");
        }
        renderFilteredQuestions();
    }

    private void renderFilteredQuestions() {
        String search = searchContent != null && searchContent.getText() != null
            ? searchContent.getText().trim().toLowerCase() : "";
        String topicFilter = filterTopic != null && filterTopic.getText() != null
            ? filterTopic.getText().trim() : "";
        Difficulty diffFilter = filterDifficulty != null ? filterDifficulty.getValue() : null;
        String qtFilter = filterQuestionType != null ? filterQuestionType.getValue() : null;

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
                if (qtFilter != null) {
                    QuestionType expected = matchFilterQuestionType(qtFilter);
                    if (expected != null) {
                        QuestionType actual = q.questionType() != null ? q.questionType() : QuestionType.MULTIPLE_CHOICE;
                        if (actual != expected) {
                            return false;
                        }
                    }
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
        Node answersNode = createAnswersDisplayNode(q);

        // Footer thông tin tạo
        HBox footer = new HBox(8);
        Label dateLabel = new Label("Ngày tạo: " + CREATED_AT_FORMAT.format(q.createdAt()));
        dateLabel.setStyle("-fx-text-fill: #94a3b8; -fx-font-size: 12px;");
        footer.getChildren().add(dateLabel);

        if (q.explanation() != null && !q.explanation().isBlank()) {
            VBox expBox = new VBox(4);
            expBox.setStyle("-fx-background-color: #fffbeb; -fx-border-color: #fbbf24; -fx-border-width: 1; -fx-border-radius: 6; -fx-background-radius: 6; -fx-padding: 10 14;");
            Label expTitle = new Label("💡 Giải thích đáp án:");
            expTitle.setStyle("-fx-font-weight: bold; -fx-font-size: 12px; -fx-text-fill: #92400e;");
            Label expText = new Label(q.explanation());
            expText.setWrapText(true);
            expText.setStyle("-fx-font-size: 13px; -fx-text-fill: #451a03;");
            expBox.getChildren().addAll(expTitle, expText);
            card.getChildren().addAll(header, contentNode, answersNode, expBox, footer);
        } else {
            card.getChildren().addAll(header, contentNode, answersNode, footer);
        }
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

    
    private Node createAnswersDisplayNode(QuestionDto q) {
        QuestionType type = q.questionType() != null ? q.questionType() : QuestionType.MULTIPLE_CHOICE;
        return switch (type) {
            case TRUE_FALSE -> createTrueFalseAnswersNode(q);
            case FILL_BLANK -> createFillBlankAnswersNode(q);
            case SHORT_ANSWER -> createShortAnswerNode(q);
            default -> createMultipleChoiceGrid(q);
        };
    }

        private Node createTrueFalseAnswersNode(QuestionDto q) {
        HBox box = new HBox(16);
        box.setAlignment(Pos.CENTER_LEFT);
        boolean isA = (q.correctOption() == 'A');
        boolean isB = (q.correctOption() == 'B');

        HBox trueBox = new HBox(10);
        trueBox.setAlignment(Pos.CENTER_LEFT);
        trueBox.setStyle(isA
            ? "-fx-background-color: #ecfdf5; -fx-border-color: #10b981; -fx-border-width: 1.5; -fx-border-radius: 8; -fx-background-radius: 8; -fx-padding: 10 18;"
            : "-fx-background-color: #f8fafc; -fx-border-color: #e2e8f0; -fx-border-width: 1; -fx-border-radius: 8; -fx-background-radius: 8; -fx-padding: 10 18;");
        Label trueBadge = new Label("ĐÚNG");
        trueBadge.setStyle(isA ? "-fx-font-weight: bold; -fx-font-size: 14px; -fx-text-fill: #047857;" : "-fx-font-weight: bold; -fx-font-size: 14px; -fx-text-fill: #64748b;");
        trueBox.getChildren().add(trueBadge);
        if (isA) {
            Region r = new Region();
            HBox.setHgrow(r, Priority.ALWAYS);
            Label check = new Label("✓ Đáp án đúng");
            check.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: #047857; -fx-background-color: #d1fae5; -fx-padding: 3 8; -fx-background-radius: 4;");
            trueBox.getChildren().addAll(r, check);
        }

        HBox falseBox = new HBox(10);
        falseBox.setAlignment(Pos.CENTER_LEFT);
        falseBox.setStyle(isB
            ? "-fx-background-color: #ecfdf5; -fx-border-color: #10b981; -fx-border-width: 1.5; -fx-border-radius: 8; -fx-background-radius: 8; -fx-padding: 10 18;"
            : "-fx-background-color: #f8fafc; -fx-border-color: #e2e8f0; -fx-border-width: 1; -fx-border-radius: 8; -fx-background-radius: 8; -fx-padding: 10 18;");
        Label falseBadge = new Label("SAI");
        falseBadge.setStyle(isB ? "-fx-font-weight: bold; -fx-font-size: 14px; -fx-text-fill: #047857;" : "-fx-font-weight: bold; -fx-font-size: 14px; -fx-text-fill: #64748b;");
        falseBox.getChildren().add(falseBadge);
        if (isB) {
            Region r = new Region();
            HBox.setHgrow(r, Priority.ALWAYS);
            Label check = new Label("✓ Đáp án đúng");
            check.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: #047857; -fx-background-color: #d1fae5; -fx-padding: 3 8; -fx-background-radius: 4;");
            falseBox.getChildren().addAll(r, check);
        }

        HBox.setHgrow(trueBox, Priority.ALWAYS);
        HBox.setHgrow(falseBox, Priority.ALWAYS);
        box.getChildren().addAll(trueBox, falseBox);
        return box;
    }

    private Node createFillBlankAnswersNode(QuestionDto q) {
        VBox container = new VBox(10);
        container.setStyle("-fx-background-color: #f0fdf4; -fx-border-color: #86efac; -fx-border-width: 1.2; -fx-border-radius: 8; -fx-background-radius: 8; -fx-padding: 12 16;");

        Label title = new Label("📝 Từ cần điền chính xác vào chỗ trống:");
        title.setStyle("-fx-font-weight: bold; -fx-font-size: 12px; -fx-text-fill: #166534;");
        container.getChildren().add(title);

        FlowPane blanks = new FlowPane();
        blanks.setHgap(16);
        blanks.setVgap(8);

        HBox b1 = new HBox(8);
        b1.setAlignment(Pos.CENTER_LEFT);
        b1.setStyle("-fx-background-color: white; -fx-border-color: #22c55e; -fx-border-width: 1.5; -fx-border-radius: 6; -fx-background-radius: 6; -fx-padding: 6 14;");
        Label tag1 = new Label("Vị trí 1:");
        tag1.setStyle("-fx-font-weight: bold; -fx-font-size: 12px; -fx-text-fill: #15803d;");
        Label val1 = new Label(q.optionA() != null && !q.optionA().isBlank() ? q.optionA() : "-");
        val1.setStyle("-fx-font-weight: bold; -fx-font-size: 14px; -fx-text-fill: #0f172a;");
        b1.getChildren().addAll(tag1, val1);
        blanks.getChildren().add(b1);

        boolean hasBlank2 = q.optionB() != null && !q.optionB().isBlank() && !"-".equals(q.optionB().trim());
        if (hasBlank2) {
            HBox b2 = new HBox(8);
            b2.setAlignment(Pos.CENTER_LEFT);
            b2.setStyle("-fx-background-color: white; -fx-border-color: #22c55e; -fx-border-width: 1.5; -fx-border-radius: 6; -fx-background-radius: 6; -fx-padding: 6 14;");
            Label tag2 = new Label("Vị trí 2:");
            tag2.setStyle("-fx-font-weight: bold; -fx-font-size: 12px; -fx-text-fill: #15803d;");
            Label val2 = new Label(q.optionB());
            val2.setStyle("-fx-font-weight: bold; -fx-font-size: 14px; -fx-text-fill: #0f172a;");
            b2.getChildren().addAll(tag2, val2);
            blanks.getChildren().add(b2);
        }

        container.getChildren().add(blanks);
        return container;
    }

    private Node createShortAnswerNode(QuestionDto q) {
        VBox container = new VBox(8);
        container.setStyle("-fx-background-color: #f8fafc; -fx-border-color: #cbd5e1; -fx-border-width: 1.2; -fx-border-radius: 8; -fx-background-radius: 8; -fx-padding: 12 16;");

        Label title = new Label("✍️ Câu trả lời mẫu / Đáp án chính xác:");
        title.setStyle("-fx-font-weight: bold; -fx-font-size: 12px; -fx-text-fill: #334155;");

        HBox ansBox = new HBox(10);
        ansBox.setAlignment(Pos.CENTER_LEFT);
        ansBox.setStyle("-fx-background-color: white; -fx-border-color: #3b82f6; -fx-border-width: 1.5; -fx-border-radius: 6; -fx-background-radius: 6; -fx-padding: 10 16;");
        Label ansText = new Label(q.optionA() != null ? q.optionA() : "");
        ansText.setWrapText(true);
        ansText.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #1d4ed8;");
        ansBox.getChildren().add(ansText);

        container.getChildren().addAll(title, ansBox);
        return container;
    }

    private Node createMultipleChoiceGrid(QuestionDto q) {
        GridPane grid = new GridPane();
        grid.setHgap(14);
        grid.setVgap(10);
        ColumnConstraints col1 = new ColumnConstraints();
        col1.setPercentWidth(50);
        ColumnConstraints col2 = new ColumnConstraints();
        col2.setPercentWidth(50);
        grid.getColumnConstraints().addAll(col1, col2);

        grid.add(createOptionBox("A", q.optionA(), q.correctOption() == 'A'), 0, 0);
        grid.add(createOptionBox("B", q.optionB(), q.correctOption() == 'B'), 1, 0);
        grid.add(createOptionBox("C", q.optionC(), q.correctOption() == 'C'), 0, 1);
        grid.add(createOptionBox("D", q.optionD(), q.correctOption() == 'D'), 1, 1);
        return grid;
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
            QuestionType selectedType = resolveQuestionType(questionType != null ? questionType.getValue() : null);
            String explanationText = questionExplanation != null ? questionExplanation.getText() : null;
            input = ClientValidation.question(new CreateQuestionDto(
                questionContent.getText(), questionOptionA.getText(), questionOptionB.getText(),
                questionOptionC.getText(), questionOptionD.getText(),
                questionCorrectOption.getValue() != null ? questionCorrectOption.getValue() : 'A', questionTopic.getText(), questionDifficulty.getValue(),
                selectedType, explanationText));
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
            QuestionType updType = resolveQuestionType(questionType != null ? questionType.getValue() : null);
            String updExplanation = questionExplanation != null ? questionExplanation.getText() : null;
            input = ClientValidation.updateQuestion(new UpdateQuestionDto(
                editTarget.id(), questionContent.getText(), questionOptionA.getText(),
                questionOptionB.getText(), questionOptionC.getText(), questionOptionD.getText(),
                questionCorrectOption.getValue() != null ? questionCorrectOption.getValue() : 'A', questionTopic.getText(), questionDifficulty.getValue(),
                updType, updExplanation));
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
        if (questionType != null) questionType.setDisable(writing);
        if (questionExplanation != null) questionExplanation.setDisable(writing);
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
        if (questionType != null) questionType.setValue(questionTypeToLabel(q.questionType()));
        if (questionExplanation != null) questionExplanation.setText(q.explanation() != null ? q.explanation() : "");
        onQuestionTypeChanged(questionType != null ? questionType.getValue() : null);
    }

        private void clearForm() {
        questionContent.clear();
        questionOptionA.clear();
        questionOptionB.clear();
        questionOptionC.clear();
        questionOptionD.clear();
        questionTopic.clear();
        questionDifficulty.setValue(Difficulty.MEDIUM);
        if (questionType != null) {
            questionType.setValue("Trac nghiem (4 lua chon)");
        }
        if (questionExplanation != null) {
            questionExplanation.clear();
        }
        onQuestionTypeChanged("Trac nghiem (4 lua chon)");
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

                private void onQuestionTypeChanged(String type) {
        if (type == null) return;
        boolean isTrueFalse = type.contains("Sai") || type.contains("Đúng") || type.contains("Dung");
        boolean isShortAnswer = type.contains("ngắn") || type.contains("ngan") || type.contains("Trả lời") || type.contains("Tra loi");
        boolean isFillBlank2 = (type.contains("chỗ trống") || type.contains("cho trong") || type.contains("Điền") || type.contains("Dien")) && type.contains("2");
        boolean isFillBlank1 = !isFillBlank2 && (type.contains("chỗ trống") || type.contains("cho trong") || type.contains("Điền") || type.contains("Dien") || type.contains("1"));

        if (questionTypeHint != null) {
            String hint = isTrueFalse
                ? "💡 Dạng Đúng / Sai: Chọn đáp án đúng bên dưới."
                : isFillBlank1
                ? "💡 Dạng Điền khuyết (1 ô): Sử dụng '___' trong nội dung câu hỏi. Nhập từ cần điền chuẩn xác vào ô bên dưới."
                : isFillBlank2
                ? "💡 Dạng Điền khuyết (2 ô): Sử dụng 2 lần '___' trong nội dung câu hỏi. Nhập từ cần điền cho ô 1 và ô 2 tương ứng."
                : isShortAnswer
                ? "💡 Dạng Trả lời ngắn: Nhập nội dung câu hỏi và nhập câu trả lời mẫu / chuẩn xác vào ô bên dưới."
                : "💡 Dạng Trắc nghiệm (4 lựa chọn): Điền đầy đủ 4 phương án A, B, C, D và chọn phương án đúng.";
            questionTypeHint.setText(hint);
        }

        if (isTrueFalse) {
            // Đúng / Sai: Chỉ hiện lựa chọn A (Đúng) và B (Sai)
            questionOptionA.setText("Đúng");
            questionOptionA.setEditable(false);
            questionOptionB.setText("Sai");
            questionOptionB.setEditable(false);
            questionOptionC.setText("-");
            questionOptionD.setText("-");

            if (boxOptionA != null) { boxOptionA.setVisible(false); boxOptionA.setManaged(false); }
            if (boxOptionB != null) { boxOptionB.setVisible(false); boxOptionB.setManaged(false); }
            if (boxOptionC != null) { boxOptionC.setVisible(false); boxOptionC.setManaged(false); }
            if (boxOptionD != null) { boxOptionD.setVisible(false); boxOptionD.setManaged(false); }
            if (boxCorrectOption != null) { boxCorrectOption.setVisible(false); boxCorrectOption.setManaged(false); }

            questionCorrectOption.getItems().setAll('A', 'B');
            if (questionCorrectOption.getValue() == null || questionCorrectOption.getValue() > 'B') {
                questionCorrectOption.setValue('A');
            }

            // Tạo và hiện selector Đúng/Sai inline
            showTrueFalseSelector();
        } else if (isShortAnswer) {
            // Trả lời ngắn: Chỉ hiện 1 ô nhập câu trả lời đúng
            questionOptionA.setEditable(true);
            if ("Đúng".equals(questionOptionA.getText()) || "Dung".equals(questionOptionA.getText())) {
                questionOptionA.clear();
            }
            questionOptionB.setText("-");
            questionOptionC.setText("-");
            questionOptionD.setText("-");

            if (boxOptionA != null) { boxOptionA.setVisible(true); boxOptionA.setManaged(true); }
            if (boxOptionB != null) { boxOptionB.setVisible(false); boxOptionB.setManaged(false); }
            if (boxOptionC != null) { boxOptionC.setVisible(false); boxOptionC.setManaged(false); }
            if (boxOptionD != null) { boxOptionD.setVisible(false); boxOptionD.setManaged(false); }
            if (boxCorrectOption != null) { boxCorrectOption.setVisible(false); boxCorrectOption.setManaged(false); }

            questionCorrectOption.getItems().setAll('A');
            questionCorrectOption.setValue('A');
                    hideTrueFalseSelector();
        } else if (isFillBlank1) {
            // Điền vào chỗ trống 1 ô: Chỉ hiện ô nhập từ cần điền cho ô 1
            questionOptionA.setEditable(true);
            if ("Đúng".equals(questionOptionA.getText()) || "Dung".equals(questionOptionA.getText())) {
                questionOptionA.clear();
            }
            questionOptionB.setText("-");
            questionOptionC.setText("-");
            questionOptionD.setText("-");

            if (boxOptionA != null) { boxOptionA.setVisible(true); boxOptionA.setManaged(true); }
            if (boxOptionB != null) { boxOptionB.setVisible(false); boxOptionB.setManaged(false); }
            if (boxOptionC != null) { boxOptionC.setVisible(false); boxOptionC.setManaged(false); }
            if (boxOptionD != null) { boxOptionD.setVisible(false); boxOptionD.setManaged(false); }
            if (boxCorrectOption != null) { boxCorrectOption.setVisible(false); boxCorrectOption.setManaged(false); }

            questionCorrectOption.getItems().setAll('A');
            questionCorrectOption.setValue('A');
                    hideTrueFalseSelector();
        } else if (isFillBlank2) {
            // Điền vào chỗ trống 2 ô: Hiện 2 ô nhập từ cho ô 1 và ô 2
            questionOptionA.setEditable(true);
            questionOptionB.setEditable(true);
            if ("Đúng".equals(questionOptionA.getText()) || "Dung".equals(questionOptionA.getText())) {
                questionOptionA.clear();
            }
            if ("Sai".equals(questionOptionB.getText()) || "-".equals(questionOptionB.getText())) {
                questionOptionB.clear();
            }
            questionOptionC.setText("-");
            questionOptionD.setText("-");

            if (boxOptionA != null) { boxOptionA.setVisible(true); boxOptionA.setManaged(true); }
            if (boxOptionB != null) { boxOptionB.setVisible(true); boxOptionB.setManaged(true); }
            if (boxOptionC != null) { boxOptionC.setVisible(false); boxOptionC.setManaged(false); }
            if (boxOptionD != null) { boxOptionD.setVisible(false); boxOptionD.setManaged(false); }
            if (boxCorrectOption != null) { boxCorrectOption.setVisible(false); boxCorrectOption.setManaged(false); }

            questionCorrectOption.getItems().setAll('A');
            questionCorrectOption.setValue('A');
        } else {
            // Trắc nghiệm (4 lựa chọn): Hiện đầy đủ 4 ô A, B, C, D và ô chọn đáp án đúng
            questionOptionA.setEditable(true);
            questionOptionB.setEditable(true);
            questionOptionC.setEditable(true);
            questionOptionD.setEditable(true);
            if ("Đúng".equals(questionOptionA.getText()) || "Dung".equals(questionOptionA.getText())) {
                questionOptionA.clear();
            }
            if ("Sai".equals(questionOptionB.getText())) {
                questionOptionB.clear();
            }
            if ("-".equals(questionOptionC.getText())) {
                questionOptionC.clear();
            }
            if ("-".equals(questionOptionD.getText())) {
                questionOptionD.clear();
            }

            if (boxOptionA != null) { boxOptionA.setVisible(true); boxOptionA.setManaged(true); }
            if (boxOptionB != null) { boxOptionB.setVisible(true); boxOptionB.setManaged(true); }
            if (boxOptionC != null) { boxOptionC.setVisible(true); boxOptionC.setManaged(true); }
            if (boxOptionD != null) { boxOptionD.setVisible(true); boxOptionD.setManaged(true); }
            if (boxCorrectOption != null) { boxCorrectOption.setVisible(true); boxCorrectOption.setManaged(true); }

            hideTrueFalseSelector();

            Character prev = questionCorrectOption.getValue();
            questionCorrectOption.getItems().setAll('A', 'B', 'C', 'D');
            if (prev != null && questionCorrectOption.getItems().contains(prev)) {
                questionCorrectOption.setValue(prev);
            } else if (questionCorrectOption.getValue() == null) {
                questionCorrectOption.setValue('A');
            }
        }

        // Cập nhật nhãn và placeholder gợi ý trực quan theo dạng câu hỏi
        if (labelOptionA != null) {
            labelOptionA.setText(
                isTrueFalse ? "Phương án A (Đúng) *" :
                isFillBlank1 || isFillBlank2 ? "Từ cần điền (Vị trí 1) *" :
                isShortAnswer ? "Câu trả lời đúng / mẫu *" :
                "Đáp án A *"
            );
        }
        if (questionOptionA != null) {
            questionOptionA.setPromptText(
                isTrueFalse ? "Đúng" :
                isFillBlank1 || isFillBlank2 ? "Ví dụ: went, was, are, conditional..." :
                isShortAnswer ? "Nhập câu trả lời ngắn chuẩn xác..." :
                "Nội dung đáp án A"
            );
        }

        if (labelOptionB != null) {
            labelOptionB.setText(
                isTrueFalse ? "Phương án B (Sai) *" :
                isFillBlank2 ? "Từ cần điền (Vị trí 2) *" :
                "Đáp án B *"
            );
        }
        if (questionOptionB != null) {
            questionOptionB.setPromptText(
                isTrueFalse ? "Sai" :
                isFillBlank2 ? "Ví dụ: had, would, been..." :
                "Nội dung đáp án B"
            );
        }

        if (questionOptionC != null) questionOptionC.setPromptText("Nội dung đáp án C");
        if (questionOptionD != null) questionOptionD.setPromptText("Nội dung đáp án D");
        if (labelOptionC != null) labelOptionC.setText("Đáp án C *");
        if (labelOptionD != null) labelOptionD.setText("Đáp án D *");
    }

    /** Creates and shows the inline True/False selector in the form. */
    private void showTrueFalseSelector() {
        hideTrueFalseSelector(); // remove previous if any
        trueFalseGroup = new ToggleGroup();

        ToggleButton btnTrue = new ToggleButton("✓  ĐÚNG");
        btnTrue.setToggleGroup(trueFalseGroup);
        btnTrue.setUserData('A');
        btnTrue.setPrefHeight(40);
        btnTrue.setMinWidth(130);
        btnTrue.setStyle("-fx-font-weight: bold; -fx-font-size: 14px; -fx-padding: 8 24; -fx-background-radius: 8; -fx-cursor: hand;"
            + "-fx-background-color: #f0fdf4; -fx-text-fill: #166534; -fx-border-color: #86efac; -fx-border-radius: 8; -fx-border-width: 1.5;");

        ToggleButton btnFalse = new ToggleButton("✗  SAI");
        btnFalse.setToggleGroup(trueFalseGroup);
        btnFalse.setUserData('B');
        btnFalse.setPrefHeight(40);
        btnFalse.setMinWidth(130);
        btnFalse.setStyle("-fx-font-weight: bold; -fx-font-size: 14px; -fx-padding: 8 24; -fx-background-radius: 8; -fx-cursor: hand;"
            + "-fx-background-color: #fef2f2; -fx-text-fill: #991b1b; -fx-border-color: #fca5a5; -fx-border-radius: 8; -fx-border-width: 1.5;");

        // Pre-select based on current correctOption
        Character current = questionCorrectOption.getValue();
        if (current != null && current == 'B') {
            btnFalse.setSelected(true);
            btnFalse.setStyle("-fx-font-weight: bold; -fx-font-size: 14px; -fx-padding: 8 24; -fx-background-radius: 8; -fx-cursor: hand;"
                + "-fx-background-color: #dc2626; -fx-text-fill: white; -fx-border-color: #dc2626; -fx-border-radius: 8; -fx-border-width: 1.5;");
        } else {
            btnTrue.setSelected(true);
            btnTrue.setStyle("-fx-font-weight: bold; -fx-font-size: 14px; -fx-padding: 8 24; -fx-background-radius: 8; -fx-cursor: hand;"
                + "-fx-background-color: #16a34a; -fx-text-fill: white; -fx-border-color: #16a34a; -fx-border-radius: 8; -fx-border-width: 1.5;");
        }

        trueFalseGroup.selectedToggleProperty().addListener((obs, oldToggle, newToggle) -> {
            if (newToggle != null) {
                questionCorrectOption.setValue((Character) newToggle.getUserData());
                btnTrue.setStyle("-fx-font-weight: bold; -fx-font-size: 14px; -fx-padding: 8 24; -fx-background-radius: 8; -fx-cursor: hand;"
                    + (newToggle == btnTrue
                        ? "-fx-background-color: #16a34a; -fx-text-fill: white; -fx-border-color: #16a34a; -fx-border-radius: 8; -fx-border-width: 1.5;"
                        : "-fx-background-color: #f0fdf4; -fx-text-fill: #166534; -fx-border-color: #86efac; -fx-border-radius: 8; -fx-border-width: 1.5;"));
                btnFalse.setStyle("-fx-font-weight: bold; -fx-font-size: 14px; -fx-padding: 8 24; -fx-background-radius: 8; -fx-cursor: hand;"
                    + (newToggle == btnFalse
                        ? "-fx-background-color: #dc2626; -fx-text-fill: white; -fx-border-color: #dc2626; -fx-border-radius: 8; -fx-border-width: 1.5;"
                        : "-fx-background-color: #fef2f2; -fx-text-fill: #991b1b; -fx-border-color: #fca5a5; -fx-border-radius: 8; -fx-border-width: 1.5;"));
            } else {
                if (oldToggle != null) oldToggle.setSelected(true);
            }
        });

        Label selectorLabel = new Label("Chọn đáp án đúng của câu hỏi: *");
        selectorLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 13px; -fx-text-fill: #1e293b;");

        HBox buttonsBox = new HBox(12, btnTrue, btnFalse);
        buttonsBox.setAlignment(Pos.CENTER_LEFT);

        trueFalseSelector = new HBox(20, selectorLabel, buttonsBox);
        trueFalseSelector.setAlignment(Pos.CENTER_LEFT);
        trueFalseSelector.setStyle("-fx-padding: 12 16; -fx-background-color: #ffffff; -fx-border-color: #cbd5e1; -fx-border-radius: 8; -fx-background-radius: 8;");

        if (questionForm != null) {
            int insertIdx = -1;
            if (optionsGridContainer != null) {
                optionsGridContainer.setVisible(false);
                optionsGridContainer.setManaged(false);
                insertIdx = questionForm.getChildren().indexOf(optionsGridContainer);
            }
            if (insertIdx < 0 && questionContent != null) {
                Node contentParent = questionContent.getParent();
                insertIdx = questionForm.getChildren().indexOf(contentParent);
                if (insertIdx >= 0) insertIdx++;
            }
            if (insertIdx >= 0 && insertIdx <= questionForm.getChildren().size()) {
                questionForm.getChildren().add(insertIdx, trueFalseSelector);
            } else {
                questionForm.getChildren().add(trueFalseSelector);
            }
        }
    }

    private void hideTrueFalseSelector() {
        if (trueFalseSelector != null && questionForm != null) {
            questionForm.getChildren().remove(trueFalseSelector);
        }
        trueFalseSelector = null;
        trueFalseGroup = null;
        if (optionsGridContainer != null) {
            optionsGridContainer.setVisible(true);
            optionsGridContainer.setManaged(true);
        }
    }

    private static QuestionType resolveQuestionType(String label) {
        if (label == null) return QuestionType.MULTIPLE_CHOICE;
        String s = label.trim().toLowerCase();
        if (s.contains("đúng") || s.contains("dung") || s.contains("sai") || s.contains("true")) {
            return QuestionType.TRUE_FALSE;
        }
        if (s.contains("điền") || s.contains("dien") || s.contains("trống") || s.contains("trong") || s.contains("blank") || s.contains("khuyết") || s.contains("khuyet")) {
            return QuestionType.FILL_BLANK;
        }
        if (s.contains("trả lời") || s.contains("tra loi") || s.contains("ngắn") || s.contains("ngan") || s.contains("short")) {
            return QuestionType.SHORT_ANSWER;
        }
        return QuestionType.MULTIPLE_CHOICE;
    }

    private static QuestionType matchFilterQuestionType(String filterLabel) {
        if (filterLabel == null) return null;
        String s = filterLabel.trim().toLowerCase();
        if (s.isEmpty() || s.contains("tất cả") || s.contains("tat ca") || s.startsWith("t") && s.contains("c")) {
            return null;
        }
        if (s.contains("đúng") || s.contains("dung") || s.contains("sai") || s.contains("true")) {
            return QuestionType.TRUE_FALSE;
        }
        if (s.contains("điền") || s.contains("dien") || s.contains("khuyết") || s.contains("khuyet") || s.contains("trống") || s.contains("trong") || s.contains("blank")) {
            return QuestionType.FILL_BLANK;
        }
        if (s.contains("trả lời") || s.contains("tra loi") || s.contains("ngắn") || s.contains("ngan") || s.contains("short")) {
            return QuestionType.SHORT_ANSWER;
        }
        if (s.contains("trắc nghiệm") || s.contains("trac nghiem") || s.contains("choice")) {
            return QuestionType.MULTIPLE_CHOICE;
        }
        return null;
    }

        private static String questionTypeToLabel(QuestionType type) {
        if (type == null) return "Trac nghiem (4 lua chon)";
        return switch (type) {
            case TRUE_FALSE -> "Dung / Sai";
            case FILL_BLANK -> "Dien vao cho trong (1 o)";
            case SHORT_ANSWER -> "Tra loi ngan";
            default -> "Trac nghiem (4 lua chon)";
        };
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
