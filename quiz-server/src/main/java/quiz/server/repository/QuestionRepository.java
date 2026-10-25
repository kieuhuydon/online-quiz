package quiz.server.repository;

import quiz.server.domain.Question;
import quiz.server.exception.QuizException;
import quiz.common.enums.Difficulty;

import java.sql.Connection;

import java.util.List;
import java.util.Optional;

/**
 * Persistence contract for questions. Authorization belongs to application services.
 * All text inputs are normalized by the caller; lookups preserve case and follow
 * the database's existing equality and uniqueness rules.
 *
 * <p>Ordinary methods acquire and release their own connection. Connection overloads
 * use only the supplied, open connection, which remains owned by the caller. They
 * must not acquire another connection, close the supplied connection, change its
 * auto-commit mode, commit or roll back. This lets services coordinate several
 * operations through {@code TransactionManager}.</p>
 *
 * <p>Invalid arguments produce {@link IllegalArgumentException}. Database failures
 * produce {@link QuizException} with DATABASE_ERROR, a safe message and the original
 * cause.</p>
 * Persistence contract for Question Bank.
 */
public interface QuestionRepository {

    /**
     * Finds a question by ID, including inactive questions.
     *
     * @param id positive question ID
     * @return the persisted question, or empty if not found
     * @throws IllegalArgumentException if the ID is not positive
     * @throws QuizException if the database operation fails
     */
    Optional<Question> findById(long id);

    /**
     * Finds a question using the caller's connection without managing its lifecycle
     * or transaction. Suitable for checking targets in a write transaction.
     *
     * @param connection non-null, open connection owned by the caller
     * @param id         positive question ID
     * @return the persisted question, or empty if not found
     * @throws IllegalArgumentException if the connection is null or the ID is not positive
     * @throws QuizException if the database operation fails
     */
    Optional<Question> findById(Connection connection, long id);

    /**
     * Lists all questions ordered by ID ascending, including inactive ones.
     *
     * @return a non-null list with no null elements; empty only when no questions exist
     * @throws QuizException if the database operation fails
     */
    List<Question> findAll();

    /**
     * Lists active questions ordered by ID ascending.
     *
     * @return a non-null list with no null elements; empty only when no active questions exist
     * @throws QuizException if the database operation fails
     */
    List<Question> findActive();

    /**
     * Lists questions matching an optional topic and/or difficulty, ordered by ID ascending.
     * A null topic or difficulty means no filter for that field.
     *
     * @param topic      optional topic filter (null = all topics)
     * @param difficulty optional difficulty filter (null = all difficulties)
     * @return a non-null list with no null elements; empty when no matches
     * @throws QuizException if the database operation fails
     */
    List<Question> findByFilter(String topic, Difficulty difficulty);

    /**
     * Inserts a new question. The database generates the ID and timestamps.
     *
     * @param question non-null new question with ID zero and no timestamps
     * @return a new persisted Question containing database-generated metadata
     * @throws IllegalArgumentException if the question is null or already persisted
     * @throws QuizException if the database operation fails
     * Inserts a new question.
     */
    Question save(Question question);

    /**
     * Inserts a new question using only the caller's connection. The returned question
     * has database metadata, but persistence is not final until the caller commits.
     *
     * @param connection non-null, open connection owned by the caller
     * @param question   non-null new question with ID zero and no timestamps
     * @return a new Question containing database-generated metadata
     * @throws IllegalArgumentException if the connection/question is null or already persisted
     * @throws QuizException if the database operation fails
     */
    Question save(Connection connection, Question question);

    /**
     * Updates an existing question's content, options, correct option, topic and difficulty.
     * Only active questions can be updated. Returns true if the ID exists and was updated.
     *
     * @param question non-null persisted question with positive ID
     * @return true if the question exists and was updated; false if not found
     * @throws IllegalArgumentException if the question is null or not persisted
     * @throws QuizException if the database operation fails
     */
    boolean update(Question question);

    /**
     * Updates a question using only the caller's connection. Changes are not final
     * until the caller commits.
     *
     * @param connection non-null, open connection owned by the caller
     * @param question   non-null persisted question with positive ID
     * @return true if the question exists and was updated; false if not found
     * @throws IllegalArgumentException if the connection/question is null or not persisted
     * @throws QuizException if the database operation fails
     */
    boolean update(Connection connection, Question question);

    /**
     * Soft-deletes a question by setting active=false. Returns true if the ID exists.
     * Does not remove rows referenced by quizzes (BR-05).
     *
     * @param id positive question ID
     * @return true if the question exists and was deactivated; false if not found
     * @throws IllegalArgumentException if the ID is not positive
     * @throws QuizException if the database operation fails
     */
    boolean deactivate(long id);

    /**
     * Soft-deletes a question using only the caller's connection.
     *
     * @param connection non-null, open connection owned by the caller
     * @param id         positive question ID
     * @return true if the question exists and was deactivated; false if not found
     * @throws IllegalArgumentException if the connection is null or the ID is not positive
     * @throws QuizException if the database operation fails
     */
    boolean deactivate(Connection connection, long id);

    /**
     * Checks whether a question ID exists, regardless of active status.
     *
     * @param id positive question ID
     * @return true if the question exists
     * @throws IllegalArgumentException if the ID is not positive
     * @throws QuizException if the database operation fails
     */
    boolean existsById(long id);

    /**
     * Returns all active questions in the Question Bank.
     */
    List<Question> findAllActive();

    /**
     * Finds multiple questions by IDs.
     */
    List<Question> findByIds(List<Long> ids);

    /**
     * Returns questions assigned to a quiz in position order.
     */
    List<Question> findQuestionsByQuizId(long quizId);

    /**
     * Counts active questions.
     */
    int countActive();
}
