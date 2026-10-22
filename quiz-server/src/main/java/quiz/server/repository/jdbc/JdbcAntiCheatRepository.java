package quiz.server.repository.jdbc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quiz.common.enums.AntiCheatEventType;
import quiz.common.enums.AntiCheatPolicyType;
import quiz.common.protocol.ErrorCode;
import quiz.server.domain.anticheat.AntiCheatRecord;
import quiz.server.domain.anticheat.AttemptMonitoringInfo;
import quiz.server.exception.QuizException;
import quiz.server.repository.AntiCheatRepository;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Triển khai JDBC cho AntiCheatRepository (FR-T09, NFR-09).
 */
public final class JdbcAntiCheatRepository implements AntiCheatRepository {
    private static final Logger log = LoggerFactory.getLogger(JdbcAntiCheatRepository.class);
    private final DataSource dataSource;

    public JdbcAntiCheatRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "DataSource cannot be null");
    }

    @Override
    public AntiCheatRecord save(AntiCheatRecord record, Connection connection) {
        Objects.requireNonNull(record, "record cannot be null");
        Objects.requireNonNull(connection, "connection cannot be null");

        String sql = """
            INSERT INTO anticheat_events (attempt_id, event_type, detail, client_time, server_time, violation_count_after, action_taken)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            RETURNING id, server_time
            """;

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, record.attemptId());
            ps.setString(2, record.eventType().name());
            ps.setString(3, record.detail());
            ps.setTimestamp(4, record.clientTime() != null ? Timestamp.from(record.clientTime()) : null);
            ps.setTimestamp(5, Timestamp.from(record.serverTime() != null ? record.serverTime() : Instant.now()));
            ps.setInt(6, record.violationCountAfter());
            ps.setString(7, record.actionTaken());

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long id = rs.getLong("id");
                    Instant serverTime = rs.getTimestamp("server_time").toInstant();
                    return new AntiCheatRecord(
                        id,
                        record.attemptId(),
                        record.eventType(),
                        record.detail(),
                        record.clientTime(),
                        serverTime,
                        record.violationCountAfter(),
                        record.actionTaken()
                    );
                }
                throw new QuizException(ErrorCode.DATABASE_ERROR, "Failed to retrieve generated anti-cheat event id");
            }
        } catch (SQLException e) {
            log.error("Failed to insert anti_cheat_events for attemptId={}: {}", record.attemptId(), e.getMessage());
            throw new QuizException(ErrorCode.DATABASE_ERROR, "Database error saving anti-cheat event", e);
        }
    }

    @Override
    public AntiCheatRecord save(AntiCheatRecord record) {
        try (Connection conn = dataSource.getConnection()) {
            return save(record, conn);
        } catch (SQLException e) {
            log.error("Failed to acquire connection for save anti-cheat event: {}", e.getMessage());
            throw new QuizException(ErrorCode.DATABASE_ERROR, "Database connection error", e);
        }
    }

    @Override
    public int countViolations(long attemptId, Connection connection) {
        Objects.requireNonNull(connection, "connection cannot be null");
        String sql = "SELECT COUNT(*) FROM anticheat_events WHERE attempt_id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, attemptId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
                return 0;
            }
        } catch (SQLException e) {
            log.error("Failed to count violations for attemptId={}: {}", attemptId, e.getMessage());
            throw new QuizException(ErrorCode.DATABASE_ERROR, "Database error counting violations", e);
        }
    }

    @Override
    public Optional<AttemptMonitoringInfo> findAttemptForMonitoring(long attemptId, Connection connection) {
        Objects.requireNonNull(connection, "connection cannot be null");
        String sql = """
            SELECT a.id AS attempt_id, a.quiz_id, a.student_id, a.status,
                   q.anticheat_policy, q.max_violations
            FROM attempts a
            JOIN quizzes q ON a.quiz_id = q.id
            WHERE a.id = ?
            FOR UPDATE
            """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, attemptId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String policyStr = rs.getString("anticheat_policy");
                    AntiCheatPolicyType policyType = policyStr != null
                        ? AntiCheatPolicyType.valueOf(policyStr)
                        : AntiCheatPolicyType.WARN_THEN_AUTO_SUBMIT;

                    return Optional.of(new AttemptMonitoringInfo(
                        rs.getLong("attempt_id"),
                        rs.getLong("quiz_id"),
                        rs.getLong("student_id"),
                        rs.getString("status"),
                        policyType,
                        rs.getInt("max_violations")
                    ));
                }
                return Optional.empty();
            }
        } catch (SQLException e) {
            log.error("Failed to find attempt for monitoring attemptId={}: {}", attemptId, e.getMessage());
            throw new QuizException(ErrorCode.DATABASE_ERROR, "Database error locking attempt for monitoring", e);
        }
    }

    @Override
    public List<AntiCheatRecord> findByAttemptId(long attemptId) {
        String sql = """
            SELECT id, attempt_id, event_type, detail, client_time, server_time, violation_count_after, action_taken
            FROM anticheat_events
            WHERE attempt_id = ?
            ORDER BY server_time ASC, id ASC
            """;

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, attemptId);
            try (ResultSet rs = ps.executeQuery()) {
                List<AntiCheatRecord> list = new ArrayList<>();
                while (rs.next()) {
                    Timestamp clientTs = rs.getTimestamp("client_time");
                    Timestamp serverTs = rs.getTimestamp("server_time");
                    list.add(new AntiCheatRecord(
                        rs.getLong("id"),
                        rs.getLong("attempt_id"),
                        AntiCheatEventType.valueOf(rs.getString("event_type")),
                        rs.getString("detail"),
                        clientTs != null ? clientTs.toInstant() : null,
                        serverTs != null ? serverTs.toInstant() : Instant.now(),
                        rs.getInt("violation_count_after"),
                        rs.getString("action_taken")
                    ));
                }
                return list;
            }
        } catch (SQLException e) {
            log.error("Failed to query anticheat_events for attemptId={}: {}", attemptId, e.getMessage());
            throw new QuizException(ErrorCode.DATABASE_ERROR, "Database error loading anti-cheat events", e);
        }
    }

    @Override
    public boolean autoSubmitAttempt(long attemptId, Connection connection) {
        Objects.requireNonNull(connection, "connection cannot be null");
        Instant now = Instant.now();

        String updateAttemptSql = """
            UPDATE attempts
            SET status = 'SUBMITTED', submitted_at = ?
            WHERE id = ? AND status = 'IN_PROGRESS'
            """;

        try (PreparedStatement ps = connection.prepareStatement(updateAttemptSql)) {
            ps.setTimestamp(1, Timestamp.from(now));
            ps.setLong(2, attemptId);
            int updated = ps.executeUpdate();
            if (updated > 0) {
                // Ensure result row exists or is updated with ANTICHEAT submit reason
                recordForceSubmitResult(attemptId, connection, now);
                return true;
            }
            return false;
        } catch (SQLException e) {
            log.error("Failed to auto-submit attemptId={}: {}", attemptId, e.getMessage());
            throw new QuizException(ErrorCode.DATABASE_ERROR, "Database error on auto-submit", e);
        }
    }

    private void recordForceSubmitResult(long attemptId, Connection conn, Instant now) throws SQLException {
        // Query total questions in quiz
        String countSql = """
            SELECT COUNT(*) AS total_q
            FROM quiz_questions qq
            JOIN attempts a ON a.quiz_id = qq.quiz_id
            WHERE a.id = ?
            """;
        int totalQuestions = 0;
        try (PreparedStatement ps = conn.prepareStatement(countSql)) {
            ps.setLong(1, attemptId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    totalQuestions = rs.getInt("total_q");
                }
            }
        }
        if (totalQuestions <= 0) {
            totalQuestions = 1; // Schema check requires total_questions > 0
        }

        String insertResultSql = """
            INSERT INTO results (attempt_id, score, correct_count, total_questions, passed, submit_reason, graded_at)
            VALUES (?, 0.0, 0, ?, FALSE, 'ANTICHEAT', ?)
            ON CONFLICT (attempt_id)
            DO UPDATE SET submit_reason = 'ANTICHEAT'
            """;
        try (PreparedStatement ps = conn.prepareStatement(insertResultSql)) {
            ps.setLong(1, attemptId);
            ps.setInt(2, totalQuestions);
            ps.setTimestamp(3, Timestamp.from(now));
            ps.executeUpdate();
        }
    }
}

