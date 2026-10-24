package quiz.client.api;

import quiz.common.dto.CreateAccountDto;
import quiz.common.dto.CreateQuestionDto;
import quiz.common.dto.LoginRequestDto;
import quiz.common.dto.UpdateQuestionDto;
import quiz.common.enums.Difficulty;
import quiz.common.enums.Role;
import quiz.common.protocol.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/** Form validation matching the server contract; the server remains authoritative. */
public final class ClientValidation {
    private static final Pattern EMAIL = Pattern.compile("[^\\s@]+@[^\\s@]+\\.[^\\s@]+", Pattern.UNICODE_CHARACTER_CLASS);
    private ClientValidation() { }

    public static LoginRequestDto login(String username, String password) {
        return new LoginRequestDto(text(username, 50), password(password, false));
    }

    public static CreateAccountDto account(CreateAccountDto input) {
        if (input == null || input.role() == null) {
            throw invalid();
        }
        if (input.role() != Role.TEACHER && input.role() != Role.STUDENT) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        String email = input.email() == null || input.email().isBlank() ? null : text(input.email(), 100);
        if (email != null && !EMAIL.matcher(email).matches()) {
            throw invalid();
        }
        return new CreateAccountDto(text(input.username(), 50), password(input.password(), true),
            text(input.fullName(), 100), email, input.role());
    }

    public static CreateQuestionDto question(CreateQuestionDto input) {
        if (input == null || input.difficulty() == null) {
            throw invalid();
        }
        return new CreateQuestionDto(text(input.content(), 1000),
            text(input.optionA(), 255), text(input.optionB(), 255),
            text(input.optionC(), 255), text(input.optionD(), 255),
            correctOption(input.correctOption()), text(input.topic(), 100), input.difficulty());
    }

    public static UpdateQuestionDto updateQuestion(UpdateQuestionDto input) {
        if (input == null || input.difficulty() == null) {
            throw invalid();
        }
        positiveId(input.id());
        return new UpdateQuestionDto(input.id(), text(input.content(), 1000),
            text(input.optionA(), 255), text(input.optionB(), 255),
            text(input.optionC(), 255), text(input.optionD(), 255),
            correctOption(input.correctOption()), text(input.topic(), 100), input.difficulty());
    }

    public static void positiveId(long id) {
        if (id <= 0) {
            throw invalid();
        }
    }

    private static char correctOption(char value) {
        char upper = Character.toUpperCase(value);
        if (upper != 'A' && upper != 'B' && upper != 'C' && upper != 'D') {
            throw invalid();
        }
        return upper;
    }

    private static String text(String input, int limit) {
        if (input == null) {
            throw invalid();
        }
        String value = input.strip();
        if (value.isBlank() || value.codePointCount(0, value.length()) > limit) {
            throw invalid();
        }
        return value;
    }

    private static String password(String value, boolean creating) {
        if (value == null || value.isBlank() || value.length() > 72
            || value.getBytes(StandardCharsets.UTF_8).length > 72
            || (creating && value.codePointCount(0, value.length()) < 8)) {
            throw invalid();
        }
        return value;
    }

    private static ApiException invalid() {
        return new ApiException(ErrorCode.VALIDATION_ERROR);
    }
}
