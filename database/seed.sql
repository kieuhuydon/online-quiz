SET client_encoding TO 'UTF8';

-- ====================================================================
-- Online Quiz cho Trung tâm đào tạo (Sample Data / Seed Script)
-- Mật khẩu mặc định cho tất cả tài khoản mẫu: password123
-- BCrypt Hash: $2a$10$RMj7Oqje6a.XQorzyUTXuuXnV/EKY/.yYf4YaxIMOz67D/tFVZ.0y
-- ====================================================================

-- DEMO ONLY. Requires an explicit opt-in; never use these accounts in production.
-- psql -v ON_ERROR_STOP=1 -c "SET quiz.allow_demo_seed = 'on'" -f database/seed.sql
BEGIN;
DO $$
DECLARE
    table_name TEXT;
    sequence_used BOOLEAN;
BEGIN
    IF current_setting('quiz.allow_demo_seed', true) IS DISTINCT FROM 'on' THEN
        RAISE EXCEPTION 'Demo seed requires quiz.allow_demo_seed=on';
    END IF;
    IF EXISTS (SELECT 1 FROM users) THEN
        RAISE EXCEPTION 'Demo seed requires a freshly created empty schema';
    END IF;
    -- The fixture below uses fixed IDs. Reject reused sequences, even after DELETE.
    FOREACH table_name IN ARRAY ARRAY['users', 'classes', 'questions', 'quizzes'] LOOP
        EXECUTE format('SELECT is_called FROM %s', pg_get_serial_sequence(table_name, 'id')::regclass)
            INTO sequence_used;
        IF sequence_used THEN
            RAISE EXCEPTION 'Demo seed requires unused sequences: %', table_name;
        END IF;
    END LOOP;
END $$;

-- 1. Insert Admin
INSERT INTO users (username, password_hash, full_name, email, role, active, created_by)
VALUES 
('admin', '$2a$10$RMj7Oqje6a.XQorzyUTXuuXnV/EKY/.yYf4YaxIMOz67D/tFVZ.0y', 'System Administrator', 'admin@quiz.center', 'ADMIN', TRUE, NULL);

-- 2. Insert Teachers
INSERT INTO users (username, password_hash, full_name, email, role, active, created_by)
VALUES 
('teacher1', '$2a$10$RMj7Oqje6a.XQorzyUTXuuXnV/EKY/.yYf4YaxIMOz67D/tFVZ.0y', 'Nguyen Van A', 'teacher1@quiz.center', 'TEACHER', TRUE, 1),
('teacher2', '$2a$10$RMj7Oqje6a.XQorzyUTXuuXnV/EKY/.yYf4YaxIMOz67D/tFVZ.0y', 'Tran Thi B', 'teacher2@quiz.center', 'TEACHER', TRUE, 1);

-- 3. Insert 50 Students (student01 -> student50)
INSERT INTO users (username, password_hash, full_name, email, role, active, created_by)
SELECT 
    'student' || LPAD(s::TEXT, 2, '0'),
    '$2a$10$RMj7Oqje6a.XQorzyUTXuuXnV/EKY/.yYf4YaxIMOz67D/tFVZ.0y',
    'Student ' || LPAD(s::TEXT, 2, '0'),
    'student' || LPAD(s::TEXT, 2, '0') || '@quiz.center',
    'STUDENT',
    TRUE,
    1
FROM generate_series(1, 50) AS s;

-- 4. Insert Classes
INSERT INTO classes (name, description, teacher_id)
VALUES 
('ENG-B1-01', 'English B1 - Morning Class', 2),
('ENG-B2-01', 'English B2 - Evening Class', 3),
('TOEIC-650', 'TOEIC Intensive 650+ Target', 2);

-- 5. Insert Class Members (Enrollments)
-- Class 1 (ENG-B1-01): student01 -> student20 (id 4 -> 23)
INSERT INTO class_members (class_id, student_id)
SELECT 1, id FROM users WHERE role = 'STUDENT' AND id BETWEEN 4 AND 23;

-- Class 2 (ENG-B2-01): student21 -> student40 (id 24 -> 43)
INSERT INTO class_members (class_id, student_id)
SELECT 2, id FROM users WHERE role = 'STUDENT' AND id BETWEEN 24 AND 43;

