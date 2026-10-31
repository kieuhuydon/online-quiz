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
import quiz.common.dto.AccountDto;
import quiz.common.dto.CreateAccountDto;
import quiz.common.dto.SetAccountActiveDto;
import javafx.scene.control.TableView;
import java.time.Instant;
import java.util.List;
import java.util.function.BooleanSupplier;
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
    @Test @SuppressWarnings("unchecked") void adminClassPageCreatesWithTeacherAndManagesMembers() throws Exception {
        var peer = login(Role.ADMIN);
        fx(() -> {
            assertNull(stage.getScene().lookup("#activeSection"));
            assertEquals("Quản lí tài khoản", button("adminAccounts").getText());
            assertEquals("Quản lí lớp học", button("adminClasses").getText());
            button("adminClasses").fire();
            assertTrue(button("adminClasses").getPseudoClassStates().contains(javafx.css.PseudoClass.getPseudoClass("current")));
            assertFalse(button("adminAccounts").getPseudoClassStates().contains(javafx.css.PseudoClass.getPseudoClass("current")));
            ((javafx.scene.control.TitledPane) stage.getScene().lookup("#createPane")).setExpanded(true);
            return null;
        });
        var teacher = account(2, Role.TEACHER, true);
        var student = account(3, Role.STUDENT, true);
        for (int i = 0; i < 2; i++) {
            var request = peer.read();
            peer.success(request, request.type() == MessageType.LIST_ACCOUNTS
                ? new java.util.ArrayList<>(List.of(teacher, student, account(4, Role.STUDENT, true), account(5, Role.STUDENT, true)))
                : new java.util.ArrayList<quiz.common.dto.ClassDto>());
        }
        awaitClassControl("createClass");
        fx(() -> {
            assertEquals(Screen.ADMIN_CLASSES, navigator.currentScreen().orElseThrow());
            text("className").setText("ENG-ADMIN");
            ((javafx.scene.control.ComboBox<AccountDto>) stage.getScene().lookup("#teacher")).setValue(teacher);
            button("createClass").fire(); return null;
        });
        var request = peer.read();
        assertEquals(new quiz.common.dto.CreateClassDto("ENG-ADMIN", "", 2), request.payload());
        var classroom = new quiz.common.dto.ClassDto(10, "ENG-ADMIN", null, 2, "Teacher", Instant.now());
        peer.success(request, classroom);
        peer.success(peer.read(), new java.util.ArrayList<quiz.common.dto.ClassMemberDto>());
        awaitClassControl("selectAllStudents");
        fx(() -> {
            button("selectAllStudents").fire();
            assertEquals("Đã chọn 3 học viên", label("selectedCount").getText());
            button("addMember").fire(); button("addMember").fire();
            assertTrue(button("selectAllStudents").isDisabled());
            return null;
        });
        request = peer.read(); assertEquals(new quiz.common.dto.AddClassMemberDto(10, 3), request.payload());
        peer.success(request, null);
        request = peer.read(); assertEquals(new quiz.common.dto.AddClassMemberDto(10, 4), request.payload());
        peer.send(Response.error(request.requestId(), ErrorCode.VALIDATION_ERROR, "hidden details"));
        request = peer.read(); assertEquals(new quiz.common.dto.AddClassMemberDto(10, 5), request.payload());
        peer.send(Response.error(request.requestId(), ErrorCode.CONFLICT, "already enrolled"));
        peer.success(peer.read(), new java.util.ArrayList<>(List.of(
            new quiz.common.dto.ClassMemberDto(10, 3, "student3", "Student 3", Instant.now()),
            new quiz.common.dto.ClassMemberDto(10, 5, "student5", "Student 5", Instant.now()))));
        awaitClassControl("selectAllStudents");
        fx(() -> {
            assertTrue(label("notice").getText().contains("1/3"));
            assertTrue(label("notice").getText().contains("1 học viên đã có"));
            var candidates = (TableView<AccountDto>) stage.getScene().lookup("#students");
            assertEquals(List.of(4L), candidates.getItems().stream().map(AccountDto::id).toList());
            assertEquals("Đã chọn 1 học viên", label("selectedCount").getText());
            button("clearStudents").fire(); assertTrue(button("addMember").isDisabled());
            ((TableView<?>) stage.getScene().lookup("#members")).getSelectionModel().select(0);
            ((javafx.scene.control.TitledPane) stage.getScene().lookup("#createPane")).setExpanded(false);
            var root = stage.getScene().getRoot(); root.applyCss(); root.layout();
            assertTrue(stage.getScene().lookup("#classes").localToScene(0, 0).getY()
                < stage.getScene().lookup("#createPane").localToScene(0, 0).getY());
            saveSnapshot("admin-classes");
            var scroll = (javafx.scene.control.ScrollPane) stage.getScene().lookup("#pageScroll");
            scroll.setVvalue(1); root.layout(); saveSnapshot("admin-classes-multi-select");
            button("removeMember").fire(); return null;
        });
        request = peer.read(); assertEquals(MessageType.REMOVE_CLASS_MEMBER, request.type());
        peer.success(request, null); peer.success(peer.read(), new java.util.ArrayList<quiz.common.dto.ClassMemberDto>());
    }

    private void awaitClassControl(String id) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (fx(() -> button(id).isDisabled()) && System.nanoTime() < until) Thread.sleep(10);
        assertFalse(fx(() -> button(id).isDisabled()));
    }

    @Test void bulkEnrollmentStopsAfterServerFailureAndKeepsUnprocessedStudentsSelected() throws Exception {
        var peer = login(Role.ADMIN);
        fx(() -> { button("adminClasses").fire(); return null; });
        var classroom = new quiz.common.dto.ClassDto(10, "Class", null, 2, "Teacher", Instant.now());
        for (int i = 0; i < 2; i++) {
            var request = peer.read();
            peer.success(request, request.type() == MessageType.LIST_ACCOUNTS
                ? new java.util.ArrayList<>(List.of(account(3, Role.STUDENT, true), account(4, Role.STUDENT, true)))
                : new java.util.ArrayList<>(List.of(classroom)));
        }
        awaitClassControl("refresh");
        fx(() -> { ((TableView<?>) stage.getScene().lookup("#classes")).getSelectionModel().select(0); return null; });
        peer.success(peer.read(), new java.util.ArrayList<quiz.common.dto.ClassMemberDto>());
        awaitClassControl("selectAllStudents");
        fx(() -> { button("selectAllStudents").fire(); button("addMember").fire(); return null; });
        var request = peer.read();
        assertEquals(new quiz.common.dto.AddClassMemberDto(10, 3), request.payload());
        peer.send(Response.error(request.requestId(), ErrorCode.SERVER_BUSY, "busy"));
        var reload = peer.read();
        assertEquals(MessageType.LIST_CLASS_MEMBERS, reload.type(), "Do not send further additions or retry on failure");
        peer.success(reload, new java.util.ArrayList<quiz.common.dto.ClassMemberDto>());
        awaitClassControl("selectAllStudents");
        fx(() -> {
            assertTrue(label("notice").getText().contains("0/2"));
            assertTrue(label("notice").getText().contains("2 học viên chưa xác nhận"));
            assertEquals("Đã chọn 2 học viên", label("selectedCount").getText());
            return null;
        });
    }
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
            assertNull(stage.getScene().lookup("#exitButton"));
            assertEquals("", label("connectionStatus").getText());
            saveSnapshot("login");
            return null;
        });
    }

    @ParameterizedTest @EnumSource(Role.class)
    void rolesLoadCorrectHomeAndLogoutOnSameStage(Role role) throws Exception {
        var peer = login(role);
        fx(() -> {
            stage.show();
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
        for (int i = 0; i < 8; i++) peer.success(peer.read(), new java.util.ArrayList<AccountDto>());
        awaitAccountsReady();
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
        fx(() -> { navigator.logout(); return null; });
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
        if (role == Role.ADMIN) {
            var list = peer.read(); assertEquals(MessageType.LIST_ACCOUNTS, list.type());
            peer.success(list, new java.util.ArrayList<AccountDto>());
            awaitAccountsReady();
        } else {
            // Student dashboard loads quizzes, history and classes when mounted.
            for (int i = 0; i < (role == Role.TEACHER ? 2 : 3); i++) {
                var list = peer.read(); peer.success(list, new java.util.ArrayList<>());
            }
        }
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
            var bounds = button("loginButton").localToScene(button("loginButton").getBoundsInLocal());
            assertTrue(bounds.getMaxY() <= stage.getScene().getHeight(), "Login must remain reachable after scrolling at minimum height");
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
                fx(() -> { stage.fireEvent(new javafx.stage.WindowEvent(stage, javafx.stage.WindowEvent.WINDOW_CLOSE_REQUEST)); stage.close(); assertFalse(stage.isShowing()); return null; });
                api.closeAsync().get(3, TimeUnit.SECONDS);
                assertEquals(ServerConnection.State.CLOSED, connection.state());
            }
        }
    }

    @Test void accountLoadsRowsProtectsAdminsAndPreservesSelectionById() throws Exception {
        var peer = login(Role.ADMIN);
        reloadAccounts(peer, List.of(account(1, Role.ADMIN, true), account(2, Role.STUDENT, true)));
        fx(() -> {
            assertEquals(7, accounts().getColumns().size());
            assertEquals("—", accounts().getColumns().get(3).getCellData(0));
            accounts().getSelectionModel().select(0);
            assertTrue(button("toggleAccount").isDisabled());
            assertTrue(label("selectedAccount").getText().contains("bảo vệ"));
            accounts().getSelectionModel().select(1); button("refreshAccounts").fire(); return null;
        });
        peer.success(peer.read(), new java.util.ArrayList<>(List.of(account(2, Role.STUDENT, false), account(1, Role.ADMIN, true))));
        awaitAccountsReady();
        fx(() -> {
            assertEquals(2, accounts().getSelectionModel().getSelectedItem().id());
            assertEquals("Mở khóa tài khoản", button("toggleAccount").getText());
            saveSnapshot("accounts-loaded"); return null;
        });
    }

    @Test void latestAccountLoadWinsAndFailureRetainsMarkedSnapshot() throws Exception {
        var peer = login(Role.ADMIN);
        fx(() -> { button("refreshAccounts").fire(); button("refreshAccounts").fire(); return null; });
        var old = peer.read(); var fresh = peer.read();
        peer.success(fresh, new java.util.ArrayList<>(List.of(account(2, Role.TEACHER, true))));
        awaitAccountsReady();
        peer.success(old, new java.util.ArrayList<>(List.of(account(1, Role.ADMIN, true))));
        var barrier = api.listAccounts(); peer.success(peer.read(), new java.util.ArrayList<AccountDto>()); barrier.get(3, TimeUnit.SECONDS);
        fx(() -> { assertEquals(2, accounts().getItems().getFirst().id()); button("refreshAccounts").fire(); return null; });
        var request = peer.read(); peer.send(Response.error(request.requestId(), ErrorCode.DATABASE_ERROR, "secret SQL"));
        awaitAccountsReady();
        fx(() -> {
            assertEquals(2, accounts().getItems().getFirst().id());
            assertTrue(label("listStatus").getText().contains("lần tải trước"));
            assertFalse(label("listStatus").getText().contains("SQL")); return null;
        });
    }

    @Test void creationHasOwnRouteAndReturnsToFullSizeListWithNewAccountSelected() throws Exception {
        var peer = login(Role.ADMIN);
        fx(() -> {
            assertNull(stage.getScene().lookup("#accountUsername"));
            assertFalse(stage.getScene().lookup("#accountActions").isVisible());
            button("createAccount").fire();
            assertEquals(Screen.ADMIN_CREATE_ACCOUNT, navigator.currentScreen().orElseThrow());
            assertNull(stage.getScene().lookup("#accountTable"));
            fillAccount("new-user", "password"); button("createAccount").fire();
            return null;
        });
        var create = peer.read();
        var created = account(2, Role.STUDENT, true);
        peer.success(create, created);
        peer.success(peer.read(), new java.util.ArrayList<>(List.of(account(1, Role.ADMIN, true), created)));
        awaitAccountsReady();
        fx(() -> {
            assertEquals(Screen.ADMIN_ACCOUNTS, navigator.currentScreen().orElseThrow());
            assertEquals(created, accounts().getSelectionModel().getSelectedItem());
            var root = stage.getScene().getRoot(); root.applyCss(); root.layout();
            var content = stage.getScene().lookup("#workspaceContent");
            assertTrue(accounts().getWidth() > content.getBoundsInLocal().getWidth() * 0.85);
            assertTrue(accounts().getHeight() > 200);
            saveSnapshot("accounts-created"); return null;
        });
    }

    @Test void acknowledgedCreateAndFailedReloadRemainSeparateResults() throws Exception {
        var peer = login(Role.ADMIN);
        fx(() -> { fillAccount("  new-user  ", " password "); button("createAccount").fire(); button("createAccount").fire(); return null; });
        var request = peer.read(); assertEquals(MessageType.CREATE_ACCOUNT, request.type());
        assertEquals(new CreateAccountDto("new-user", " password ", "Tên kiểm thử", null, Role.STUDENT), request.payload());
        fx(() -> { assertTrue(button("createAccount").isDisabled()); assertTrue(button("backToAccounts").isDisabled()); return null; });
        peer.success(request, new AccountDto(2, "new-user", "Tên kiểm thử", null, Role.STUDENT, true, 1L, Instant.now()));
        var list = peer.read(); assertEquals(request.requestId() + 1, list.requestId());
        peer.send(Response.error(list.requestId(), ErrorCode.DATABASE_ERROR, "SQL secret"));
        awaitAccountsReady();
        fx(() -> {
            assertTrue(label("workspaceNotice").getText().contains("Đã tạo"));
            assertTrue(label("listStatus").getText().contains("Không tải"));
            assertNull(stage.getScene().lookup("#accountPassword"));
            assertNull(stage.getScene().lookup("#accountUsername")); return null;
        });
    }

    @ParameterizedTest @EnumSource(value = ErrorCode.class, names = {"USERNAME_ALREADY_EXISTS", "EMAIL_ALREADY_EXISTS", "CONFLICT", "VALIDATION_ERROR", "FORBIDDEN", "DATABASE_ERROR", "SERVER_BUSY"})
    void accountWriteErrorsKeepSafeFieldsAndAllowCorrection(ErrorCode code) throws Exception {
        var peer = login(Role.ADMIN);
        fx(() -> { fillAccount("new-user", "password"); button("createAccount").fire(); return null; });
        var request = peer.read(); peer.send(Response.error(request.requestId(), code, "secret SQL password"));
        awaitAccountsReady();
        fx(() -> {
            assertEquals("new-user", text("accountUsername").getText());
            assertEquals("", text("accountPassword").getText());
            assertFalse(label("formError").getText().contains("secret"));
            assertTrue(label("formError").isVisible());
            if (code == ErrorCode.EMAIL_ALREADY_EXISTS) assertTrue(label("emailError").getText().contains("Email"));
            if (code == ErrorCode.USERNAME_ALREADY_EXISTS) assertTrue(label("usernameError").getText().contains("Tên đăng nhập")); return null;
        });
    }

    @ParameterizedTest @ValueSource(strings = {"blank", "username-long", "name-long", "email", "password-short", "password-bytes", "admin-role"})
    @SuppressWarnings("unchecked") void invalidAccountFormSendsNoRequest(String invalid) throws Exception {
        var peer = login(Role.ADMIN);
        fx(() -> {
            fillAccount("new-user", "password");
            switch (invalid) {
                case "blank" -> text("accountUsername").setText(" ");
                case "username-long" -> text("accountUsername").setText("😀".repeat(51));
                case "name-long" -> text("accountFullName").setText("😀".repeat(101));
                case "email" -> text("accountEmail").setText("invalid");
                case "password-short" -> text("accountPassword").setText("short");
                case "password-bytes" -> text("accountPassword").setText("😀".repeat(19));
                case "admin-role" -> ((javafx.scene.control.ComboBox<Role>) stage.getScene().lookup("#accountRole")).setValue(Role.ADMIN);
            }
            button("createAccount").fire(); assertTrue(label("formError").isVisible());
            assertFalse(button("createAccount").isDisabled());
            button("backToAccounts").fire(); return null;
        });
        var request = peer.read(); assertEquals(MessageType.LIST_ACCOUNTS, request.type());
        peer.success(request, new java.util.ArrayList<AccountDto>()); awaitAccountsReady();
    }

    @Test void lockNeedsConfirmationThenUnlockUsesExplicitState() throws Exception {
        var peer = login(Role.ADMIN);
        reloadAccounts(peer, List.of(account(2, Role.TEACHER, true)));
        fx(() -> {
            accounts().getSelectionModel().select(0); button("toggleAccount").fire();
            assertTrue(label("confirmationText").getText().contains("user2"));
            assertTrue(label("confirmationText").getText().contains("thu hồi"));
            button("cancelLock").fire(); assertFalse(stage.getScene().lookup("#confirmation").isVisible());
            button("toggleAccount").fire(); button("confirmLock").fire(); button("confirmLock").fire(); return null;
        });
        var lock = peer.read(); assertEquals(MessageType.SET_ACCOUNT_ACTIVE, lock.type());
        assertEquals(new SetAccountActiveDto(2, false), lock.payload());
        fx(() -> { assertTrue(accounts().getItems().getFirst().active()); return null; });
        peer.success(lock, null);
        var list = peer.read(); assertEquals(lock.requestId() + 1, list.requestId());
        peer.success(list, new java.util.ArrayList<>(List.of(account(2, Role.TEACHER, false)))); awaitAccountsReady();
        fx(() -> { button("toggleAccount").fire(); return null; });
        var unlock = peer.read(); assertEquals(new SetAccountActiveDto(2, true), unlock.payload()); peer.success(unlock, null);
        peer.success(peer.read(), new java.util.ArrayList<>(List.of(account(2, Role.TEACHER, true)))); awaitAccountsReady();
        fx(() -> { assertTrue(accounts().getItems().getFirst().active()); assertTrue(label("accountNotice").getText().contains("mở khóa")); return null; });
    }

    @Test void missingAccountRefreshesWithoutPretendingLockSucceeded() throws Exception {
        var peer = login(Role.ADMIN); reloadAccounts(peer, List.of(account(2, Role.STUDENT, false)));
        fx(() -> { accounts().getSelectionModel().select(0); button("toggleAccount").fire(); return null; });
        var request = peer.read(); peer.send(Response.error(request.requestId(), ErrorCode.NOT_FOUND, "missing"));
        peer.success(peer.read(), new java.util.ArrayList<AccountDto>()); awaitAccountsReady();
        fx(() -> { assertTrue(accounts().getItems().isEmpty()); assertTrue(label("accountNotice").getText().contains("không còn")); return null; });
    }

    @Test void acknowledgedLockAndFailedReloadKeepSuccessAndStaleStatusSeparate() throws Exception {
        var peer = login(Role.ADMIN); reloadAccounts(peer, List.of(account(2, Role.STUDENT, true)));
        fx(() -> { accounts().getSelectionModel().select(0); button("toggleAccount").fire(); button("confirmLock").fire(); return null; });
        peer.success(peer.read(), null);
        var list = peer.read(); peer.send(Response.error(list.requestId(), ErrorCode.SERVER_BUSY, "busy")); awaitAccountsReady();
        fx(() -> {
            assertTrue(label("accountNotice").getText().contains("Đã khóa"));
            assertTrue(label("listStatus").getText().contains("lần tải trước"));
            assertTrue(accounts().getItems().getFirst().active(), "Old snapshot must remain marked until reload succeeds"); return null;
        });
    }

    @Test void compactAccountFormScrollsIntoViewAndEnterSubmitsOnce() throws Exception {
        var peer = login(Role.ADMIN);
        fx(() -> { button("createAccount").fire(); stage.setWidth(900); stage.setHeight(620); return null; }); awaitCompactSize();
        // Native resize and the responsive GridPane finish on subsequent JavaFX pulses.
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (!fx(this::scrollCreateIntoView) && System.nanoTime() < until) Thread.sleep(10);
        fx(() -> {
            assertTrue(scrollCreateIntoView(), "Create remains reachable at minimum window size");
            assertTrue(text("accountUsername").isFocusTraversable());
            fillAccount("keyboard-user", "password");
            text("accountPassword").fireEvent(new ActionEvent());
            button("createAccount").fire(); return null;
        });
        var create = peer.read(); assertEquals(MessageType.CREATE_ACCOUNT, create.type());
        peer.send(Response.error(create.requestId(), ErrorCode.CONFLICT, "duplicate")); awaitAccountsReady();
        until = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (!fx(this::scrollCreateIntoView) && System.nanoTime() < until) Thread.sleep(10);
        fx(() -> {
            assertTrue(scrollCreateIntoView(), "Create remains reachable when inline error expands the form");
            saveSnapshot("accounts-form-compact"); button("backToAccounts").fire(); return null;
        });
        var list = peer.read(); assertEquals(create.requestId() + 1, list.requestId());
        peer.success(list, new java.util.ArrayList<AccountDto>()); awaitAccountsReady();
    }

    @Test void lostWriteResponseRedirectsWithUnknownOutcomeAndClearsOldPassword() throws Exception {
        fx(() -> {
            navigator.close(); connection = new ServerConnection(server.config(700, 16)); api = new QuizApi(connection);
            navigator = new SceneNavigator(stage, api, new FxAsync()); return null;
        });
        var peer = login(Role.ADMIN);
        TextField password = fx(() -> { fillAccount("new-user", "password"); button("createAccount").fire(); return text("accountPassword"); });
        assertEquals(MessageType.CREATE_ACCOUNT, peer.read().type()); // Deliberately no response.
        awaitScreen(Screen.LOGIN);
        fx(() -> { assertTrue(label("loginNotice").getText().contains("Chưa xác nhận")); assertEquals("", password.getText()); return null; });
    }

    @Test void disposedAccountScreenIgnoresLateCreateAndListResponses() throws Exception {
        var peer = login(Role.ADMIN);
        Label oldNotice = fx(() -> { fillAccount("new-user", "password"); button("createAccount").fire(); return label("formError"); });
        var create = peer.read();
        fx(() -> { navigator.showHome(); return null; });
        var list = peer.read(); peer.success(create, account(2, Role.STUDENT, true));
        peer.success(list, new java.util.ArrayList<AccountDto>()); awaitAccountsReady();
        fx(() -> { assertEquals("", oldNotice.getText()); assertFalse(label("accountNotice").isVisible()); return null; });
    }

    private static AccountDto account(long id, Role role, boolean active) {
        return new AccountDto(id, "user" + id, "Nguyễn Việt — tên dài để kiểm tra bảng tài khoản", null, role, active, null, Instant.EPOCH);
    }
    @SuppressWarnings("unchecked") private TableView<AccountDto> accounts() {
        stage.getScene().getRoot().applyCss(); return (TableView<AccountDto>) stage.getScene().lookup("#accountTable");
    }
    private TextField text(String id) { return (TextField) stage.getScene().lookup("#" + id); }
    @SuppressWarnings("unchecked") private void fillAccount(String username, String password) {
        stage.getScene().getRoot().applyCss();
        if (navigator.currentScreen().orElseThrow() == Screen.ADMIN_ACCOUNTS) {
            button("createAccount").fire(); stage.getScene().getRoot().applyCss();
        }
        text("accountUsername").setText(username); text("accountFullName").setText("  Tên kiểm thử  ");
        text("accountEmail").setText("  "); text("accountPassword").setText(password);
        ((javafx.scene.control.ComboBox<Role>) stage.getScene().lookup("#accountRole")).setValue(Role.STUDENT);
    }
    private void reloadAccounts(SocketFixture.Peer peer, List<AccountDto> rows) throws Exception {
        fx(() -> { button("refreshAccounts").fire(); return null; });
        peer.success(peer.read(), new java.util.ArrayList<>(rows)); awaitAccountsReady();
    }
    private void awaitAccountsReady() throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (!fx(this::accountsReady) && System.nanoTime() < until) Thread.sleep(10);
        assertTrue(fx(this::accountsReady));
    }

    private boolean accountsReady() {
        stage.getScene().getRoot().applyCss();
        if (navigator.currentScreen().orElse(null) == Screen.ADMIN_CREATE_ACCOUNT) return !button("createAccount").isDisabled();
        return navigator.currentScreen().orElse(null) == Screen.ADMIN_ACCOUNTS
            && !stage.getScene().lookup("#accountProgress").isVisible();
    }

    private boolean scrollCreateIntoView() {
        stage.getScene().getRoot().applyCss(); stage.getScene().getRoot().layout();
        var scroll = (javafx.scene.control.ScrollPane) stage.getScene().lookup("#pageScroll");
        scroll.setVvalue(1); stage.getScene().getRoot().layout();
        var bounds = button("createAccount").localToScene(button("createAccount").getBoundsInLocal());
        return bounds.getMaxY() <= stage.getScene().getHeight() && bounds.getMinY() >= 68;
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
