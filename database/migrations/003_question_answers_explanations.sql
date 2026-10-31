ALTER TABLE questions ADD COLUMN IF NOT EXISTS answer_key TEXT NOT NULL DEFAULT '';
ALTER TABLE questions ADD COLUMN IF NOT EXISTS explanation TEXT NOT NULL DEFAULT '';
ALTER TABLE attempt_answers ADD COLUMN IF NOT EXISTS answer_text TEXT;
UPDATE attempt_answers SET answer_text = selected_option::TEXT WHERE answer_text IS NULL AND selected_option IS NOT NULL;
