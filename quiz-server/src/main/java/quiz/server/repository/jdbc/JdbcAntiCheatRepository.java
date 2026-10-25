package quiz.server.repository.jdbc;

import quiz.common.dto.AntiCheatEventDto;
import quiz.common.enums.AntiCheatEventType;
import quiz.common.protocol.ErrorCode;
import quiz.server.exception.QuizException;
import quiz.server.repository.AntiCheatRepository;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * PostgreSQL persistence for anticheat_events table.
 */
public final class JdbcAntiCheatRepository implements AntiCheatRepository {
    private final DataSource dataSource;

    public JdbcAntiCheatRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource);
    }

    @Override
    public void recordEvent(Connection c, long attemptId, AntiCheatEventType eventType,
                            String detail, Instant clientTime, Instant serverTime,
                            int violationCountAfter, String actionTaken) {
        String sql = """
            INSERT INTO anticheat_events (attempt_id, event_type, detail, client_time, server_time, violation_count_after, action_taken)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """;
        try (PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, attemptId);
            s.setString(2, eventType.name());
            s.setString(3, detail);
            if (clientTime != null) {
                s.setObject(4, utc(clientTime));
            } else {
                s.setNull(4, Types.TIMESTAMP_WITH_TIMEZONE);
            }
            s.setObject(5, utc(serverTime));
            s.setInt(6, violationCountAfter);
            s.setString(7, actionTaken);
            s.executeUpdate();
        } catch (SQLException e) {
            throw new QuizException(ErrorCode.DATABASE_ERROR, "Lưu sự kiện gian lận thất bại", e);
        }
    }

    @Override
    public int countViolations(Connection c, long attemptId) {
        String sql = "SELECT COUNT(*) FROM anticheat_events WHERE attempt_id = ?";
        if (c != null) {
            try (PreparedStatement s = c.prepareStatement(sql)) {
                s.setLong(1, attemptId);
                try (ResultSet r = s.executeQuery()) {
                    return r.next() ? r.getInt(1) : 0;
                }
            } catch (SQLException e) {
                throw new QuizException(ErrorCode.DATABASE_ERROR, "Đếm số lần vi phạm thất bại", e);
            }
        }
        try (Connection conn = dataSource.getConnection();
             PreparedStatement s = conn.prepareStatement(sql)) {
            s.setLong(1, attemptId);
            try (ResultSet r = s.executeQuery()) {
                return r.next() ? r.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new QuizException(ErrorCode.DATABASE_ERROR, "Đếm số lần vi phạm thất bại", e);
        }
    }

    @Override
    public List<AntiCheatEventDto> findEventsByAttemptId(long attemptId) {
        String sql = """
            SELECT id, attempt_id, event_type, detail, client_time, server_time, violation_count_after, action_taken
            FROM anticheat_events
            WHERE attempt_id = ?
            ORDER BY id ASC
            """;
        try (Connection c = dataSource.getConnection();
             PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, attemptId);
            try (ResultSet r = s.executeQuery()) {
                List<AntiCheatEventDto> list = new ArrayList<>();
                while (r.next()) {
                    OffsetDateTime clientDt = r.getObject("client_time", OffsetDateTime.class);
                    OffsetDateTime serverDt = r.getObject("server_time", OffsetDateTime.class);
                    list.add(new AntiCheatEventDto(
                        r.getLong("id"),
                        r.getLong("attempt_id"),
                        AntiCheatEventType.valueOf(r.getString("event_type")),
                        r.getString("detail"),
                        clientDt != null ? clientDt.toInstant() : null,
                        serverDt != null ? serverDt.toInstant() : null,
                        r.getInt("violation_count_after"),
                        r.getString("action_taken")
                    ));
                }
                return List.copyOf(list);
            }
        } catch (SQLException e) {
            throw new QuizException(ErrorCode.DATABASE_ERROR, "Truy vấn danh sách vi phạm thất bại", e);
        }
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
