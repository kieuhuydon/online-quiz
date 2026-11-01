package quiz.client.ui.student;

import javafx.fxml.FXML;
import javafx.scene.control.Label;
import quiz.client.api.QuizApi;
import quiz.client.ui.*;
import quiz.common.dto.ResultDto;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public final class ResultController extends ScreenController {
    private static final DateTimeFormatter DATE_FORMAT =
        DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault());

    private final QuizApi api;
    private final SceneNavigator navigator;
    private final ResultDto result;

    @FXML private Label quizTitle, score, outcome, correct, submitted, submitReasonLabel;

    public ResultController(QuizApi api, SceneNavigator navigator, ResultDto result) {
        this.api = api;
        this.navigator = navigator;
        this.result = result;
    }

    @FXML
    private void initialize() {
        quizTitle.setText(result.quizTitle());
        score.setText(String.format(Locale.ROOT, "%.2f / 10", result.score()));
        if (outcome != null) {
            outcome.getStyleClass().removeAll("badge-pass", "badge-fail");
            if (result.passed()) {
                outcome.setText("🎉 ĐẠT YÊU CẦU");
                outcome.getStyleClass().add("badge-pass");
            } else {
                outcome.setText("⚠️ CHƯA ĐẠT YÊU CẦU");
                outcome.getStyleClass().add("badge-fail");
            }
        }
        if (correct != null) {
            correct.setText(result.correctCount() + " / " + result.totalQuestions() + " câu");
        }
        if (submitted != null && result.submittedAt() != null) {
            submitted.setText(DATE_FORMAT.format(result.submittedAt()));
        }
        if (submitReasonLabel != null && result.submitReason() != null) {
            submitReasonLabel.setText(switch (result.submitReason()) {
                case MANUAL -> "Nộp thủ công";
                case TIMEOUT -> "Tự động nộp — Hết giờ";
                case ANTICHEAT -> "Tự động nộp — Vi phạm quy chế";
            });
        }
    }

    @FXML
    private void review() {
        navigator.showReview(result);
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
