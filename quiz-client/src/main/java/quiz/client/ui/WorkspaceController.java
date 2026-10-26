package quiz.client.ui;

import javafx.fxml.FXML;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import quiz.client.session.ClientSession;

/** Shared authenticated layout; no network operations or role selection in the view. */
public final class WorkspaceController extends ScreenController {
    private final SceneNavigator navigator;
    @FXML private Label roleLabel;
    @FXML private Label fullName;
    @FXML private Label username;
    @FXML private Label pageTitle;
    @FXML private Label activeSection;
    @FXML private Label notice;
    @FXML private StackPane content;

    public WorkspaceController(SceneNavigator navigator) {
        this.navigator = navigator;
    }

    void show(ClientSession.Snapshot session, Screen route, Parent body, String message) {
        roleLabel.setText(Screen.roleLabel(session.role()));
        fullName.setText(session.fullName());
        username.setText("@" + session.username());
        pageTitle.setText(route.title());
        activeSection.setText(route.role() == quiz.common.enums.Role.ADMIN ? "Tài khoản" : "Tổng quan");
        notice.setText(message);
        notice.setVisible(!message.isBlank());
        notice.setManaged(!message.isBlank());
        content.getChildren().setAll(body);
    }

    @FXML
    private void logout() {
        if (isActive()) {
            navigator.logout();
        }
    }
}
