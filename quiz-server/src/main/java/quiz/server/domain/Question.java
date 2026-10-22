package quiz.server.domain;

import quiz.common.enums.Difficulty;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * Entity câu hỏi trắc nghiệm phía server (bất biến, không Serializable).
 */
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
    private static final Set<Character> VALID_OPTIONS = Set.of('A', 'B', 'C', 'D');
    public static final int MAX_TOPIC_LENGTH = 100;

    public Question {
        if (id < 0) {
            throw new IllegalArgumentException("Question ID cannot be negative");
        }
        if (id == 0 && (createdAt != null || updatedAt != null)) {
            throw new IllegalArgumentException("New question cannot have timestamps");
        }
        if (id > 0 && (createdAt == null || updatedAt == null)) {
            throw new IllegalArgumentException("Persisted question must have timestamps");
        }
        if (content == null || content.strip().isBlank()) {
            throw new IllegalArgumentException("Question content cannot be blank");
        }
        content = content.strip();
        if (optionA == null || optionA.strip().isBlank()) throw new IllegalArgumentException("Option A cannot be blank");
        if (optionB == null || optionB.strip().isBlank()) throw new IllegalArgumentException("Option B cannot be blank");
        if (optionC == null || optionC.strip().isBlank()) throw new IllegalArgumentException("Option C cannot be blank");
        if (optionD == null || optionD.strip().isBlank()) throw new IllegalArgumentException("Option D cannot be blank");
        optionA = optionA.strip();
        optionB = optionB.strip();
        optionC = optionC.strip();
        optionD = optionD.strip();

        correctOption = Character.toUpperCase(correctOption);
        if (!VALID_OPTIONS.contains(correctOption)) {
            throw new IllegalArgumentException("Correct option must be A, B, C, or D");
        }
        if (topic == null || topic.strip().isBlank()) {
            throw new IllegalArgumentException("Topic cannot be blank");
        }
        topic = topic.strip();
        if (topic.length() > MAX_TOPIC_LENGTH) {
            throw new IllegalArgumentException("Topic exceeds maximum length: " + MAX_TOPIC_LENGTH);
        }
        if (difficulty == null) {
            throw new IllegalArgumentException("Difficulty cannot be null");
        }
        if (createdBy <= 0) {
            throw new IllegalArgumentException("Creator ID must be positive");
        }
    }

    public static Question createNew(String content, String optionA, String optionB, String optionC, String optionD,
                                      char correctOption, String topic, Difficulty difficulty, long createdBy) {
        return new Question(0, content, optionA, optionB, optionC, optionD, correctOption, topic, difficulty, true, createdBy, null, null);
    }

    public static Question restore(long id, String content, String optionA, String optionB, String optionC, String optionD,
                                   char correctOption, String topic, Difficulty difficulty, boolean active,
                                   long createdBy, Instant createdAt, Instant updatedAt) {
        return new Question(id, content, optionA, optionB, optionC, optionD, correctOption, topic, difficulty, active,
            createdBy, Objects.requireNonNull(createdAt), Objects.requireNonNull(updatedAt));
    }
}
