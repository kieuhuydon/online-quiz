package quiz.client.ui.teacher.question;

import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.stage.Stage;
import org.junit.jupiter.api.*;
import quiz.client.api.QuizApi;
import quiz.client.network.ServerConnection;
import quiz.client.support.SocketFixture;
import quiz.client.ui.SceneNavigator;
import quiz.client.ui.Screen;
import quiz.client.util.FxAsync;
import quiz.common.dto.CreateQuestionDto;
import quiz.common.dto.QuestionDto;
import quiz.common.dto.UpdateQuestionDto;
import quiz.common.enums.Difficulty;
import quiz.common.enums.Role;
import quiz.common.protocol.MessageType;
import quiz.common.protocol.Request;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** Real JavaFX/FXML + loopback client API for the Teacher question bank UI. */
@Timeout(20)
class QuestionControllerUiIT {

    private SocketFixture server;
    private ServerConnection connection;
    private QuizApi api;
    private Stage stage;
    private SceneNavigator navigator;
    private FxAsync fx;

    @BeforeAll
    static void startToolkit() throws Exception {
        var ready = new CountDownLatch(1);
        Platform.startup(() -> { Platform.setImplicitExit(false); ready.countDown(); });
        assertTrue(ready.await(10, TimeUnit.SECONDS), "JavaFX toolkit did not start");
    }

    @AfterAll
    static void stopToolkit() { Platform.exit(); }

    @BeforeEach
    void setup() throws Exception {
        server = new SocketFixture();
        connection = new ServerConnection(server.config());
        api = new QuizApi(connection);
        fx(() -> { stage = new Stage(); navigator = new SceneNavigator(stage, api, new FxAsync()); return null; });
    }

    @AfterEach
    void cleanup() throws Exception {
        try {
            fx(() -> { navigator.close(); stage.close(); return null; });
            api.closeAsync().get(4, TimeUnit.SECONDS);
        } finally { server.close(); }
    }

    @Test
    void questionBankLoadsAndDisplaysQuestions() throws Exception {
        var peer = loginAsTeacher();
        fx(() -> { navigator.show(Screen.TEACHER_QUESTIONS); return null; });
        var list = readUntil(peer, MessageType.LIST_QUESTIONS);
        peer.success(list, new java.util.ArrayList<>(List.of(
            new QuestionDto(1, "Câu 1?", "A", "B", "C", "D", 'A', "Ngữ pháp", Difficulty.EASY, true, 2, Instant.now()),
            new QuestionDto(2, "Câu 2?", "A", "B", "C", "D", 'B', "Từ vựng", Difficulty.HARD, false, 2, Instant.now())
        )));
        awaitQuestionsReady();
        fx(() -> {
            assertEquals(2, questionTable().getItems().size());
            return null;
        });
    }

    @Test
    void createQuestionSendsCorrectPayloadAndRefreshesList() throws Exception {
        var peer = loginAsTeacher();
        fx(() -> { navigator.show(Screen.TEACHER_QUESTIONS); return null; });
        var listMsg = readUntil(peer, MessageType.LIST_QUESTIONS);
        peer.success(listMsg, new java.util.ArrayList<QuestionDto>());

        fx(() -> {
            button("newQuestion").fire();
            textArea("questionContent").setText("Câu hỏi mới?");
            text("questionOptionA").setText("A");
            text("questionOptionB").setText("B");
            text("questionOptionC").setText("C");
            text("questionOptionD").setText("D");
            combo("questionCorrectOption").setValue('B');
            text("questionTopic").setText("Ngữ pháp");
            combo("questionDifficulty").setValue(Difficulty.MEDIUM);
            button("createQuestion").fire();
            return null;
        });
        var create = peer.read();
        assertEquals(MessageType.CREATE_QUESTION, create.type());
        var dto = (CreateQuestionDto) create.payload();
        assertEquals("Câu hỏi mới?", dto.content());
        assertEquals('B', dto.correctOption());
        assertEquals("Ngữ pháp", dto.topic());
        assertEquals(Difficulty.MEDIUM, dto.difficulty());

        var created = new QuestionDto(3, "Câu hỏi mới?", "A", "B", "C", "D", 'B',
            "Ngữ pháp", Difficulty.MEDIUM, true, 2, Instant.now());
        peer.success(create, created);
        peer.success(peer.read(), new java.util.ArrayList<>(List.of(created)));
        awaitQuestionsReady();
        fx(() -> {
            assertEquals(1, questionTable().getItems().size());
            assertEquals("Câu hỏi mới?", questionTable().getItems().get(0).content());
            assertFalse(questionFormVisible());
            return null;
        });
    }

