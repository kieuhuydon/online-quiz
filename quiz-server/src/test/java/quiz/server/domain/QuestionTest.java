package quiz.server.domain;

import org.junit.jupiter.api.Test;
import quiz.common.enums.Difficulty;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class QuestionTest {

    @Test
    void createNewCreatesActiveQuestion() {
        Question q = Question.createNew("Content?", "A", "B", "C", "D", 'A', "Grammar", Difficulty.EASY, 2);
        assertEquals(0, q.id());
        assertEquals("Content?", q.content());
        assertEquals('A', q.correctOption());
        assertTrue(q.active());
        assertNull(q.createdAt());
    }

    @Test
    void restoreReconstructsQuestion() {
        Instant now = Instant.now();
        Question q = Question.restore(5, "Content?", "A", "B", "C", "D", 'C', "Grammar", Difficulty.HARD, true, 2, now, now);
        assertEquals(5, q.id());
        assertEquals('C', q.correctOption());
        assertEquals(now, q.createdAt());
    }

    @Test
    void rejectsInvalidOption() {
        assertThrows(IllegalArgumentException.class, () ->
            Question.createNew("Content?", "A", "B", "C", "D", 'E', "Grammar", Difficulty.EASY, 2));
    }
}
