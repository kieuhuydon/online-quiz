package quiz.client.ui;

import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.event.ActionEvent;
import javafx.stage.Stage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import quiz.client.api.QuizApi;
import quiz.client.network.ServerConnection;
import quiz.client.support.SocketFixture;
import quiz.client.util.FxAsync;
import quiz.common.dto.LoginResultDto;
import quiz.common.enums.Role;
import quiz.common.protocol.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Real JavaFX/FXML + loopback client API. Requires desktop; no DB and no production fake sessions. */
@Timeout(20)
class ClientUiIT {
    private SocketFixture server;
    private ServerConnection connection;
    private QuizApi api;
    private Stage stage;
    private SceneNavigator navigator;

    @BeforeAll static void startToolkit() throws Exception {
        var ready = new CountDownLatch(1);
        Platform.startup(() -> { Platform.setImplicitExit(false); ready.countDown(); });
        assertTrue(ready.await(10, TimeUnit.SECONDS), "JavaFX toolkit did not start");
    }

    @AfterAll static void stopToolkit() { Platform.exit(); }

    @BeforeEach void setup() throws Exception {
        server = new SocketFixture();
        connection = new ServerConnection(server.config());
        api = new QuizApi(connection);
        fx(() -> { stage = new Stage(); navigator = new SceneNavigator(stage, api, new FxAsync()); return null; });
    }

    @AfterEach void cleanup() throws Exception {
        try {
            fx(() -> { navigator.close(); stage.close(); return null; });
            api.closeAsync().get(4, TimeUnit.SECONDS);
        } finally { server.close(); }
    }

    @Test void loginLoadsWithoutConnectingAndIsReadyForSubmission() throws Exception {
        fx(() -> {
            navigator.showLogin(""); stage.show(); stage.getScene().getRoot().applyCss(); stage.getScene().getRoot().layout();
            assertEquals(Screen.LOGIN, navigator.currentScreen().orElseThrow());
            assertEquals(ServerConnection.State.DISCONNECTED, connection.state());
            assertFalse(button("loginButton").isDisabled());
            assertFalse(label("loginNotice").isVisible());
            saveSnapshot("login");
            return null;
        });
    }

    @ParameterizedTest @EnumSource(Role.class)
    void rolesLoadCorrectHomeAndLogoutOnSameStage(Role role) throws Exception {
        var peer = login(role);
        fx(() -> {
            navigator.showHome(); stage.show();
            assertEquals(Screen.home(role), navigator.currentScreen().orElseThrow());
            assertEquals(Screen.roleLabel(role), label("roleLabel").getText());
            assertEquals("Nguyễn Việt — phiên kiểm thử", label("sessionFullName").getText());
            saveSnapshot(role.name().toLowerCase());
            stage.setWidth(900); stage.setHeight(620);
            return null;
        });
        awaitCompactSize();
        fx(() -> {
            saveSnapshot(role.name().toLowerCase() + "-compact");
            button("logoutButton").fire();
            assertTrue(stage.getScene().getRoot().isDisabled());
            navigator.logout(); // Duplicate click does not send another LOGOUT.
            return null;
        });
        var logout = peer.read(); assertEquals(MessageType.LOGOUT, logout.type()); peer.success(logout, null);
        awaitScreen(Screen.LOGIN);
        fx(() -> { assertEquals("Bạn đã đăng xuất.", label("loginNotice").getText()); return null; });
        assertEquals(ServerConnection.State.CONNECTED, connection.state());
    }

    @Test void missingSessionAndWrongRoleNeverRenderAdminScreen() throws Exception {
        fx(() -> {
            navigator.show(Screen.ADMIN_ACCOUNTS);
            assertEquals(Screen.LOGIN, navigator.currentScreen().orElseThrow());
            assertTrue(label("loginNotice").isVisible()); return null;
        });
        login(Role.STUDENT);
        fx(() -> {
            navigator.show(Screen.ADMIN_ACCOUNTS);
            assertEquals(Screen.STUDENT_DASHBOARD, navigator.currentScreen().orElseThrow());
            stage.show(); stage.getScene().getRoot().applyCss();
            assertEquals("Bạn không có quyền truy cập màn hình này.", label("workspaceNotice").getText());
            assertEquals("Không gian học tập", label("pageTitle").getText()); return null;
        });
    }

