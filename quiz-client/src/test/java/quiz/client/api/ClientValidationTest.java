package quiz.client.api;

import org.junit.jupiter.api.Test;
import quiz.common.dto.CreateAccountDto;
import quiz.common.dto.CreateQuestionDto;
import quiz.common.dto.UpdateQuestionDto;
import quiz.common.enums.Difficulty;
import quiz.common.enums.Role;
import quiz.common.protocol.ErrorCode;
import static org.junit.jupiter.api.Assertions.*;

class ClientValidationTest {
    @Test void creationErrorsIdentifyFieldsAndPreserveValidationBoundaries() {
        var invalid = new CreateAccountDto(" ", "short", " ", "bad-email", Role.ADMIN);
        var errors = ClientValidation.accountErrors(invalid);
        assertEquals(java.util.Set.of(ClientValidation.AccountField.values()), errors.keySet());
        assertTrue(errors.get(ClientValidation.AccountField.EMAIL).contains("định dạng"));
        assertTrue(errors.get(ClientValidation.AccountField.PASSWORD).contains("8 ký tự"));
        assertFalse(errors.values().stream().anyMatch(message -> message.contains("bad-email")));
        assertTrue(ClientValidation.accountErrors(dto("ế".repeat(24), " ", Role.STUDENT)).isEmpty());
        assertTrue(ClientValidation.accountErrors(dto("ế".repeat(25), null, Role.TEACHER))
            .containsKey(ClientValidation.AccountField.PASSWORD));
    }
    @Test void normalizationPreservesCaseAndPasswordWhitespace() {
        var login = ClientValidation.login("  Admin  ", " x ");
        assertEquals("Admin", login.username()); assertEquals(" x ", login.password());
        var account = ClientValidation.account(new CreateAccountDto(" Student ", " pass123 ", " Nguyễn Việt ", " ", Role.STUDENT));
        assertEquals("Student", account.username()); assertEquals("Nguyễn Việt", account.fullName());
        assertEquals(" pass123 ", account.password()); assertNull(account.email());
    }

    @Test void questionMissingExplanationRejected() {
        assertThrows(ApiException.class, () -> ClientValidation.question(new CreateQuestionDto(
            "Câu?", "A", "B", "C", "D", 'A', "T", Difficulty.EASY, quiz.common.enums.QuestionType.MULTIPLE_CHOICE, null)));
        assertThrows(ApiException.class, () -> ClientValidation.question(new CreateQuestionDto(
            "Câu?", "A", "B", "C", "D", 'A', "T", Difficulty.EASY, quiz.common.enums.QuestionType.MULTIPLE_CHOICE, "   ")));
    }

    @Test void questionUnderscoreValidation() {
        // Đúng 3 gạch dưới -> Hợp lệ
        assertDoesNotThrow(() -> ClientValidation.question(new CreateQuestionDto(
            "Câu ___ điền khuyết", "A", "B", "C", "D", 'A', "T", Difficulty.EASY, quiz.common.enums.QuestionType.FILL_BLANK, "Giải thích")));

        // 1, 2, 4 gạch dưới -> Bị từ chối
        assertThrows(ApiException.class, () -> ClientValidation.question(new CreateQuestionDto(
            "Câu _ điền khuyết", "A", "B", "C", "D", 'A', "T", Difficulty.EASY, quiz.common.enums.QuestionType.FILL_BLANK, "Giải thích")));
        assertThrows(ApiException.class, () -> ClientValidation.question(new CreateQuestionDto(
            "Câu __ điền khuyết", "A", "B", "C", "D", 'A', "T", Difficulty.EASY, quiz.common.enums.QuestionType.FILL_BLANK, "Giải thích")));
        assertThrows(ApiException.class, () -> ClientValidation.question(new CreateQuestionDto(
            "Câu ____ điền khuyết", "A", "B", "C", "D", 'A', "T", Difficulty.EASY, quiz.common.enums.QuestionType.FILL_BLANK, "Giải thích")));
    }

    @Test void textQuestionAnswerIsStoredAsKeyAndNotAsChoices() {
        var fill = ClientValidation.question(new CreateQuestionDto(
            "She ___ here.", "works", "-", "-", "-", 'A', "English", Difficulty.EASY,
            quiz.common.enums.QuestionType.FILL_BLANK, "Present simple"));
        assertEquals("works", fill.answerKey());
        assertEquals("", fill.optionA());
        assertEquals("", fill.optionB());
        assertEquals('A', fill.correctOption());

        var mcq = ClientValidation.question(new CreateQuestionDto(
            "Choose one", "one", "two", "three", "four", 'B', "English", Difficulty.EASY,
            quiz.common.enums.QuestionType.MULTIPLE_CHOICE, "Explanation"));
        assertEquals("one", mcq.optionA());
        assertEquals("four", mcq.optionD());
        assertEquals("", mcq.answerKey());
    }

