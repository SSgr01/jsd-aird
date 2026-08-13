-- A user may re-run semantic recognition for the same component more than
-- once. The model client's attempt counter is scoped to one invocation, so
-- (run, region, phase, attempt) is not a globally unique call identity. The
-- call UUID remains the immutable audit identity; keep a non-unique lookup
-- index for diagnostics and ordering.
ALTER TABLE tpl.recognition_call
    DROP CONSTRAINT IF EXISTS recognition_call_recognition_run_id_region_id_phase_attempt_key;

CREATE INDEX IF NOT EXISTS idx_recognition_call_region_phase_attempt
    ON tpl.recognition_call (recognition_run_id, region_id, phase, attempt, created_at DESC);
