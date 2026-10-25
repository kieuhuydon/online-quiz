package quiz.server.domain;

import quiz.common.enums.AntiCheatPolicyType;
import quiz.common.enums.QuizStatus;

import java.time.Instant;

/** Immutable server-side quiz entity. */
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
        if (id < 0) throw new IllegalArgumentException("Quiz ID cannot be negative");
        if (id == 0 && createdAt != null) throw new IllegalArgumentException("New quiz cannot have createdAt timestamp");
        if (id > 0 && createdAt == null) throw new IllegalArgumentException("Persisted quiz must have createdAt timestamp");
        title = required(title, "Quiz title", MAX_TITLE_LENGTH);
        description = optional(description, MAX_DESCRIPTION_LENGTH);
        category = optional(category, MAX_CATEGORY_LENGTH);
        pin = optional(pin, MAX_PIN_LENGTH);
        if (durationMinutes <= 0) throw new IllegalArgumentException("Duration must be greater than zero");
        if (!Double.isFinite(passScore) || passScore < 0 || passScore > 100) throw new IllegalArgumentException("Pass score must be between 0 and 100");
        if (status == null) throw new IllegalArgumentException("Quiz status cannot be null");
        if (anticheatPolicy == null) throw new IllegalArgumentException("Anti-cheat policy cannot be null");
        if (maxViolations <= 0) throw new IllegalArgumentException("Max violations must be greater than zero");
        if (createdBy <= 0) throw new IllegalArgumentException("Creator ID must be positive");
    }

    public static Quiz createNew(String title, String description, String category,
                                 int durationMinutes, double passScore,
                                 AntiCheatPolicyType anticheatPolicy, int maxViolations,
                                 long createdBy) {
        return new Quiz(0, title, description, category, durationMinutes, passScore,
            QuizStatus.DRAFT, null, anticheatPolicy, maxViolations, createdBy, null, null, null);
    }

    public static Quiz restore(long id, String title, String description, String category,
                               int durationMinutes, double passScore, QuizStatus status,
                               String pin, AntiCheatPolicyType anticheatPolicy,
                               int maxViolations, long createdBy, Instant createdAt,
                               Instant openedAt, Instant closedAt) {
        if (id <= 0) throw new IllegalArgumentException("Persisted quiz ID must be positive");
        return new Quiz(id, title, description, category, durationMinutes, passScore,
            status, pin, anticheatPolicy, maxViolations, createdBy, createdAt, openedAt, closedAt);
    }

    private static String required(String value, String field, int max) {
        String normalized = value == null ? null : value.strip();
        if (normalized == null || normalized.isBlank()) throw new IllegalArgumentException(field + " cannot be blank");
        return checked(normalized, field, max);
    }

    private static String optional(String value, int max) {
        if (value == null || value.isBlank()) return null;
        return checked(value.strip(), "Value", max);
    }

    private static String checked(String value, String field, int max) {
        if (value.codePointCount(0, value.length()) > max) throw new IllegalArgumentException(field + " exceeds maximum length: " + max);
        return value;
    }

    @Override
    public String toString() {
        return "Quiz[id=" + id + ", title=" + title + ", status=" + status + "]";
    }
}
