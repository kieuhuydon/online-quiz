package quiz.server.service.anticheat;

import quiz.server.domain.anticheat.AntiCheatAction;

/**
 * Chính sách LOG_ONLY:
 * Chỉ ghi nhận nhật ký làm bằng chứng (BR-13), không cảnh báo và không tự nộp bài.
 */
public final class LogOnlyPolicy implements AntiCheatPolicy {
    @Override
    public AntiCheatAction decide(int currentViolationCount, int maxViolations) {
        return AntiCheatAction.NONE;
    }
}

