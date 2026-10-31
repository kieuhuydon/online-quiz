package quiz.server.service;

import org.junit.jupiter.api.*;
import quiz.common.dto.*;
import quiz.common.enums.*;
import quiz.server.db.TransactionManager;
import quiz.server.domain.*;
import quiz.server.exception.*;
import quiz.server.network.UserSession;
import quiz.server.repository.*;
import quiz.server.repository.jdbc.MonitoringDAO;
import java.sql.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class MonitoringServiceTest {
    private final Instant now=Instant.parse("2026-10-31T00:00:00Z");
    private final UserSession teacher=new UserSession(1,Role.TEACHER,"teacher");
    private final Attempt attempt=new Attempt(10,20,30,AttemptStatus.IN_PROGRESS,now.minusSeconds(60),now.plusSeconds(600),null,null);
    private MonitoringDAO dao;private AttemptRepository attempts;private QuizRepository quizzes;
    private AttemptService exams;private MonitoringService service;private Connection connection;
    @BeforeEach void setup() throws Exception {
        dao=mock(MonitoringDAO.class);attempts=mock(AttemptRepository.class);quizzes=mock(QuizRepository.class);
        exams=mock(AttemptService.class);connection=mock(Connection.class);
        TransactionManager tx=mock(TransactionManager.class);
        when(tx.executeWithException(any())).thenAnswer(i->((TransactionManager.TransactionCallback<?>)i.getArgument(0)).doInTransaction(connection));
        when(attempts.findByIdForUpdate(connection,10)).thenReturn(Optional.of(attempt));
        when(attempts.findById(10)).thenReturn(Optional.of(attempt));
        when(quizzes.findById(20)).thenReturn(Optional.of(new Quiz(20,"Test",null,null,10,50,QuizStatus.OPEN,"123",
            AntiCheatPolicyType.LOG_ONLY,3,1,now,null,null)));
        when(dao.find(connection,10,now)).thenReturn(state(0,0));
        service=new MonitoringService(dao,attempts,quizzes,exams,tx,Clock.fixed(now,ZoneOffset.UTC));
    }
    private ExamSession state(double score,int seconds) {
        return new ExamSession(10,20,30,"Student","127.0.0.1",MonitoringStatus.IN_PROGRESS,0,null,score,seconds,false,now,600);
    }
    private ApplyPenaltyDto penalty(PenaltyType type,double amount) {
        return new ApplyPenaltyDto(UUID.randomUUID().toString(),10,type,amount,"Test reason");
    }
    @Test void studentAndOtherTeacherCannotReadOrPenalize() throws Exception {
        for(UserSession actor:List.of(new UserSession(30,Role.STUDENT,"student"),new UserSession(2,Role.TEACHER,"other"))) {
            assertThrows(ForbiddenException.class,()->service.list(20,actor));
            assertThrows(ForbiddenException.class,()->service.penalize(penalty(PenaltyType.TERMINATE,0),actor));
            assertThrows(ForbiddenException.class,()->service.history(10,actor));
        }
        verify(dao,never()).apply(any(),any(),anyLong(),any());
    }
    @Test void cannotReadAnotherStudentsState() {
        assertThrows(ForbiddenException.class,()->service.studentState(10,new UserSession(31,Role.STUDENT,"other")));
    }
    @Test void reducingTimeChangesServerDeadline() throws Exception {
        service.penalize(penalty(PenaltyType.DEDUCT_TIME,300),teacher);
        verify(dao).updateDeadline(connection,10,now.plusSeconds(300));
        verify(exams,never()).finishLocked(any(),any(),any());
    }
    @Test void reducingAllRemainingTimeGradesInSameTransaction() throws Exception {
        service.penalize(penalty(PenaltyType.DEDUCT_TIME,600),teacher);
        verify(exams).finishLocked(connection,attempt,SubmitReason.TIMEOUT);
        verify(dao,never()).updateDeadline(any(),anyLong(),any());
    }
    @Test void forceStopGradesBeforeCommitting() throws Exception {
        ApplyPenaltyDto p=penalty(PenaltyType.TERMINATE,0);
        service.penalize(p,teacher);
        var order=inOrder(dao,exams);
        order.verify(dao).apply(connection,p,1,now);
        order.verify(exams).finishLocked(connection,attempt,SubmitReason.ANTICHEAT);
    }
    @Test void rejectsInvalidAmountsReasonsAndOperationIds() {
        for(double amount:new double[]{Double.NaN,Double.POSITIVE_INFINITY,-1,0,11,0.001})
            assertThrows(ValidationException.class,()->service.penalize(penalty(PenaltyType.DEDUCT_SCORE,amount),teacher));
        assertThrows(ValidationException.class,()->service.penalize(penalty(PenaltyType.DEDUCT_TIME,0.5),teacher));
        assertThrows(ValidationException.class,()->service.penalize(new ApplyPenaltyDto("bad",10,PenaltyType.TERMINATE,0,"reason"),teacher));
        assertThrows(ValidationException.class,()->service.penalize(new ApplyPenaltyDto(UUID.randomUUID().toString(),10,PenaltyType.TERMINATE,0," "),teacher));
    }
    @Test void rejectsCumulativeOverflow() throws Exception {
        when(dao.find(connection,10,now)).thenReturn(state(9,Integer.MAX_VALUE));
        assertThrows(ValidationException.class,()->service.penalize(penalty(PenaltyType.DEDUCT_SCORE,2),teacher));
        assertThrows(ValidationException.class,()->service.penalize(penalty(PenaltyType.DEDUCT_TIME,1),teacher));
    }
    @Test void submittedAttemptCannotBePenalized() {
        when(attempts.findByIdForUpdate(connection,10)).thenReturn(Optional.of(new Attempt(10,20,30,AttemptStatus.SUBMITTED,
            now.minusSeconds(60),now.plusSeconds(600),now,SubmitReason.MANUAL)));
        assertThrows(ConflictException.class,()->service.penalize(penalty(PenaltyType.TERMINATE,0),teacher));
    }
    @Test void duplicateRequestOrDatabaseFailureDoesNotReachGrading() throws Exception {
        doThrow(new ConflictException("duplicate")).when(dao).apply(any(),any(),anyLong(),any());
        assertThrows(ConflictException.class,()->service.penalize(penalty(PenaltyType.TERMINATE,0),teacher));
        verify(exams,never()).finishLocked(any(),any(),any());
    }
    @Test void finalScoreUsesDeductionAndUpdatesPassStatus() throws Exception {
        QuestionRepository questions=mock(QuestionRepository.class);UserRepository users=mock(UserRepository.class);
        GradingService grading=mock(GradingService.class);
        when(users.findById(30)).thenReturn(Optional.of(ServiceTestUsers.user(30,Role.STUDENT,true)));
        when(grading.grade(any(),any(),any(),any(),any(),any(),any())).thenReturn(
            new ExamResult(10,20,30,"Student",8,4,5,true,SubmitReason.MANUAL,now));
        AttemptService actual=new AttemptService(attempts,quizzes,questions,users,mock(TransactionManager.class),grading,dao);
        when(dao.find(eq(connection),eq(10L),any())).thenReturn(state(4,0));
        ExamResult result=actual.finishLocked(connection,attempt,SubmitReason.MANUAL);
        assertEquals(4,result.score());assertFalse(result.passed());assertEquals(4,result.correctCount());
        when(dao.find(eq(connection),eq(10L),any())).thenReturn(state(10,0));
        assertEquals(0,actual.finishLocked(connection,attempt,SubmitReason.MANUAL).score());
    }
}
