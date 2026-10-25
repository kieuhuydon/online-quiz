package quiz.server.service;

import quiz.common.dto.CreateQuizDto;
import quiz.common.dto.QuestionDto;
import quiz.common.dto.QuizDetailDto;
import quiz.common.dto.QuizDto;
import quiz.common.dto.QuizInfoDto;
import quiz.common.dto.QuizQuestionsDto;
import quiz.common.dto.ResultDto;
import quiz.common.dto.UpdateQuizDto;
import quiz.common.enums.AntiCheatPolicyType;
import quiz.common.enums.QuizStatus;
import quiz.common.enums.Role;
import quiz.server.db.TransactionManager;
import quiz.server.domain.Question;
import quiz.server.domain.Quiz;
import quiz.server.domain.User;
import quiz.server.exception.ConflictException;
import quiz.server.exception.ForbiddenException;
import quiz.server.exception.NotFoundException;
import quiz.server.exception.UnauthorizedException;
import quiz.server.exception.ValidationException;
import quiz.server.network.UserSession;
import quiz.server.repository.ClassRepository;
import quiz.server.repository.QuestionRepository;
import quiz.server.repository.QuizRepository;
import quiz.server.repository.UserRepository;

import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Service quản lý bài kiểm tra (Quiz) và ngân hàng câu hỏi gắn vào Quiz.
 */
public final class QuizService {
    private final QuizRepository quizzes;
    private final QuestionRepository questions;
    private final UserRepository users;
    private final TransactionManager transactions;
    private final ClassRepository classes;
    private final SecureRandom random = new SecureRandom();

    public QuizService(QuizRepository quizzes, QuestionRepository questions, UserRepository users,
                       TransactionManager transactions, ClassRepository classes) {
        this.quizzes = Objects.requireNonNull(quizzes, "QuizRepository cannot be null");
        this.questions = Objects.requireNonNull(questions, "QuestionRepository cannot be null");
        this.users = users;
        this.transactions = transactions;
        this.classes = classes;
    }

    public QuizService(QuizRepository quizzes, QuestionRepository questions,
                       UserRepository users, TransactionManager transactions) {
        this(quizzes, questions, users, transactions, null);
    }

