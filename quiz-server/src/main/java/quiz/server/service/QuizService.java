package quiz.server.service;

import quiz.common.dto.QuizQuestionsDto;
import quiz.common.enums.QuizStatus;
import quiz.common.enums.Role;
import quiz.server.db.TransactionManager;
import quiz.server.domain.Quiz;
import quiz.server.domain.User;
import quiz.server.exception.ForbiddenException;
import quiz.server.exception.NotFoundException;
import quiz.server.exception.UnauthorizedException;
import quiz.server.exception.ValidationException;
import quiz.server.repository.QuestionRepository;
import quiz.server.repository.QuizRepository;
import quiz.server.repository.UserRepository;

import java.sql.Connection;
import java.util.List;
import java.util.Objects;

/**
 * Quản lý gán câu hỏi vào bài kiểm tra (Quiz). Chỉ TEACHER và ADMIN được phép
 * thao tác. Chỉ có thể chọn câu hỏi khi quiz còn ở trạng thái {@code DRAFT}.
 * Trả về domain objects; handler phụ trách map sang DTO.
 */
public final class QuizService {
    private final QuizRepository quizzes;
    private final QuestionRepository questions;
    private final UserRepository users;
    private final TransactionManager transactions;

    public QuizService(QuizRepository quizzes, QuestionRepository questions,
                       UserRepository users, TransactionManager transactions) {
        this.quizzes = Objects.requireNonNull(quizzes, "QuizRepository cannot be null");
        this.questions = Objects.requireNonNull(questions, "QuestionRepository cannot be null");
        this.users = Objects.requireNonNull(users, "UserRepository cannot be null");
        this.transactions = Objects.requireNonNull(transactions, "TransactionManager cannot be null");
    }

    /** Gán danh sách câu hỏi vào quiz (thay thế toàn bộ danh sách hiện có). Chỉ khi DRAFT. */
    public Quiz setQuestions(long quizId, QuizQuestionsDto dto, long actorId) {
        requireTeacherOrAdmin(actorId);
        if (dto == null) {
            throw new ValidationException("Danh sách câu hỏi là bắt buộc");
        }
        ServiceValidation.positiveId(quizId);
        return transactions.executeInTransaction(connection -> {
            requireTeacherOrAdmin(connection, actorId);
            Quiz quiz = quizzes.findById(connection, quizId)
                .orElseThrow(() -> new NotFoundException("Quiz không tồn tại"));
            if (quiz.status() != QuizStatus.DRAFT) {
                throw new ValidationException("Chỉ có thể chọn câu hỏi khi quiz ở trạng thái DRAFT");
            }
            // Validate all question IDs exist and are active.
            for (Long questionId : dto.questionIds()) {
                if (questionId == null || questionId <= 0) {
                    throw new ValidationException("ID câu hỏi không hợp lệ");
                }
                if (!questions.existsById(questionId)) {
                    throw new NotFoundException("Câu hỏi ID " + questionId + " không tồn tại");
                }
            }
            boolean ok = quizzes.setQuestions(connection, quizId, dto.questionIds());
            if (!ok) {
                throw new NotFoundException("Quiz không tồn tại");
            }
            return quizzes.findById(connection, quizId)
                .orElseThrow(() -> new NotFoundException("Quiz không tồn tại"));
        });
    }

    /** Lấy chi tiết quiz bao gồm danh sách câu hỏi đã gán. */
    public Quiz getQuiz(long quizId, long actorId) {
        requireTeacherOrAdmin(actorId);
        return quizzes.findById(quizId)
            .orElseThrow(() -> new NotFoundException("Quiz không tồn tại"));
    }

    /** Liệt kê tất cả quiz, ID tăng dần. */
    public List<Quiz> listQuizzes(long actorId) {
        requireTeacherOrAdmin(actorId);
        return List.copyOf(quizzes.findAll());
    }

    private void requireTeacherOrAdmin(long actorId) {
        checkActor(users.findById(actorId).orElse(null));
    }

    private void requireTeacherOrAdmin(Connection connection, long actorId) {
        checkActor(users.findById(connection, actorId).orElse(null));
    }

    private static void checkActor(Object actor) {
        if (!(actor instanceof User user) || !user.active()) {
            throw new UnauthorizedException("Người thực hiện không còn hoạt động");
        }
        if (user.role() != Role.TEACHER
            && user.role() != Role.ADMIN) {
            throw new ForbiddenException("Chỉ Giáo viên và Quản trị viên được quản lý quiz");
        }
    }
}
