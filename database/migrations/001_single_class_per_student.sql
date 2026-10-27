-- Existing databases: psql -v ON_ERROR_STOP=1 -f database/migrations/001_single_class_per_student.sql
-- Resolve duplicate memberships explicitly before retrying; this script never deletes data.
BEGIN;
SET LOCAL lock_timeout = '10s';
LOCK TABLE class_members IN SHARE ROW EXCLUSIVE MODE;

DO $$
BEGIN
    IF EXISTS (SELECT student_id FROM class_members GROUP BY student_id HAVING count(*) > 1) THEN
        RAISE EXCEPTION 'Cannot enforce one class per student: duplicate memberships exist'
            USING ERRCODE = '23505',
                HINT = 'List student_id and array_agg(class_id) grouped by student_id having count(*) > 1; choose the class to keep, remove only unwanted memberships, then rerun.';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'class_members'::regclass AND conname = 'uq_class_members_student'
    ) THEN
        ALTER TABLE class_members ADD CONSTRAINT uq_class_members_student UNIQUE (student_id);
    END IF;
END;
$$;
COMMIT;
