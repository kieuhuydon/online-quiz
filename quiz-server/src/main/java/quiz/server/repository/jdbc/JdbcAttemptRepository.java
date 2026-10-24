package quiz.server.repository.jdbc;

import quiz.common.enums.AttemptStatus;
import quiz.common.enums.SubmitReason;
import quiz.common.protocol.ErrorCode;
import quiz.server.domain.*;
import quiz.server.exception.ConflictException;
import quiz.server.exception.QuizException;
import quiz.server.repository.AttemptRepository;
import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

/** PostgreSQL persistence for attempts, answers and results. */
public final class JdbcAttemptRepository implements AttemptRepository {
    private final DataSource dataSource;
    public JdbcAttemptRepository(DataSource dataSource) { this.dataSource = Objects.requireNonNull(dataSource); }

    @Override public Attempt create(Connection c, Attempt a) {
        try (PreparedStatement s = c.prepareStatement("INSERT INTO attempts (quiz_id,student_id,status,started_at,deadline_at) VALUES (?,?,'IN_PROGRESS',?,?) RETURNING *")) {
            s.setLong(1,a.quizId()); s.setLong(2,a.studentId()); s.setObject(3,utc(a.startedAt())); s.setObject(4,utc(a.deadlineAt()));
            try (ResultSet r=s.executeQuery()) { r.next(); return mapAttempt(r); }
        } catch (SQLException e) { if ("23505".equals(e.getSQLState())) throw new ConflictException("Bạn đã có lượt làm bài cho quiz này",e); throw db(e); }
    }
    @Override public Optional<Attempt> findById(long id) { return queryAttempt("SELECT * FROM attempts WHERE id=?", id); }
    @Override public Optional<Attempt> findByIdForUpdate(Connection c,long id) {
        try (PreparedStatement s=c.prepareStatement("SELECT * FROM attempts WHERE id=? FOR UPDATE")) { s.setLong(1,id); try(ResultSet r=s.executeQuery()){ return r.next()?Optional.of(mapAttempt(r)):Optional.empty(); } } catch(SQLException e){throw db(e);}
    }
    @Override public Optional<Attempt> findByStudentAndQuiz(long studentId,long quizId) {
        try(Connection c=dataSource.getConnection(); PreparedStatement s=c.prepareStatement("SELECT * FROM attempts WHERE student_id=? AND quiz_id=?")) { s.setLong(1,studentId);s.setLong(2,quizId);try(ResultSet r=s.executeQuery()){return r.next()?Optional.of(mapAttempt(r)):Optional.empty();} } catch(SQLException e){throw db(e);}
    }
    @Override public List<Attempt> findByStudentId(long studentId) {
        try(Connection c=dataSource.getConnection();PreparedStatement s=c.prepareStatement("SELECT * FROM attempts WHERE student_id=? ORDER BY started_at DESC")){s.setLong(1,studentId);try(ResultSet r=s.executeQuery()){List<Attempt> out=new ArrayList<>();while(r.next())out.add(mapAttempt(r));return List.copyOf(out);}}catch(SQLException e){throw db(e);}
    }
    @Override public List<Attempt> findExpiredInProgress(Instant now) {
        try(Connection c=dataSource.getConnection();PreparedStatement s=c.prepareStatement("SELECT * FROM attempts WHERE status='IN_PROGRESS' AND deadline_at<=? ORDER BY deadline_at")){s.setObject(1,utc(now));try(ResultSet r=s.executeQuery()){List<Attempt> out=new ArrayList<>();while(r.next())out.add(mapAttempt(r));return List.copyOf(out);}}catch(SQLException e){throw db(e);}
    }
    @Override public void submit(Connection c,long id,Instant at,SubmitReason reason) {
        try(PreparedStatement s=c.prepareStatement("UPDATE attempts SET status='SUBMITTED',submitted_at=? WHERE id=? AND status='IN_PROGRESS'")){s.setObject(1,utc(at));s.setLong(2,id);if(s.executeUpdate()!=1)throw new ConflictException("Attempt đã được nộp");}catch(SQLException e){throw db(e);}
    }
    @Override public void saveAnswer(Connection c,AnswerRecord a) {
        try(PreparedStatement s=c.prepareStatement("INSERT INTO attempt_answers(attempt_id,question_id,selected_option,answered_at) VALUES(?,?,?,?) ON CONFLICT(attempt_id,question_id) DO UPDATE SET selected_option=EXCLUDED.selected_option,answered_at=EXCLUDED.answered_at")){s.setLong(1,a.attemptId());s.setLong(2,a.questionId());if(a.selectedOption()==null)s.setNull(3,Types.CHAR);else s.setString(3,a.selectedOption().toString());s.setObject(4,utc(a.savedAt()));s.executeUpdate();}catch(SQLException e){throw db(e);}
    }
    @Override public Map<Long,Character> getAnswers(Connection c,long id) {
        try(PreparedStatement s=c.prepareStatement("SELECT question_id,selected_option FROM attempt_answers WHERE attempt_id=?")){s.setLong(1,id);try(ResultSet r=s.executeQuery()){Map<Long,Character> out=new HashMap<>();while(r.next()){String v=r.getString(2);if(v!=null)out.put(r.getLong(1),v.charAt(0));}return Map.copyOf(out);}}catch(SQLException e){throw db(e);}
    }
    @Override public void saveResult(Connection c,ExamResult r) {
        try(PreparedStatement s=c.prepareStatement("INSERT INTO results(attempt_id,score,correct_count,total_questions,passed,submit_reason,graded_at) VALUES(?,?,?,?,?,?,?)")){s.setLong(1,r.attemptId());s.setBigDecimal(2,java.math.BigDecimal.valueOf(r.score()));s.setInt(3,r.correctCount());s.setInt(4,r.totalQuestions());s.setBoolean(5,r.passed());s.setString(6,r.submitReason().name());s.setObject(7,utc(r.submittedAt()));s.executeUpdate();}catch(SQLException e){if("23505".equals(e.getSQLState()))throw new ConflictException("Kết quả Attempt đã được lưu",e);throw db(e);}
    }
    @Override public Optional<ExamResult> findResult(long id) { return queryResults("WHERE a.id=?",id).stream().findFirst(); }
    @Override public List<ExamResult> findResultsByStudent(long id) { return queryResults("WHERE a.student_id=?",id); }
    private Optional<Attempt> queryAttempt(String sql,long id){try(Connection c=dataSource.getConnection();PreparedStatement s=c.prepareStatement(sql)){s.setLong(1,id);try(ResultSet r=s.executeQuery()){return r.next()?Optional.of(mapAttempt(r)):Optional.empty();}}catch(SQLException e){throw db(e);}}
    private List<ExamResult> queryResults(String where,long id){String sql="SELECT a.id attempt_id,a.quiz_id,a.student_id,u.full_name,q.title,r.score,r.correct_count,r.total_questions,r.passed,r.submit_reason,a.submitted_at FROM results r JOIN attempts a ON a.id=r.attempt_id JOIN users u ON u.id=a.student_id JOIN quizzes q ON q.id=a.quiz_id "+where+" ORDER BY a.submitted_at DESC";try(Connection c=dataSource.getConnection();PreparedStatement s=c.prepareStatement(sql)){s.setLong(1,id);try(ResultSet rs=s.executeQuery()){List<ExamResult> out=new ArrayList<>();while(rs.next())out.add(new ExamResult(rs.getLong("attempt_id"),rs.getLong("quiz_id"),rs.getLong("student_id"),rs.getString("full_name"),rs.getBigDecimal("score").doubleValue(),rs.getInt("correct_count"),rs.getInt("total_questions"),rs.getBoolean("passed"),SubmitReason.valueOf(rs.getString("submit_reason")),rs.getObject("submitted_at",OffsetDateTime.class).toInstant()));return List.copyOf(out);}}catch(SQLException e){throw db(e);}}
    private static Attempt mapAttempt(ResultSet r)throws SQLException{OffsetDateTime submitted=r.getObject("submitted_at",OffsetDateTime.class);return new Attempt(r.getLong("id"),r.getLong("quiz_id"),r.getLong("student_id"),AttemptStatus.valueOf(r.getString("status")),r.getObject("started_at",OffsetDateTime.class).toInstant(),r.getObject("deadline_at",OffsetDateTime.class).toInstant(),submitted==null?null:submitted.toInstant(),null);}
    private static OffsetDateTime utc(Instant instant){return OffsetDateTime.ofInstant(instant,ZoneOffset.UTC);}
    private static RuntimeException db(SQLException e){return new QuizException(ErrorCode.DATABASE_ERROR,"Database operation failed",e);}
}
