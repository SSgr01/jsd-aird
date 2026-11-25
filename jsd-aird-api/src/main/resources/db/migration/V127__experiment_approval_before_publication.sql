-- Approval locks the reviewed version; only a later publish action makes it public.
ALTER TABLE rnd.experiment DROP CONSTRAINT IF EXISTS experiment_status_check;
ALTER TABLE rnd.experiment ADD CONSTRAINT experiment_status_check
    CHECK (status IN ('DRAFT','PENDING','IN_PROGRESS','PENDING_REVIEW','APPROVED','RETURNED','COMPLETED','VOIDED'));
ALTER TABLE rnd.experiment_version DROP CONSTRAINT IF EXISTS experiment_version_status_check;
ALTER TABLE rnd.experiment_version ADD CONSTRAINT experiment_version_status_check
    CHECK (status IN ('DRAFT','PENDING','IN_PROGRESS','PENDING_REVIEW','APPROVED','RETURNED','COMPLETED','VOIDED'));
