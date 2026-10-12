package quiz.server.network;

import quiz.common.dto.LoginResultDto;
import quiz.server.exception.UnauthorizedException;
import quiz.server.service.SessionCoordinator;

import java.util.Objects;

/** Coordinates token ownership with the connection; never writes while holding a user lock. */
public final class SessionLifecycle {
    private final SessionCoordinator coordinator;
    private final ConnectionRegistry connections;

    public SessionLifecycle(SessionCoordinator coordinator, ConnectionRegistry connections) {
        this.coordinator = Objects.requireNonNull(coordinator);
        this.connections = Objects.requireNonNull(connections);
    }

    public boolean bind(ClientConnection connection, LoginResultDto login) {
        boolean bound = coordinator.withUserLock(login.userId(), () -> {
            try {
                return coordinator.withCurrentSession(login.token(), session -> {
                    synchronized (connection) {
                        if (connection.isClosed()) {
                            return false;
                        }
                        ClientConnection previous = connections.get(session.userId()).orElse(null);
                        connection.setToken(login.token());
                        connections.register(session.userId(), connection);
                        if (previous != null && previous != connection) {
                            previous.close();
                        }
                        return true;
                    }
                });
            } catch (UnauthorizedException expired) {
                return false;
            }
        });
        if (!bound) {
            coordinator.logout(login.token());
        }
        return bound;
    }

    public boolean owns(ClientConnection connection, String token) {
        if (token == null || !token.equals(connection.getToken()) || connection.isClosed()) {
            return false;
        }
        try {
            return coordinator.withCurrentSession(token, session -> token.equals(connection.getToken()));
        } catch (UnauthorizedException expired) {
            return false;
        }
    }

    public void unbind(ClientConnection connection) {
        String token;
        synchronized (connection) {
            token = connection.getToken();
            connection.setToken(null);
            connection.setUserId(null);
            connections.removeConnection(connection);
        }
        coordinator.logout(token);
    }

    public void disconnect(ClientConnection connection) {
        connection.close();
        unbind(connection);
    }
}
