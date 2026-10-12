package quiz.server.network;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quiz.common.dto.LoginResultDto;
import quiz.common.protocol.*;
import quiz.server.dispatch.RequestDispatcher;

import java.io.*;
import java.net.Socket;
import java.util.concurrent.*;

/** One reader per socket and one outstanding worker operation per reader. */
public final class ClientHandler implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(ClientHandler.class);
    private final Socket socket;
    private final RequestDispatcher dispatcher;
    private final ExecutorService workers;
    private final SessionLifecycle lifecycle;
    private final int handshakeTimeout;
    private final int readTimeout;
    private final Runnable onClosed;

    public ClientHandler(Socket socket, RequestDispatcher dispatcher, ExecutorService workers,
                         SessionLifecycle lifecycle, int handshakeTimeout, int readTimeout, Runnable onClosed) {
        this.socket = socket;
        this.dispatcher = dispatcher;
        this.workers = workers;
        this.lifecycle = lifecycle;
        this.handshakeTimeout = handshakeTimeout;
        this.readTimeout = readTimeout;
        this.onClosed = onClosed;
    }

    @Override
    public void run() {
        ClientConnection connection = null;
        try {
            socket.setSoTimeout(handshakeTimeout);
            ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
            out.flush();
            connection = new ClientConnection(socket, out);
            ObjectInputStream in = MessageCodec.createFilteredInputStream(socket.getInputStream());
            socket.setSoTimeout(readTimeout);
            while (!socket.isClosed() && !Thread.currentThread().isInterrupted()) {
                Object frame = MessageCodec.read(in);
                if (!(frame instanceof Request request) || request.requestId() <= 0) {
                    break;
                }
                Response response;
                ClientConnection current = connection;
                try {
                    response = workers.submit(() -> process(current, request)).get();
                } catch (RejectedExecutionException busy) {
                    response = Response.error(request.requestId(), ErrorCode.SERVER_BUSY, "Server is busy");
                }
                if (request.type() == MessageType.LOGIN && response.success()
                    && !lifecycle.owns(connection, ((LoginResultDto) response.payload()).token())) {
                    response = Response.error(request.requestId(), ErrorCode.UNAUTHENTICATED, "Session is no longer current");
                }
                connection.send(response);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException failed) {
            // Do not turn VM errors into business responses.
            if (failed.getCause() instanceof Error fatal) {
                throw fatal;
            }
            log.debug("Worker failed exception={}", failed.getCause().getClass().getName());
        } catch (IOException | ClassNotFoundException transport) {
            log.debug("Connection ended exception={}", transport.getClass().getName());
        } finally {
            if (connection != null) {
                lifecycle.disconnect(connection);
            } else {
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // The handshake failed before a ClientConnection could own this socket.
                }
            }
            onClosed.run();
        }
    }

    private Response process(ClientConnection connection, Request request) {
        if (connection.isClosed()) {
            return Response.error(request.requestId(), ErrorCode.UNAUTHENTICATED, "Connection is closed");
        }
        if (request.type() == MessageType.LOGIN) {
            if (connection.getToken() != null) {
                return Response.error(request.requestId(), ErrorCode.CONFLICT, "Connection is already authenticated");
            }
        } else if (!lifecycle.owns(connection, request.token())) {
            return Response.error(request.requestId(), ErrorCode.UNAUTHENTICATED, "Token does not belong to this connection");
        }
        Response result = dispatcher.dispatch(request);
        if (result.success() && request.type() == MessageType.LOGIN) {
            if (!lifecycle.bind(connection, (LoginResultDto) result.payload())) {
                return Response.error(request.requestId(), ErrorCode.UNAUTHENTICATED, "Session is no longer current");
            }
        } else if (result.success() && request.type() == MessageType.LOGOUT) {
            lifecycle.unbind(connection);
        }
        return result;
    }
}
