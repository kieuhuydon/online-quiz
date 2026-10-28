SET client_encoding TO 'UTF8';

-- Online Quiz: demo fixtures for Admin, Teacher and Student screens.
-- Run on an empty development schema only:
-- psql -v ON_ERROR_STOP=1 -c "SET quiz.allow_demo_seed = 'on'" -f database/seed.sql
-- Accounts: admin, teacher1, teacher2, student01..student50. Password: password123.
-- student50 is disabled. student46..student49 are active and have no class.
-- No fixed IDs, reset, deletion or upsert. Any failure rolls back all inserted rows.
BEGIN;
LOCK TABLE users, classes, class_members, questions, quizzes, quiz_questions,
    quiz_assignments, attempts, attempt_answers, results, anticheat_events
    IN SHARE ROW EXCLUSIVE MODE;

DO $$
DECLARE
    v_table text;
    v_has_data boolean;
    v_hash constant text := '$2a$10$RMj7Oqje6a.XQorzyUTXuuXnV/EKY/.yYf4YaxIMOz67D/tFVZ.0y';
    v_admin bigint;
    v_teacher1 bigint;
    v_teacher2 bigint;
    v_class_b1 bigint;
    v_class_b2 bigint;
    v_class_toeic bigint;
    v_quiz record;
    v_sample record;
    v_student bigint;
    v_attempt bigint;
    v_total integer;
    v_started timestamptz;
    v_deadline timestamptz;
    v_submitted timestamptz;
BEGIN
    IF current_setting('quiz.allow_demo_seed', true) IS DISTINCT FROM 'on' THEN
        RAISE EXCEPTION 'Demo seed requires quiz.allow_demo_seed=on';
    END IF;
    FOREACH v_table IN ARRAY ARRAY['users', 'classes', 'class_members', 'questions',
        'quizzes', 'quiz_questions', 'quiz_assignments', 'attempts', 'attempt_answers',
        'results', 'anticheat_events'] LOOP
        EXECUTE format('SELECT EXISTS (SELECT 1 FROM %I)', v_table) INTO v_has_data;
        IF v_has_data THEN
            RAISE EXCEPTION 'Demo seed requires empty tables; % already contains data', v_table;
        END IF;
    END LOOP;

    -- 1. Accounts. All foreign keys below use returned IDs or unique usernames.
    INSERT INTO users (username, password_hash, full_name, email, role)
    VALUES ('admin', v_hash, 'Quản trị viên', 'admin@quiz.center', 'ADMIN')
    RETURNING id INTO v_admin;

    INSERT INTO users (username, password_hash, full_name, email, role, created_by)
    VALUES ('teacher1', v_hash, 'Nguyễn Văn An', 'teacher1@quiz.center', 'TEACHER', v_admin)
    RETURNING id INTO v_teacher1;
    INSERT INTO users (username, password_hash, full_name, email, role, created_by)
    VALUES ('teacher2', v_hash, 'Trần Thị Bình', 'teacher2@quiz.center', 'TEACHER', v_admin)
    RETURNING id INTO v_teacher2;

    INSERT INTO users (username, password_hash, full_name, email, role, active, created_by)
    SELECT 'student' || lpad(s::text, 2, '0'), v_hash,
        'Học viên ' || lpad(s::text, 2, '0'),
        CASE WHEN s % 10 = 0 THEN NULL ELSE 'student' || lpad(s::text, 2, '0') || '@quiz.center' END,
        'STUDENT', s <> 50, v_admin
    FROM generate_series(1, 50) AS s;

    -- 2. One class at most per student; five students remain unenrolled.
    INSERT INTO classes (name, description, teacher_id)
    VALUES ('ENG-B1-01', 'Tiếng Anh B1 - Lớp buổi sáng', v_teacher1)
    RETURNING id INTO v_class_b1;
    INSERT INTO classes (name, description, teacher_id)
    VALUES ('ENG-B2-01', 'Tiếng Anh B2 - Lớp buổi tối', v_teacher2)
    RETURNING id INTO v_class_b2;
    INSERT INTO classes (name, description, teacher_id)
    VALUES ('TOEIC-650', 'Luyện thi TOEIC mục tiêu 650+', v_teacher1)
    RETURNING id INTO v_class_toeic;

    INSERT INTO class_members (class_id, student_id)
    SELECT CASE WHEN s <= 20 THEN v_class_b1 WHEN s <= 35 THEN v_class_b2 ELSE v_class_toeic END,
        u.id
    FROM generate_series(1, 45) AS s
    JOIN users u ON u.username = 'student' || lpad(s::text, 2, '0');

    -- 3. Shared question bank: ten questions for each topic, with mixed difficulties.
    INSERT INTO questions (content, option_a, option_b, option_c, option_d,
        correct_option, topic, difficulty, active, created_by)
    VALUES
