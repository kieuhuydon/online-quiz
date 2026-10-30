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

    @Test
    void validatesIdTimestampAndLengthBoundaries() {
        Instant now = Instant.now();
        assertThrows(IllegalArgumentException.class, () -> new Classroom(-1, "Class", null, 2, null));
        assertThrows(IllegalArgumentException.class, () -> new Classroom(0, "Class", null, 2, now));
        assertThrows(IllegalArgumentException.class, () -> new Classroom(1, "Class", null, 2, null));
        assertThrows(IllegalArgumentException.class, () -> Classroom.createNew("x".repeat(101), null, 2));
        assertThrows(IllegalArgumentException.class, () -> Classroom.createNew("Class", "d".repeat(256), 2));

        Classroom blankDesc = Classroom.createNew("  ENG-01  ", "   ", 2);
        assertEquals("ENG-01", blankDesc.name());
        assertNull(blankDesc.description());
    }
}
