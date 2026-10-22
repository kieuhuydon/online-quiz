package quiz.server.domain;

import quiz.common.enums.AntiCheatPolicyType;
import quiz.common.enums.QuizStatus;

import java.time.Instant;
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
            throw new IllegalArgumentException("Quiz status cannot be null");
        }
        if (anticheatPolicy == null) {
            throw new IllegalArgumentException("Anti-cheat policy cannot be null");
        }
        if (maxViolations <= 0) {
            throw new IllegalArgumentException("Max violations must be greater than zero");
        }
        if (createdBy <= 0) {
            throw new IllegalArgumentException("Creator ID must be positive");
        }
    }

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
