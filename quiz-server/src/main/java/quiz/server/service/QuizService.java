package quiz.server.service;

import quiz.common.dto.CreateQuizDto;
import quiz.common.dto.QuestionDto;
import quiz.common.dto.QuizDetailDto;
import quiz.common.dto.QuizDto;
import quiz.common.dto.QuizInfoDto;
import quiz.common.dto.UpdateQuizDto;
import quiz.common.enums.AntiCheatPolicyType;
import quiz.common.enums.QuizStatus;
import quiz.common.enums.Role;
import quiz.server.domain.Question;
import quiz.server.domain.Quiz;
import quiz.server.exception.ConflictException;
import quiz.server.exception.ForbiddenException;
import quiz.server.exception.NotFoundException;
import quiz.server.exception.ValidationException;
import quiz.server.network.UserSession;
import quiz.server.repository.ClassRepository;
import quiz.server.repository.QuestionRepository;
import quiz.server.repository.QuizRepository;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Quản lý nghiệp vụ đề kiểm tra, chọn câu hỏi, mở/đóng quiz, và giao bài (FR-A04, FR-A05, FR-A06, FR-A08, FR-A09, FR-S02, FR-S04).
 */
public final class QuizService {
    private final QuizRepository quizRepository;
    private final QuestionRepository questionRepository;
    private final ClassRepository classRepository;
    private final SecureRandom random = new SecureRandom();

    public QuizService(QuizRepository quizRepository, QuestionRepository questionRepository, ClassRepository classRepository) {
        this.quizRepository = Objects.requireNonNull(quizRepository, "QuizRepository cannot be null");
        this.questionRepository = Objects.requireNonNull(questionRepository, "QuestionRepository cannot be null");
        this.classRepository = Objects.requireNonNull(classRepository, "ClassRepository cannot be null");
    }

    public QuizDto createQuiz(CreateQuizDto dto, UserSession session) {
        Objects.requireNonNull(session, "UserSession cannot be null");
        if (dto == null) throw new ValidationException("Dữ liệu đề kiểm tra không được để trống");
        if (dto.title() == null || dto.title().strip().isBlank()) {
            throw new ValidationException("Tiêu đề đề kiểm tra không được để trống");
        }
        if (dto.durationMinutes() <= 0) {
            throw new ValidationException("Thời lượng làm bài phải lớn hơn 0 phút");
        }
        if (dto.passScore() < 0 || dto.passScore() > 100) {
            throw new ValidationException("Điểm đạt phải trong khoảng từ 0 đến 100");
        }
        AntiCheatPolicyType policy = dto.anticheatPolicy() != null ? dto.anticheatPolicy() : AntiCheatPolicyType.WARN_THEN_AUTO_SUBMIT;
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
        Quiz saved = quizRepository.save(quiz);
        return toDto(saved);
    }