    public QuizService(QuizRepository quizzes, QuestionRepository questions, ClassRepository classes) {
        this(quizzes, questions, null, null, classes);
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

    /** Lấy chi tiết quiz bao gồm danh sách câu hỏi đã gán (theo actorId). */
    public Quiz getQuiz(long quizId, long actorId) {
        requireTeacherOrAdmin(actorId);
        return quizzes.findById(quizId)
            .orElseThrow(() -> new NotFoundException("Quiz không tồn tại"));
    }

    /** Liệt kê tất cả quiz, ID tăng dần (theo actorId). */
    public List<Quiz> listQuizzes(long actorId) {
        requireTeacherOrAdmin(actorId);
        return List.copyOf(quizzes.findAll());
    }

    // --- Quiz & Assignment milestone methods ---

    public QuizDto createQuiz(CreateQuizDto dto, UserSession session) {
        Objects.requireNonNull(session, "UserSession cannot be null");
        if (dto == null) {
            throw new ValidationException("Thông tin đề kiểm tra không được để trống");
        }
        if (dto.title() == null || dto.title().strip().isBlank()) {
            throw new ValidationException("Tiêu đề đề kiểm tra không được để trống");
        }
        if (dto.durationMinutes() <= 0) {
            throw new ValidationException("Thời lượng làm bài phải lớn hơn 0");
        }
        if (dto.passScore() < 0 || dto.passScore() > 100) {
            throw new ValidationException("Điểm đạt phải từ 0 đến 100");
        }

        AntiCheatPolicyType policy = dto.anticheatPolicy() != null
            ? dto.anticheatPolicy() : AntiCheatPolicyType.WARN_THEN_AUTO_SUBMIT;
        int maxViolations = dto.maxViolations() > 0 ? dto.maxViolations() : 3;

        Quiz quiz = Quiz.createNew(
            dto.title().strip(),
            dto.description() == null ? null : dto.description().strip(),
            dto.category() == null ? null : dto.category().strip(),
            dto.durationMinutes(),
            dto.passScore(),
            policy,
            maxViolations,
            session.userId()
        );
        Quiz saved = quizzes.save(quiz);
        return toDto(saved);
    }

    public QuizDto updateQuiz(UpdateQuizDto dto, UserSession session) {
        Objects.requireNonNull(session, "UserSession cannot be null");
        if (dto == null) {
            throw new ValidationException("Thông tin cập nhật không được để trống");
        }
        Quiz quiz = quizzes.findById(dto.id())
            .orElseThrow(() -> new NotFoundException("Đề kiểm tra không tồn tại"));

        checkTeacherPermission(quiz, session, "chỉnh sửa");

        if (quiz.status() != QuizStatus.DRAFT) {
            throw new ConflictException("Chỉ có thể chỉnh sửa đề kiểm tra khi ở trạng thái DRAFT");
        }

        if (dto.title() == null || dto.title().strip().isBlank()) {
            throw new ValidationException("Tiêu đề đề kiểm tra không được để trống");
        }
        if (dto.durationMinutes() <= 0) {
            throw new ValidationException("Thời lượng làm bài phải lớn hơn 0");
        }
        if (dto.passScore() < 0 || dto.passScore() > 100) {
            throw new ValidationException("Điểm đạt phải từ 0 đến 100");
        }

        AntiCheatPolicyType policy = dto.anticheatPolicy() != null ? dto.anticheatPolicy() : quiz.anticheatPolicy();
        int maxViolations = dto.maxViolations() > 0 ? dto.maxViolations() : quiz.maxViolations();

        Quiz updated = new Quiz(
            quiz.id(),
            dto.title().strip(),
            dto.description() == null ? null : dto.description().strip(),
            dto.category() == null ? null : dto.category().strip(),
            dto.durationMinutes(),
            dto.passScore(),
            quiz.status(),
            quiz.pin(),
            policy,
            maxViolations,
            quiz.createdBy(),
            quiz.createdAt(),
            quiz.openedAt(),
            quiz.closedAt()
        );
        quizzes.update(updated);
        return toDto(updated);
    }

    public QuizDto getQuiz(long quizId) {
        Quiz quiz = quizzes.findById(quizId)
            .orElseThrow(() -> new NotFoundException("Đề kiểm tra không tồn tại"));
        return toDto(quiz);
    }

    public QuizDetailDto getQuizDetail(long quizId) {
        Quiz quiz = quizzes.findById(quizId)
            .orElseThrow(() -> new NotFoundException("Đề kiểm tra không tồn tại"));
        List<Question> questionList = questions.findQuestionsByQuizId(quizId);
        List<QuestionDto> questionDtos = new ArrayList<>(questionList.size());
        for (Question q : questionList) {
            questionDtos.add(new QuestionDto(
                q.id(), q.content(), q.optionA(), q.optionB(), q.optionC(), q.optionD(),
                q.correctOption(), q.topic(), q.difficulty(), q.active(), q.createdBy(), q.createdAt()
            ));
        }
        List<Long> assignedClasses = quizzes.findAssignedClassIds(quizId);
        return new QuizDetailDto(toDto(quiz), questionDtos, assignedClasses);
    }

    public ArrayList<QuizDto> listQuizzes(UserSession session) {
        Objects.requireNonNull(session, "UserSession cannot be null");
        List<Quiz> list;
        if (session.role() == Role.ADMIN) {
            list = quizzes.findAll();
        } else {
            list = quizzes.findByCreator(session.userId());
        }
        ArrayList<QuizDto> dtos = new ArrayList<>(list.size());
        for (Quiz q : list) {
            dtos.add(toDto(q));
        }
        return dtos;
    }

    public void setQuizQuestions(long quizId, List<Long> questionIds, UserSession session) {
        Objects.requireNonNull(session, "UserSession cannot be null");
        Quiz quiz = quizzes.findById(quizId)
            .orElseThrow(() -> new NotFoundException("Đề kiểm tra không tồn tại"));

        checkTeacherPermission(quiz, session, "thay đổi câu hỏi");

        if (quiz.status() != QuizStatus.DRAFT) {
            throw new ConflictException("Chỉ có thể chọn câu hỏi cho đề kiểm tra khi ở trạng thái DRAFT");
        }

        if (questionIds != null && !questionIds.isEmpty()) {
            List<Question> existing = questions.findByIds(questionIds);
            if (existing.size() != questionIds.size()) {
                throw new ValidationException("Một hoặc nhiều câu hỏi không tồn tại trong ngân hàng câu hỏi");
            }
        }
        quizzes.setQuestions(quizId, questionIds != null ? questionIds : List.of());
    }

    public QuizDto openQuiz(long quizId, UserSession session) {
        Objects.requireNonNull(session, "UserSession cannot be null");
        Quiz quiz = quizzes.findById(quizId)
            .orElseThrow(() -> new NotFoundException("Đề kiểm tra không tồn tại"));

        checkTeacherPermission(quiz, session, "mở");

        if (quiz.status() != QuizStatus.DRAFT) {
            throw new ConflictException("Chỉ có thể mở đề kiểm tra khi đang ở trạng thái DRAFT");
        }

        int questionCount = quizzes.countQuestions(quizId);
        if (questionCount == 0) {
            throw new ValidationException("Không thể mở đề kiểm tra chưa có câu hỏi nào");
        }

        String pin = String.format("%06d", 100000 + random.nextInt(900000));
        Instant openedAt = Instant.now();
        quizzes.updateStatus(quizId, QuizStatus.OPEN, pin, openedAt, null);

        return new QuizDto(
            quiz.id(), quiz.title(), quiz.description(), quiz.category(),
            quiz.durationMinutes(), quiz.passScore(), QuizStatus.OPEN, pin,
            quiz.anticheatPolicy(), quiz.maxViolations(), quiz.createdBy(),
            quiz.createdAt(), openedAt, null
        );
    }

    public QuizDto closeQuiz(long quizId, UserSession session) {
        Objects.requireNonNull(session, "UserSession cannot be null");
        Quiz quiz = quizzes.findById(quizId)
            .orElseThrow(() -> new NotFoundException("Đề kiểm tra không tồn tại"));

        checkTeacherPermission(quiz, session, "đóng");

        if (quiz.status() != QuizStatus.OPEN) {
            throw new ConflictException("Chỉ có thể đóng đề kiểm tra đang ở trạng thái OPEN");
        }

        Instant closedAt = Instant.now();
        quizzes.updateStatus(quizId, QuizStatus.CLOSED, quiz.pin(), quiz.openedAt(), closedAt);

        return new QuizDto(
            quiz.id(), quiz.title(), quiz.description(), quiz.category(),
            quiz.durationMinutes(), quiz.passScore(), QuizStatus.CLOSED, quiz.pin(),
            quiz.anticheatPolicy(), quiz.maxViolations(), quiz.createdBy(),
            quiz.createdAt(), quiz.openedAt(), closedAt
        );
    }

    public void assignQuiz(long quizId, long classId, UserSession session) {
        Objects.requireNonNull(session, "UserSession cannot be null");
        Quiz quiz = quizzes.findById(quizId)
            .orElseThrow(() -> new NotFoundException("Đề kiểm tra không tồn tại"));
        if (classes != null) {
            classes.findById(classId)
                .orElseThrow(() -> new NotFoundException("Lớp học không tồn tại"));
        }

        checkTeacherPermission(quiz, session, "giao");

        quizzes.assignToClass(quizId, classId, session.userId());
    }

    public ArrayList<QuizInfoDto> listAssignedQuizzesForStudent(long studentId) {
        List<Quiz> quizList = quizzes.findAssignedQuizzesForStudent(studentId);
        ArrayList<QuizInfoDto> dtos = new ArrayList<>(quizList.size());
        for (Quiz q : quizList) {
            int count = quizzes.countQuestions(q.id());
            dtos.add(toInfoDto(q, count));
        }
        return dtos;
    }

    public QuizInfoDto getQuizInfo(long quizId, long studentId) {
        Quiz quiz = quizzes.findById(quizId)
            .orElseThrow(() -> new NotFoundException("Đề kiểm tra không tồn tại"));
        int count = quizzes.countQuestions(quizId);
        return toInfoDto(quiz, count);
    }

    public ArrayList<ResultDto> listQuizResults(long quizId, UserSession session) {
        Objects.requireNonNull(session, "UserSession cannot be null");
        Quiz quiz = quizzes.findById(quizId)
            .orElseThrow(() -> new NotFoundException("Đề kiểm tra không tồn tại"));
        checkTeacherPermission(quiz, session, "xem kết quả");
        return new ArrayList<>(quizzes.findResultsByQuizId(quizId));
    }

    private void checkTeacherPermission(Quiz quiz, UserSession session, String action) {
        if (session.role() != Role.ADMIN && quiz.createdBy() != session.userId()) {
            throw new ForbiddenException("Bạn không có quyền " + action + " đề kiểm tra này");
        }
    }

    private static QuizDto toDto(Quiz q) {
        return new QuizDto(
            q.id(), q.title(), q.description(), q.category(), q.durationMinutes(),
            q.passScore(), q.status(), q.pin(), q.anticheatPolicy(), q.maxViolations(),
            q.createdBy(), q.createdAt(), q.openedAt(), q.closedAt()
        );
    }

    private static QuizInfoDto toInfoDto(Quiz q, int questionCount) {
        return new QuizInfoDto(
            q.id(), q.title(), q.description(), q.category(), q.durationMinutes(),
            q.passScore(), questionCount, q.status(), q.pin(), q.pin() != null, q.openedAt(), q.closedAt()
        );
    }

    private void requireTeacherOrAdmin(long actorId) {
        if (users != null) {
            checkActor(users.findById(actorId).orElse(null));
        }
    }

    private void requireTeacherOrAdmin(Connection connection, long actorId) {
        if (users != null) {
            checkActor(users.findById(connection, actorId).orElse(null));
        }
    }

    private static void checkActor(Object actor) {
        if (!(actor instanceof User user) || !user.active()) {
            throw new UnauthorizedException("Người thực hiện không còn hoạt động");
        }
        if (user.role() != Role.TEACHER && user.role() != Role.ADMIN) {
            throw new ForbiddenException("Chỉ Giáo viên và Quản trị viên được quản lý quiz");
        }
    }
}
