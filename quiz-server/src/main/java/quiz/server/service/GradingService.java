package quiz.server.service;

import quiz.common.enums.SubmitReason;
import quiz.server.domain.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Grades exclusively on the server, storing score on schema's 0–10 scale. */
public final class GradingService {
    public ExamResult grade(Attempt attempt, Map<Long,Character> answers, List<Question> questions,
                            Quiz quiz, String studentName, SubmitReason reason, Instant submittedAt) {
        int correct=0;
        for(Question question:questions) {
            Character selected=answers.get(question.id());
            if(selected!=null && selected==question.correctOption()) correct++;
        }
        int total=questions.size();
        double score=total==0?0.0:Math.round((correct*10.0/total)*100.0)/100.0;
        double percentage=total==0?0.0:correct*100.0/total;
        return new ExamResult(attempt.id(),attempt.quizId(),attempt.studentId(),studentName,score,correct,total,
            percentage>=quiz.passScore(),reason,submittedAt);
    }
}