-- Class 3 (TOEIC-650): student41 -> student50 (id 44 -> 53)
INSERT INTO class_members (class_id, student_id)
SELECT 3, id FROM users WHERE role = 'STUDENT' AND id BETWEEN 44 AND 53;

-- 6. Insert Questions (>= 30 questions)
-- Grammar Questions (1-10)
INSERT INTO questions (content, option_a, option_b, option_c, option_d, correct_option, topic, difficulty, active, created_by)
VALUES
('She _____ to school every day by bus.', 'go', 'goes', 'going', 'gone', 'B', 'Grammar', 'EASY', TRUE, 2),
('If it _____ tomorrow, we will cancel the picnic.', 'rains', 'rain', 'rained', 'will rain', 'A', 'Grammar', 'EASY', TRUE, 2),
('They have been living here _____ 2018.', 'for', 'since', 'in', 'at', 'B', 'Grammar', 'EASY', TRUE, 2),
('Neither the teacher nor the students _____ present yesterday.', 'was', 'were', 'is', 'are', 'B', 'Grammar', 'MEDIUM', TRUE, 2),
('I wish I _____ more time to finish the exam.', 'have', 'had', 'will have', 'having', 'B', 'Grammar', 'MEDIUM', TRUE, 2),
('By the time we arrived, the movie _____.', 'already started', 'has already started', 'had already started', 'was starting', 'C', 'Grammar', 'MEDIUM', TRUE, 2),
('Hardly _____ when the bell rang.', 'he entered the room', 'had he entered the room', 'did he enter the room', 'he had entered the room', 'B', 'Grammar', 'HARD', TRUE, 2),
('Not only _____ late, but he also forgot his homework.', 'he was', 'was he', 'did he', 'he did', 'B', 'Grammar', 'HARD', TRUE, 2),
('It is essential that she _____ the meeting on time.', 'attends', 'attend', 'attended', 'will attend', 'B', 'Grammar', 'HARD', TRUE, 2),
('The report _____ by the end of next week.', 'will finish', 'is finishing', 'will be finished', 'has finished', 'C', 'Grammar', 'MEDIUM', TRUE, 2);

-- Vocabulary Questions (11-20)
INSERT INTO questions (content, option_a, option_b, option_c, option_d, correct_option, topic, difficulty, active, created_by)
VALUES
('The weather was so _____ that we stayed indoors all day.', 'pleasant', 'awful', 'gorgeous', 'warm', 'B', 'Vocabulary', 'EASY', TRUE, 2),
('Could you please lend me your pen? Mine has run _____ ink.', 'into', 'out of', 'over', 'away', 'B', 'Vocabulary', 'EASY', TRUE, 2),
('He gave an accurate _____ of the suspect to the police.', 'description', 'imagination', 'connection', 'impression', 'A', 'Vocabulary', 'EASY', TRUE, 2),
('The new software had a profound _____ on team productivity.', 'affect', 'effect', 'result', 'reason', 'B', 'Vocabulary', 'MEDIUM', TRUE, 2),
('We need to find an _____ solution to reduce carbon emissions.', 'innovative', 'inaccurate', 'ordinary', 'inactive', 'A', 'Vocabulary', 'MEDIUM', TRUE, 2),
('Her dedication and hard work were finally _____ with a promotion.', 'repaid', 'rewarded', 'returned', 'refunded', 'B', 'Vocabulary', 'MEDIUM', TRUE, 2),
('The CEO delivered an exceptionally _____ speech that inspired everyone.', 'eloquent', 'awkward', 'abrupt', 'ambiguous', 'A', 'Vocabulary', 'HARD', TRUE, 2),
('The scientist discovered a previously _____ species in the deep ocean.', 'unprecedented', 'obscure', 'lucid', 'ubiquitous', 'B', 'Vocabulary', 'HARD', TRUE, 2),
('His arguments were so _____ that nobody could dispute them.', 'cogent', 'feeble', 'flawed', 'redundant', 'A', 'Vocabulary', 'HARD', TRUE, 2),
('The contract contains a clause that allows for _____ termination.', 'preliminary', 'unilateral', 'reciprocal', 'temporary', 'B', 'Vocabulary', 'HARD', TRUE, 2);

