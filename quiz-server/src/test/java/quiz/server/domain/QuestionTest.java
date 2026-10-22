package quiz.server.domain;

import org.junit.jupiter.api.Test;
import quiz.common.enums.Difficulty;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/** Domain invariants for the question entity. */
class QuestionTest {

    @Test
    void createNewHasZeroIdAndNoTimestamps() {
        Question q = Question.createNew("Câu hỏi mẫu?", "A", "B", "C", "D", 'A',
            "Ngữ pháp", Difficulty.EASY, 7);

        assertEquals(0, q.id());
        assertNull(q.createdAt());
        assertNull(q.updatedAt());
        assertTrue(q.active());
        assertEquals(7, q.createdBy());
    }

    @Test
    void restoreRequiresPositiveIdAndTimestamps() {
        Question q = Question.restore(1, "Câu hỏi mẫu?", "A", "B", "C", "D", 'B',
            "Ngữ pháp", Difficulty.MEDIUM, true, 7,
            Instant.parse("2026-10-10T00:00:00Z"),
            Instant.parse("2026-10-11T00:00:00Z"));

        assertEquals(1, q.id());
        assertEquals('B', q.correctOption());
        assertTrue(q.active());
        assertEquals(Difficulty.MEDIUM, q.difficulty());
    }

    @Test
    void invalidCorrectOptionRejected() {
        assertThrows(IllegalArgumentException.class, () -> Question.createNew(
            "Câu hỏi?", "A", "B", "C", "D", 'X', "Ngữ pháp", Difficulty.EASY, 7));
    }

    @Test
    void blankFieldsRejected() {
        assertThrows(IllegalArgumentException.class, () -> Question.createNew(
            "   ", "A", "B", "C", "D", 'A', "Ngữ pháp", Difficulty.EASY, 7));
        assertThrows(IllegalArgumentException.class, () -> Question.createNew(
            "Câu hỏi?", "   ", "B", "C", "D", 'A', "Ngữ pháp", Difficulty.EASY, 7));
    }

    @Test
    void lengthLimitsEnforced() {
        String longContent = "x".repeat(1001);
        assertThrows(IllegalArgumentException.class, () -> Question.createNew(
            longContent, "A", "B", "C", "D", 'A', "Ngữ pháp", Difficulty.EASY, 7));

        String longTopic = "x".repeat(101);
        assertThrows(IllegalArgumentException.class, () -> Question.createNew(
            "Câu hỏi?", "A", "B", "C", "D", 'A', longTopic, Difficulty.EASY, 7));
    }

    @Test
    void nullDifficultyRejected() {
        assertThrows(IllegalArgumentException.class, () -> Question.createNew(
            "Câu hỏi?", "A", "B", "C", "D", 'A', "Ngữ pháp", null, 7));
    }

    @Test
    void toStringExcludesContentAndOptions() {
        Question q = Question.createNew("Câu hỏi?", "A", "B", "C", "D", 'A',
            "Ngữ pháp", Difficulty.EASY, 7);

        String s = q.toString();
        assertTrue(s.contains("id=0"));
        assertTrue(s.contains("Ngữ pháp"));
        assertTrue(s.contains("EASY"));
        assertTrue(s.contains("active=true"));
    }
}
