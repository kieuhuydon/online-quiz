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
    @FXML private Button btnViewResults;
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
    @FXML private Slider sliderEasy;
    @FXML private Slider sliderMedium;
    @FXML private Slider sliderHard;
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

    // Exam Results Panel (Feature 5)
    @FXML private VBox quizResultsPanel;
    @FXML private Label lblResultsQuizTitle;
    @FXML private Label lblResultsStatusBadge;
    @FXML private Label lblResultsQuizInfo;
    @FXML private Label lblResultsTotalSubmissions;
    @FXML private Label lblResultsAverageScore;
    @FXML private Label lblResultsPassRate;
    @FXML private Label lblResultsHighestScore;
    @FXML private Label resultsNotice;
    @FXML private TableView<ResultDto> resultsTable;

    // --- Tab 2: Class Controls ---
    @FXML private ScrollPane classScrollPane;
    @FXML private TableView<ClassDto> classTable;
    @FXML private TableView<ClassMemberDto> memberTable;
    @FXML private Label selectedClassLabel;
    @FXML private Label classNotice;
    @FXML private ProgressIndicator classProgress;
    @FXML private TextField txtNewClassName;
    @FXML private TextField txtNewClassDesc;
    @FXML private Button btnCreateClass;
    @FXML private Button btnRefreshClasses;
    @FXML private TextField txtAddStudentId;
    @FXML private Button btnAddMember;
    @FXML private Button btnRemoveMember;

    // Observable Lists & State
    private final ObservableList<QuizDto> quizList = FXCollections.observableArrayList();
    private final ObservableList<ClassDto> classList = FXCollections.observableArrayList();
    private final ObservableList<ClassMemberDto> memberList = FXCollections.observableArrayList();
    private final ObservableList<QuestionRow> questionRows = FXCollections.observableArrayList();
    private final ObservableList<AssignClassRow> assignClassRows = FXCollections.observableArrayList();
    private final ObservableList<DetailQuestionRow> detailQuestionRows = FXCollections.observableArrayList();
    private final ObservableList<QuestionDto> pickerQuestionRows = FXCollections.observableArrayList();
    private final ObservableList<ResultDto> resultsList = FXCollections.observableArrayList();

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
        configureQuizTable();
        configureClassTable();
        configureMemberTable();
        configureQuestionTable();
        configureAssignClassTable();
        configureDetailQuestionTable();
        configurePickerTable();
        configureResultsTable();

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

        // 4. Feature 1: Listeners cho thanh trượt tỷ lệ độ khó tạo đề tự động
        sliderEasy.valueProperty().addListener((obs, o, n) -> updateAutoDistribution());
        sliderMedium.valueProperty().addListener((obs, o, n) -> updateAutoDistribution());
        sliderHard.valueProperty().addListener((obs, o, n) -> updateAutoDistribution());
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
        btnViewResults.setDisable(!hasSel);
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

        sliderEasy.setValue(30);
        sliderMedium.setValue(30);
        sliderHard.setValue(40);
        updateAutoDistribution();

        autoQuizError.setVisible(false);
        lblAutoDurationError.setVisible(false);
        autoQuizPanel.setVisible(true);
        autoQuizPanel.setManaged(true);
        scrollToBottom();
    }

    @FXML
    private void onPreset1Click() {
        sliderEasy.setValue(30);
        sliderMedium.setValue(30);
        sliderHard.setValue(40);
        updateAutoDistribution();
    }

    @FXML
    private void onPreset2Click() {
        sliderEasy.setValue(40);
        sliderMedium.setValue(40);
        sliderHard.setValue(20);
        updateAutoDistribution();
    }

    @FXML
    private void onPreset3Click() {
        sliderEasy.setValue(20);
        sliderMedium.setValue(50);
        sliderHard.setValue(30);
        updateAutoDistribution();
    }

    @FXML
    private void onPreset4Click() {
        sliderEasy.setValue(50);
        sliderMedium.setValue(30);
        sliderHard.setValue(20);
        updateAutoDistribution();
    }

    private void updateAutoDistribution() {
        int totalQuestions = parsePositiveInt(txtAutoTotalQuestions != null ? txtAutoTotalQuestions.getText() : "30", 30);
        int easyPct = (int) Math.round(sliderEasy.getValue());
        int medPct = (int) Math.round(sliderMedium.getValue());
        int hardPct = (int) Math.round(sliderHard.getValue());
        int totalPct = easyPct + medPct + hardPct;

        int easyCount = (int) Math.round(totalQuestions * (easyPct / 100.0));
        int medCount = (int) Math.round(totalQuestions * (medPct / 100.0));
        int hardCount = Math.max(0, totalQuestions - easyCount - medCount);

        lblEasyPercent.setText(easyPct + "% (" + easyCount + " câu)");
        lblMediumPercent.setText(medPct + "% (" + medCount + " câu)");
        lblHardPercent.setText(hardPct + "% (" + hardCount + " câu)");

        lblEasyCount.setText(easyCount + " câu");
        lblMediumCount.setText(medCount + " câu");
        lblHardCount.setText(hardCount + " câu");

        lblAutoDistributionSummary.setText(String.format("Phân bổ: %d Dễ (%d%%) + %d Vừa (%d%%) + %d Khó (%d%%) = %d câu hỏi (Tổng: %d%%)",
            easyCount, easyPct, medCount, medPct, hardCount, hardPct, totalQuestions, totalPct));

        boolean valid100 = (totalPct == 100);
        lblAutoPercentageError.setVisible(!valid100);
        lblAutoPercentageError.setManaged(!valid100);
        if (!valid100) {
            lblAutoPercentageError.setText("⚠️ Tổng tỷ lệ hiện tại là " + totalPct + "%. Vui lòng điều chỉnh để tổng đúng 100%!");
            lblAutoDistributionSummary.setStyle("-fx-font-weight: bold; -fx-text-fill: #dc2626;");
        } else {
            lblAutoDistributionSummary.setStyle("-fx-font-weight: bold; -fx-text-fill: #0f766e;");
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

        int easyPct = (int) Math.round(sliderEasy.getValue());
        int medPct = (int) Math.round(sliderMedium.getValue());
        int hardPct = (int) Math.round(sliderHard.getValue());
        if (easyPct + medPct + hardPct != 100) {
            showAutoQuizError("Tổng phần trăm độ khó phải bằng 100% (Hiện tại: " + (easyPct + medPct + hardPct) + "%)");
            return;
        }

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
            private final Label lbl = new Label();
            {
                lbl.setWrapText(true);
            }
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                } else {
                    lbl.setText(item);
                    setGraphic(lbl);
                }
            }
        });

        TableColumn<DetailQuestionRow, String> colOptions = new TableColumn<>("Đáp án đúng & Lựa chọn");
        colOptions.setPrefWidth(220);
        colOptions.setCellValueFactory(r -> {
            QuestionDto q = r.getValue().getQuestion();
            return new SimpleStringProperty("Đáp án: " + q.correctOption() + " | A: " + q.optionA() + " | B: " + q.optionB());
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

        hideAllQuizPanels();
        quizDetailPanel.setVisible(true);
        quizDetailPanel.setManaged(true);
        questionPickerPanel.setVisible(false);
        questionPickerPanel.setManaged(false);
        scrollToBottom();

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
        hideAllQuizPanels();
        assignPanel.setVisible(true);
        assignPanel.setManaged(true);
        scrollToBottom();

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
    // FEATURE 5: KẾT QUẢ THI CỦA HỌC SINH (EXAM RESULTS)
    // =========================================================================

    private void configureResultsTable() {
        TableColumn<ResultDto, String> colStt = new TableColumn<>("#");
        colStt.setPrefWidth(45);
        colStt.setCellValueFactory(r -> new SimpleStringProperty(String.valueOf(resultsList.indexOf(r.getValue()) + 1)));

        TableColumn<ResultDto, String> colStudent = new TableColumn<>("Học sinh");
        colStudent.setPrefWidth(180);
        colStudent.setCellValueFactory(r -> new SimpleStringProperty(r.getValue().studentName()));

        TableColumn<ResultDto, String> colScore = new TableColumn<>("Điểm số");
        colScore.setPrefWidth(95);
        colScore.setCellValueFactory(r -> new SimpleStringProperty(String.format("%.1f / 10", r.getValue().score())));
        colScore.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle("");
                } else {
                    setText(item);
                    setStyle("-fx-font-weight: bold; -fx-text-fill: #1d4ed8; -fx-font-size: 13px;");
                }
            }
        });

        TableColumn<ResultDto, String> colCorrect = new TableColumn<>("Số câu đúng");
        colCorrect.setPrefWidth(120);
        colCorrect.setCellValueFactory(r -> new SimpleStringProperty(r.getValue().correctCount() + " / " + r.getValue().totalQuestions() + " câu"));

        TableColumn<ResultDto, String> colStatus = new TableColumn<>("Kết quả");
        colStatus.setPrefWidth(110);
        colStatus.setCellValueFactory(r -> new SimpleStringProperty(r.getValue().passed() ? "ĐẠT" : "CHƯA ĐẠT"));
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
                    if ("ĐẠT".equals(item)) {
                        badge.getStyleClass().add("badge-pass");
                    } else {
                        badge.getStyleClass().add("badge-fail");
                    }
                    setGraphic(badge);
                    setText(null);
                }
            }
        });

        TableColumn<ResultDto, String> colReason = new TableColumn<>("Hình thức nộp");
        colReason.setPrefWidth(140);
        colReason.setCellValueFactory(r -> {
            SubmitReason reason = r.getValue().submitReason();
            if (reason == null) return new SimpleStringProperty("—");
            return new SimpleStringProperty(switch (reason) {
                case MANUAL -> "Tự nộp bài";
                case TIMEOUT -> "Hết giờ làm bài";
                case ANTICHEAT -> "Vi phạm tự nộp";
            });
        });

        TableColumn<ResultDto, String> colSubmitted = new TableColumn<>("Thời gian nộp");
        colSubmitted.setPrefWidth(140);
        colSubmitted.setCellValueFactory(r -> new SimpleStringProperty(r.getValue().submittedAt() != null ? DATE_FORMAT.format(r.getValue().submittedAt()) : "—"));

        resultsTable.getColumns().setAll(List.of(colStt, colStudent, colScore, colCorrect, colStatus, colReason, colSubmitted));
        resultsTable.setItems(resultsList);
        resultsTable.setPlaceholder(new Label("Chưa có học sinh nào hoàn thành bài thi cho đề kiểm tra này."));
    }

    @FXML
    private void onViewResultsClick() {
        QuizDto sel = quizTable.getSelectionModel().getSelectedItem();
        if (sel == null) return;

        hideAllQuizPanels();
        quizResultsPanel.setVisible(true);
        quizResultsPanel.setManaged(true);
        scrollToBottom();

        lblResultsQuizTitle.setText("📊 BẢNG KẾT QUẢ THI: " + sel.title());
        lblResultsStatusBadge.setText(sel.status().name());
        lblResultsStatusBadge.getStyleClass().removeAll("badge-draft", "badge-open", "badge-closed");
        lblResultsStatusBadge.getStyleClass().add(sel.status() == QuizStatus.OPEN ? "badge-open" : "badge-closed");
        lblResultsQuizInfo.setText("Chủ đề: " + sel.category() + " | Thời lượng: " + sel.durationMinutes() + " phút | Điểm đạt: " + String.format("%.0f%%", sel.passScore()));

        loadResultsForQuiz(sel.id());
    }

    @FXML
    private void onRefreshResultsClick() {
        QuizDto sel = quizTable.getSelectionModel().getSelectedItem();
        if (sel != null) {
            loadResultsForQuiz(sel.id());
        }
    }

    private void loadResultsForQuiz(long quizId) {
        setQuizLoading(true);
        fx.observe(api.listQuizResults(quizId), this::isActive,
            results -> {
                setQuizLoading(false);
                resultsList.setAll(results);

                int total = results.size();
                lblResultsTotalSubmissions.setText(total + " bài");

                if (total == 0) {
                    lblResultsAverageScore.setText("0.0");
                    lblResultsPassRate.setText("0%");
                    lblResultsHighestScore.setText("0.0");
                } else {
                    double sum = 0;
                    double max = 0;
                    long passCount = 0;
                    for (ResultDto r : results) {
                        sum += r.score();
                        if (r.score() > max) max = r.score();
                        if (r.passed()) passCount++;
                    }
                    double avg = sum / total;
                    double rate = (passCount * 100.0) / total;

                    lblResultsAverageScore.setText(String.format("%.1f", avg));
                    lblResultsPassRate.setText(String.format("%.0f%%", rate));
                    lblResultsHighestScore.setText(String.format("%.1f", max));
                }
            },
            err -> {
                setQuizLoading(false);
                showQuizNotice("Lỗi khi tải kết quả thi: " + formatError(err), true);
            },
            () -> setQuizLoading(false)
        );
    }

    @FXML
    private void onCloseResultsClick() {
        hideAllQuizPanels();
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
        quizResultsPanel.setVisible(false);
        quizResultsPanel.setManaged(false);
        scrollToTop();
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
            btnAddMember.setDisable(true);
            btnRemoveMember.setDisable(true);
            return;
        }

        selectedClassLabel.setText("Danh sách học viên lớp: " + selected.name());
        btnAddMember.setDisable(false);
        btnRemoveMember.setDisable(false);
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

    @FXML
    private void onCreateClassClick() {
        String name = txtNewClassName.getText();
        String desc = txtNewClassDesc.getText();
        if (name == null || name.strip().isBlank()) {
            showClassNotice("Tên lớp học không được để trống", true);
            return;
        }

        btnCreateClass.setDisable(true);
        fx.observe(api.createClass(new CreateClassDto(name.strip(), desc)), this::isActive,
            created -> {
                showClassNotice("Đã tạo lớp học '" + created.name() + "' thành công!", false);
                txtNewClassName.clear();
                txtNewClassDesc.clear();
                btnCreateClass.setDisable(false);
                loadClasses();
            },
            err -> {
                showClassNotice("Lỗi tạo lớp học: " + formatError(err), true);
                btnCreateClass.setDisable(false);
            },
            () -> btnCreateClass.setDisable(false)
        );
    }

    @FXML
    private void onAddMemberClick() {
        ClassDto sel = classTable.getSelectionModel().getSelectedItem();
        if (sel == null) return;

        String studentIdText = txtAddStudentId.getText();
        if (studentIdText == null || studentIdText.strip().isBlank()) {
            showClassNotice("Vui lòng nhập Student ID để thêm vào lớp", true);
            return;
        }

        long studentId;
        try {
            studentId = Long.parseLong(studentIdText.strip());
            if (studentId <= 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            showClassNotice("Student ID phải là số nguyên dương", true);
            return;
        }

        btnAddMember.setDisable(true);
        fx.observe(api.addClassMember(sel.id(), studentId), this::isActive,
            ignored -> {
                showClassNotice("Đã thêm học viên #" + studentId + " vào lớp " + sel.name() + "!", false);
                txtAddStudentId.clear();
                btnAddMember.setDisable(false);
                loadClassMembers(sel.id());
            },
            err -> {
                showClassNotice("Lỗi thêm học viên: " + formatError(err), true);
                btnAddMember.setDisable(false);
            },
            () -> btnAddMember.setDisable(false)
        );
    }

    @FXML
    private void onRemoveMemberClick() {
        ClassDto selClass = classTable.getSelectionModel().getSelectedItem();
        ClassMemberDto selMember = memberTable.getSelectionModel().getSelectedItem();
        if (selClass == null || selMember == null) {
            showClassNotice("Vui lòng chọn học viên cần xóa khỏi lớp", true);
            return;
        }

        btnRemoveMember.setDisable(true);
        fx.observe(api.removeClassMember(selClass.id(), selMember.studentId()), this::isActive,
            ignored -> {
                showClassNotice("Đã xóa học viên @" + selMember.studentUsername() + " khỏi lớp " + selClass.name(), false);
                btnRemoveMember.setDisable(false);
                loadClassMembers(selClass.id());
            },
            err -> {
                showClassNotice("Lỗi xóa học viên: " + formatError(err), true);
                btnRemoveMember.setDisable(false);
            },
            () -> btnRemoveMember.setDisable(false)
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
