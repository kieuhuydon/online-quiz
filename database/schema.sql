SET client_encoding TO 'UTF8';

-- ====================================================================
-- Online Quiz cho Trung tâm đào tạo (PostgreSQL DDL Schema)
-- ====================================================================

-- Initial schema only. Existing tables cause an error, never data deletion.
-- Run with psql -v ON_ERROR_STOP=1 -f database/schema.sql against a NEW database.
BEGIN;

-- 1. Users (Học viên, Giáo viên, Quản trị viên)
CREATE TABLE users (
    id            BIGSERIAL PRIMARY KEY,
    username      VARCHAR(50)  NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    full_name     VARCHAR(100) NOT NULL,
    email         VARCHAR(100) UNIQUE,
    role          VARCHAR(10)  NOT NULL CHECK (role IN ('STUDENT', 'TEACHER', 'ADMIN')),
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_by    BIGINT       REFERENCES users(id),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 2. Classes (Lớp học tại trung tâm)
CREATE TABLE classes (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(100) NOT NULL UNIQUE,
    description VARCHAR(255),
    teacher_id  BIGINT       NOT NULL REFERENCES users(id),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 3. Class Members / Enrollments (Danh sách học viên trong lớp)
CREATE TABLE class_members (
    class_id    BIGINT      NOT NULL REFERENCES classes(id) ON DELETE CASCADE,
    student_id  BIGINT      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    enrolled_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (class_id, student_id),
    CONSTRAINT uq_class_members_student UNIQUE (student_id)
);

-- 4. Questions (Ngân hàng câu hỏi trắc nghiệm)
CREATE TABLE questions (
    id             BIGSERIAL PRIMARY KEY,
    content        TEXT NOT NULL,
    option_a       TEXT NOT NULL,
    option_b       TEXT NOT NULL,
    option_c       TEXT NOT NULL,
    option_d       TEXT NOT NULL,
    correct_option CHAR(1),
    topic          VARCHAR(100) NOT NULL,
    difficulty     VARCHAR(10)  NOT NULL CHECK (difficulty IN ('EASY', 'MEDIUM', 'HARD')),
    question_type  VARCHAR(20)  NOT NULL DEFAULT 'MULTIPLE_CHOICE' CHECK (question_type IN ('MULTIPLE_CHOICE', 'TRUE_FALSE', 'FILL_BLANK', 'SHORT_ANSWER')),
    explanation    TEXT,
    answer_key     TEXT NOT NULL DEFAULT '',
    active         BOOLEAN NOT NULL DEFAULT TRUE,          -- Soft-delete (BR-05)
    created_by     BIGINT NOT NULL REFERENCES users(id),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT questions_correct_option_by_type CHECK (
        (question_type = 'MULTIPLE_CHOICE' AND correct_option IS NOT NULL AND correct_option IN ('A', 'B', 'C', 'D')) OR
        (question_type = 'TRUE_FALSE' AND correct_option IS NOT NULL AND correct_option IN ('A', 'B')) OR
        (question_type IN ('FILL_BLANK', 'SHORT_ANSWER') AND correct_option IS NULL)
    )
);

-- 5. Quizzes (Bài kiểm tra)
CREATE TABLE quizzes (
    id               BIGSERIAL PRIMARY KEY,
    title            VARCHAR(200) NOT NULL,
    description      VARCHAR(500),
    category         VARCHAR(100),
    duration_minutes INT NOT NULL CHECK (duration_minutes > 0),
    pass_score       NUMERIC(5,2) NOT NULL DEFAULT 50.00 CHECK (pass_score BETWEEN 0 AND 100),
    status           VARCHAR(10)  NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'OPEN', 'CLOSED')),
    pin              VARCHAR(10),                          -- Mã PIN tham gia thi (sinh khi OPEN)
    anticheat_policy VARCHAR(30)  NOT NULL DEFAULT 'WARN_THEN_AUTO_SUBMIT'
        CHECK (anticheat_policy IN ('LOG_ONLY', 'WARN_THEN_AUTO_SUBMIT')),
    max_violations   INT NOT NULL DEFAULT 3 CHECK (max_violations > 0),
    created_by       BIGINT NOT NULL REFERENCES users(id),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    opened_at        TIMESTAMPTZ,
    closed_at        TIMESTAMPTZ
);

