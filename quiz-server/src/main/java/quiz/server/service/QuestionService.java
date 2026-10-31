package quiz.server.service;

import quiz.common.dto.CreateQuestionDto;
import quiz.common.dto.QuestionDto;
import quiz.common.dto.UpdateQuestionDto;
import quiz.common.enums.Difficulty;
import quiz.common.enums.QuestionType;
import quiz.common.enums.Role;
import quiz.server.db.TransactionManager;
import quiz.server.domain.Question;
import quiz.server.domain.User;
import quiz.server.exception.ForbiddenException;
import quiz.server.exception.NotFoundException;
import quiz.server.exception.UnauthorizedException;
import quiz.server.exception.ValidationException;
import quiz.server.repository.QuestionRepository;
import quiz.server.repository.UserRepository;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Manages the question bank: create, read, update, deactivate. Only TEACHER
 * and ADMIN may operate. The actor is always checked against the database
 * (never trusted from the client). Returns domain objects; the handler maps
 * them to DTOs.
 */
public final class QuestionService {
    private final QuestionRepository questions;
    private final UserRepository users;
    private final TransactionManager transactions;

    public QuestionService(QuestionRepository questions, UserRepository users,
                           TransactionManager transactions) {
        this.questions = Objects.requireNonNull(questions, "QuestionRepository cannot be null");
        this.users = Objects.requireNonNull(users, "UserRepository cannot be null");
        this.transactions = Objects.requireNonNull(transactions, "TransactionManager cannot be null");
    }

    /** Creates a new question. Actor must be TEACHER or ADMIN and active. */
    public Question createQuestion(CreateQuestionDto dto, long actorId) {
        requireTeacherOrAdmin(actorId);
        CreateQuestionDto validated = validateCreate(dto);
        Question input = Question.createNew(validated.content(), validated.optionA(),
            validated.optionB(), validated.optionC(), validated.optionD(),
            validated.correctOption(), validated.topic(), validated.difficulty(),
            validated.questionType(), validated.explanation(), actorId);
        return transactions.executeInTransaction(connection -> {
            requireTeacherOrAdmin(connection, actorId);
            return questions.save(connection, input);
        });
    }

    /** Returns question details by ID (including deactivated questions). */
    public Question getQuestion(long questionId, long actorId) {
        requireTeacherOrAdmin(actorId);
        return questions.findById(questionId)
            .orElseThrow(() -> new NotFoundException("Question not found"));
    }

    /** Lists all questions (including deactivated), ID ascending. */
    public List<Question> listQuestions(long actorId) {
        requireTeacherOrAdmin(actorId);
        return List.copyOf(questions.findAll());
    }

    /** Lists active questions only, ID ascending. */
    public List<Question> listActiveQuestions(long actorId) {
        requireTeacherOrAdmin(actorId);
        return List.copyOf(questions.findActive());
    }

    /** Updates a question. Only active questions may be updated. Actor must be TEACHER/ADMIN. */
    public Question updateQuestion(UpdateQuestionDto dto, long actorId) {
        requireTeacherOrAdmin(actorId);
        UpdateQuestionDto validated = validateUpdate(dto);
        return transactions.executeInTransaction(connection -> {
            requireTeacherOrAdmin(connection, actorId);
            Question existing = questions.findById(connection, validated.id())
                .orElseThrow(() -> new NotFoundException("Question not found"));
            if (!existing.active()) {
                throw new ValidationException("Cannot update a deactivated question");
            }
            Question updated = new Question(existing.id(), validated.content(), validated.optionA(),
                validated.optionB(), validated.optionC(), validated.optionD(),
                validated.correctOption(), validated.topic(), validated.difficulty(),
                validated.questionType(), validated.explanation(),
                existing.active(), existing.createdBy(), existing.createdAt(), existing.updatedAt());
            boolean ok = questions.update(connection, updated);
            if (!ok) {
                throw new NotFoundException("Question not found");
            }
            return questions.findById(connection, validated.id())
                .orElseThrow(() -> new NotFoundException("Question not found"));
        });
    }

    /** Deactivates a question (soft-delete). Questions used in quizzes keep their row, active=false. */
    public void deactivateQuestion(long questionId, long actorId) {
        requireTeacherOrAdmin(actorId);
        ServiceValidation.positiveId(questionId);
        transactions.executeInTransaction(connection -> {
            requireTeacherOrAdmin(connection, actorId);
            Question existing = questions.findById(connection, questionId)
                .orElseThrow(() -> new NotFoundException("Question not found"));
            if (!existing.active()) {
                throw new ValidationException("Question has already been deactivated");
            }
            boolean ok = questions.deactivate(connection, questionId);
            if (!ok) {
                throw new NotFoundException("Question not found");
            }
            return null;
        });
    }

