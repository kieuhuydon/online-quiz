package quiz.server.domain;

import quiz.common.enums.AntiCheatPolicyType;
import quiz.common.enums.QuizStatus;

import java.time.Instant;

/**
 * Immutable server-side quiz entity. Not a wire DTO; use {@link quiz.common.dto.QuizDto}
 * instead. String limits count Unicode code points, matching database column limits.
import java.util.Objects;

/**
 * Entity đề kiểm tra (Quiz) phía server (bất biến, không Serializable).
 */
public record Quiz(
    long id,
    String title,
    String description,
    String category,
    int durationMinutes,
    double passScore,
    QuizStatus status,
    String pin,
    AntiCheatPolicyType anticheatPolicy,
    int maxViolations,
    long createdBy,
    Instant createdAt,
    Instant openedAt,
    Instant closedAt
) {
    private static final int MAX_TITLE_LENGTH = 200;
    private static final int MAX_DESCRIPTION_LENGTH = 500;
    private static final int MAX_CATEGORY_LENGTH = 100;
    private static final int MAX_PIN_LENGTH = 10;

    public Quiz {
        validateStorageState(id, createdAt);
        title = normalizeRequiredText(title, "Title", MAX_TITLE_LENGTH);
        description = normalizeOptionalText(description, MAX_DESCRIPTION_LENGTH);
        category = normalizeOptionalText(category, MAX_CATEGORY_LENGTH);
        if (durationMinutes <= 0) {
            throw new IllegalArgumentException("Duration must be positive");
    public static final int MAX_TITLE_LENGTH = 200;
    public static final int MAX_DESCRIPTION_LENGTH = 500;
    public static final int MAX_CATEGORY_LENGTH = 100;

    public Quiz {
        if (id < 0) {
            throw new IllegalArgumentException("Quiz ID cannot be negative");
        }
        if (id == 0 && createdAt != null) {
            throw new IllegalArgumentException("New quiz cannot have createdAt timestamp");
        }
        if (id > 0 && createdAt == null) {
            throw new IllegalArgumentException("Persisted quiz must have createdAt timestamp");
        }
        if (title == null || title.strip().isBlank()) {
            throw new IllegalArgumentException("Quiz title cannot be blank");
        }
        title = title.strip();
        if (title.length() > MAX_TITLE_LENGTH) {
            throw new IllegalArgumentException("Quiz title exceeds maximum length: " + MAX_TITLE_LENGTH);
        }
        if (description != null) {
            description = description.strip();
            if (description.isEmpty()) {
                description = null;
            } else if (description.length() > MAX_DESCRIPTION_LENGTH) {
                throw new IllegalArgumentException("Quiz description exceeds maximum length: " + MAX_DESCRIPTION_LENGTH);
            }
        }
        if (category != null) {
            category = category.strip();
            if (category.isEmpty()) {
                category = null;
            } else if (category.length() > MAX_CATEGORY_LENGTH) {
                throw new IllegalArgumentException("Quiz category exceeds maximum length: " + MAX_CATEGORY_LENGTH);
            }
        }
        if (durationMinutes <= 0) {
            throw new IllegalArgumentException("Duration must be greater than zero");
        }
        if (passScore < 0 || passScore > 100) {
            throw new IllegalArgumentException("Pass score must be between 0 and 100");
        }
        if (status == null) {
            throw new IllegalArgumentException("Status cannot be null");
        }
        pin = normalizeOptionalText(pin, MAX_PIN_LENGTH);
            throw new IllegalArgumentException("Quiz status cannot be null");
        }
        if (anticheatPolicy == null) {
            throw new IllegalArgumentException("Anti-cheat policy cannot be null");
        }
        if (maxViolations <= 0) {
            throw new IllegalArgumentException("Max violations must be positive");
            throw new IllegalArgumentException("Max violations must be greater than zero");
        }
        if (createdBy <= 0) {
            throw new IllegalArgumentException("Creator ID must be positive");
        }
    }

    /**
     * Creates a new draft quiz awaiting database-generated ID and timestamps.
     */
    public static Quiz createNew(String title, String description, String category,
                                 int durationMinutes, double passScore,
                                 AntiCheatPolicyType anticheatPolicy, int maxViolations,
                                 long createdBy) {
        return new Quiz(0, title, description, category, durationMinutes, passScore,
            QuizStatus.DRAFT, null, anticheatPolicy, maxViolations, createdBy,
            null, null, null);
    }

    /**
     * Restores a persisted quiz, keeping ID and timestamps from database.
     */
    public static Quiz restore(long id, String title, String description, String category,
                               int durationMinutes, double passScore, QuizStatus status,
                               String pin, AntiCheatPolicyType anticheatPolicy,
                               int maxViolations, long createdBy, Instant createdAt,
                               Instant openedAt, Instant closedAt) {
        if (id <= 0) {
            throw new IllegalArgumentException("Persisted quiz ID must be positive");
        }
        return new Quiz(id, title, description, category, durationMinutes, passScore,
            status, pin, anticheatPolicy, maxViolations, createdBy, createdAt,
            openedAt, closedAt);
    }

    private static void validateStorageState(long id, Instant createdAt) {
        if (id < 0) {
            throw new IllegalArgumentException("Quiz ID cannot be negative");
        }
        if (id == 0 && createdAt != null) {
            throw new IllegalArgumentException("New quiz cannot have a creation timestamp");
        }
        if (id > 0 && createdAt == null) {
            throw new IllegalArgumentException("Persisted quiz must have a creation timestamp");
        }
    }

    private static String normalizeRequiredText(String value, String field, int maxLength) {
        String normalized = value == null ? null : value.strip();
        validateRequiredText(normalized, field, maxLength);
        return normalized;
    }

    private static String normalizeOptionalText(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip();
        validateLength(normalized, maxLength);
        return normalized;
    }

    private static void validateRequiredText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " cannot be blank");
        }
        validateLength(value, maxLength);
    }

    private static void validateLength(String value, int maxLength) {
        if (value.codePointCount(0, value.length()) > maxLength) {
            throw new IllegalArgumentException("Value cannot exceed " + maxLength + " characters");
        }
    }

    @Override
    public String toString() {
        return "Quiz[id=" + id + ", title=" + title + ", status=" + status + "]";
    public static Quiz createNew(String title, String description, String category, int durationMinutes,
                                 double passScore, AntiCheatPolicyType anticheatPolicy, int maxViolations, long createdBy) {
        return new Quiz(0, title, description, category, durationMinutes, passScore, QuizStatus.DRAFT,
            null, anticheatPolicy, maxViolations, createdBy, null, null, null);
    }

    public static Quiz restore(long id, String title, String description, String category, int durationMinutes,
                               double passScore, QuizStatus status, String pin, AntiCheatPolicyType anticheatPolicy,
                               int maxViolations, long createdBy, Instant createdAt, Instant openedAt, Instant closedAt) {
        return new Quiz(id, title, description, category, durationMinutes, passScore, Objects.requireNonNull(status),
            pin, Objects.requireNonNull(anticheatPolicy), maxViolations, createdBy,
            Objects.requireNonNull(createdAt), openedAt, closedAt);
    }
}
