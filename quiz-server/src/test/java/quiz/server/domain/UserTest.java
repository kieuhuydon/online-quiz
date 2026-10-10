package quiz.server.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import quiz.common.enums.Role;

import java.io.Serializable;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("User domain invariants")
class UserTest {
    private static final String HASH = "stored-password-hash";
    private static final Instant CREATED_AT = Instant.parse("2026-10-10T03:00:00Z");

    @Test
    void createsActiveUserWithoutDatabaseMetadata() {
        User user = User.createNew("student01", HASH, "Nguyễn Văn Việt", "viet@example.com",
            Role.STUDENT, 7L);

        assertAll(
            () -> assertEquals(0, user.id()),
            () -> assertNull(user.createdAt()),
            () -> assertTrue(user.active()),
            () -> assertEquals(7L, user.createdBy()),
            () -> assertEquals(HASH, user.passwordHash()),
            () -> assertEquals("Nguyễn Văn Việt", user.fullName()),
            () -> assertEquals("viet@example.com", user.email())
        );
    }

    @Test
    void restoresDatabaseMetadataAndInactiveStatus() {
        User user = User.restore(42, "teacher01", HASH, "Teacher", null, Role.TEACHER,
            false, 1L, CREATED_AT);

        assertAll(
            () -> assertEquals(42, user.id()),
            () -> assertEquals(CREATED_AT, user.createdAt()),
            () -> assertFalse(user.active()),
            () -> assertEquals(1L, user.createdBy()),
            () -> assertNull(user.email()),
            () -> assertEquals(Role.TEACHER, user.role())
        );
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void supportsAllDomainRolesIncludingBootstrapAdmin(Role role) {
        User user = User.createNew("bootstrap", HASH, "Bootstrap User", null, role, null);

        assertEquals(role, user.role());
        assertNull(user.createdBy());
    }

    @Test
    void normalizesProfileWhitespaceWithoutChangingCaseOrHash() {
        String suppliedHash = " " + HASH + " ";
        User user = User.createNew("\u2003 Student01 \t", suppliedHash,
            "  Nguyễn  Văn Việt \n", "  Viet@Example.com  ", Role.STUDENT, null);

        assertAll(
            () -> assertEquals("Student01", user.username()),
            () -> assertEquals("Nguyễn  Văn Việt", user.fullName()),
            () -> assertEquals("Viet@Example.com", user.email()),
            () -> assertEquals(suppliedHash, user.passwordHash())
        );
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n", "\u2003"})
    void normalizesMissingEmailToNull(String email) {
        assertNull(User.createNew("user", HASH, "Full Name", email, Role.STUDENT, null).email());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n", "\u2003"})
    void rejectsMissingUsername(String username) {
        assertThrows(IllegalArgumentException.class,
            () -> User.createNew(username, HASH, "Full Name", null, Role.STUDENT, null));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n", "\u2003"})
    void rejectsMissingFullName(String fullName) {
        assertThrows(IllegalArgumentException.class,
            () -> User.createNew("user", HASH, fullName, null, Role.STUDENT, null));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n", "\u2003"})
    void rejectsMissingPasswordHash(String hash) {
        assertThrows(IllegalArgumentException.class,
            () -> User.createNew("user", hash, "Full Name", null, Role.STUDENT, null));
    }

    @ParameterizedTest
    @CsvSource({"username, 50", "fullName, 100", "email, 100", "passwordHash, 255"})
    void enforcesDatabaseCharacterLimitsForAsciiAndSupplementaryUnicode(String field, int limit) {
        String supplementaryCharacter = "\uD83D\uDE00";
        assertAll(
            () -> assertDoesNotThrow(() -> userWithField(field, "a")),
            () -> assertDoesNotThrow(() -> userWithField(field, "a".repeat(limit))),
            () -> assertThrows(IllegalArgumentException.class,
                () -> userWithField(field, "a".repeat(limit + 1))),
            () -> assertDoesNotThrow(() -> userWithField(field, supplementaryCharacter.repeat(limit))),
            () -> assertThrows(IllegalArgumentException.class,
                () -> userWithField(field, supplementaryCharacter.repeat(limit + 1)))
        );
    }

    @Test
    void appliesProfileLengthLimitsAfterNormalization() {
        User user = User.createNew(" " + "a".repeat(50) + " ", HASH,
            " " + "b".repeat(100) + " ", " " + "c".repeat(100) + " ", Role.STUDENT, null);

        assertEquals(50, user.username().length());
        assertEquals(100, user.fullName().length());
        assertEquals(100, user.email().length());
    }

    @Test
    void rejectsNullRoleWithDomainException() {
        assertThrows(IllegalArgumentException.class,
            () -> User.createNew("user", HASH, "Full Name", null, null, null));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, Long.MIN_VALUE})
    void rejectsNonpositiveCreatorIds(long createdBy) {
        assertThrows(IllegalArgumentException.class,
            () -> User.createNew("user", HASH, "Full Name", null, Role.STUDENT, createdBy));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {1, Long.MAX_VALUE})
    void acceptsBootstrapOrPositiveCreatorId(Long createdBy) {
        User user = User.createNew("user", HASH, "Full Name", null, Role.STUDENT, createdBy);

        assertEquals(createdBy, user.createdBy());
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, Long.MIN_VALUE})
    void restoreRejectsUnassignedOrNegativeIds(long id) {
        assertThrows(IllegalArgumentException.class,
            () -> User.restore(id, "user", HASH, "Full Name", null, Role.STUDENT,
                true, null, CREATED_AT));
    }

    @Test
    void rejectsInconsistentStorageMetadataInCanonicalConstructor() {
        assertAll(
            () -> assertThrows(IllegalArgumentException.class,
                () -> new User(-1, "user", HASH, "Full Name", null, Role.STUDENT,
                    true, null, null)),
            () -> assertThrows(IllegalArgumentException.class,
                () -> new User(0, "user", HASH, "Full Name", null, Role.STUDENT,
                    true, null, CREATED_AT)),
            () -> assertThrows(IllegalArgumentException.class,
                () -> new User(1, "user", HASH, "Full Name", null, Role.STUDENT,
                    true, null, null)),
            () -> assertThrows(IllegalArgumentException.class,
                () -> User.restore(1, "user", HASH, "Full Name", null, Role.STUDENT,
                    true, null, null))
        );
    }

    @Test
    void restoreAlsoValidatesProfileData() {
        assertThrows(IllegalArgumentException.class,
            () -> User.restore(1, " ", HASH, "Full Name", null, Role.STUDENT,
                true, null, CREATED_AT));
    }

    @Test
    void diagnosticOutputDoesNotExposeHash() {
        User user = User.restore(42, "student", HASH, "Full Name", "user@example.com",
            Role.STUDENT, true, null, CREATED_AT);

        assertFalse(user.toString().contains(HASH));
        assertFalse(user.toString().contains("passwordHash"));
        assertTrue(user.toString().contains("id=42"));
        assertTrue(user.toString().contains("role=STUDENT"));
    }

    @Test
    void domainEntityIsNotSerializable() {
        Object user = User.createNew("user", HASH, "Full Name", null, Role.STUDENT, null);

        assertFalse(user instanceof Serializable);
    }

    private User userWithField(String field, String value) {
        return User.createNew(
            "username".equals(field) ? value : "user",
            "passwordHash".equals(field) ? value : HASH,
            "fullName".equals(field) ? value : "Full Name",
            "email".equals(field) ? value : null,
            Role.STUDENT, null
        );
    }
}
