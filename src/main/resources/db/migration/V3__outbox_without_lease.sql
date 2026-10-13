-- D-004: a task is held by the row lock of the worker's transaction (FOR UPDATE SKIP LOCKED), not
-- by a lease, and its times come from the application clock.
ALTER TABLE outbox_task
    DROP COLUMN locked_until,
    ALTER COLUMN next_attempt_at DROP DEFAULT,
    ALTER COLUMN created_at DROP DEFAULT,
    ALTER COLUMN updated_at DROP DEFAULT;
