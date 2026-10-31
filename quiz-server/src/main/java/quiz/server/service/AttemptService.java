package quiz.server.service;

import quiz.common.dto.*;
import quiz.common.enums.*;
import quiz.server.db.TransactionManager;
import quiz.server.domain.*;
import quiz.server.exception.*;
import quiz.server.repository.*;
import quiz.server.repository.jdbc.MonitoringDAO;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.*;
import java.util.*;

public final class AttemptService {
    private final AttemptRepository attempts; private final QuizRepository quizzes; private final QuestionRepository questions;
    private final UserRepository users; private final TransactionManager transactions; private final GradingService grading;
    private final Clock clock;
    private MonitoringDAO monitoring;
    public AttemptService(AttemptRepository attempts, QuizRepository quizzes, QuestionRepository questions,
                          UserRepository users, TransactionManager transactions, GradingService grading, MonitoringDAO monitoring) {
        this(attempts,quizzes,questions,users,transactions,grading);
        this.monitoring=Objects.requireNonNull(monitoring);
    }
    public AttemptService(AttemptRepository attempts, QuizRepository quizzes, QuestionRepository questions,
                          UserRepository users, TransactionManager transactions, GradingService grading) {
        this(attempts,quizzes,questions,users,transactions,grading,Clock.systemUTC());
    }
    AttemptService(AttemptRepository attempts,QuizRepository quizzes,QuestionRepository questions,UserRepository users,
                   TransactionManager transactions,GradingService grading,Clock clock) {
        this.attempts=Objects.requireNonNull(attempts);this.quizzes=Objects.requireNonNull(quizzes);
        this.questions=Objects.requireNonNull(questions);this.users=Objects.requireNonNull(users);
        this.transactions=Objects.requireNonNull(transactions);this.grading=Objects.requireNonNull(grading);this.clock=Objects.requireNonNull(clock);
    }
    public AttemptStateDto startAttempt(long quizId,String pin,long studentId) {
        return startAttempt(quizId,pin,studentId,null);
    }
    public AttemptStateDto startAttempt(long quizId,String pin,long studentId,String ipAddress) {
        if(ipAddress!=null && (ipAddress.length()>45 || !ipAddress.matches("[0-9a-fA-F:.%]+")))
            throw new ValidationException("Địa chỉ IP không hợp lệ");
        Quiz quiz=quizzes.findById(quizId).orElseThrow(()->new NotFoundException("Bài kiểm tra không tồn tại"));
        Optional<Attempt> existing=attempts.findByStudentAndQuiz(studentId,quizId);
        if(existing.isPresent()) {
            Attempt a=existing.get();
            if(a.status()==AttemptStatus.SUBMITTED)throw new ConflictException("Bạn đã nộp bài kiểm tra này trước đó. Mỗi học viên chỉ được làm bài này một lần.");
            if(!clock.instant().isBefore(a.deadlineAt())) {
                submitAttempt(a.id(),studentId,SubmitReason.TIMEOUT);
                throw new ConflictException("Bài kiểm tra đã hết giờ và được tự động nộp");
            }
            if(quiz.status()==QuizStatus.OPEN && !Objects.equals(quiz.pin(),pin))throw new ForbiddenException("Mã PIN không chính xác");
            if(monitoring!=null) transactions.executeWithException(c->{monitoring.create(c,a,ipAddress);return null;});
            return state(a);
        }
        if(quiz.status()!=QuizStatus.OPEN)throw new ConflictException("Bài kiểm tra hiện không mở");
        if(!quizzes.findAssignedQuizzesForStudent(studentId).stream().anyMatch(q->q.id()==quizId))throw new ForbiddenException("Bài kiểm tra chưa được giao cho bạn");
        if(!Objects.equals(quiz.pin(),pin))throw new ForbiddenException("Mã PIN không chính xác");
        Instant start=clock.instant();
        Attempt created=transactions.executeWithException(c->{
            Attempt a=attempts.create(c,new Attempt(0,quizId,studentId,AttemptStatus.IN_PROGRESS,start,start.plusSeconds(quiz.durationMinutes()*60L),null,null));
            if(monitoring!=null) monitoring.create(c,a,ipAddress);
            return a;
        });
        return state(created);
    }
    public void saveAnswer(long attemptId,long questionId,String answer,long studentId) {
        if(answer != null && answer.strip().length() > 1000)throw new ValidationException("Đáp án quá dài");
        transactions.executeWithException(c->{
            Attempt a=attempts.findByIdForUpdate(c,attemptId).orElseThrow(()->new NotFoundException("Attempt không tồn tại"));
            authorize(a,studentId); if(a.status()!=AttemptStatus.IN_PROGRESS)throw new ConflictException("Bài làm đã được nộp");
            if(!clock.instant().isBefore(a.deadlineAt()))throw new ConflictException("Đã hết thời gian làm bài");
            List<Question> qs=questions.findQuestionsByQuizId(a.quizId());
            if(qs.stream().noneMatch(q->q.id()==questionId))throw new ValidationException("Câu hỏi không thuộc bài kiểm tra");
            attempts.saveAnswer(c,new AnswerRecord(attemptId,questionId,answer == null ? null : answer.strip(),clock.instant())); return null;
        });
    }
    public void saveAnswer(long attemptId,long questionId,Character answer,long studentId) {
        saveAnswer(attemptId, questionId, answer == null ? null : answer.toString(), studentId);
    }
    public ResultDto submitAttempt(long attemptId,long studentId,SubmitReason requestedReason) {
        ExamResult result=transactions.executeWithException(c->{
            Attempt a=attempts.findByIdForUpdate(c,attemptId).orElseThrow(()->new NotFoundException("Attempt không tồn tại"));
            authorize(a,studentId);
            Optional<ExamResult> existing=attempts.findResult(attemptId);
            if(existing.isPresent())return existing.get();
            if(a.status()!=AttemptStatus.IN_PROGRESS)throw new ConflictException("Attempt đã được nộp");
            Instant now=clock.instant(); SubmitReason reason=requestedReason==SubmitReason.ANTICHEAT?SubmitReason.ANTICHEAT:SubmitReason.MANUAL; if(!now.isBefore(a.deadlineAt()))reason=SubmitReason.TIMEOUT;
            Quiz quiz=quizzes.findById(a.quizId()).orElseThrow(()->new NotFoundException("Bài kiểm tra không tồn tại"));
            User user=users.findById(studentId).orElseThrow(()->new NotFoundException("Tài khoản không tồn tại"));
            List<Question> qs=questions.findQuestionsByQuizId(a.quizId());
            ExamResult graded=grading.grade(a,attempts.getTextAnswers(c,attemptId),qs,quiz,user.fullName(),reason,now);
            attempts.submit(c,attemptId,now,reason);attempts.saveResult(c,graded);return graded;
        });
        return resultDto(result);
    }
    /** Caller holds the attempt row lock. Grading, penalty and status commit together. */
    ExamResult finishLocked(Connection c,Attempt a,SubmitReason reason) throws SQLException {
        Instant now=clock.instant();
        Quiz quiz=quizzes.findById(a.quizId()).orElseThrow(()->new NotFoundException("Bài kiểm tra không tồn tại"));
        User user=users.findById(a.studentId()).orElseThrow(()->new NotFoundException("Tài khoản không tồn tại"));
        ExamResult raw=grading.grade(a,attempts.getAnswers(c,a.id()),questions.findQuestionsByQuizId(a.quizId()),quiz,user.fullName(),reason,now);
        double deduction=monitoring==null?0:monitoring.find(c,a.id(),now).deductedScore();
        double score=Math.max(0,Math.round((raw.score()-deduction)*100.0)/100.0);
        ExamResult result=new ExamResult(raw.attemptId(),raw.quizId(),raw.studentId(),raw.studentName(),score,
            raw.correctCount(),raw.totalQuestions(),score*10>=quiz.passScore(),reason,now);
        attempts.submit(c,a.id(),now,reason);
        attempts.saveResult(c,result);
        if(monitoring!=null) monitoring.submitted(c,a.id(),now);
        return result;
    }
    public AttemptStateDto getAttemptState(long id,long studentId){Attempt a=attempts.findById(id).orElseThrow(()->new NotFoundException("Attempt không tồn tại"));authorize(a,studentId);if(a.status()==AttemptStatus.SUBMITTED)throw new ConflictException("Attempt đã được nộp");if(!clock.instant().isBefore(a.deadlineAt())){submitAttempt(id,studentId,SubmitReason.TIMEOUT);throw new ConflictException("Bài kiểm tra đã hết giờ và được tự động nộp");}return state(a);}
    public ResultDto getResult(long id,long studentId){ExamResult r=attempts.findResult(id).orElseThrow(()->new NotFoundException("Kết quả chưa có"));if(r.studentId()!=studentId)throw new ForbiddenException("Bạn không có quyền xem kết quả này");return resultDto(r);}
    public List<ResultDto> listHistory(long studentId){return attempts.findResultsByStudent(studentId).stream().map(this::resultDto).toList();}
    public void finalizeExpiredAttempts(){for(Attempt a:attempts.findExpiredInProgress(clock.instant())){try{submitAttempt(a.id(),a.studentId(),SubmitReason.TIMEOUT);}catch(ConflictException ignored){/* Another request finalized it under the attempt lock. */}}}
    private AttemptStateDto state(Attempt a){Quiz quiz=quizzes.findById(a.quizId()).orElseThrow(()->new NotFoundException("Bài kiểm tra không tồn tại"));List<Question> qs=questions.findQuestionsByQuizId(a.quizId());List<StudentQuestionDto> ds=new ArrayList<>();for(int i=0;i<qs.size();i++){Question q=qs.get(i);int blanks=q.content().split("___",-1).length-1;ds.add(new StudentQuestionDto(q.id(),i+1,q.content(),q.optionA(),q.optionB(),q.optionC(),q.optionD(),q.topic(),q.difficulty(),q.questionType(),blanks));}Map<Long,String> saved=transactions.executeInTransaction(c->attempts.getTextAnswers(c,a.id()));long millis=Duration.between(clock.instant(),a.deadlineAt()).toMillis();long remain=millis<=0?0:(millis+999)/1000;return new AttemptStateDto(a.id(),a.quizId(),quiz.title(),remain,List.copyOf(ds),saved);}
    private ResultDto resultDto(ExamResult r){String title=quizzes.findById(r.quizId()).map(Quiz::title).orElse("Bài kiểm tra");Attempt a=attempts.findById(r.attemptId()).orElse(null);List<QuestionReviewDto> review=new ArrayList<>();if(a!=null){Map<Long,String> answers=transactions.executeInTransaction(c->attempts.getTextAnswers(c,a.id()));for(Question q:questions.findQuestionsByQuizId(r.quizId())){String submitted=answers.getOrDefault(q.id(),"");String key=q.answerKey().isBlank()?String.valueOf(q.correctOption()):q.answerKey();boolean correct=submitted!=null&&grading.matchesAnswer(q,submitted);review.add(new QuestionReviewDto(q.id(),q.content(),submitted,key,q.explanation(),correct));}}return new ResultDto(r.attemptId(),r.quizId(),title,r.studentName(),r.score(),r.correctCount(),r.totalQuestions(),r.passed(),r.submitReason(),r.submittedAt(),List.copyOf(review));}
    private static void authorize(Attempt a,long student){if(a.studentId()!=student)throw new ForbiddenException("Attempt không thuộc về bạn");}
}
