package quiz.server.domain;

import org.junit.jupiter.api.Test;
import quiz.common.enums.AntiCheatPolicyType;
import quiz.common.enums.QuizStatus;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/** Domain invariants for the quiz entity. */
class QuizTest {

    @Test
    void createNewIsDraftWithZeroIdAndNoTimestamps() {
        Quiz q = Quiz.createNew("Kiểm tra 15 phút", null, null, 15, 50.0,
            AntiCheatPolicyType.WARN_THEN_AUTO_SUBMIT, 3, 7);

        assertEquals(0, q.id());
        assertNull(q.createdAt());
        assertEquals(QuizStatus.DRAFT, q.status());
        assertNull(q.pin());
        assertTrue(q.openedAt() == null);
        assertTrue(q.closedAt() == null);
    }

    @Test
    void restoreRequiresPositiveIdAndCreatedAt() {
        Instant now = Instant.parse("2026-10-10T00:00:00Z");
        Quiz q = Quiz.restore(1, "Kiểm tra 15 phút", "Mô tả", "Toán", 15, 50.0,
            QuizStatus.OPEN, "PIN123", AntiCheatPolicyType.LOG_ONLY, 3,
            7, now, now, null);

        assertEquals(1, q.id());
        assertEquals(QuizStatus.OPEN, q.status());
        assertEquals("PIN123", q.pin());
        assertEquals(AntiCheatPolicyType.LOG_ONLY, q.anticheatPolicy());
    }

    @Test
    void invalidDurationRejected() {
        assertThrows(IllegalArgumentException.class, () -> Quiz.createNew(
            "Quiz", null, null, 0, 50.0, AntiCheatPolicyType.WARN_THEN_AUTO_SUBMIT, 3, 7));
        assertThrows(IllegalArgumentException.class, () -> Quiz.createNew(
            "Quiz", null, null, -5, 50.0, AntiCheatPolicyType.WARN_THEN_AUTO_SUBMIT, 3, 7));
    }

    @Test
    void passScoreOutOfRangeRejected() {
        assertThrows(IllegalArgumentException.class, () -> Quiz.createNew(
            "Quiz", null, null, 15, -1.0, AntiCheatPolicyType.WARN_THEN_AUTO_SUBMIT, 3, 7));
        assertThrows(IllegalArgumentException.class, () -> Quiz.createNew(
            "Quiz", null, null, 15, 101.0, AntiCheatPolicyType.WARN_THEN_AUTO_SUBMIT, 3, 7));
    }

    @Test
    void nullStatusRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Quiz(0, "Quiz", null, null,
            15, 50.0, null, null, AntiCheatPolicyType.WARN_THEN_AUTO_SUBMIT, 3, 7,
            null, null, null));
    }

    @Test
    void blankTitleRejected() {
        assertThrows(IllegalArgumentException.class, () -> Quiz.createNew(
            "   ", null, null, 15, 50.0, AntiCheatPolicyType.WARN_THEN_AUTO_SUBMIT, 3, 7));
    }

    @Test
    void toStringIncludesIdAndStatus() {
        Quiz q = Quiz.createNew("Kiểm tra", null, null, 15, 50.0,
            AntiCheatPolicyType.WARN_THEN_AUTO_SUBMIT, 3, 7);
        String s = q.toString();
        assertTrue(s.contains("id=0"));
        assertTrue(s.contains("DRAFT"));
    }
}
