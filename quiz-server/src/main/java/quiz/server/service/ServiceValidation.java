package quiz.server.service;

import quiz.server.exception.ValidationException;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/** Shared input checks; password values are never trimmed or included in error messages. */
final class ServiceValidation {
    private static final Pattern EMAIL = Pattern.compile("[^\\s@]+@[^\\s@]+\\.[^\\s@]+",
        Pattern.UNICODE_CHARACTER_CLASS);

    private ServiceValidation() {
    }

    static void positiveId(long id) {
        if (id <= 0) {
            throw new ValidationException("User ID must be positive");
        }
    }

    static String requiredText(String value, String field, int limit) {
        String normalized = value == null ? null : value.strip();
        if (normalized == null || normalized.isBlank()
            || normalized.codePointCount(0, normalized.length()) > limit) {
            throw new ValidationException(field + " must be nonblank and at most " + limit + " characters");
        }
        return normalized;
    }

    static void password(String value, boolean creating) {
        if (value == null || value.isBlank() || value.length() > 72
            || value.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ValidationException("Password must be nonblank and at most 72 UTF-8 bytes");
        }
        if (creating && value.codePointCount(0, value.length()) < 8) {
            throw new ValidationException("New password must have at least 8 characters");
        }
    }

    static String email(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = requiredText(value, "Email", 100);
        if (!EMAIL.matcher(normalized).matches()) {
            throw new ValidationException("Email format is invalid");
        }
        return normalized;
    }
}
