package quiz.client.ui.teacher;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import quiz.client.api.ApiException;
import quiz.client.api.QuizApi;
import quiz.client.ui.SceneNavigator;
import quiz.client.ui.ScreenController;
import quiz.client.util.FxAsync;
import quiz.common.dto.*;
import quiz.common.enums.AntiCheatEventType;
import quiz.common.enums.AntiCheatPolicyType;
import quiz.common.enums.Difficulty;
import quiz.common.enums.QuizStatus;
import quiz.common.enums.SubmitReason;
import quiz.common.protocol.ErrorCode;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Controller cho không gian giảng dạy của Giáo viên (Bắc: FR-A04, FR-A05, FR-A06, FR-A07, FR-A08, FR-A09).
 */
public final class TeacherController extends ScreenController {
    private static final DateTimeFormatter DATE_FORMAT =
        DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault());

    private final QuizApi api;
    private final SceneNavigator navigator;
    private final FxAsync fx;

    // --- Tab 1: Quiz Controls ---
    @FXML private ScrollPane quizScrollPane;
    @FXML private VBox quizOverview;
    @FXML private TableView<QuizDto> quizTable;
    @FXML private Label totalQuizzesCount;
    @FXML private Label openQuizzesCount;
    @FXML private Label draftQuizzesCount;
    @FXML private Label quizNotice;
    @FXML private ProgressIndicator quizProgress;

    // Quiz action buttons
    @FXML private Button btnNewQuiz;
    @FXML private Button btnAutoGenerateQuiz;
    @FXML private Button btnEditQuiz;
    @FXML private Button btnSelectQuestions;
    @FXML private Button btnOpenQuiz;
    @FXML private Button btnCloseQuiz;
    @FXML private Button btnAssignQuiz;
    @FXML private Button btnViewDetail;
    @FXML private Button btnRefreshQuiz;

    // Quiz Form (Manual Create / Edit)
    @FXML private VBox quizFormPanel;
    @FXML private Label formTitle;
    @FXML private TextField txtQuizTitle;
    @FXML private TextField txtQuizCategory;
    @FXML private TextField txtQuizDuration;
    @FXML private Label lblDurationError;
    @FXML private TextField txtQuizPassScore;
    @FXML private ComboBox<AntiCheatPolicyType> cmbQuizPolicy;
    @FXML private TextField txtQuizMaxViolations;
    @FXML private TextArea txtQuizDescription;
    @FXML private Label formError;
    @FXML private Button btnSaveQuiz;
    @FXML private Button btnCancelQuizForm;
    private boolean isEditingQuiz = false;
    private long editingQuizId = 0;

    // Auto Generate Quiz Panel (Feature 1 & 4)
    @FXML private VBox autoQuizPanel;
    @FXML private Label autoQuizError;
    @FXML private TextField txtAutoQuizTitle;
    @FXML private TextField txtAutoQuizCategory;
    @FXML private TextField txtAutoQuizDuration;
    @FXML private Label lblAutoDurationError;
    @FXML private TextField txtAutoTotalQuestions;
    @FXML private TextField txtAutoQuizPassScore;
    @FXML private ComboBox<AntiCheatPolicyType> cmbAutoQuizPolicy;
    @FXML private TextField txtAutoQuizMaxViolations;
    @FXML private TextField txtEasyPct;
    @FXML private TextField txtMediumPct;
    @FXML private TextField txtHardPct;
    @FXML private Label lblEasyPercent;
    @FXML private Label lblMediumPercent;
    @FXML private Label lblHardPercent;
    @FXML private Label lblEasyCount;
    @FXML private Label lblMediumCount;
    @FXML private Label lblHardCount;
    @FXML private Label lblAutoDistributionSummary;
    @FXML private Label lblAutoPercentageError;
    @FXML private Button btnConfirmAutoGenerate;

    // Question Selection Panel
    @FXML private VBox questionSelectionPanel;
    @FXML private Label questionSelectionTitle;
    @FXML private TableView<QuestionRow> questionTable;
    @FXML private Label selectedQuestionsCount;
    @FXML private Button btnSaveSelectedQuestions;
    @FXML private Button btnCancelQuestionSelection;
    private long targetQuizIdForQuestions = 0;

    // Assign Quiz to Multiple Classes Panel (Feature 3)
    @FXML private VBox assignPanel;
    @FXML private Label assignQuizTitle;
    @FXML private CheckBox chkSelectAllClasses;
    @FXML private Label lblSelectedClassesCount;
    @FXML private TableView<AssignClassRow> assignClassTable;
    @FXML private Button btnConfirmAssign;
    @FXML private Button btnCancelAssign;
    private long targetQuizIdForAssign = 0;

    // Quiz Details & Review/Edit Panel (Feature 2)
    @FXML private VBox quizDetailPanel;
    @FXML private Label detailQuizTitle;
    @FXML private Label detailStatusBadge;
    @FXML private Label detailQuizInfo;
    @FXML private Label detailQuestionsCount;
    @FXML private Label detailDifficultyBreakdown;
    @FXML private Label detailAssignedClasses;
    @FXML private Label detailNotice;
    @FXML private Button btnDetailAddQuestion;
    @FXML private Button btnDetailSaveQuestions;
    @FXML private TableView<DetailQuestionRow> detailQuestionTable;
    @FXML private Button btnCloseDetail;

    // Question Bank Picker Panel (Sub-panel of Detail)
    @FXML private VBox questionPickerPanel;
    @FXML private Label lblPickerTitle;
    @FXML private TextField txtPickerSearch;
    @FXML private ComboBox<Difficulty> cmbPickerDifficulty;
    @FXML private TableView<QuestionDto> pickerTable;
    @FXML private Button btnConfirmPicker;

    // --- Tab 2: Class Controls ---
    @FXML private ScrollPane classScrollPane;
    @FXML private TableView<ClassDto> classTable;
    @FXML private TableView<ClassMemberDto> memberTable;
    @FXML private Label selectedClassLabel;
    @FXML private Label classNotice;
    @FXML private ProgressIndicator classProgress;
    @FXML private Button btnRefreshClasses;

    // Class scoped results workspace
    @FXML private ComboBox<ClassDto> resultsClassCombo;
    @FXML private ComboBox<QuizDto> resultsQuizCombo;
    @FXML private Label classResultsNotice;
    @FXML private Label classResultsCount;
    @FXML private Label classResultsAverage;
    @FXML private Label classResultsPassRate;
    @FXML private Label classResultsHighest;
    @FXML private Label classResultsQuizTitle;
    @FXML private Label classResultsStatusBadge;
    @FXML private Label classResultsQuizInfo;
    @FXML private TableView<ResultDto> classResultsTable;

    // Observable Lists & State
    private final ObservableList<QuizDto> quizList = FXCollections.observableArrayList();
    private final ObservableList<ClassDto> classList = FXCollections.observableArrayList();
    private final ObservableList<ClassMemberDto> memberList = FXCollections.observableArrayList();
    private final ObservableList<QuestionRow> questionRows = FXCollections.observableArrayList();
    private final ObservableList<AssignClassRow> assignClassRows = FXCollections.observableArrayList();
    private final ObservableList<DetailQuestionRow> detailQuestionRows = FXCollections.observableArrayList();
    private final ObservableList<QuestionDto> pickerQuestionRows = FXCollections.observableArrayList();
    private final ObservableList<ResultDto> classResultsList = FXCollections.observableArrayList();
    private List<ResultDto> allClassResults = List.of();

    private List<QuestionDto> allBankQuestions = new ArrayList<>();
    private DetailQuestionRow questionToReplace = null;
    private boolean isPickerForReplace = false;
    private boolean isDetailQuizDraft = false;
    private long currentDetailQuizId = 0;

    public TeacherController(QuizApi api, SceneNavigator navigator, FxAsync fx) {
        this.api = Objects.requireNonNull(api);
        this.navigator = Objects.requireNonNull(navigator);
        this.fx = Objects.requireNonNull(fx);
    }

    @FXML
    private void initialize() {
        btnRefreshClasses.setTooltip(new Tooltip("Tải lại danh sách lớp và học viên"));
        configureQuizTable();
        configureClassTable();
        configureMemberTable();
        configureQuestionTable();
        configureAssignClassTable();
        configureDetailQuestionTable();
        configurePickerTable();
        configureClassResultsWorkspace();

        // 1. Format Chính sách vi phạm (cmbQuizPolicy & cmbAutoQuizPolicy)
        javafx.util.StringConverter<AntiCheatPolicyType> policyConverter = new javafx.util.StringConverter<>() {
            @Override
            public String toString(AntiCheatPolicyType p) {
                if (p == null) return "";
                return p == AntiCheatPolicyType.WARN_THEN_AUTO_SUBMIT
                    ? "Cảnh báo và tự động nộp bài khi quá số lần"
                    : "Chỉ ghi nhận nhật ký (không tự nộp bài)";
            }
            @Override
            public AntiCheatPolicyType fromString(String s) { return null; }
        };
        setupPolicyCombo(cmbQuizPolicy, policyConverter);
        setupPolicyCombo(cmbAutoQuizPolicy, policyConverter);

        // 2. Format Combobox Độ khó trong bộ chọn câu hỏi thay thế/thêm
        cmbPickerDifficulty.getItems().setAll(Difficulty.values());
        cmbPickerDifficulty.valueProperty().addListener((obs, o, n) -> filterPickerQuestions());
        txtPickerSearch.textProperty().addListener((obs, o, n) -> filterPickerQuestions());

        // 3. Feature 4: Validation lỗi thời lượng - hiển thị lỗi trực tiếp dưới box khi gõ chữ
        setupDurationValidation(txtQuizDuration, lblDurationError);
        setupDurationValidation(txtAutoQuizDuration, lblAutoDurationError);

        // 4. Feature 1: Listeners cho TextField tỷ lệ độ khó - Hard tự tính = 100 - Easy - Medium
        txtEasyPct.textProperty().addListener((obs, o, n) -> updateAutoDistribution());
        txtMediumPct.textProperty().addListener((obs, o, n) -> updateAutoDistribution());
        txtAutoTotalQuestions.textProperty().addListener((obs, o, n) -> updateAutoDistribution());

        quizTable.getSelectionModel().selectedItemProperty().addListener((obs, oldV, selected) -> updateQuizButtons(selected));
        classTable.getSelectionModel().selectedItemProperty().addListener((obs, oldV, selected) -> onClassSelected(selected));

        hideAllQuizPanels();
        updateQuizButtons(null);
    }

    private void setupPolicyCombo(ComboBox<AntiCheatPolicyType> cmb, javafx.util.StringConverter<AntiCheatPolicyType> policyConverter) {
        if (cmb == null) return;
        cmb.setConverter(policyConverter);
        cmb.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(AntiCheatPolicyType p, boolean empty) {
                super.updateItem(p, empty);
                setText(empty || p == null ? null : policyConverter.toString(p));
            }
        });
        cmb.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(AntiCheatPolicyType p, boolean empty) {
                super.updateItem(p, empty);
                setText(empty || p == null ? null : policyConverter.toString(p));
            }
        });
        cmb.getItems().setAll(AntiCheatPolicyType.WARN_THEN_AUTO_SUBMIT, AntiCheatPolicyType.LOG_ONLY);
        cmb.setValue(AntiCheatPolicyType.WARN_THEN_AUTO_SUBMIT);
    }

    /**
     * Feature 4: Kiểm tra trực tiếp khi gõ vào box thời lượng. Nếu có chữ sẽ hiện lỗi ngay bên dưới.
     */
    private void setupDurationValidation(TextField field, Label errorLabel) {
        if (field == null || errorLabel == null) return;
        field.textProperty().addListener((obs, oldV, newV) -> {
            if (newV != null && !newV.isBlank() && !newV.trim().matches("\\d+")) {
                errorLabel.setText("⚠️ Không được viết chữ vào đây, chỉ được nhập số nguyên phút (ví dụ: 15, 30, 45)!");
                errorLabel.setVisible(true);
                errorLabel.setManaged(true);
                field.setStyle("-fx-border-color: #dc2626; -fx-border-width: 1.5px; -fx-border-radius: 4px;");
            } else {
                errorLabel.setVisible(false);
                errorLabel.setManaged(false);
                field.setStyle("");
            }
        });
    }

    @Override
    protected void onMount() {
        loadQuizzes();
        loadClasses();
    }

    // =========================================================================
    // QUIZ TABLE & GENERAL BUTTONS
    // =========================================================================

    private void configureQuizTable() {
        TableColumn<QuizDto, String> colId = new TableColumn<>("ID");
        colId.setPrefWidth(55);
        colId.setCellValueFactory(d -> new SimpleStringProperty(String.valueOf(d.getValue().id())));

        TableColumn<QuizDto, String> colTitle = new TableColumn<>("Tiêu đề bài kiểm tra");
        colTitle.setPrefWidth(220);
        colTitle.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().title()));

        TableColumn<QuizDto, String> colCategory = new TableColumn<>("Chủ đề");
        colCategory.setPrefWidth(120);
        colCategory.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().category() == null ? "—" : d.getValue().category()));

        TableColumn<QuizDto, String> colDuration = new TableColumn<>("Thời lượng");
        colDuration.setPrefWidth(90);
        colDuration.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().durationMinutes() + " phút"));

        TableColumn<QuizDto, String> colPass = new TableColumn<>("Điểm đạt");
        colPass.setPrefWidth(75);
        colPass.setCellValueFactory(d -> new SimpleStringProperty(String.format("%.0f%%", d.getValue().passScore())));

        TableColumn<QuizDto, String> colStatus = new TableColumn<>("Trạng thái");
        colStatus.setPrefWidth(110);
        colStatus.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().status().name()));
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
                    if ("DRAFT".equals(item)) {
                        badge.setText("BẢN NHÁP");
                        badge.getStyleClass().add("badge-draft");
                    } else if ("OPEN".equals(item)) {
                        badge.setText("ĐANG MỞ");
                        badge.getStyleClass().add("badge-open");
                    } else {
                        badge.setText("ĐÃ ĐÓNG");
                        badge.getStyleClass().add("badge-closed");
                    }
                    setGraphic(badge);
                    setText(null);
                }
            }
        });

        TableColumn<QuizDto, String> colPin = new TableColumn<>("Mã PIN");
        colPin.setPrefWidth(90);
        colPin.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().pin() == null ? "—" : d.getValue().pin()));
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

        TableColumn<QuizDto, String> colCreated = new TableColumn<>("Ngày tạo");
        colCreated.setPrefWidth(130);
        colCreated.setCellValueFactory(d -> new SimpleStringProperty(DATE_FORMAT.format(d.getValue().createdAt())));

        quizTable.getColumns().setAll(List.of(colId, colTitle, colCategory, colDuration, colPass, colStatus, colPin, colCreated));
        quizTable.setItems(quizList);
        quizTable.setPlaceholder(new Label("Chưa có đề kiểm tra nào. Bấm 'Tạo Đề Mới' hoặc 'Tạo Đề Tự Động' để bắt đầu."));
    }

    private void updateQuizButtons(QuizDto selected) {
        boolean hasSel = selected != null;
        btnEditQuiz.setDisable(!hasSel || selected.status() != QuizStatus.DRAFT);
        btnSelectQuestions.setDisable(!hasSel || selected.status() != QuizStatus.DRAFT);
        btnOpenQuiz.setDisable(!hasSel || selected.status() != QuizStatus.DRAFT);
        btnCloseQuiz.setDisable(!hasSel || selected.status() != QuizStatus.OPEN);
        btnAssignQuiz.setDisable(!hasSel);
        btnViewDetail.setDisable(!hasSel);
    }

    @FXML
    private void loadQuizzes() {
        setQuizLoading(true);
        fx.observe(api.listQuizzes(), this::isActive,
            quizzes -> {
                quizList.setAll(quizzes);
                updateStats(quizzes);
                setQuizLoading(false);
            },
            err -> {
                showQuizNotice("Lỗi khi tải danh sách đề thi: " + formatError(err), true);
                setQuizLoading(false);
            },
            () -> setQuizLoading(false)
        );
    }

    private void updateStats(List<QuizDto> quizzes) {
        long total = quizzes.size();
        long open = quizzes.stream().filter(q -> q.status() == QuizStatus.OPEN).count();
        long draft = quizzes.stream().filter(q -> q.status() == QuizStatus.DRAFT).count();

        totalQuizzesCount.setText(String.valueOf(total));
        openQuizzesCount.setText(String.valueOf(open));
        draftQuizzesCount.setText(String.valueOf(draft));
    }

    // =========================================================================
    // FEATURE 1: TẠO ĐỀ TỰ ĐỘNG (AUTO GENERATE BY DIFFICULTY % & QUESTION COUNT)
    // =========================================================================

    @FXML
    private void onAutoGenerateQuizClick() {
        hideAllQuizPanels();
        txtAutoQuizTitle.setText("Đề Kiểm Tra Tự Động - " + java.time.LocalDate.now());
        txtAutoQuizCategory.setText("Tiếng Anh");
        txtAutoQuizDuration.setText("30");
        txtAutoTotalQuestions.setText("30");
        txtAutoQuizPassScore.setText("50");
        txtAutoQuizMaxViolations.setText("3");
        cmbAutoQuizPolicy.setValue(AntiCheatPolicyType.WARN_THEN_AUTO_SUBMIT);

        txtEasyPct.setText("30");
        txtMediumPct.setText("30");
        // Hard tự tính = 40 (100 - 30 - 30), không cần set thủ công
        updateAutoDistribution();

        autoQuizError.setVisible(false);
        lblAutoDurationError.setVisible(false);
        autoQuizPanel.setVisible(true);
        autoQuizPanel.setManaged(true);
        scrollToBottom();
    }

    @FXML
    private void onPreset1Click() {
        txtEasyPct.setText("30");
        txtMediumPct.setText("30");
        // Hard = 40 tự tính
    }

    @FXML
    private void onPreset2Click() {
        txtEasyPct.setText("40");
        txtMediumPct.setText("40");
        // Hard = 20 tự tính
    }

    @FXML
    private void onPreset3Click() {
        txtEasyPct.setText("20");
        txtMediumPct.setText("50");
        // Hard = 30 tự tính
    }

    @FXML
    private void onPreset4Click() {
        txtEasyPct.setText("50");
        txtMediumPct.setText("30");
        // Hard = 20 tự tính
    }

    private void updateAutoDistribution() {
        int totalQuestions = parsePositiveInt(txtAutoTotalQuestions != null ? txtAutoTotalQuestions.getText() : "30", 30);

        // Đọc Easy và Medium từ TextField; Hard = 100 - Easy - Medium (tự tính)
        String easyRaw = txtEasyPct != null ? txtEasyPct.getText().trim() : "30";
        String medRaw  = txtMediumPct != null ? txtMediumPct.getText().trim() : "30";

        boolean easyValid  = easyRaw.matches("\\d+");
        boolean medValid   = medRaw.matches("\\d+");

        int easyPct = easyValid ? Integer.parseInt(easyRaw) : -1;
        int medPct  = medValid  ? Integer.parseInt(medRaw)  : -1;

        // Highlight lỗi nhập chữ cho Easy
        if (txtEasyPct != null) {
            txtEasyPct.setStyle(easyValid ? "" : "-fx-border-color: #dc2626; -fx-border-width: 1.5px; -fx-border-radius: 6;");
        }
        // Highlight lỗi nhập chữ cho Medium
        if (txtMediumPct != null) {
            txtMediumPct.setStyle(medValid ? "" : "-fx-border-color: #dc2626; -fx-border-width: 1.5px; -fx-border-radius: 6;");
        }

        if (!easyValid || !medValid) {
            // Còn đang gõ hoặc sai định dạng, chưa tính
            if (lblAutoPercentageError != null) {
                lblAutoPercentageError.setText("⚠️ Vui lòng nhập số nguyên (0–100) cho Easy và Medium.");
                lblAutoPercentageError.setVisible(true);
                lblAutoPercentageError.setManaged(true);
            }
            if (lblAutoDistributionSummary != null) {
                lblAutoDistributionSummary.setStyle("-fx-font-weight: bold; -fx-text-fill: #dc2626;");
            }
            return;
        }

        // Hard tự tính, clamp về [0, 100]
        int hardPct = Math.max(0, Math.min(100, 100 - easyPct - medPct));
        int totalPct = easyPct + medPct + hardPct; // luôn = 100 trừ khi Easy+Medium > 100

        // Cập nhật ô Hard (read-only)
        if (txtHardPct != null) txtHardPct.setText(String.valueOf(hardPct));

        // Tính số câu
        int easyCount = (int) Math.round(totalQuestions * (easyPct / 100.0));
        int medCount  = (int) Math.round(totalQuestions * (medPct  / 100.0));
        int hardCount = Math.max(0, totalQuestions - easyCount - medCount);

        // Cập nhật labels tỷ lệ %
        if (lblEasyPercent   != null) lblEasyPercent.setText(easyPct + "%");
        if (lblMediumPercent != null) lblMediumPercent.setText(medPct + "%");
        if (lblHardPercent   != null) lblHardPercent.setText(hardPct + "%");

        // Cập nhật labels số câu
        if (lblEasyCount   != null) lblEasyCount.setText("→ " + easyCount + " câu");
        if (lblMediumCount != null) lblMediumCount.setText("→ " + medCount + " câu");
        if (lblHardCount   != null) lblHardCount.setText("→ " + hardCount + " câu");

        // Validation: Easy + Medium không được > 100
        boolean overLimit = (easyPct + medPct > 100);

        if (lblAutoPercentageError != null) {
            lblAutoPercentageError.setVisible(overLimit);
            lblAutoPercentageError.setManaged(overLimit);
            if (overLimit) {
                lblAutoPercentageError.setText("⚠️ Easy + Medium = " + (easyPct + medPct) + "% > 100%. Hãy giảm bớt!");
            }
        }

        if (lblAutoDistributionSummary != null) {
            lblAutoDistributionSummary.setText(String.format(
                "Phân bổ: %d Dễ (%d%%) + %d Vừa (%d%%) + %d Khó (%d%%) = %d câu hỏi (Tổng: %d%%)",
                easyCount, easyPct, medCount, medPct, hardCount, hardPct, totalQuestions, easyPct + medPct + hardPct));
        }

        if (!overLimit) {
            lblAutoDistributionSummary.setStyle("-fx-font-weight: bold; -fx-text-fill: #0f766e;");
        } else {
            lblAutoDistributionSummary.setStyle("-fx-font-weight: bold; -fx-text-fill: #dc2626;");
        }
    }

    @FXML
    private void onConfirmAutoGenerateClick() {
        String title = txtAutoQuizTitle.getText();
        String category = txtAutoQuizCategory.getText();
        String durationText = txtAutoQuizDuration.getText();
        String totalQText = txtAutoTotalQuestions.getText();
        String passScoreText = txtAutoQuizPassScore.getText();
        String maxViolationsText = txtAutoQuizMaxViolations.getText();

        if (title == null || title.strip().isBlank()) {
            showAutoQuizError("Tiêu đề đề thi không được để trống");
            return;
        }

        int duration;
        try {
            duration = Integer.parseInt(durationText.trim());
            if (duration <= 0) throw new NumberFormatException();
        } catch (Exception e) {
            lblAutoDurationError.setText("⚠️ Không được viết chữ vào đây, chỉ được nhập số nguyên phút!");
            lblAutoDurationError.setVisible(true);
            lblAutoDurationError.setManaged(true);
            txtAutoQuizDuration.setStyle("-fx-border-color: #dc2626; -fx-border-width: 1.5px;");
            showAutoQuizError("Thời lượng làm bài phải là số nguyên phút lớn hơn 0");
            return;
        }

        int totalQuestions;
        try {
            totalQuestions = Integer.parseInt(totalQText.trim());
            if (totalQuestions <= 0) throw new NumberFormatException();
        } catch (Exception e) {
            showAutoQuizError("Tổng số câu hỏi phải là số nguyên lớn hơn 0");
            return;
        }

        double passScore;
        try {
            passScore = Double.parseDouble(passScoreText.trim());
            if (passScore < 0 || passScore > 100) throw new NumberFormatException();
        } catch (Exception e) {
            showAutoQuizError("Điểm đạt phải là số từ 0 đến 100");
            return;
        }

        int maxViolations;
        try {
            maxViolations = Integer.parseInt(maxViolationsText.trim());
            if (maxViolations <= 0) throw new NumberFormatException();
        } catch (Exception e) {
            showAutoQuizError("Số vi phạm tối đa phải là số nguyên lớn hơn 0");
            return;
        }

        int easyPct;
        int medPct;
        try {
            easyPct = Integer.parseInt(txtEasyPct.getText().trim());
            medPct  = Integer.parseInt(txtMediumPct.getText().trim());
        } catch (Exception e) {
            showAutoQuizError("Tỷ lệ Easy và Medium phải là số nguyên (0–100).");
            return;
        }
        if (easyPct < 0 || medPct < 0 || easyPct + medPct > 100) {
            showAutoQuizError("Easy + Medium = " + (easyPct + medPct) + "% > 100%. Hãy điều chỉnh lại!");
            return;
        }
        int hardPct = 100 - easyPct - medPct;

        int easyCount = (int) Math.round(totalQuestions * (easyPct / 100.0));
        int medCount = (int) Math.round(totalQuestions * (medPct / 100.0));
        int hardCount = Math.max(0, totalQuestions - easyCount - medCount);

        btnConfirmAutoGenerate.setDisable(true);
        setQuizLoading(true);

        // Lấy câu hỏi từ ngân hàng câu hỏi và bốc ngẫu nhiên theo tỷ lệ độ khó
        api.listQuestions().whenComplete((allQuestions, err) -> fx.run(() -> {
            if (err != null) {
                setQuizLoading(false);
                btnConfirmAutoGenerate.setDisable(false);
                showAutoQuizError("Lỗi khi tải ngân hàng câu hỏi: " + formatError(err));
                return;
            }

            // Lọc câu hỏi theo chủ đề nếu có nhập
            List<QuestionDto> candidateQuestions = allQuestions;
            if (category != null && !category.strip().isBlank()) {
                String catLower = category.strip().toLowerCase();
                List<QuestionDto> filtered = allQuestions.stream()
                    .filter(q -> q.topic() != null && q.topic().toLowerCase().contains(catLower))
                    .toList();
                if (filtered.size() >= totalQuestions) {
                    candidateQuestions = filtered;
                }
            }

            List<QuestionDto> easyList = new ArrayList<>(candidateQuestions.stream().filter(q -> q.difficulty() == Difficulty.EASY).toList());
            List<QuestionDto> medList = new ArrayList<>(candidateQuestions.stream().filter(q -> q.difficulty() == Difficulty.MEDIUM).toList());
            List<QuestionDto> hardList = new ArrayList<>(candidateQuestions.stream().filter(q -> q.difficulty() == Difficulty.HARD).toList());

            if (easyList.size() < easyCount) {
                setQuizLoading(false);
                btnConfirmAutoGenerate.setDisable(false);
                showAutoQuizError("Ngân hàng chỉ có " + easyList.size() + " câu Dễ (EASY), không đủ " + easyCount + " câu yêu cầu!");
                return;
            }
            if (medList.size() < medCount) {
                setQuizLoading(false);
                btnConfirmAutoGenerate.setDisable(false);
                showAutoQuizError("Ngân hàng chỉ có " + medList.size() + " câu Trung bình (MEDIUM), không đủ " + medCount + " câu yêu cầu!");
                return;
            }
            if (hardList.size() < hardCount) {
                setQuizLoading(false);
                btnConfirmAutoGenerate.setDisable(false);
                showAutoQuizError("Ngân hàng chỉ có " + hardList.size() + " câu Khó (HARD), không đủ " + hardCount + " câu yêu cầu!");
                return;
            }

            Collections.shuffle(easyList);
            Collections.shuffle(medList);
            Collections.shuffle(hardList);

            List<Long> selectedIds = new ArrayList<>();
            easyList.subList(0, easyCount).forEach(q -> selectedIds.add(q.id()));
            medList.subList(0, medCount).forEach(q -> selectedIds.add(q.id()));
            hardList.subList(0, hardCount).forEach(q -> selectedIds.add(q.id()));
            Collections.shuffle(selectedIds); // Trộn lẫn thứ tự trong đề thi

            CreateQuizDto createDto = new CreateQuizDto(
                title.strip(),
                "Tạo tự động: " + easyCount + " Dễ, " + medCount + " Vừa, " + hardCount + " Khó.",
                category != null && !category.isBlank() ? category.strip() : "Tổng hợp",
                duration,
                passScore,
                cmbAutoQuizPolicy.getValue(),
                maxViolations
            );

            // Tạo đề thi và gán danh sách câu hỏi tự động
            api.createQuiz(createDto).thenCompose(created ->
                api.setQuizQuestions(created.id(), selectedIds).thenApply(ignored -> created)
            ).whenComplete((createdQuiz, createErr) -> fx.run(() -> {
                setQuizLoading(false);
                btnConfirmAutoGenerate.setDisable(false);
                if (createErr != null) {
                    showAutoQuizError("Lỗi tạo đề thi tự động: " + formatError(createErr));
                } else {
                    hideAllQuizPanels();
                    loadQuizzes();
                    showQuizNotice("⚡ Đã TẠO ĐỀ TỰ ĐỘNG thành công! Đề thi '" + createdQuiz.title() + "' gồm " + selectedIds.size() + " câu hỏi (" + easyCount + " Dễ, " + medCount + " Vừa, " + hardCount + " Khó).", false);
                    // Mở ngay giao diện Review & Chỉnh sửa đề vừa tạo cho giáo viên xem
                    openReviewAndEdit(createdQuiz.id(), createdQuiz.title(), QuizStatus.DRAFT);
                }
            }));
        }));
    }

    @FXML
    private void onCancelAutoQuizClick() {
        hideAllQuizPanels();
    }

    private void showAutoQuizError(String msg) {
        autoQuizError.setText(msg);
        autoQuizError.setVisible(true);
        autoQuizError.setManaged(true);
    }

    private int parsePositiveInt(String text, int defaultValue) {
        try {
            int v = Integer.parseInt(text.trim());
            return v > 0 ? v : defaultValue;
        } catch (Exception e) {
            return defaultValue;
        }
    }

    // =========================================================================
    // QUIZ FORM (MANUAL CREATE / EDIT)
    // =========================================================================

    @FXML
    private void onNewQuizClick() {
        hideAllQuizPanels();
        isEditingQuiz = false;
        editingQuizId = 0;
        formTitle.setText("Tạo Đề Kiểm Tra Mới (Trạng thái: DRAFT)");
        txtQuizTitle.clear();
        txtQuizCategory.setText("Tiếng Anh");
        txtQuizDuration.setText("30");
        lblDurationError.setVisible(false);
        txtQuizDuration.setStyle("");
        txtQuizPassScore.setText("50");
        txtQuizMaxViolations.setText("3");
        txtQuizDescription.clear();
        cmbQuizPolicy.setValue(AntiCheatPolicyType.WARN_THEN_AUTO_SUBMIT);
        formError.setVisible(false);
        quizFormPanel.setVisible(true);
        quizFormPanel.setManaged(true);
        scrollToBottom();
    }

    @FXML
    private void onEditQuizClick() {
        QuizDto sel = quizTable.getSelectionModel().getSelectedItem();
        if (sel == null || sel.status() != QuizStatus.DRAFT) return;

        hideAllQuizPanels();
        isEditingQuiz = true;
        editingQuizId = sel.id();
        formTitle.setText("Chỉnh Sửa Đề Kiểm Tra #" + sel.id() + " (DRAFT)");
        txtQuizTitle.setText(sel.title());
        txtQuizCategory.setText(sel.category() != null ? sel.category() : "");
        txtQuizDuration.setText(String.valueOf(sel.durationMinutes()));
        lblDurationError.setVisible(false);
        txtQuizDuration.setStyle("");
        txtQuizPassScore.setText(String.format("%.0f", sel.passScore()));
        txtQuizMaxViolations.setText(String.valueOf(sel.maxViolations()));
        txtQuizDescription.setText(sel.description() != null ? sel.description() : "");
        cmbQuizPolicy.setValue(sel.anticheatPolicy());
        formError.setVisible(false);
        quizFormPanel.setVisible(true);
        quizFormPanel.setManaged(true);
        scrollToBottom();
    }

    @FXML
    private void onSaveQuizClick() {
        String title = txtQuizTitle.getText();
        String category = txtQuizCategory.getText();
        String desc = txtQuizDescription.getText();
        int duration;
        double passScore;
        int maxViolations;

        try {
            duration = Integer.parseInt(txtQuizDuration.getText().trim());
            if (duration <= 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            lblDurationError.setText("⚠️ Không được viết chữ vào đây, chỉ được nhập số nguyên phút!");
            lblDurationError.setVisible(true);
            lblDurationError.setManaged(true);
            txtQuizDuration.setStyle("-fx-border-color: #dc2626; -fx-border-width: 1.5px;");
            showFormError("Thời lượng phải là số nguyên phút lớn hơn 0");
            return;
        }

        try {
            passScore = Double.parseDouble(txtQuizPassScore.getText().trim());
            if (passScore < 0 || passScore > 100) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            showFormError("Điểm đạt phải là số từ 0 đến 100");
            return;
        }

        try {
            maxViolations = Integer.parseInt(txtQuizMaxViolations.getText().trim());
            if (maxViolations <= 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            showFormError("Số vi phạm tối đa phải là số nguyên lớn hơn 0");
            return;
        }

        if (title == null || title.strip().isBlank()) {
            showFormError("Tiêu đề không được để trống");
            return;
        }

        btnSaveQuiz.setDisable(true);
        if (!isEditingQuiz) {
            CreateQuizDto dto = new CreateQuizDto(title.strip(), desc, category, duration, passScore, cmbQuizPolicy.getValue(), maxViolations);
            fx.observe(api.createQuiz(dto), this::isActive,
                created -> {
                    showQuizNotice("Đã tạo đề thi '" + created.title() + "' (ID: " + created.id() + ") thành công ở trạng thái DRAFT!", false);
                    hideAllQuizPanels();
                    loadQuizzes();
                },
                err -> {
                    showFormError("Lỗi tạo đề thi: " + formatError(err));
                    btnSaveQuiz.setDisable(false);
                },
                () -> btnSaveQuiz.setDisable(false)
            );
        } else {
            UpdateQuizDto dto = new UpdateQuizDto(editingQuizId, title.strip(), desc, category, duration, passScore, cmbQuizPolicy.getValue(), maxViolations);
            fx.observe(api.updateQuiz(dto), this::isActive,
                updated -> {
                    showQuizNotice("Đã cập nhật đề thi #" + updated.id() + " thành công!", false);
                    hideAllQuizPanels();
                    loadQuizzes();
                },
                err -> {
                    showFormError("Lỗi cập nhật đề thi: " + formatError(err));
                    btnSaveQuiz.setDisable(false);
                },
                () -> btnSaveQuiz.setDisable(false)
            );
        }
    }

    @FXML
    private void onCancelQuizFormClick() {
        hideAllQuizPanels();
    }

    // =========================================================================
    // FEATURE 2: REVIEW VÀ CHỈNH SỬA CHI TIẾT ĐỀ THI (THÊM / XOÁ / THAY THẾ)
    // =========================================================================

    private void configureDetailQuestionTable() {
        TableColumn<DetailQuestionRow, String> colStt = new TableColumn<>("#");
        colStt.setPrefWidth(45);
        colStt.setCellValueFactory(r -> new SimpleStringProperty(String.valueOf(detailQuestionRows.indexOf(r.getValue()) + 1)));

        TableColumn<DetailQuestionRow, String> colDiff = new TableColumn<>("Độ khó");
        colDiff.setPrefWidth(85);
        colDiff.setCellValueFactory(r -> new SimpleStringProperty(r.getValue().getQuestion().difficulty().name()));
        colDiff.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    setText(null);
                } else {
                    Label badge = new Label(item);
                    badge.getStyleClass().add("badge");
                    if ("EASY".equals(item)) badge.getStyleClass().add("badge-easy");
                    else if ("MEDIUM".equals(item)) badge.getStyleClass().add("badge-medium");
                    else badge.getStyleClass().add("badge-hard");
                    setGraphic(badge);
                    setText(null);
                }
            }
        });

        TableColumn<DetailQuestionRow, String> colTopic = new TableColumn<>("Chủ đề");
        colTopic.setPrefWidth(100);
        colTopic.setCellValueFactory(r -> new SimpleStringProperty(r.getValue().getQuestion().topic()));

        TableColumn<DetailQuestionRow, String> colContent = new TableColumn<>("Nội dung câu hỏi");
        colContent.setPrefWidth(280);
        colContent.setCellValueFactory(r -> new SimpleStringProperty(r.getValue().getQuestion().content()));
        colContent.setCellFactory(col -> new TableCell<>() {
            private final TextField txt = new TextField();
            {
                txt.setEditable(false);
                txt.setStyle("-fx-background-color: transparent; -fx-border-color: transparent; -fx-padding: 0; -fx-text-fill: #1e293b;");
            }
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                } else {
                    txt.setText(item);
                    setGraphic(txt);
                }
            }
        });

        TableColumn<DetailQuestionRow, String> colOptions = new TableColumn<>("Đáp án đúng & Lựa chọn");
        colOptions.setPrefWidth(220);
        colOptions.setCellValueFactory(r -> {
            QuestionDto q = r.getValue().getQuestion();
            return new SimpleStringProperty("Đáp án: " + q.correctOption() + " | A: " + q.optionA() + " | B: " + q.optionB() + " | C: " + q.optionC() + " | D: " + q.optionD());
        });
        colOptions.setCellFactory(col -> new TableCell<>() {
            private final TextField txt = new TextField();
            {
                txt.setEditable(false);
                txt.setStyle("-fx-background-color: transparent; -fx-border-color: transparent; -fx-padding: 0; -fx-text-fill: #475569;");
            }
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                } else {
                    txt.setText(item);
                    setGraphic(txt);
                }
            }
        });

        TableColumn<DetailQuestionRow, Void> colActions = new TableColumn<>("Thao tác");
        colActions.setPrefWidth(170);
        colActions.setCellFactory(col -> new TableCell<>() {
            private final Button btnReplace = new Button("🔄 Đổi câu");
            private final Button btnDelete = new Button("❌ Xóa");
            private final HBox box = new HBox(6, btnReplace, btnDelete);
            {
                btnReplace.getStyleClass().addAll("secondary-button", "btn-small");
                btnDelete.getStyleClass().addAll("danger-button", "btn-small");
                box.setAlignment(Pos.CENTER);

                btnReplace.setOnAction(e -> {
                    DetailQuestionRow row = getTableRow() != null ? getTableRow().getItem() : null;
                    if (row != null) openQuestionPickerForReplace(row);
                });
                btnDelete.setOnAction(e -> {
                    DetailQuestionRow row = getTableRow() != null ? getTableRow().getItem() : null;
                    if (row != null) removeQuestionFromDetail(row);
                });
            }
            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                    setGraphic(null);
                } else {
                    btnReplace.setDisable(!isDetailQuizDraft);
                    btnDelete.setDisable(!isDetailQuizDraft);
                    setGraphic(box);
                }
            }
        });

        detailQuestionTable.setRowFactory(tv -> {
            TableRow<DetailQuestionRow> row = new TableRow<>();
            ContextMenu cm = new ContextMenu();
            MenuItem copyContentItem = new MenuItem("📋 Sao chép nội dung câu hỏi");
            copyContentItem.setOnAction(e -> {
                if (row.getItem() != null) {
                    ClipboardContent cc = new ClipboardContent();
                    cc.putString(row.getItem().getQuestion().content());
                    Clipboard.getSystemClipboard().setContent(cc);
                }
            });
            MenuItem copyFullItem = new MenuItem("📋 Sao chép toàn bộ câu hỏi và đáp án");
            copyFullItem.setOnAction(e -> {
                if (row.getItem() != null) {
                    QuestionDto q = row.getItem().getQuestion();
                    ClipboardContent cc = new ClipboardContent();
                    cc.putString(String.format("Câu hỏi: %s\nA. %s\nB. %s\nC. %s\nD. %s\nĐáp án đúng: %s",
                        q.content(), q.optionA(), q.optionB(), q.optionC(), q.optionD(), q.correctOption()));
                    Clipboard.getSystemClipboard().setContent(cc);
                }
            });
            cm.getItems().addAll(copyContentItem, copyFullItem);
            row.contextMenuProperty().bind(
                javafx.beans.binding.Bindings.when(row.emptyProperty())
                    .then((ContextMenu) null)
                    .otherwise(cm)
            );
            return row;
        });

        detailQuestionTable.getColumns().setAll(List.of(colStt, colDiff, colTopic, colContent, colOptions, colActions));
        detailQuestionTable.setItems(detailQuestionRows);
        detailQuestionTable.setPlaceholder(new Label("Đề kiểm tra này chưa có câu hỏi nào. Bấm '+ Thêm Câu Hỏi Từ Ngân Hàng' bên trên."));
    }

    private void configurePickerTable() {
        TableColumn<QuestionDto, String> colId = new TableColumn<>("ID");
        colId.setPrefWidth(45);
        colId.setCellValueFactory(d -> new SimpleStringProperty(String.valueOf(d.getValue().id())));

        TableColumn<QuestionDto, String> colDiff = new TableColumn<>("Độ khó");
        colDiff.setPrefWidth(85);
        colDiff.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().difficulty().name()));
        colDiff.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    setText(null);
                } else {
                    Label badge = new Label(item);
                    badge.getStyleClass().add("badge");
                    if ("EASY".equals(item)) badge.getStyleClass().add("badge-easy");
                    else if ("MEDIUM".equals(item)) badge.getStyleClass().add("badge-medium");
                    else badge.getStyleClass().add("badge-hard");
                    setGraphic(badge);
                    setText(null);
                }
            }
        });

        TableColumn<QuestionDto, String> colTopic = new TableColumn<>("Chủ đề");
        colTopic.setPrefWidth(110);
        colTopic.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().topic()));

        TableColumn<QuestionDto, String> colContent = new TableColumn<>("Nội dung câu hỏi");
        colContent.setPrefWidth(380);
        colContent.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().content()));

        TableColumn<QuestionDto, String> colCorrect = new TableColumn<>("Đáp án");
        colCorrect.setPrefWidth(65);
        colCorrect.setCellValueFactory(d -> new SimpleStringProperty(String.valueOf(d.getValue().correctOption())));

        pickerTable.getColumns().setAll(List.of(colId, colDiff, colTopic, colContent, colCorrect));
        pickerTable.setItems(pickerQuestionRows);
        pickerTable.getSelectionModel().selectedItemProperty().addListener((obs, o, n) ->
            btnConfirmPicker.setDisable(n == null)
        );
    }

    @FXML
    private void onViewDetailClick() {
        QuizDto sel = quizTable.getSelectionModel().getSelectedItem();
        if (sel == null) return;
        openReviewAndEdit(sel.id(), sel.title(), sel.status());
    }

    private void openReviewAndEdit(long quizId, String quizTitle, QuizStatus status) {
        currentDetailQuizId = quizId;
        isDetailQuizDraft = (status == QuizStatus.DRAFT);

        showQuizPanel(quizDetailPanel);
        questionPickerPanel.setVisible(false);
        questionPickerPanel.setManaged(false);
        scrollToTop();

        detailQuizTitle.setText("Chi tiết & Chỉnh sửa đề thi #" + quizId + ": " + quizTitle);
        detailStatusBadge.setText(status == QuizStatus.DRAFT ? "BẢN NHÁP (DRAFT)" : status.name());
        detailStatusBadge.getStyleClass().removeAll("badge-draft", "badge-open", "badge-closed");
        detailStatusBadge.getStyleClass().add(status == QuizStatus.DRAFT ? "badge-draft" : (status == QuizStatus.OPEN ? "badge-open" : "badge-closed"));

        btnDetailAddQuestion.setDisable(!isDetailQuizDraft);
        btnDetailSaveQuestions.setDisable(!isDetailQuizDraft);

        if (!isDetailQuizDraft) {
            detailNotice.setText("ℹ️ Đề thi đang ở trạng thái " + status + " nên chỉ được xem chi tiết, không thể thay đổi câu hỏi.");
            detailNotice.setStyle("-fx-text-fill: #64748b;");
            detailNotice.setVisible(true);
            detailNotice.setManaged(true);
        } else {
            detailNotice.setVisible(false);
            detailNotice.setManaged(false);
        }

        setQuizLoading(true);
        // Load bank questions và chi tiết đề thi đồng thời
        api.listQuestions().thenCombine(api.getQuiz(quizId), (bank, detail) -> {
            allBankQuestions = bank;
            return detail;
        }).whenComplete((detail, err) -> fx.run(() -> {
            setQuizLoading(false);
            if (err != null) {
                showQuizNotice("Lỗi khi tải chi tiết đề thi: " + formatError(err), true);
            } else {
                detailQuizInfo.setText("Chủ đề: " + detail.quiz().category() + " | Thời lượng: " + detail.quiz().durationMinutes()
                    + " phút | Điểm đạt: " + String.format("%.0f%%", detail.quiz().passScore()) + (detail.quiz().pin() != null ? " | PIN: " + detail.quiz().pin() : ""));
                detailAssignedClasses.setText("Các lớp đã giao: " + (detail.assignedClassIds().isEmpty() ? "Chưa giao lớp nào" : detail.assignedClassIds().size() + " lớp"));

                List<DetailQuestionRow> rows = new ArrayList<>();
                for (QuestionDto q : detail.questions()) {
                    rows.add(new DetailQuestionRow(q));
                }
                detailQuestionRows.setAll(rows);
                updateDetailBreakdown();
            }
        }));
    }

    private void updateDetailBreakdown() {
        int total = detailQuestionRows.size();
        long easy = detailQuestionRows.stream().filter(r -> r.getQuestion().difficulty() == Difficulty.EASY).count();
        long med = detailQuestionRows.stream().filter(r -> r.getQuestion().difficulty() == Difficulty.MEDIUM).count();
        long hard = detailQuestionRows.stream().filter(r -> r.getQuestion().difficulty() == Difficulty.HARD).count();

        detailQuestionsCount.setText("Số lượng câu hỏi: " + total + " câu");
        detailDifficultyBreakdown.setText("📊 Phân bổ: " + easy + " Dễ | " + med + " Trung bình | " + hard + " Khó");
        detailQuestionTable.refresh();
    }

    @FXML
    private void onDetailAddQuestionClick() {
        if (!isDetailQuizDraft) return;
        isPickerForReplace = false;
        questionToReplace = null;
        lblPickerTitle.setText("➕ Chọn câu hỏi từ Ngân hàng câu hỏi để THÊM vào đề thi:");
        btnConfirmPicker.setText("Thêm Câu Hỏi Này Vào Đề");
        btnConfirmPicker.setDisable(true);
        questionPickerPanel.setVisible(true);
        questionPickerPanel.setManaged(true);
        filterPickerQuestions();
        scrollToBottom();
    }

    private void openQuestionPickerForReplace(DetailQuestionRow row) {
        if (!isDetailQuizDraft || row == null) return;
        isPickerForReplace = true;
        questionToReplace = row;
        lblPickerTitle.setText("🔄 Chọn câu hỏi từ Ngân hàng câu hỏi để THAY THẾ cho câu #" + (detailQuestionRows.indexOf(row) + 1) + " (Độ khó: " + row.getQuestion().difficulty() + "):");
        btnConfirmPicker.setText("Xác Nhận Thay Thế Câu Này");
        btnConfirmPicker.setDisable(true);
        cmbPickerDifficulty.setValue(row.getQuestion().difficulty()); // Gợi ý độ khó tương đương
        questionPickerPanel.setVisible(true);
        questionPickerPanel.setManaged(true);
        filterPickerQuestions();
        scrollToBottom();
    }

    private void removeQuestionFromDetail(DetailQuestionRow row) {
        if (!isDetailQuizDraft || row == null) return;
        detailQuestionRows.remove(row);
        updateDetailBreakdown();
        detailNotice.setText("⚠️ Đã xóa 1 câu hỏi khỏi đề. Nhớ nhấn 'Lưu Thay Đổi Đề Thi' bên trên để lưu lại vào cơ sở dữ liệu!");
        detailNotice.setStyle("-fx-text-fill: #d97706;");
        detailNotice.setVisible(true);
        detailNotice.setManaged(true);
    }

    private void filterPickerQuestions() {
        Set<Long> existingIds = new HashSet<>();
        for (DetailQuestionRow r : detailQuestionRows) {
            existingIds.add(r.getQuestion().id());
        }

        Difficulty filterDiff = cmbPickerDifficulty.getValue();
        String search = txtPickerSearch.getText() != null ? txtPickerSearch.getText().trim().toLowerCase() : "";

        List<QuestionDto> filtered = allBankQuestions.stream()
            .filter(q -> !existingIds.contains(q.id())) // Không hiển thị những câu đã có trong đề
            .filter(q -> filterDiff == null || q.difficulty() == filterDiff)
            .filter(q -> search.isEmpty() || q.content().toLowerCase().contains(search) || q.topic().toLowerCase().contains(search))
            .toList();

        pickerQuestionRows.setAll(filtered);
    }

    @FXML
    private void onConfirmPickerClick() {
        QuestionDto selectedBankQ = pickerTable.getSelectionModel().getSelectedItem();
        if (selectedBankQ == null) return;

        if (isPickerForReplace && questionToReplace != null) {
            int idx = detailQuestionRows.indexOf(questionToReplace);
            if (idx >= 0) {
                detailQuestionRows.set(idx, new DetailQuestionRow(selectedBankQ));
                detailNotice.setText("✅ Đã thay thế câu hỏi thành công. Nhớ nhấn 'Lưu Thay Đổi Đề Thi' để lưu!");
            }
        } else {
            detailQuestionRows.add(new DetailQuestionRow(selectedBankQ));
            detailNotice.setText("✅ Đã thêm câu hỏi vào đề. Nhớ nhấn 'Lưu Thay Đổi Đề Thi' để lưu!");
        }

        detailNotice.setStyle("-fx-text-fill: #15803d;");
        detailNotice.setVisible(true);
        detailNotice.setManaged(true);
        questionPickerPanel.setVisible(false);
        questionPickerPanel.setManaged(false);
        updateDetailBreakdown();
    }

    @FXML
    private void onCancelPickerClick() {
        questionPickerPanel.setVisible(false);
        questionPickerPanel.setManaged(false);
    }

    @FXML
    private void onDetailSaveQuestionsClick() {
        if (!isDetailQuizDraft) return;

        List<Long> ids = detailQuestionRows.stream()
            .map(r -> r.getQuestion().id())
            .toList();

        btnDetailSaveQuestions.setDisable(true);
        setQuizLoading(true);
        fx.observe(api.setQuizQuestions(currentDetailQuizId, ids), this::isActive,
            ignored -> {
                showQuizNotice("✅ Đã cập nhật thành công " + ids.size() + " câu hỏi cho đề kiểm tra #" + currentDetailQuizId + "!", false);
                detailNotice.setText("✅ Đã lưu thay đổi vào cơ sở dữ liệu thành công!");
                detailNotice.setStyle("-fx-text-fill: #15803d;");
                detailNotice.setVisible(true);
                detailNotice.setManaged(true);
                btnDetailSaveQuestions.setDisable(false);
                setQuizLoading(false);
                loadQuizzes();
            },
            err -> {
                showQuizNotice("Lỗi khi lưu câu hỏi: " + formatError(err), true);
                btnDetailSaveQuestions.setDisable(false);
                setQuizLoading(false);
            },
            () -> {
                btnDetailSaveQuestions.setDisable(false);
                setQuizLoading(false);
            }
        );
    }

    @FXML
    private void onCloseDetailClick() {
        hideAllQuizPanels();
    }

    // =========================================================================
    // FEATURE 3: GIAO ĐỀ KIỂM TRA CHO NHIỀU LỚP HỌC (MULTI-CLASS ASSIGNMENT)
    // =========================================================================

    private void configureAssignClassTable() {
        TableColumn<AssignClassRow, Boolean> colCheck = new TableColumn<>("Chọn");
        colCheck.setPrefWidth(60);
        colCheck.setCellValueFactory(r -> r.getValue().selectedProperty());
        colCheck.setCellFactory(col -> new TableCell<>() {
            private final CheckBox chk = new CheckBox();
            {
                chk.setOnAction(e -> {
                    AssignClassRow row = getTableRow() != null ? getTableRow().getItem() : null;
                    if (row != null && !row.isAlreadyAssigned()) {
                        row.setSelected(chk.isSelected());
                        updateAssignCount();
                    }
                });
            }
            @Override
            protected void updateItem(Boolean item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                    setGraphic(null);
                } else {
                    AssignClassRow row = getTableRow().getItem();
                    chk.setSelected(row.isSelected());
                    chk.setDisable(row.isAlreadyAssigned());
                    setGraphic(chk);
                }
            }
        });

        TableColumn<AssignClassRow, String> colId = new TableColumn<>("ID");
        colId.setPrefWidth(55);
        colId.setCellValueFactory(r -> new SimpleStringProperty(String.valueOf(r.getValue().getClassDto().id())));

        TableColumn<AssignClassRow, String> colName = new TableColumn<>("Tên Lớp");
        colName.setPrefWidth(160);
        colName.setCellValueFactory(r -> new SimpleStringProperty(r.getValue().getClassDto().name()));

        TableColumn<AssignClassRow, String> colDesc = new TableColumn<>("Mô Tả");
        colDesc.setPrefWidth(220);
        colDesc.setCellValueFactory(r -> new SimpleStringProperty(r.getValue().getClassDto().description() == null ? "—" : r.getValue().getClassDto().description()));

        TableColumn<AssignClassRow, String> colTeacher = new TableColumn<>("Giáo viên");
        colTeacher.setPrefWidth(140);
        colTeacher.setCellValueFactory(r -> new SimpleStringProperty(r.getValue().getClassDto().teacherName()));

        TableColumn<AssignClassRow, String> colStatus = new TableColumn<>("Trạng thái giao");
        colStatus.setPrefWidth(130);
        colStatus.setCellValueFactory(r -> new SimpleStringProperty(r.getValue().isAlreadyAssigned() ? "ĐÃ GIAO" : "CHƯA GIAO"));
        colStatus.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    Label badge = new Label(item);
                    badge.getStyleClass().add("badge");
                    if ("ĐÃ GIAO".equals(item)) {
                        badge.getStyleClass().add("badge-open");
                    } else {
                        badge.getStyleClass().add("badge-draft");
                    }
                    setGraphic(badge);
                    setText(null);
                }
            }
        });

        assignClassTable.getColumns().setAll(List.of(colCheck, colId, colName, colDesc, colTeacher, colStatus));
        assignClassTable.setItems(assignClassRows);
    }

    @FXML
    private void onAssignQuizClick() {
        QuizDto sel = quizTable.getSelectionModel().getSelectedItem();
        if (sel == null) return;

        targetQuizIdForAssign = sel.id();
        assignQuizTitle.setText("Giao đề thi #" + sel.id() + ": " + sel.title() + " cho Nhiều Lớp Học");
        showQuizPanel(assignPanel);

        setQuizLoading(true);
        // Tải chi tiết đề thi để biết các lớp nào đã được giao trước đó
        api.getQuiz(sel.id()).thenCombine(api.listClasses(), (detail, classes) -> {
            Set<Long> assigned = new HashSet<>(detail.assignedClassIds());
            List<AssignClassRow> rows = new ArrayList<>();
            for (ClassDto c : classes) {
                boolean isAssigned = assigned.contains(c.id());
                rows.add(new AssignClassRow(c, isAssigned, isAssigned));
            }
            return rows;
        }).whenComplete((rows, err) -> fx.run(() -> {
            setQuizLoading(false);
            if (err != null) {
                showQuizNotice("Lỗi khi tải danh sách lớp học: " + formatError(err), true);
            } else {
                assignClassRows.setAll(rows);
                chkSelectAllClasses.setSelected(false);
                updateAssignCount();
            }
        }));
    }

    @FXML
    private void onSelectAllClassesClick() {
        boolean select = chkSelectAllClasses.isSelected();
        for (AssignClassRow row : assignClassRows) {
            if (!row.isAlreadyAssigned()) {
                row.setSelected(select);
            }
        }
        assignClassTable.refresh();
        updateAssignCount();
    }

    private void updateAssignCount() {
        long newSelectedCount = assignClassRows.stream()
            .filter(r -> r.isSelected() && !r.isAlreadyAssigned())
            .count();
        long alreadyAssignedCount = assignClassRows.stream().filter(AssignClassRow::isAlreadyAssigned).count();
        lblSelectedClassesCount.setText("Chọn mới: " + newSelectedCount + " lớp (Đã giao trước đó: " + alreadyAssignedCount + " lớp)");
    }

    @FXML
    private void onConfirmAssignClick() {
        List<ClassDto> classesToAssign = assignClassRows.stream()
            .filter(r -> r.isSelected() && !r.isAlreadyAssigned())
            .map(AssignClassRow::getClassDto)
            .toList();

        if (classesToAssign.isEmpty()) {
            showQuizNotice("Vui lòng tích chọn ít nhất một lớp học mới để giao bài!", true);
            return;
        }

        btnConfirmAssign.setDisable(true);
        setQuizLoading(true);

        // Giao đề thi cho tất cả các lớp đã chọn đồng thời
        List<CompletableFuture<Void>> futures = classesToAssign.stream()
            .map(c -> api.assignQuiz(targetQuizIdForAssign, c.id()))
            .toList();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).whenComplete((res, err) -> fx.run(() -> {
            btnConfirmAssign.setDisable(false);
            setQuizLoading(false);
            if (err != null) {
                showQuizNotice("Lỗi khi giao bài cho các lớp: " + formatError(err), true);
            } else {
                showQuizNotice("🎉 Đã giao đề thi #" + targetQuizIdForAssign + " thành công cho " + classesToAssign.size() + " lớp học!", false);
                hideAllQuizPanels();
                loadQuizzes();
            }
        }));
    }

    @FXML
    private void onCancelAssignClick() {
        hideAllQuizPanels();
    }

    // =========================================================================
    private void configureClassResultsWorkspace() {
        resultsClassCombo.setItems(classList);
        resultsClassCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(ClassDto value) { return value == null ? "" : value.name(); }
            @Override public ClassDto fromString(String value) { return null; }
        });
        resultsQuizCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(QuizDto value) { return value == null ? "" : value.title(); }
            @Override public QuizDto fromString(String value) { return null; }
        });
        resultsClassCombo.valueProperty().addListener((obs, oldValue, selected) -> loadResultsForClass(selected));
        resultsQuizCombo.valueProperty().addListener((obs, oldValue, selected) -> showSelectedClassQuizResults(selected));

        TableColumn<ResultDto, String> student = new TableColumn<>("Học viên");
        student.setPrefWidth(220);
        student.setCellValueFactory(row -> new SimpleStringProperty(row.getValue().studentName()));
        TableColumn<ResultDto, String> score = new TableColumn<>("Điểm");
        score.setPrefWidth(100);
        score.setCellValueFactory(row -> new SimpleStringProperty(String.format("%.1f / 10", row.getValue().score())));
        TableColumn<ResultDto, String> correct = new TableColumn<>("Số câu đúng");
        correct.setPrefWidth(130);
        correct.setCellValueFactory(row -> new SimpleStringProperty(row.getValue().correctCount() + " / " + row.getValue().totalQuestions()));
        TableColumn<ResultDto, String> passed = new TableColumn<>("Kết quả");
        passed.setPrefWidth(120);
        passed.setCellValueFactory(row -> new SimpleStringProperty(row.getValue().passed() ? "ĐẠT" : "CHƯA ĐẠT"));
        TableColumn<ResultDto, String> reason = new TableColumn<>("Hình thức nộp");
        reason.setPrefWidth(160);
        reason.setCellValueFactory(row -> new SimpleStringProperty(submitReasonLabel(row.getValue().submitReason())));
        TableColumn<ResultDto, String> submitted = new TableColumn<>("Thời gian nộp");
        submitted.setPrefWidth(180);
        submitted.setCellValueFactory(row -> new SimpleStringProperty(row.getValue().submittedAt() == null ? "—" : DATE_FORMAT.format(row.getValue().submittedAt())));
        TableColumn<ResultDto, Void> log = new TableColumn<>("Nhật ký");
        log.setPrefWidth(140);
        log.setCellFactory(col -> new TableCell<>() {
            private final Button button = new Button();
            {
                button.setOnAction(event -> showAttemptAntiCheatLogs(getTableView().getItems().get(getIndex())));
            }
            @Override protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) { setGraphic(null); return; }
                ResultDto result = getTableView().getItems().get(getIndex());
                boolean violation = result.submitReason() == SubmitReason.ANTICHEAT;
                button.setText(violation ? "⚠️ Xem vi phạm" : "Xem log");
                button.setStyle(violation
                    ? "-fx-background-color: #ef4444; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 11px;"
                    : "-fx-font-size: 11px;");
                setGraphic(button);
            }
        });
        classResultsTable.getColumns().setAll(List.of(student, score, correct, passed, reason, submitted, log));
        classResultsTable.setItems(classResultsList);
        classResultsTable.setPlaceholder(new Label("Chọn lớp và bài kiểm tra để xem kết quả."));
        classResultsTable.setRowFactory(table -> new TableRow<>() {
            @Override protected void updateItem(ResultDto item, boolean empty) {
                super.updateItem(item, empty);
                setStyle(!empty && item != null && item.submitReason() == SubmitReason.ANTICHEAT
                    ? "-fx-background-color: #fee2e2; -fx-text-fill: #991b1b;" : "");
            }
        });
    }

    private void loadResultsForClass(ClassDto selected) {
        resultsQuizCombo.getItems().clear();
        resultsQuizCombo.setValue(null);
        classResultsList.clear();
        allClassResults = List.of();
        resetClassResultStats();
        classResultsQuizTitle.setText("Chưa chọn bài kiểm tra");
        classResultsQuizInfo.setText("Chọn lớp và bài kiểm tra để xem thông tin chi tiết.");
        classResultsStatusBadge.setVisible(false);
        classResultsStatusBadge.setManaged(false);
        if (selected == null) return;
        classResultsNotice.setVisible(false);
        classResultsNotice.setManaged(false);
        fx.observe(api.getClassQuizResults(selected.id()), this::isActive,
            payload -> {
                resultsQuizCombo.getItems().setAll(payload.quizzes());
                allClassResults = payload.results();
                if (!payload.quizzes().isEmpty()) resultsQuizCombo.getSelectionModel().selectFirst();
                else showClassResultsNotice("Lớp này chưa được giao bài kiểm tra nào.", false);
            },
            error -> showClassResultsNotice("Không thể tải kết quả lớp: " + formatError(error), true),
            () -> { });
    }

    private void showSelectedClassQuizResults(QuizDto quiz) {
        if (quiz == null) {
            classResultsList.clear();
            resetClassResultStats();
            classResultsQuizTitle.setText("Chưa chọn bài kiểm tra");
            classResultsQuizInfo.setText("Chọn lớp và bài kiểm tra để xem thông tin chi tiết.");
            classResultsStatusBadge.setVisible(false);
            classResultsStatusBadge.setManaged(false);
            return;
        }
        classResultsQuizTitle.setText("Kết quả đề thi: " + quiz.title());
        classResultsStatusBadge.setText(quiz.status().name());
        classResultsStatusBadge.getStyleClass().removeAll("badge-draft", "badge-open", "badge-closed");
        classResultsStatusBadge.getStyleClass().add(switch (quiz.status()) {
            case DRAFT -> "badge-draft";
            case OPEN -> "badge-open";
            case CLOSED -> "badge-closed";
        });
        classResultsStatusBadge.setVisible(true);
        classResultsStatusBadge.setManaged(true);
        classResultsQuizInfo.setText("Chủ đề: " + quiz.category() + " | Thời lượng: " + quiz.durationMinutes()
            + " phút | Điểm đạt: " + String.format("%.0f%%", quiz.passScore()));
        List<ResultDto> selected = allClassResults.stream().filter(result -> result.quizId() == quiz.id()).toList();
        classResultsList.setAll(selected);
        int count = selected.size();
        classResultsCount.setText(String.valueOf(count));
        if (count == 0) {
            classResultsAverage.setText("0.0 / 10");
            classResultsPassRate.setText("0%");
            classResultsHighest.setText("0.0 / 10");
            showClassResultsNotice("Đề kiểm tra này chưa có bài nộp trong lớp được chọn. Thống kê sẽ được cập nhật khi có học viên hoàn thành bài thi.", false);
            return;
        }
        classResultsNotice.setVisible(false);
        classResultsNotice.setManaged(false);
        double average = selected.stream().mapToDouble(ResultDto::score).average().orElse(0);
        long passed = selected.stream().filter(ResultDto::passed).count();
        double highest = selected.stream().mapToDouble(ResultDto::score).max().orElse(0);
        classResultsAverage.setText(String.format("%.1f / 10", average));
        classResultsPassRate.setText(String.format("%.0f%%", passed * 100.0 / count));
        classResultsHighest.setText(String.format("%.1f / 10", highest));
    }

    private static String submitReasonLabel(SubmitReason reason) {
        if (reason == null) return "—";
        return switch (reason) {
            case MANUAL -> "Tự nộp bài";
            case TIMEOUT -> "Hết giờ làm bài";
            case ANTICHEAT -> "Vi phạm tự nộp";
        };
    }

    private void resetClassResultStats() {
        classResultsCount.setText("0");
        classResultsAverage.setText("—");
        classResultsPassRate.setText("—");
        classResultsHighest.setText("—");
    }

    private void showClassResultsNotice(String message, boolean error) {
        classResultsNotice.setText(message);
        classResultsNotice.getStyleClass().removeAll("error-notice");
        if (error) classResultsNotice.getStyleClass().add("error-notice");
        classResultsNotice.setVisible(true);
        classResultsNotice.setManaged(true);
    }

    @FXML
    private void refreshClassResults() {
        loadResultsForClass(resultsClassCombo.getValue());
    }

    private void showAttemptAntiCheatLogs(ResultDto row) {
        if (row == null) return;
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Nhật ký chống gian lận (Anti-Cheat Logs)");
        dialog.setHeaderText("Thí sinh: " + row.studentName() + " | Điểm: " + String.format("%.1f", row.score()) + " | Hình thức: " + row.submitReason());
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        TableView<AntiCheatEventDto> logTable = new TableView<>();
        logTable.setPrefWidth(720);
        logTable.setPrefHeight(350);

        TableColumn<AntiCheatEventDto, String> colTime = new TableColumn<>("Thời gian (Server)");
        colTime.setPrefWidth(140);
        colTime.setCellValueFactory(e -> new SimpleStringProperty(e.getValue().serverTime() != null ? DATE_FORMAT.format(e.getValue().serverTime()) : "—"));

        TableColumn<AntiCheatEventDto, String> colType = new TableColumn<>("Hành vi phát hiện");
        colType.setPrefWidth(160);
        colType.setCellValueFactory(e -> new SimpleStringProperty(switch (e.getValue().eventType()) {
            case FOCUS_LOST -> "Rời cửa sổ / Alt+Tab";
            case COPY_ATTEMPT -> "Cố gắng Copy";
            case PASTE_ATTEMPT -> "Cố gắng Paste";
            case CUT_ATTEMPT -> "Cố gắng Cut";
            case CONTEXT_MENU_ATTEMPT -> "Menu chuột phải";
            case PROHIBITED_PROCESS -> "Ứng dụng bị cấm";
        }));

        TableColumn<AntiCheatEventDto, String> colDetail = new TableColumn<>("Chi tiết vi phạm");
        colDetail.setPrefWidth(210);
        colDetail.setCellValueFactory(e -> new SimpleStringProperty(e.getValue().detail() != null ? e.getValue().detail() : "—"));

        TableColumn<AntiCheatEventDto, String> colCount = new TableColumn<>("Lần thứ");
        colCount.setPrefWidth(70);
        colCount.setCellValueFactory(e -> new SimpleStringProperty(String.valueOf(e.getValue().violationCountAfter())));

        TableColumn<AntiCheatEventDto, String> colTaken = new TableColumn<>("Xử lý");
        colTaken.setPrefWidth(120);
        colTaken.setCellValueFactory(e -> new SimpleStringProperty(switch (e.getValue().actionTaken()) {
            case "WARNED" -> "Cảnh báo";
            case "AUTO_SUBMITTED" -> "Thu hồi bài thi";
            case "NONE" -> "Ghi nhận";
            default -> e.getValue().actionTaken();
        }));

        logTable.getColumns().setAll(List.of(colTime, colType, colDetail, colCount, colTaken));
        logTable.setPlaceholder(new Label("Đang tải nhật ký vi phạm..."));

        VBox content = new VBox(10, logTable);
        dialog.getDialogPane().setContent(content);

        fx.observe(api.listAttemptEvents(row.attemptId()), this::isActive,
            events -> {
                logTable.getItems().setAll(events);
                if (events.isEmpty()) {
                    logTable.setPlaceholder(new Label("Không phát hiện hành vi vi phạm nào trong lượt làm bài này."));
                }
            },
            err -> logTable.setPlaceholder(new Label("Lỗi khi tải nhật ký: " + formatError(err))),
            () -> {}
        );

        dialog.showAndWait();
    }

    // =========================================================================
    // PUBLISH / OPEN QUIZ (Sinh PIN)
    // =========================================================================

    @FXML
    private void onOpenQuizClick() {
        QuizDto sel = quizTable.getSelectionModel().getSelectedItem();
        if (sel == null || sel.status() != QuizStatus.DRAFT) return;

        btnOpenQuiz.setDisable(true);
        fx.observe(api.openQuiz(sel.id()), this::isActive,
            opened -> {
                showQuizNotice("🎉 Đã MỞ ĐỀ THI! Mã PIN tham gia: " + opened.pin() + ". Học viên các lớp được giao có thể bắt đầu thi.", false);
                loadQuizzes();
            },
            err -> {
                showQuizNotice("Lỗi khi mở đề thi: " + formatError(err), true);
                btnOpenQuiz.setDisable(false);
            },
            () -> btnOpenQuiz.setDisable(false)
        );
    }

    @FXML
    private void onCloseQuizClick() {
        QuizDto sel = quizTable.getSelectionModel().getSelectedItem();
        if (sel == null || sel.status() != QuizStatus.OPEN) return;

        btnCloseQuiz.setDisable(true);
        fx.observe(api.closeQuiz(sel.id()), this::isActive,
            closed -> {
                showQuizNotice("Đã đóng đề thi #" + closed.id() + ". Không nhận bài thi mới.", false);
                loadQuizzes();
            },
            err -> {
                showQuizNotice("Lỗi khi đóng đề thi: " + formatError(err), true);
                btnCloseQuiz.setDisable(false);
            },
            () -> btnCloseQuiz.setDisable(false)
        );
    }

    // =========================================================================
    // MANUAL QUESTION SELECTION (FR-A05)
    // =========================================================================

    private void configureQuestionTable() {
        TableColumn<QuestionRow, Boolean> colCheck = new TableColumn<>("Chọn");
        colCheck.setPrefWidth(60);
        colCheck.setCellValueFactory(f -> f.getValue().selectedProperty());
        colCheck.setCellFactory(CheckBoxTableCell.forTableColumn(colCheck));
        colCheck.setEditable(true);

        TableColumn<QuestionRow, String> colId = new TableColumn<>("ID");
        colId.setPrefWidth(50);
        colId.setCellValueFactory(f -> new SimpleStringProperty(String.valueOf(f.getValue().getQuestion().id())));

        TableColumn<QuestionRow, String> colContent = new TableColumn<>("Nội dung câu hỏi");
        colContent.setPrefWidth(280);
        colContent.setCellValueFactory(f -> new SimpleStringProperty(f.getValue().getQuestion().content()));

        TableColumn<QuestionRow, String> colTopic = new TableColumn<>("Chủ đề");
        colTopic.setPrefWidth(110);
        colTopic.setCellValueFactory(f -> new SimpleStringProperty(f.getValue().getQuestion().topic()));

        TableColumn<QuestionRow, String> colDiff = new TableColumn<>("Độ khó");
        colDiff.setPrefWidth(85);
        colDiff.setCellValueFactory(f -> new SimpleStringProperty(f.getValue().getQuestion().difficulty().name()));

        questionTable.getColumns().setAll(List.of(colCheck, colId, colContent, colTopic, colDiff));
        questionTable.setEditable(true);
        questionTable.setItems(questionRows);
    }

    @FXML
    private void onSelectQuestionsClick() {
        QuizDto sel = quizTable.getSelectionModel().getSelectedItem();
        if (sel == null || sel.status() != QuizStatus.DRAFT) return;

        targetQuizIdForQuestions = sel.id();
        questionSelectionTitle.setText("Chọn câu hỏi từ Ngân hàng câu hỏi cho đề: " + sel.title());
        hideAllQuizPanels();
        questionSelectionPanel.setVisible(true);
        questionSelectionPanel.setManaged(true);
        scrollToBottom();

        setQuizLoading(true);
        api.listQuestions().thenCombine(api.getQuiz(sel.id()), (allQ, detail) -> {
            Set<Long> existingIds = new HashSet<>();
            for (QuestionDto q : detail.questions()) {
                existingIds.add(q.id());
            }
            List<QuestionRow> rows = new ArrayList<>();
            for (QuestionDto q : allQ) {
                boolean checked = existingIds.contains(q.id());
                QuestionRow row = new QuestionRow(q, checked);
                row.selectedProperty().addListener((obs, oldVal, newVal) -> updateSelectedQuestionCount());
                rows.add(row);
            }
            return rows;
        }).whenComplete((rows, err) -> fx.run(() -> {
            setQuizLoading(false);
            if (err != null) {
                showQuizNotice("Lỗi khi tải câu hỏi: " + formatError(err), true);
            } else {
                questionRows.setAll(rows);
                updateSelectedQuestionCount();
            }
        }));
    }

    private void updateSelectedQuestionCount() {
        long count = questionRows.stream().filter(QuestionRow::isSelected).count();
        selectedQuestionsCount.setText("Đã chọn: " + count + " câu hỏi");
    }

    @FXML
    private void onSaveSelectedQuestionsClick() {
        List<Long> selectedIds = questionRows.stream()
            .filter(QuestionRow::isSelected)
            .map(r -> r.getQuestion().id())
            .toList();

        btnSaveSelectedQuestions.setDisable(true);
        fx.observe(api.setQuizQuestions(targetQuizIdForQuestions, selectedIds), this::isActive,
            ignored -> {
                showQuizNotice("Đã lưu " + selectedIds.size() + " câu hỏi vào đề kiểm tra #" + targetQuizIdForQuestions + "!", false);
                hideAllQuizPanels();
                loadQuizzes();
            },
            err -> {
                showQuizNotice("Lỗi khi lưu câu hỏi: " + formatError(err), true);
                btnSaveSelectedQuestions.setDisable(false);
            },
            () -> btnSaveSelectedQuestions.setDisable(false)
        );
    }

    @FXML
    private void onCancelQuestionSelectionClick() {
        hideAllQuizPanels();
    }

    // =========================================================================
    // UI UTILITIES
    // =========================================================================

    private void hideAllQuizPanels() {
        quizOverview.setVisible(true);
        quizOverview.setManaged(true);
        quizFormPanel.setVisible(false);
        quizFormPanel.setManaged(false);
        autoQuizPanel.setVisible(false);
        autoQuizPanel.setManaged(false);
        questionSelectionPanel.setVisible(false);
        questionSelectionPanel.setManaged(false);
        assignPanel.setVisible(false);
        assignPanel.setManaged(false);
        quizDetailPanel.setVisible(false);
        quizDetailPanel.setManaged(false);
        questionPickerPanel.setVisible(false);
        questionPickerPanel.setManaged(false);
        scrollToTop();
    }

    private void showQuizPanel(VBox panel) {
        hideAllQuizPanels();
        quizOverview.setVisible(false);
        quizOverview.setManaged(false);
        panel.setVisible(true);
        panel.setManaged(true);
    }

    private void scrollToBottom() {
        javafx.application.Platform.runLater(() -> {
            if (quizScrollPane != null) {
                quizScrollPane.setVvalue(1.0);
            }
            if (quizFormPanel != null && quizFormPanel.getScene() != null) {
                var pageScroll = (ScrollPane) quizFormPanel.getScene().lookup("#pageScroll");
                if (pageScroll != null) {
                    pageScroll.setVvalue(1.0);
                }
            }
        });
    }

    private void scrollToTop() {
        javafx.application.Platform.runLater(() -> {
            if (quizScrollPane != null) {
                quizScrollPane.setVvalue(0.0);
            }
            if (quizFormPanel != null && quizFormPanel.getScene() != null) {
                var pageScroll = (ScrollPane) quizFormPanel.getScene().lookup("#pageScroll");
                if (pageScroll != null) {
                    pageScroll.setVvalue(0.0);
                }
            }
        });
    }

    private void showFormError(String msg) {
        formError.setText(msg);
        formError.setVisible(true);
        formError.setManaged(true);
    }

    private void showQuizNotice(String msg, boolean isError) {
        quizNotice.setText(msg);
        quizNotice.getStyleClass().removeAll("notice", "error-notice");
        quizNotice.getStyleClass().add(isError ? "error-notice" : "notice");
        quizNotice.setVisible(true);
        quizNotice.setManaged(true);
    }

    private void setQuizLoading(boolean loading) {
        quizProgress.setVisible(loading);
        btnRefreshQuiz.setDisable(loading);
    }

    // =========================================================================
    // CLASS MANAGEMENT LOGIC (FR-A07)
    // =========================================================================

    private void configureClassTable() {
        TableColumn<ClassDto, String> colId = new TableColumn<>("ID");
        colId.setPrefWidth(60);
        colId.setCellValueFactory(c -> new SimpleStringProperty(String.valueOf(c.getValue().id())));

        TableColumn<ClassDto, String> colName = new TableColumn<>("Tên Lớp");
        colName.setPrefWidth(160);
        colName.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().name()));

        TableColumn<ClassDto, String> colDesc = new TableColumn<>("Mô Tả");
        colDesc.setPrefWidth(220);
        colDesc.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().description() == null ? "—" : c.getValue().description()));

        TableColumn<ClassDto, String> colTeacher = new TableColumn<>("Giáo viên");
        colTeacher.setPrefWidth(140);
        colTeacher.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().teacherName()));

        classTable.getColumns().setAll(List.of(colId, colName, colDesc, colTeacher));
        classTable.setItems(classList);
        classTable.setPlaceholder(new Label("Chưa có lớp học nào."));
    }

    private void configureMemberTable() {
        TableColumn<ClassMemberDto, String> colId = new TableColumn<>("Student ID");
        colId.setPrefWidth(80);
        colId.setCellValueFactory(m -> new SimpleStringProperty(String.valueOf(m.getValue().studentId())));

        TableColumn<ClassMemberDto, String> colUsername = new TableColumn<>("Tên Đăng Nhập");
        colUsername.setPrefWidth(150);
        colUsername.setCellValueFactory(m -> new SimpleStringProperty(m.getValue().studentUsername()));

        TableColumn<ClassMemberDto, String> colName = new TableColumn<>("Họ và Tên");
        colName.setPrefWidth(180);
        colName.setCellValueFactory(m -> new SimpleStringProperty(m.getValue().studentFullName()));

        TableColumn<ClassMemberDto, String> colEnrolled = new TableColumn<>("Ngày tham gia");
        colEnrolled.setPrefWidth(140);
        colEnrolled.setCellValueFactory(m -> new SimpleStringProperty(DATE_FORMAT.format(m.getValue().enrolledAt())));

        memberTable.getColumns().setAll(List.of(colId, colUsername, colName, colEnrolled));
        memberTable.setItems(memberList);
        memberTable.setPlaceholder(new Label("Chọn một lớp để xem danh sách học viên."));
    }

    @FXML
    private void loadClasses() {
        classProgress.setVisible(true);
        btnRefreshClasses.setDisable(true);
        fx.observe(api.listClasses(), this::isActive,
            classes -> {
                classList.setAll(classes);
                classProgress.setVisible(false);
                btnRefreshClasses.setDisable(false);
            },
            err -> {
                showClassNotice("Lỗi khi tải danh sách lớp: " + formatError(err), true);
                classProgress.setVisible(false);
                btnRefreshClasses.setDisable(false);
            },
            () -> {
                classProgress.setVisible(false);
                btnRefreshClasses.setDisable(false);
            }
        );
    }

    private void onClassSelected(ClassDto selected) {
        if (selected == null) {
            selectedClassLabel.setText("Chưa chọn lớp nào");
            memberList.clear();
            return;
        }

        selectedClassLabel.setText("Danh sách học viên lớp: " + selected.name());
        loadClassMembers(selected.id());
    }

    private void loadClassMembers(long classId) {
        classProgress.setVisible(true);
        fx.observe(api.listClassMembers(classId), this::isActive,
            members -> {
                memberList.setAll(members);
                classProgress.setVisible(false);
            },
            err -> {
                showClassNotice("Lỗi khi tải thành viên lớp: " + formatError(err), true);
                classProgress.setVisible(false);
            },
            () -> classProgress.setVisible(false)
        );
    }

    private void showClassNotice(String msg, boolean isError) {
        classNotice.setText(msg);
        classNotice.getStyleClass().removeAll("notice", "error-notice");
        classNotice.getStyleClass().add(isError ? "error-notice" : "notice");
        classNotice.setVisible(true);
        classNotice.setManaged(true);
    }

    private static String formatError(Throwable failure) {
        Throwable unwrapped = FxAsync.unwrap(failure);
        if (unwrapped instanceof ApiException apiEx) {
            if (apiEx.errorCode() == ErrorCode.CONFLICT) return "Dữ liệu bị trùng lặp hoặc xung đột trạng thái";
            if (apiEx.errorCode() == ErrorCode.FORBIDDEN) return "Bạn không có quyền thực hiện thao tác này";
            if (apiEx.errorCode() == ErrorCode.NOT_FOUND) return "Không tìm thấy dữ liệu yêu cầu";
            if (apiEx.errorCode() == ErrorCode.VALIDATION_ERROR) return "Dữ liệu không hợp lệ";
            return apiEx.errorCode().name();
        }
        return unwrapped.getMessage() != null ? unwrapped.getMessage() : unwrapped.getClass().getSimpleName();
    }

    // --- Helper model for question selection table ---
    public static class QuestionRow {
        private final QuestionDto question;
        private final BooleanProperty selected;

        public QuestionRow(QuestionDto question, boolean selected) {
            this.question = question;
            this.selected = new SimpleBooleanProperty(selected);
        }

        public QuestionDto getQuestion() { return question; }
        public BooleanProperty selectedProperty() { return selected; }
        public boolean isSelected() { return selected.get(); }
        public void setSelected(boolean val) { this.selected.set(val); }
    }

    public static class AssignClassRow {
        private final ClassDto classDto;
        private final BooleanProperty selected;
        private final boolean alreadyAssigned;

        public AssignClassRow(ClassDto classDto, boolean selected, boolean alreadyAssigned) {
            this.classDto = classDto;
            this.selected = new SimpleBooleanProperty(selected);
            this.alreadyAssigned = alreadyAssigned;
        }

        public ClassDto getClassDto() { return classDto; }
        public BooleanProperty selectedProperty() { return selected; }
        public boolean isSelected() { return selected.get(); }
        public void setSelected(boolean val) { this.selected.set(val); }
        public boolean isAlreadyAssigned() { return alreadyAssigned; }
    }

    public static class DetailQuestionRow {
        private final QuestionDto question;

        public DetailQuestionRow(QuestionDto question) {
            this.question = question;
        }

        public QuestionDto getQuestion() { return question; }
    }
}
