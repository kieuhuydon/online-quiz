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
import java.util.LinkedHashMap;
import java.util.Map;

/** Form validation matching the server contract; the server remains authoritative. */
public final class ClientValidation {
    public enum AccountField { USERNAME, FULL_NAME, EMAIL, ROLE, PASSWORD }
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
        quiz.common.enums.QuestionType type = input.questionType() == null ? quiz.common.enums.QuestionType.MULTIPLE_CHOICE : input.questionType();
        return new CreateQuestionDto(text(input.content(), 1000),
            text(input.optionA(), 255), text(input.optionB(), 255),
            text(input.optionC(), 255), text(input.optionD(), 255),
            correctOption(input.correctOption()), text(input.topic(), 100), input.difficulty(), input.questionType(), optionalText(input.explanation(), 2000));
    }

    public static UpdateQuestionDto updateQuestion(UpdateQuestionDto input) {
        if (input == null || input.difficulty() == null) {
            throw invalid();
        }
        positiveId(input.id());
        quiz.common.enums.QuestionType type = input.questionType() == null ? quiz.common.enums.QuestionType.MULTIPLE_CHOICE : input.questionType();
        return new UpdateQuestionDto(input.id(), text(input.content(), 1000),
            text(input.optionA(), 255), text(input.optionB(), 255),
            text(input.optionC(), 255), text(input.optionD(), 255),
            correctOption(input.correctOption()), text(input.topic(), 100), input.difficulty(), input.questionType(), optionalText(input.explanation(), 2000));

    }

    /** Ordered field errors for the creation form; never includes entered values. */
    public static Map<AccountField, String> accountErrors(CreateAccountDto input) {
        Map<AccountField, String> errors = new LinkedHashMap<>();
        checkText(errors, AccountField.USERNAME, input.username(), 50,
            "Vui lòng nhập tên đăng nhập.", "Tên đăng nhập tối đa 50 ký tự.");
        checkText(errors, AccountField.FULL_NAME, input.fullName(), 100,
            "Vui lòng nhập họ và tên.", "Họ và tên tối đa 100 ký tự.");
        String email = input.email() == null ? "" : input.email().strip();
        if (email.codePointCount(0, email.length()) > 100) {
            errors.put(AccountField.EMAIL, "Email tối đa 100 ký tự.");
        } else if (!email.isEmpty() && !EMAIL.matcher(email).matches()) {
            errors.put(AccountField.EMAIL, "Email không đúng định dạng.");
        }
        if (input.role() != Role.TEACHER && input.role() != Role.STUDENT) {
            errors.put(AccountField.ROLE, "Vui lòng chọn Giáo viên hoặc Học viên.");
        }
        String password = input.password();
        if (password == null || password.isBlank()) {
            errors.put(AccountField.PASSWORD, "Vui lòng nhập mật khẩu.");
        } else if (password.codePointCount(0, password.length()) < 8) {
            errors.put(AccountField.PASSWORD, "Mật khẩu phải có ít nhất 8 ký tự.");
        } else if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            errors.put(AccountField.PASSWORD, "Mật khẩu vượt giới hạn cho phép; hãy rút ngắn mật khẩu.");
        }
        return java.util.Collections.unmodifiableMap(errors);
    }

    private static void checkText(Map<AccountField, String> errors, AccountField field, String input,
                                  int limit, String required, String tooLong) {
        String value = input == null ? "" : input.strip();
        if (value.isBlank()) errors.put(field, required);
        else if (value.codePointCount(0, value.length()) > limit) errors.put(field, tooLong);
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

    private static String option(String input, quiz.common.enums.QuestionType type, String fallback) {
        return type == quiz.common.enums.QuestionType.MULTIPLE_CHOICE ? text(input, 255)
            : (input == null || input.isBlank() ? fallback : text(input, 255));
    }

    private static String password(String value, boolean creating) {
        if (value == null || value.isBlank() || value.length() > 72
            || value.getBytes(StandardCharsets.UTF_8).length > 72
            || (creating && value.codePointCount(0, value.length()) < 8)) {
            throw invalid();
        }
        return value;
    }

    private static String optionalText(String input, int limit) {
        if (input == null) return null;
        String value = input.strip();
        if (value.isEmpty()) return null;
        if (value.codePointCount(0, value.length()) > limit) {
            throw invalid();
        }
        return value;
    }

    private static ApiException invalid() {
        return new ApiException(ErrorCode.VALIDATION_ERROR);
    }
}

