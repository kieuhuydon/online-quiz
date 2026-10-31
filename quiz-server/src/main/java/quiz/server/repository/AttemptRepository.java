package quiz.server.repository;

import quiz.server.domain.*;
import java.sql.Connection;
import java.util.*;

public interface AttemptRepository {
    Attempt create(Connection connection, Attempt attempt);
    Optional<Attempt> findById(long id);
    Optional<Attempt> findByIdForUpdate(Connection connection, long id);
    Optional<Attempt> findByStudentAndQuiz(long studentId, long quizId);
    List<Attempt> findByStudentId(long studentId);
    List<Attempt> findExpiredInProgress(java.time.Instant now);
    void submit(Connection connection, long id, java.time.Instant submittedAt, quiz.common.enums.SubmitReason reason);
    void saveAnswer(Connection connection, AnswerRecord record);
    Map<Long, Character> getAnswers(Connection connection, long attemptId);
    default Map<Long, String> getTextAnswers(Connection connection, long attemptId) {
        Map<Long, String> converted = new HashMap<>();
        getAnswers(connection, attemptId).forEach((id, value) -> converted.put(id, value == null ? null : value.toString()));
        return Map.copyOf(converted);
    }
    void saveResult(Connection connection, ExamResult result);
    Optional<ExamResult> findResult(long attemptId);
    List<ExamResult> findResultsByStudent(long studentId);
}
