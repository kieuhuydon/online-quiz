package quiz.server.service;

import quiz.common.dto.QuestionDto;
import quiz.server.domain.Question;
import quiz.server.exception.NotFoundException;
import quiz.server.repository.QuestionRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Quản lý ngân hàng câu hỏi phục vụ chọn câu hỏi vào Quiz (FR-A05).
 */
public final class QuestionService {
    private final QuestionRepository questionRepository;

    public QuestionService(QuestionRepository questionRepository) {
        this.questionRepository = Objects.requireNonNull(questionRepository, "QuestionRepository cannot be null");
    }

    public ArrayList<QuestionDto> listQuestions() {
        List<Question> questions = questionRepository.findAllActive();
        ArrayList<QuestionDto> dtos = new ArrayList<>(questions.size());
        for (Question q : questions) {
            dtos.add(toDto(q));
        }
        return dtos;
    }

    public QuestionDto getQuestion(long id) {
        Question q = questionRepository.findById(id)
            .orElseThrow(() -> new NotFoundException("Câu hỏi không tồn tại"));
        return toDto(q);
    }

    private static QuestionDto toDto(Question q) {
        return new QuestionDto(
            q.id(),
            q.content(),
            q.optionA(),
            q.optionB(),
            q.optionC(),
            q.optionD(),
            q.correctOption(),
            q.topic(),
            q.difficulty(),
            q.active(),
            q.createdBy(),
            q.createdAt()
        );
    }
}
