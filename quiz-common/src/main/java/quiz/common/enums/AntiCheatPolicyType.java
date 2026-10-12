package quiz.common.enums;

/**
 * Chính sách xử lý vi phạm gian lận của bài kiểm tra.
 */
public enum AntiCheatPolicyType {
    LOG_ONLY,                // Chỉ ghi nhận nhật ký vi phạm, không tự nộp bài
    WARN_THEN_AUTO_SUBMIT    // Cảnh báo khi còn dưới ngưỡng, tự động nộp bài khi đạt maxViolations
}
