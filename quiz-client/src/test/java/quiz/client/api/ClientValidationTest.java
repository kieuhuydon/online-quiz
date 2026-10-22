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
    @Test void normalizationPreservesCaseAndPasswordWhitespace() {
        var login = ClientValidation.login("  Admin  ", " x ");
        assertEquals("Admin", login.username()); assertEquals(" x ", login.password());
        var account = ClientValidation.account(new CreateAccountDto(" Student ", " pass123 ", " Nguyễn Việt ", " ", Role.STUDENT));
        assertEquals("Student", account.username()); assertEquals("Nguyễn Việt", account.fullName());
        assertEquals(" pass123 ", account.password()); assertNull(account.email());
    }

    @Test void questionValidationNormalizesAndLimits() {
        var q = ClientValidation.question(new CreateQuestionDto(
            "  Câu hỏi?  ", "  A  ", "B", "C", "D", 'a', "  Ngữ pháp  ", Difficulty.EASY));
        assertEquals("Câu hỏi?", q.content());
        assertEquals("A", q.optionA());
        assertEquals('A', q.correctOption());
        assertEquals("Ngữ pháp", q.topic());
        assertEquals(Difficulty.EASY, q.difficulty());
    }

    @Test void questionInvalidCorrectOptionRejected() {
        assertThrows(ApiException.class, () -> ClientValidation.question(new CreateQuestionDto(
            "Câu?", "A", "B", "C", "D", 'X', "T", Difficulty.EASY)));
    }

    @Test void questionMissingDifficultyRejected() {
        assertThrows(ApiException.class, () -> ClientValidation.question(new CreateQuestionDto(
            "Câu?", "A", "B", "C", "D", 'A', "T", null)));
    }

    @Test void updateQuestionRequiresPositiveId() {
        assertThrows(ApiException.class, () -> ClientValidation.updateQuestion(new UpdateQuestionDto(
            0, "Câu?", "A", "B", "C", "D", 'A', "T", Difficulty.EASY)));
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