    @Test
    void updateQuestionSendsCorrectPayload() throws Exception {
        var peer = loginAsTeacher();
        var existing = new QuestionDto(1, "Câu cũ?", "A", "B", "C", "D", 'A', "Ngữ pháp", Difficulty.EASY, true, 2, Instant.now());
        fx(() -> { navigator.show(Screen.TEACHER_QUESTIONS); return null; });
        var listMsg = readUntil(peer, MessageType.LIST_QUESTIONS);
        peer.success(listMsg, new java.util.ArrayList<>(List.of(existing)));
        awaitQuestionsReady();

        fx(() -> {
            questionTable().getSelectionModel().select(0);
            button("editSelected").fire();
            return null;
        });
        fx(() -> {
            textArea("questionContent").setText("Câu mới?");
            button("updateQuestion").fire();
            return null;
        });
        var update = peer.read();
        assertEquals(MessageType.UPDATE_QUESTION, update.type());
        var dto = (UpdateQuestionDto) update.payload();
        assertEquals(1L, dto.id());
        assertEquals("Câu mới?", dto.content());
    }

    @Test
    void deactivateQuestionSendsCorrectPayload() throws Exception {
        var peer = loginAsTeacher();
        var existing = new QuestionDto(1, "Câu hỏi?", "A", "B", "C", "D", 'A', "Ngữ pháp", Difficulty.EASY, true, 2, Instant.now());
        fx(() -> { navigator.show(Screen.TEACHER_QUESTIONS); return null; });
        var listMsg = readUntil(peer, MessageType.LIST_QUESTIONS);
        peer.success(listMsg, new java.util.ArrayList<>(List.of(existing)));
        awaitQuestionsReady();

        fx(() -> {
            questionTable().getSelectionModel().select(0);
            button("deactivateQuestion").fire();
            return null;
        });
        var request = peer.read();
        assertEquals(MessageType.DEACTIVATE_QUESTION, request.type());
        assertEquals(1L, request.payload());
        peer.success(request, null);
        fx(() -> {
            assertTrue(label("questionNotice").getText().contains("Đã vô hiệu hóa"));
            return null;
        });
    }

    @Test
    void filterByTopicFiltersQuestions() throws Exception {
        var peer = loginAsTeacher();
        var questions = List.of(
            new QuestionDto(1, "Câu ngữ pháp?", "A", "B", "C", "D", 'A', "Ngữ pháp", Difficulty.EASY, true, 2, Instant.now()),
            new QuestionDto(2, "Câu từ vựng?", "A", "B", "C", "D", 'B', "Từ vựng", Difficulty.HARD, true, 2, Instant.now()),
            new QuestionDto(3, "Câu ngữ pháp khác?", "A", "B", "C", "D", 'C', "Ngữ pháp", Difficulty.MEDIUM, true, 2, Instant.now())
        );
        fx(() -> { navigator.show(Screen.TEACHER_QUESTIONS); return null; });
        var listMsg = readUntil(peer, MessageType.LIST_QUESTIONS);
        peer.success(listMsg, new java.util.ArrayList<>(questions));
        awaitQuestionsReady();

        fx(() -> {
            text("filterTopic").setText("Ngữ pháp");
            button("filterApply").fire();
            return null;
        });
        awaitQuestionsReady();
        fx(() -> {
            assertEquals(2, questionTable().getItems().size());
            assertTrue(questionTable().getItems().stream().allMatch(q -> q.topic().equals("Ngữ pháp")));
            return null;
        });
    }

