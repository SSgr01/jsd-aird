-- Editor snapshots are internal processing artifacts.  They must never become
-- the user-visible template name.  Repair rows created before the source-name
-- propagation fix, but only when the current name is the exact generated
-- snapshot filename.
WITH fixes AS (
    SELECT DISTINCT ON (t.id)
           t.id,
           regexp_replace(
               source_file.original_name,
               '\.(xlsx|xls|csv|docx|doc)$',
               '',
               'i'
           ) AS display_name
    FROM tpl.template t
    JOIN tpl.template_version tv
      ON tv.template_id = t.id
    JOIN tpl.template_import_job source_job
      ON source_job.generated_template_version_id = tv.id
     AND source_job.organization_id = t.organization_id
    JOIN ops.file_object source_file
      ON source_file.id = coalesce(source_job.original_source_file_id,
                                   source_job.source_file_id)
     AND source_file.organization_id = t.organization_id
    WHERE lower(t.name) ~ '^(xlsx|xls|csv|docx|doc)-univer-snapshot\.json$'
      AND lower(source_file.original_name) ~ '\.(xlsx|xls|csv|docx|doc)$'
    ORDER BY t.id, source_job.created_at ASC, source_job.id ASC
)
UPDATE tpl.template t
SET name = fixes.display_name,
    updated_at = now()
FROM fixes
WHERE t.id = fixes.id
  AND fixes.display_name IS NOT NULL
  AND btrim(fixes.display_name) <> '';
