package quiz.client.ui.student;

import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import quiz.client.ui.SceneNavigator;
import quiz.client.ui.Screen;
import quiz.client.ui.ScreenController;
import quiz.common.dto.QuestionReviewDto;
import quiz.common.dto.ResultDto;

import java.util.List;

/** Displays the answer-by-answer review after an attempt has been submitted. */
public final class ReviewController extends ScreenController {
    private final SceneNavigator navigator;
    private final ResultDto result;

    @FXML private Label quizTitle;
    @FXML private Label summary;
    @FXML private VBox questionCards;

    public ReviewController(SceneNavigator navigator, ResultDto result) {
        this.navigator = navigator;
        this.result = result;
    }

    @FXML
    private void initialize() {
        quizTitle.setText(result.quizTitle());
        summary.setText(String.format("%.2f / 10 điểm   •   %d / %d câu đúng",
            result.score(), result.correctCount(), result.totalQuestions()));
        List<QuestionReviewDto> review = result.review() == null ? List.of() : result.review();
        if (review.isEmpty()) {
            questionCards.getChildren().add(new Label("Bài thi này chưa có dữ liệu review."));
            return;
        }
        for (int i = 0; i < review.size(); i++) {
            questionCards.getChildren().add(createCard(review.get(i), i + 1));
        }
    }

    private VBox createCard(QuestionReviewDto item, int number) {
        String accent = item.correct() ? "#16a34a" : "#dc2626";
        String tint = item.correct() ? "#f0fdf4" : "#fef2f2";
        VBox card = new VBox(12);
        card.setFillWidth(true);
        card.setStyle("-fx-padding: 20; -fx-background-color: white; -fx-background-radius: 12; "
            + "-fx-border-color: #e2e8f0; -fx-border-radius: 12; "
            + "-fx-effect: dropshadow(three-pass-box, rgba(15,23,42,0.06), 10, 0, 0, 3);");

        HBox header = new HBox(12);
        header.setAlignment(Pos.CENTER_LEFT);
        Label questionNumber = new Label(String.format("CÂU %02d", number));
        questionNumber.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: #64748b;");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label verdict = new Label(item.correct() ? "✓  Chính xác" : "✕  Chưa chính xác");
        verdict.setStyle("-fx-padding: 6 10; -fx-background-radius: 20; -fx-background-color: " + tint
            + "; -fx-text-fill: " + accent + "; -fx-font-weight: bold;");
        header.getChildren().addAll(questionNumber, spacer, verdict);

        Label content = text(item.content(), "-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #172338;");
        VBox answerBox = new VBox(7);
        answerBox.setStyle("-fx-padding: 12; -fx-background-color: " + tint + "; -fx-background-radius: 8;");
        answerBox.getChildren().add(text("Câu trả lời của bạn: " + display(item.submittedAnswer()),
            "-fx-text-fill: " + accent + "; -fx-font-weight: bold;"));
        answerBox.getChildren().add(text("Đáp án đúng: " + display(item.correctAnswer()),
            "-fx-text-fill: #15803d; -fx-font-weight: bold;"));

        VBox explanationBox = new VBox(5);
        explanationBox.setStyle("-fx-padding: 12; -fx-background-color: #f8fafc; -fx-background-radius: 8;");
        Label explanationTitle = text("GIẢI THÍCH", "-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: #356ea6;");
        Label explanation = text(display(item.explanation()), "-fx-text-fill: #475569; -fx-line-spacing: 3px;");
        explanationBox.getChildren().addAll(explanationTitle, explanation);

        card.getChildren().addAll(header, content, answerBox, explanationBox);
        return card;
    }

    private static Label text(String value, String style) {
        Label label = new Label(value);
        label.setWrapText(true);
        label.setMaxWidth(Double.MAX_VALUE);
        label.setStyle(style);
        return label;
    }

    private static String display(String value) {
        return value == null || value.isBlank() ? "(Chưa trả lời)" : value;
    }

    @FXML
    private void history() {
        navigator.show(Screen.STUDENT_HISTORY);
    }
}
