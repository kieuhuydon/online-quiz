package quiz.client.ui.student;

import javafx.fxml.FXML;
import javafx.scene.control.Label;
import quiz.client.api.QuizApi;
import quiz.client.ui.*;
import quiz.common.dto.ResultDto;

public final class ResultController extends ScreenController {
    private final QuizApi api;
    private final SceneNavigator navigator;
    private final ResultDto result;

    @FXML private Label quizTitle, score;

    public ResultController(QuizApi api, SceneNavigator navigator, ResultDto result) {
        this.api = api;
        this.navigator = navigator;
        this.result = result;
    }

    @FXML
    private void initialize() {
        quizTitle.setText(result.quizTitle());
        score.setText(String.format("%.2f / 10", result.score()));
    }

    @FXML
    private void history() {
        navigator.show(Screen.STUDENT_HISTORY);
    }

    @FXML
    private void home() {
        navigator.show(Screen.STUDENT_DASHBOARD);
    }
}
