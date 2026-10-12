package quiz.common.enums;

/**
 * Lý do nộp bài kiểm tra.
 */
public enum SubmitReason {
    MANUAL,     // Học viên chủ động bấm nộp
    TIMEOUT,    // Hết giờ làm bài do server tính toán
    ANTICHEAT   // Tự động nộp do vượt quá ngưỡng vi phạm gian lận
}
