package quiz.client.ui;

import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.postgresql.ds.PGSimpleDataSource;
import quiz.client.api.QuizApi;
import quiz.client.config.ClientConfig;
import quiz.client.network.ServerConnection;
import quiz.client.support.ClientTestDatabase;
import quiz.client.util.FxAsync;
import quiz.common.enums.Role;
import quiz.server.ServerMain;
import quiz.server.config.ServerConfig;
import quiz.server.db.DataSourceProvider;
import quiz.server.domain.User;
import quiz.server.network.QuizServer;
import quiz.server.repository.jdbc.JdbcUserRepository;
import quiz.server.security.PasswordHasher;

import java.net.ServerSocket;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

/** Actual Login FXML/controller → QuizApi → TCP → server bootstrap/services → guarded PostgreSQL. */
@Timeout(30)
class ClientLoginDbIT {
    private static final String PASSWORD = " login-fixture-password ";
    private PGSimpleDataSource dataSource;
    private boolean verified;
    private final List<String> fixtures = new ArrayList<>();
    private QuizServer server;
    private QuizApi api;
    private Stage stage;
    private SceneNavigator navigator;

    @BeforeAll static void toolkit() throws Exception {
        var ready = new CountDownLatch(1);
        Platform.startup(() -> { Platform.setImplicitExit(false); ready.countDown(); });
        assertTrue(ready.await(10, TimeUnit.SECONDS));
    }
    @AfterAll static void stopToolkit() { Platform.exit(); }

    @BeforeEach void setup() throws Exception {
        dataSource = ClientTestDatabase.load();
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT current_database(), current_user")) {
            assertTrue(result.next()); assertEquals("quizdb_test", result.getString(1)); assertEquals("quiz_test", result.getString(2));
            verified = true;
        }
        int port;
        try (ServerSocket reservation = new ServerSocket(0)) { port = reservation.getLocalPort(); }
        server = ServerMain.start(new ServerConfig(port, 10, 2, 10, 3, dataSource.getURL(), dataSource.getUser(),
            dataSource.getPassword(), 2, 0, 0, 1000, 1000, 3000, 2000));
        api = new QuizApi(new ServerConnection(new ClientConfig("127.0.0.1", server.getPort(), 1000, 1000, 3000, 16, 1000)));
        fx(() -> {
            stage = new Stage(); navigator = new SceneNavigator(stage, api, new FxAsync());
            navigator.showLogin(""); stage.show(); return null;
        });
    }

    @AfterEach void cleanup() throws Exception {
        try {
            if (navigator != null) fx(() -> { navigator.close(); stage.close(); return null; });
            if (api != null) api.closeAsync().get(3, TimeUnit.SECONDS);
        } finally {
            if (server != null) server.close();
            DataSourceProvider.close();
            if (verified) try (Connection connection = dataSource.getConnection();
                PreparedStatement delete = connection.prepareStatement("DELETE FROM users WHERE username = ?")) {
                for (String username : fixtures) { delete.setString(1, username); delete.executeUpdate(); }
            }
        }
    }

    @ParameterizedTest @EnumSource(Role.class)
    void formAuthenticatesEveryRoleAndLogsOutAgainstRealServer(Role role) throws Exception {
        User account = fixture(role);
        submit("  " + account.username() + "  ", PASSWORD);
        await(() -> navigator.currentScreen().orElse(null) == Screen.home(role));
        assertEquals(account.id(), api.session().current().orElseThrow().userId());
        assertEquals(role, api.session().current().orElseThrow().role());
        fx(() -> {
            stage.getScene().getRoot().applyCss();
            assertEquals(account.fullName(), ((Label) stage.getScene().lookup("#sessionFullName")).getText());
            ((Button) stage.getScene().lookup("#logoutButton")).fire(); return null;
        });
        await(() -> navigator.currentScreen().orElse(null) == Screen.LOGIN);
        assertTrue(api.session().current().isEmpty());
        submit(account.username(), PASSWORD);
        await(() -> navigator.currentScreen().orElse(null) == Screen.home(role));
    }

    @Test void wrongPasswordAndDisabledAccountReturnSafeErrorsThenRetry() throws Exception {
        User account = fixture(Role.STUDENT);
        submit(account.username(), "wrong-password");
        await(() -> notice().contains("không đúng") && !loginButton().isDisabled());
        assertTrue(api.session().current().isEmpty());
        new JdbcUserRepository(dataSource).updateActiveStatus(account.id(), false);
        submit(account.username(), PASSWORD);
        await(() -> notice().contains("bị khóa") && !loginButton().isDisabled());
        new JdbcUserRepository(dataSource).updateActiveStatus(account.id(), true);
        submit(account.username(), PASSWORD);
        await(() -> navigator.currentScreen().orElse(null) == Screen.STUDENT_DASHBOARD);
    }

    private User fixture(Role role) {
        String username = "login_" + UUID.randomUUID().toString().replace("-", "");
        fixtures.add(username);
        return new JdbcUserRepository(dataSource).save(User.createNew(username, PasswordHasher.hashPassword(PASSWORD),
            "Login kiểm thử " + role, null, role, null));
    }

    private void submit(String username, String password) throws Exception {
        fx(() -> {
            ((TextField) stage.getScene().lookup("#usernameInput")).setText(username);
            ((PasswordField) stage.getScene().lookup("#passwordInput")).setText(password);
            loginButton().fire(); return null;
        });
    }

    private Button loginButton() { return (Button) stage.getScene().lookup("#loginButton"); }
    private String notice() { return ((Label) stage.getScene().lookup("#loginNotice")).getText(); }
    private void await(BooleanSupplier condition) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!fx(condition::getAsBoolean) && System.nanoTime() < until) Thread.sleep(10);
        assertTrue(fx(condition::getAsBoolean), "UI did not reach expected state");
    }
    private static <T> T fx(Callable<T> task) throws Exception {
        var future = new FutureTask<>(task); Platform.runLater(future); return future.get(5, TimeUnit.SECONDS);
    }
}
