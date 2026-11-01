package quiz.server.service;

import quiz.common.enums.SubmitReason;
import quiz.server.domain.*;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
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
        String normalized = selected == null ? "" : selected.strip().replaceAll("\\s+", " ");
        if (q.questionType() == quiz.common.enums.QuestionType.MULTIPLE_CHOICE) {
            return normalized.equalsIgnoreCase(String.valueOf(q.correctOption()));
        }
        if (q.questionType() == quiz.common.enums.QuestionType.TRUE_FALSE) {
            String expected = q.correctOption() == 'A' ? q.optionA() : q.optionB();
            return normalized.equalsIgnoreCase("TRUE" ) && q.correctOption() == 'A'
                || normalized.equalsIgnoreCase("FALSE") && q.correctOption() == 'B'
                || normalized.equalsIgnoreCase("A") && q.correctOption() == 'A'
                || normalized.equalsIgnoreCase("B") && q.correctOption() == 'B'
                || normalized.equalsIgnoreCase(expected);
        }
        String expected = q.answerKey().isBlank() ? q.optionA() : q.answerKey();
        if (q.questionType() == quiz.common.enums.QuestionType.FILL_BLANK) {
            for (String acceptedWord : expected.split("\\s*\\|\\s*", -1)) {
                if (normalized.equalsIgnoreCase(acceptedWord.strip())) return true;
            }
            return false;
        }
        for (String alternative : expected.split("\\s*\\|\\s*", -1)) {
            if (normalized.equalsIgnoreCase(alternative.strip())) return true;
        }
        return false;
    }

    public String correctAnswerText(Question q) {
        return switch (q.questionType()) {
            case MULTIPLE_CHOICE -> switch (q.correctOption()) {
                case 'A' -> q.optionA(); case 'B' -> q.optionB(); case 'C' -> q.optionC(); default -> q.optionD();
            };
            case TRUE_FALSE -> q.correctOption() == 'A' ? q.optionA() : q.optionB();
            case FILL_BLANK, SHORT_ANSWER -> q.answerKey().isBlank() ? q.optionA() : q.answerKey();
        };
    }

    public String submittedAnswerText(Question q, String answer) {
        if (answer == null || answer.isBlank()) return "";
        if (q.questionType() == quiz.common.enums.QuestionType.MULTIPLE_CHOICE && answer.matches("(?i)[A-D]")) {
            return switch (answer.toUpperCase()) {
                case "A" -> q.optionA(); case "B" -> q.optionB(); case "C" -> q.optionC(); default -> q.optionD();
            };
        }
        if (q.questionType() == quiz.common.enums.QuestionType.TRUE_FALSE) {
            if (answer.equalsIgnoreCase("TRUE") || answer.equalsIgnoreCase("A")) return q.optionA();
            if (answer.equalsIgnoreCase("FALSE") || answer.equalsIgnoreCase("B")) return q.optionB();
        }
        return answer;
    }
}
