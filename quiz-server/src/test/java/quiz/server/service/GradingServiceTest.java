package quiz.server.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import quiz.common.enums.AntiCheatPolicyType;
import quiz.common.enums.AttemptStatus;
import quiz.common.enums.Difficulty;
import quiz.common.enums.QuizStatus;
import quiz.common.enums.SubmitReason;
import quiz.common.enums.QuestionType;
import quiz.server.domain.Attempt;
import quiz.server.domain.ExamResult;
import quiz.server.domain.Question;
import quiz.server.domain.Quiz;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("GradingService Unit Tests")
class GradingServiceTest {

    private final GradingService gradingService = new GradingService();
    private final Instant now = Instant.parse("2026-10-30T08:00:00Z");

    private Attempt createAttempt() {
        return new Attempt(100L, 10L, 50L, AttemptStatus.IN_PROGRESS,
            now.minusSeconds(600), now.plusSeconds(1200), null, null);
    }

    private Quiz createQuiz(double passScore) {
        return Quiz.restore(10L, "Java Final", "Desc", "Java", 30, passScore,
            QuizStatus.OPEN, "123456", AntiCheatPolicyType.WARN_THEN_AUTO_SUBMIT, 3,
            2L, now.minusSeconds(3600), now.minusSeconds(1800), null);
    }

    private Question question(long id, char correctOption) {
        return Question.restore(id, "Question " + id, "Opt A", "Opt B", "Opt C", "Opt D",
            correctOption, "Java", Difficulty.EASY, true, 2L, now, now);
    }

    @Test
    @DisplayName("Chấm đúng toàn bộ câu hỏi trả về điểm 10.0 và passed = true")
    void grade_AllCorrect_ReturnsPerfectScoreAndPassed() {
        Attempt attempt = createAttempt();
        Quiz quiz = createQuiz(50.0);
        List<Question> questions = List.of(
            question(1L, 'A'),
            question(2L, 'B'),
            question(3L, 'C'),
            question(4L, 'D')
        );
        Map<Long, Character> answers = Map.of(
            1L, 'A',
            2L, 'B',
            3L, 'C',
            4L, 'D'
        );

        ExamResult result = gradingService.grade(
            attempt, answers, questions, quiz, "Nguyen Van A", SubmitReason.MANUAL, now
        );

        assertEquals(100L, result.attemptId());
        assertEquals(10L, result.quizId());
        assertEquals(50L, result.studentId());
        assertEquals("Nguyen Van A", result.studentName());
        assertEquals(10.0, result.score());
        assertEquals(4, result.correctCount());
        assertEquals(4, result.totalQuestions());
        assertTrue(result.passed());
        assertEquals(SubmitReason.MANUAL, result.submitReason());
        assertEquals(now, result.submittedAt());
    }

    @Test
    @DisplayName("Chấm điểm đạt đúng ngưỡng passScore thì passed = true, dưới ngưỡng thì passed = false")
    void grade_PassScoreBoundary() {
        Attempt attempt = createAttempt();
        Quiz quiz = createQuiz(50.0);
        List<Question> questions = List.of(
            question(1L, 'A'),
            question(2L, 'B'),
            question(3L, 'C'),
            question(4L, 'D')
        );

        // 2/4 = 50% == passScore (50.0) -> passed = true
        Map<Long, Character> exactPassAnswers = Map.of(1L, 'A', 2L, 'B', 3L, 'A', 4L, 'A');
        ExamResult passResult = gradingService.grade(
            attempt, exactPassAnswers, questions, quiz, "Student", SubmitReason.MANUAL, now
        );
        assertEquals(5.0, passResult.score());
        assertEquals(2, passResult.correctCount());
        assertTrue(passResult.passed());

        // 1/4 = 25% < passScore (50.0) -> passed = false
        Map<Long, Character> failAnswers = Map.of(1L, 'A', 2L, 'A', 3L, 'A', 4L, 'A');
        ExamResult failResult = gradingService.grade(
            attempt, failAnswers, questions, quiz, "Student", SubmitReason.TIMEOUT, now
        );
        assertEquals(2.5, failResult.score());
        assertEquals(1, failResult.correctCount());
        assertFalse(failResult.passed());
        assertEquals(SubmitReason.TIMEOUT, failResult.submitReason());
    }

