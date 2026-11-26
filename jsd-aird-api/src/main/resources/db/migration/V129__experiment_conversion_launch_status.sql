ALTER TABLE data.experiment_conversion DROP CONSTRAINT experiment_conversion_status_check;
ALTER TABLE data.experiment_conversion ADD CONSTRAINT experiment_conversion_status_check
    CHECK (status IN ('QUEUED', 'ACCEPTED', 'RUNNING', 'START_FAILED', 'WAITING_REVIEW', 'FAILED'));
ALTER TABLE data.experiment_conversion ADD COLUMN launch_request_id text;
