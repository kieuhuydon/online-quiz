package quiz.client.ui.admin.account;

import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.VBox;
import quiz.client.api.ApiException;
import quiz.client.api.QuizApi;
import quiz.client.ui.SceneNavigator;
import quiz.client.ui.ScreenController;
import quiz.client.ui.Screen;
import quiz.client.util.FxAsync;
import quiz.common.dto.AccountDto;
import quiz.common.enums.Role;
import quiz.common.protocol.ErrorCode;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Function;

/** FX thread owns the form and list. Responses update only the mounted session and latest load. */
public final class AccountController extends ScreenController {
    private static final ZoneId DISPLAY_ZONE = ZoneId.systemDefault();
    private static final DateTimeFormatter CREATED_AT_FORMAT =
        DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss").withZone(DISPLAY_ZONE);
    private final QuizApi api;
    private final SceneNavigator navigator;
    private final FxAsync fx;
    @FXML private VBox confirmation;
    @FXML private TableView<AccountDto> accountTable;
    @FXML private Button refreshAccounts;
    @FXML private Button createAccount;
    @FXML private Button toggleAccount;
    @FXML private Button confirmLock;
    @FXML private Label listStatus;
    @FXML private Label accountNotice;
    @FXML private Label selectedAccount;
    @FXML private javafx.scene.layout.HBox accountActions;
    @FXML private Label confirmationText;
    @FXML private ProgressIndicator accountProgress;
    private long loadVersion;
    private boolean loading;
    private boolean writing;
    private boolean hasLoadedAccounts;
    private boolean listFresh;
    private AccountDto lockTarget;
    private Long pendingSelection;

    public AccountController(QuizApi api, SceneNavigator navigator, FxAsync fx) {
        this.api = api;
        this.navigator = navigator;
        this.fx = fx;
    }

    @FXML
    private void initialize() {
        configureTable();
        accountTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        updateControls();
    }

    private void configureTable() {
        addColumn("ID", 65, account -> Long.toString(account.id()));
        addColumn("Tên đăng nhập", 145, AccountDto::username);
        addColumn("Họ và tên", 200, AccountDto::fullName);
        addColumn("Email", 200, account -> account.email() == null ? "—" : account.email());
        addColumn("Vai trò", 105, account -> Screen.roleLabel(account.role()));
        addColumn("Trạng thái", 125, account -> account.active() ? "Hoạt động" : "Đã khóa");
        addColumn("Ngày tạo", 165, account -> CREATED_AT_FORMAT.format(account.createdAt()));
        accountTable.setPlaceholder(new Label("Chưa tải danh sách tài khoản."));
        accountTable.getSelectionModel().selectedItemProperty()
            .addListener((observable, previous, selected) -> cancelLock());
    }

    private void addColumn(String title, double width, Function<AccountDto, String> text) {
        TableColumn<AccountDto, String> column = new TableColumn<>(title);
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
        accountTable.getColumns().add(column);
    }

    public void selectAfterLoad(long id) {
        pendingSelection = id;
        applyPendingSelection();
    }

    private void applyPendingSelection() {
        if (pendingSelection == null) return;
        accountTable.getItems().stream().filter(account -> account.id() == pendingSelection)
            .findFirst().ifPresent(account -> {
                accountTable.getSelectionModel().select(account);
                accountTable.scrollTo(account);
                pendingSelection = null;
            });
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
        accountTable.setPlaceholder(new Label("Đang tải danh sách…"));
        updateControls();
        fx.observe(api.listAccounts(), () -> isActive() && version == loadVersion,
            this::showAccounts, this::showLoadFailure, () -> {
                loading = false;
                updateControls();
            });
    }

