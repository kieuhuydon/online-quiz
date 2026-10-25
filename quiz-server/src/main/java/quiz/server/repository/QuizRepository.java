package quiz.server.repository;

import quiz.common.enums.QuizStatus;
import quiz.server.domain.Quiz;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Persistence contract for Quizzes, Quiz Questions, and Quiz Assignments.
 */
public interface QuizRepository {

    /**
     * Inserts a new quiz (status DRAFT).
     */
    Quiz save(Quiz quiz);

    /**
     * Updates an existing quiz (only when DRAFT).
     */
    boolean update(Quiz quiz);

    /**
     * Finds a quiz by ID.
     */
    Optional<Quiz> findById(long id);

    /**
     * Returns all quizzes.
     */
    List<Quiz> findAll();

    /**
     * Returns quizzes created by a specific teacher.
     */
    List<Quiz> findByCreator(long teacherId);

    /**
     * Updates quiz status, PIN, and open/close timestamps.
     */
    boolean updateStatus(long quizId, QuizStatus status, String pin, Instant openedAt, Instant closedAt);

    /**
     * Replaces quiz questions with the specified list of question IDs in position order (1..N).
     */
    void setQuestions(long quizId, List<Long> questionIds);

    /**
     * Returns the ordered list of question IDs in a quiz.
     */
    List<Long> getQuestionIds(long quizId);

    /**
     * Counts the number of questions in a quiz.
     */
    int countQuestions(long quizId);

    /**
     * Assigns a quiz to a class.
     */
    void assignToClass(long quizId, long classId, long assignedBy);

    /**
     * Returns class IDs assigned to a quiz.
     */
    List<Long> findAssignedClassIds(long quizId);

    /**
     * Finds quizzes assigned to classes that the specified student is enrolled in.
     */
    List<Quiz> findAssignedQuizzesForStudent(long studentId);

    /**
     * Finds exam results for a quiz.
     */
    List<quiz.common.dto.ResultDto> findResultsByQuizId(long quizId);
}
