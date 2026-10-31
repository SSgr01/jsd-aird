ALTER TABLE mfg.inventory_sample_dispatch
    ADD COLUMN status varchar(16) NOT NULL DEFAULT 'COMPLETED';

ALTER TABLE mfg.inventory_sample_dispatch
    ADD CONSTRAINT inventory_sample_dispatch_status_check
    CHECK (status IN ('PENDING', 'COMPLETED'));

ALTER TABLE mfg.inventory_sample_dispatch
    ALTER COLUMN status SET DEFAULT 'PENDING';
