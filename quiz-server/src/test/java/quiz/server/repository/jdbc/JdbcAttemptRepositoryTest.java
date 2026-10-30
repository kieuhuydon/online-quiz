package quiz.server.repository.jdbc;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import quiz.common.enums.AttemptStatus;
import quiz.common.enums.SubmitReason;
import quiz.common.protocol.ErrorCode;
import quiz.server.domain.AnswerRecord;
import quiz.server.domain.Attempt;
import quiz.server.domain.ExamResult;
import quiz.server.exception.ConflictException;
import quiz.server.exception.QuizException;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@DisplayName("JdbcAttemptRepository Unit Tests")
class JdbcAttemptRepositoryTest {

    private DataSource dataSource;
    private Connection connection;
    private PreparedStatement statement;
    private ResultSet rows;
    private JdbcAttemptRepository repository;

    private final Instant now = Instant.parse("2026-10-30T08:00:00Z");
    private final OffsetDateTime nowUtc = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);

    @BeforeEach
    void setUp() throws SQLException {
        dataSource = mock(DataSource.class);
        connection = mock(Connection.class);
        statement = mock(PreparedStatement.class);
        rows = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);
        repository = new JdbcAttemptRepository(dataSource);
    }

    private void stubAttemptRow() throws SQLException {
        when(rows.getLong("id")).thenReturn(100L);
        when(rows.getLong("quiz_id")).thenReturn(10L);
        when(rows.getLong("student_id")).thenReturn(5L);
        when(rows.getString("status")).thenReturn("IN_PROGRESS");
        when(rows.getObject("started_at", OffsetDateTime.class)).thenReturn(nowUtc);
        when(rows.getObject("deadline_at", OffsetDateTime.class)).thenReturn(nowUtc.plusMinutes(30));
        when(rows.getObject("submitted_at", OffsetDateTime.class)).thenReturn(null);
    }

    @Test
    @DisplayName("create, findById, findByIdForUpdate, findByStudentAndQuiz, findByStudentId và findExpiredInProgress ánh xạ đúng Attempt")
    void createAndFindAttempts() throws SQLException {
        stubAttemptRow();
        when(rows.next()).thenReturn(true, false);

        Attempt input = new Attempt(0L, 10L, 5L, AttemptStatus.IN_PROGRESS, now, now.plusSeconds(1800), null, null);
        Attempt created = repository.create(connection, input);
        assertEquals(100L, created.id());
        assertEquals(10L, created.quizId());
        assertEquals(5L, created.studentId());
        assertEquals(AttemptStatus.IN_PROGRESS, created.status());

        when(rows.next()).thenReturn(true, false);
        assertTrue(repository.findById(100L).isPresent());

        when(rows.next()).thenReturn(true, false);
        assertTrue(repository.findByIdForUpdate(connection, 100L).isPresent());

        when(rows.next()).thenReturn(true, false);
        assertTrue(repository.findByStudentAndQuiz(5L, 10L).isPresent());

        when(rows.next()).thenReturn(true, false);
        List<Attempt> byStudent = repository.findByStudentId(5L);
        assertEquals(1, byStudent.size());

        when(rows.next()).thenReturn(true, false);
        List<Attempt> expired = repository.findExpiredInProgress(now);
        assertEquals(1, expired.size());
    }

    @Test
    @DisplayName("submit, saveAnswer, getAnswers, saveResult, findResult và findResultsByStudent hoạt động đúng")
    void submitAnswersAndResults() throws SQLException {
        when(statement.executeUpdate()).thenReturn(1);
        repository.submit(connection, 100L, now, SubmitReason.MANUAL);
        verify(statement).setLong(2, 100L);

        // When executeUpdate != 1 on submit, throws ConflictException
        when(statement.executeUpdate()).thenReturn(0);
        assertThrows(ConflictException.class, () -> repository.submit(connection, 100L, now, SubmitReason.MANUAL));

        // saveAnswer with non-null and null option
        repository.saveAnswer(connection, new AnswerRecord(100L, 1L, 'B', now));
        verify(statement).setString(3, "B");

        repository.saveAnswer(connection, new AnswerRecord(100L, 2L, null, now));
        verify(statement).setNull(3, Types.CHAR);

        // getAnswers
        when(rows.next()).thenReturn(true, true, false);
        when(rows.getLong(1)).thenReturn(1L, 2L);
        when(rows.getString(2)).thenReturn("A", (String) null);
        Map<Long, Character> answers = repository.getAnswers(connection, 100L);
        assertEquals(Map.of(1L, 'A'), answers);

        // saveResult & findResult / findResultsByStudent
        ExamResult examResult = new ExamResult(100L, 10L, 5L, "Student Five", 8.5, 17, 20, true, SubmitReason.MANUAL, now);
        repository.saveResult(connection, examResult);
        verify(statement).setBigDecimal(2, BigDecimal.valueOf(8.5));

        when(rows.next()).thenReturn(true, false);
        when(rows.getLong("attempt_id")).thenReturn(100L);
        when(rows.getLong("quiz_id")).thenReturn(10L);
        when(rows.getLong("student_id")).thenReturn(5L);
        when(rows.getString("full_name")).thenReturn("Student Five");
        when(rows.getBigDecimal("score")).thenReturn(BigDecimal.valueOf(8.5));
        when(rows.getInt("correct_count")).thenReturn(17);
        when(rows.getInt("total_questions")).thenReturn(20);
        when(rows.getBoolean("passed")).thenReturn(true);
        when(rows.getString("submit_reason")).thenReturn("MANUAL");
        when(rows.getObject("submitted_at", OffsetDateTime.class)).thenReturn(nowUtc);

        assertTrue(repository.findResult(100L).isPresent());
        when(rows.next()).thenReturn(true, false);
        assertEquals(1, repository.findResultsByStudent(5L).size());
    }

    @Test
    @DisplayName("Chuyển đổi mã lỗi SQL 23505 sang ConflictException và lỗi SQL khác sang QuizException(DATABASE_ERROR)")
    void mapsUniqueViolationAndDatabaseError() throws SQLException {
        SQLException uniqueViolation = new SQLException("Duplicate", "23505");
        when(statement.executeQuery()).thenThrow(uniqueViolation);

        Attempt input = new Attempt(0L, 10L, 5L, AttemptStatus.IN_PROGRESS, now, now.plusSeconds(1800), null, null);
        assertThrows(ConflictException.class, () -> repository.create(connection, input));

        when(statement.executeUpdate()).thenThrow(uniqueViolation);
        ExamResult examResult = new ExamResult(100L, 10L, 5L, "Student", 8.0, 8, 10, true, SubmitReason.MANUAL, now);
        assertThrows(ConflictException.class, () -> repository.saveResult(connection, examResult));

        SQLException genericError = new SQLException("Connection reset", "08006");
        doThrow(genericError).when(statement).executeQuery();
        QuizException dbEx = assertThrows(QuizException.class, () -> repository.findById(100L));
        assertEquals(ErrorCode.DATABASE_ERROR, dbEx.getErrorCode());
    }
}
