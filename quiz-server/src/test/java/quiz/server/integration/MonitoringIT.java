package quiz.server.integration;

import org.junit.jupiter.api.*;
import org.postgresql.ds.PGSimpleDataSource;
import quiz.common.dto.ApplyPenaltyDto;
import quiz.common.enums.*;
import quiz.server.db.TransactionManager;
import quiz.server.exception.ConflictException;
import quiz.server.network.UserSession;
import quiz.server.repository.jdbc.*;
import quiz.server.service.*;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real PostgreSQL tests in an isolated schema of quizdb_test, using explicit test credentials. */
@Timeout(30)
class MonitoringIT {
    private PGSimpleDataSource source;
    private String schema;
    private long teacher,student,quiz,attempt;
    private MonitoringDAO dao;
    private AttemptService exams;
    private MonitoringService monitoring;
    private UserSession actor;
    @BeforeEach void setup() throws Exception {
        source=PostgreSqlTestConfig.load().dataSource();
        schema="monitor_test_"+UUID.randomUUID().toString().replace("-","");
        try(Connection c=source.getConnection()) {
            try(var s=c.prepareStatement("SELECT current_database(),current_user");var r=s.executeQuery()) {
                assertTrue(r.next());assertEquals("quizdb_test",r.getString(1));assertEquals("quiz_test",r.getString(2));
            }
            // Identifier is generated above, never supplied by the client.
            try(var s=c.prepareStatement("CREATE SCHEMA "+schema)) {s.execute();}
        }
        source.setCurrentSchema(schema);
        try(Connection c=source.getConnection()) {
            String sql=Files.readString(Path.of(System.getProperty("maven.multiModuleProjectDirectory",".."),"database/schema.sql"));
            try(var s=c.prepareStatement(sql)) {s.execute();}
            teacher=id(c,"INSERT INTO users(username,password_hash,full_name,role) VALUES('teacher','unused','Teacher','TEACHER') RETURNING id");
            student=id(c,"INSERT INTO users(username,password_hash,full_name,role) VALUES('student','unused','Student','STUDENT') RETURNING id");
            quiz=id(c,"INSERT INTO quizzes(title,duration_minutes,created_by,anticheat_policy) VALUES('Test',10,?,'LOG_ONLY') RETURNING id",teacher);
            long question=id(c,"INSERT INTO questions(content,option_a,option_b,option_c,option_d,correct_option,topic,difficulty,created_by) VALUES('Q','A','B','C','D','A','Java','EASY',?) RETURNING id",teacher);
            execute(c,"INSERT INTO quiz_questions VALUES(?,?,1)",quiz,question);
            attempt=id(c,"INSERT INTO attempts(quiz_id,student_id,started_at,deadline_at) VALUES(?,?,now(),now()+interval '10 minutes') RETURNING id",quiz,student);
            execute(c,"INSERT INTO attempt_answers(attempt_id,question_id,selected_option) VALUES(?,?,'A')",attempt,question);
        }
        dao=new MonitoringDAO(source);
        JdbcAttemptRepository attempts=new JdbcAttemptRepository(source);
        JdbcQuizRepository quizzes=new JdbcQuizRepository(source);
        TransactionManager tx=new TransactionManager(source);
        exams=new AttemptService(attempts,quizzes,new JdbcQuestionRepository(source),new JdbcUserRepository(source),tx,new GradingService(),dao);
        monitoring=new MonitoringService(dao,attempts,quizzes,exams,tx);
        tx.executeWithException(c->{dao.create(c,attempts.findById(attempt).orElseThrow(),"127.0.0.1");return null;});
        actor=new UserSession(teacher,Role.TEACHER,"teacher");
    }
    @AfterEach void cleanupOwnSchema() throws Exception {
        if(source==null||schema==null) return;
        assertTrue(schema.matches("monitor_test_[0-9a-f]{32}"));
        try(Connection c=source.getConnection();var s=c.prepareStatement("DROP SCHEMA IF EXISTS "+schema+" CASCADE")) {s.execute();}
    }
    @Test void duplicateConcurrentPenaltyOnlyAppliesOnce() throws Exception {
        ApplyPenaltyDto p=penalty(PenaltyType.DEDUCT_SCORE,2);
        List<Boolean> successes=race(()->{monitoring.penalize(p,actor);return true;},()->{monitoring.penalize(p,actor);return true;});
        assertEquals(1,successes.stream().filter(Boolean::booleanValue).count());
        assertEquals(2,monitoring.list(quiz,actor).getFirst().deductedScore());
        assertEquals(1,monitoring.history(attempt,actor).size());
        assertEquals(8,exams.submitAttempt(attempt,student,SubmitReason.MANUAL).score());
    }
    @Test void submissionRacingPenaltyKeepsResultConsistent() throws Exception {
        race(()->{exams.submitAttempt(attempt,student,SubmitReason.MANUAL);return true;},
            ()->{monitoring.penalize(penalty(PenaltyType.DEDUCT_SCORE,2),actor);return true;});
        var state=monitoring.list(quiz,actor).getFirst();
        assertEquals(10-state.deductedScore(),exams.getResult(attempt,student).score());
        assertEquals(MonitoringStatus.SUBMITTED,state.status());
    }
    @Test void forceStopAndTimeExhaustionPersistFinalResults() {
        monitoring.penalize(penalty(PenaltyType.TERMINATE,0),actor);
        var state=monitoring.list(quiz,actor).getFirst();
        assertTrue(state.forceStopped());assertEquals(MonitoringStatus.TERMINATED,state.status());
        assertEquals(SubmitReason.ANTICHEAT,exams.getResult(attempt,student).submitReason());
        assertThrows(ConflictException.class,()->exams.saveAnswer(attempt,1,'B',student));
    }
    @Test void timePenaltyCanFinishExamWithoutStudentConnection() {
        monitoring.penalize(penalty(PenaltyType.DEDUCT_TIME,3600),actor);
        assertEquals(SubmitReason.TIMEOUT,exams.getResult(attempt,student).submitReason());
        assertEquals(3600,monitoring.list(quiz,actor).getFirst().deductedTimeSeconds());
    }
    @Test void failureAfterAuditInsertRollsBackPenaltyAndAudit() {
        MonitoringDAO failing=new MonitoringDAO(source) {
            @Override public void apply(Connection c,ApplyPenaltyDto p,long actor,Instant now) throws SQLException {
                super.apply(c,p,actor,now);throw new SQLException("test rollback");
            }
        };
        MonitoringService service=new MonitoringService(failing,new JdbcAttemptRepository(source),new JdbcQuizRepository(source),exams,new TransactionManager(source));
        assertThrows(RuntimeException.class,()->service.penalize(penalty(PenaltyType.DEDUCT_SCORE,2),actor));
        assertEquals(0,monitoring.list(quiz,actor).getFirst().deductedScore());assertTrue(monitoring.history(attempt,actor).isEmpty());
    }
    @Test void migrationBackfillsExistingAttempts() throws Exception {
        try(Connection c=source.getConnection()) {
            try(var s=c.prepareStatement("DROP TABLE monitoring_penalties,exam_sessions")) {s.execute();}
            String sql=Files.readString(Path.of(System.getProperty("maven.multiModuleProjectDirectory",".."),"database/migrations/001_exam_monitoring.sql"));
            try(var s=c.prepareStatement(sql)) {s.execute();}
        }
        assertEquals(attempt,monitoring.list(quiz,actor).getFirst().sessionId());
        assertEquals(MonitoringStatus.IN_PROGRESS,monitoring.list(quiz,actor).getFirst().status());
    }
    private ApplyPenaltyDto penalty(PenaltyType type,double value) {return new ApplyPenaltyDto(UUID.randomUUID().toString(),attempt,type,value,"Test");}
    private static List<Boolean> race(Callable<Boolean> a,Callable<Boolean> b) throws Exception {
        try(var pool=Executors.newFixedThreadPool(2)) {
            CountDownLatch ready=new CountDownLatch(2),go=new CountDownLatch(1);
            List<Future<Boolean>> results=new ArrayList<>();
            for(var action:List.of(a,b)) results.add(pool.submit(()->{ready.countDown();go.await();try{return action.call();}catch(ConflictException e){return false;}}));
            assertTrue(ready.await(5,TimeUnit.SECONDS));go.countDown();
            return List.of(results.get(0).get(10,TimeUnit.SECONDS),results.get(1).get(10,TimeUnit.SECONDS));
        }
    }
    private static long id(Connection c,String sql,Object...args) throws SQLException {
        try(var s=c.prepareStatement(sql)) {for(int i=0;i<args.length;i++)s.setObject(i+1,args[i]);try(var r=s.executeQuery()){r.next();return r.getLong(1);}}
    }
    private static void execute(Connection c,String sql,Object...args) throws SQLException {
        try(var s=c.prepareStatement(sql)) {for(int i=0;i<args.length;i++)s.setObject(i+1,args[i]);s.executeUpdate();}
    }
}
