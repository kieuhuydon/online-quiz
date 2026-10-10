package quiz.server.domain;

import quiz.common.enums.Role;

import java.time.Instant;

/**
 * Immutable server-side user entity. This entity is not a wire DTO and must not
 * be sent to clients; its password hash is excluded from diagnostic output.
 * String limits count Unicode code points, matching the database column limits.
 * Password hashing and email-format validation belong to security/services.
 */
public record User(
    long id,
    String username,
    String passwordHash,
    String fullName,
    String email,
    Role role,
    boolean active,
    Long createdBy,
    Instant createdAt
) {
    private static final int MAX_USERNAME_LENGTH = 50;
    private static final int MAX_FULL_NAME_LENGTH = 100;
    private static final int MAX_EMAIL_LENGTH = 100;
    private static final int MAX_PASSWORD_HASH_LENGTH = 255;

    /**
     * Validates both supported states: new (zero ID, no creation timestamp)
     * and persisted (positive ID, non-null creation timestamp).
     *
     * @throws IllegalArgumentException if a domain invariant is violated
     */
    public User {
        validateStorageState(id, createdAt);
        username = normalizeRequiredText(username, "Username", MAX_USERNAME_LENGTH);
        fullName = normalizeRequiredText(fullName, "Full name", MAX_FULL_NAME_LENGTH);
        validateRequiredText(passwordHash, "Password hash", MAX_PASSWORD_HASH_LENGTH);
        email = normalizeEmail(email);
        if (role == null) {
            throw new IllegalArgumentException("Role cannot be null");
        }
        if (createdBy != null && createdBy <= 0) {
            throw new IllegalArgumentException("Creator ID must be positive when present");
        }
    }

    /**
     * Creates an active user awaiting database-generated ID and timestamp.
     * The caller supplies an already hashed password; null creator is allowed
     * for bootstrap accounts.
     */
    public static User createNew(String username, String passwordHash, String fullName,
                                 String email, Role role, Long createdBy) {
        return new User(0, username, passwordHash, fullName, email, role, true, createdBy, null);
    }

    /**
     * Restores a persisted user, retaining the database ID, timestamp and status.
     *
     * @throws IllegalArgumentException if the ID is not positive or another
     *                                  domain invariant is violated
     */
    public static User restore(long id, String username, String passwordHash, String fullName,
                               String email, Role role, boolean active, Long createdBy,
                               Instant createdAt) {
        if (id <= 0) {
            throw new IllegalArgumentException("Persisted user ID must be positive");
        }
        return new User(id, username, passwordHash, fullName, email, role, active,
            createdBy, createdAt);
    }

    private static void validateStorageState(long id, Instant createdAt) {
        if (id < 0) {
            throw new IllegalArgumentException("User ID cannot be negative");
        }
        if (id == 0 && createdAt != null) {
            throw new IllegalArgumentException("New user cannot have a creation timestamp");
        }
        if (id > 0 && createdAt == null) {
            throw new IllegalArgumentException("Persisted user must have a creation timestamp");
        }
    }

    private static String normalizeRequiredText(String value, String field, int maxLength) {
        String normalized = value == null ? null : value.strip();
        validateRequiredText(normalized, field, maxLength);
        return normalized;
    }

    private static void validateRequiredText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " cannot be null or blank");
        }
        validateLength(value, field, maxLength);
    }

    private static String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        String normalized = email.strip();
        validateLength(normalized, "Email", MAX_EMAIL_LENGTH);
        return normalized;
    }

    private static void validateLength(String value, String field, int maxLength) {
        if (value.codePointCount(0, value.length()) > maxLength) {
            throw new IllegalArgumentException(field + " cannot exceed " + maxLength + " characters");
        }
    }

    @Override
    public String toString() {
        return "User[id=" + id + ", username=" + username + ", role=" + role + ", active=" + active + "]";
    }
}
