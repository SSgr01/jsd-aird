-- Separate parsing, human review and publication.  Parse runs are immutable executions;
-- review revisions own editable content and publications own searchable snapshots.

ALTER TABLE kb.document_parse_run
    ADD COLUMN source_document_jsonb jsonb NOT NULL DEFAULT '{"type":"doc","schemaVersion":1,"content":[]}'::jsonb,
    ADD COLUMN document_schema_version integer NOT NULL DEFAULT 1;

CREATE TABLE kb.document_source_node (
    source_node_key uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES iam.organization(id),
    parse_run_id uuid NOT NULL REFERENCES kb.document_parse_run(id) ON DELETE CASCADE,
    node_no integer NOT NULL CHECK (node_no >= 0),
    node_type varchar(40) NOT NULL,
    raw_text text NOT NULL DEFAULT '',
    source_anchor_jsonb jsonb NOT NULL DEFAULT '{"version":1,"kind":"none"}'::jsonb,
    confidence_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (parse_run_id, node_no)
);

CREATE INDEX idx_kb_source_node_run
    ON kb.document_source_node(parse_run_id, node_no);

-- The former parse block id is reused as the source key. This keeps issue references
-- stable and makes the migration deterministic.
INSERT INTO kb.document_source_node (
    source_node_key, organization_id, parse_run_id, node_no, node_type, raw_text,
    source_anchor_jsonb, confidence_jsonb
)
SELECT b.id, r.organization_id, b.parse_run_id, b.block_no,
       CASE
           WHEN b.section IN ('table', 'spreadsheet-row') THEN 'tableRow'
           WHEN b.section IN ('heading', 'title') THEN 'heading'
           WHEN b.section = 'listItem' THEN 'listItem'
           WHEN b.section = 'image' THEN 'image'
           WHEN b.start_time_ms IS NOT NULL THEN 'audioSegment'
           ELSE 'paragraph'
       END,
       b.raw_text,
       jsonb_strip_nulls(jsonb_build_object(
           'version', 1,
           'kind', CASE
               WHEN b.sheet_name IS NOT NULL THEN 'sheet_range'
               WHEN b.start_time_ms IS NOT NULL THEN 'time_range'
               WHEN b.paragraph_id IS NOT NULL THEN 'docx_path'
               WHEN b.page_no IS NOT NULL AND b.bbox_jsonb IS NOT NULL THEN 'page_region'
               WHEN b.page_no IS NOT NULL THEN 'page'
               ELSE 'none'
           END,
           'page', b.page_no,
           'polygon', b.bbox_jsonb,
           'sheetKey', b.sheet_name,
           'sheetName', b.sheet_name,
           'range', b.cell_range,
           'paragraphId', b.paragraph_id,
           'startMs', b.start_time_ms,
           'endMs', b.end_time_ms
       )),
       jsonb_strip_nulls(jsonb_build_object(
           'textConfidence', b.confidence,
           'structureConfidence', NULL,
           'tableConfidence', NULL
       ))
FROM kb.document_parse_block b
JOIN kb.document_parse_run r ON r.id = b.parse_run_id
ON CONFLICT (source_node_key) DO NOTHING;

UPDATE kb.document_parse_run r
SET source_document_jsonb = jsonb_build_object(
    'type', 'doc',
    'schemaVersion', 1,
    'content', coalesce((
        SELECT jsonb_agg(jsonb_build_object(
            'type', CASE
                WHEN n.node_type = 'heading' THEN 'heading'
                WHEN n.node_type = 'listItem' THEN 'bulletList'
                ELSE 'paragraph'
            END,
            'attrs', jsonb_build_object('sourceNodeKey', n.source_node_key),
            'content', jsonb_build_array(jsonb_build_object('type', 'text', 'text', n.raw_text))
        ) ORDER BY n.node_no)
        FROM kb.document_source_node n WHERE n.parse_run_id = r.id
    ), '[]'::jsonb)
);