    @Test
    void filterByDifficultyFiltersQuestions() throws Exception {
        var peer = loginAsTeacher();
        var questions = List.of(
            new QuestionDto(1, "Câu dễ?", "A", "B", "C", "D", 'A', "Ngữ pháp", Difficulty.EASY, true, 2, Instant.now()),
            new QuestionDto(2, "Câu trung bình?", "A", "B", "C", "D", 'B', "Từ vựng", Difficulty.MEDIUM, true, 2, Instant.now()),
            new QuestionDto(3, "Câu khó?", "A", "B", "C", "D", 'C', "Đọc hiểu", Difficulty.HARD, true, 2, Instant.now())
        );
        fx(() -> { navigator.show(Screen.TEACHER_QUESTIONS); return null; });
        var listMsg = readUntil(peer, MessageType.LIST_QUESTIONS);
        peer.success(listMsg, new java.util.ArrayList<>(questions));
        awaitQuestionsReady();

        fx(() -> {
            combo("filterDifficulty").setValue(Difficulty.HARD);
            button("filterApply").fire();
            return null;
        });
        awaitQuestionsReady();
        fx(() -> {
            assertEquals(1, questionTable().getItems().size());
            assertEquals(Difficulty.HARD, questionTable().getItems().get(0).difficulty());
            return null;
        });
    }

    @Test
    void filterByTopicAndDifficultyTogether() throws Exception {
        var peer = loginAsTeacher();
        var questions = List.of(
            new QuestionDto(1, "Câu ngữ pháp dễ?", "A", "B", "C", "D", 'A', "Ngữ pháp", Difficulty.EASY, true, 2, Instant.now()),
            new QuestionDto(2, "Câu ngữ pháp khó?", "A", "B", "C", "D", 'B', "Ngữ pháp", Difficulty.HARD, true, 2, Instant.now()),
            new QuestionDto(3, "Câu từ vựng dễ?", "A", "B", "C", "D", 'A', "Từ vựng", Difficulty.EASY, true, 2, Instant.now())
        );
        fx(() -> { navigator.show(Screen.TEACHER_QUESTIONS); return null; });
        var listMsg = readUntil(peer, MessageType.LIST_QUESTIONS);
        peer.success(listMsg, new java.util.ArrayList<>(questions));
        awaitQuestionsReady();

        fx(() -> {
            text("filterTopic").setText("Ngữ pháp");
            combo("filterDifficulty").setValue(Difficulty.EASY);
            button("filterApply").fire();
            return null;
        });
        awaitQuestionsReady();
        fx(() -> {
            assertEquals(1, questionTable().getItems().size());
            assertEquals("Ngữ pháp", questionTable().getItems().get(0).topic());
            assertEquals(Difficulty.EASY, questionTable().getItems().get(0).difficulty());
            return null;
        });
    }

    @Test
    void invalidQuestionFormSendsNoRequest() throws Exception {
        var peer = loginAsTeacher();
        fx(() -> { navigator.show(Screen.TEACHER_QUESTIONS); return null; });
        var listMsg = readUntil(peer, MessageType.LIST_QUESTIONS);
        peer.success(listMsg, new java.util.ArrayList<QuestionDto>());

        fx(() -> {
            button("newQuestion").fire();
            textArea("questionContent").setText(" ");
            text("questionOptionA").setText("A");
            text("questionOptionB").setText("B");
            text("questionOptionC").setText("C");
            text("questionOptionD").setText("D");
            button("createQuestion").fire();
            return null;
        });
        assertTrue(label("formError").isVisible());
        assertFalse(label("formError").getText().contains("SQL"));
        Thread.sleep(200);
    }

