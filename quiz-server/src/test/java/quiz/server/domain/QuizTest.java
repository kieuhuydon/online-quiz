package quiz.server.domain;

import org.junit.jupiter.api.Test;
import quiz.common.enums.AntiCheatPolicyType;
import quiz.common.enums.QuizStatus;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class QuizTest {

    @Test
    void createNewSetsStatusDraftAndZeroId() {
        Quiz quiz = Quiz.createNew("Grammar Test", "Desc", "Grammar", 30, 50.0,
            AntiCheatPolicyType.WARN_THEN_AUTO_SUBMIT, 3, 2);
        assertEquals(0, quiz.id());
        assertEquals("Grammar Test", quiz.title());
        assertEquals(QuizStatus.DRAFT, quiz.status());
        assertNull(quiz.pin());
        assertNull(quiz.createdAt());
        assertNull(quiz.openedAt());
    }

    @Test
    void restoreRestoresAllFields() {
        Instant now = Instant.now();
        Quiz quiz = Quiz.restore(1, "Test", null, "Category", 20, 60.0,
            QuizStatus.OPEN, "123456", AntiCheatPolicyType.WARN_THEN_AUTO_SUBMIT, 3, 2, now, now, null);
        assertEquals(1, quiz.id());
        assertEquals(QuizStatus.OPEN, quiz.status());
        assertEquals("123456", quiz.pin());
        assertEquals(now, quiz.openedAt());
    }

    @Test
    void rejectsInvalidDuration() {
        assertThrows(IllegalArgumentException.class, () ->
            Quiz.createNew("Test", "Desc", "Cat", 0, 50.0,
                AntiCheatPolicyType.WARN_THEN_AUTO_SUBMIT, 3, 2));
    }

    @Test
    void rejectsInvalidPassScore() {
        assertThrows(IllegalArgumentException.class, () ->
            Quiz.createNew("Test", "Desc", "Cat", 30, 105.0,
                AntiCheatPolicyType.WARN_THEN_AUTO_SUBMIT, 3, 2));
    }
}
