package quiz.server.repository;

import quiz.server.domain.Quiz;
import quiz.server.exception.QuizException;
import quiz.common.enums.QuizStatus;

import java.sql.Connection;
import quiz.common.enums.QuizStatus;
import quiz.server.domain.Quiz;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Persistence contract for quizzes. Authorization belongs to application services.
 *
 * <p>Ordinary methods acquire and release their own connection. Connection overloads
 * use only the supplied, open connection, which remains owned by the caller.</p>
 *
 * <p>Invalid arguments produce {@link IllegalArgumentException}. Database failures
 * produce {@link QuizException} with DATABASE_ERROR, a safe message and the original
 * cause.</p>
 * Persistence contract for Quizzes, Quiz Questions, and Quiz Assignments.
 */
public interface QuizRepository {

    /**
     * Finds a quiz by ID, including closed quizzes.
     *
     * @param id positive quiz ID
     * @return the persisted quiz, or empty if not found
     * @throws IllegalArgumentException if the ID is not positive
     * @throws QuizException if the database operation fails
     */
    Optional<Quiz> findById(long id);

    /**
     * Finds a quiz using the caller's connection without managing its lifecycle
     * or transaction.
     *
     * @param connection non-null, open connection owned by the caller
     * @param id         positive quiz ID
     * @return the persisted quiz, or empty if not found
     * @throws IllegalArgumentException if the connection is null or the ID is not positive
     * @throws QuizException if the database operation fails
     */
    Optional<Quiz> findById(Connection connection, long id);

    /**
     * Lists all quizzes ordered by ID ascending, including closed ones.
     *
     * @return a non-null list with no null elements; empty when no quizzes exist
     * @throws QuizException if the database operation fails
     */
    List<Quiz> findAll();

    /**
     * Lists quizzes matching an optional status, ordered by ID ascending.
     *
     * @param status optional status filter (null = all statuses)
     * @return a non-null list with no null elements; empty when no matches
     * @throws QuizException if the database operation fails
     */
    List<Quiz> findByStatus(QuizStatus status);

    /**
     * Inserts a new quiz. The database generates the ID and creation timestamp.
     *
     * @param quiz non-null new quiz with ID zero and no creation timestamp
     * @return a new persisted Quiz containing database-generated metadata
     * @throws IllegalArgumentException if the quiz is null or already persisted
     * @throws QuizException if the database operation fails
     */
    Quiz save(Quiz quiz);

    /**
     * Inserts a new quiz using only the caller's connection.
     *
     * @param connection non-null, open connection owned by the caller
     * @param quiz       non-null new quiz with ID zero and no creation timestamp
     * @return a new Quiz containing database-generated metadata
     * @throws IllegalArgumentException if the connection/quiz is null or already persisted
     * @throws QuizException if the database operation fails
     */
    Quiz save(Connection connection, Quiz quiz);

    /**
     * Updates an existing quiz's mutable fields. Returns true if the ID exists.
     *
     * @param quiz non-null persisted quiz with positive ID
     * @return true if the quiz exists and was updated; false if not found
     * @throws IllegalArgumentException if the quiz is null or not persisted
     * @throws QuizException if the database operation fails
     */
    boolean update(Quiz quiz);

    /**
     * Updates a quiz using only the caller's connection.
     *
     * @param connection non-null, open connection owned by the caller
     * @param quiz       non-null persisted quiz with positive ID
     * @return true if the quiz exists and was updated; false if not found
     * @throws IllegalArgumentException if the connection/quiz is null or not persisted
     * @throws QuizException if the database operation fails
     */
    boolean update(Connection connection, Quiz quiz);

    /**
     * Replaces the set of questions assigned to a quiz. Only allowed when the quiz
     * is in {@code DRAFT} status. All existing {@code quiz_questions} rows for the
     * quiz are removed and re-inserted with the given positions (1-based).
     *
     * @param quizId     positive quiz ID
     * @param questionIds question IDs in desired order; may be empty to clear all
     * @return true if the quiz exists and questions were set; false if quiz not found
     * @throws IllegalArgumentException if the quiz ID is not positive
     * @throws QuizException if the database operation fails
     */
    boolean setQuestions(long quizId, List<Long> questionIds);

    /**
     * Replaces the set of questions assigned to a quiz using the caller's connection.
     *
     * @param connection   non-null, open connection owned by the caller
     * @param quizId       positive quiz ID
     * @param questionIds  question IDs in desired order; may be empty to clear all
     * @return true if the quiz exists and questions were set; false if quiz not found
     * @throws IllegalArgumentException if the connection is null or the quiz ID is not positive
     * @throws QuizException if the database operation fails
     */
    boolean setQuestions(Connection connection, long quizId, List<Long> questionIds);

    /**
     * Lists question IDs assigned to a quiz, ordered by position ascending.
     *
     * @param quizId positive quiz ID
     * @return a non-null list of question IDs; empty when no questions assigned
     * @throws IllegalArgumentException if the quiz ID is not positive
     * @throws QuizException if the database operation fails
     */
    List<Long> findQuestionIdsByQuiz(long quizId);

    /**
     * Checks whether a quiz ID exists, regardless of status.
     *
     * @param id positive quiz ID
     * @return true if the quiz exists
     * @throws IllegalArgumentException if the ID is not positive
     * @throws QuizException if the database operation fails
     */
    boolean existsById(long id);
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
}
