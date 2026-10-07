package quiz.common.enums;

import java.io.Serializable;

/**
 * Lý do nộp bài kiểm tra.
 */
public enum SubmitReason implements Serializable {
    MANUAL,     // Học viên chủ động bấm nộp
    TIMEOUT,    // Hết giờ làm bài do server tính toán
    ANTICHEAT   // Tự động nộp do vượt quá ngưỡng vi phạm gian lận
}
