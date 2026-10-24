package quiz.client.ui;

import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quiz.client.api.QuizApi;
import quiz.client.network.TransportException;
import quiz.client.session.ClientSession;
import quiz.client.ui.admin.account.AccountController;
import quiz.client.ui.login.LoginController;
import quiz.client.ui.teacher.question.QuestionController;
import quiz.client.util.FxAsync;
import quiz.common.enums.Role;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** Owns one Stage, shared dependencies and exactly one session-loss subscription. FX thread only. */
public final class SceneNavigator implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(SceneNavigator.class);
    private static final String STYLESHEET = "/quiz/client/ui/app.css";
    private final Stage stage;
    private final QuizApi api;
    private final FxAsync fx;
    private final Function<String, URL> resources;
    private final AutoCloseable sessionSubscription;
    private List<ScreenController> controllers = List.of();
    private Screen screen;
    private long viewVersion;
    private boolean closed;
    private boolean loggingOut;
    private quiz.common.dto.AttemptStateDto activeAttempt;
    private quiz.common.dto.ResultDto activeResult;
    private final Set<CompletableFuture<?>> accountWrites = ConcurrentHashMap.newKeySet();

    public SceneNavigator(Stage stage, QuizApi api, FxAsync fx) {
        this(stage, api, fx, SceneNavigator.class::getResource);
    }

    SceneNavigator(Stage stage, QuizApi api, FxAsync fx, Function<String, URL> resources) {
        requireFx();
        this.stage = Objects.requireNonNull(stage);
        this.api = Objects.requireNonNull(api);
        this.fx = Objects.requireNonNull(fx);
        this.resources = Objects.requireNonNull(resources);
        sessionSubscription = api.addSessionLostListener(event -> fx.run(() -> {
            if (!closed && api.isCurrent(event)) {
                loggingOut = false;
                boolean uncertain = !accountWrites.isEmpty();
                accountWrites.clear();
                showLogin(uncertain
                    ? "Chưa xác nhận được kết quả thao tác tài khoản. Đăng nhập lại và tải danh sách để kiểm tra trước khi thực hiện lại."
                    : "Phiên làm việc đã kết thúc. Vui lòng đăng nhập lại.");
            }
        }));
        stage.setMinWidth(880);
        stage.setMinHeight(600);
        stage.setOnCloseRequest(event -> close());
    }

    public void showHome() {
        show(api.session().current().map(session -> Screen.home(session.role())).orElse(Screen.LOGIN));
    }

    public void show(Screen requested) {
        navigate(Objects.requireNonNull(requested), "");
    }

    public void showExam(quiz.common.dto.AttemptStateDto attempt) { activeAttempt = Objects.requireNonNull(attempt); navigate(Screen.STUDENT_EXAM, ""); }
    public void showResult(quiz.common.dto.ResultDto result) { activeResult = Objects.requireNonNull(result); navigate(Screen.STUDENT_RESULT, ""); }

    public void showLogin(String notice) {
        navigate(Screen.LOGIN, notice == null ? "" : notice);
    }

    public Optional<Screen> currentScreen() {
        return Optional.ofNullable(screen);
    }

    /** Keep an unacknowledged write visible even when session loss disposes its screen first. */
    public <T> CompletableFuture<T> trackAccountWrite(CompletableFuture<T> future) {
        requireFx();
        accountWrites.add(future);
        future.whenComplete((value, failure) -> {
            Throwable cause = failure == null ? null : FxAsync.unwrap(failure);
            if (!(cause instanceof TransportException transport) || transport.kind() == TransportException.Kind.OVERLOADED) {
                accountWrites.remove(future);
            }
        });
        return future;
    }

    private void navigate(Screen requested, String notice) {
        requireFx();
        if (closed || loggingOut) {
            return;
        }
        ClientSession.Snapshot session = api.session().current().orElse(null);
        Screen target = session == null ? Screen.LOGIN : Screen.home(session.role());
        boolean canAccess = session != null && (requested.role() == session.role()
            || (session.role() == Role.ADMIN && requested.role() == Role.TEACHER));
        if (canAccess) {
            target = requested;
        }
        if (requested != Screen.LOGIN && requested != target) {
            notice = session == null ? "Vui lòng đăng nhập để tiếp tục." : "Bạn không có quyền truy cập màn hình này.";
        }
        List<ScreenController> created = new ArrayList<>();
        try {
            Loaded content = load(target.resource(), created);
            Parent root = content.root();
            if (target == Screen.LOGIN) {
                ((LoginController) content.controller()).setNotice(notice);
            } else {
                Loaded shell = load("/quiz/client/ui/shell.fxml", created);
                ((WorkspaceController) shell.controller()).show(session, target, content.root(), notice);
                root = shell.root();
            }
            String css = resource(STYLESHEET).toExternalForm();
            replace(root, target, created, session, css);
        } catch (IOException | RuntimeException failed) {
            created.forEach(ScreenController::dispose);
            failView(failed);
        }
    }

    private void replace(Parent root, Screen target, List<ScreenController> next, ClientSession.Snapshot session, String css) {
        disposeView();
        screen = target;
        controllers = List.copyOf(next);
        long version = viewVersion;
        if (stage.getScene() == null) {
            var bounds = javafx.stage.Screen.getPrimary().getVisualBounds();
            stage.setScene(new Scene(root, Math.min(1120, bounds.getWidth() - 48), Math.min(740, bounds.getHeight() - 64)));
        } else {
            stage.getScene().setRoot(root);
        }
        stage.getScene().getStylesheets().setAll(css);
        stage.setTitle("Online Quiz — " + target.title());
        for (ScreenController controller : controllers) {
            controller.mount(() -> !closed && viewVersion == version,
                () -> Objects.equals(session, api.session().current().orElse(null)));
        }
    }

    private Loaded load(String path, List<ScreenController> created) throws IOException {
        FXMLLoader loader = new FXMLLoader(resource(path));
        loader.setControllerFactory(type -> {
            ScreenController controller = createController(type);
            created.add(controller);
            return controller;
        });
        Parent root = loader.load();
        if (!(loader.getController() instanceof ScreenController controller)) {
            throw new IOException("Missing screen controller");
        }
        return new Loaded(root, controller);
    }

    private ScreenController createController(Class<?> type) {
        if (type == LoginController.class) {
            return new LoginController(api, this, fx);
        }
        if (type == AccountController.class) {
            return new AccountController(api, this, fx);
        }
        if (type == quiz.client.ui.teacher.TeacherController.class) {
            return new quiz.client.ui.teacher.TeacherController(api, this, fx);
        }
        if (type == quiz.client.ui.student.StudentController.class) {
            return new quiz.client.ui.student.StudentController(api, this, fx);
        }
        if (type == quiz.client.ui.student.ExamController.class) {
            return new quiz.client.ui.student.ExamController(api, this, fx, Objects.requireNonNull(activeAttempt));
        }
        if (type == quiz.client.ui.student.ResultController.class) {
            return new quiz.client.ui.student.ResultController(api, this, Objects.requireNonNull(activeResult));
        }
        if (type == quiz.client.ui.student.HistoryController.class) {
            return new quiz.client.ui.student.HistoryController(api, this, fx);
        }
        if (type == DashboardController.class) {
            return new DashboardController(api, this, fx);
        }
        if (type == QuestionController.class) {
            return new QuestionController(api, this, fx);
        }
        if (type == WorkspaceController.class) {
            return new WorkspaceController(this);
        }
        throw new IllegalArgumentException("Unsupported screen controller");
    }

    private URL resource(String path) throws IOException {
        URL result = resources.apply(path);
        if (result == null) {
            throw new IOException("Missing client UI resource: " + path);
        }
        return result;
    }

    /** Shared by all authenticated screens. Logout errors still return to an unauthenticated screen. */
    public void logout() {
        requireFx();
        if (closed || loggingOut) {
            return;
        }
        if (api.session().current().isEmpty()) {
            showLogin("Phiên làm việc đã kết thúc.");
            return;
        }
        loggingOut = true;
        long version = viewVersion;
        stage.getScene().getRoot().setDisable(true);
        fx.observe(api.logout(), () -> !closed && version == viewVersion,
            ignored -> finishLogout("Bạn đã đăng xuất."),
            failure -> finishLogout("Đã kết thúc phiên trên máy này. Vui lòng kết nối lại khi đăng nhập."),
            () -> loggingOut = false);
    }

    private void finishLogout(String notice) {
        loggingOut = false;
        if (!accountWrites.isEmpty()) {
            notice += " Chưa xác nhận kết quả thao tác tài khoản. Đăng nhập lại và tải danh sách để kiểm tra.";
        }
        accountWrites.clear();
        showLogin(notice);
    }

    public void exit() {
        close();
        stage.close();
    }

    private void failView(Exception failure) {
        log.error("Cannot load client screen: {}", failure.getClass().getSimpleName());
        close();
        StartupErrorView.show(stage, "Không thể mở giao diện",
            "Thiếu hoặc lỗi tài nguyên giao diện. Hãy kiểm tra bản build rồi mở lại ứng dụng.");
    }

    private void disposeView() {
        viewVersion++;
        controllers.forEach(ScreenController::dispose);
        controllers = List.of();
        screen = null;
    }

    @Override
    public void close() {
        requireFx();
        if (closed) {
            return;
        }
        closed = true;
        accountWrites.clear();
        disposeView();
        try {
            sessionSubscription.close();
        } catch (Exception failure) {
            log.warn("Cannot remove navigation subscription: {}", failure.getClass().getSimpleName());
        }
        api.close();
    }

    private static void requireFx() {
        if (!Platform.isFxApplicationThread()) {
            throw new IllegalStateException("Navigation requires the JavaFX thread");
        }
    }

    private record Loaded(Parent root, ScreenController controller) { }
}