    @Test void repeatedNavigationAndTwoExpiredRequestsOnlyReplaceSceneOnce() throws Exception {
        var peer = login(Role.ADMIN);
        fx(() -> { for (int i = 0; i < 8; i++) navigator.showHome(); stage.show(); return null; });
        var replacements = new AtomicInteger();
        fx(() -> { stage.getScene().rootProperty().addListener((observable, old, fresh) -> replacements.incrementAndGet()); return null; });
        var first = api.listAccounts(); var second = api.listAccounts();
        Request request = peer.read(); peer.read();
        peer.send(Response.error(request.requestId(), ErrorCode.UNAUTHENTICATED, "fixture expired"));
        assertThrows(ExecutionException.class, () -> first.get(3, TimeUnit.SECONDS));
        assertThrows(ExecutionException.class, () -> second.get(3, TimeUnit.SECONDS));
        awaitScreen(Screen.LOGIN);
        fx(() -> { assertEquals(1, replacements.get()); assertTrue(label("loginNotice").isVisible()); return null; });
    }

    @Test void oldControllerCannotApplyCallbacksAfterNavigation() throws Exception {
        var source = new CompletableFuture<String>();
        var calls = new AtomicInteger();
        CompletableFuture<Void> observation = fx(() -> {
            navigator.showLogin("");
            Parent oldRoot = stage.getScene().getRoot();
            oldRoot.applyCss(); oldRoot.layout();
            PasswordField field = (PasswordField) oldRoot.lookup("#passwordInput"); field.setText("fixture-password");
            ScreenController controller = new ScreenController() { };
            controller.mount(() -> stage.getScene().getRoot() == oldRoot);
            var pending = new FxAsync().observe(source, controller::isActive,
                value -> calls.incrementAndGet(), error -> calls.incrementAndGet(), calls::incrementAndGet);
            navigator.showLogin("Màn hình mới");
            assertEquals("", field.getText(), "Disposed LoginController clears password");
            return pending;
        });
        source.complete("late"); observation.get(3, TimeUnit.SECONDS);
        assertEquals(0, calls.get());
        fx(() -> { assertEquals("Màn hình mới", label("loginNotice").getText()); return null; });
    }

    @Test void logoutErrorReturnsLoginAndReloginUsesFreshConnection() throws Exception {
        var peer = login(Role.TEACHER);
        fx(() -> { navigator.showHome(); navigator.logout(); return null; });
        var request = peer.read(); peer.send(Response.error(request.requestId(), ErrorCode.SERVER_BUSY, "busy"));
        awaitScreen(Screen.LOGIN);
        assertTrue(api.session().current().isEmpty()); assertEquals(ServerConnection.State.DISCONNECTED, connection.state());
        login(Role.STUDENT);
        fx(() -> { navigator.showHome(); assertEquals(Screen.STUDENT_DASHBOARD, navigator.currentScreen().orElseThrow()); return null; });
    }

    @ParameterizedTest @ValueSource(strings = {"missing-fxml", "invalid-fxml", "missing-css"})
    void resourceFailuresShowFallbackAndCloseDependencies(String problem) throws Exception {
        fx(() -> {
            navigator.close();
            api = new QuizApi(new ServerConnection(server.config()));
            navigator = new SceneNavigator(stage, api, new FxAsync(), path -> {
                if (problem.equals("missing-css") && path.endsWith("app.css")) return null;
                if (path.endsWith("login.fxml")) {
                    if (problem.equals("missing-fxml")) return null;
                    if (problem.equals("invalid-fxml")) return getClass().getResource("/quiz/client/ui/broken.fxml");
                }
                return SceneNavigator.class.getResource(path);
            });
            navigator.showLogin("");
            assertTrue(navigator.currentScreen().isEmpty());
            assertNotNull(stage.getScene().lookup("#startupError"));
            assertTrue(stage.isShowing());
            return null;
        });
        assertThrows(ExecutionException.class, () -> api.connect().get(3, TimeUnit.SECONDS));
    }

