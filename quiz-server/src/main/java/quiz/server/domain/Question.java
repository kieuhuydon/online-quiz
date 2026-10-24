package quiz.server.domain;

import quiz.common.enums.Difficulty;

import java.time.Instant;

/** Immutable server-side question entity. */
public record Question(
    long id,
    String content,
    String optionA,
    String optionB,
    String optionC,
    String optionD,
    char correctOption,
    String topic,
    Difficulty difficulty,
    boolean active,
    long createdBy,
    Instant createdAt,
    Instant updatedAt
) {
    private static final int MAX_CONTENT_LENGTH = 1000;
    private static final int MAX_OPTION_LENGTH = 255;
    private static final int MAX_TOPIC_LENGTH = 100;

    public Question {
        validateStorageState(id, createdAt, updatedAt);
        content = normalizeRequiredText(content, "Question content", MAX_CONTENT_LENGTH);
        optionA = normalizeRequiredText(optionA, "Option A", MAX_OPTION_LENGTH);
        optionB = normalizeRequiredText(optionB, "Option B", MAX_OPTION_LENGTH);
        optionC = normalizeRequiredText(optionC, "Option C", MAX_OPTION_LENGTH);
        optionD = normalizeRequiredText(optionD, "Option D", MAX_OPTION_LENGTH);
        correctOption = Character.toUpperCase(correctOption);
        if (correctOption < 'A' || correctOption > 'D') {
            throw new IllegalArgumentException("Correct option must be A, B, C, or D");
        }
        topic = normalizeRequiredText(topic, "Topic", MAX_TOPIC_LENGTH);
        if (difficulty == null) throw new IllegalArgumentException("Difficulty cannot be null");
        if (createdBy <= 0) throw new IllegalArgumentException("Creator ID must be positive");
    }

    public static Question createNew(String content, String optionA, String optionB,
                                     String optionC, String optionD, char correctOption,
                                     String topic, Difficulty difficulty, long createdBy) {
        return new Question(0, content, optionA, optionB, optionC, optionD,
            correctOption, topic, difficulty, true, createdBy, null, null);
    }

    public static Question restore(long id, String content, String optionA, String optionB,
                                   String optionC, String optionD, char correctOption,
                                   String topic, Difficulty difficulty, boolean active,
                                   long createdBy, Instant createdAt, Instant updatedAt) {
        if (id <= 0) throw new IllegalArgumentException("Persisted question ID must be positive");
        return new Question(id, content, optionA, optionB, optionC, optionD,
            correctOption, topic, difficulty, active, createdBy, createdAt, updatedAt);
    }

    private static void validateStorageState(long id, Instant createdAt, Instant updatedAt) {
        if (id < 0) throw new IllegalArgumentException("Question ID cannot be negative");
        if (id == 0 && (createdAt != null || updatedAt != null)) {
            throw new IllegalArgumentException("New question cannot have timestamps");
        }
        if (id > 0 && (createdAt == null || updatedAt == null)) {
            throw new IllegalArgumentException("Persisted question must have creation and update timestamps");
        }
    }

    private static String normalizeRequiredText(String value, String field, int maxLength) {
        String normalized = value == null ? null : value.strip();
        if (normalized == null || normalized.isBlank()) {
            throw new IllegalArgumentException(field + " cannot be blank");
        }
        if (normalized.codePointCount(0, normalized.length()) > maxLength) {
            throw new IllegalArgumentException(field + " cannot exceed " + maxLength + " characters");
        }
        return normalized;
    }

    @Override
    public String toString() {
        return "Question[id=" + id + ", topic=" + topic + ", difficulty=" + difficulty
            + ", active=" + active + "]";
    }
}