    @Test void fillBlankRequiresOneGapAndAllowsMultiWordAnswer() {
        assertThrows(ApiException.class, () -> ClientValidation.question(new CreateQuestionDto(
            "She ___ and he ___ every day.", "works", "-", "-", "-", 'A', "English", Difficulty.EASY,
            quiz.common.enums.QuestionType.FILL_BLANK, "Use the present simple.")));
        var multiWord = ClientValidation.question(new CreateQuestionDto(
            "They ___ dinner.", "are having", "-", "-", "-", 'A', "English", Difficulty.EASY,
            quiz.common.enums.QuestionType.FILL_BLANK, "Use the present continuous."));
        assertEquals("are having", multiWord.answerKey());
    }

    @Test void questionValidationNormalizesAndLimits() {
        var q = ClientValidation.question(new CreateQuestionDto(
            "  Câu hỏi?  ", "  A  ", "B", "C", "D", 'a', "  Ngữ pháp  ", Difficulty.EASY, quiz.common.enums.QuestionType.MULTIPLE_CHOICE, "  Giải thích chi tiết  "));
        assertEquals("Câu hỏi?", q.content());
        assertEquals("A", q.optionA());
        assertEquals('A', q.correctOption());
        assertEquals("Ngữ pháp", q.topic());
        assertEquals(Difficulty.EASY, q.difficulty());
        assertEquals("Giải thích chi tiết", q.explanation());
    }

    @Test void questionInvalidCorrectOptionRejected() {
        assertThrows(ApiException.class, () -> ClientValidation.question(new CreateQuestionDto(
            "Câu?", "A", "B", "C", "D", 'X', "T", Difficulty.EASY, quiz.common.enums.QuestionType.MULTIPLE_CHOICE, "Giải thích")));
    }

    @Test void questionMissingDifficultyRejected() {
        assertThrows(ApiException.class, () -> ClientValidation.question(new CreateQuestionDto(
            "Câu?", "A", "B", "C", "D", 'A', "T", null, quiz.common.enums.QuestionType.MULTIPLE_CHOICE, "Giải thích")));
    }

    @Test void updateQuestionRequiresPositiveId() {
        assertThrows(ApiException.class, () -> ClientValidation.updateQuestion(new UpdateQuestionDto(
            0, "Câu?", "A", "B", "C", "D", 'A', "T", Difficulty.EASY, quiz.common.enums.QuestionType.MULTIPLE_CHOICE, "Giải thích")));
    }

    @Test void unicodeLimitsUseCodePointsAndPasswordBytes() {
        String emoji = "\uD83D\uDE00";
        assertEquals(emoji.repeat(50), ClientValidation.login(emoji.repeat(50), emoji.repeat(18)).username());
        assertThrows(ApiException.class, () -> ClientValidation.login(emoji.repeat(51), "x"));
        assertThrows(ApiException.class, () -> ClientValidation.login("user", emoji.repeat(19)));
        assertDoesNotThrow(() -> ClientValidation.login("user", "x")); // Existing short password.
        assertThrows(ApiException.class, () -> ClientValidation.account(dto(emoji.repeat(7), "valid@example.com", Role.TEACHER)));
        assertDoesNotThrow(() -> ClientValidation.account(dto(emoji.repeat(8), "valid@example.com", Role.TEACHER)));
        assertDoesNotThrow(() -> ClientValidation.login("user", "x".repeat(72)));
        assertThrows(ApiException.class, () -> ClientValidation.login("user", "x".repeat(73)));
    }

    @Test void invalidFormsHaveSafeErrors() {
        for (String password : new String[] {null, "", "   "}) {
            assertEquals(ErrorCode.VALIDATION_ERROR, assertThrows(ApiException.class,
                () -> ClientValidation.login("user", password)).errorCode());
        }
        assertThrows(ApiException.class, () -> ClientValidation.login(null, "password"));
        assertThrows(ApiException.class, () -> ClientValidation.account(dto("password", "missing-dot@local", Role.STUDENT)));
        assertThrows(ApiException.class, () -> ClientValidation.account(dto("password", "a\u2003b@example.com", Role.STUDENT)));
        assertThrows(ApiException.class, () -> ClientValidation.account(null));
        assertThrows(ApiException.class, () -> ClientValidation.positiveId(0));
        assertEquals(ErrorCode.FORBIDDEN, assertThrows(ApiException.class,
            () -> ClientValidation.account(dto("password", null, Role.ADMIN))).errorCode());
    }

    private static CreateAccountDto dto(String password, String email, Role role) {
        return new CreateAccountDto("user", password, "User", email, role);
    }
}

