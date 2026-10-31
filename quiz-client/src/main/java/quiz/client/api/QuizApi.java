package quiz.client.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quiz.client.network.ServerConnection;
import quiz.client.network.TransportException;
import quiz.client.network.PushListener;
import quiz.client.session.ClientSession;
import quiz.client.util.FxAsync;
import quiz.common.dto.AccountDto;
import quiz.common.dto.CreateAccountDto;
import quiz.common.dto.CreateQuestionDto;
import quiz.common.dto.LoginRequestDto;
import quiz.common.dto.LoginResultDto;
import quiz.common.dto.QuestionDto;
import quiz.common.dto.QuizDto;
import quiz.common.dto.QuizQuestionsDto;
import quiz.common.dto.SetAccountActiveDto;
import quiz.common.dto.UpdateQuestionDto;
import quiz.common.dto.*;
import quiz.common.protocol.ErrorCode;
import quiz.common.protocol.MessageType;
import quiz.common.protocol.Response;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import static quiz.client.network.TransportException.Kind.CLOSED;
import static quiz.client.network.TransportException.Kind.CONNECTION_LOST;
import static quiz.client.network.TransportException.Kind.OVERLOADED;
import static quiz.client.network.TransportException.Kind.PROTOCOL;

/** Connect explicitly before login. Requests are asynchronous and are never retried automatically. */
public final class QuizApi implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(QuizApi.class);
    public record SessionLost(long generation, long revision, TransportException cause) { }
    private final ServerConnection connection;
    private final ClientSession session;
    private final Object lock = new Object();
    private final List<Consumer<SessionLost>> sessionLostListeners = new CopyOnWriteArrayList<>();
    private final AutoCloseable disconnectSubscription;
    private long revision;
    private long sessionRevision;
    private long transitioningGeneration;
    private boolean disposed;
    private final ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(
        Thread.ofPlatform().daemon().name("quiz-client-heartbeat").factory());
    private ScheduledFuture<?> heartbeatTask;
    private boolean heartbeatInFlight;

    public QuizApi(ServerConnection connection) {
        this.connection = Objects.requireNonNull(connection);
        session = new ClientSession(connection::isConnected);
        disconnectSubscription = connection.addDisconnectListener(this::disconnected);
    }

    public CompletableFuture<Long> connect() {
        synchronized (lock) {
            return disposed ? CompletableFuture.failedFuture(new TransportException(CLOSED)) : connection.connect();
        }
    }

    public ClientSession session() {
        return session;
    }

    public AutoCloseable addPushListener(PushListener listener) {
        return connection.addPushListener(listener);
    }

    /** Recover from LOGIN conflict without silently logging out an authenticated user. */
    public void resetLoginConnection() {
        synchronized (lock) {
            ensureAvailable();
            if (session.current().isPresent()) {
                throw new ApiException(ErrorCode.CONFLICT);
            }
            revision++;
            transitioningGeneration = 0;
            connection.disconnect(connection.connectedGeneration(), new TransportException(CONNECTION_LOST));
        }
    }

    /** Dispatch onto JavaFX, then check isCurrent(event) before showing a dialog or changing scenes. */
    public AutoCloseable addSessionLostListener(Consumer<SessionLost> listener) {
        sessionLostListeners.add(Objects.requireNonNull(listener));
        return () -> sessionLostListeners.remove(listener);
    }

    public boolean isCurrent(SessionLost event) {
        synchronized (lock) {
            return !disposed && event.revision() == revision && session.current().isEmpty();
        }
    }

    public CompletableFuture<LoginResultDto> login(String username, String password) {
        synchronized (lock) {
            try {
                ensureAvailable();
                if (session.current().isPresent() || transitionActive()) {
                    throw new ApiException(ErrorCode.CONFLICT);
                }
                LoginRequestDto dto = ClientValidation.login(username, password);
                long generation = connection.connectedGeneration();
                if (generation == 0) {
                    throw new TransportException(CONNECTION_LOST);
                }
                transitioningGeneration = generation;
                long operation = ++revision;
                return exchange(generation, operation, MessageType.LOGIN, null, dto,
                    payload -> establishSession(generation, operation, payload), true);
            } catch (RuntimeException failure) {
                return CompletableFuture.failedFuture(failure);
            }
        }
    }

    private LoginResultDto establishSession(long generation, long operation, Object payload) {
        if (!(payload instanceof LoginResultDto login)) {
            throw new TransportException(PROTOCOL);
        }
        try {
            session.establish(generation, login);
        } catch (IllegalArgumentException invalid) {
            throw new TransportException(PROTOCOL);
        }
        sessionRevision = operation;
        startHeartbeat();
        return login;
    }

    private void startHeartbeat() {
        stopHeartbeat();
        long interval = connection.heartbeatIntervalMs();
        heartbeatTask = heartbeatExecutor.scheduleWithFixedDelay(this::heartbeat, interval, interval, TimeUnit.MILLISECONDS);
    }

    private void stopHeartbeat() {
        if (heartbeatTask != null) {
            heartbeatTask.cancel(false);
            heartbeatTask = null;
        }
        heartbeatInFlight = false;
    }

    /** Runs on a daemon, with at most one heartbeat pending for the current session. */
    private void heartbeat() {
        synchronized (lock) {
            if (disposed || transitionActive() || session.current().isEmpty() || heartbeatInFlight) return;
            long operation = sessionRevision;
            heartbeatInFlight = true;
            protectedCall(MessageType.HEARTBEAT, null, payload -> {
                requireNull(payload);
                return null;
            }).whenComplete((ignored, failure) -> {
                synchronized (lock) {
                    if (operation == sessionRevision) heartbeatInFlight = false;
                }
            });
        }
    }

    public CompletableFuture<Void> logout() {
        synchronized (lock) {
            try {
                ClientSession.Snapshot snapshot = authenticated();
                stopHeartbeat();
                transitioningGeneration = snapshot.generation();
                long operation = ++revision;
                return exchange(snapshot.generation(), operation, MessageType.LOGOUT, snapshot.token(), null,
                    payload -> {
                        requireNull(payload);
                        session.clear(snapshot.generation());
                        return null;
                    }, true);
            } catch (RuntimeException failure) {
                return CompletableFuture.failedFuture(failure);
            }
        }
    }

    public CompletableFuture<AccountDto> createAccount(CreateAccountDto dto) {
        try {
            return protectedCall(MessageType.CREATE_ACCOUNT, ClientValidation.account(dto), QuizApi::decodeAccount);
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    public CompletableFuture<List<AccountDto>> listAccounts() {
        return protectedCall(MessageType.LIST_ACCOUNTS, null, QuizApi::decodeAccounts);
    }

    public CompletableFuture<Void> setAccountActive(long userId, boolean active) {
        try {
            ClientValidation.positiveId(userId);
            return protectedCall(MessageType.SET_ACCOUNT_ACTIVE, new SetAccountActiveDto(userId, active),
                payload -> {
                    requireNull(payload);
                    return null;
                });
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    public CompletableFuture<QuestionDto> createQuestion(CreateQuestionDto dto) {
        try {
            return protectedCall(MessageType.CREATE_QUESTION, ClientValidation.question(dto),
                QuizApi::decodeQuestion);
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    public CompletableFuture<QuestionDto> getQuestion(long questionId) {
        ClientValidation.positiveId(questionId);
        return protectedCall(MessageType.GET_QUESTION, questionId, QuizApi::decodeQuestion);
    }

    // --- Class Management ---
    public CompletableFuture<ClassDto> createClass(CreateClassDto dto) {
        return protectedCall(MessageType.CREATE_CLASS, dto, QuizApi::decodeClass);
    }

    public CompletableFuture<Void> deleteClass(long classId) {
        ClientValidation.positiveId(classId);
        return protectedCall(MessageType.DELETE_CLASS, classId, payload -> {
            requireNull(payload);
            return null;
        });
    }

    public CompletableFuture<List<ClassDto>> listClasses() {
        return protectedCall(MessageType.LIST_CLASSES, null, QuizApi::decodeClasses);
    }

    public CompletableFuture<List<ClassDto>> listMyClasses() {
        return protectedCall(MessageType.LIST_MY_CLASSES, null, QuizApi::decodeClasses);
    }

    public CompletableFuture<Void> addClassMember(long classId, long studentId) {
        return protectedCall(MessageType.ADD_CLASS_MEMBER, new AddClassMemberDto(classId, studentId), payload -> {
            requireNull(payload);
            return null;
        });
    }

    public CompletableFuture<Void> removeClassMember(long classId, long studentId) {
        return protectedCall(MessageType.REMOVE_CLASS_MEMBER, new AddClassMemberDto(classId, studentId), payload -> {
            requireNull(payload);
            return null;
        });
    }

    public CompletableFuture<List<AccountDto>> listUnenrolledStudents() {
        return protectedCall(MessageType.LIST_UNENROLLED_STUDENTS, null, QuizApi::decodeAccounts);
    }

    public CompletableFuture<List<ClassMemberDto>> listClassMembers(long classId) {
        return protectedCall(MessageType.LIST_CLASS_MEMBERS, classId, QuizApi::decodeClassMembers);
    }

    // --- Question Bank ---
    public CompletableFuture<List<QuestionDto>> listQuestions() {
        return protectedCall(MessageType.LIST_QUESTIONS, null, QuizApi::decodeQuestions);
    }

    public CompletableFuture<QuestionDto> updateQuestion(UpdateQuestionDto dto) {
        try {
            return protectedCall(MessageType.UPDATE_QUESTION, ClientValidation.updateQuestion(dto),
                QuizApi::decodeQuestion);
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    public CompletableFuture<Void> deactivateQuestion(long questionId) {
        ClientValidation.positiveId(questionId);
        return protectedCall(MessageType.DEACTIVATE_QUESTION, questionId,
            payload -> {
                requireNull(payload);
                return null;
            });
    }

    public CompletableFuture<Integer> deactivateQuestions(List<Long> questionIds) {
        if (questionIds == null || questionIds.isEmpty()) {
            return CompletableFuture.completedFuture(0);
        }
        questionIds.forEach(ClientValidation::positiveId);
        return protectedCall(MessageType.BATCH_DEACTIVATE_QUESTIONS, new ArrayList<>(questionIds),
            payload -> {
                if (!(payload instanceof Integer count)) {
                    throw new IllegalStateException("Unexpected response type from server");
                }
                return count;
            });
    }

    public CompletableFuture<QuizDto> setQuizQuestions(QuizQuestionsDto dto) {
        try {
            return protectedCall(MessageType.SET_QUIZ_QUESTIONS, dto, QuizApi::decodeQuiz);
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }



    // --- Quiz Management ---
    public CompletableFuture<QuizDto> createQuiz(CreateQuizDto dto) {
        return protectedCall(MessageType.CREATE_QUIZ, dto, QuizApi::decodeQuiz);
    }

    public CompletableFuture<QuizDto> updateQuiz(UpdateQuizDto dto) {
        return protectedCall(MessageType.UPDATE_QUIZ, dto, QuizApi::decodeQuiz);
    }

    public CompletableFuture<QuizDetailDto> getQuiz(long quizId) {
        return protectedCall(MessageType.GET_QUIZ, quizId, QuizApi::decodeQuizDetail);
    }

    public CompletableFuture<List<QuizDto>> listQuizzes() {
        return protectedCall(MessageType.LIST_QUIZZES, null, QuizApi::decodeQuizzes);
    }

    public CompletableFuture<Void> setQuizQuestions(long quizId, List<Long> questionIds) {
        return protectedCall(MessageType.SET_QUIZ_QUESTIONS, new QuizQuestionsDto(quizId, questionIds), payload -> {
            requireNull(payload);
            return null;
        });
    }

    public CompletableFuture<QuizDto> openQuiz(long quizId) {
        return protectedCall(MessageType.OPEN_QUIZ, quizId, QuizApi::decodeQuiz);
    }

    public CompletableFuture<QuizDto> closeQuiz(long quizId) {
        return protectedCall(MessageType.CLOSE_QUIZ, quizId, QuizApi::decodeQuiz);
    }

    public CompletableFuture<Void> assignQuiz(long quizId, long classId) {
        return protectedCall(MessageType.ASSIGN_QUIZ, new AssignQuizDto(quizId, classId), payload -> {
            requireNull(payload);
            return null;
        });
    }

    public CompletableFuture<List<ResultDto>> listQuizResults(long quizId) {
        return protectedCall(MessageType.LIST_QUIZ_RESULTS, quizId, QuizApi::decodeResults);
    }

    public CompletableFuture<ClassQuizResultsDto> getClassQuizResults(long classId) {
        ClientValidation.positiveId(classId);
        return protectedCall(MessageType.GET_CLASS_QUIZ_RESULTS, classId, payload -> {
            if (!(payload instanceof ClassQuizResultsDto dto)) throw new TransportException(PROTOCOL);
            return dto;
        });
    }

    // --- Student Quiz Flow ---
    public CompletableFuture<List<QuizInfoDto>> listAssignedQuizzes() {
        return protectedCall(MessageType.LIST_ASSIGNED_QUIZZES, null, QuizApi::decodeQuizInfos);
    }

    public CompletableFuture<QuizInfoDto> getQuizInfo(long quizId) {
        return protectedCall(MessageType.GET_QUIZ_INFO, quizId, QuizApi::decodeQuizInfo);
    }

    public CompletableFuture<AttemptStateDto> startAttempt(long quizId, String pin) {
        final long operation;
        synchronized(lock) { operation=sessionRevision; }
        return CompletableFuture.supplyAsync(() -> {
            try { return java.net.InetAddress.getLocalHost().getHostAddress(); }
            catch(java.net.UnknownHostException e) { return null; }
        }).thenCompose(ip -> {
            synchronized(lock) {
                if(operation!=sessionRevision) return CompletableFuture.failedFuture(new TransportException(CONNECTION_LOST));
                return protectedCall(MessageType.START_ATTEMPT, new StartAttemptRequestDto(quizId, pin,ip), QuizApi::decodeAttemptState);
            }
        });
    }

    public int monitoringIntervalMs() { return connection.monitoringIntervalMs(); }
    public CompletableFuture<List<ExamSession>> listExamSessions(long quizId) {
        return protectedCall(MessageType.LIST_EXAM_SESSIONS,quizId,p->typedList(p,ExamSession.class));
    }
    public CompletableFuture<ExamSession> getExamSession(long id) {
        return protectedCall(MessageType.GET_EXAM_SESSION,id,QuizApi::decodeExamSession);
    }
    public CompletableFuture<ExamSession> applyMonitoringPenalty(ApplyPenaltyDto p) {
        return protectedCall(MessageType.APPLY_MONITORING_PENALTY,p,QuizApi::decodeExamSession);
    }
    public CompletableFuture<List<MonitoringPenalty>> listMonitoringPenalties(long id) {
        return protectedCall(MessageType.LIST_MONITORING_PENALTIES,id,p->typedList(p,MonitoringPenalty.class));
    }
    private static ExamSession decodeExamSession(Object p) {
        if(!(p instanceof ExamSession s)) throw new TransportException(PROTOCOL);
        return s;
    }
    private static <T> List<T> typedList(Object p,Class<T> type) {
        if(!(p instanceof List<?> list)||list.stream().anyMatch(x->!type.isInstance(x))) throw new TransportException(PROTOCOL);
        return list.stream().map(type::cast).toList();
    }

    public CompletableFuture<Void> saveAnswer(long attemptId, long questionId, String answer) {
        return protectedCall(MessageType.SAVE_ANSWER, new SaveAnswerDto(attemptId, questionId, answer), payload -> {
            requireNull(payload); return null;
        });
    }

    public CompletableFuture<Void> saveAnswer(long attemptId, long questionId, Character option) {
        return saveAnswer(attemptId, questionId, option == null ? null : option.toString());
    }

    public CompletableFuture<ResultDto> submitAttempt(long attemptId) {
        return protectedCall(MessageType.SUBMIT_ATTEMPT, new SubmitAttemptDto(attemptId), QuizApi::decodeResult);
    }

    public CompletableFuture<AttemptStateDto> getAttemptState(long attemptId) {
        return protectedCall(MessageType.GET_ATTEMPT_STATE, attemptId, QuizApi::decodeAttemptState);
    }

    public CompletableFuture<ResultDto> getMyResult(long attemptId) {
        return protectedCall(MessageType.GET_MY_RESULT, attemptId, QuizApi::decodeResult);
    }

    public CompletableFuture<List<ResultDto>> listMyHistory() {
        return protectedCall(MessageType.LIST_MY_HISTORY, null, QuizApi::decodeResults);
    }

    // --- Anti-Cheat Monitoring Flow ---
    public CompletableFuture<Void> reportAntiCheat(ReportAntiCheatDto dto) {
        return protectedCall(MessageType.REPORT_ANTICHEAT_EVENT, dto, payload -> {
            requireNull(payload); return null;
        });
    }

    public CompletableFuture<List<AntiCheatEventDto>> listAttemptEvents(long attemptId) {
        return protectedCall(MessageType.LIST_ATTEMPT_EVENTS, attemptId, QuizApi::decodeAntiCheatEvents);
    }

    private <T> CompletableFuture<T> protectedCall(MessageType type, Serializable payload, Function<Object, T> decoder) {
        synchronized (lock) {
            try {
                ClientSession.Snapshot snapshot = authenticated();
                return exchange(snapshot.generation(), revision, type, snapshot.token(), payload, decoder, false);
            } catch (RuntimeException failure) {
                return CompletableFuture.failedFuture(failure);
            }
        }
    }

    private <T> CompletableFuture<T> exchange(long generation, long operation, MessageType type, String token,
                                             Serializable payload, Function<Object, T> decoder, boolean changesSession) {
        CompletableFuture<T> result = new CompletableFuture<>();
        CompletableFuture<Response> response = connection.send(generation, type, token, payload);
        response.whenCompleteAsync((envelope, failure) -> {
            T value = null;
            Throwable error = failure == null ? null : FxAsync.unwrap(failure);
            synchronized (lock) {
                if (operation != revision || disposed || result.isCancelled()) {
                    error = new TransportException(CONNECTION_LOST);
                } else {
                    try {
                        if (error != null) {
                            throw asRuntime(error);
                        }
                        value = connection.whileConnected(generation, () -> {
                            if (!envelope.success()) {
                                throw new ApiException(envelope.errorCode());
                            }
                            return decoder.apply(envelope.payload());
                        });
                    } catch (RuntimeException failed) {
                        error = failed;
                        if (type == MessageType.LOGOUT || invalidatesConnection(failed)) {
                            // The disconnect event clears/notifies this generation, even if its future fails first.
                            connection.disconnect(generation, new TransportException(
                                failed instanceof TransportException transport ? transport.kind() : CONNECTION_LOST));
                        }
                    }
                    if (changesSession) {
                        transitioningGeneration = 0;
                    }
                }
            }
            if (error == null) {
                result.complete(value);
            } else {
                result.completeExceptionally(error);
            }
        });
        result.whenComplete((value, error) -> {
            if (result.isCancelled()) {
                // Cancelling an API request cannot undo a server write or an in-flight authentication.
                connection.disconnect(generation, new TransportException(CONNECTION_LOST));
                response.cancel(false);
            }
        });
        return result;
    }

    private ClientSession.Snapshot authenticated() {
        ensureAvailable();
        if (transitionActive()) {
            throw new ApiException(ErrorCode.CONFLICT);
        }
        return session.current().orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
    }

    private boolean transitionActive() {
        return transitioningGeneration != 0 && connection.isConnected(transitioningGeneration);
    }

    private void ensureAvailable() {
        if (disposed) {
            throw new TransportException(CLOSED);
        }
    }

    private void disconnected(long generation, TransportException failure) {
        boolean lost;
        SessionLost event;
        synchronized (lock) {
            lost = session.clear(generation);
            if (lost) stopHeartbeat();
            if (transitioningGeneration == generation) {
                transitioningGeneration = 0;
            }
            event = new SessionLost(generation, sessionRevision, failure);
        }
        if (lost) {
            for (Consumer<SessionLost> listener : sessionLostListeners) {
                Thread.ofVirtual().name("quiz-client-session-event").start(() -> notifySessionLost(listener, event));
            }
        }
    }

    private void notifySessionLost(Consumer<SessionLost> listener, SessionLost event) {
        if (!isCurrent(event) || !sessionLostListeners.contains(listener)) {
            return;
        }
        try {
            listener.accept(event);
        } catch (RuntimeException failure) {
            log.warn("Session listener failed: {}", failure.getClass().getSimpleName());
        }
    }

    private static boolean invalidatesConnection(RuntimeException failure) {
        return failure instanceof TransportException transport && transport.kind() != OVERLOADED
            || failure instanceof ApiException api && api.errorCode() == ErrorCode.UNAUTHENTICATED;
    }

    private static RuntimeException asRuntime(Throwable failure) {
        return failure instanceof RuntimeException runtime ? runtime : new TransportException(CONNECTION_LOST);
    }

    private static List<AccountDto> decodeAccounts(Object payload) {
        if (!(payload instanceof List<?> items)) {
            throw new TransportException(PROTOCOL);
        }
        List<AccountDto> accounts = new ArrayList<>(items.size());
        for (Object item : items) {
            accounts.add(decodeAccount(item));
        }
        return List.copyOf(accounts);
    }

    private static AccountDto decodeAccount(Object payload) {
        if (!(payload instanceof AccountDto dto) || dto.id() <= 0 || dto.username() == null || dto.username().isBlank()
            || dto.fullName() == null || dto.fullName().isBlank() || dto.role() == null || dto.createdAt() == null
            || (dto.createdBy() != null && dto.createdBy() <= 0)) {
            throw new TransportException(PROTOCOL);
        }
        return dto;
    }

    private static void requireNull(Object payload) {
        if (payload != null) {
            throw new TransportException(PROTOCOL);
        }
    }


    @SuppressWarnings("unchecked")
    private static ClassDto decodeClass(Object payload) {
        if (!(payload instanceof ClassDto dto)) throw new TransportException(PROTOCOL);
        return dto;
    }

    @SuppressWarnings("unchecked")
    private static List<ClassDto> decodeClasses(Object payload) {
        if (!(payload instanceof List<?> list)) throw new TransportException(PROTOCOL);
        return List.copyOf((List<ClassDto>) payload);
    }

    @SuppressWarnings("unchecked")
    private static List<ClassMemberDto> decodeClassMembers(Object payload) {
        if (!(payload instanceof List<?> list)) throw new TransportException(PROTOCOL);
        return List.copyOf((List<ClassMemberDto>) payload);
    }

    @SuppressWarnings("unchecked")
    private static List<QuestionDto> decodeQuestions(Object payload) {
        if (!(payload instanceof List<?> list)) throw new TransportException(PROTOCOL);
        return List.copyOf((List<QuestionDto>) payload);
    }

    private static QuestionDto decodeQuestion(Object payload) {
        if (!(payload instanceof QuestionDto dto)) throw new TransportException(PROTOCOL);
        return dto;
    }

    private static QuizDto decodeQuiz(Object payload) {
        if (!(payload instanceof QuizDto dto)) throw new TransportException(PROTOCOL);
        return dto;
    }

    private static QuizDetailDto decodeQuizDetail(Object payload) {
        if (!(payload instanceof QuizDetailDto dto)) throw new TransportException(PROTOCOL);
        return dto;
    }

    @SuppressWarnings("unchecked")
    private static List<QuizDto> decodeQuizzes(Object payload) {
        if (!(payload instanceof List<?> list)) throw new TransportException(PROTOCOL);
        return List.copyOf((List<QuizDto>) payload);
    }

    @SuppressWarnings("unchecked")
    private static List<QuizInfoDto> decodeQuizInfos(Object payload) {
        if (!(payload instanceof List<?> list)) throw new TransportException(PROTOCOL);
        return List.copyOf((List<QuizInfoDto>) payload);
    }

    private static QuizInfoDto decodeQuizInfo(Object payload) {
        if (!(payload instanceof QuizInfoDto dto)) throw new TransportException(PROTOCOL);
        return dto;
    }

    private static AttemptStateDto decodeAttemptState(Object payload) {
        if (!(payload instanceof AttemptStateDto dto)) throw new TransportException(PROTOCOL);
        return dto;
    }

    private static ResultDto decodeResult(Object payload) {
        if (!(payload instanceof ResultDto dto)) throw new TransportException(PROTOCOL);
        return dto;
    }

    @SuppressWarnings("unchecked")
    private static List<ResultDto> decodeResults(Object payload) {
        if (!(payload instanceof List<?> list)) throw new TransportException(PROTOCOL);
        return List.copyOf((List<ResultDto>) payload);
    }

    @SuppressWarnings("unchecked")
    private static List<AntiCheatEventDto> decodeAntiCheatEvents(Object payload) {
        if (!(payload instanceof List<?> list)) throw new TransportException(PROTOCOL);
        return List.copyOf((List<AntiCheatEventDto>) payload);
    }

    /** Disposes the API and its owned connection; application shutdown need not send LOGOUT. */
    @Override
    public void close() {
        closeAsync();
    }

    public CompletableFuture<Void> closeAsync() {
        synchronized (lock) {
            if (disposed) {
                return connection.closeAsync();
            }
            disposed = true;
            stopHeartbeat();
            heartbeatExecutor.shutdownNow();
            revision++;
            session.clear();
            sessionLostListeners.clear();
            try {
                disconnectSubscription.close();
            } catch (Exception failure) {
                throw new IllegalStateException("Cannot remove disconnect listener", failure);
            }
            return connection.closeAsync();
        }
    }
}
