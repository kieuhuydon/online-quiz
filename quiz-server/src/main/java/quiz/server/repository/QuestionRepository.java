package quiz.server.repository;

import quiz.server.domain.Question;

import java.util.List;
import java.util.Optional;

/**
 * Persistence contract for Question Bank.
 */
public interface QuestionRepository {

    /**
     * Inserts a new question.
     */
    Question save(Question question);

    /**
     * Finds a question by ID.
     */
    Optional<Question> findById(long id);

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
