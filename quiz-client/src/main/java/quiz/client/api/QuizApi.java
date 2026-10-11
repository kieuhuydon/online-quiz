package quiz.client.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quiz.client.network.ServerConnection;
import quiz.client.network.TransportException;
import quiz.client.session.ClientSession;
import quiz.client.util.FxAsync;
import quiz.common.dto.*;
import quiz.common.protocol.*;

import java.io.Serializable;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;
import static quiz.client.network.TransportException.Kind.*;

/** Five asynchronous Auth/Account operations. Connect explicitly before login; never retries requests. */
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

    public ClientSession session() { return session; }

    /** Recover from LOGIN conflict without silently logging out an authenticated user. */
    public void resetLoginConnection() {
        synchronized (lock) {
            ensureAvailable();
            if (session.current().isPresent()) throw new ApiException(ErrorCode.CONFLICT);
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
        synchronized (lock) { return !disposed && event.revision() == revision && session.current().isEmpty(); }
    }

    public CompletableFuture<LoginResultDto> login(String username, String password) {
        synchronized (lock) {
            try {
                ensureAvailable();
                if (session.current().isPresent() || transitionActive()) throw new ApiException(ErrorCode.CONFLICT);
                LoginRequestDto dto = ClientValidation.login(username, password);
                long generation = connection.connectedGeneration();
                if (generation == 0) throw new TransportException(CONNECTION_LOST);
                transitioningGeneration = generation;
                long operation = ++revision;
                return exchange(generation, operation, MessageType.LOGIN, null, dto, payload -> {
                    if (!(payload instanceof LoginResultDto login)) throw new TransportException(PROTOCOL);
                    try { session.establish(generation, login); }
                    catch (IllegalArgumentException invalid) { throw new TransportException(PROTOCOL); }
                    sessionRevision = operation;
                    return login;
                }, true);
            } catch (RuntimeException failure) { return CompletableFuture.failedFuture(failure); }
        }
    }

    public CompletableFuture<Void> logout() {
        synchronized (lock) {
            try {
                ClientSession.Snapshot snapshot = authenticated();
                transitioningGeneration = snapshot.generation();
                long operation = ++revision;
                return exchange(snapshot.generation(), operation, MessageType.LOGOUT, snapshot.token(), null,
                    payload -> { requireNull(payload); session.clear(snapshot.generation()); return null; }, true);
            } catch (RuntimeException failure) { return CompletableFuture.failedFuture(failure); }
        }
    }

    public CompletableFuture<AccountDto> createAccount(CreateAccountDto dto) {
        try { return protectedCall(MessageType.CREATE_ACCOUNT, ClientValidation.account(dto), QuizApi::account); }
        catch (RuntimeException failure) { return CompletableFuture.failedFuture(failure); }
    }

    public CompletableFuture<List<AccountDto>> listAccounts() {
        return protectedCall(MessageType.LIST_ACCOUNTS, null, payload -> {
            if (!(payload instanceof List<?> items)) throw new TransportException(PROTOCOL);
            List<AccountDto> accounts = new ArrayList<>(items.size());
            for (Object item : items) accounts.add(account(item));
            return List.copyOf(accounts);
        });
    }

    public CompletableFuture<Void> setAccountActive(long userId, boolean active) {
        try {
            ClientValidation.positiveId(userId);
            return protectedCall(MessageType.SET_ACCOUNT_ACTIVE, new SetAccountActiveDto(userId, active),
                payload -> { requireNull(payload); return null; });
        } catch (RuntimeException failure) { return CompletableFuture.failedFuture(failure); }
    }

    private <T> CompletableFuture<T> protectedCall(MessageType type, Serializable payload, Function<Object, T> decoder) {
        synchronized (lock) {
            try {
                ClientSession.Snapshot snapshot = authenticated();
                return exchange(snapshot.generation(), revision, type, snapshot.token(), payload, decoder, false);
            } catch (RuntimeException failure) { return CompletableFuture.failedFuture(failure); }
        }
    }

    private <T> CompletableFuture<T> exchange(long generation, long operation, MessageType type, String token,
                                             Serializable payload, Function<Object, T> decoder, boolean auth) {
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
                        if (error != null) throw asRuntime(error);
                        value = connection.whileConnected(generation, () -> {
                            if (!envelope.success()) throw new ApiException(envelope.errorCode());
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
                    if (auth) transitioningGeneration = 0;
                }
            }
            if (error == null) result.complete(value); else result.completeExceptionally(error);
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
        if (transitionActive()) throw new ApiException(ErrorCode.CONFLICT);
        return session.current().orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
    }

    private boolean transitionActive() {
        return transitioningGeneration != 0 && connection.isConnected(transitioningGeneration);
    }

    private void ensureAvailable() { if (disposed) throw new TransportException(CLOSED); }

    private void disconnected(long generation, TransportException failure) {
        boolean lost;
        SessionLost event;
        synchronized (lock) {
            lost = session.clear(generation);
            if (transitioningGeneration == generation) transitioningGeneration = 0;
            event = new SessionLost(generation, sessionRevision, failure);
        }
        if (lost) for (Consumer<SessionLost> listener : sessionLostListeners) {
            Thread.ofVirtual().name("quiz-client-session-event").start(() -> {
                if (!isCurrent(event) || !sessionLostListeners.contains(listener)) return;
                try { listener.accept(event); }
                catch (RuntimeException failed) { log.warn("Session listener failed: {}", failed.getClass().getSimpleName()); }
            });
        }
    }

    private static boolean invalidatesConnection(RuntimeException failure) {
        return failure instanceof TransportException transport && transport.kind() != OVERLOADED
            || failure instanceof ApiException api && api.errorCode() == ErrorCode.UNAUTHENTICATED;
    }

    private static RuntimeException asRuntime(Throwable failure) {
        return failure instanceof RuntimeException runtime ? runtime : new TransportException(CONNECTION_LOST);
    }

    private static AccountDto account(Object payload) {
        if (!(payload instanceof AccountDto dto) || dto.id() <= 0 || dto.username() == null || dto.username().isBlank()
            || dto.fullName() == null || dto.fullName().isBlank() || dto.role() == null || dto.createdAt() == null
            || (dto.createdBy() != null && dto.createdBy() <= 0)) throw new TransportException(PROTOCOL);
        return dto;
    }

    private static void requireNull(Object payload) { if (payload != null) throw new TransportException(PROTOCOL); }

    /** Disposes the API and its owned connection; application shutdown need not send LOGOUT. */
    @Override public void close() { closeAsync(); }

    public CompletableFuture<Void> closeAsync() {
        synchronized (lock) {
            if (disposed) return connection.closeAsync();
            disposed = true;
            revision++;
            session.clear();
            sessionLostListeners.clear();
            try { disconnectSubscription.close(); }
            catch (Exception impossible) { throw new IllegalStateException("Cannot remove disconnect listener", impossible); }
            return connection.closeAsync();
        }
    }
}
