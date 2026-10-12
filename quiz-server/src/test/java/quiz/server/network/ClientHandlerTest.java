package quiz.server.network;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import quiz.common.dto.LoginResultDto;
import quiz.common.enums.Role;
import quiz.common.protocol.*;
import quiz.server.config.ServerConfig;
import quiz.server.dispatch.RequestDispatcher;
import quiz.server.service.SessionCoordinator;
import java.io.*;
import java.net.*;
import java.time.Duration;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClientHandlerTest {
    @Test @Timeout(10)
    void slowReaderDoesNotOccupyWorkerAndShutdownUnblocksWriter() throws Exception {
        var sessions = new SessionRegistry(); var connections = new ConnectionRegistry();
        var coordinator = new SessionCoordinator(sessions);
        RequestDispatcher dispatcher = mock(RequestDispatcher.class);
        CountDownLatch first = new CountDownLatch(1), second = new CountDownLatch(1);
        when(dispatcher.dispatch(any())).thenAnswer(call -> {
            Request request = call.getArgument(0);
            if (request.type() == MessageType.LOGIN) {
                long id = request.requestId() == 1 ? 7 : 8;
                String token = coordinator.openSession(new UserSession(id, Role.ADMIN, "admin" + id));
                return Response.success(request.requestId(), new LoginResultDto(token, id, "admin" + id, "Admin", Role.ADMIN));
            }
            if (request.requestId() == 2) first.countDown(); else second.countDown();
            return Response.success(request.requestId(), "x".repeat(900000));
        });
        var config = new ServerConfig(5555, 2, 1, 1, 3, "jdbc:postgresql://localhost/unused", "unused", "test-only",
            1, 0, 0, 250, 1000, 10000, 1000);
        try (var server = new QuizServer(0, config, dispatcher, new SessionLifecycle(coordinator, connections), sessions, connections);
             Socket slow = new Socket()) {
            slow.setReceiveBufferSize(1024); slow.connect(new InetSocketAddress("127.0.0.1", server.getPort())); slow.setSoTimeout(2000);
            ObjectOutputStream out = new ObjectOutputStream(slow.getOutputStream()); out.flush();
            ObjectInputStream in = MessageCodec.createFilteredInputStream(slow.getInputStream());
            MessageCodec.write(out, new Request(1, MessageType.LOGIN, null, null));
            String token = ((LoginResultDto) ((Response) MessageCodec.read(in)).payload()).token();
            connections.get(7).orElseThrow().getSocket().setSendBufferSize(1024);
            MessageCodec.write(out, new Request(2, MessageType.LIST_ACCOUNTS, token, null));
            MessageCodec.write(out, new Request(3, MessageType.LIST_ACCOUNTS, token, null));
            assertTrue(first.await(2, TimeUnit.SECONDS));
            assertFalse(second.await(200, TimeUnit.MILLISECONDS), "Reader should be blocked sending the first large response");
            try (QuizServerTest.Peer other = new QuizServerTest.Peer(server.getPort())) {
                other.id = 10;
                assertTrue(other.exchange(MessageType.LOGIN, null, null).success());
            }
            assertTimeoutPreemptively(Duration.ofSeconds(3), server::close);
            assertEquals(0, server.getOpenConnectionCount()); assertEquals(0, sessions.size()); assertEquals(0, connections.size());
        }
    }
}
