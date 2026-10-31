package quiz.server.domain;

import quiz.common.enums.Difficulty;
import quiz.common.enums.QuestionType;

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
    QuestionType questionType,
    boolean active,
    long createdBy,
    Instant createdAt,
    Instant updatedAt,
    String answerKey,
    String explanation
) {
    private static final int MAX_CONTENT_LENGTH = 1000;
    private static final int MAX_OPTION_LENGTH = 255;
    private static final int MAX_TOPIC_LENGTH = 100;
    private static final int MAX_EXPLANATION_LENGTH = 2000;

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
        if (questionType == null) questionType = QuestionType.MULTIPLE_CHOICE;
        answerKey = answerKey == null ? "" : answerKey.strip();
        explanation = normalizeOptionalText(explanation, "Explanation", MAX_EXPLANATION_LENGTH);
        if (createdBy <= 0) throw new IllegalArgumentException("Creator ID must be positive");
    }

    public Question(long id, String content, String optionA, String optionB,
                    String optionC, String optionD, char correctOption,
                    String topic, Difficulty difficulty, QuestionType questionType,
                    String explanation, boolean active, long createdBy,
                    Instant createdAt, Instant updatedAt) {
        this(id, content, optionA, optionB, optionC, optionD, correctOption,
             topic, difficulty, questionType, active, createdBy, createdAt, updatedAt, "", explanation);
    }

    public Question(long id, String content, String optionA, String optionB,
                    String optionC, String optionD, char correctOption,
                    String topic, Difficulty difficulty, QuestionType questionType,
                    boolean active, long createdBy, Instant createdAt, Instant updatedAt) {
        this(id, content, optionA, optionB, optionC, optionD, correctOption,
             topic, difficulty, questionType, active, createdBy, createdAt, updatedAt, "", "");
    }

    public Question(long id, String content, String optionA, String optionB,
                    String optionC, String optionD, char correctOption,
                    String topic, Difficulty difficulty, boolean active,
                    long createdBy, Instant createdAt, Instant updatedAt) {
        this(id, content, optionA, optionB, optionC, optionD, correctOption,
             topic, difficulty, QuestionType.MULTIPLE_CHOICE, active, createdBy, createdAt, updatedAt, "", "");
    }

    public static Question createNew(String content, String optionA, String optionB,
                                     String optionC, String optionD, char correctOption,
                                     String topic, Difficulty difficulty, QuestionType questionType,
                                     String explanation, long createdBy) {
        return new Question(0, content, optionA, optionB, optionC, optionD,
            correctOption, topic, difficulty, questionType, true, createdBy, null, null, "", explanation);
    }

    public static Question createNew(String content, String optionA, String optionB,
                                     String optionC, String optionD, char correctOption,
                                     String topic, Difficulty difficulty, QuestionType questionType,
                                     long createdBy, String answerKey, String explanation) {
        return new Question(0, content, optionA, optionB, optionC, optionD,
            correctOption, topic, difficulty, questionType, true, createdBy, null, null, answerKey, explanation);
    }

    public static Question createNew(String content, String optionA, String optionB,
                                     String optionC, String optionD, char correctOption,
                                     String topic, Difficulty difficulty, QuestionType questionType,
                                     long createdBy) {
        return createNew(content, optionA, optionB, optionC, optionD, correctOption,
            topic, difficulty, questionType, null, createdBy);
    }

    public static Question createNew(String content, String optionA, String optionB,
                                     String optionC, String optionD, char correctOption,
                                     String topic, Difficulty difficulty, long createdBy) {
        return createNew(content, optionA, optionB, optionC, optionD, correctOption,
            topic, difficulty, QuestionType.MULTIPLE_CHOICE, null, createdBy);
    }

    public static Question restore(long id, String content, String optionA, String optionB,
                                   String optionC, String optionD, char correctOption,
                                   String topic, Difficulty difficulty, QuestionType questionType,
                                   String explanation, boolean active, long createdBy,
                                   Instant createdAt, Instant updatedAt) {
        if (id <= 0) throw new IllegalArgumentException("Persisted question ID must be positive");
        return new Question(id, content, optionA, optionB, optionC, optionD,
            correctOption, topic, difficulty, questionType, active, createdBy, createdAt, updatedAt, "", explanation);
    }

    public static Question restore(long id, String content, String optionA, String optionB, String optionC,
                                   String optionD, char correctOption, String topic, Difficulty difficulty,
                                   QuestionType type, boolean active, long createdBy, Instant createdAt,
                                   Instant updatedAt, String answerKey, String explanation) {
        if (id <= 0) throw new IllegalArgumentException("Persisted question ID must be positive");
        return new Question(id, content, optionA, optionB, optionC, optionD, correctOption, topic, difficulty,
            type, active, createdBy, createdAt, updatedAt, answerKey, explanation);
    }

    public static Question restore(long id, String content, String optionA, String optionB,
                                   String optionC, String optionD, char correctOption,
                                   String topic, Difficulty difficulty, QuestionType questionType,
                                   boolean active, long createdBy, Instant createdAt, Instant updatedAt) {
        return restore(id, content, optionA, optionB, optionC, optionD, correctOption,
            topic, difficulty, questionType, null, active, createdBy, createdAt, updatedAt);
    }

    public static Question restore(long id, String content, String optionA, String optionB,
                                   String optionC, String optionD, char correctOption,
                                   String topic, Difficulty difficulty, boolean active,
                                   long createdBy, Instant createdAt, Instant updatedAt) {
        return restore(id, content, optionA, optionB, optionC, optionD, correctOption,
            topic, difficulty, QuestionType.MULTIPLE_CHOICE, null, active, createdBy, createdAt, updatedAt);
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

    private static String normalizeOptionalText(String value, String field, int maxLength) {
        if (value == null) return "";
        String normalized = value.strip();
        if (normalized.isEmpty()) return "";
        if (normalized.codePointCount(0, normalized.length()) > maxLength) {
            throw new IllegalArgumentException(field + " cannot exceed " + maxLength + " characters");
        }
        return normalized;
    }

    @Override
    public String toString() {
        return "Question[id=" + id + ", topic=" + topic + ", difficulty=" + difficulty
            + ", questionType=" + questionType + ", active=" + active + "]";
    }
}
