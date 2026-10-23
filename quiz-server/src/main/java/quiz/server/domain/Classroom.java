package quiz.server.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Entity lớp học phía server (bất biến, không Serializable).
 */
public record Classroom(
    long id,
    String name,
    String description,
    long teacherId,
    Instant createdAt
) {
    public static final int MAX_NAME_LENGTH = 100;
    public static final int MAX_DESCRIPTION_LENGTH = 255;

    public Classroom {
        if (id < 0) {
            throw new IllegalArgumentException("Class ID cannot be negative");
        }
        if (id == 0 && createdAt != null) {
            throw new IllegalArgumentException("New class cannot have createdAt timestamp");
        }
        if (id > 0 && createdAt == null) {
            throw new IllegalArgumentException("Persisted class must have createdAt timestamp");
        }
        if (name == null || name.strip().isBlank()) {
            throw new IllegalArgumentException("Class name cannot be blank");
        }
        name = name.strip();
        if (name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("Class name exceeds maximum length: " + MAX_NAME_LENGTH);
        }
        if (description != null) {
            description = description.strip();
            if (description.isEmpty()) {
                description = null;
            } else if (description.length() > MAX_DESCRIPTION_LENGTH) {
                throw new IllegalArgumentException("Class description exceeds maximum length: " + MAX_DESCRIPTION_LENGTH);
            }
        }
        if (teacherId <= 0) {
            throw new IllegalArgumentException("Teacher ID must be positive");
        }
    }

    public static Classroom createNew(String name, String description, long teacherId) {
        return new Classroom(0, name, description, teacherId, null);
    }

    public static Classroom restore(long id, String name, String description, long teacherId, Instant createdAt) {
        return new Classroom(id, name, description, teacherId, Objects.requireNonNull(createdAt, "createdAt cannot be null"));
    }
}