    @Test void closeDuringPendingRequestDisposesViewAndReleasesNetwork() throws Exception {
        var peer = login(Role.ADMIN);
        var result = api.listAccounts(); peer.read();
        fx(() -> {
            navigator.showHome(); stage.show();
            stage.fireEvent(new javafx.stage.WindowEvent(stage, javafx.stage.WindowEvent.WINDOW_CLOSE_REQUEST));
            assertTrue(navigator.currentScreen().isEmpty()); return null;
        });
        assertThrows(ExecutionException.class, () -> result.get(3, TimeUnit.SECONDS));
        api.closeAsync().get(3, TimeUnit.SECONDS);
        assertEquals(ServerConnection.State.CLOSED, connection.state()); assertTrue(api.session().current().isEmpty());
    }

    private SocketFixture.Peer login(Role role) throws Exception {
        fx(() -> { navigator.showLogin(""); stage.show(); fillAndSubmit("  fixture  ", "fixture-password"); return null; });
        var peer = server.take();
        Request request = peer.read();
        assertEquals(MessageType.LOGIN, request.type()); assertNull(request.token());
        assertEquals(new quiz.common.dto.LoginRequestDto("fixture", "fixture-password"), request.payload());
        peer.success(request, new LoginResultDto("fixture-token-" + role, 1, "fixture", "Nguyễn Việt — phiên kiểm thử", role));
        awaitScreen(Screen.home(role));
        return peer;
    }

    @ParameterizedTest @EnumSource(value = ErrorCode.class, names = {"INVALID_CREDENTIALS", "ACCOUNT_DISABLED", "CONFLICT", "SERVER_BUSY", "DATABASE_ERROR"})
    void loginErrorsClearPasswordKeepUsernameAndAllowManualRetry(ErrorCode code) throws Exception {
        fx(() -> { navigator.showLogin(""); stage.show(); fillAndSubmit("  fixture  ", " pass "); return null; });
        var peer = server.take(); var request = peer.read();
        assertEquals(new quiz.common.dto.LoginRequestDto("fixture", " pass "), request.payload());
        fx(() -> {
            assertTrue(button("loginButton").isDisabled());
            assertTrue(stage.getScene().lookup("#loginProgress").isVisible());
            stage.setTitle("UI vẫn phản hồi khi server đang chờ");
            ((TextField) stage.getScene().lookup("#usernameInput")).fireEvent(new ActionEvent());
            button("loginButton").fire(); return null;
        });
        long firstRequestId = request.requestId();
        peer.send(Response.error(request.requestId(), code, "SQL password secret must not be displayed"));
        awaitReady();
        fx(() -> {
            assertEquals(Screen.LOGIN, navigator.currentScreen().orElseThrow());
            assertEquals("fixture", ((TextField) stage.getScene().lookup("#usernameInput")).getText());
            assertEquals("", ((PasswordField) stage.getScene().lookup("#passwordInput")).getText());
            assertFalse(label("loginNotice").getText().contains("SQL"));
            assertTrue(label("loginNotice").isVisible()); return null;
        });
        assertTrue(api.session().current().isEmpty());
        if (code == ErrorCode.CONFLICT) assertEquals(ServerConnection.State.DISCONNECTED, connection.state());
        else assertEquals(ServerConnection.State.CONNECTED, connection.state());
        fx(() -> { fillAndSubmit("fixture", "x"); return null; }); // Existing short password is allowed.
        if (code == ErrorCode.CONFLICT) peer = server.take();
        request = peer.read();
        if (code != ErrorCode.CONFLICT) assertEquals(firstRequestId + 1, request.requestId(), "Duplicate Enter/click must not send another LOGIN");
        assertEquals(new quiz.common.dto.LoginRequestDto("fixture", "x"), request.payload());
        peer.success(request, new LoginResultDto("retry-token", 1, "fixture", "Fixture", Role.STUDENT));
        awaitScreen(Screen.STUDENT_DASHBOARD);
    }

