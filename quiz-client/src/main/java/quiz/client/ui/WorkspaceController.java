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
    @FXML private javafx.scene.control.Button adminAccounts, adminClasses;
    @FXML private Label notice;
    @FXML private StackPane content;
    @FXML private javafx.scene.layout.VBox adminNavigation;
    @FXML private javafx.scene.layout.VBox teacherNavigation;
    @FXML private javafx.scene.control.Button teacherDashboard, teacherQuestions;

    public WorkspaceController(SceneNavigator navigator) {
        this.navigator = navigator;
    }

    void show(ClientSession.Snapshot session, Screen route, Parent body, String message) {
        roleLabel.setText(Screen.roleLabel(session.role()));
        fullName.setText(session.fullName());
        username.setText("@" + session.username());
        pageTitle.setText(route.title());
        var current = javafx.css.PseudoClass.getPseudoClass("current");
        adminAccounts.pseudoClassStateChanged(current, route == Screen.ADMIN_ACCOUNTS || route == Screen.ADMIN_CREATE_ACCOUNT);
        adminClasses.pseudoClassStateChanged(current, route == Screen.ADMIN_CLASSES);
        adminNavigation.setVisible(session.role() == quiz.common.enums.Role.ADMIN);
        adminNavigation.setManaged(session.role() == quiz.common.enums.Role.ADMIN);
        boolean isTeacher = session.role() == quiz.common.enums.Role.TEACHER;
        teacherNavigation.setVisible(isTeacher);
        teacherNavigation.setManaged(isTeacher);
        teacherDashboard.pseudoClassStateChanged(current, route == Screen.TEACHER_DASHBOARD);
        teacherQuestions.pseudoClassStateChanged(current, route == Screen.TEACHER_QUESTIONS);
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

    @FXML private void accounts() { if (isActive()) navigator.show(Screen.ADMIN_ACCOUNTS); }
    @FXML private void classes() { if (isActive()) navigator.show(Screen.ADMIN_CLASSES); }
    @FXML private void teacherDashboard() { if (isActive()) navigator.show(Screen.TEACHER_DASHBOARD); }
    @FXML private void teacherQuestions() { if (isActive()) navigator.show(Screen.TEACHER_QUESTIONS); }
}
