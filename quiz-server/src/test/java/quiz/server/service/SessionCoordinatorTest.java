package quiz.server.service;

import org.junit.jupiter.api.Test;
import quiz.common.enums.Role;
import quiz.common.protocol.ErrorCode;
import quiz.server.db.TransactionManager;
import quiz.server.domain.User;
import quiz.server.exception.QuizException;
import quiz.server.exception.UnauthorizedException;
import quiz.server.network.SessionRegistry;
import quiz.server.network.UserSession;
import quiz.server.repository.UserRepository;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SessionCoordinatorTest {
    @Test
    void oldTokenCleanupPreservesReplacementAndOtherUser() {
        SessionRegistry registry = new SessionRegistry();
        SessionCoordinator coordinator = new SessionCoordinator(registry);
        UserSession user = new UserSession(2, Role.STUDENT, "User2");
        String oldToken = coordinator.openSession(user);
        String otherToken = coordinator.openSession(new UserSession(3, Role.TEACHER, "User3"));
        String newToken = coordinator.openSession(user);

        coordinator.logout(oldToken);
        assertTrue(registry.isValid(newToken));
        assertTrue(registry.isValid(otherToken));
        coordinator.revokeUser(2);
        assertFalse(registry.isValid(newToken));
        assertTrue(registry.isValid(otherToken));
    }

    @Test
    void rechecksTokenAfterDatabaseReadOrUncoordinatedDisconnect() {
        SessionRegistry registry = new SessionRegistry();
        SessionCoordinator coordinator = new SessionCoordinator(registry);
        registry.register("old", new UserSession(2, Role.STUDENT, "User2"));
        UserRepository users = mock(UserRepository.class);
        when(users.findById(2)).thenAnswer(invocation -> {
            registry.remove("old");
            registry.register("new", new UserSession(2, Role.STUDENT, "User2"));
            return Optional.of(ServiceTestUsers.user(2, Role.STUDENT, true));
        });

        assertThrows(UnauthorizedException.class, () -> new AuthService(users, coordinator).authenticate("old"));
        assertTrue(registry.isValid("new"));
    }

    @Test
    void concurrentLoginsLeaveExactlyOneCurrentSession() throws Exception {
        SessionRegistry registry = new SessionRegistry();
        SessionCoordinator coordinator = new SessionCoordinator(registry);
        UserRepository users = mock(UserRepository.class);
        User user = ServiceTestUsers.user(2, Role.STUDENT, true);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        when(users.findByUsername("User2")).thenAnswer(invocation -> {
            ready.countDown();
            assertTrue(start.await(5, TimeUnit.SECONDS));
            return Optional.of(user);
        });
        when(users.findById(2)).thenReturn(Optional.of(user));
        AuthService auth = new AuthService(users, coordinator);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var left = executor.submit(() -> auth.login("User2", ServiceTestUsers.PASSWORD));
            var right = executor.submit(() -> auth.login("User2", ServiceTestUsers.PASSWORD));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            String first = left.get(10, TimeUnit.SECONDS).token();
            String second = right.get(10, TimeUnit.SECONDS).token();
            assertNotEquals(first, second);
            assertEquals(1, registry.size());
            assertNotEquals(registry.isValid(first), registry.isValid(second));
            String current = registry.isValid(first) ? first : second;
            auth.logout(registry.isValid(first) ? second : first);
            assertEquals(2, auth.authenticate(current).userId());
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void disableCommitsWhileLoginIsWaitingAndPreventsLateTokenPublication() throws Exception {
        SessionRegistry registry = new SessionRegistry();
        registry.register("old", new UserSession(2, Role.STUDENT, "User2"));
        SessionCoordinator coordinator = new SessionCoordinator(registry);
        AtomicReference<User> target = new AtomicReference<>(ServiceTestUsers.user(2, Role.STUDENT, true));
        UserRepository users = mock(UserRepository.class);
        CountDownLatch read = new CountDownLatch(1);
        when(users.findByUsername("User2")).thenAnswer(invocation -> {
            User snapshot = target.get();
            read.countDown();
            return Optional.of(snapshot);
        });
        when(users.findById(2)).thenAnswer(invocation -> Optional.of(target.get()));
        when(users.findById(1)).thenReturn(Optional.of(ServiceTestUsers.user(1, Role.ADMIN, true)));
        Connection connection = mock(Connection.class);
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        when(users.findById(connection, 1)).thenReturn(Optional.of(ServiceTestUsers.user(1, Role.ADMIN, true)));
        when(users.findById(connection, 2)).thenAnswer(invocation -> Optional.of(target.get()));
        when(users.updateActiveStatus(connection, 2, false)).thenAnswer(invocation -> {
            target.set(ServiceTestUsers.user(2, Role.STUDENT, false));
            return true;
        });
        AccountService accounts = new AccountService(users, new TransactionManager(dataSource), coordinator);
        AuthService auth = new AuthService(users, coordinator);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var future = coordinator.withUserLock(2, () -> {
                var login = executor.submit(() -> assertThrows(QuizException.class,
                    () -> auth.login("User2", ServiceTestUsers.PASSWORD)));
                try {
                    assertTrue(read.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(error);
                }
                accounts.setAccountActive(2, false, 1);
                return login;
            });
            assertEquals(ErrorCode.ACCOUNT_DISABLED, future.get(10, TimeUnit.SECONDS).getErrorCode());
            assertEquals(0, registry.size());
            verify(connection).commit();
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void callbackFailureReleasesLockAndDoesNotCorruptExistingToken() throws Exception {
        SessionRegistry registry = new SessionRegistry();
        SessionCoordinator coordinator = new SessionCoordinator(registry);
        String token = coordinator.openSession(new UserSession(2, Role.STUDENT, "User2"));
        assertThrows(IllegalStateException.class,
            () -> coordinator.withCurrentSession(token, session -> { throw new IllegalStateException("failure"); }));
        var executor = Executors.newSingleThreadExecutor();
        try {
            assertEquals(2L, executor.submit(() -> coordinator.withCurrentSession(token, UserSession::userId))
                .get(5, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void rejectsInvalidIdentityWithoutCallingCallback() {
        SessionCoordinator coordinator = new SessionCoordinator(new SessionRegistry());
        assertThrows(IllegalArgumentException.class, () -> coordinator.withUserLock(0, () -> "invalid"));
        assertThrows(UnauthorizedException.class,
            () -> coordinator.withCurrentSession("fake", session -> { fail("Must not run"); return null; }));
    }
}
