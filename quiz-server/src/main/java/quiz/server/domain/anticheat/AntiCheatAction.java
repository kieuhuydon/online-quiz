package quiz.server.domain.anticheat;

/**
 * Hành động được quyết định bởi AntiCheatPolicy sau khi ghi nhận một vi phạm (BR-12).
 */
public enum AntiCheatAction {
    NONE,           // Chỉ ghi nhận nhật ký (áp dụng cho LOG_ONLY)
    WARNED,         // Cảnh báo học viên (khi chưa vượt quá maxViolations)
    AUTO_SUBMITTED  // Tự động nộp bài thi (khi đạt maxViolations)
}

