-- V1 through V75 are the shared, already-applied migration history.
-- All subsequent workbench / experiment changes are consolidated here.
-- Existing local tables are retained so previously-created experiments, models,
-- model files and observations survive the one-time local history alignment.
-- Flyway runs this file as one PostgreSQL transaction.

-- section: experiment-import-history:begin
ALTER TABLE data.import_job
    ADD COLUMN IF NOT EXISTS hidden_at timestamptz;

CREATE INDEX IF NOT EXISTS idx_data_import_job_visible_owner_created
    ON data.import_job (organization_id, source_owner, created_at DESC)
    WHERE hidden_at IS NULL;

COMMENT ON COLUMN data.import_job.hidden_at IS '实验导入历史软隐藏时间；不删除来源文件、追溯数据或已生成实验';
-- section: experiment-import-history:end

-- section: operation-manual:begin
-- System-wide packaged help documents; user conversations are never persisted here.
CREATE TABLE IF NOT EXISTS ai.operation_manual_index (
    manual_version varchar(160) NOT NULL,
    embedding_model varchar(160) NOT NULL,
    embedding_dimension integer NOT NULL CHECK (embedding_dimension > 0),
    status varchar(16) NOT NULL CHECK (status IN ('BUILDING', 'READY', 'FAILED')),
    chunk_count integer NOT NULL DEFAULT 0,
    error_message varchar(500),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (manual_version, embedding_model, embedding_dimension)
);

CREATE TABLE IF NOT EXISTS ai.operation_manual_chunk (
    manual_version varchar(160) NOT NULL,
    embedding_model varchar(160) NOT NULL,
    embedding_dimension integer NOT NULL,
    chunk_id varchar(160) NOT NULL,
    section_id varchar(120) NOT NULL,
    content text NOT NULL,
    content_hash varchar(64) NOT NULL,
    embedding vector NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (manual_version, embedding_model, embedding_dimension, chunk_id),
    FOREIGN KEY (manual_version, embedding_model, embedding_dimension)
        REFERENCES ai.operation_manual_index(manual_version, embedding_model, embedding_dimension),
    CHECK (vector_dims(embedding) = embedding_dimension)
);

CREATE INDEX IF NOT EXISTS operation_manual_vector_reuse ON ai.operation_manual_chunk
    (embedding_model, embedding_dimension, content_hash);
CREATE INDEX IF NOT EXISTS operation_manual_section ON ai.operation_manual_chunk
    (manual_version, embedding_model, embedding_dimension, section_id);
-- section: operation-manual:end

-- section: optimization-observations:begin
CREATE TABLE IF NOT EXISTS ai.research_candidate_observation_v2 (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES iam.organization(id),
    research_run_id uuid NOT NULL,
    research_candidate_id uuid NOT NULL,
    target_id uuid NOT NULL,
    observed_result_jsonb jsonb NOT NULL,
    created_by uuid NOT NULL REFERENCES iam.app_user(id),
    updated_by uuid NOT NULL REFERENCES iam.app_user(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, id),
    UNIQUE (organization_id, research_run_id, research_candidate_id, target_id),
    FOREIGN KEY (organization_id, research_run_id, research_candidate_id)
        REFERENCES ai.research_candidate_v2(organization_id, research_run_id, id),
    FOREIGN KEY (organization_id, target_id)
        REFERENCES ai.prediction_target(organization_id, id),
    CONSTRAINT research_candidate_observation_value_check CHECK (
        jsonb_typeof(observed_result_jsonb) IN ('number','string')
    )
);

CREATE INDEX IF NOT EXISTS idx_research_candidate_observation_run
    ON ai.research_candidate_observation_v2(organization_id, research_run_id, research_candidate_id, target_id);
-- section: optimization-observations:end

-- section: historical-optimization:begin
CREATE TABLE IF NOT EXISTS ai.research_candidate_history_observation_v2 (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES iam.organization(id),
    research_run_id uuid NOT NULL,
    research_candidate_id uuid NOT NULL,
    field_code varchar(240) NOT NULL,
    observed_value_jsonb jsonb NOT NULL,
    created_by uuid NOT NULL REFERENCES iam.app_user(id),
    updated_by uuid NOT NULL REFERENCES iam.app_user(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, research_run_id, research_candidate_id, field_code),
    FOREIGN KEY (organization_id, research_run_id, research_candidate_id)
        REFERENCES ai.research_candidate_v2(organization_id, research_run_id, id),
    CONSTRAINT research_candidate_history_observation_value_check CHECK (
        jsonb_typeof(observed_value_jsonb) = 'number'
    )
);

CREATE INDEX IF NOT EXISTS idx_research_candidate_history_observation_run
    ON ai.research_candidate_history_observation_v2(organization_id, research_run_id, research_candidate_id);