CREATE TABLE kb.document_review_revision (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES iam.organization(id),
    document_id uuid NOT NULL REFERENCES kb.document(id) ON DELETE CASCADE,
    document_version_id uuid NOT NULL REFERENCES kb.document_version(id) ON DELETE CASCADE,
    parse_run_id uuid NOT NULL REFERENCES kb.document_parse_run(id),
    revision_no integer NOT NULL CHECK (revision_no > 0),
    lock_version integer NOT NULL DEFAULT 0 CHECK (lock_version >= 0),
    base_publication_id uuid REFERENCES kb.publication(id),
    confirmed_document_jsonb jsonb NOT NULL,
    confirmed_text text NOT NULL DEFAULT '',
    excluded_review_node_ids jsonb NOT NULL DEFAULT '[]'::jsonb,
    status varchar(24) NOT NULL CHECK (status IN (
        'DRAFT', 'BUILDING', 'PUBLISHED', 'FAILED', 'SUPERSEDED'
    )),
    failure_reason varchar(2000),
    created_by uuid NOT NULL REFERENCES iam.app_user(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid NOT NULL REFERENCES iam.app_user(id),
    updated_at timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz,
    UNIQUE (document_id, revision_no)
);

CREATE INDEX idx_kb_review_revision_version
    ON kb.document_review_revision(organization_id, document_version_id, revision_no DESC);
CREATE UNIQUE INDEX uq_kb_review_revision_draft
    ON kb.document_review_revision(document_version_id)
    WHERE status = 'DRAFT';

-- A historical revision-like parse run refers to its true source run in result_jsonb.
INSERT INTO kb.document_review_revision (
    id, organization_id, document_id, document_version_id, parse_run_id, revision_no,
    lock_version, base_publication_id, confirmed_document_jsonb, confirmed_text,
    excluded_review_node_ids, status, failure_reason, created_by, created_at, updated_by,
    updated_at, published_at
)
SELECT gen_random_uuid(), r.organization_id, r.document_id, r.document_version_id,
       coalesce(source_run.id, r.id),
       row_number() OVER (PARTITION BY r.document_id ORDER BY r.created_at, r.run_no),
       greatest(v.review_revision, 0),
       p.id,
       jsonb_build_object(
           'type', 'doc',
           'schemaVersion', 1,
           'content', coalesce((
               SELECT jsonb_agg(jsonb_build_object(
                   'type', CASE
                       WHEN b.section IN ('heading', 'title') THEN 'heading'
                       ELSE 'paragraph'
                   END,
                   'attrs', jsonb_build_object(
                       'reviewNodeId', b.id,
                       'origin', 'source',
                       'sourceNodeKeys', jsonb_build_array(coalesce((
                           SELECT source_block.id FROM kb.document_parse_block source_block
                           WHERE source_block.parse_run_id = source_run.id
                             AND source_block.block_no = b.block_no
                           LIMIT 1
                       ), b.id))
                   ),
                   'content', jsonb_build_array(jsonb_build_object(
                       'type', 'text',
                       'text', coalesce(b.confirmed_text, b.normalized_text, b.raw_text, '')
                   ))
               ) ORDER BY b.block_no)
               FROM kb.document_parse_block b WHERE b.parse_run_id = r.id
           ), '[]'::jsonb)
       ),
       coalesce((
           SELECT string_agg(coalesce(b.confirmed_text, b.normalized_text, b.raw_text, ''), E'\n\n' ORDER BY b.block_no)
           FROM kb.document_parse_block b
           WHERE b.parse_run_id = r.id AND b.review_status <> 'IGNORED'
       ), ''),
       coalesce((
           SELECT jsonb_agg(to_jsonb(b.id) ORDER BY b.block_no)
           FROM kb.document_parse_block b
           WHERE b.parse_run_id = r.id AND b.review_status = 'IGNORED'
       ), '[]'::jsonb),
       CASE
           WHEN p.id IS NOT NULL THEN 'PUBLISHED'
           WHEN r.status = 'INDEXING' THEN 'BUILDING'
           WHEN r.status = 'FAILED' THEN 'FAILED'
           ELSE 'DRAFT'
       END,
       r.error_message,
       coalesce(p.published_by, d.created_by), r.created_at,
       coalesce(p.published_by, d.created_by), coalesce(r.finished_at, r.created_at), p.published_at
FROM kb.document_parse_run r
JOIN kb.document d ON d.id = r.document_id
JOIN kb.document_version v ON v.id = r.document_version_id
LEFT JOIN kb.publication p ON p.parse_run_id = r.id
LEFT JOIN kb.document_parse_run source_run
       ON source_run.id = CASE
           WHEN r.result_jsonb->>'sourceParseRunId' ~* '^[0-9a-f-]{36}$'
           THEN (r.result_jsonb->>'sourceParseRunId')::uuid
           ELSE NULL
       END
WHERE r.result_jsonb->>'revision' = 'true'
   OR p.id IS NOT NULL
   OR NOT EXISTS (
       SELECT 1 FROM kb.document_parse_run historical_revision
       WHERE historical_revision.result_jsonb->>'revision' = 'true'
         AND historical_revision.result_jsonb->>'sourceParseRunId' = r.id::text
   );

-- At most one editable draft may remain for a file version after migration.
WITH ranked AS (
    SELECT id, row_number() OVER (
        PARTITION BY document_version_id ORDER BY revision_no DESC
    ) AS rank_no
    FROM kb.document_review_revision WHERE status = 'DRAFT'
)
UPDATE kb.document_review_revision r
SET status = 'SUPERSEDED'
FROM ranked x WHERE x.id = r.id AND x.rank_no > 1;

CREATE TABLE kb.document_review_issue_state (
    review_revision_id uuid NOT NULL REFERENCES kb.document_review_revision(id) ON DELETE CASCADE,
    parse_issue_id uuid NOT NULL REFERENCES kb.document_parse_issue(id) ON DELETE CASCADE,
    status varchar(24) NOT NULL CHECK (status IN ('OPEN', 'RESOLVED', 'IGNORED')),
    resolution varchar(1000),
    updated_by uuid REFERENCES iam.app_user(id),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (review_revision_id, parse_issue_id)
);

CREATE TABLE kb.document_parse_issue_source_node (
    parse_issue_id uuid NOT NULL REFERENCES kb.document_parse_issue(id) ON DELETE CASCADE,
    source_node_key uuid NOT NULL REFERENCES kb.document_source_node(source_node_key) ON DELETE CASCADE,
    PRIMARY KEY (parse_issue_id, source_node_key)
);

INSERT INTO kb.document_parse_issue_source_node(parse_issue_id, source_node_key)
SELECT id, parse_block_id FROM kb.document_parse_issue WHERE parse_block_id IS NOT NULL
ON CONFLICT DO NOTHING;

ALTER TABLE kb.publication ADD COLUMN review_revision_id uuid;
UPDATE kb.publication p
SET review_revision_id = r.id
FROM kb.document_review_revision r
WHERE r.document_id = p.document_id
  AND r.document_version_id = p.document_version_id
  AND r.status = 'PUBLISHED'
  AND r.base_publication_id = p.id;
ALTER TABLE kb.publication
    ALTER COLUMN review_revision_id SET NOT NULL,
    ADD CONSTRAINT fk_kb_publication_review_revision
        FOREIGN KEY (review_revision_id) REFERENCES kb.document_review_revision(id);

UPDATE ops.async_job j
SET payload_jsonb = (j.payload_jsonb - 'parseRunId')
        || jsonb_build_object('reviewRevisionId', p.review_revision_id)
FROM kb.publication p
WHERE j.job_type = 'KB_BUILD_KNOWLEDGE_VECTOR'
  AND j.status IN ('READY', 'RETRY')
  AND j.payload_jsonb->>'publicationId' = p.id::text;

ALTER TABLE kb.document_chunk
    ADD COLUMN review_revision_id uuid,
    ADD COLUMN review_node_ids_jsonb jsonb NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN source_node_keys_jsonb jsonb NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN source_anchor_jsonb jsonb;

-- Keep the original parse-run identity while resolving the publication.  A
-- revision run also points back to its source run, so both the source and the
-- revision publication can satisfy the fallback predicate.  Prefer the exact
-- parse-run match to prevent a revision chunk from being attached to the
-- source publication nondeterministically.
WITH chunk_revision_match AS (
    SELECT DISTINCT ON (c.id)
           c.id AS chunk_id,
           p.review_revision_id
    FROM kb.document_chunk c
    JOIN kb.publication p
      ON p.document_id = c.document_id
     AND p.document_version_id = c.document_version_id
     AND (
          p.parse_run_id = c.parse_run_id
          OR EXISTS (
              SELECT 1
              FROM kb.document_parse_run fake
              WHERE fake.id = c.parse_run_id
                AND fake.result_jsonb->>'revision' = 'true'
                AND fake.result_jsonb->>'sourceParseRunId' = p.parse_run_id::text
          )
     )
    ORDER BY c.id,
             CASE WHEN p.parse_run_id = c.parse_run_id THEN 0 ELSE 1 END,
             p.id
)
UPDATE kb.document_chunk c
SET review_revision_id = m.review_revision_id
FROM chunk_revision_match m
WHERE c.id = m.chunk_id;

-- The legacy index cannot remain while historical revision chunks are
-- normalized to their source parse run.  Drop it before the update; the new
-- review-revision-scoped index below becomes the invariant for review chunks.
DROP INDEX IF EXISTS kb.uq_kb_chunk_parse_run_no;
UPDATE kb.document_chunk c
SET parse_run_id = r.parse_run_id
FROM kb.document_review_revision r
WHERE r.id = c.review_revision_id AND c.parse_run_id IS DISTINCT FROM r.parse_run_id;
ALTER TABLE kb.document_chunk
    ADD CONSTRAINT fk_kb_chunk_review_revision
        FOREIGN KEY (review_revision_id) REFERENCES kb.document_review_revision(id) ON DELETE CASCADE;
CREATE UNIQUE INDEX uq_kb_chunk_review_revision_no
    ON kb.document_chunk(review_revision_id, chunk_no)
    WHERE review_revision_id IS NOT NULL;

UPDATE kb.publication p
SET parse_run_id = r.parse_run_id
FROM kb.document_review_revision r
WHERE r.id = p.review_revision_id AND p.parse_run_id IS DISTINCT FROM r.parse_run_id;

ALTER TABLE kb.document_processing_step ADD COLUMN review_revision_id uuid;
UPDATE kb.document_processing_step s
SET review_revision_id = r.id
FROM kb.document_review_revision r
WHERE (s.parse_run_id = r.parse_run_id OR EXISTS (
      SELECT 1 FROM kb.document_parse_run fake
      WHERE fake.id = s.parse_run_id AND fake.result_jsonb->>'revision' = 'true'
        AND fake.result_jsonb->>'sourceParseRunId' = r.parse_run_id::text
        AND fake.created_at = r.created_at
  ))
   AND s.step_key IN ('CHUNK', 'BM25_INDEX', 'VECTOR_INDEX')
   AND r.status IN ('BUILDING', 'PUBLISHED', 'FAILED');

-- Legacy NULL-parse-run rows were unique per document version. Review steps
-- are now scoped by review_revision_id, so remove that invariant before
-- normalizing the rows to NULL.
DROP INDEX IF EXISTS kb.uq_kb_processing_step_legacy_version;
UPDATE kb.document_processing_step
SET parse_run_id = NULL
WHERE review_revision_id IS NOT NULL
  AND step_key IN ('CHUNK', 'BM25_INDEX', 'VECTOR_INDEX');
ALTER TABLE kb.document_processing_step
    ADD CONSTRAINT fk_kb_processing_step_review_revision
        FOREIGN KEY (review_revision_id) REFERENCES kb.document_review_revision(id) ON DELETE CASCADE;
DROP INDEX IF EXISTS kb.uq_kb_processing_step_parse_run;
CREATE UNIQUE INDEX uq_kb_processing_step_parse_run
    ON kb.document_processing_step(parse_run_id, step_key)
    WHERE parse_run_id IS NOT NULL AND review_revision_id IS NULL;
CREATE UNIQUE INDEX uq_kb_processing_step_review_revision
    ON kb.document_processing_step(review_revision_id, step_key)
    WHERE review_revision_id IS NOT NULL;

-- Large worksheet storage. Values remain immutable; review changes are sparse patches.
CREATE TABLE kb.document_source_table (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES iam.organization(id),
    parse_run_id uuid NOT NULL REFERENCES kb.document_parse_run(id) ON DELETE CASCADE,
    source_node_key uuid NOT NULL REFERENCES kb.document_source_node(source_node_key) ON DELETE CASCADE,
    sheet_key varchar(160) NOT NULL,
    sheet_name varchar(260) NOT NULL,
    row_count integer NOT NULL,
    column_count integer NOT NULL,
    non_empty_count integer NOT NULL,
    UNIQUE (parse_run_id, sheet_key)
);

CREATE TABLE kb.document_source_table_cell (
    source_table_id uuid NOT NULL REFERENCES kb.document_source_table(id) ON DELETE CASCADE,
    row_no integer NOT NULL,
    column_no integer NOT NULL,
    display_value text NOT NULL DEFAULT '',
    source_anchor_jsonb jsonb NOT NULL,
    PRIMARY KEY (source_table_id, row_no, column_no)
);

CREATE TABLE kb.document_review_table_cell_patch (
    review_revision_id uuid NOT NULL REFERENCES kb.document_review_revision(id) ON DELETE CASCADE,
    source_table_id uuid NOT NULL REFERENCES kb.document_source_table(id) ON DELETE CASCADE,
    row_no integer NOT NULL,
    column_no integer NOT NULL,
    confirmed_value text NOT NULL,
    updated_by uuid NOT NULL REFERENCES iam.app_user(id),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (review_revision_id, source_table_id, row_no, column_no)
);

CREATE TABLE kb.document_review_table_row_state (
    review_revision_id uuid NOT NULL REFERENCES kb.document_review_revision(id) ON DELETE CASCADE,
    source_table_id uuid NOT NULL REFERENCES kb.document_source_table(id) ON DELETE CASCADE,
    row_no integer NOT NULL,
    excluded boolean NOT NULL DEFAULT true,
    header boolean NOT NULL DEFAULT false,
    updated_by uuid NOT NULL REFERENCES iam.app_user(id),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (review_revision_id, source_table_id, row_no)
);

-- Historical V26 revisions were represented as fake parse executions. Repoint
-- issue, source-node and parse-block references before removing those runs.
-- The review rows and publication/chunk rows already use the true source run.
UPDATE kb.document_parse_issue issue
SET parse_run_id = source_run.id
FROM kb.document_parse_run fake_run
JOIN kb.document_parse_run source_run
  ON source_run.id = CASE
      WHEN fake_run.result_jsonb->>'sourceParseRunId' ~* '^[0-9a-f-]{36}$'
      THEN (fake_run.result_jsonb->>'sourceParseRunId')::uuid
      ELSE NULL
  END
WHERE fake_run.result_jsonb->>'revision' = 'true'
  AND issue.parse_run_id = fake_run.id;

UPDATE kb.document_parse_issue issue
SET parse_block_id = source_block.id
FROM kb.document_parse_run fake_run
JOIN kb.document_parse_run source_run
  ON source_run.id = CASE
      WHEN fake_run.result_jsonb->>'sourceParseRunId' ~* '^[0-9a-f-]{36}$'
      THEN (fake_run.result_jsonb->>'sourceParseRunId')::uuid
      ELSE NULL
  END
JOIN kb.document_parse_block fake_block
  ON fake_block.parse_run_id = fake_run.id
JOIN kb.document_parse_block source_block
  ON source_block.parse_run_id = source_run.id
 AND source_block.block_no = fake_block.block_no
WHERE fake_run.result_jsonb->>'revision' = 'true'
  AND issue.parse_block_id = fake_block.id;

INSERT INTO kb.document_parse_issue_source_node(parse_issue_id, source_node_key)
SELECT link.parse_issue_id, source_node.source_node_key
FROM kb.document_parse_issue_source_node link
JOIN kb.document_source_node fake_node
  ON fake_node.source_node_key = link.source_node_key
JOIN kb.document_parse_run fake_run
  ON fake_run.id = fake_node.parse_run_id
JOIN kb.document_source_node source_node
  ON source_node.parse_run_id = CASE
      WHEN fake_run.result_jsonb->>'sourceParseRunId' ~* '^[0-9a-f-]{36}$'
      THEN (fake_run.result_jsonb->>'sourceParseRunId')::uuid
      ELSE NULL
  END
 AND source_node.node_no = fake_node.node_no
WHERE fake_run.result_jsonb->>'revision' = 'true'
ON CONFLICT DO NOTHING;

DELETE FROM kb.document_parse_issue_source_node link
USING kb.document_source_node fake_node
JOIN kb.document_parse_run fake_run ON fake_run.id = fake_node.parse_run_id
WHERE link.source_node_key = fake_node.source_node_key
  AND fake_run.result_jsonb->>'revision' = 'true';

DELETE FROM kb.document_source_node node
USING kb.document_parse_run fake_run
WHERE node.parse_run_id = fake_run.id
  AND fake_run.result_jsonb->>'revision' = 'true';

DELETE FROM kb.document_parse_run
WHERE result_jsonb->>'revision' = 'true';

-- Parse issues now point to immutable source nodes and resolution belongs to a review.
ALTER TABLE kb.document_parse_issue DROP COLUMN IF EXISTS parse_block_id;
DROP TABLE kb.document_parse_block;

-- Parse run state is now only the state of the parser execution.
ALTER TABLE kb.document_parse_run DROP CONSTRAINT IF EXISTS document_parse_run_status_check;
UPDATE kb.document_parse_run
SET status = 'SUCCEEDED',
    finished_at = coalesce(finished_at, now())
WHERE status NOT IN ('QUEUED', 'PROCESSING', 'FAILED');
ALTER TABLE kb.document_parse_run
    ADD CONSTRAINT document_parse_run_status_check
    CHECK (status IN ('QUEUED', 'PROCESSING', 'SUCCEEDED', 'FAILED'));

COMMENT ON TABLE kb.document_source_node IS 'Immutable semantic nodes and authoritative source anchors for a parse run';
COMMENT ON TABLE kb.document_review_revision IS 'Editable human-confirmed document revision, independent from parser executions';
COMMENT ON COLUMN kb.document_review_revision.confirmed_text IS 'Server-generated deterministic projection; never accepted from clients';