    @Test
    void studentCannotAccessQuestionBank() throws Exception {
        var peer = loginAsStudent();
        fx(() -> {
            navigator.show(Screen.TEACHER_QUESTIONS);
            assertEquals(Screen.STUDENT_DASHBOARD, navigator.currentScreen().orElseThrow());
            return null;
        });
    }

    // ---- helpers ----

    private SocketFixture.Peer loginAsTeacher() throws Exception {
        return login(Role.TEACHER);
    }

    private SocketFixture.Peer loginAsStudent() throws Exception {
        return login(Role.STUDENT);
    }

    private SocketFixture.Peer login(Role role) throws Exception {
        fx(() -> { navigator.showLogin(""); stage.show(); fillAndSubmit("  fixture  ", "fixture-password"); return null; });
        var peer = server.take();
        Request request = peer.read();
        assertEquals(MessageType.LOGIN, request.type());
        assertNull(request.token());
        peer.success(request, new quiz.common.dto.LoginResultDto("fixture-token-" + role, 1, "fixture", "Nguyễn Việt — phiên kiểm thử", role));
        awaitScreen(Screen.home(role));
        return peer;
    }

    /** Read messages until we get the expected type, responding to all others. */
    private Request readUntil(SocketFixture.Peer peer, MessageType expected) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < until) {
            try {
                var msg = peer.read();
                if (msg.type() == expected) {
                    return msg;
                }
                peer.success(msg, new java.util.ArrayList<>());
            } catch (Exception e) {
                break;
            }
        }
        throw new AssertionError("Expected message " + expected + " but timed out waiting");
    }

    private void fillAndSubmit(String user, String pass) {
        stage.getScene().getRoot().applyCss(); stage.getScene().getRoot().layout();
        ((TextField) stage.getScene().lookup("#usernameInput")).setText(user);
        ((PasswordField) stage.getScene().lookup("#passwordInput")).setText(pass);
        button("loginButton").fire();
    }

    private void awaitQuestionsReady() throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (!fx(this::questionsReady) && System.nanoTime() < until) Thread.sleep(10);
        assertTrue(fx(this::questionsReady));
    }

    private boolean questionsReady() {
        stage.getScene().getRoot().applyCss();
        return navigator.currentScreen().orElse(null) == Screen.TEACHER_QUESTIONS
            && !stage.getScene().lookup("#questionProgress").isVisible();
    }

    private @SuppressWarnings("unchecked") TableView<QuestionDto> questionTable() {
        stage.getScene().getRoot().applyCss();
        return (TableView<QuestionDto>) stage.getScene().lookup("#questionTable");
    }

    private TextArea textArea(String id) { return (TextArea) stage.getScene().lookup("#" + id); }
    private TextField text(String id) { return (TextField) stage.getScene().lookup("#" + id); }
    private <T> ComboBox<T> combo(String id) { return (ComboBox<T>) stage.getScene().lookup("#" + id); }
    private Button button(String id) { stage.getScene().getRoot().applyCss(); return (Button) stage.getScene().lookup("#" + id); }
    private Label label(String id) { stage.getScene().getRoot().applyCss(); return (Label) stage.getScene().lookup("#" + id); }
    private boolean questionFormVisible() { return stage.getScene().lookup("#questionForm").isVisible(); }

    private void awaitScreen(Screen expected) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (!fx(() -> navigator.currentScreen().orElse(null) == expected) && System.nanoTime() < until) {
            Thread.sleep(10);
        }
        assertEquals(expected, fx(() -> navigator.currentScreen().orElse(null)));
    }

    private static <T> T fx(Callable<T> task) throws Exception {
        var future = new FutureTask<>(task); Platform.runLater(future);
        return future.get(5, TimeUnit.SECONDS);
    }
}
