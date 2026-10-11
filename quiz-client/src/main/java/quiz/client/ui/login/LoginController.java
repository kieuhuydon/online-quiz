package quiz.client.ui.login;

import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import quiz.client.api.QuizApi;
import quiz.client.ui.SceneNavigator;
import quiz.client.ui.ScreenController;
import quiz.client.util.FxAsync;

/** Login scaffold only. Form submission/validation is implemented in task 5.3. */
public final class LoginController extends ScreenController {
    private final QuizApi api;
    private final SceneNavigator navigator;
    private final FxAsync fx;
    @FXML private Label notice;
    @FXML private PasswordField password;

    public LoginController(QuizApi api, SceneNavigator navigator, FxAsync fx) {
        this.api = api;
        this.navigator = navigator;
        this.fx = fx;
    }

    public void setNotice(String message) {
        notice.setText(message);
        notice.setVisible(!message.isBlank());
        notice.setManaged(!message.isBlank());
    }

    @Override protected void onDispose() { if (password != null) password.clear(); }
}
