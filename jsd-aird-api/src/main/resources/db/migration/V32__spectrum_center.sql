CREATE SCHEMA IF NOT EXISTS spc;

CREATE TABLE spc.chart_category (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES iam.organization(id),
    code varchar(64) NOT NULL,
    name varchar(120) NOT NULL,
    description varchar(500),
    analysis_hint text,
    fields_jsonb jsonb NOT NULL DEFAULT '[]'::jsonb,
    sort_order integer NOT NULL DEFAULT 100,
    system_category boolean NOT NULL DEFAULT false,
    created_by uuid NOT NULL REFERENCES iam.app_user(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, code)
);

CREATE INDEX idx_spc_chart_category_order
    ON spc.chart_category (organization_id, sort_order, name);

CREATE TABLE spc.chart_asset (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES iam.organization(id),
    category_id uuid NOT NULL REFERENCES spc.chart_category(id),
    file_object_id uuid NOT NULL,
    title varchar(260) NOT NULL,
    original_name varchar(260) NOT NULL,
    content_type varchar(160) NOT NULL,
    size_bytes bigint NOT NULL CHECK (size_bytes >= 0),
    sha256 char(64) NOT NULL,
    sample_name varchar(260),
    batch_no varchar(160),
    test_conditions text,
    metadata_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    page_count integer NOT NULL DEFAULT 1 CHECK (page_count > 0),
    status varchar(20) NOT NULL DEFAULT 'READY'
        CHECK (status IN ('READY', 'DELETED')),
    created_by uuid NOT NULL REFERENCES iam.app_user(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, file_object_id)
);

CREATE INDEX idx_spc_chart_asset_list
    ON spc.chart_asset (organization_id, category_id, status, updated_at DESC);

CREATE INDEX idx_spc_chart_asset_search
    ON spc.chart_asset (organization_id, title, original_name, sample_name, batch_no);

CREATE TABLE spc.chat_session (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES iam.organization(id),
    title varchar(260) NOT NULL DEFAULT '新的图谱分析对话',
    created_by uuid NOT NULL REFERENCES iam.app_user(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_spc_chat_session_list
    ON spc.chat_session (organization_id, created_by, updated_at DESC);

CREATE TABLE spc.analysis_run (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES iam.organization(id),
    session_id uuid NOT NULL REFERENCES spc.chat_session(id) ON DELETE CASCADE,
    user_message_id uuid,
    mode varchar(40) NOT NULL DEFAULT 'AI_CHAT'
        CHECK (mode IN ('AI_CHAT', 'CATEGORY_ASSISTED', 'OPTIONAL_SCENARIO')),
    question text NOT NULL,
    chart_ids_jsonb jsonb NOT NULL DEFAULT '[]'::jsonb,
    page_selections_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    selected_categories_jsonb jsonb NOT NULL DEFAULT '[]'::jsonb,
    scenario_template varchar(80),
    status varchar(20) NOT NULL DEFAULT 'QUEUED'
        CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'PARTIAL', 'FAILED', 'CANCELLED')),
    progress smallint NOT NULL DEFAULT 0 CHECK (progress BETWEEN 0 AND 100),
    current_stage varchar(120),
    prompt_version varchar(80),
    model varchar(160),
    result_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    raw_response_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    warning_jsonb jsonb NOT NULL DEFAULT '[]'::jsonb,
    error_message text,
    created_by uuid NOT NULL REFERENCES iam.app_user(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    started_at timestamptz,
    completed_at timestamptz
);

CREATE INDEX idx_spc_analysis_run_session
    ON spc.analysis_run (organization_id, session_id, created_at);

CREATE INDEX idx_spc_analysis_run_status
    ON spc.analysis_run (organization_id, status, created_at DESC);

CREATE TABLE spc.chat_message (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES iam.organization(id),
    session_id uuid NOT NULL REFERENCES spc.chat_session(id) ON DELETE CASCADE,
    analysis_run_id uuid REFERENCES spc.analysis_run(id) ON DELETE SET NULL,
    role varchar(20) NOT NULL CHECK (role IN ('USER', 'ASSISTANT')),
    content text NOT NULL,
    citations_jsonb jsonb NOT NULL DEFAULT '[]'::jsonb,
    result_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    warning_jsonb jsonb NOT NULL DEFAULT '[]'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE spc.analysis_run
    ADD CONSTRAINT fk_spc_analysis_user_message
    FOREIGN KEY (user_message_id) REFERENCES spc.chat_message(id) ON DELETE SET NULL;

CREATE INDEX idx_spc_chat_message_session
    ON spc.chat_message (organization_id, session_id, created_at);

INSERT INTO spc.chart_category (
    id, organization_id, code, name, description, analysis_hint, fields_jsonb,
    sort_order, system_category, created_by
)
SELECT gen_random_uuid(), o.id, seed.code, seed.name, seed.description, seed.analysis_hint,
       seed.fields_jsonb::jsonb, seed.sort_order, true, u.id
FROM iam.organization o
JOIN LATERAL (
    SELECT u0.id
    FROM iam.app_user u0
    WHERE u0.organization_id = o.id AND u0.dictionary_admin = true
    ORDER BY u0.id
    LIMIT 1
) u ON TRUE
CROSS JOIN (VALUES
    ('IR', '红外 IR', '峰位、峰形和测试条件的视觉观察', '峰检测、相似度、批次和成分差异', '[{"key":"peakPositions","label":"峰位"},{"key":"spectralLibraryHitRate","label":"谱库命中率"}]', 10),
    ('UV', '紫外 UV', '波长范围、吸收值、浓度和曲线', '吸收峰、曲线叠加和样品对比', '[{"key":"wavelengthRange","label":"波长范围"},{"key":"concentration","label":"浓度"}]', 20),
    ('HPLC_GPC', '液相 HPLC/GPC', '保留时间、分子量和色谱曲线', '峰形、分子量、分布和异常提示', '[{"key":"methodFile","label":"方法文件"},{"key":"molecularWeight","label":"Mn / Mw"}]', 30),
    ('GC', '气相 GC', '保留时间、峰面积和方法文件', '成分和批次对比', '[{"key":"retentionTimes","label":"保留时间"},{"key":"peakAreas","label":"峰面积"}]', 40),
    ('PARTICLE_SIZE', '纳米粒径', 'D10、D50、D90、平均粒径、PDI和分布曲线', '分布对比和异常识别', '[{"key":"d10d50d90","label":"D10 / D50 / D90"},{"key":"pdi","label":"PDI"}]', 50),
    ('MECHANICAL', '拉伸/力学', '最大力、强度、伸长率、弹性系数和原始曲线', '多次测试汇总和曲线差异', '[{"key":"strength","label":"强度"},{"key":"elongation","label":"伸长率"}]', 60)
) AS seed(code, name, description, analysis_hint, fields_jsonb, sort_order)
WHERE NOT EXISTS (
    SELECT 1 FROM spc.chart_category existing
    WHERE existing.organization_id = o.id AND existing.code = seed.code
);