    @ParameterizedTest @ValueSource(strings = {"username-blank", "username-long", "password-blank", "password-bytes"})
    void invalidFormNeverConnects(String invalid) throws Exception {
        String username = switch (invalid) { case "username-blank" -> " "; case "username-long" -> "😀".repeat(51); default -> "fixture"; };
        String password = switch (invalid) { case "password-blank" -> " "; case "password-bytes" -> "😀".repeat(19); default -> "password"; };
        fx(() -> {
            navigator.showLogin(""); fillAndSubmit(username, password);
            assertFalse(button("loginButton").isDisabled());
            assertTrue(label("loginNotice").isVisible());
            assertFalse(stage.getScene().lookup("#loginProgress").isVisible()); return null;
        });
        assertEquals(ServerConnection.State.DISCONNECTED, connection.state());
    }

    @Test void loginTimeoutThenRetryUsesFreshSocketAndDropsOldResponse() throws Exception {
        fx(() -> {
            navigator.close(); connection = new ServerConnection(server.config(250, 16)); api = new QuizApi(connection);
            navigator = new SceneNavigator(stage, api, new FxAsync());
            navigator.showLogin(""); stage.show(); fillAndSubmit("fixture", "password"); return null;
        });
        var old = server.take(); var request = old.read();
        awaitReady();
        assertEquals(ServerConnection.State.DISCONNECTED, connection.state());
        fx(() -> { assertTrue(label("loginNotice").getText().contains("thời gian")); fillAndSubmit("fixture", "password"); return null; });
        var fresh = server.take();
        fresh.success(fresh.read(), new LoginResultDto("new-token", 1, "fixture", "Fixture", Role.TEACHER));
        awaitScreen(Screen.TEACHER_DASHBOARD);
        try { old.success(request, new LoginResultDto("old-token", 2, "old", "Old", Role.ADMIN)); }
        catch (java.io.IOException closed) { assertFalse(api.session().current().orElseThrow().token().equals("old-token")); }
        assertEquals(Role.TEACHER, api.session().current().orElseThrow().role());
    }

    @Test void leavingLoginDuringAuthenticationPreventsLateNavigation() throws Exception {
        fx(() -> { navigator.showLogin(""); fillAndSubmit("fixture", "password"); return null; });
        var peer = server.take(); var request = peer.read();
        fx(() -> { navigator.showLogin("Màn hình mới"); return null; });
        assertTrue(api.session().current().isEmpty());
        assertEquals(ServerConnection.State.DISCONNECTED, connection.state());
        try { peer.success(request, new LoginResultDto("late-token", 1, "fixture", "Fixture", Role.ADMIN)); }
        catch (java.io.IOException closed) { assertTrue(api.session().current().isEmpty()); }
        fx(() -> { assertEquals(Screen.LOGIN, navigator.currentScreen().orElseThrow()); assertEquals("Màn hình mới", label("loginNotice").getText()); return null; });
    }

    @Test void serverUnavailableKeepsFormReadyAndCompactErrorCanScroll() throws Exception {
        int closedPort;
        try (var reservation = new java.net.ServerSocket(0)) { closedPort = reservation.getLocalPort(); }
        int port = closedPort;
        fx(() -> {
            navigator.close();
            connection = new ServerConnection(new quiz.client.config.ClientConfig("127.0.0.1", port, 500, 500, 1000, 16, 1000));
            api = new QuizApi(connection); navigator = new SceneNavigator(stage, api, new FxAsync());
            navigator.showLogin(""); stage.show(); stage.setWidth(900); stage.setHeight(620);
            fillAndSubmit("fixture", "password"); return null;
        });
        awaitReady(); awaitCompactSize();
        fx(() -> {
            assertTrue(label("loginNotice").getText().contains("kết nối"));
            assertEquals("", ((PasswordField) stage.getScene().lookup("#passwordInput")).getText());
            var scroll = (javafx.scene.control.ScrollPane) stage.getScene().getRoot();
            scroll.setVvalue(1); scroll.applyCss(); scroll.layout();
            var bounds = button("exitButton").localToScene(button("exitButton").getBoundsInLocal());
            assertTrue(bounds.getMaxY() <= stage.getScene().getHeight(), "Exit must remain reachable after scrolling at minimum height");
            saveSnapshot("login-error-compact"); return null;
        });
        assertTrue(api.session().current().isEmpty());
    }

