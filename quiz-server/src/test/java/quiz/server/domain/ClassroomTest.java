package quiz.server.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class ClassroomTest {

    @Test
    void createNewCreatesClassroomWithZeroIdAndNullTimestamp() {
        Classroom c = Classroom.createNew("ENG-B1-01", "Morning class", 2);
        assertEquals(0, c.id());
        assertEquals("ENG-B1-01", c.name());
        assertEquals("Morning class", c.description());
        assertEquals(2, c.teacherId());
        assertNull(c.createdAt());
    }

    @Test
    void restoreCreatesPersistedClassroom() {
        Instant now = Instant.now();
        Classroom c = Classroom.restore(10, "ENG-B2-01", null, 3, now);
        assertEquals(10, c.id());
        assertEquals("ENG-B2-01", c.name());
        assertNull(c.description());
        assertEquals(now, c.createdAt());
    }

    @Test
    void rejectsBlankName() {
        assertThrows(IllegalArgumentException.class, () -> Classroom.createNew("   ", "desc", 2));
    }

    @Test
    void rejectsInvalidTeacherId() {
        assertThrows(IllegalArgumentException.class, () -> Classroom.createNew("Class", "desc", 0));
    }
}
