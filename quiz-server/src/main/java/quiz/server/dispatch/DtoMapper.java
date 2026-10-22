package quiz.server.dispatch;

import quiz.common.dto.AccountDto;
import quiz.common.dto.LoginResultDto;
import quiz.common.dto.QuestionDto;
import quiz.common.dto.QuizDto;
import quiz.server.domain.Question;
import quiz.server.domain.Quiz;
import quiz.server.domain.User;
import quiz.server.service.AuthResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Auth/Account, Question and Quiz wire mapping; password hashes are deliberately excluded. */
public final class DtoMapper {
    private DtoMapper() {
    }

    public static AccountDto account(User user) {
        Objects.requireNonNull(user, "User cannot be null");
        return new AccountDto(user.id(), user.username(), user.fullName(), user.email(), user.role(),
            user.active(), user.createdBy(), user.createdAt());
    }

    public static LoginResultDto login(AuthResult result) {
        Objects.requireNonNull(result, "AuthResult cannot be null");
        User user = result.user();
        return new LoginResultDto(result.token(), user.id(), user.username(), user.fullName(), user.role());
    }

    public static ArrayList<AccountDto> accounts(List<User> users) {
        Objects.requireNonNull(users, "Users cannot be null");
        ArrayList<AccountDto> accounts = new ArrayList<>(users.size());
        for (User user : users) {
            accounts.add(account(user));
        }
        return accounts;
    }

    public static QuestionDto question(Question question) {
        Objects.requireNonNull(question, "Question cannot be null");
        return new QuestionDto(question.id(), question.content(), question.optionA(), question.optionB(),
            question.optionC(), question.optionD(), question.correctOption(), question.topic(),
            question.difficulty(), question.active(), question.createdBy(), question.createdAt());
    }

    public static ArrayList<QuestionDto> questions(List<Question> questionList) {
        Objects.requireNonNull(questionList, "Questions cannot be null");
        ArrayList<QuestionDto> dtos = new ArrayList<>(questionList.size());
        for (Question question : questionList) {
            dtos.add(question(question));
        }
        return dtos;
    }

    public static QuizDto quiz(Quiz quiz) {
        Objects.requireNonNull(quiz, "Quiz cannot be null");
        return new QuizDto(quiz.id(), quiz.title(), quiz.description(), quiz.category(),
            quiz.durationMinutes(), quiz.passScore(), quiz.status(), quiz.pin(),
            quiz.anticheatPolicy(), quiz.maxViolations(), quiz.createdBy(), quiz.createdAt(),
            quiz.openedAt(), quiz.closedAt());
    }

    public static ArrayList<QuizDto> quizzes(List<Quiz> quizList) {
        Objects.requireNonNull(quizList, "Quizzes cannot be null");
        ArrayList<QuizDto> dtos = new ArrayList<>(quizList.size());
        for (Quiz quiz : quizList) {
            dtos.add(quiz(quiz));
        }
        return dtos;
    }
}
