package quiz.server.service;

import quiz.common.enums.SubmitReason;
import quiz.server.domain.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Grades exclusively on the server, storing score on schema's 0–10 scale. */
public final class GradingService {
    public ExamResult grade(Attempt attempt, Map<Long,?> answers, List<Question> questions,
                            Quiz quiz, String studentName, SubmitReason reason, Instant submittedAt) {
        int correct=0;
        for(Question question:questions) {
            Object selected=answers.get(question.id());
            if(selected != null && matchesAnswer(question, String.valueOf(selected))) correct++;
        }
        int total=questions.size();
        double score=total==0?0.0:Math.round((correct*10.0/total)*100.0)/100.0;
        double percentage=total==0?0.0:correct*100.0/total;
        return new ExamResult(attempt.id(),attempt.quizId(),attempt.studentId(),studentName,score,correct,total,
            percentage>=quiz.passScore(),reason,submittedAt);
    }

    public boolean matchesAnswer(Question q, String selected) {
        String expected = q.answerKey().isBlank() ? String.valueOf(q.correctOption()) : q.answerKey();
        String normalized = selected.strip().replaceAll("\\s+", " ");
        if (q.questionType() == quiz.common.enums.QuestionType.FILL_BLANK) {
            String[] expectedWords = expected.split("\\s*\\|\\s*", -1);
            String[] answerWords = normalized.split("\\s*\\|\\s*", -1);
            if (expectedWords.length != answerWords.length) return false;
            for (int i = 0; i < expectedWords.length; i++) if (!expectedWords[i].equalsIgnoreCase(answerWords[i].strip())) return false;
            return true;
        }
        return normalized.equalsIgnoreCase(expected.strip());
    }
}
