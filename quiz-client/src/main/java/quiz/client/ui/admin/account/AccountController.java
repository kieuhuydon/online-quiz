package quiz.client.ui.admin.account;

import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import quiz.client.api.ApiException;
import quiz.client.api.ClientValidation;
import quiz.client.api.QuizApi;
import quiz.client.network.TransportException;
import quiz.client.ui.SceneNavigator;
import quiz.client.ui.ScreenController;
import quiz.client.util.FxAsync;
import quiz.common.dto.AccountDto;
import quiz.common.dto.CreateAccountDto;
import quiz.common.enums.Role;
import quiz.common.protocol.ErrorCode;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.function.Function;

/** FX thread owns the form and list. Responses update only the mounted session and latest load. */
public final class AccountController extends ScreenController {
    private static final ZoneId ZONE = ZoneId.systemDefault();
    private static final DateTimeFormatter CREATED = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss").withZone(ZONE);
    private final QuizApi api;
    private final SceneNavigator navigator;
    private final FxAsync fx;
    @FXML private GridPane accountPanels;
    @FXML private VBox accountForm, confirmation;
    @FXML private TableView<AccountDto> accountTable;
    @FXML private TextField accountUsername, accountFullName, accountEmail;
    @FXML private PasswordField accountPassword;
    @FXML private ComboBox<Role> accountRole;
    @FXML private Button refreshAccounts, createAccount, toggleAccount, confirmLock;
    @FXML private Label listStatus, accountNotice, selectedAccount, confirmationText, formError, timeZone;
    @FXML private ProgressIndicator accountProgress;
    private long loadVersion;
    private boolean loading, writing, loaded, listFresh;
    private AccountDto lockTarget;

    public AccountController(QuizApi api, SceneNavigator navigator, FxAsync fx) {
        this.api = api; this.navigator = navigator; this.fx = fx;
    }

    @FXML private void initialize() {
        column("ID", 65, account -> Long.toString(account.id()));
        column("Tên đăng nhập", 145, AccountDto::username);
        column("Họ và tên", 200, AccountDto::fullName);
        column("Email", 200, account -> account.email() == null ? "—" : account.email());
        column("Vai trò", 105, account -> account.role().name());
        column("Trạng thái", 125, account -> account.active() ? "Hoạt động" : "Đã khóa");
        column("Ngày tạo", 165, account -> CREATED.format(account.createdAt()));
        accountTable.setPlaceholder(new Label("Chưa tải danh sách tài khoản."));
        accountTable.getSelectionModel().selectedItemProperty().addListener((observable, old, selected) -> {
            cancelLock(); updateControls();
        });
        accountRole.getItems().setAll(Role.TEACHER, Role.STUDENT);
        accountRole.setValue(Role.STUDENT);
        timeZone.setText("Ngày tạo theo múi giờ " + ZONE.getId() + ". Toàn bộ danh sách, chưa phân trang.");
        accountPanels.widthProperty().addListener((observable, old, width) -> layoutPanels(width.doubleValue()));
        layoutPanels(0);
        updateControls();
    }

