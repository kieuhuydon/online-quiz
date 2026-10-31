package quiz.client.anticheat;

import javafx.application.Platform;
import quiz.client.api.QuizApi;
import quiz.common.dto.ExamSession;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Polls server decisions. Existing ExamAntiCheatController owns focus/process detection. */
public final class StudentAntiCheatService implements AutoCloseable {
    private final ScheduledExecutorService executor=Executors.newSingleThreadScheduledExecutor(
        Thread.ofPlatform().daemon().name("student-monitor-sync").factory());
    private final AtomicBoolean pending=new AtomicBoolean();
    private volatile boolean closed;
    private boolean started;

    public synchronized void start(QuizApi api,long attemptId,Consumer<ExamSession> update,Consumer<Throwable> error) {
        if(started||closed) return;
        started=true;
        executor.scheduleWithFixedDelay(()->{
            if(closed||!pending.compareAndSet(false,true)) return;
            long start=System.nanoTime();
            api.getExamSession(attemptId).whenComplete((state,failure)->{
                Platform.runLater(()->{
                    try {
                        if(closed) return;
                        if(failure!=null) error.accept(failure);
                        else {
                            // Conservatively account for response transit and FX queue delay.
                            long elapsed=TimeUnit.NANOSECONDS.toSeconds(System.nanoTime()-start);
                            update.accept(new ExamSession(state.sessionId(),state.examId(),state.studentId(),state.studentName(),
                                state.ipAddress(),state.status(),state.violationCount(),state.violationDetails(),state.deductedScore(),
                                state.forceStopped(),state.lastUpdated(),Math.max(0,state.remainingSeconds()-elapsed)));
                        }
                    } finally { pending.set(false); }
                });
            });
        },0,api.monitoringIntervalMs(),TimeUnit.MILLISECONDS);
    }
    @Override public void close() { closed=true;executor.shutdownNow(); }
}
