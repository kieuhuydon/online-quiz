package quiz.server.service.anticheat;

import quiz.common.enums.AntiCheatPolicyType;

/**
 * Factory cung cấp instance AntiCheatPolicy tương ứng với cấu hình của Quiz.
 */
public final class AntiCheatPolicyFactory {
    private static final AntiCheatPolicy WARN_THEN_AUTO_SUBMIT = new WarnThenAutoSubmitPolicy();
    private static final AntiCheatPolicy LOG_ONLY = new LogOnlyPolicy();

    private AntiCheatPolicyFactory() { }

    public static AntiCheatPolicy getPolicy(AntiCheatPolicyType type) {
        if (type == AntiCheatPolicyType.LOG_ONLY) {
            return LOG_ONLY;
        }
        return WARN_THEN_AUTO_SUBMIT;
    }
}

