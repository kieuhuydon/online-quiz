package quiz.server.service.anticheat;

import quiz.server.domain.anticheat.AntiCheatAction;

/**
 * Chiến lược (Strategy) xử lý vi phạm gian lận theo chính sách của bài kiểm tra (BR-12).
 */
public interface AntiCheatPolicy {
    /**
     * Quyết định hành động xử lý dựa trên số lần vi phạm hiện tại và ngưỡng vi phạm tối đa.
     *
     * @param currentViolationCount số lần vi phạm đã ghi nhận (bao gồm lần này)
     * @param maxViolations ngưỡng vi phạm tối đa cấu hình trong Quiz
     * @return hành động cần thực thi: NONE, WARNED hoặc AUTO_SUBMITTED
     */
    AntiCheatAction decide(int currentViolationCount, int maxViolations);
}

