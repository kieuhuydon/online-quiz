-- Existing databases: preserve free-response answers in answer_key and stop
-- representing them with a fake A/B/C/D correct_option marker.
BEGIN;

ALTER TABLE questions ADD COLUMN IF NOT EXISTS question_type VARCHAR(20) NOT NULL DEFAULT 'MULTIPLE_CHOICE';
ALTER TABLE questions ADD COLUMN IF NOT EXISTS explanation TEXT NOT NULL DEFAULT '';
ALTER TABLE questions ADD COLUMN IF NOT EXISTS answer_key TEXT NOT NULL DEFAULT '';

UPDATE questions
SET answer_key = option_a
WHERE question_type IN ('FILL_BLANK', 'SHORT_ANSWER')
  AND (answer_key IS NULL OR btrim(answer_key) = '')
  AND option_a IS NOT NULL
  AND btrim(option_a) NOT IN ('', '-', '—');

UPDATE questions q
SET explanation = CASE
    WHEN q.question_type IN ('FILL_BLANK', 'SHORT_ANSWER') THEN
        'The expected answer is "' || q.answer_key || '"; responses are checked against this text without case sensitivity.'
    WHEN q.question_type = 'TRUE_FALSE' THEN
        'The correct response is "' || CASE q.correct_option WHEN 'A' THEN q.option_a ELSE q.option_b END || '".'
    WHEN q.topic = 'Grammar' THEN
        'The correct form is "' || CASE q.correct_option WHEN 'A' THEN q.option_a WHEN 'B' THEN q.option_b WHEN 'C' THEN q.option_c ELSE q.option_d END || '" because it matches the grammar required by the sentence.'
    WHEN q.topic = 'Vocabulary' THEN
        '"' || CASE q.correct_option WHEN 'A' THEN q.option_a WHEN 'B' THEN q.option_b WHEN 'C' THEN q.option_c ELSE q.option_d END || '" has the meaning required by the sentence or definition.'
    WHEN q.topic = 'Reading' THEN
        'The passage supports "' || CASE q.correct_option WHEN 'A' THEN q.option_a WHEN 'B' THEN q.option_b WHEN 'C' THEN q.option_c ELSE q.option_d END || '"; the other choices add unsupported details or contradict it.'
    ELSE 'The correct answer is "' || CASE q.correct_option WHEN 'A' THEN q.option_a WHEN 'B' THEN q.option_b WHEN 'C' THEN q.option_c ELSE q.option_d END || '".'
END
WHERE q.explanation IS NULL OR btrim(q.explanation) = '';

ALTER TABLE questions ALTER COLUMN correct_option DROP NOT NULL;
ALTER TABLE questions DROP CONSTRAINT IF EXISTS questions_correct_option_check;
ALTER TABLE questions DROP CONSTRAINT IF EXISTS questions_correct_option_by_type;

UPDATE questions
SET correct_option = NULL,
    option_a = '', option_b = '', option_c = '', option_d = ''
WHERE question_type IN ('FILL_BLANK', 'SHORT_ANSWER');

ALTER TABLE questions
    ADD CONSTRAINT questions_correct_option_by_type CHECK (
        (question_type = 'MULTIPLE_CHOICE' AND correct_option IS NOT NULL AND correct_option IN ('A', 'B', 'C', 'D')) OR
        (question_type = 'TRUE_FALSE' AND correct_option IS NOT NULL AND correct_option IN ('A', 'B')) OR
        (question_type IN ('FILL_BLANK', 'SHORT_ANSWER') AND correct_option IS NULL)
    );

COMMIT;
