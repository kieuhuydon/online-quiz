package quiz.client.ui.teacher;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import quiz.client.api.ApiException;
import quiz.client.api.QuizApi;
import quiz.client.ui.SceneNavigator;
import quiz.client.ui.ScreenController;
import quiz.client.util.FxAsync;
import quiz.common.dto.*;
import quiz.common.dto.AntiCheatEventDto;
import quiz.common.enums.AntiCheatPolicyType;
import quiz.common.enums.Difficulty;
import quiz.common.enums.QuizStatus;
import quiz.common.protocol.ErrorCode;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

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
    @FXML private Button btnEditQuiz;
    @FXML private Button btnSelectQuestions;
    @FXML private Button btnOpenQuiz;
    @FXML private Button btnCloseQuiz;
    @FXML private Button btnAssignQuiz;
    @FXML private Button btnViewDetail;
    @FXML private Button btnAntiCheatLogs;
    @FXML private VBox antiCheatPanel;
    @FXML private TextField txtAttemptIdFilter;
    @FXML private Label antiCheatStatusLabel;
    @FXML private TableView<AntiCheatEventDto> antiCheatTable;
    @FXML private Button btnRefreshQuiz;

    // Quiz Form (Create / Edit)
    @FXML private VBox quizFormPanel;
    @FXML private Label formTitle;
    @FXML private TextField txtQuizTitle;
    @FXML private TextField txtQuizCategory;
    @FXML private TextField txtQuizDuration;
    @FXML private TextField txtQuizPassScore;
    @FXML private ComboBox<AntiCheatPolicyType> cmbQuizPolicy;
    @FXML private TextField txtQuizMaxViolations;
    @FXML private TextArea txtQuizDescription;
    @FXML private Label formError;
    @FXML private Button btnSaveQuiz;
    @FXML private Button btnCancelQuizForm;
    private boolean isEditingQuiz = false;
    private long editingQuizId = 0;

    // Question Selection Panel
    @FXML private VBox questionSelectionPanel;
    @FXML private Label questionSelectionTitle;
    @FXML private TableView<QuestionRow> questionTable;
    @FXML private Label selectedQuestionsCount;
    @FXML private Button btnSaveSelectedQuestions;
    @FXML private Button btnCancelQuestionSelection;
    private long targetQuizIdForQuestions = 0;

    // Assign Quiz to Class Panel
    @FXML private VBox assignPanel;
    @FXML private Label assignQuizTitle;
    @FXML private ComboBox<ClassDto> cmbAssignClass;
    @FXML private Button btnConfirmAssign;
    @FXML private Button btnCancelAssign;
    private long targetQuizIdForAssign = 0;

    // Quiz Details Panel
    @FXML private VBox quizDetailPanel;
    @FXML private Label detailQuizTitle;
    @FXML private Label detailQuizInfo;
    @FXML private Label detailQuestionsCount;
    @FXML private Label detailAssignedClasses;
    @FXML private ListView<String> listQuizQuestions;
    @FXML private Button btnCloseDetail;

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

    private final ObservableList<QuizDto> quizList = FXCollections.observableArrayList();
    private final ObservableList<ClassDto> classList = FXCollections.observableArrayList();
    private final ObservableList<ClassMemberDto> memberList = FXCollections.observableArrayList();
    private final ObservableList<QuestionRow> questionRows = FXCollections.observableArrayList();

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

        // 1. Format Chính sách vi phạm (cmbQuizPolicy)
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
        cmbQuizPolicy.setConverter(policyConverter);
        cmbQuizPolicy.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(AntiCheatPolicyType p, boolean empty) {
                super.updateItem(p, empty);
                setText(empty || p == null ? null : policyConverter.toString(p));
            }
        });
        cmbQuizPolicy.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(AntiCheatPolicyType p, boolean empty) {
                super.updateItem(p, empty);
                setText(empty || p == null ? null : policyConverter.toString(p));
            }
        });
        cmbQuizPolicy.getItems().setAll(AntiCheatPolicyType.WARN_THEN_AUTO_SUBMIT, AntiCheatPolicyType.LOG_ONLY);
        cmbQuizPolicy.setValue(AntiCheatPolicyType.WARN_THEN_AUTO_SUBMIT);

        // 2. Format Chọn Lớp Học (cmbAssignClass)
        javafx.util.StringConverter<ClassDto> classConverter = new javafx.util.StringConverter<>() {
            @Override
            public String toString(ClassDto c) {
                if (c == null) return "Chưa chọn lớp nào";
                if (c.description() != null && !c.description().isBlank()) {
                    return c.name() + " (" + c.description() + ")";
                }
                return c.name();
            }
            @Override
            public ClassDto fromString(String s) { return null; }
        };
        cmbAssignClass.setConverter(classConverter);
        cmbAssignClass.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(ClassDto c, boolean empty) {
                super.updateItem(c, empty);
                setText(empty || c == null ? null : classConverter.toString(c));
            }
        });
        cmbAssignClass.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(ClassDto c, boolean empty) {
                super.updateItem(c, empty);
                setText(empty || c == null ? null : classConverter.toString(c));
            }
        });

        quizTable.getSelectionModel().selectedItemProperty().addListener((obs, oldV, selected) -> updateQuizButtons(selected));
        classTable.getSelectionModel().selectedItemProperty().addListener((obs, oldV, selected) -> onClassSelected(selected));

        hideAllQuizPanels();
        updateQuizButtons(null);
    }

    @Override
    protected void onMount() {
        loadQuizzes();
        loadClasses();
    }

    // =========================================================================
    // QUIZ MANAGEMENT LOGIC
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
        quizTable.setPlaceholder(new Label("Chưa có đề kiểm tra nào. Bấm 'Tạo Đề Mới' để bắt đầu."));
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

    @FXML
    private void onNewQuizClick() {
        hideAllQuizPanels();
        isEditingQuiz = false;
        editingQuizId = 0;
        formTitle.setText("Tạo Đề Kiểm Tra Mới (Trạng thái: DRAFT)");
        txtQuizTitle.clear();
        txtQuizCategory.setText("Tiếng Anh");
        txtQuizDuration.setText("30");
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

    // --- QUESTION SELECTION ---

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
        // Load bank questions AND currently selected questions for this quiz
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

    // --- PUBLISH / OPEN QUIZ (Sinh PIN) ---

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

    // --- ASSIGN TO CLASS ---

    @FXML
    private void onAssignQuizClick() {
        QuizDto sel = quizTable.getSelectionModel().getSelectedItem();
        if (sel == null) return;

        targetQuizIdForAssign = sel.id();
        assignQuizTitle.setText("Giao đề thi: " + sel.title() + " cho Lớp học");
        hideAllQuizPanels();
        assignPanel.setVisible(true);
        assignPanel.setManaged(true);
        scrollToBottom();

        cmbAssignClass.setItems(classList);
        if (!classList.isEmpty()) {
            cmbAssignClass.setValue(classList.get(0));
        }
    }

    @FXML
    private void onConfirmAssignClick() {
        ClassDto targetClass = cmbAssignClass.getValue();
        if (targetClass == null) {
            showQuizNotice("Vui lòng chọn một lớp học để giao bài", true);
            return;
        }

        btnConfirmAssign.setDisable(true);
        fx.observe(api.assignQuiz(targetQuizIdForAssign, targetClass.id()), this::isActive,
            ignored -> {
                showQuizNotice("✅ Đã giao đề thi #" + targetQuizIdForAssign + " cho lớp '" + targetClass.name() + "' thành công! Học viên lớp này có thể nhìn thấy bài thi.", false);
                hideAllQuizPanels();
            },
            err -> {
                showQuizNotice("Lỗi khi giao bài: " + formatError(err), true);
                btnConfirmAssign.setDisable(false);
            },
            () -> btnConfirmAssign.setDisable(false)
        );
    }

    @FXML
    private void onCancelAssignClick() {
        hideAllQuizPanels();
    }

    // --- VIEW QUIZ DETAILS ---

    @FXML
    private void onViewDetailClick() {
        QuizDto sel = quizTable.getSelectionModel().getSelectedItem();
        if (sel == null) return;

        hideAllQuizPanels();
        quizDetailPanel.setVisible(true);
        quizDetailPanel.setManaged(true);
        scrollToBottom();

        detailQuizTitle.setText("Chi tiết đề kiểm tra #" + sel.id() + ": " + sel.title());
        detailQuizInfo.setText("Chủ đề: " + sel.category() + " | Thời lượng: " + sel.durationMinutes() + " phút | Điểm đạt: " + sel.passScore() + "% | Trạng thái: " + sel.status() + (sel.pin() != null ? " (PIN: " + sel.pin() + ")" : ""));

        setQuizLoading(true);
        fx.observe(api.getQuiz(sel.id()), this::isActive,
            detail -> {
                detailQuestionsCount.setText("Số lượng câu hỏi: " + detail.questions().size() + " câu");
                List<String> qLines = new ArrayList<>();
                int idx = 1;
                for (QuestionDto q : detail.questions()) {
                    qLines.add(idx++ + ". " + q.content() + " [Độ khó: " + q.difficulty() + " | Đáp án đúng: " + q.correctOption() + "]");
                }
                listQuizQuestions.setItems(FXCollections.observableArrayList(qLines));
                detailAssignedClasses.setText("Các lớp đã được giao: " + (detail.assignedClassIds().isEmpty() ? "Chưa giao cho lớp nào" : detail.assignedClassIds().size() + " lớp"));
                setQuizLoading(false);
            },
            err -> {
                showQuizNotice("Lỗi tải chi tiết: " + formatError(err), true);
                setQuizLoading(false);
            },
            () -> setQuizLoading(false)
        );
    }

    @FXML
    private void onCloseDetailClick() {
        hideAllQuizPanels();
    }

    private void hideAllQuizPanels() {
        quizFormPanel.setVisible(false);
        quizFormPanel.setManaged(false);
        questionSelectionPanel.setVisible(false);
        questionSelectionPanel.setManaged(false);
        assignPanel.setVisible(false);
        assignPanel.setManaged(false);
        quizDetailPanel.setVisible(false);
        quizDetailPanel.setManaged(false);
        if (antiCheatPanel != null) {
            antiCheatPanel.setVisible(false);
            antiCheatPanel.setManaged(false);
        }
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
}
