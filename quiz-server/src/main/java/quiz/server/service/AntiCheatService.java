package quiz.server.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quiz.common.dto.AntiCheatEventDto;
import quiz.common.dto.AntiCheatWarningDto;
import quiz.common.dto.ReportAntiCheatDto;
import quiz.common.enums.AntiCheatPolicyType;
import quiz.common.enums.AttemptStatus;
import quiz.common.enums.Role;
import quiz.common.enums.SubmitReason;
import quiz.common.protocol.MessageType;
import quiz.common.protocol.Response;
import quiz.server.db.TransactionManager;
import quiz.server.domain.Attempt;
import quiz.server.domain.Quiz;
import quiz.server.exception.ForbiddenException;
import quiz.server.exception.NotFoundException;
import quiz.server.network.ConnectionRegistry;
import quiz.server.network.UserSession;
import quiz.server.repository.AntiCheatRepository;
import quiz.server.repository.AttemptRepository;
import quiz.server.repository.QuizRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Dịch vụ giám sát và xử lý vi phạm chống gian lận trong quá trình làm bài thi (FR-S13, FR-S14, FR-T09, BR-12, BR-13, NFR-09).
 */
public final class AntiCheatService {
    private static final Logger log = LoggerFactory.getLogger(AntiCheatService.class);

    private final AntiCheatRepository antiCheatRepo;
    private final AttemptRepository attemptRepo;
    private final QuizRepository quizRepo;
    private final AttemptService attemptService;
    private final ConnectionRegistry connectionRegistry;
    private final TransactionManager transactionManager;
    private final Clock clock;

    public AntiCheatService(AntiCheatRepository antiCheatRepo,
                            AttemptRepository attemptRepo,
                            QuizRepository quizRepo,
                            AttemptService attemptService,
                            ConnectionRegistry connectionRegistry,
                            TransactionManager transactionManager) {
        this(antiCheatRepo, attemptRepo, quizRepo, attemptService, connectionRegistry, transactionManager, Clock.systemUTC());
    }

    AntiCheatService(AntiCheatRepository antiCheatRepo,
                     AttemptRepository attemptRepo,
                     QuizRepository quizRepo,
                     AttemptService attemptService,
                     ConnectionRegistry connectionRegistry,
                     TransactionManager transactionManager,
                     Clock clock) {
        this.antiCheatRepo = Objects.requireNonNull(antiCheatRepo, "AntiCheatRepository cannot be null");
        this.attemptRepo = Objects.requireNonNull(attemptRepo, "AttemptRepository cannot be null");
        this.quizRepo = Objects.requireNonNull(quizRepo, "QuizRepository cannot be null");
        this.attemptService = Objects.requireNonNull(attemptService, "AttemptService cannot be null");
        this.connectionRegistry = Objects.requireNonNull(connectionRegistry, "ConnectionRegistry cannot be null");
        this.transactionManager = Objects.requireNonNull(transactionManager, "TransactionManager cannot be null");
        this.clock = Objects.requireNonNull(clock, "Clock cannot be null");
    }

    private record EventResult(int countAfter, String action, int maxViolations) {}