-- Reading & Comprehension Questions (21-30)
INSERT INTO questions (content, option_a, option_b, option_c, option_d, correct_option, topic, difficulty, active, created_by)
VALUES
('Which word is closest in meaning to "huge"?', 'Tiny', 'Enormous', 'Narrow', 'Shallow', 'B', 'Reading', 'EASY', TRUE, 3),
('What is the opposite meaning of "frequent"?', 'Common', 'Rare', 'Repeated', 'Continuous', 'B', 'Reading', 'EASY', TRUE, 3),
('In business emails, "ASAP" stands for:', 'As Soon As Possible', 'All Systems Are Protected', 'Always Send All Pages', 'At Same Available Price', 'A', 'Reading', 'EASY', TRUE, 3),
('Which sentence best summarizes an argument against remote work?', 'It cuts commute time', 'It can lead to social isolation', 'It offers schedule flexibility', 'It reduces office rent', 'B', 'Reading', 'MEDIUM', TRUE, 3),
('What tone is typically used in academic research papers?', 'Informal and humorous', 'Objective and formal', 'Emotional and dramatic', 'Sarcastic and casual', 'B', 'Reading', 'MEDIUM', TRUE, 3),
('If an author writes with "nostalgia", the author feels:', 'Anger at present events', 'Longing for the past', 'Fear of future challenges', 'Excitement for novelty', 'B', 'Reading', 'MEDIUM', TRUE, 3),
('The idiom "break the ice" means to:', 'Make people feel more comfortable', 'Cause an accident', 'Freeze relationships', 'Avoid talking to people', 'A', 'Reading', 'MEDIUM', TRUE, 3),
('A statement that appears self-contradictory but may reveal truth is a:', 'Metaphor', 'Paradox', 'Hyperbole', 'Simile', 'B', 'Reading', 'HARD', TRUE, 3),
('When evidence is described as "empirical", it is based on:', 'Observation or experiment', 'Theoretical speculation', 'Traditional belief', 'Pure mathematical deduction', 'A', 'Reading', 'HARD', TRUE, 3),
('The phrase "take with a grain of salt" implies:', 'Total acceptance', 'Mild skepticism', 'Complete rejection', 'Extreme enthusiasm', 'B', 'Reading', 'HARD', TRUE, 3);

-- 7. Insert Sample Quizzes
-- Quiz 1: OPEN with PIN 123456
INSERT INTO quizzes (title, description, category, duration_minutes, pass_score, status, pin, anticheat_policy, max_violations, created_by, opened_at)
VALUES 
('English Grammar Placement Test', 'Test basic and intermediate grammar skills.', 'Grammar', 15, 50.00, 'OPEN', '123456', 'WARN_THEN_AUTO_SUBMIT', 3, 2, now());

-- Quiz 2: DRAFT (chưa mở)
INSERT INTO quizzes (title, description, category, duration_minutes, pass_score, status, pin, anticheat_policy, max_violations, created_by)
VALUES 
('Vocabulary & Reading Assessment', 'Comprehensive vocabulary and reading test.', 'General English', 20, 60.00, 'DRAFT', NULL, 'WARN_THEN_AUTO_SUBMIT', 3, 3);

-- 8. Assign Questions to Quiz 1 (10 questions)
INSERT INTO quiz_questions (quiz_id, question_id, position)
VALUES 
(1, 1, 1),
(1, 2, 2),
(1, 3, 3),
(1, 4, 4),
(1, 5, 5),
(1, 11, 6),
(1, 12, 7),
(1, 13, 8),
(1, 21, 9),
(1, 22, 10);

-- Assign Questions to Quiz 2 (10 questions)
INSERT INTO quiz_questions (quiz_id, question_id, position)
VALUES 
(2, 6, 1),
(2, 7, 2),
(2, 14, 3),
(2, 15, 4),
(2, 16, 5),
(2, 24, 6),
(2, 25, 7),
(2, 26, 8),
(2, 27, 9),
(2, 28, 10);

-- 9. Assign Quiz 1 to Class 1 (ENG-B1-01)
INSERT INTO quiz_assignments (quiz_id, class_id, assigned_by)
VALUES 
(1, 1, 2);

COMMIT;
