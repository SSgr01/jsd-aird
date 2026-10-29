-- Existing sample records already have a stock transaction, regardless of their displayed status.
UPDATE mfg.inventory_sample_dispatch
SET status = 'COMPLETED'
WHERE status = 'PENDING' AND inventory_transaction_id IS NOT NULL;

ALTER TABLE mfg.inventory_sample_dispatch
    ALTER COLUMN inventory_transaction_id DROP NOT NULL;

ALTER TABLE mfg.inventory_sample_dispatch
    ADD CONSTRAINT inventory_sample_dispatch_completion_transaction_check
    CHECK ((status = 'PENDING' AND inventory_transaction_id IS NULL)
        OR (status = 'COMPLETED' AND inventory_transaction_id IS NOT NULL));
