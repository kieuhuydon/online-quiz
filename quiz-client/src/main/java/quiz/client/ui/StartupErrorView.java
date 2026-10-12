package quiz.client.ui;

import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/** A resource-independent fallback: failed FXML/CSS must not leave an empty or privileged screen. */
public final class StartupErrorView {
    private StartupErrorView() { }

    public static void show(Stage stage, String title, String message) {
        Label heading = new Label(title);
        heading.setStyle("-fx-font-size: 22px; -fx-font-weight: bold;");
        Label detail = new Label(message);
        detail.setId("startupError");
        detail.setWrapText(true);
        Button close = new Button("Đóng ứng dụng");
        close.setOnAction(event -> stage.close());
        VBox root = new VBox(20, heading, detail, close);
        root.setPadding(new Insets(36));
        root.setStyle("-fx-background-color: #f5f7fb; -fx-font-family: 'Segoe UI'; -fx-font-size: 14px;");
        stage.setTitle("Online Quiz — Không thể khởi động");
        if (stage.getScene() == null) stage.setScene(new Scene(root, 680, 300));
        else stage.getScene().setRoot(root);
        stage.show();
    }
}
