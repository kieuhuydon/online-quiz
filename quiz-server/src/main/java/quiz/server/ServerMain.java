package quiz.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quiz.server.config.ServerConfig;
import quiz.server.db.DataSourceProvider;
import quiz.server.db.TransactionManager;
import quiz.server.dispatch.AuthGuard;
import quiz.server.dispatch.RequestDispatcher;
import quiz.server.dispatch.handlers.*;
import quiz.server.network.*;
import quiz.server.repository.jdbc.*;
import quiz.server.service.*;

import java.nio.file.Path;
import java.util.List;

/** Application entry point. Database schema and fixtures are provisioned separately. */
public final class ServerMain {
    private static final Logger log = LoggerFactory.getLogger(ServerMain.class);

    private ServerMain() { }

    public static ServerConfig loadConfig(String[] args) throws java.io.IOException {
        if (args.length == 0) return ServerConfig.load();
        if (args.length != 2 || !"--config".equals(args[0])) {
            throw new IllegalArgumentException("Usage: ServerMain [--config <path>]");
        }
        return ServerConfig.load(Path.of(args[1]));
    }

    public static QuizServer start(ServerConfig config) throws java.io.IOException {
        try {
            DataSourceProvider.init(config);
            JdbcUserRepository users = new JdbcUserRepository(DataSourceProvider.getDataSource());
            // Executes a repository query even when there are no accounts: pool creation is insufficient.
            users.findByUsername("bootstrap-readiness-probe");
            JdbcClassRepository classes = new JdbcClassRepository(DataSourceProvider.getDataSource());
            JdbcQuestionRepository questions = new JdbcQuestionRepository(DataSourceProvider.getDataSource());
            JdbcQuizRepository quizzes = new JdbcQuizRepository(DataSourceProvider.getDataSource());
            JdbcAttemptRepository attempts = new JdbcAttemptRepository(DataSourceProvider.getDataSource());
            ClassService classService = new ClassService(classes, users);
            QuestionService questionService = new QuestionService(questions, users,
                new TransactionManager(DataSourceProvider.getDataSource()));
            QuizService quizService = new QuizService(quizzes, questions, users,
                new TransactionManager(DataSourceProvider.getDataSource()), classes);
            AttemptService attemptService = new AttemptService(attempts, quizzes, questions, users,
                new TransactionManager(DataSourceProvider.getDataSource()), new GradingService());
            SessionRegistry sessions = new SessionRegistry();
            ConnectionRegistry connections = new ConnectionRegistry();
            SessionCoordinator coordinator = new SessionCoordinator(sessions);
            AuthService auth = new AuthService(users, coordinator);
            AccountService accounts = new AccountService(users, new TransactionManager(DataSourceProvider.getDataSource()), coordinator);
            RequestDispatcher dispatcher = new RequestDispatcher(new AuthGuard(auth),
                List.of(
                    new LoginHandler(auth),
                    new AccountHandler(accounts),
                    new ClassHandler(classService),
                    new QuestionHandler(questionService),
                    new QuizHandler(quizService),
                    new AttemptHandler(attemptService),
                    new ResultHandler(attemptService)
                ));
            return new QuizServer(config.getPort(), config, dispatcher,
                new SessionLifecycle(coordinator, connections), sessions, connections,
                attemptService::finalizeExpiredAttempts);
        } catch (Exception failure) {
            DataSourceProvider.close();
            throw failure;
        }
    }

    public static void main(String[] args) {
        QuizServer server = null;
        Thread hook = null;
        try {
            server = start(loadConfig(args));
            QuizServer running = server;
            hook = new Thread(() -> {
                running.close();
                DataSourceProvider.close();
            }, "quiz-shutdown");
            Runtime.getRuntime().addShutdownHook(hook);
            log.info("Quiz server listening on port {}", server.getPort());
            server.awaitTermination();
        } catch (Exception failure) {
            // Configuration/JDBC messages may contain credentials; only log the exception type.
            log.error("Server startup/runtime failed exception={}", failure.getClass().getName());
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("Server failed; check configuration, database schema/access and bind port");
        } finally {
            if (server != null) server.close();
            DataSourceProvider.close();
            if (hook != null) {
                try { Runtime.getRuntime().removeShutdownHook(hook); }
                catch (IllegalStateException shutdownInProgress) { }
            }
        }
    }
}
