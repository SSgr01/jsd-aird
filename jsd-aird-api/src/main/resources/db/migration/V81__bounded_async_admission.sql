-- Counts reserve READY/RUNNING work together: a retry retains its existing slot.
-- The same transaction as the business write either enqueues everything or rolls back.
CREATE INDEX IF NOT EXISTS idx_async_job_capacity_active
    ON ops.async_job(organization_id) WHERE status IN ('READY','RUNNING');

CREATE OR REPLACE FUNCTION ops.enforce_async_capacity() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    total_limit integer := coalesce(nullif(current_setting('app.capacity_pending_jobs',true),''),'100')::integer;
    org_limit integer := coalesce(nullif(current_setting('app.capacity_org_pending_jobs',true),''),'20')::integer;
BEGIN
    IF NEW.status NOT IN ('READY','RUNNING') THEN RETURN NEW; END IF;
    IF TG_OP='UPDATE' AND OLD.status IN ('READY','RUNNING') AND OLD.organization_id=NEW.organization_id THEN RETURN NEW; END IF;
    PERFORM pg_advisory_xact_lock(hashtextextended('aird:async-capacity',0));
    IF TG_OP='INSERT' AND NEW.idempotency_key IS NOT NULL AND EXISTS(
        SELECT 1 FROM ops.async_job WHERE organization_id=NEW.organization_id AND idempotency_key=NEW.idempotency_key
    ) THEN RETURN NEW; END IF;
    IF (SELECT count(*) FROM ops.async_job WHERE status IN ('READY','RUNNING')) >= total_limit
       OR (SELECT count(*) FROM ops.async_job WHERE organization_id=NEW.organization_id AND status IN ('READY','RUNNING')) >= org_limit THEN
        RAISE EXCEPTION USING ERRCODE='55P03', MESSAGE='aird:queue-capacity';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER bounded_async_admission BEFORE INSERT OR UPDATE OF status,organization_id ON ops.async_job
    FOR EACH ROW EXECUTE FUNCTION ops.enforce_async_capacity();
