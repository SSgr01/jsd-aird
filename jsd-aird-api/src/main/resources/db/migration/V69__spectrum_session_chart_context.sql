ALTER TABLE spc.chat_session
    ADD COLUMN chart_ids_jsonb jsonb NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN page_selections_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb;

WITH latest_context AS (
    SELECT DISTINCT ON (session_id)
           session_id, chart_ids_jsonb, page_selections_jsonb
    FROM spc.analysis_run
    WHERE chart_ids_jsonb IS NOT NULL
      AND chart_ids_jsonb <> '[]'::jsonb
    ORDER BY session_id, created_at DESC, id DESC
)
UPDATE spc.chat_session AS session
SET chart_ids_jsonb = latest_context.chart_ids_jsonb,
    page_selections_jsonb = latest_context.page_selections_jsonb
FROM latest_context
WHERE latest_context.session_id = session.id;