-- 6. Quiz Questions (Câu hỏi thuộc bài kiểm tra)
CREATE TABLE quiz_questions (
    quiz_id     BIGINT NOT NULL REFERENCES quizzes(id) ON DELETE CASCADE,
    question_id BIGINT NOT NULL REFERENCES questions(id),
    position    INT NOT NULL CHECK (position > 0),
    UNIQUE (quiz_id, position),
    PRIMARY KEY (quiz_id, question_id)
);

-- 7. Quiz Assignments (Giao bài kiểm tra cho lớp)
CREATE TABLE quiz_assignments (
    id          BIGSERIAL PRIMARY KEY,
    quiz_id     BIGINT NOT NULL REFERENCES quizzes(id) ON DELETE CASCADE,
    class_id    BIGINT NOT NULL REFERENCES classes(id) ON DELETE CASCADE,
    assigned_by BIGINT REFERENCES users(id),
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (quiz_id, class_id)
);

-- 8. Attempts (Lượt làm bài của học viên)
CREATE TABLE attempts (
    id           BIGSERIAL PRIMARY KEY,
    quiz_id      BIGINT NOT NULL REFERENCES quizzes(id),
    student_id   BIGINT NOT NULL REFERENCES users(id),
    status       VARCHAR(12) NOT NULL DEFAULT 'IN_PROGRESS' CHECK (status IN ('IN_PROGRESS', 'SUBMITTED')),
    started_at   TIMESTAMPTZ NOT NULL,
    deadline_at  TIMESTAMPTZ NOT NULL CHECK (deadline_at > started_at),
    submitted_at TIMESTAMPTZ,
    UNIQUE (quiz_id, student_id)                           -- BR-09: Mỗi học viên chỉ có 1 attempt / quiz
);

-- 9. Attempt Answers (Câu trả lời từng câu trong Attempt)
CREATE TABLE attempt_answers (
    attempt_id      BIGINT NOT NULL REFERENCES attempts(id) ON DELETE CASCADE,
    question_id     BIGINT NOT NULL REFERENCES questions(id),
    selected_option CHAR(1) CHECK (selected_option IN ('A', 'B', 'C', 'D')), -- tương thích dữ liệu cũ
    answer_text     TEXT,
    is_correct      BOOLEAN,                                                -- Server chấm khi nộp bài
    answered_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (attempt_id, question_id)
);

-- 10. Results (Kết quả bài thi - 1:1 với Attempt)
CREATE TABLE results (
    attempt_id      BIGINT PRIMARY KEY REFERENCES attempts(id) ON DELETE CASCADE,
    score           NUMERIC(5,2) NOT NULL CHECK (score BETWEEN 0 AND 10),
    correct_count   INT NOT NULL CHECK (correct_count >= 0 AND correct_count <= total_questions),
    total_questions INT NOT NULL CHECK (total_questions > 0),
    passed          BOOLEAN NOT NULL,
    submit_reason   VARCHAR(10) NOT NULL CHECK (submit_reason IN ('MANUAL', 'TIMEOUT', 'ANTICHEAT')),
    graded_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 11. AntiCheat Events (Nhật ký vi phạm chống gian lận)
CREATE TABLE anticheat_events (
    id                    BIGSERIAL PRIMARY KEY,
    attempt_id            BIGINT NOT NULL REFERENCES attempts(id) ON DELETE CASCADE,
    event_type            VARCHAR(30) NOT NULL CHECK (event_type IN
        ('FOCUS_LOST', 'COPY_ATTEMPT', 'PASTE_ATTEMPT', 'CUT_ATTEMPT', 'CONTEXT_MENU_ATTEMPT', 'PROHIBITED_PROCESS')),
    detail                VARCHAR(255),
    client_time           TIMESTAMPTZ,
    server_time           TIMESTAMPTZ NOT NULL DEFAULT now(),
    violation_count_after INT NOT NULL CHECK (violation_count_after >= 0),
    action_taken          VARCHAR(20) NOT NULL CHECK (action_taken IN ('NONE', 'WARNED', 'AUTO_SUBMITTED'))
);

-- Indexes tối ưu truy vấn
CREATE INDEX idx_attempts_quiz      ON attempts(quiz_id);
CREATE INDEX idx_attempts_student   ON attempts(student_id);
CREATE INDEX idx_ac_events_attempt  ON anticheat_events(attempt_id);
CREATE INDEX idx_questions_filter   ON questions(topic, difficulty) WHERE active;
CREATE INDEX idx_class_members_user ON class_members(student_id);

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

COMMIT;
