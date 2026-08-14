-- Field extraction was removed in V29. Remove its historical issues and
-- metadata so the review API only exposes text/block-level parse problems.
DELETE FROM kb.document_parse_issue
WHERE issue_code IN ('REQUIRED_FIELD_MISSING', 'LOW_FIELD_CONFIDENCE');

UPDATE kb.publication
SET metadata_snapshot_jsonb = metadata_snapshot_jsonb - 'fields' - 'fieldResults';
