package quiz.server.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quiz.common.dto.AntiCheatEventDto;
import quiz.common.dto.AntiCheatWarningDto;
import quiz.common.dto.ReportAntiCheatDto;
import quiz.common.enums.Role;
import quiz.common.protocol.ErrorCode;
import quiz.common.protocol.MessageType;
import quiz.common.protocol.Response;
import quiz.server.db.TransactionManager;
import quiz.server.domain.anticheat.AntiCheatAction;
import quiz.server.domain.anticheat.AntiCheatRecord;
import quiz.server.domain.anticheat.AttemptMonitoringInfo;
import quiz.server.exception.QuizException;
import quiz.server.network.ConnectionRegistry;
import quiz.server.network.UserSession;
import quiz.server.repository.AntiCheatRepository;
import quiz.server.service.anticheat.AntiCheatPolicy;
import quiz.server.service.anticheat.AntiCheatPolicyFactory;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Service xử lý nghiệp vụ giám sát và chống gian lận (FR-S13, FR-S14, FR-T09, BR-12, BR-13, NFR-09).
 */
public class AntiCheatService {
    private static final Logger log = LoggerFactory.getLogger(AntiCheatService.class);

    private final AntiCheatRepository repository;
    private final TransactionManager txManager;
    private final ConnectionRegistry connections;

    public AntiCheatService(AntiCheatRepository repository, TransactionManager txManager, ConnectionRegistry connections) {
        this.repository = Objects.requireNonNull(repository, "AntiCheatRepository cannot be null");
        this.txManager = Objects.requireNonNull(txManager, "TransactionManager cannot be null");
        this.connections = connections; // may be null in test mocks
    }

    /**
     * Ghi nhận sự kiện vi phạm chống gian lận từ học viên (FR-S13, FR-T09).
     * Thực hiện kiểm tra trong transaction (khóa dòng Attempt), đếm vi phạm, áp dụng chính sách,
     * lưu nhật ký và gửi thông báo cảnh báo / buộc nộp bài qua kênh PUSH Socket.
     */
    public void reportViolation(long studentId, ReportAntiCheatDto dto) {
        if (dto == null || dto.attemptId() <= 0 || dto.eventType() == null) {
            log.warn("Invalid anti-cheat report dto received: {}", dto);
            return;
        }

        try {
            // Bước 1 & 2 & 3: Transaction thực thi khóa attempt và cập nhật
            DecisionResult decision = txManager.executeInTransaction(conn -> {
                Optional<AttemptMonitoringInfo> opt = repository.findAttemptForMonitoring(dto.attemptId(), conn);
                if (opt.isEmpty()) {
                    log.warn("Attempt {} not found for anti-cheat reporting", dto.attemptId());
                    return null;
                }

                AttemptMonitoringInfo info = opt.get();
                if (info.studentId() != studentId) {
                    log.warn("Attempt {} belongs to student {} but reported by student {}",
                        dto.attemptId(), info.studentId(), studentId);
                    return null;
                }

                // Nếu Attempt đã nộp hoặc không còn IN_PROGRESS -> bỏ qua êm (không lỗi)
                if (!"IN_PROGRESS".equalsIgnoreCase(info.status())) {
                    log.info("Attempt {} is already submitted/finished ({}), ignoring anti-cheat event {}",
                        dto.attemptId(), info.status(), dto.eventType());
                    return null;
                }

                // Đếm vi phạm trước đó và cộng thêm 1
                int currentCount = repository.countViolations(dto.attemptId(), conn);
                int newCount = currentCount + 1;

                // Áp dụng Strategy AntiCheatPolicy
                AntiCheatPolicy policy = AntiCheatPolicyFactory.getPolicy(info.policyType());
                AntiCheatAction action = policy.decide(newCount, info.maxViolations());

                // Ghi nhận bản ghi sự kiện vào DB
                AntiCheatRecord record = AntiCheatRecord.createNew(
                    dto.attemptId(),
                    dto.eventType(),
                    dto.detail(),
                    dto.clientTime(),
                    newCount,
                    action.name()
                );
                repository.save(record, conn);

                // Nếu chính sách quyết định AUTO_SUBMITTED -> tự động nộp bài thi
                if (action == AntiCheatAction.AUTO_SUBMITTED) {
                    repository.autoSubmitAttempt(dto.attemptId(), conn);
                }

                return new DecisionResult(action, newCount, info.maxViolations(), dto);
            });

            if (decision == null) {
                return;
            }

            // Bước 4: Sau khi transaction commit thành công, phát thông báo PUSH tới học viên
            dispatchPushNotification(studentId, decision);

        } catch (Exception ex) {
            // NFR-09: Lỗi ghi sự kiện không được làm vỡ luồng bài thi; ghi log cảnh báo
            log.error("Failed to process anti-cheat event for studentId={}, attemptId={}: {}",
                studentId, dto.attemptId(), ex.getMessage(), ex);
        }
    }

