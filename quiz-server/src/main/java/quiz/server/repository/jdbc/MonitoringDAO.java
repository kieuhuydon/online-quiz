package quiz.server.repository.jdbc;

import quiz.common.dto.*;
import quiz.common.enums.*;
import quiz.common.protocol.ErrorCode;
import quiz.server.domain.Attempt;
import quiz.server.exception.*;
import javax.sql.DataSource;
import java.sql.*;
import java.time.*;
import java.util.*;

/** JDBC persistence. Mutations share the caller's transaction and attempt row lock. */
public class MonitoringDAO {
    private final DataSource source;
    private static final String SELECT = "SELECT s.*,u.full_name,a.deadline_at FROM exam_sessions s "
        + "JOIN attempts a ON a.id=s.session_id JOIN users u ON u.id=s.student_id ";

    public MonitoringDAO(DataSource source) { this.source=Objects.requireNonNull(source); }

    public void create(Connection c, Attempt a, String ip) throws SQLException {
        try (PreparedStatement s=c.prepareStatement("INSERT INTO exam_sessions(session_id,exam_id,student_id,ip_address) VALUES(?,?,?,?) ON CONFLICT(session_id) DO UPDATE SET ip_address=COALESCE(EXCLUDED.ip_address,exam_sessions.ip_address)")) {
            s.setLong(1,a.id());s.setLong(2,a.quizId());s.setLong(3,a.studentId());s.setString(4,ip);s.executeUpdate();
        }
    }

    public ExamSession find(Connection c,long id,Instant now) throws SQLException {
        try(PreparedStatement s=c.prepareStatement(SELECT+"WHERE s.session_id=?")) {
            s.setLong(1,id);
            try(ResultSet r=s.executeQuery()) {
                if(!r.next()) throw new NotFoundException("Không tìm thấy phiên giám sát; kiểm tra migration");
                return map(r,now);
            }
        }
    }

    public List<ExamSession> list(long examId,Instant now) {
        try(Connection c=source.getConnection();PreparedStatement s=c.prepareStatement(SELECT+"WHERE s.exam_id=? ORDER BY s.student_id")) {
            s.setLong(1,examId);
            try(ResultSet r=s.executeQuery()) {
                List<ExamSession> rows=new ArrayList<>();
                while(r.next()) rows.add(map(r,now));
                return List.copyOf(rows);
            }
        } catch(SQLException e) { throw failure(e); }
    }

    public void recordViolation(Connection c,long id,AntiCheatEventType type,String detail,Instant now) throws SQLException {
        try(PreparedStatement s=c.prepareStatement("UPDATE exam_sessions SET violation_count=violation_count+1,"
            + "violation_details=?,status=CASE WHEN status='CHEATING' OR ? THEN 'CHEATING' ELSE 'WARNING' END,last_updated=? "
            + "WHERE session_id=? AND status NOT IN ('SUBMITTED','TERMINATED')")) {
            s.setString(1,type+": "+Objects.toString(detail,""));s.setBoolean(2,type==AntiCheatEventType.PROHIBITED_PROCESS);
            s.setObject(3,utc(now));s.setLong(4,id);s.executeUpdate();
        }
    }

    public void apply(Connection c,ApplyPenaltyDto p,long teacher,Instant now) throws SQLException {
        try(PreparedStatement s=c.prepareStatement("INSERT INTO monitoring_penalties VALUES(?,?,?,?,?,?,?)")) {
            s.setString(1,p.operationId());s.setLong(2,p.sessionId());s.setLong(3,teacher);s.setString(4,p.type().name());
            s.setDouble(5,p.amount());s.setString(6,p.reason().strip());s.setObject(7,utc(now));s.executeUpdate();
        } catch(SQLException e) {
            if("23505".equals(e.getSQLState())) throw new ConflictException("Yêu cầu xử phạt này đã được áp dụng");
            throw e;
        }
        try(PreparedStatement s=c.prepareStatement("UPDATE exam_sessions SET deducted_score=ROUND((deducted_score+?)::numeric,2),"
            + "deducted_time_seconds=deducted_time_seconds+?,is_force_stopped=is_force_stopped OR ?,"
            + "status=CASE WHEN ? THEN 'TERMINATED' WHEN status='CHEATING' THEN status ELSE 'WARNING' END,last_updated=? WHERE session_id=?")) {
            s.setDouble(1,p.type()==PenaltyType.DEDUCT_SCORE?p.amount():0);
            s.setInt(2,p.type()==PenaltyType.DEDUCT_TIME?(int)p.amount():0);
            s.setBoolean(3,p.type()==PenaltyType.TERMINATE);s.setBoolean(4,p.type()==PenaltyType.TERMINATE);
            s.setObject(5,utc(now));s.setLong(6,p.sessionId());s.executeUpdate();
        }
    }

    public void updateDeadline(Connection c,long id,Instant deadline) throws SQLException {
        try(PreparedStatement s=c.prepareStatement("UPDATE attempts SET deadline_at=? WHERE id=?")) {
            s.setObject(1,utc(deadline));s.setLong(2,id);s.executeUpdate();
        }
    }

    public void submitted(Connection c,long id,Instant now) throws SQLException {
        try(PreparedStatement s=c.prepareStatement("UPDATE exam_sessions SET status=CASE WHEN is_force_stopped THEN 'TERMINATED' ELSE 'SUBMITTED' END,last_updated=? WHERE session_id=?")) {
            s.setObject(1,utc(now));s.setLong(2,id);s.executeUpdate();
        }
    }

    public List<MonitoringPenalty> penalties(long id) {
        try(Connection c=source.getConnection();PreparedStatement s=c.prepareStatement("SELECT * FROM monitoring_penalties WHERE session_id=? ORDER BY applied_at,operation_id")) {
            s.setLong(1,id);
            try(ResultSet r=s.executeQuery()) {
                List<MonitoringPenalty> rows=new ArrayList<>();
                while(r.next()) rows.add(new MonitoringPenalty(r.getLong("teacher_id"),PenaltyType.valueOf(r.getString("penalty_type")),
                    r.getDouble("amount"),r.getString("reason"),r.getObject("applied_at",OffsetDateTime.class).toInstant()));
                return List.copyOf(rows);
            }
        } catch(SQLException e) { throw failure(e); }
    }

    private static ExamSession map(ResultSet r,Instant now) throws SQLException {
        MonitoringStatus status=MonitoringStatus.valueOf(r.getString("status"));
        long remaining=Math.max(0,(Duration.between(now,r.getObject("deadline_at",OffsetDateTime.class).toInstant()).toMillis()+999)/1000);
        if(status==MonitoringStatus.SUBMITTED||status==MonitoringStatus.TERMINATED) remaining=0;
        return new ExamSession(r.getLong("session_id"),r.getLong("exam_id"),r.getLong("student_id"),r.getString("full_name"),
            r.getString("ip_address"),status,r.getInt("violation_count"),r.getString("violation_details"),r.getDouble("deducted_score"),
            r.getInt("deducted_time_seconds"),r.getBoolean("is_force_stopped"),r.getObject("last_updated",OffsetDateTime.class).toInstant(),remaining);
    }
    private static OffsetDateTime utc(Instant t) { return t.atOffset(ZoneOffset.UTC); }
    private static QuizException failure(SQLException e) { return new QuizException(ErrorCode.DATABASE_ERROR,"Database operation failed",e); }
}
