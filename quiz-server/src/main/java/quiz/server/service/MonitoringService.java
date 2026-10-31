package quiz.server.service;

import quiz.common.dto.*;
import quiz.common.enums.*;
import quiz.server.db.TransactionManager;
import quiz.server.domain.*;
import quiz.server.exception.*;
import quiz.server.network.UserSession;
import quiz.server.repository.*;
import quiz.server.repository.jdbc.MonitoringDAO;
import java.time.*;
import java.util.*;

/** Authorization and penalties are enforced here, never by client-side controls. */
public final class MonitoringService {
    private final MonitoringDAO dao;
    private final AttemptRepository attempts;
    private final QuizRepository quizzes;
    private final AttemptService exams;
    private final TransactionManager transactions;
    private final Clock clock;

    public MonitoringService(MonitoringDAO dao,AttemptRepository attempts,QuizRepository quizzes,
                             AttemptService exams,TransactionManager transactions) {
        this(dao,attempts,quizzes,exams,transactions,Clock.systemUTC());
    }
    MonitoringService(MonitoringDAO dao,AttemptRepository attempts,QuizRepository quizzes,
                      AttemptService exams,TransactionManager transactions,Clock clock) {
        this.dao=dao;this.attempts=attempts;this.quizzes=quizzes;this.exams=exams;this.transactions=transactions;this.clock=clock;
    }
    public List<ExamSession> list(long examId,UserSession actor) {
        authorize(examId,actor);
        return dao.list(examId,clock.instant());
    }
    public ExamSession studentState(long id,UserSession actor) {
        if(actor.role()!=Role.STUDENT) throw new ForbiddenException("Chỉ dành cho sinh viên");
        return transactions.executeWithException(c->{
            Attempt a=attempts.findByIdForUpdate(c,id).orElseThrow(()->new NotFoundException("Lượt thi không tồn tại"));
            if(a.studentId()!=actor.userId()) throw new ForbiddenException("Lượt thi không thuộc về bạn");
            if(a.status()==AttemptStatus.IN_PROGRESS && !clock.instant().isBefore(a.deadlineAt()))
                exams.finishLocked(c,a,SubmitReason.TIMEOUT);
            return dao.find(c,id,clock.instant());
        });
    }
    public List<MonitoringPenalty> history(long id,UserSession actor) {
        Attempt a=attempts.findById(id).orElseThrow(()->new NotFoundException("Lượt thi không tồn tại"));
        authorize(a.quizId(),actor);
        return dao.penalties(id);
    }
    public ExamSession penalize(ApplyPenaltyDto p,UserSession actor) {
        validate(p);
        return transactions.executeWithException(c->{
            Attempt a=attempts.findByIdForUpdate(c,p.sessionId()).orElseThrow(()->new NotFoundException("Lượt thi không tồn tại"));
            authorize(a.quizId(),actor);
            Instant now=clock.instant();
            if(a.status()!=AttemptStatus.IN_PROGRESS || !now.isBefore(a.deadlineAt()))
                throw new ConflictException("Bài thi đã kết thúc hoặc hết giờ");
            ExamSession state=dao.find(c,a.id(),now);
            if(p.type()==PenaltyType.DEDUCT_SCORE && Math.round((state.deductedScore()+p.amount())*100)>1000)
                throw new ValidationException("Tổng điểm trừ không được vượt quá 10");
            if(p.type()==PenaltyType.DEDUCT_TIME && p.amount()>Integer.MAX_VALUE-state.deductedTimeSeconds())
                throw new ValidationException("Tổng thời gian trừ vượt giới hạn");
            dao.apply(c,p,actor.userId(),now);
            if(p.type()==PenaltyType.TERMINATE) exams.finishLocked(c,a,SubmitReason.ANTICHEAT);
            else if(p.type()==PenaltyType.DEDUCT_TIME) {
                Instant deadline=a.deadlineAt().minusSeconds((long)p.amount());
                if(!deadline.isAfter(now)) exams.finishLocked(c,a,SubmitReason.TIMEOUT);
                else dao.updateDeadline(c,a.id(),deadline);
            }
            return dao.find(c,a.id(),now);
        });
    }
    private void authorize(long examId,UserSession actor) {
        if(actor.role()!=Role.TEACHER && actor.role()!=Role.ADMIN) throw new ForbiddenException("Không có quyền giám sát");
        Quiz quiz=quizzes.findById(examId).orElseThrow(()->new NotFoundException("Bài kiểm tra không tồn tại"));
        if(actor.role()!=Role.ADMIN && quiz.createdBy()!=actor.userId()) throw new ForbiddenException("Bạn không quản lý bài thi này");
    }
    private static void validate(ApplyPenaltyDto p) {
        if(p==null||p.type()==null||p.sessionId()<=0||p.reason()==null||p.reason().isBlank()||p.reason().length()>255)
            throw new ValidationException("Chọn hành động và nhập lý do (tối đa 255 ký tự)");
        try { if(!UUID.fromString(p.operationId()).toString().equals(p.operationId())) throw new IllegalArgumentException(); }
        catch(RuntimeException e) { throw new ValidationException("Mã yêu cầu không hợp lệ"); }
        if(!Double.isFinite(p.amount()) || switch(p.type()) {
            case DEDUCT_SCORE -> p.amount()<=0||p.amount()>10||Math.abs(p.amount()*100-Math.rint(p.amount()*100))>0.000001;
            case DEDUCT_TIME -> p.amount()<1||p.amount()>Integer.MAX_VALUE||p.amount()!=Math.rint(p.amount());
            case TERMINATE -> p.amount()!=0;
        }) throw new ValidationException("Mức phạt không hợp lệ (điểm: tối đa 2 chữ số thập phân; thời gian: giây nguyên)");
    }
}
