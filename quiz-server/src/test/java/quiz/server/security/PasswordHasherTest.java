package quiz.server.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PasswordHasher Unit Tests")
class PasswordHasherTest {

    @Test
    @DisplayName("Should hash password and verify successfully")
    void testHashAndCheckPassword() {
        String plain = "password123";
        String hash = PasswordHasher.hashPassword(plain);

        assertNotNull(hash);
        assertTrue(hash.startsWith("$2a$10$"));
        assertTrue(PasswordHasher.checkPassword(plain, hash));
        assertFalse(PasswordHasher.checkPassword("wrongpassword", hash));
    }

    @Test
    @DisplayName("Should verify seed admin password hash accurately")
    void testCheckSeedPasswordHash() {
        // Hash từ seed.sql cho password123
        String seedHash = "$2a$10$RMj7Oqje6a.XQorzyUTXuuXnV/EKY/.yYf4YaxIMOz67D/tFVZ.0y";

        assertTrue(PasswordHasher.checkPassword("password123", seedHash));
        assertFalse(PasswordHasher.checkPassword("admin123", seedHash));
    }

    @Test
    @DisplayName("Should throw exception when hashing blank or null password")
    void testHashInvalidPassword() {
        assertThrows(IllegalArgumentException.class, () -> PasswordHasher.hashPassword(null));
        assertThrows(IllegalArgumentException.class, () -> PasswordHasher.hashPassword(""));
        assertThrows(IllegalArgumentException.class, () -> PasswordHasher.hashPassword("   "));
    }

    @Test
    @DisplayName("Should safely return false for null, empty or corrupted hashes")
    void testCheckInvalidInputs() {
        assertFalse(PasswordHasher.checkPassword(null, "somehash"));
        assertFalse(PasswordHasher.checkPassword("pass", null));
        assertFalse(PasswordHasher.checkPassword("pass", "invalid_not_a_bcrypt_hash"));
    }
}