    private void showAccounts(List<AccountDto> accounts) {
        AccountDto selected = accountTable.getSelectionModel().getSelectedItem();
        accountTable.getItems().setAll(accounts);
        if (selected != null) {
            accounts.stream()
                .filter(account -> account.id() == selected.id())
                .findFirst()
                .ifPresent(account -> accountTable.getSelectionModel().select(account));
        }
        hasLoadedAccounts = true;
        applyPendingSelection();
        listFresh = true;
        accountTable.setPlaceholder(new Label("Chưa có tài khoản nào."));
        listStatus.setText(accounts.isEmpty() ? "Danh sách trống." : accounts.size() + " tài khoản • Đã cập nhật");
    }

    private void showLoadFailure(Throwable failure) {
        accountTable.setPlaceholder(new Label("Không tải được danh sách. Chọn Tải lại để thử lại."));
        listStatus.setText("Không tải được danh sách. " + AccountMessages.message(failure)
            + (hasLoadedAccounts ? " Dữ liệu đang hiển thị là lần tải trước." : ""));
    }

    @FXML
    private void create() {
        if (isActive() && !writing) navigator.show(Screen.ADMIN_CREATE_ACCOUNT);
    }

    @FXML
    private void toggle() {
        AccountDto selected = accountTable.getSelectionModel().getSelectedItem();
        if (!isActive() || writing || loading || !listFresh || selected == null || selected.role() == Role.ADMIN) {
            return;
        }
        if (!selected.active()) {
            setActive(selected, true);
            return;
        }
        lockTarget = selected;
        confirmationText.setText("Khóa tài khoản “" + selected.username() + "”? Các phiên đăng nhập hiện có sẽ bị thu hồi. Người dùng phải đăng nhập lại sau khi được mở khóa.");
        confirmation.setVisible(true);
        confirmation.setManaged(true);
        confirmLock.requestFocus();
        updateControls();
    }

    @FXML
    private void confirmLock() {
        AccountDto selected = lockTarget;
        if (selected == null || !isActive() || writing || loading || !listFresh) {
            return;
        }
        cancelLock();
        setActive(selected, false);
    }

    @FXML
    private void cancelLock() {
        lockTarget = null;
        confirmation.setVisible(false);
        confirmation.setManaged(false);
        updateControls();
    }

    private void setActive(AccountDto selected, boolean active) {
        beginWrite(active ? "Đang mở khóa tài khoản…" : "Đang khóa tài khoản…");
        fx.observe(navigator.trackAccountWrite(api.setAccountActive(selected.id(), active)), this::isActive, ignored -> {
            writing = false;
            show(accountNotice, "Đã " + (active ? "mở khóa" : "khóa") + " tài khoản “" + selected.username() + "”.", false);
            reload();
        }, failure -> {
            show(accountNotice, AccountMessages.message(failure), true);
            if (failure instanceof ApiException business && business.errorCode() == ErrorCode.NOT_FOUND) {
                writing = false;
                reload();
            }
        }, this::finishWrite);
    }

    private void beginWrite(String message) {
        writing = true;
        cancelLock();
        show(accountNotice, message, false);
    }

    private void finishWrite() {
        writing = false;
        updateControls();
    }

    private void updateControls() {
        boolean busy = writing || loading;
        createAccount.setDisable(writing);
        refreshAccounts.setDisable(writing);
        AccountDto selected = accountTable.getSelectionModel().getSelectedItem();
        accountActions.setVisible(selected != null);
        accountActions.setManaged(selected != null);
        selectedAccount.setText(selected == null ? ""
            : "Đã chọn: " + selected.username() + (selected.role() == Role.ADMIN ? " • Tài khoản Admin được bảo vệ." : ""));
        toggleAccount.setText(selected != null && !selected.active() ? "Mở khóa tài khoản" : "Khóa tài khoản");
        toggleAccount.setDisable(busy || !listFresh || lockTarget != null || selected == null || selected.role() == Role.ADMIN);
        confirmLock.setDisable(busy);
        accountProgress.setVisible(busy);
        accountProgress.setManaged(busy);
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

    @Override
    protected void onDispose() {
        ++loadVersion;
        lockTarget = null;
    }
}
