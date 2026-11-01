package quiz.client.ui.teacher;

import org.junit.jupiter.api.Test;
import quiz.common.dto.QuestionDto;
import quiz.common.enums.Difficulty;
import quiz.common.enums.QuestionType;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class TeacherQuestionAnswerDisplayTest {
    @Test
    void freeResponsePreviewShowsTextAnswerAndExplanationInsteadOfMarker() {
        QuestionDto question = new QuestionDto(1, "She ___ at this company.", "", "", "", "", 'A',
            "Verb Tenses", Difficulty.MEDIUM, QuestionType.FILL_BLANK,
            "Use the present perfect.", true, 2, Instant.parse("2026-11-01T00:00:00Z"), "has worked");

        assertEquals("has worked", TeacherController.getCorrectOptionText(question));
        String preview = TeacherController.formatQuestionPreview(question);
        assertTrue(preview.contains("has worked"));
        assertTrue(preview.contains("Use the present perfect."));
        assertFalse(preview.contains("A ("));
    }
}