('She _____ to school every day by bus.', 'go', 'goes', 'going', 'gone', 'B', 'Grammar', 'EASY', TRUE, v_teacher1),
('If it _____ tomorrow, we will cancel the picnic.', 'rains', 'rain', 'rained', 'will rain', 'A', 'Grammar', 'EASY', TRUE, v_teacher1),
('They have been living here _____ 2018.', 'for', 'since', 'in', 'at', 'B', 'Grammar', 'EASY', TRUE, v_teacher1),
('Neither the teacher nor the students _____ present yesterday.', 'was', 'were', 'is', 'are', 'B', 'Grammar', 'MEDIUM', TRUE, v_teacher1),
('I wish I _____ more time to finish the exam.', 'have', 'had', 'will have', 'having', 'B', 'Grammar', 'MEDIUM', TRUE, v_teacher1),
('By the time we arrived, the movie _____.', 'already started', 'has already started', 'had already started', 'was starting', 'C', 'Grammar', 'MEDIUM', TRUE, v_teacher1),
('Hardly _____ when the bell rang.', 'he entered the room', 'had he entered the room', 'did he enter the room', 'he had entered the room', 'B', 'Grammar', 'HARD', TRUE, v_teacher1),
('Not only _____ late, but he also forgot his homework.', 'he was', 'was he', 'did he', 'he did', 'B', 'Grammar', 'HARD', TRUE, v_teacher1),
('It is essential that she _____ the meeting on time.', 'attends', 'attend', 'attended', 'will attend', 'B', 'Grammar', 'HARD', TRUE, v_teacher1),
('The report _____ by the end of next week.', 'will finish', 'is finishing', 'will be finished', 'has finished', 'C', 'Grammar', 'MEDIUM', TRUE, v_teacher1),
('The weather was so _____ that we stayed indoors all day.', 'pleasant', 'awful', 'gorgeous', 'warm', 'B', 'Vocabulary', 'EASY', TRUE, v_teacher1),
('Could you please lend me your pen? Mine has run _____ ink.', 'into', 'out of', 'over', 'away', 'B', 'Vocabulary', 'EASY', TRUE, v_teacher1),
('He gave an accurate _____ of the suspect to the police.', 'description', 'imagination', 'connection', 'impression', 'A', 'Vocabulary', 'EASY', TRUE, v_teacher1),
('The new software had a profound _____ on team productivity.', 'affect', 'effect', 'result', 'reason', 'B', 'Vocabulary', 'MEDIUM', TRUE, v_teacher1),
('We need to find an _____ solution to reduce carbon emissions.', 'innovative', 'inaccurate', 'ordinary', 'inactive', 'A', 'Vocabulary', 'MEDIUM', TRUE, v_teacher1),
('Her dedication and hard work were finally _____ with a promotion.', 'repaid', 'rewarded', 'returned', 'refunded', 'B', 'Vocabulary', 'MEDIUM', TRUE, v_teacher1),
('The CEO delivered an exceptionally _____ speech that inspired everyone.', 'eloquent', 'awkward', 'abrupt', 'ambiguous', 'A', 'Vocabulary', 'HARD', TRUE, v_teacher1),
('The scientist discovered a previously _____ species in the deep ocean.', 'unprecedented', 'obscure', 'lucid', 'ubiquitous', 'B', 'Vocabulary', 'HARD', TRUE, v_teacher1),
('His arguments were so _____ that nobody could dispute them.', 'cogent', 'feeble', 'flawed', 'redundant', 'A', 'Vocabulary', 'HARD', TRUE, v_teacher1),
('The contract contains a clause that allows for _____ termination.', 'preliminary', 'unilateral', 'reciprocal', 'temporary', 'B', 'Vocabulary', 'HARD', TRUE, v_teacher1),
('Which word is closest in meaning to "huge"?', 'Tiny', 'Enormous', 'Narrow', 'Shallow', 'B', 'Reading', 'EASY', TRUE, v_teacher2),
('What is the opposite meaning of "frequent"?', 'Common', 'Rare', 'Repeated', 'Continuous', 'B', 'Reading', 'EASY', TRUE, v_teacher2),
('In business emails, "ASAP" stands for:', 'As Soon As Possible', 'All Systems Are Protected', 'Always Send All Pages', 'At Same Available Price', 'A', 'Reading', 'EASY', TRUE, v_teacher2),
('Which sentence best summarizes an argument against remote work?', 'It cuts commute time', 'It can lead to social isolation', 'It offers schedule flexibility', 'It reduces office rent', 'B', 'Reading', 'MEDIUM', TRUE, v_teacher2),
('What tone is typically used in academic research papers?', 'Informal and humorous', 'Objective and formal', 'Emotional and dramatic', 'Sarcastic and casual', 'B', 'Reading', 'MEDIUM', TRUE, v_teacher2),
('If an author writes with "nostalgia", the author feels:', 'Anger at present events', 'Longing for the past', 'Fear of future challenges', 'Excitement for novelty', 'B', 'Reading', 'MEDIUM', TRUE, v_teacher2),
('The idiom "break the ice" means to:', 'Make people feel more comfortable', 'Cause an accident', 'Freeze relationships', 'Avoid talking to people', 'A', 'Reading', 'MEDIUM', TRUE, v_teacher2),
('A statement that appears self-contradictory but may reveal truth is a:', 'Metaphor', 'Paradox', 'Hyperbole', 'Simile', 'B', 'Reading', 'HARD', TRUE, v_teacher2),
('When evidence is described as "empirical", it is based on:', 'Observation or experiment', 'Theoretical speculation', 'Traditional belief', 'Pure mathematical deduction', 'A', 'Reading', 'HARD', TRUE, v_teacher2),
('The phrase "take with a grain of salt" implies:', 'Total acceptance', 'Mild skepticism', 'Complete rejection', 'Extreme enthusiasm', 'B', 'Reading', 'HARD', TRUE, v_teacher2);

    -- 4. Three ready-to-take quizzes, one draft, two closed quizzes with history.
    -- PINs: B1 123456, B2 234567, TOEIC 345678; closed quizzes 456789 / 567890.
    INSERT INTO quizzes (title, description, category, duration_minutes, pass_score,
        status, pin, anticheat_policy, max_violations, created_by, created_at, opened_at, closed_at)
    VALUES
    ('B1 Grammar - Practice', 'Bài luyện tập ngữ pháp cho lớp B1.', 'Grammar', 15, 50,
        'OPEN', '123456', 'WARN_THEN_AUTO_SUBMIT', 3, v_teacher1, now() - interval '2 days', now() - interval '2 hours', NULL),
    ('B2 Vocabulary - Practice', 'Bài luyện tập từ vựng cho lớp B2.', 'Vocabulary', 20, 60,
        'OPEN', '234567', 'LOG_ONLY', 3, v_teacher2, now() - interval '2 days', now() - interval '2 hours', NULL),
    ('TOEIC Reading - Practice', 'Bài luyện đọc hiểu cho lớp TOEIC.', 'Reading', 20, 60,
        'OPEN', '345678', 'WARN_THEN_AUTO_SUBMIT', 3, v_teacher1, now() - interval '2 days', now() - interval '2 hours', NULL),
    ('General English - Draft', 'Đề nháp để thử sửa câu hỏi, thời lượng và giao bài.', 'General English', 20, 50,
        'DRAFT', NULL, 'WARN_THEN_AUTO_SUBMIT', 3, v_teacher2, now(), NULL, NULL),
    ('B1 Grammar - Completed', 'Bài kiểm tra đã đóng, có kết quả và nhật ký vi phạm mẫu.', 'Grammar', 15, 50,
        'CLOSED', '456789', 'WARN_THEN_AUTO_SUBMIT', 3, v_teacher1, now() - interval '2 days', now() - interval '1 day', now() - interval '23 hours'),
    ('B2 Vocabulary - Completed', 'Bài kiểm tra đã đóng, có kết quả và nhật ký vi phạm mẫu.', 'Vocabulary', 20, 60,
        'CLOSED', '567890', 'WARN_THEN_AUTO_SUBMIT', 3, v_teacher2, now() - interval '2 days', now() - interval '1 day', now() - interval '23 hours');

    FOR v_quiz IN SELECT id, category FROM quizzes ORDER BY id LOOP
        INSERT INTO quiz_questions (quiz_id, question_id, position)
        SELECT v_quiz.id, q.id, row_number() OVER (ORDER BY q.id)::integer
        FROM questions q
        WHERE q.topic = v_quiz.category OR v_quiz.category = 'General English'
        ORDER BY q.id
        LIMIT CASE WHEN v_quiz.category = 'General English' THEN 15 ELSE 10 END;
    END LOOP;

    INSERT INTO quiz_assignments (quiz_id, class_id, assigned_by)
    SELECT q.id, c.id, q.created_by
    FROM (VALUES
        ('B1 Grammar - Practice', 'ENG-B1-01'),
        ('B2 Vocabulary - Practice', 'ENG-B2-01'),
        ('TOEIC Reading - Practice', 'TOEIC-650'),
        ('B1 Grammar - Completed', 'ENG-B1-01'),
        ('B2 Vocabulary - Completed', 'ENG-B2-01')
    ) AS assignment(title, class_name)
    JOIN quizzes q ON q.title = assignment.title
    JOIN classes c ON c.name = assignment.class_name;

    -- 5. Eight submitted attempts. Open quizzes have no attempts so students can try them.
    -- Answer rows determine the stored score; MANUAL, TIMEOUT and ANTICHEAT are represented.
    FOR v_sample IN SELECT * FROM (VALUES
        ('student01', 'B1 Grammar - Completed', 10, 'MANUAL'),
        ('student02', 'B1 Grammar - Completed',  4, 'MANUAL'),
        ('student03', 'B1 Grammar - Completed',  6, 'TIMEOUT'),
        ('student04', 'B1 Grammar - Completed',  0, 'ANTICHEAT'),
        ('student21', 'B2 Vocabulary - Completed', 8, 'MANUAL'),
        ('student22', 'B2 Vocabulary - Completed', 3, 'MANUAL'),
        ('student23', 'B2 Vocabulary - Completed', 5, 'TIMEOUT'),
        ('student24', 'B2 Vocabulary - Completed', 2, 'ANTICHEAT')
    ) AS sample(username, title, correct_count, reason) LOOP
        SELECT id INTO STRICT v_student FROM users WHERE username = v_sample.username;
        SELECT id, duration_minutes, pass_score INTO STRICT v_quiz FROM quizzes WHERE title = v_sample.title;
        SELECT count(*) INTO v_total FROM quiz_questions WHERE quiz_id = v_quiz.id;
        v_started := now() - interval '1 day' + interval '5 minutes';
        v_deadline := v_started + make_interval(mins => v_quiz.duration_minutes);
        v_submitted := CASE v_sample.reason
            WHEN 'TIMEOUT' THEN v_deadline
            WHEN 'ANTICHEAT' THEN v_started + interval '4 minutes'
            ELSE v_started + interval '8 minutes' END;

        INSERT INTO attempts (quiz_id, student_id, status, started_at, deadline_at, submitted_at)
        VALUES (v_quiz.id, v_student, 'SUBMITTED', v_started, v_deadline, v_submitted)
        RETURNING id INTO v_attempt;

        INSERT INTO attempt_answers (attempt_id, question_id, selected_option, is_correct, answered_at)
        SELECT v_attempt, q.id,
            CASE WHEN qq.position <= v_sample.correct_count THEN q.correct_option
                WHEN q.correct_option = 'A' THEN 'B' ELSE 'A' END,
            qq.position <= v_sample.correct_count, v_started + interval '3 minutes'
        FROM quiz_questions qq JOIN questions q ON q.id = qq.question_id
        WHERE qq.quiz_id = v_quiz.id;

        INSERT INTO results (attempt_id, score, correct_count, total_questions, passed, submit_reason, graded_at)
        VALUES (v_attempt, round(v_sample.correct_count * 10.0 / v_total, 2),
            v_sample.correct_count, v_total, v_sample.correct_count * 100.0 / v_total >= v_quiz.pass_score,
            v_sample.reason, v_submitted);

        IF v_sample.reason = 'ANTICHEAT' THEN
            INSERT INTO anticheat_events (attempt_id, event_type, detail, client_time,
                server_time, violation_count_after, action_taken)
            SELECT v_attempt, 'FOCUS_LOST', 'Dữ liệu demo: rời cửa sổ làm bài lần ' || n,
                v_started + make_interval(mins => CASE WHEN n = 3 THEN 4 ELSE n END),
                v_started + make_interval(mins => CASE WHEN n = 3 THEN 4 ELSE n END),
                n, CASE WHEN n = 3 THEN 'AUTO_SUBMITTED' ELSE 'WARNED' END
            FROM generate_series(1, 3) AS n;
        END IF;
    END LOOP;
END;
$$;
COMMIT;
