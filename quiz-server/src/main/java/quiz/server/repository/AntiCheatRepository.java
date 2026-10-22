package quiz.server.repository;

import quiz.server.domain.anticheat.AntiCheatRecord;
import quiz.server.domain.anticheat.AttemptMonitoringInfo;

import java.sql.Connection;
import java.util.List;
import java.util.Optional;

/**
 * Hợp đồng lưu trữ cho phân hệ Anti-Cheat Monitoring (FR-T09).
 */
public interface AntiCheatRepository {

    /**
     * Lưu sự kiện vi phạm vào cơ sở dữ liệu trong một connection/transaction cụ thể.
     */
    AntiCheatRecord save(AntiCheatRecord record, Connection connection);

    /**
     * Lưu sự kiện vi phạm vào cơ sở dữ liệu.
     */
    AntiCheatRecord save(AntiCheatRecord record);

    /**
     * Đếm số lượng vi phạm hiện tại của một Attempt.
     */
    int countViolations(long attemptId, Connection connection);

    /**
     * Tìm thông tin Attempt cùng chính sách AntiCheat của Quiz, khóa dòng FOR UPDATE.
     */
    Optional<AttemptMonitoringInfo> findAttemptForMonitoring(long attemptId, Connection connection);

    /**
     * Lấy toàn bộ danh sách vi phạm của một Attempt để làm bằng chứng cho Giáo viên (BR-13).
     */
    List<AntiCheatRecord> findByAttemptId(long attemptId);

    /**
     * Tự động chuyển trạng thái Attempt thành SUBMITTED khi đạt maxViolations.
     */
    boolean autoSubmitAttempt(long attemptId, Connection connection);
}

