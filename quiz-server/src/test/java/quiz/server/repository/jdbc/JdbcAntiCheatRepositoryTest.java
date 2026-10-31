package quiz.server.repository.jdbc;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import quiz.common.dto.AntiCheatEventDto;
import quiz.common.enums.AntiCheatEventType;
import quiz.common.protocol.ErrorCode;
import quiz.server.exception.QuizException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@DisplayName("JdbcAntiCheatRepository Unit Tests")
class JdbcAntiCheatRepositoryTest {

    private DataSource dataSource;
    private Connection connection;
    private PreparedStatement statement;
    private Statement rawStatement;
    private ResultSet rows;
    private JdbcAntiCheatRepository repository;

    private final Instant now = Instant.parse("2026-10-30T08:00:00Z");
    private final OffsetDateTime nowUtc = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);

    @BeforeEach
    void setUp() throws SQLException {
        dataSource = mock(DataSource.class);
        connection = mock(Connection.class);
        statement = mock(PreparedStatement.class);
        rawStatement = mock(Statement.class);
        rows = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(connection.createStatement()).thenReturn(rawStatement);
        when(statement.executeQuery()).thenReturn(rows);
        repository = new JdbcAntiCheatRepository(dataSource);
    }

    @Test
    @DisplayName("ensureConstraint, recordEvent, countViolations và findEventsByAttemptId hoạt động đúng")
    void recordCountAndFindEvents() throws SQLException {
        assertDoesNotThrow(() -> repository.ensureConstraint());
        verify(rawStatement, times(2)).execute(anyString());

        // recordEvent with non-null clientTime
        repository.recordEvent(connection, 100L, AntiCheatEventType.FOCUS_LOST, "Alt+Tab", now, now, 1, "WARNED");
        verify(statement).setLong(1, 100L);
        verify(statement).setString(2, "FOCUS_LOST");
        verify(statement).setObject(4, nowUtc);
        verify(statement).executeUpdate();

        // recordEvent with null clientTime
        repository.recordEvent(connection, 100L, AntiCheatEventType.COPY_ATTEMPT, "Ctrl+C", null, now, 2, "WARNED");
        verify(statement).setNull(4, Types.TIMESTAMP_WITH_TIMEZONE);

        // countViolations with caller connection and owned connection
        when(rows.next()).thenReturn(true);
        when(rows.getInt(1)).thenReturn(2);
        assertEquals(2, repository.countViolations(connection, 100L));
        assertEquals(2, repository.countViolations(null, 100L));

        // findEventsByAttemptId
        when(rows.next()).thenReturn(true, false);
        when(rows.getLong("id")).thenReturn(1L);
        when(rows.getLong("attempt_id")).thenReturn(100L);
        when(rows.getString("event_type")).thenReturn("PROHIBITED_PROCESS");
        when(rows.getString("detail")).thenReturn("Discord");
        when(rows.getObject("client_time", OffsetDateTime.class)).thenReturn(nowUtc);
        when(rows.getObject("server_time", OffsetDateTime.class)).thenReturn(nowUtc);
        when(rows.getInt("violation_count_after")).thenReturn(1);
        when(rows.getString("action_taken")).thenReturn("WARNED");

        List<AntiCheatEventDto> events = repository.findEventsByAttemptId(100L);
        assertEquals(1, events.size());
        assertEquals(AntiCheatEventType.PROHIBITED_PROCESS, events.get(0).eventType());
        assertEquals("Discord", events.get(0).detail());
    }

    @Test
    @DisplayName("Bọc SQLException thành QuizException(DATABASE_ERROR)")
    void wrapsSqlExceptionsIntoQuizException() throws SQLException {
        when(statement.executeUpdate()).thenThrow(new SQLException("DB down"));
        QuizException ex1 = assertThrows(QuizException.class, () ->
            repository.recordEvent(connection, 1L, AntiCheatEventType.FOCUS_LOST, "d", now, now, 1, "WARNED")
        );
        assertEquals(ErrorCode.DATABASE_ERROR, ex1.getErrorCode());

        when(statement.executeQuery()).thenThrow(new SQLException("DB down"));
        QuizException ex2 = assertThrows(QuizException.class, () -> repository.countViolations(connection, 1L));
        assertEquals(ErrorCode.DATABASE_ERROR, ex2.getErrorCode());

        QuizException ex3 = assertThrows(QuizException.class, () -> repository.findEventsByAttemptId(1L));
        assertEquals(ErrorCode.DATABASE_ERROR, ex3.getErrorCode());
    }
}