    public ArrayList<QuestionDto> listQuestions() {
        List<Question> questionsList = questions.findAllActive();
        ArrayList<QuestionDto> dtos = new ArrayList<>(questionsList.size());
        for (Question q : questionsList) {
            dtos.add(toDto(q));
        }
        return dtos;
    }

    public QuestionDto getQuestion(long id) {
        Question q = questions.findById(id)
            .orElseThrow(() -> new NotFoundException("Câu hỏi không tồn tại"));
        return toDto(q);
    }

    private static QuestionDto toDto(Question q) {
        return new QuestionDto(
            q.id(), q.content(), q.optionA(), q.optionB(), q.optionC(), q.optionD(),
            q.correctOption(), q.topic(), q.difficulty(), q.questionType(), q.explanation(), q.active(), q.createdBy(), q.createdAt()
        );
    }

    private CreateQuestionDto validateCreate(CreateQuestionDto dto) {
        if (dto == null) {
            throw new ValidationException("Question data is required");
        }
        String content = ServiceValidation.requiredText(dto.content(), "Question content", 1000);
        String optionA = ServiceValidation.requiredText(dto.optionA(), "Option A", 255);
        String optionB = ServiceValidation.requiredText(dto.optionB(), "Option B", 255);
        String optionC = ServiceValidation.requiredText(dto.optionC(), "Option C", 255);
        String optionD = ServiceValidation.requiredText(dto.optionD(), "Option D", 255);
        char correctOption = normalizeCorrectOption(dto.correctOption());
        String topic = ServiceValidation.requiredText(dto.topic(), "Topic", 100);
        if (dto.difficulty() == null) {
            throw new ValidationException("Difficulty is required");
        }
        QuestionType questionType = dto.questionType() != null ? dto.questionType() : QuestionType.MULTIPLE_CHOICE;
        String explanation = dto.explanation();
        return new CreateQuestionDto(content, optionA, optionB, optionC, optionD,
            correctOption, topic, dto.difficulty(), questionType, explanation);
    }

    private UpdateQuestionDto validateUpdate(UpdateQuestionDto dto) {
        if (dto == null) {
            throw new ValidationException("Question data is required");
        }
        ServiceValidation.positiveId(dto.id());
        String content = ServiceValidation.requiredText(dto.content(), "Question content", 1000);
        String optionA = ServiceValidation.requiredText(dto.optionA(), "Option A", 255);
        String optionB = ServiceValidation.requiredText(dto.optionB(), "Option B", 255);
        String optionC = ServiceValidation.requiredText(dto.optionC(), "Option C", 255);
        String optionD = ServiceValidation.requiredText(dto.optionD(), "Option D", 255);
        char correctOption = normalizeCorrectOption(dto.correctOption());
        String topic = ServiceValidation.requiredText(dto.topic(), "Topic", 100);
        if (dto.difficulty() == null) {
            throw new ValidationException("Difficulty is required");
        }
        QuestionType questionType = dto.questionType() != null ? dto.questionType() : QuestionType.MULTIPLE_CHOICE;
        String explanation = dto.explanation();
        return new UpdateQuestionDto(dto.id(), content, optionA, optionB, optionC, optionD,
            correctOption, topic, dto.difficulty(), questionType, explanation);
    }

    private static char normalizeCorrectOption(char correctOption) {
        char upper = Character.toUpperCase(correctOption);
        if (upper != 'A' && upper != 'B' && upper != 'C' && upper != 'D') {
            throw new ValidationException("Correct option must be A, B, C, or D");
        }
        return upper;
    }

    private void requireTeacherOrAdmin(long actorId) {
        checkActor(users.findById(actorId).orElse(null));
    }

    private void requireTeacherOrAdmin(Connection connection, long actorId) {
        checkActor(users.findById(connection, actorId).orElse(null));
    }

    private static void checkActor(Object actor) {
        if (!(actor instanceof User user) || !user.active()) {
            throw new UnauthorizedException("Actor is no longer active");
        }
        if (user.role() != Role.TEACHER && user.role() != Role.ADMIN) {
            throw new ForbiddenException("Only Teachers and Admins can manage questions");
        }
    }
}


