package quiz.server.service.anticheat;

import quiz.server.domain.anticheat.AntiCheatAction;

/**
 * Chính sách WARN_THEN_AUTO_SUBMIT:
 * Cảnh báo học viên khi vi phạm dưới ngưỡng; tự động nộp bài khi đạt maxViolations.
 */
public final class WarnThenAutoSubmitPolicy implements AntiCheatPolicy {
    @Override
    public AntiCheatAction decide(int currentViolationCount, int maxViolations) {
        if (currentViolationCount >= maxViolations) {
            return AntiCheatAction.AUTO_SUBMITTED;
        }
        return AntiCheatAction.WARNED;
    }
}

