package quiz.client.ui;

import javafx.fxml.FXML;
import javafx.scene.control.Button;
import quiz.client.api.QuizApi;
import quiz.client.ui.SceneNavigator;
import quiz.client.util.FxAsync;

/** Teacher/student landing scaffold. Question bank entry point for teachers. */
public final class DashboardController extends ScreenController {
    private final QuizApi api;
    private final SceneNavigator navigator;
    private final FxAsync fx;
    @FXML private Button openQuestions;

    public DashboardController(QuizApi api, SceneNavigator navigator, FxAsync fx) {
        this.api = api;
        this.navigator = navigator;
        this.fx = fx;
    }

    @FXML
    private void initialize() {
        if (openQuestions != null) {
            openQuestions.setOnAction(ignored -> {
                if (isActive()) {
                    navigator.show(Screen.TEACHER_QUESTIONS);
                }
            });
        }
    }
}
