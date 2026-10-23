package quiz.server.domain;

import quiz.common.enums.AttemptStatus;
import quiz.common.enums.SubmitReason;
import java.time.Instant;

public record Attempt(long id, long quizId, long studentId, AttemptStatus status, Instant startedAt,
                      Instant deadlineAt, Instant submittedAt, SubmitReason submitReason) { }
