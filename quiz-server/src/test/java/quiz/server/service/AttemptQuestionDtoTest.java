package quiz.server.service;

import org.junit.jupiter.api.Test;
import quiz.common.dto.StudentQuestionDto;
import quiz.common.enums.Difficulty;
import quiz.common.enums.QuestionType;
import quiz.server.domain.Question;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AttemptQuestionDtoTest {
    private static final Instant NOW = Instant.parse("2026-11-01T00:00:00Z");

    @Test
    void studentPayloadKeepsAllChoicesAndTrueFalseLabels() {
        Question multipleChoice = question(1, "Pick one", "First", "Second", "Third", "Fourth",
            'B', QuestionType.MULTIPLE_CHOICE, "");
        Question trueFalse = question(2, "The Earth is flat.", "True", "False", "", "",
            'B', QuestionType.TRUE_FALSE, "");
        Question fillBlank = question(3, "She ___ every day.", "", "", "", "",
            'A', QuestionType.FILL_BLANK, "works");

        StudentQuestionDto mcqDto = AttemptService.toStudentQuestionDto(1, multipleChoice);
        StudentQuestionDto trueFalseDto = AttemptService.toStudentQuestionDto(2, trueFalse);
        StudentQuestionDto fillDto = AttemptService.toStudentQuestionDto(3, fillBlank);

        assertEquals("First", mcqDto.optionA());
        assertEquals("Second", mcqDto.optionB());
        assertEquals("Third", mcqDto.optionC());
        assertEquals("Fourth", mcqDto.optionD());
        assertEquals("True", trueFalseDto.optionA());
        assertEquals("False", trueFalseDto.optionB());
        assertEquals(1, fillDto.blankCount());
        assertEquals("", fillDto.optionA());
    }

    private static Question question(long id, String content, String a, String b, String c, String d,
                                     char correct, QuestionType type, String answerKey) {
        return Question.restore(id, content, a, b, c, d, correct, "English", Difficulty.EASY,
            type, true, 2, NOW, NOW, answerKey, "Example explanation");
    }
}
