-- psql -v auto_model='Qwen/Qwen3-Embedding-4B' -v auto_dimension=2560 -f scripts/local/migrate_embedding_jobs.sql
-- Run only after backup, with both APIs/Workers stopped and no RUNNING jobs.
\set ON_ERROR_STOP on
BEGIN;
SELECT pg_advisory_xact_lock(hashtextextended('embedding-job-migration',0));
UPDATE ops.async_job SET payload_jsonb=payload_jsonb || jsonb_build_object(
 'expectedEmbeddingModel', :'auto_model', 'expectedEmbeddingDimension', :'auto_dimension'::integer),updated_at=now()
WHERE status='READY' AND job_type IN ('KB_BUILD_KNOWLEDGE_INDEX','KB_BUILD_KNOWLEDGE_VECTOR')
 AND NOT (payload_jsonb ? 'expectedEmbeddingModel')
 AND length(:'auto_model')>0 AND :'auto_dimension'::integer BETWEEN 1 AND 4000;
-- Rebuild jobs with no explicit source target require review; never guess their model.
SELECT id,job_type,status FROM ops.async_job
 WHERE status='READY' AND job_type IN ('KB_BUILD_KNOWLEDGE_INDEX','KB_BUILD_KNOWLEDGE_VECTOR','KB_REBUILD_PUBLISHED_INDEX')
 AND (NOT (payload_jsonb ? 'expectedEmbeddingModel') OR NOT (payload_jsonb ? 'expectedEmbeddingDimension'));
COMMIT;
-- Old vectors omitted by V118 (unmarked, unsupported or not representable in FP16).
SELECT coalesce(c.embedding_model,'UNMARKED') AS model,vector_dims(c.embedding) AS dimension,count(*)
 FROM kb.document_chunk c LEFT JOIN kb.document_chunk_embedding e ON e.chunk_id=c.id AND e.embedding_model=c.embedding_model
 AND e.embedding_dimension=vector_dims(c.embedding)
 WHERE c.embedding IS NOT NULL AND e.chunk_id IS NULL GROUP BY 1,2;
