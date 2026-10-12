package quiz.client;

import javafx.application.Application;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quiz.client.api.QuizApi;
import quiz.client.config.ClientConfig;
import quiz.client.network.ServerConnection;
import quiz.client.ui.SceneNavigator;
import quiz.client.ui.StartupErrorView;
import quiz.client.util.FxAsync;

/** JavaFX composition root. One API/connection per application, owned until stop(). */
public final class ClientMain extends Application {
    private static final Logger log = LoggerFactory.getLogger(ClientMain.class);
    private QuizApi api;
    private SceneNavigator navigator;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        try {
            ClientConfig config = ClientConfig.fromArgs(getParameters().getRaw().toArray(String[]::new));
            api = new QuizApi(new ServerConnection(config));
            navigator = new SceneNavigator(stage, api, new FxAsync());
            navigator.showLogin("");
            stage.show();
        } catch (Exception failed) {
            stop();
            log.error("Client startup failed: {}", failed.getClass().getSimpleName());
            StartupErrorView.show(stage, "Không thể khởi động ứng dụng",
                "Kiểm tra tệp cấu hình client và tham số --config, sau đó mở lại ứng dụng.");
        }
    }

    /** No network wait or synchronous logout on the JavaFX thread. */
    @Override
    public void stop() {
        if (navigator != null) {
            navigator.close();
        }
        if (api != null) {
            api.closeAsync().whenComplete((ignored, failure) -> {
                if (failure != null) {
                    log.warn("Client cleanup did not finish within its shutdown budget");
                }
            });
        }
    }
}