    /**
     * Tiếp nhận báo cáo vi phạm từ Client, ghi nhận nhật ký (Evidence BR-13),
     * áp dụng chính sách xử lý vi phạm (BR-12) và gửi cảnh báo / tự động nộp bài.
     */
    public void reportEvent(ReportAntiCheatDto dto, long studentId) {
        Objects.requireNonNull(dto, "ReportAntiCheatDto cannot be null");
        Attempt attempt = attemptRepo.findById(dto.attemptId())
            .orElseThrow(() -> new NotFoundException("Lượt làm bài không tồn tại"));

        if (attempt.studentId() != studentId) {
            throw new ForbiddenException("Lượt làm bài không thuộc về bạn");
        }

        // NFR-09: Nếu bài thi đã kết thúc thì không xử lý tiếp để tránh lỗi xung đột submit
        if (attempt.status() != AttemptStatus.IN_PROGRESS) {
            log.info("Bỏ qua sự kiện gian lận cho attemptId={} vì trạng thái là {}", attempt.id(), attempt.status());
            return;
        }

        Quiz quiz = quizRepo.findById(attempt.quizId())
            .orElseThrow(() -> new NotFoundException("Bài kiểm tra không tồn tại"));

        AntiCheatPolicyType policy = quiz.anticheatPolicy() != null ? quiz.anticheatPolicy() : AntiCheatPolicyType.WARN_THEN_AUTO_SUBMIT;
        int maxViolations = quiz.maxViolations() > 0 ? quiz.maxViolations() : 3;
        Instant now = clock.instant();

        EventResult result = transactionManager.executeWithException(conn -> {
            int currentViolations = antiCheatRepo.countViolations(conn, dto.attemptId());
            int violationCountAfter = currentViolations + 1;
            String action;

            if (policy == AntiCheatPolicyType.LOG_ONLY) {
                action = "NONE";
            } else {
                if (violationCountAfter >= maxViolations) {
                    action = "AUTO_SUBMITTED";
                } else {
                    action = "WARNED";
                }
            }

            String detail = dto.detail();
            if (detail != null && detail.length() > 255) {
                detail = detail.substring(0, 255);
            }

            antiCheatRepo.recordEvent(
                conn,
                dto.attemptId(),
                dto.eventType(),
                detail,
                dto.clientTime() != null ? dto.clientTime() : now,
                now,
                violationCountAfter,
                action
            );

            return new EventResult(violationCountAfter, action, maxViolations);
        });

        log.warn("Sự kiện gian lận ghi nhận: attemptId={}, eventType={}, detail={}, count={}/{}, action={}",
            dto.attemptId(), dto.eventType(), dto.detail(), result.countAfter(), result.maxViolations(), result.action());

        // Thực hiện hành động sau giao dịch
        if ("WARNED".equals(result.action())) {
            String warningMsg = String.format("CẢNH BÁO VI PHẠM (%d/%d): Bạn vừa vi phạm quy chế thi (%s). Vượt quá %d lần bài thi sẽ tự động bị thu hồi!",
                result.countAfter(), result.maxViolations(), dto.eventType(), result.maxViolations());
            connectionRegistry.sendToUser(studentId, Response.push(
                MessageType.PUSH_ANTICHEAT_WARNING,
                new AntiCheatWarningDto(result.countAfter(), result.maxViolations(), warningMsg)
            ));
        } else if ("AUTO_SUBMITTED".equals(result.action())) {
            try {
                attemptService.submitAttempt(dto.attemptId(), studentId, SubmitReason.ANTICHEAT);
            } catch (Exception e) {
                log.warn("Lỗi khi tự động nộp bài vi phạm attemptId={}: {}", dto.attemptId(), e.getMessage());
            }
            connectionRegistry.sendToUser(studentId, Response.push(
                MessageType.PUSH_FORCE_SUBMITTED,
                "Bài thi của bạn đã bị tự động nộp do vi phạm quy chế thi vượt quá " + result.maxViolations() + " lần."
            ));
        }
    }

    /**
     * Tra cứu nhật ký vi phạm của một lượt làm bài (dành cho Giáo viên & Quản trị viên).
     */
    public List<AntiCheatEventDto> listAttemptEvents(long attemptId, UserSession session) {
        Objects.requireNonNull(session, "UserSession cannot be null");
        if (session.role() != Role.TEACHER && session.role() != Role.ADMIN) {
            throw new ForbiddenException("Chỉ Giáo viên và Quản trị viên mới có quyền xem nhật ký vi phạm");
        }

        attemptRepo.findById(attemptId).orElseThrow(() -> new NotFoundException("Lượt làm bài không tồn tại"));
        return antiCheatRepo.findEventsByAttemptId(attemptId);
    }
}
