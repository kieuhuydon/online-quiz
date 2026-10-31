-- Existing database only. Run once with psql -v ON_ERROR_STOP=1 -f this-file.sql.
BEGIN;
CREATE TABLE exam_sessions (
    session_id BIGINT PRIMARY KEY REFERENCES attempts(id) ON DELETE CASCADE,
    exam_id BIGINT NOT NULL REFERENCES quizzes(id),
    student_id BIGINT NOT NULL REFERENCES users(id),
    ip_address VARCHAR(45),
    status VARCHAR(16) NOT NULL DEFAULT 'IN_PROGRESS'
        CHECK (status IN ('IN_PROGRESS','WARNING','CHEATING','SUBMITTED','TERMINATED')),
    violation_count INT NOT NULL DEFAULT 0 CHECK (violation_count >= 0),
    violation_details TEXT,
    deducted_score DOUBLE PRECISION NOT NULL DEFAULT 0 CHECK (deducted_score BETWEEN 0 AND 10),
    deducted_time_seconds INT NOT NULL DEFAULT 0 CHECK (deducted_time_seconds >= 0),
    is_force_stopped BOOLEAN NOT NULL DEFAULT FALSE,
    last_updated TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_exam_sessions_exam ON exam_sessions(exam_id);
CREATE TABLE monitoring_penalties (
    operation_id VARCHAR(36) PRIMARY KEY,
    session_id BIGINT NOT NULL REFERENCES exam_sessions(session_id),
    teacher_id BIGINT NOT NULL REFERENCES users(id),
    penalty_type VARCHAR(16) NOT NULL CHECK (penalty_type IN ('DEDUCT_SCORE','DEDUCT_TIME','TERMINATE')),
    amount DOUBLE PRECISION NOT NULL CHECK (amount >= 0 AND amount < 'Infinity'::float8),
    reason VARCHAR(255) NOT NULL,
    applied_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_monitoring_penalties_session ON monitoring_penalties(session_id);
-- Preserve existing attempts and event history.
INSERT INTO exam_sessions(session_id,exam_id,student_id,status,violation_count,violation_details)
SELECT a.id,a.quiz_id,a.student_id,
    CASE WHEN a.status='SUBMITTED' THEN 'SUBMITTED'
         WHEN EXISTS(SELECT 1 FROM anticheat_events e WHERE e.attempt_id=a.id AND e.event_type='PROHIBITED_PROCESS') THEN 'CHEATING'
         WHEN EXISTS(SELECT 1 FROM anticheat_events e WHERE e.attempt_id=a.id) THEN 'WARNING'
         ELSE 'IN_PROGRESS' END,
    (SELECT count(*) FROM anticheat_events e WHERE e.attempt_id=a.id),
    (SELECT e.event_type || ': ' || COALESCE(e.detail,'') FROM anticheat_events e WHERE e.attempt_id=a.id ORDER BY e.server_time DESC,e.id DESC LIMIT 1)
FROM attempts a;
COMMIT;
