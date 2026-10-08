package quiz.server.network;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import quiz.common.enums.Role;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SessionRegistry Unit Tests")
class SessionRegistryTest {

    private SessionRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SessionRegistry();
    }

    @Test
    @DisplayName("Should register, query and validate active sessions")
    void testRegisterAndGetSession() {
        UserSession session = new UserSession(1L, Role.ADMIN, "admin");
        String token = "test-token-123";

        registry.register(token, session);

        assertTrue(registry.isValid(token));
        assertEquals(1, registry.size());

        Optional<UserSession> found = registry.get(token);
        assertTrue(found.isPresent());
        assertEquals(session, found.get());
        assertEquals(1L, found.get().userId());
        assertEquals(Role.ADMIN, found.get().role());
        assertEquals("admin", found.get().username());
    }

    @Test
    @DisplayName("Should remove session on logout")
    void testRemoveSession() {
        UserSession session = new UserSession(2L, Role.STUDENT, "student01");
        String token = "student-token-456";

        registry.register(token, session);
        assertEquals(1, registry.size());

        Optional<UserSession> removed = registry.remove(token);
        assertTrue(removed.isPresent());
        assertEquals(session, removed.get());

        assertFalse(registry.isValid(token));
        assertTrue(registry.get(token).isEmpty());
        assertEquals(0, registry.size());
    }

    @Test
    @DisplayName("Should remove session by userId")
    void testRemoveByUserId() {
        registry.register("token-1", new UserSession(10L, Role.STUDENT, "student10"));
        registry.register("token-2", new UserSession(20L, Role.TEACHER, "teacher01"));

        assertEquals(2, registry.size());

        registry.removeByUserId(10L);

        assertEquals(1, registry.size());
        assertFalse(registry.isValid("token-1"));
        assertTrue(registry.isValid("token-2"));
    }

    @Test
    @DisplayName("Should find token by userId")
    void testFindTokenByUserId() {
        registry.register("token-teacher", new UserSession(5L, Role.TEACHER, "teacher01"));

        Optional<String> token = registry.findTokenByUserId(5L);
        assertTrue(token.isPresent());
        assertEquals("token-teacher", token.get());

        assertTrue(registry.findTokenByUserId(999L).isEmpty());
    }

    @Test
    @DisplayName("Should handle clear and invalid tokens safely")
    void testClearAndInvalidQueries() {
        registry.register("tok1", new UserSession(1L, Role.STUDENT, "s1"));
        registry.register("tok2", new UserSession(2L, Role.STUDENT, "s2"));
        assertEquals(2, registry.size());

        registry.clear();
        assertEquals(0, registry.size());
        assertFalse(registry.isValid(null));
        assertFalse(registry.isValid(""));
        assertTrue(registry.get(null).isEmpty());
    }
}