    @Test void exitDuringConnectionHandshakeClosesWithoutBlockingUi() throws Exception {
        try (var listener = new java.net.ServerSocket(0, 10, java.net.InetAddress.getLoopbackAddress())) {
            listener.setSoTimeout(3000);
            fx(() -> {
                navigator.close();
                connection = new ServerConnection(new quiz.client.config.ClientConfig(listener.getInetAddress().getHostAddress(),
                    listener.getLocalPort(), 1000, 3000, 3000, 16, 1000));
                api = new QuizApi(connection); navigator = new SceneNavigator(stage, api, new FxAsync());
                navigator.showLogin(""); stage.show(); fillAndSubmit("fixture", "password"); return null;
            });
            try (var stalledPeer = listener.accept()) {
                fx(() -> { button("exitButton").fire(); assertFalse(stage.isShowing()); return null; });
                api.closeAsync().get(3, TimeUnit.SECONDS);
                assertEquals(ServerConnection.State.CLOSED, connection.state());
            }
        }
    }

    private void fillAndSubmit(String user, String pass) {
        stage.getScene().getRoot().applyCss(); stage.getScene().getRoot().layout();
        ((TextField) stage.getScene().lookup("#usernameInput")).setText(user);
        ((PasswordField) stage.getScene().lookup("#passwordInput")).setText(pass);
        button("loginButton").fire();
    }

    private void awaitReady() throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (!fx(() -> !button("loginButton").isDisabled()) && System.nanoTime() < until) Thread.sleep(10);
        assertTrue(fx(() -> !button("loginButton").isDisabled()));
    }

    private void awaitScreen(Screen expected) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (!fx(() -> navigator.currentScreen().orElse(null) == expected) && System.nanoTime() < until) {
            Thread.sleep(10);
        }
        assertEquals(expected, fx(() -> navigator.currentScreen().orElse(null)));
    }

    private void awaitCompactSize() throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (!fx(() -> stage.getScene().getWidth() <= 900 && stage.getScene().getHeight() <= 620)
            && System.nanoTime() < until) Thread.sleep(10);
        assertTrue(fx(() -> stage.getScene().getWidth() <= 900 && stage.getScene().getHeight() <= 620));
    }

    private Label label(String id) { stage.getScene().getRoot().applyCss(); return (Label) stage.getScene().lookup("#" + id); }
    private Button button(String id) { stage.getScene().getRoot().applyCss(); return (Button) stage.getScene().lookup("#" + id); }

    private void saveSnapshot(String name) throws Exception {
        stage.getScene().getRoot().applyCss(); stage.getScene().getRoot().layout();
        var image = stage.getScene().snapshot(null);
        var buffer = new BufferedImage((int) image.getWidth(), (int) image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < buffer.getHeight(); y++) for (int x = 0; x < buffer.getWidth(); x++) {
            buffer.setRGB(x, y, image.getPixelReader().getArgb(x, y));
        }
        Path directory = Path.of("target", "ui-screenshots"); Files.createDirectories(directory);
        assertTrue(ImageIO.write(buffer, "png", directory.resolve(name + ".png").toFile()));
    }

    private static <T> T fx(Callable<T> task) throws Exception {
        var future = new FutureTask<>(task); Platform.runLater(future);
        return future.get(5, TimeUnit.SECONDS);
    }
}
