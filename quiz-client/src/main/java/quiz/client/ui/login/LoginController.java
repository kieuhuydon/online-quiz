package quiz.client.ui.login;

import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.control.ProgressIndicator;
import quiz.client.api.ApiException;
import quiz.client.api.ClientValidation;
import quiz.client.api.QuizApi;
import quiz.client.network.TransportException;
import quiz.common.dto.LoginRequestDto;
import quiz.common.protocol.ErrorCode;
import quiz.client.ui.SceneNavigator;
import quiz.client.ui.ScreenController;
import quiz.client.util.FxAsync;

import java.util.concurrent.CompletableFuture;

/** Form calls the asynchronous API only; credentials never enter navigation or logs. */
public final class LoginController extends ScreenController {
    private final QuizApi api;
    private final SceneNavigator navigator;
    private final FxAsync fx;
    @FXML private Label notice;
    @FXML private PasswordField password;
    @FXML private TextField username;
    @FXML private Button loginButton;
    @FXML private ProgressIndicator progress;
    @FXML private Label connectionStatus;
    private CompletableFuture<?> pending;
    private boolean busy;

    public LoginController(QuizApi api, SceneNavigator navigator, FxAsync fx) {
        this.api = api;
        this.navigator = navigator;
        this.fx = fx;
    }

    public void setNotice(String message) {
        notice.getStyleClass().remove("error-notice");
        notice.setText(message);
        notice.setVisible(!message.isBlank());
        notice.setManaged(!message.isBlank());
    }

    @FXML
    private void initialize() {
        fx.run(() -> {
            if (isMounted()) {
                username.requestFocus();
            }
        });
    }

    @FXML
    private void submit() {
        if (!isMounted() || busy) {
            return;
        }
        LoginRequestDto input;
        try {
            input = ClientValidation.login(username.getText(), password.getText());
        } catch (ApiException invalid) {
            showError(LoginMessages.message(invalid));
            if (username.getText().isBlank()) {
                username.requestFocus();
            } else {
                password.requestFocus();
            }
            return;
        }
        username.setText(input.username());
        setNotice("");
        setBusy(true, "Đang kết nối với máy chủ…");
        pending = api.connect();
        fx.observe(pending, this::isMounted,
            ignored -> authenticate(input), this::failed,
            () -> { /* Successful connect starts authentication; keep the form busy. */ });
    }

    private void authenticate(LoginRequestDto input) {
        setBusy(true, "Đang xác thực tài khoản…");
        var authentication = api.login(input.username(), input.password());
        pending = authentication;
        fx.observe(authentication, this::isMounted, login -> {
            password.clear();
            if (api.session().current().isEmpty()) {
                failed(new TransportException(TransportException.Kind.CONNECTION_LOST));
            } else {
                navigator.showHome();
            }
        }, this::failed, () -> {
            if (isMounted()) {
                setBusy(false, "Sẵn sàng đăng nhập");
            }
        });
    }

    private void failed(Throwable failure) {
        password.clear();
        if (failure instanceof ApiException apiError && apiError.errorCode() == ErrorCode.CONFLICT) {
            api.resetLoginConnection();
        }
        setBusy(false, "Chưa đăng nhập");
        showError(LoginMessages.message(failure));
        password.requestFocus();
    }

    private void showError(String message) {
        setNotice(message);
        notice.getStyleClass().add("error-notice");
    }

    private void setBusy(boolean busy, String status) {
        this.busy = busy;
        username.setDisable(busy);
        password.setDisable(busy);
        loginButton.setDisable(busy);
        progress.setVisible(busy);
        progress.setManaged(busy);
        connectionStatus.setText(status);
    }

    @FXML
    private void exit() {
        navigator.exit();
    }

    @Override
    protected void onDispose() {
        if (password != null) {
            password.clear();
        }
        if (pending != null && !pending.isDone()) {
            pending.cancel(false);
        }
        pending = null;
    }
}