    private void dispatchPushNotification(long studentId, DecisionResult decision) {
        if (connections == null) {
            return;
        }

        if (decision.action() == AntiCheatAction.WARNED) {
            String msg = String.format("CẢNH BÁO: Phát hiện vi phạm (%s)! Bạn đã vi phạm %d/%d lần cho phép.",
                formatEventName(decision.dto().eventType()), decision.violationCount(), decision.maxViolations());

            AntiCheatWarningDto warningPayload = new AntiCheatWarningDto(
                decision.violationCount(),
                decision.maxViolations(),
                msg
            );
            Response pushResp = Response.push(MessageType.PUSH_ANTICHEAT_WARNING, warningPayload);
            connections.sendToUser(studentId, pushResp);
            log.info("Sent PUSH_ANTICHEAT_WARNING to studentId={}, count={}/{}",
                studentId, decision.violationCount(), decision.maxViolations());

        } else if (decision.action() == AntiCheatAction.AUTO_SUBMITTED) {
            String msg = String.format("Bài thi đã bị HỆ THỐNG TỰ ĐỘNG THU HỒI VÀ NỘP BÀI do bạn đã vi phạm quy chế thi vượt quá %d lần cho phép!",
                decision.maxViolations());

            Response pushResp = Response.push(MessageType.PUSH_FORCE_SUBMITTED, msg);
            connections.sendToUser(studentId, pushResp);
            log.warn("Sent PUSH_FORCE_SUBMITTED to studentId={}, violated max {} times",
                studentId, decision.maxViolations());
        }
    }

    /**
     * Giáo viên / Quản trị viên xem bằng chứng các sự kiện vi phạm của một Attempt (FR-T09, BR-13).
     */
    public List<AntiCheatEventDto> listAttemptEvents(long attemptId, UserSession session) {
        if (session == null || (session.role() != Role.TEACHER && session.role() != Role.ADMIN)) {
            throw new QuizException(ErrorCode.FORBIDDEN, "Only teachers or admins can view anti-cheat evidence");
        }
        if (attemptId <= 0) {
            throw new QuizException(ErrorCode.VALIDATION_ERROR, "Invalid attemptId: " + attemptId);
        }

        List<AntiCheatRecord> records = repository.findByAttemptId(attemptId);
        if (records == null || records.isEmpty()) {
            return Collections.emptyList();
        }

        return records.stream()
            .map(r -> new AntiCheatEventDto(
                r.id(),
                r.attemptId(),
                r.eventType(),
                r.detail(),
                r.clientTime(),
                r.serverTime(),
                r.violationCountAfter(),
                r.actionTaken()
            ))
            .toList();
    }

    private static String formatEventName(quiz.common.enums.AntiCheatEventType type) {
        if (type == null) return "Không xác định";
        return switch (type) {
            case FOCUS_LOST -> "Rời khỏi cửa sổ thi / chuyển tab";
            case COPY_ATTEMPT -> "Cố tình sao chép nội dung";
            case PASTE_ATTEMPT -> "Cố tình dán văn bản";
            case CUT_ATTEMPT -> "Cố tình cắt nội dung";
            case CONTEXT_MENU_ATTEMPT -> "Bấm chuột phải mở menu";
        };
    }

    private record DecisionResult(
        AntiCheatAction action,
        int violationCount,
        int maxViolations,
        ReportAntiCheatDto dto
    ) {}
}

