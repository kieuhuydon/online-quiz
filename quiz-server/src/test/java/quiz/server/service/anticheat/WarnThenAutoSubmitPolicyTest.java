package quiz.server.service.anticheat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import quiz.common.enums.AntiCheatPolicyType;
import quiz.server.domain.anticheat.AntiCheatAction;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WarnThenAutoSubmitPolicyTest {

    private final WarnThenAutoSubmitPolicy policy = new WarnThenAutoSubmitPolicy();

    @Test
    @DisplayName("Returns WARNED when violation count is below maxViolations")
    void returnsWarnedBelowThreshold() {
        assertEquals(AntiCheatAction.WARNED, policy.decide(1, 3));
        assertEquals(AntiCheatAction.WARNED, policy.decide(2, 3));
    }

    @Test
    @DisplayName("Returns AUTO_SUBMITTED when violation count reaches or exceeds maxViolations")
    void returnsAutoSubmittedAtThreshold() {
        assertEquals(AntiCheatAction.AUTO_SUBMITTED, policy.decide(3, 3));
        assertEquals(AntiCheatAction.AUTO_SUBMITTED, policy.decide(4, 3));
    }

    @Test
    @DisplayName("Factory produces appropriate policies")
    void factoryProducesExpectedPolicies() {
        assertEquals(LogOnlyPolicy.class, AntiCheatPolicyFactory.getPolicy(AntiCheatPolicyType.LOG_ONLY).getClass());
        assertEquals(WarnThenAutoSubmitPolicy.class, AntiCheatPolicyFactory.getPolicy(AntiCheatPolicyType.WARN_THEN_AUTO_SUBMIT).getClass());
    }
}