ALTER TABLE ai.research_candidate_v2
    DROP CONSTRAINT IF EXISTS research_candidate_v2_strategy_check,
    ADD CONSTRAINT research_candidate_v2_strategy_check CHECK (
        search_strategy IN ('CONTROLLED_POOL','CONTROL','CONSERVATIVE','BALANCED','EXPLORATORY','HISTORICAL_BO')
    );
-- section: historical-optimization:end

-- section: modeling-workbench:begin
CREATE TABLE IF NOT EXISTS ai.modeling_workbench_run (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES iam.organization(id),
    created_by uuid NOT NULL REFERENCES iam.app_user(id),
    dataset_id uuid NOT NULL,
    dataset_name varchar(240) NOT NULL,
    idempotency_key varchar(200) NOT NULL,
    request_hash char(64) NOT NULL,
    request_jsonb jsonb NOT NULL,
    selected_x_jsonb jsonb NOT NULL,
    selected_y_jsonb jsonb NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'QUEUED',
    progress smallint NOT NULL DEFAULT 0,
    current_stage varchar(40) NOT NULL DEFAULT 'QUEUED',
    result_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    error_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    model_artifact_key varchar(700),
    model_artifact_sha256 char(64),
    ops_job_id uuid REFERENCES ops.async_job(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    finished_at timestamptz,
    UNIQUE (organization_id, id),
    UNIQUE (organization_id, idempotency_key),
    CONSTRAINT modeling_workbench_run_hash_check CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT modeling_workbench_run_status_check CHECK (status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED','CANCELLED')),
    CONSTRAINT modeling_workbench_run_progress_check CHECK (progress BETWEEN 0 AND 100),
    CONSTRAINT modeling_workbench_run_x_array CHECK (jsonb_typeof(selected_x_jsonb) = 'array'),
    CONSTRAINT modeling_workbench_run_y_object CHECK (jsonb_typeof(selected_y_jsonb) = 'object'),
    CONSTRAINT modeling_workbench_run_request_object CHECK (jsonb_typeof(request_jsonb) = 'object'),
    CONSTRAINT modeling_workbench_run_result_object CHECK (jsonb_typeof(result_jsonb) = 'object'),
    CONSTRAINT modeling_workbench_run_error_object CHECK (jsonb_typeof(error_jsonb) = 'object'),
    CONSTRAINT modeling_workbench_run_artifact_hash_check CHECK (
        model_artifact_sha256 IS NULL OR model_artifact_sha256 ~ '^[0-9a-f]{64}$'
    )
);

CREATE INDEX IF NOT EXISTS idx_modeling_workbench_run_history
    ON ai.modeling_workbench_run(organization_id, created_by, created_at DESC, id);
-- section: modeling-workbench:end

-- section: model-registration:begin
ALTER TABLE ai.artifact ADD COLUMN IF NOT EXISTS workbench_run_id uuid;
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conrelid = 'ai.artifact'::regclass
                     AND conname = 'artifact_organization_id_workbench_run_id_fkey') THEN
        ALTER TABLE ai.artifact
            ADD CONSTRAINT artifact_organization_id_workbench_run_id_fkey
            FOREIGN KEY (organization_id, workbench_run_id)
            REFERENCES ai.modeling_workbench_run(organization_id, id);
    END IF;
END $$;
ALTER TABLE ai.artifact DROP CONSTRAINT IF EXISTS artifact_owner_check;
ALTER TABLE ai.artifact ADD CONSTRAINT artifact_owner_check CHECK (
    training_snapshot_id IS NOT NULL OR training_job_attempt_id IS NOT NULL OR workbench_run_id IS NOT NULL);

ALTER TABLE ai.model_version ADD COLUMN IF NOT EXISTS workbench_run_id uuid;
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conrelid = 'ai.model_version'::regclass
                     AND conname = 'model_version_organization_id_workbench_run_id_fkey') THEN
        ALTER TABLE ai.model_version
            ADD CONSTRAINT model_version_organization_id_workbench_run_id_fkey
            FOREIGN KEY (organization_id, workbench_run_id)
            REFERENCES ai.modeling_workbench_run(organization_id, id);
    END IF;
END $$;
ALTER TABLE ai.model_version ALTER COLUMN training_snapshot_id DROP NOT NULL;
ALTER TABLE ai.model_version ALTER COLUMN training_job_id DROP NOT NULL;
ALTER TABLE ai.model_version DROP CONSTRAINT IF EXISTS model_training_origin_check;
ALTER TABLE ai.model_version ADD CONSTRAINT model_training_origin_check CHECK (
    (workbench_run_id IS NULL AND training_snapshot_id IS NOT NULL AND training_job_id IS NOT NULL)
    OR (workbench_run_id IS NOT NULL AND training_snapshot_id IS NULL AND training_job_id IS NULL));
CREATE UNIQUE INDEX IF NOT EXISTS uq_model_workbench_run ON ai.model_version(organization_id,workbench_run_id)
    WHERE workbench_run_id IS NOT NULL;
-- section: model-registration:end
