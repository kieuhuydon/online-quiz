package quiz.common.dto;

import java.io.Serializable;

/** Post-submit review item; correct answers and explanations are never sent during an active attempt. */
public record QuestionReviewDto(long questionId, String content, String submittedAnswer,
                                String correctAnswer, String explanation, boolean correct) implements Serializable { }