    public QuizDto updateQuiz(UpdateQuizDto dto, UserSession session) {
        Objects.requireNonNull(session, "UserSession cannot be null");
        if (dto == null || dto.id() <= 0) throw new ValidationException("ID đề kiểm tra không hợp lệ");
        Quiz quiz = quizRepository.findById(dto.id())
            .orElseThrow(() -> new NotFoundException("Đề kiểm tra không tồn tại"));

        checkTeacherPermission(quiz, session, "sửa");

        if (quiz.status() != QuizStatus.DRAFT) {
            throw new ConflictException("Chỉ có thể sửa đề kiểm tra khi đang ở trạng thái DRAFT");
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
        quizRepository.update(updated);
        return toDto(updated);
    }

    public QuizDto getQuiz(long quizId) {
        Quiz quiz = quizRepository.findById(quizId)
            .orElseThrow(() -> new NotFoundException("Đề kiểm tra không tồn tại"));
        return toDto(quiz);
    }

    public QuizDetailDto getQuizDetail(long quizId) {
        Quiz quiz = quizRepository.findById(quizId)
            .orElseThrow(() -> new NotFoundException("Đề kiểm tra không tồn tại"));
        List<Question> questions = questionRepository.findQuestionsByQuizId(quizId);
        List<QuestionDto> questionDtos = new ArrayList<>(questions.size());
        for (Question q : questions) {
            questionDtos.add(new QuestionDto(
                q.id(), q.content(), q.optionA(), q.optionB(), q.optionC(), q.optionD(),
                q.correctOption(), q.topic(), q.difficulty(), q.active(), q.createdBy(), q.createdAt()
            ));
        }
        List<Long> assignedClasses = quizRepository.findAssignedClassIds(quizId);
        return new QuizDetailDto(toDto(quiz), questionDtos, assignedClasses);
    }

    public ArrayList<QuizDto> listQuizzes(UserSession session) {
        Objects.requireNonNull(session, "UserSession cannot be null");
        List<Quiz> list;
        if (session.role() == Role.ADMIN) {
            list = quizRepository.findAll();
        } else {
            list = quizRepository.findByCreator(session.userId());
        }
        ArrayList<QuizDto> dtos = new ArrayList<>(list.size());
        for (Quiz q : list) {
            dtos.add(toDto(q));
        }
        return dtos;
    }

    public void setQuizQuestions(long quizId, List<Long> questionIds, UserSession session) {
        Objects.requireNonNull(session, "UserSession cannot be null");
        Quiz quiz = quizRepository.findById(quizId)
            .orElseThrow(() -> new NotFoundException("Đề kiểm tra không tồn tại"));

        checkTeacherPermission(quiz, session, "thay đổi câu hỏi");

        if (quiz.status() != QuizStatus.DRAFT) {
            throw new ConflictException("Chỉ có thể chọn câu hỏi cho đề kiểm tra khi ở trạng thái DRAFT");
        }

        if (questionIds != null && !questionIds.isEmpty()) {
            List<Question> existing = questionRepository.findByIds(questionIds);
            if (existing.size() != questionIds.size()) {
                throw new ValidationException("Một hoặc nhiều câu hỏi không tồn tại trong ngân hàng câu hỏi");
            }
        }
        quizRepository.setQuestions(quizId, questionIds != null ? questionIds : List.of());
    }

    public QuizDto openQuiz(long quizId, UserSession session) {
        Objects.requireNonNull(session, "UserSession cannot be null");
        Quiz quiz = quizRepository.findById(quizId)
            .orElseThrow(() -> new NotFoundException("Đề kiểm tra không tồn tại"));

        checkTeacherPermission(quiz, session, "mở");

        if (quiz.status() != QuizStatus.DRAFT) {
            throw new ConflictException("Chỉ có thể mở đề kiểm tra khi đang ở trạng thái DRAFT");
        }

        int questionCount = quizRepository.countQuestions(quizId);
        if (questionCount == 0) {
            throw new ValidationException("Không thể mở đề kiểm tra chưa có câu hỏi nào");
        }

        String pin = String.format("%06d", 100000 + random.nextInt(900000));
        Instant openedAt = Instant.now();
        quizRepository.updateStatus(quizId, QuizStatus.OPEN, pin, openedAt, null);

        return new QuizDto(
            quiz.id(), quiz.title(), quiz.description(), quiz.category(),
            quiz.durationMinutes(), quiz.passScore(), QuizStatus.OPEN, pin,
            quiz.anticheatPolicy(), quiz.maxViolations(), quiz.createdBy(),
            quiz.createdAt(), openedAt, null
        );
    }

    public QuizDto closeQuiz(long quizId, UserSession session) {
        Objects.requireNonNull(session, "UserSession cannot be null");
        Quiz quiz = quizRepository.findById(quizId)
            .orElseThrow(() -> new NotFoundException("Đề kiểm tra không tồn tại"));

        checkTeacherPermission(quiz, session, "đóng");

        if (quiz.status() != QuizStatus.OPEN) {
            throw new ConflictException("Chỉ có thể đóng đề kiểm tra đang ở trạng thái OPEN");
        }

        Instant closedAt = Instant.now();
        quizRepository.updateStatus(quizId, QuizStatus.CLOSED, quiz.pin(), quiz.openedAt(), closedAt);

        return new QuizDto(
            quiz.id(), quiz.title(), quiz.description(), quiz.category(),
            quiz.durationMinutes(), quiz.passScore(), QuizStatus.CLOSED, quiz.pin(),
            quiz.anticheatPolicy(), quiz.maxViolations(), quiz.createdBy(),
            quiz.createdAt(), quiz.openedAt(), closedAt
        );
    }

    public void assignQuiz(long quizId, long classId, UserSession session) {
        Objects.requireNonNull(session, "UserSession cannot be null");
        Quiz quiz = quizRepository.findById(quizId)
            .orElseThrow(() -> new NotFoundException("Đề kiểm tra không tồn tại"));
        classRepository.findById(classId)
            .orElseThrow(() -> new NotFoundException("Lớp học không tồn tại"));

        checkTeacherPermission(quiz, session, "giao");

        quizRepository.assignToClass(quizId, classId, session.userId());
    }

    public ArrayList<QuizInfoDto> listAssignedQuizzesForStudent(long studentId) {
        List<Quiz> quizzes = quizRepository.findAssignedQuizzesForStudent(studentId);
        ArrayList<QuizInfoDto> dtos = new ArrayList<>(quizzes.size());
        for (Quiz q : quizzes) {
            int count = quizRepository.countQuestions(q.id());
            dtos.add(toInfoDto(q, count));
        }
        return dtos;
    }

    public QuizInfoDto getQuizInfo(long quizId, long studentId) {
        Quiz quiz = quizRepository.findById(quizId)
            .orElseThrow(() -> new NotFoundException("Đề kiểm tra không tồn tại"));
        int count = quizRepository.countQuestions(quizId);
        return toInfoDto(quiz, count);
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
            q.passScore(), questionCount, q.status(), q.pin(), q.openedAt(), q.closedAt()
        );
    }
}
