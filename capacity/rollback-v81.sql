-- Operator-only rollback. Verify target database and backup first.
-- Existing jobs, leases, generation, revision and permission checks are retained.
BEGIN;
SET LOCAL lock_timeout='1s';
SET LOCAL statement_timeout='5s';
DROP TRIGGER IF EXISTS bounded_async_admission ON ops.async_job;
DROP FUNCTION IF EXISTS ops.enforce_async_capacity();
DROP INDEX IF EXISTS ops.idx_async_job_capacity_active;
COMMIT;