    @Test
    @DisplayName("Làm tròn điểm chính xác đến 2 chữ số thập phân (ví dụ 1/3 -> 3.33 và 2/3 -> 6.67)")
    void grade_RoundsScoreToTwoDecimalPlaces() {
        Attempt attempt = createAttempt();
        Quiz quiz = createQuiz(60.0);
        List<Question> questions = List.of(
            question(1L, 'A'),
            question(2L, 'B'),
            question(3L, 'C')
        );

        ExamResult oneThird = gradingService.grade(
            attempt, Map.of(1L, 'A'), questions, quiz, "Student", SubmitReason.MANUAL, now
        );
        assertEquals(3.33, oneThird.score());
        assertFalse(oneThird.passed());

        ExamResult twoThirds = gradingService.grade(
            attempt, Map.of(1L, 'A', 2L, 'B'), questions, quiz, "Student", SubmitReason.ANTICHEAT, now
        );
        assertEquals(6.67, twoThirds.score());
        assertTrue(twoThirds.passed());
        assertEquals(SubmitReason.ANTICHEAT, twoThirds.submitReason());
    }

    @Test
    @DisplayName("Câu hỏi bỏ trống hoặc giá trị null được tính là chưa trả lời đúng")
    void grade_UnansweredAndNullEntriesAreCountedWrong() {
        Attempt attempt = createAttempt();
        Quiz quiz = createQuiz(50.0);
        List<Question> questions = List.of(
            question(1L, 'A'),
            question(2L, 'B')
        );
        Map<Long, Character> answers = new HashMap<>();
        answers.put(1L, null);

        ExamResult result = gradingService.grade(
            attempt, answers, questions, quiz, "Student", SubmitReason.TIMEOUT, now
        );

        assertEquals(0.0, result.score());
        assertEquals(0, result.correctCount());
        assertEquals(2, result.totalQuestions());
        assertFalse(result.passed());
    }

    @Test
    @DisplayName("Danh sách câu hỏi rỗng trả về điểm 0.0 và passed = false")
    void grade_EmptyQuestions_ReturnsZeroScoreAndFalse() {
        Attempt attempt = createAttempt();
        Quiz quiz = createQuiz(50.0);

        ExamResult result = gradingService.grade(
            attempt, Map.of(), List.of(), quiz, "Student", SubmitReason.MANUAL, now
        );

        assertEquals(0.0, result.score());
        assertEquals(0, result.correctCount());
        assertEquals(0, result.totalQuestions());
        assertFalse(result.passed());
    }

    @Test
    @DisplayName("Grades text questions case-insensitively and reviews actual answer text")
    void gradesTextAndTrueFalseAnswersAsText() {
        Question fill = Question.restore(10, "She ___ here.", "", "", "", "", 'A', "English",
            Difficulty.EASY, QuestionType.FILL_BLANK, true, 2, now, now, "works", "Present simple");
        Question shortAnswer = Question.restore(11, "Name the color.", "", "", "", "", 'A', "English",
            Difficulty.EASY, QuestionType.SHORT_ANSWER, true, 2, now, now, "blue|azure", "Both are accepted");
        Question trueFalse = Question.restore(12, "The Earth is round.", "True", "False", "", "", 'A', "English",
            Difficulty.EASY, QuestionType.TRUE_FALSE, true, 2, now, now, "", "It is approximately spherical");
        Question mcq = question(13, 'B');

        assertTrue(gradingService.matchesAnswer(fill, "WORKS"));
        assertTrue(gradingService.matchesAnswer(shortAnswer, "AZURE"));
        assertTrue(gradingService.matchesAnswer(trueFalse, "true"));
        assertFalse(gradingService.matchesAnswer(trueFalse, "B"));
        assertEquals("True", gradingService.correctAnswerText(trueFalse));
        assertEquals("Opt B", gradingService.correctAnswerText(mcq));
        assertEquals("Opt B", gradingService.submittedAnswerText(mcq, "B"));
    }
}
