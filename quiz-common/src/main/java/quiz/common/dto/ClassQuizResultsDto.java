package quiz.common.dto;

import java.io.Serializable;
import java.util.List;

/** Quizzes assigned to a teacher's class and their submitted results. */
public record ClassQuizResultsDto(List<QuizDto> quizzes, List<ResultDto> results) implements Serializable {
    public ClassQuizResultsDto {
        quizzes = List.copyOf(quizzes);
        results = List.copyOf(results);
    }
}
