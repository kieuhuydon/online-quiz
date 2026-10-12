package quiz.server.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TokenGenerator Unit Tests")
class TokenGeneratorTest {

    @Test
    void tenDigitPinsUseValuesBeyondIntegerRange() {
        boolean aboveIntegerRange = false;
        for (int i = 0; i < 100; i++) {
            long pin = Long.parseLong(TokenGenerator.generatePin(10));
            assertTrue(pin >= 1_000_000_000L && pin < 10_000_000_000L);
            aboveIntegerRange |= pin > Integer.MAX_VALUE;
        }
        assertTrue(aboveIntegerRange, "10-digit PIN generation must not be limited to int range");
    }

    @Test
    @DisplayName("Should generate unique URL-safe tokens with sufficient entropy")
    void testGenerateToken() {
        Set<String> tokens = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            String token = TokenGenerator.generateToken();
            assertNotNull(token);
            assertTrue(token.length() >= 32, "Token should be at least 32 characters");
            assertFalse(token.contains("/"), "Token should be URL-safe (no /)");
            assertFalse(token.contains("+"), "Token should be URL-safe (no +)");
            assertFalse(token.contains("="), "Token should be unpadded (no =)");
            tokens.add(token);
        }
        assertEquals(100, tokens.size(), "All 100 generated tokens must be unique");
    }

    @Test
    @DisplayName("Should validate minimum byte count for tokens")
    void testTokenByteCountValidation() {
        assertThrows(IllegalArgumentException.class, () -> TokenGenerator.generateToken(15));
        assertDoesNotThrow(() -> TokenGenerator.generateToken(16));
    }

    @Test
    @DisplayName("Should generate valid 6-digit PIN numbers")
    void testGeneratePin() {
        for (int i = 0; i < 50; i++) {
            String pin = TokenGenerator.generatePin();
            assertNotNull(pin);
            assertEquals(6, pin.length());
            assertTrue(pin.matches("\\d{6}"));
            int pinVal = Integer.parseInt(pin);
            assertTrue(pinVal >= 100000 && pinVal <= 999999);
        }
    }

    @Test
    @DisplayName("Should generate valid PIN numbers with custom digits")
    void testGeneratePinCustomDigits() {
        String pin4 = TokenGenerator.generatePin(4);
        assertEquals(4, pin4.length());
        assertTrue(pin4.matches("\\d{4}"));

        String pin8 = TokenGenerator.generatePin(8);
        assertEquals(8, pin8.length());
        assertTrue(pin8.matches("\\d{8}"));

        assertThrows(IllegalArgumentException.class, () -> TokenGenerator.generatePin(3));
        assertThrows(IllegalArgumentException.class, () -> TokenGenerator.generatePin(11));
    }
}