    private void column(String title, double width, Function<AccountDto, String> text) {
        TableColumn<AccountDto, String> column = new TableColumn<>(title);
        column.setPrefWidth(width);
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(text.apply(cell.getValue())));
        column.setCellFactory(ignored -> new TableCell<>() {
            @Override protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                setText(empty ? null : value);
                setTooltip(empty || value == null ? null : new Tooltip(value));
            }
        });
        accountTable.getColumns().add(column);
    }

    private void layoutPanels(double width) {
        boolean wide = width >= 780;
        ColumnConstraints table = new ColumnConstraints(); table.setPercentWidth(wide ? 65 : 100);
        ColumnConstraints form = new ColumnConstraints(); form.setPercentWidth(wide ? 35 : 0);
        accountPanels.getColumnConstraints().setAll(wide ? java.util.List.of(table, form) : java.util.List.of(table));
        GridPane.setColumnIndex(accountForm, wide ? 1 : 0);
        GridPane.setRowIndex(accountForm, wide ? 0 : 1);
    }

    @Override protected void onMount() { reload(); }

    @FXML private void reload() {
        if (!isActive() || writing) return;
        long version = ++loadVersion;
        loading = true;
        listFresh = false;
        listStatus.setText("Đang tải danh sách…");
        accountTable.setPlaceholder(new Label("Đang tải danh sách…"));
        updateControls();
        fx.observe(api.listAccounts(), () -> isActive() && version == loadVersion, accounts -> {
            AccountDto selected = accountTable.getSelectionModel().getSelectedItem();
            Long selectedId = selected == null ? null : selected.id();
            accountTable.getItems().setAll(accounts);
            if (selectedId != null) accounts.stream().filter(account -> account.id() == selectedId)
                .findFirst().ifPresent(account -> accountTable.getSelectionModel().select(account));
            loaded = true;
            listFresh = true;
            accountTable.setPlaceholder(new Label("Chưa có tài khoản nào."));
            listStatus.setText(accounts.isEmpty() ? "Danh sách trống." : accounts.size() + " tài khoản • Đã cập nhật");
        }, failure -> {
            accountTable.setPlaceholder(new Label("Không tải được danh sách. Chọn Tải lại để thử lại."));
            listStatus.setText("Không tải được danh sách. " + error(failure)
                + (loaded ? " Dữ liệu đang hiển thị là lần tải trước." : ""));
        }, () -> { loading = false; updateControls(); });
    }

    @FXML private void create() {
        if (!isActive() || writing || loading) return;
        CreateAccountDto input;
        try {
            input = ClientValidation.account(new CreateAccountDto(accountUsername.getText(), accountPassword.getText(),
                accountFullName.getText(), accountEmail.getText(), accountRole.getValue()));
        } catch (ApiException invalid) {
            accountPassword.clear();
            show(formError, "Kiểm tra thông tin: tên đăng nhập 1–50 ký tự, họ tên 1–100 ký tự, email hợp lệ tối đa 100 ký tự; mật khẩu ít nhất 8 ký tự và tối đa 72 byte UTF-8.", true);
            return;
        }
        accountUsername.setText(input.username()); accountFullName.setText(input.fullName());
        accountEmail.setText(input.email() == null ? "" : input.email());
        show(formError, "", false);
        beginWrite("Đang tạo tài khoản…");
        fx.observe(navigator.trackAccountWrite(api.createAccount(input)), this::isActive, created -> {
            accountUsername.clear(); accountFullName.clear(); accountEmail.clear(); accountPassword.clear();
            writing = false;
            show(accountNotice, "Đã tạo tài khoản “" + created.username() + "”.", false);
            show(formError, "Đã tạo tài khoản “" + created.username() + "”. Xem trạng thái tải lại trong bảng tài khoản.", false);
            reload();
        }, failure -> {
            show(accountNotice, error(failure), true);
            show(formError, error(failure), true);
        }, () -> {
            accountPassword.clear(); writing = false; updateControls();
        });
    }

    @FXML private void toggle() {
        AccountDto selected = accountTable.getSelectionModel().getSelectedItem();
        if (!isActive() || writing || loading || !listFresh || selected == null || selected.role() == Role.ADMIN) return;
        if (!selected.active()) { setActive(selected, true); return; }
        lockTarget = selected;
        confirmationText.setText("Khóa tài khoản “" + selected.username() + "”? Các phiên đăng nhập hiện có sẽ bị thu hồi. Người dùng phải đăng nhập lại sau khi được mở khóa.");
        confirmation.setVisible(true); confirmation.setManaged(true); confirmLock.requestFocus();
        updateControls();
    }

    @FXML private void confirmLock() {
        AccountDto selected = lockTarget;
        if (selected == null || !isActive() || writing || loading || !listFresh) return;
        cancelLock(); setActive(selected, false);
    }

    @FXML private void cancelLock() {
        lockTarget = null; confirmation.setVisible(false); confirmation.setManaged(false);
        updateControls();
    }

    private void setActive(AccountDto selected, boolean active) {
        beginWrite(active ? "Đang mở khóa tài khoản…" : "Đang khóa tài khoản…");
        fx.observe(navigator.trackAccountWrite(api.setAccountActive(selected.id(), active)), this::isActive, ignored -> {
            writing = false;
            show(accountNotice, "Đã " + (active ? "mở khóa" : "khóa") + " tài khoản “" + selected.username() + "”.", false);
            reload();
        }, failure -> {
            show(accountNotice, error(failure), true);
            if (failure instanceof ApiException business && business.errorCode() == ErrorCode.NOT_FOUND) {
                writing = false; reload();
            }
        }, () -> { writing = false; updateControls(); });
    }

    private void beginWrite(String message) {
        writing = true; cancelLock(); show(accountNotice, message, false); updateControls();
    }

    private void updateControls() {
        boolean busy = writing || loading;
        createAccount.setDisable(busy);
        refreshAccounts.setDisable(writing);
        accountUsername.setDisable(writing); accountFullName.setDisable(writing);
        accountEmail.setDisable(writing); accountPassword.setDisable(writing); accountRole.setDisable(writing);
        AccountDto selected = accountTable.getSelectionModel().getSelectedItem();
        selectedAccount.setText(selected == null ? "Chọn một tài khoản để xem thao tác."
            : "Đã chọn: " + selected.username() + (selected.role() == Role.ADMIN ? " • Tài khoản Admin được bảo vệ." : ""));
        toggleAccount.setText(selected != null && !selected.active() ? "Mở khóa tài khoản" : "Khóa tài khoản");
        toggleAccount.setDisable(busy || !listFresh || lockTarget != null || selected == null || selected.role() == Role.ADMIN);
        confirmLock.setDisable(busy);
        accountProgress.setVisible(busy); accountProgress.setManaged(busy);
    }

    private static void show(Label label, String message, boolean error) {
        label.setText(message); label.setVisible(!message.isEmpty()); label.setManaged(!message.isEmpty());
        label.getStyleClass().remove("error-notice");
        if (error) label.getStyleClass().add("error-notice");
    }

    private static String error(Throwable failure) {
        failure = FxAsync.unwrap(failure);
        if (failure instanceof ApiException business) return switch (business.errorCode()) {
            case CONFLICT -> "Tên đăng nhập đã tồn tại. Hãy chọn tên khác.";
            case VALIDATION_ERROR -> "Thông tin tài khoản không hợp lệ. Kiểm tra lại các trường.";
            case FORBIDDEN -> "Bạn không có quyền thực hiện thao tác này. Tài khoản Admin không thể bị khóa.";
            case UNAUTHENTICATED -> "Phiên làm việc đã kết thúc. Vui lòng đăng nhập lại.";
            case NOT_FOUND -> "Tài khoản không còn tồn tại. Đang tải lại danh sách.";
            case SERVER_BUSY -> "Máy chủ đang bận. Hãy thử lại sau.";
            case DATABASE_ERROR -> "Không thể xử lý dữ liệu. Hãy thử lại sau.";
            default -> "Không thể hoàn tất yêu cầu. Hãy tải lại danh sách để kiểm tra.";
        };
        if (failure instanceof TransportException transport && transport.kind() == TransportException.Kind.OVERLOADED)
            return "Có quá nhiều yêu cầu đang chờ. Yêu cầu này chưa được gửi.";
        return "Chưa xác nhận được kết quả. Đăng nhập lại và tải danh sách để kiểm tra trước khi thực hiện lại.";
    }

    @Override protected void onDispose() { ++loadVersion; if (accountPassword != null) accountPassword.clear(); lockTarget = null; }
}
