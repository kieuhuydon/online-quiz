package quiz.client.ui.admin.account;

import javafx.css.PseudoClass;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.util.StringConverter;
import quiz.client.api.ApiException;
import quiz.client.api.ClientValidation;
import quiz.client.api.ClientValidation.AccountField;
import quiz.client.api.QuizApi;
import quiz.client.ui.SceneNavigator;
import quiz.client.ui.Screen;
import quiz.client.ui.ScreenController;
import quiz.client.util.FxAsync;
import quiz.common.dto.CreateAccountDto;
import quiz.common.enums.Role;

import java.util.LinkedHashMap;
import java.util.Map;

/** Owns the creation form only. Writes remain tracked by the shared navigator. */
public final class CreateAccountController extends ScreenController {
    private static final PseudoClass INVALID = PseudoClass.getPseudoClass("invalid");
    private final QuizApi api;
    private final SceneNavigator navigator;
    private final FxAsync fx;
    @FXML private TextField accountUsername, accountFullName, accountEmail;
    @FXML private PasswordField accountPassword;
    @FXML private ComboBox<Role> accountRole;
    @FXML private Label usernameError, fullNameError, emailError, roleError, passwordError, formError;
    @FXML private Button createAccount, backToAccounts;
    @FXML private ProgressIndicator createProgress;
    private final Map<AccountField, Control> fields = new LinkedHashMap<>();
    private final Map<AccountField, Label> errors = new LinkedHashMap<>();
    private boolean writing;

    public CreateAccountController(QuizApi api, SceneNavigator navigator, FxAsync fx) {
        this.api = api;
        this.navigator = navigator;
        this.fx = fx;
    }

    @FXML private void initialize() {
        fields.put(AccountField.USERNAME, accountUsername);
        fields.put(AccountField.FULL_NAME, accountFullName);
        fields.put(AccountField.EMAIL, accountEmail);
        fields.put(AccountField.ROLE, accountRole);
        fields.put(AccountField.PASSWORD, accountPassword);
        errors.put(AccountField.USERNAME, usernameError);
        errors.put(AccountField.FULL_NAME, fullNameError);
        errors.put(AccountField.EMAIL, emailError);
        errors.put(AccountField.ROLE, roleError);
        errors.put(AccountField.PASSWORD, passwordError);
        accountRole.getItems().setAll(Role.TEACHER, Role.STUDENT);
        accountRole.setConverter(new StringConverter<>() {
            @Override public String toString(Role role) { return role == null ? "" : Screen.roleLabel(role); }
            @Override public Role fromString(String text) { throw new UnsupportedOperationException(); }
        });
        accountRole.setValue(Role.STUDENT);
        fields.forEach((field, control) -> {
            if (control instanceof TextInputControl text) {
                text.textProperty().addListener((observable, old, value) -> clearError(field));
            }
        });
        accountRole.valueProperty().addListener((observable, old, value) -> clearError(AccountField.ROLE));
    }

    @FXML private void create() {
        if (!isActive() || writing) return;
        clearErrors();
        CreateAccountDto input = new CreateAccountDto(accountUsername.getText(), accountPassword.getText(),
            accountFullName.getText(), accountEmail.getText(), accountRole.getValue());
        Map<AccountField, String> problems = ClientValidation.accountErrors(input);
        if (!problems.isEmpty()) {
            problems.forEach(this::showError);
            fields.get(problems.keySet().iterator().next()).requestFocus();
            message(formError, "Vui lòng sửa các trường được đánh dấu.");
            return;
        }
        input = ClientValidation.account(input);
        accountUsername.setText(input.username());
        accountFullName.setText(input.fullName());
        accountEmail.setText(input.email() == null ? "" : input.email());
        setWriting(true);
        fx.observe(navigator.trackAccountWrite(api.createAccount(input)), this::isActive,
            created -> {
                accountPassword.clear();
                navigator.showCreatedAccount(created);
            }, this::failed, () -> {
                accountPassword.clear();
                setWriting(false);
            });
    }

    private void failed(Throwable failure) {
        String text = AccountMessages.message(failure);
        message(formError, text);
        if (FxAsync.unwrap(failure) instanceof ApiException business) {
            AccountField field = switch (business.errorCode()) {
                case EMAIL_ALREADY_EXISTS -> AccountField.EMAIL;
                case USERNAME_ALREADY_EXISTS -> AccountField.USERNAME;
                default -> null;
            };
            if (field != null) {
                showError(field, text);
                // Finally re-enables controls before the next FX pulse focuses the invalid field.
                fx.run(() -> { if (isActive()) fields.get(field).requestFocus(); });
            }
        }
    }

    @FXML private void back() {
        if (isActive() && !writing) navigator.show(Screen.ADMIN_ACCOUNTS);
    }

    private void setWriting(boolean busy) {
        writing = busy;
        fields.values().forEach(control -> control.setDisable(busy));
        createAccount.setDisable(busy);
        backToAccounts.setDisable(busy);
        createAccount.setText(busy ? "Đang tạo tài khoản…" : "Tạo tài khoản");
        createProgress.setVisible(busy);
        createProgress.setManaged(busy);
    }

    private void clearErrors() {
        fields.keySet().forEach(this::clearError);
        message(formError, "");
    }

    private void clearError(AccountField field) {
        fields.get(field).pseudoClassStateChanged(INVALID, false);
        message(errors.get(field), "");
    }

    private void showError(AccountField field, String text) {
        fields.get(field).pseudoClassStateChanged(INVALID, true);
        message(errors.get(field), text);
    }

    private static void message(Label label, String text) {
        label.setText(text);
        label.setVisible(!text.isBlank());
        label.setManaged(!text.isBlank());
    }

    @Override protected void onDispose() { accountPassword.clear(); }
}
