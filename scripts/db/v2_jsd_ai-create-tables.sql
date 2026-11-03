-- jsd_ai 数据库全部建表语句（仅 CREATE TABLE，字段带中文描述）
-- 来源库: jsd_ai@127.0.0.1:5432 (PostgreSQL 15.19, Flyway 迁移记录至 V96)
-- 表数量: 212  字段数量: 2893
-- 描述来源: 数据库列注释优先，其次按命名约定补齐（docs/db/tools/column_comments.py）
-- 索引/外键/视图/触发器见同目录 jsd_ai-schema.sql；本文件依赖其头部 CREATE SCHEMA / CREATE EXTENSION
-- 重新生成: py docs/db/tools/gen_create_table_ddl.py

-- ==================== schema: ai ====================

CREATE TABLE ai.ai_call_audit (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    actor_id uuid NOT NULL, -- 操作者ID
    conversation_id uuid, -- 会话ID
    request_kind character varying(40) NOT NULL, -- 请求类别
    model character varying(160), -- 模型
    prompt_version character varying(80) NOT NULL, -- 提示词版本
    request_sha256 character(64) NOT NULL, -- 请求哈希（SHA-256）
    response_sha256 character(64), -- 响应哈希（SHA-256）
    input_tokens integer DEFAULT 0 NOT NULL, -- 输入Token数
    output_tokens integer DEFAULT 0 NOT NULL, -- 输出Token数
    total_tokens integer DEFAULT 0 NOT NULL, -- 总Token数
    status character varying(24) NOT NULL, -- AI调用审计状态
    error_message character varying(500), -- 错误信息
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    CONSTRAINT ai_call_audit_status_check CHECK (((status)::text = ANY (ARRAY[('SUCCEEDED'::character varying)::text, ('FAILED'::character varying)::text])))
);

CREATE TABLE ai.artifact (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    artifact_type character varying(40) NOT NULL, -- 产物类型
    training_snapshot_id uuid, -- 训练快照ID
    training_job_attempt_id uuid, -- 训练尝试ID
    object_key character varying(700) NOT NULL, -- 对象存储键
    sha256 character(64) NOT NULL, -- 哈希值（SHA-256）
    size_bytes bigint NOT NULL, -- 大小（字节）
    metadata_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 元数据（JSON）
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    media_type character varying(160), -- 媒体类型
    content_addressed boolean DEFAULT true NOT NULL, -- 是否内容寻址
    workbench_run_id uuid, -- 工作台运行ID
    CONSTRAINT artifact_hash_check CHECK ((sha256 ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT artifact_metadata_object CHECK ((jsonb_typeof(metadata_jsonb) = 'object'::text)),
    CONSTRAINT artifact_owner_check CHECK (((training_snapshot_id IS NOT NULL) OR (training_job_attempt_id IS NOT NULL) OR (workbench_run_id IS NOT NULL))),
    CONSTRAINT artifact_size_bytes_check CHECK ((size_bytes >= 0))
);

CREATE TABLE ai.assistant_conversation (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    title character varying(240), -- 会话标题
    created_by uuid NOT NULL, -- 创建人ID
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    updated_at timestamp with time zone DEFAULT now() NOT NULL, -- 更新时间
    summary text, -- 会话摘要
    summary_version character varying(40), -- 摘要版本
    summary_token_count integer DEFAULT 0 NOT NULL, -- 摘要Token数
    last_summarized_message_id uuid, -- 最近摘要消息ID
    title_source character varying(24) DEFAULT 'FIRST_QUESTION'::character varying NOT NULL, -- 标题来源
    conversation_type character varying(40) DEFAULT 'AI_QA'::character varying NOT NULL, -- 会话类型
    scope_snapshot_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL -- 范围快照（JSON）
);

CREATE TABLE ai.assistant_message (
    id uuid NOT NULL, -- 主键ID
    conversation_id uuid NOT NULL, -- 会话ID
    role character varying(16) NOT NULL, -- 消息角色
    content text NOT NULL, -- 消息内容
    citations_jsonb jsonb DEFAULT '[]'::jsonb NOT NULL, -- 引用列表（JSON）
    warnings_jsonb jsonb DEFAULT '[]'::jsonb NOT NULL, -- 警告列表（JSON）
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    query_plan_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 查询计划（JSON）
    retrieval_trace_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 检索轨迹（JSON）
    CONSTRAINT assistant_message_role_check CHECK (((role)::text = ANY (ARRAY[('USER'::character varying)::text, ('ASSISTANT'::character varying)::text])))
);

CREATE TABLE ai.configuration_command_receipt (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    operation character varying(120) NOT NULL, -- 操作
    idempotency_key character varying(200) NOT NULL, -- 幂等键
    request_hash character(64) NOT NULL, -- 请求哈希
    resource_type character varying(80) NOT NULL, -- 资源类型
    resource_id uuid, -- 资源ID
    response_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 响应内容（JSON）
    created_by uuid NOT NULL, -- 创建人ID
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    CONSTRAINT configuration_receipt_hash_check CHECK ((request_hash ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT configuration_receipt_response_object CHECK ((jsonb_typeof(response_jsonb) = 'object'::text))
);

CREATE TABLE ai.context_snapshot (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    actor_id uuid NOT NULL, -- 操作者ID
    context_profile character varying(120) NOT NULL, -- 上下文配置
    subject_type character varying(80) NOT NULL, -- 主体类型
    subject_id uuid, -- 主体ID
    permission_scope_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 权限范围（JSON）
    visible_data_summary_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 可见数据摘要（JSON）
    snapshot_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 快照（JSON）
    model character varying(160), -- 模型
    prompt_version character varying(80), -- 提示词版本
    created_at timestamp with time zone DEFAULT now() NOT NULL -- 创建时间
);

CREATE TABLE ai.data_review (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    review_type character varying(32) NOT NULL, -- 审核类型
    eligibility_id uuid, -- 准入判定ID
    identity_issue_id uuid, -- 标识问题ID
    status character varying(24) DEFAULT 'OPEN'::character varying NOT NULL, -- 数据审核状态
    priority smallint DEFAULT 100 NOT NULL, -- 优先级
    assigned_to uuid, -- 指派给
    revision bigint DEFAULT 0 NOT NULL, -- 修订号
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    updated_at timestamp with time zone DEFAULT now() NOT NULL, -- 更新时间
    reason_code character varying(80), -- 原因码
    evidence_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 证据（JSON）
    CONSTRAINT data_review_evidence_object CHECK ((jsonb_typeof(evidence_jsonb) = 'object'::text)),
    CONSTRAINT data_review_status_check CHECK (((status)::text = ANY (ARRAY[('OPEN'::character varying)::text, ('IN_REVIEW'::character varying)::text, ('RESOLVED'::character varying)::text, ('CANCELLED'::character varying)::text]))),
    CONSTRAINT data_review_subject_check CHECK (((((eligibility_id IS NOT NULL))::integer + ((identity_issue_id IS NOT NULL))::integer) = 1)),
    CONSTRAINT data_review_type_check CHECK (((review_type)::text = ANY (ARRAY[('ELIGIBILITY'::character varying)::text, ('IDENTITY'::character varying)::text, ('UNKNOWN_MATERIAL'::character varying)::text, ('OUTLIER'::character varying)::text])))
);

CREATE TABLE ai.data_review_decision (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    data_review_id uuid NOT NULL, -- 数据审核ID
    decision character varying(32) NOT NULL, -- 决策
    reason text NOT NULL, -- 原因
    evidence_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 证据（JSON）
    decided_by uuid NOT NULL, -- 决策人ID
    decided_at timestamp with time zone DEFAULT now() NOT NULL, -- 决策时间
    decision_no integer, -- 决策单号
    request_id character varying(160), -- 请求ID
    idempotency_key character varying(200), -- 幂等键
    before_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 变更前数据（JSON）
    after_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 变更后数据（JSON）
    CONSTRAINT data_review_decision_after_object CHECK ((jsonb_typeof(after_jsonb) = 'object'::text)),
    CONSTRAINT data_review_decision_before_object CHECK ((jsonb_typeof(before_jsonb) = 'object'::text)),
    CONSTRAINT data_review_decision_check CHECK (((decision)::text = ANY (ARRAY[('KEEP'::character varying)::text, ('EXCLUDE'::character varying)::text, ('INCLUDE'::character varying)::text, ('MERGE'::character varying)::text, ('KEEP_SEPARATE'::character varying)::text, ('REMAP'::character varying)::text, ('DISMISS'::character varying)::text]))),
    CONSTRAINT data_review_decision_evidence_object CHECK ((jsonb_typeof(evidence_jsonb) = 'object'::text))
);

CREATE TABLE ai.eligibility_evaluation_run (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    target_id uuid NOT NULL, -- 目标ID
    target_version_id uuid NOT NULL, -- 目标版本ID
    input_scheme_id uuid NOT NULL, -- 输入方案ID
    source_mapping_versions_jsonb jsonb DEFAULT '[]'::jsonb NOT NULL, -- 来源映射版本列表（JSON）
    material_dictionary_version_id uuid, -- 物料字典版本ID
    modeling_policy_version_id uuid NOT NULL, -- 建模策略版本ID
    rule_fingerprint character(64) NOT NULL, -- 规则指纹
    async_job_id uuid, -- 异步任务ID
    status character varying(24) DEFAULT 'QUEUED'::character varying NOT NULL, -- 准入评估状态
    total_samples bigint, -- 样品总数
    trainable_count bigint, -- 可训练样本数
    excluded_count bigint, -- 排除数量
    review_required_count bigint, -- 待审核数量
    funnel_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 漏斗数据（JSON）
    error_code character varying(100), -- 错误码
    error_message text, -- 错误信息
    requested_by uuid, -- 请求人ID
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    started_at timestamp with time zone, -- 开始时间
    finished_at timestamp with time zone, -- 完成时间
    updated_at timestamp with time zone DEFAULT now() NOT NULL, -- 更新时间
    CONSTRAINT eligibility_run_funnel_object CHECK ((jsonb_typeof(funnel_jsonb) = 'object'::text)),
    CONSTRAINT eligibility_run_hash_check CHECK ((rule_fingerprint ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT eligibility_run_mapping_array CHECK ((jsonb_typeof(source_mapping_versions_jsonb) = 'array'::text)),
    CONSTRAINT eligibility_run_status_check CHECK (((status)::text = ANY (ARRAY[('QUEUED'::character varying)::text, ('RUNNING'::character varying)::text, ('SUCCEEDED'::character varying)::text, ('FAILED'::character varying)::text, ('CANCELLED'::character varying)::text])))
);

CREATE TABLE ai.employee_scheduled_task (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    task_code character varying(64) NOT NULL, -- 任务编码
    task_name character varying(120) NOT NULL, -- 任务名称
    description character varying(500) DEFAULT ''::character varying NOT NULL, -- 员工计划任务描述
    cadence character varying(16) NOT NULL, -- 执行周期
    day_of_week smallint, -- 星期
    run_time time without time zone NOT NULL, -- 运行时间
    timezone character varying(64) DEFAULT 'Asia/Shanghai'::character varying NOT NULL, -- 时区
    enabled boolean DEFAULT true NOT NULL, -- 是否启用
    next_run_at timestamp with time zone, -- 下次运行时间
    last_run_at timestamp with time zone, -- 最近运行时间
    updated_by uuid, -- 更新人ID
    version bigint DEFAULT 0 NOT NULL, -- 员工计划任务版本号
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    updated_at timestamp with time zone DEFAULT now() NOT NULL, -- 更新时间
    CONSTRAINT employee_scheduled_task_cadence_check CHECK (((cadence)::text = ANY (ARRAY[('DAILY'::character varying)::text, ('WEEKLY'::character varying)::text]))),
    CONSTRAINT employee_scheduled_task_check CHECK (((((cadence)::text = 'DAILY'::text) AND (day_of_week IS NULL)) OR (((cadence)::text = 'WEEKLY'::text) AND (day_of_week IS NOT NULL)))),
    CONSTRAINT employee_scheduled_task_day_of_week_check CHECK (((day_of_week >= 1) AND (day_of_week <= 7)))
);

CREATE TABLE ai.experiment_analysis (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    created_by uuid NOT NULL, -- 创建人ID
    conversation_id uuid, -- 会话ID
    analysis_type character varying(40) NOT NULL, -- 分析类型
    primary_experiment_id uuid NOT NULL, -- 主实验ID
    experiment_ids_jsonb jsonb DEFAULT '[]'::jsonb NOT NULL, -- 实验ID列表（JSON）
    parameters_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 参数（JSON）
    source_versions_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 来源版本列表（JSON）
    context_snapshot_id uuid, -- 上下文快照ID
    async_job_id uuid, -- 异步任务ID
    result_id uuid, -- 结果ID
    parent_analysis_id uuid, -- 父分析ID
    status character varying(20) NOT NULL, -- 实验分析状态
    idempotency_key character varying(160) NOT NULL, -- 幂等键
    error_message text, -- 错误信息
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    updated_at timestamp with time zone DEFAULT now() NOT NULL, -- 更新时间
    finished_at timestamp with time zone, -- 完成时间
    CONSTRAINT experiment_analysis_analysis_type_check CHECK (((analysis_type)::text = ANY (ARRAY[('SINGLE_REVIEW'::character varying)::text, ('MULTI_COMPARE'::character varying)::text, ('FAILURE_DIAGNOSIS'::character varying)::text, ('SIMILAR_SEARCH'::character varying)::text, ('FORMULA_PERFORMANCE'::character varying)::text, ('FOLLOW_UP'::character varying)::text, ('NEXT_EXPERIMENT_DRAFT'::character varying)::text]))),
    CONSTRAINT experiment_analysis_status_check CHECK (((status)::text = ANY (ARRAY[('QUEUED'::character varying)::text, ('RUNNING'::character varying)::text, ('SUCCEEDED'::character varying)::text, ('FAILED'::character varying)::text, ('CANCELLED'::character varying)::text, ('STALE'::character varying)::text])))
);

CREATE TABLE ai.fact_projection_receipt (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    source_type character varying(24) NOT NULL, -- 来源类型
    source_event_id uuid NOT NULL, -- 来源事件ID
    source_business_key character varying(320) NOT NULL, -- 来源业务键
    source_sequence bigint NOT NULL, -- 来源序号
    event_type character varying(100) NOT NULL, -- 事件类型
    payload_hash character(64) NOT NULL, -- 载荷哈希
    status character varying(24) DEFAULT 'PROCESSING'::character varying NOT NULL, -- 事实投影回执状态
    attempt_count integer DEFAULT 1 NOT NULL, -- 尝试次数
    error_message text, -- 错误信息
    processed_at timestamp with time zone, -- 处理时间
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    updated_at timestamp with time zone DEFAULT now() NOT NULL, -- 更新时间
    CONSTRAINT fact_projection_payload_hash_check CHECK ((payload_hash ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT fact_projection_source_check CHECK (((source_type)::text = ANY (ARRAY[('DATA_CENTER'::character varying)::text, ('EXPERIMENT'::character varying)::text]))),
    CONSTRAINT fact_projection_status_check CHECK (((status)::text = ANY (ARRAY[('PROCESSING'::character varying)::text, ('COMPLETED'::character varying)::text, ('FAILED'::character varying)::text, ('STALE'::character varying)::text])))
);

CREATE TABLE ai.input_field (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    field_code character varying(160) NOT NULL, -- 字段编码
    name character varying(240) NOT NULL, -- 输入字段名称
    value_type character varying(24) NOT NULL, -- 取值类型
    availability_stage character varying(32) NOT NULL, -- 可用阶段
    status character varying(24) DEFAULT 'DRAFT'::character varying NOT NULL, -- 输入字段状态
    current_version_id uuid, -- 当前版本ID
    revision bigint DEFAULT 0 NOT NULL, -- 修订号
    created_by uuid NOT NULL, -- 创建人ID
    updated_by uuid NOT NULL, -- 更新人ID
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    updated_at timestamp with time zone DEFAULT now() NOT NULL, -- 更新时间
    CONSTRAINT input_field_stage_check CHECK (((availability_stage)::text = ANY (ARRAY[('PRE_EXPERIMENT'::character varying)::text, ('POST_EXPERIMENT'::character varying)::text]))),
    CONSTRAINT input_field_status_check CHECK (((status)::text = ANY (ARRAY[('DRAFT'::character varying)::text, ('ACTIVE'::character varying)::text, ('PAUSED'::character varying)::text, ('RETIRED'::character varying)::text]))),
    CONSTRAINT input_field_value_type_check CHECK (((value_type)::text = ANY (ARRAY[('NUMBER'::character varying)::text, ('STRING'::character varying)::text, ('BOOLEAN'::character varying)::text, ('CATEGORY'::character varying)::text, ('COMPOSITION'::character varying)::text])))
);

CREATE TABLE ai.input_field_version (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    input_field_id uuid NOT NULL, -- 输入字段ID
    version_no integer NOT NULL, -- 版本号
    status character varying(24) DEFAULT 'DRAFT'::character varying NOT NULL, -- 输入字段版本状态
    value_type character varying(24) NOT NULL, -- 取值类型
    unit character varying(80), -- 单位
    availability_stage character varying(32) NOT NULL, -- 可用阶段
    standard_field_dictionary_id uuid, -- 标准字段字典ID
    definition_jsonb jsonb NOT NULL, -- 定义（JSON）
    preprocessing_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 预处理配置（JSON）
    config_hash character(64) NOT NULL, -- 配置哈希
    published_at timestamp with time zone, -- 发布时间
    created_by uuid NOT NULL, -- 创建人ID
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    CONSTRAINT input_field_definition_object CHECK ((jsonb_typeof(definition_jsonb) = 'object'::text)),
    CONSTRAINT input_field_preprocess_object CHECK ((jsonb_typeof(preprocessing_jsonb) = 'object'::text)),
    CONSTRAINT input_field_version_hash_check CHECK ((config_hash ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT input_field_version_stage_check CHECK (((availability_stage)::text = ANY (ARRAY[('PRE_EXPERIMENT'::character varying)::text, ('POST_EXPERIMENT'::character varying)::text]))),
    CONSTRAINT input_field_version_status_check CHECK (((status)::text = ANY (ARRAY[('DRAFT'::character varying)::text, ('PUBLISHED'::character varying)::text, ('RETIRED'::character varying)::text]))),
    CONSTRAINT input_field_version_value_type_check CHECK (((value_type)::text = ANY (ARRAY[('NUMBER'::character varying)::text, ('STRING'::character varying)::text, ('BOOLEAN'::character varying)::text, ('CATEGORY'::character varying)::text, ('COMPOSITION'::character varying)::text]))),
    CONSTRAINT input_field_version_version_no_check CHECK ((version_no > 0))
);

CREATE TABLE ai.input_scheme (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    target_id uuid NOT NULL, -- 目标ID
    target_version_id uuid NOT NULL, -- 目标版本ID
    material_dictionary_version_id uuid, -- 物料字典版本ID
    scheme_code character varying(160) NOT NULL, -- 方案编码
    name character varying(240) NOT NULL, -- 输入方案名称
    version_no integer NOT NULL, -- 版本号
    status character varying(24) DEFAULT 'DRAFT'::character varying NOT NULL, -- 输入方案状态
    preprocessing_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 预处理配置（JSON）
    config_hash character(64) NOT NULL, -- 配置哈希
    revision bigint DEFAULT 0 NOT NULL, -- 修订号
    frozen_at timestamp with time zone, -- 冻结时间
    created_by uuid NOT NULL, -- 创建人ID
    updated_by uuid NOT NULL, -- 更新人ID
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    updated_at timestamp with time zone DEFAULT now() NOT NULL, -- 更新时间
    CONSTRAINT input_scheme_hash_check CHECK ((config_hash ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT input_scheme_preprocess_object CHECK ((jsonb_typeof(preprocessing_jsonb) = 'object'::text)),
    CONSTRAINT input_scheme_status_check CHECK (((status)::text = ANY (ARRAY[('DRAFT'::character varying)::text, ('FROZEN'::character varying)::text, ('RETIRED'::character varying)::text]))),
    CONSTRAINT input_scheme_version_no_check CHECK ((version_no > 0))
);

CREATE TABLE ai.input_scheme_field (
    organization_id uuid NOT NULL, -- 组织ID
    input_scheme_id uuid NOT NULL, -- 输入方案ID
    input_field_version_id uuid NOT NULL, -- 输入字段版本ID
    required boolean NOT NULL, -- 是否必填
    ordinal integer NOT NULL, -- 序号
    override_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 覆盖配置（JSON）
    CONSTRAINT input_scheme_field_ordinal_check CHECK ((ordinal >= 0)),
    CONSTRAINT input_scheme_field_override_object CHECK ((jsonb_typeof(override_jsonb) = 'object'::text))
);

CREATE TABLE ai.material_dictionary_version (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    dictionary_code character varying(160) NOT NULL, -- 字典编码
    version_no integer NOT NULL, -- 版本号
    status character varying(24) NOT NULL, -- 物料字典版本状态
    vocabulary_jsonb jsonb NOT NULL, -- 词表（JSON）
    encoder_jsonb jsonb NOT NULL, -- 编码器配置（JSON）
    dictionary_hash character(64) NOT NULL, -- 字典哈希
    revision bigint DEFAULT 0 NOT NULL, -- 修订号
    frozen_at timestamp with time zone, -- 冻结时间
    created_by uuid NOT NULL, -- 创建人ID
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    CONSTRAINT material_dictionary_encoder_object CHECK ((jsonb_typeof(encoder_jsonb) = 'object'::text)),
    CONSTRAINT material_dictionary_hash_check CHECK ((dictionary_hash ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT material_dictionary_status_check CHECK (((status)::text = ANY (ARRAY[('DRAFT'::character varying)::text, ('FROZEN'::character varying)::text, ('RETIRED'::character varying)::text]))),
    CONSTRAINT material_dictionary_version_version_no_check CHECK ((version_no > 0)),
    CONSTRAINT material_dictionary_vocabulary_array CHECK ((jsonb_typeof(vocabulary_jsonb) = 'array'::text))
);

CREATE TABLE ai.model_feedback (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    model_version_id uuid NOT NULL, -- 模型版本ID
    target_id uuid NOT NULL, -- 目标ID
    experiment_id uuid NOT NULL, -- 实验ID
    experiment_version_id uuid NOT NULL, -- 实验版本ID
    prediction_record_id uuid, -- 预测记录ID
    research_candidate_id uuid, -- 研究候选ID
    predicted_result_jsonb jsonb NOT NULL, -- 预测结果（JSON）
    observed_result_jsonb jsonb NOT NULL, -- 观测结果（JSON）
    residual_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 残差（JSON）
    status character varying(24) DEFAULT 'RECORDED'::character varying NOT NULL, -- 模型反馈状态
    content_hash character(64) NOT NULL, -- 内容哈希
    created_by uuid NOT NULL, -- 创建人ID
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    target_version_id uuid, -- 目标版本ID
    source_mapping_version_id uuid, -- 来源映射版本ID
    sample_revision_id uuid, -- 样品修订ID
    comparison_status character varying(24), -- 比对状态
    reason_code character varying(100), -- 原因码
    observation_refs_jsonb jsonb DEFAULT '[]'::jsonb NOT NULL, -- 观测引用（JSON）
    metrics_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 指标（JSON）
    prediction_evidence_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 预测证据（JSON）
    projection_event_id uuid, -- 投影事件ID
    CONSTRAINT model_feedback_comparison_check CHECK (((comparison_status IS NULL) OR ((comparison_status)::text = ANY (ARRAY[('COMPARABLE'::character varying)::text, ('EXCLUDED'::character varying)::text, ('NOT_CURRENT'::character varying)::text])))),
    CONSTRAINT model_feedback_hash_check CHECK ((content_hash ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT model_feedback_metrics_object CHECK ((jsonb_typeof(metrics_jsonb) = 'object'::text)),
    CONSTRAINT model_feedback_observation_refs_array CHECK ((jsonb_typeof(observation_refs_jsonb) = 'array'::text)),
    CONSTRAINT model_feedback_observed_object CHECK ((jsonb_typeof(observed_result_jsonb) = 'object'::text)),
    CONSTRAINT model_feedback_prediction_evidence_object CHECK ((jsonb_typeof(prediction_evidence_jsonb) = 'object'::text)),
    CONSTRAINT model_feedback_prediction_object CHECK ((jsonb_typeof(predicted_result_jsonb) = 'object'::text)),
    CONSTRAINT model_feedback_residual_object CHECK ((jsonb_typeof(residual_jsonb) = 'object'::text)),
    CONSTRAINT model_feedback_status_check CHECK (((status)::text = ANY (ARRAY[('RECORDED'::character varying)::text, ('QUALIFIED'::character varying)::text, ('EXCLUDED'::character varying)::text])))
);

CREATE TABLE ai.model_feedback_projection_receipt (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    experiment_event_id uuid NOT NULL, -- 实验事件ID
    experiment_id uuid NOT NULL, -- 实验ID
    experiment_version_id uuid NOT NULL, -- 实验版本ID
    event_type character varying(100) NOT NULL, -- 事件类型
    payload_hash character(64) NOT NULL, -- 载荷哈希
    status character varying(24) DEFAULT 'PROCESSING'::character varying NOT NULL, -- 反馈投影回执状态
    attempt_count integer DEFAULT 1 NOT NULL, -- 尝试次数
    feedback_count integer DEFAULT 0 NOT NULL, -- 反馈数量
    error_message text, -- 错误信息
    processed_at timestamp with time zone, -- 处理时间
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    updated_at timestamp with time zone DEFAULT now() NOT NULL, -- 更新时间
    CONSTRAINT model_feedback_receipt_hash_check CHECK ((payload_hash ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT model_feedback_receipt_status_check CHECK (((status)::text = ANY (ARRAY[('PROCESSING'::character varying)::text, ('COMPLETED'::character varying)::text, ('FAILED'::character varying)::text, ('STALE'::character varying)::text])))
);

CREATE TABLE ai.model_release (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    target_id uuid NOT NULL, -- 目标ID
    model_version_id uuid NOT NULL, -- 模型版本ID
    previous_model_version_id uuid, -- 上一模型版本ID
    action character varying(24) NOT NULL, -- 操作
    expected_previous_revision bigint, -- 期望的前一修订号
    reason text NOT NULL, -- 原因
    actor_id uuid NOT NULL, -- 操作者ID
    request_id character varying(160) NOT NULL, -- 请求ID
    idempotency_key character varying(200) NOT NULL, -- 幂等键
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    CONSTRAINT model_release_action_check CHECK (((action)::text = ANY (ARRAY[('ACTIVATE'::character varying)::text, ('PAUSE'::character varying)::text, ('ROLLBACK'::character varying)::text, ('REPLACE'::character varying)::text])))
);

CREATE TABLE ai.model_version (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    target_id uuid NOT NULL, -- 目标ID
    target_version_id uuid NOT NULL, -- 目标版本ID
    input_scheme_id uuid NOT NULL, -- 输入方案ID
    training_snapshot_id uuid, -- 训练快照ID
    training_job_id uuid, -- 训练任务ID
    model_artifact_id uuid, -- 模型产物ID
    model_type character varying(80) NOT NULL, -- 模型类型
    status character varying(24) DEFAULT 'CANDIDATE'::character varying NOT NULL, -- 模型版本状态
    metrics_jsonb jsonb NOT NULL, -- 指标（JSON）
    applicability_domain_jsonb jsonb NOT NULL, -- 适用范围域（JSON）
    model_card_jsonb jsonb NOT NULL, -- 模型卡（JSON）
    contract_version character varying(40) DEFAULT 'formula-model.v2'::character varying NOT NULL, -- 契约版本
    model_hash character(64) NOT NULL, -- 模型哈希
    revision bigint DEFAULT 0 NOT NULL, -- 修订号
    created_by uuid NOT NULL, -- 创建人ID
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    updated_at timestamp with time zone DEFAULT now() NOT NULL, -- 更新时间
    version_no integer, -- 版本号
    data_nature character varying(24) DEFAULT 'REAL'::character varying NOT NULL, -- 数据性质
    production_eligible boolean DEFAULT false NOT NULL, -- 是否可投产
    comparison_status character varying(32) DEFAULT 'NOT_COMPARABLE'::character varying NOT NULL, -- 比对状态
    rejection_reasons_jsonb jsonb DEFAULT '[]'::jsonb NOT NULL, -- 驳回原因列表（JSON）
    domain_policy_version_id uuid, -- 领域策略版本ID
    workbench_run_id uuid, -- 工作台运行ID
    CONSTRAINT model_card_object CHECK ((jsonb_typeof(model_card_jsonb) = 'object'::text)),
    CONSTRAINT model_domain_object CHECK ((jsonb_typeof(applicability_domain_jsonb) = 'object'::text)),
    CONSTRAINT model_hash_check CHECK ((model_hash ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT model_metrics_object CHECK ((jsonb_typeof(metrics_jsonb) = 'object'::text)),
    CONSTRAINT model_training_origin_check CHECK ((((workbench_run_id IS NULL) AND (training_snapshot_id IS NOT NULL) AND (training_job_id IS NOT NULL)) OR ((workbench_run_id IS NOT NULL) AND (training_snapshot_id IS NULL) AND (training_job_id IS NULL)))),
    CONSTRAINT model_version_artifact_required CHECK ((((status)::text = 'REJECTED'::text) OR (model_artifact_id IS NOT NULL))),
    CONSTRAINT model_version_comparison_check CHECK (((comparison_status)::text = ANY (ARRAY[('BETTER'::character varying)::text, ('WORSE'::character varying)::text, ('EQUIVALENT'::character varying)::text, ('NOT_COMPARABLE'::character varying)::text]))),
    CONSTRAINT model_version_data_nature_check CHECK (((data_nature)::text = ANY (ARRAY[('REAL'::character varying)::text, ('SYNTHETIC'::character varying)::text]))),
    CONSTRAINT model_version_reasons_array CHECK ((jsonb_typeof(rejection_reasons_jsonb) = 'array'::text)),
    CONSTRAINT model_version_status_check CHECK (((status)::text = ANY (ARRAY[('CANDIDATE'::character varying)::text, ('ACTIVE'::character varying)::text, ('PAUSED'::character varying)::text, ('RETIRED'::character varying)::text, ('REJECTED'::character varying)::text])))
);

CREATE TABLE ai.modeling_policy_version (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    target_id uuid NOT NULL, -- 目标ID
    version_no integer NOT NULL, -- 版本号
    status character varying(24) NOT NULL, -- 建模策略版本状态
    qualification_jsonb jsonb NOT NULL, -- 资质信息（JSON）
    validation_jsonb jsonb NOT NULL, -- 校验结果（JSON）
    training_jsonb jsonb NOT NULL, -- 训练配置（JSON）
    policy_hash character(64) NOT NULL, -- 策略哈希
    published_at timestamp with time zone, -- 发布时间
    created_by uuid NOT NULL, -- 创建人ID
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    kind character varying(24) DEFAULT 'TRAINING'::character varying NOT NULL, -- 建模策略版本种类
    configuration_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 配置（JSON）
    revision bigint DEFAULT 0 NOT NULL, -- 修订号
    published_by uuid, -- 发布人ID
    CONSTRAINT modeling_policy_configuration_object CHECK ((jsonb_typeof(configuration_jsonb) = 'object'::text)),
    CONSTRAINT modeling_policy_hash_check CHECK ((policy_hash ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT modeling_policy_kind_check CHECK (((kind)::text = ANY (ARRAY[('ELIGIBILITY'::character varying)::text, ('PREPROCESSING'::character varying)::text, ('TRAINING'::character varying)::text, ('DOMAIN'::character varying)::text, ('QUALITY'::character varying)::text]))),
    CONSTRAINT modeling_policy_qualification_object CHECK ((jsonb_typeof(qualification_jsonb) = 'object'::text)),
    CONSTRAINT modeling_policy_status_check CHECK (((status)::text = ANY (ARRAY[('DRAFT'::character varying)::text, ('PUBLISHED'::character varying)::text, ('RETIRED'::character varying)::text]))),
    CONSTRAINT modeling_policy_training_object CHECK ((jsonb_typeof(training_jsonb) = 'object'::text)),
    CONSTRAINT modeling_policy_validation_object CHECK ((jsonb_typeof(validation_jsonb) = 'object'::text)),
    CONSTRAINT modeling_policy_version_version_no_check CHECK ((version_no > 0))
);

CREATE TABLE ai.modeling_workbench_run (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    created_by uuid NOT NULL, -- 创建人ID
    dataset_id uuid NOT NULL, -- 数据集ID
    dataset_name character varying(240) NOT NULL, -- 数据集名称
    idempotency_key character varying(200) NOT NULL, -- 幂等键
    request_hash character(64) NOT NULL, -- 请求哈希
    request_jsonb jsonb NOT NULL, -- 请求内容（JSON）
    selected_x_jsonb jsonb NOT NULL, -- 选定的X轴数据（JSON）
    selected_y_jsonb jsonb NOT NULL, -- 选定的Y轴数据（JSON）
    status character varying(24) DEFAULT 'QUEUED'::character varying NOT NULL, -- 建模工作台运行状态
    progress smallint DEFAULT 0 NOT NULL, -- 进度
    current_stage character varying(40) DEFAULT 'QUEUED'::character varying NOT NULL, -- 当前阶段
    result_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 结果（JSON）
    error_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 错误详情（JSON）
    model_artifact_key character varying(700), -- 模型产物键
    model_artifact_sha256 character(64), -- 模型产物哈希（SHA-256）
    ops_job_id uuid, -- 运维任务ID
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    updated_at timestamp with time zone DEFAULT now() NOT NULL, -- 更新时间
    finished_at timestamp with time zone, -- 完成时间
    CONSTRAINT modeling_workbench_run_artifact_hash_check CHECK (((model_artifact_sha256 IS NULL) OR (model_artifact_sha256 ~ '^[0-9a-f]{64}$'::text))),
    CONSTRAINT modeling_workbench_run_error_object CHECK ((jsonb_typeof(error_jsonb) = 'object'::text)),
    CONSTRAINT modeling_workbench_run_hash_check CHECK ((request_hash ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT modeling_workbench_run_progress_check CHECK (((progress >= 0) AND (progress <= 100))),
    CONSTRAINT modeling_workbench_run_request_object CHECK ((jsonb_typeof(request_jsonb) = 'object'::text)),
    CONSTRAINT modeling_workbench_run_result_object CHECK ((jsonb_typeof(result_jsonb) = 'object'::text)),
    CONSTRAINT modeling_workbench_run_status_check CHECK (((status)::text = ANY (ARRAY[('QUEUED'::character varying)::text, ('RUNNING'::character varying)::text, ('SUCCEEDED'::character varying)::text, ('FAILED'::character varying)::text, ('CANCELLED'::character varying)::text]))),
    CONSTRAINT modeling_workbench_run_x_array CHECK ((jsonb_typeof(selected_x_jsonb) = 'array'::text)),
    CONSTRAINT modeling_workbench_run_y_object CHECK ((jsonb_typeof(selected_y_jsonb) = 'object'::text))
);

CREATE TABLE ai.operation_manual_chunk (
    manual_version character varying(160) NOT NULL, -- 手册版本
    embedding_model character varying(160) NOT NULL, -- 嵌入模型
    embedding_dimension integer NOT NULL, -- 向量维度
    chunk_id character varying(160) NOT NULL, -- 片段ID
    section_id character varying(120) NOT NULL, -- 章节ID
    content text NOT NULL, -- 操作手册片段内容
    content_hash character varying(64) NOT NULL, -- 内容哈希
    embedding public.vector NOT NULL, -- 向量嵌入
    updated_at timestamp with time zone DEFAULT now() NOT NULL, -- 更新时间
    CONSTRAINT operation_manual_chunk_check CHECK ((public.vector_dims(embedding) = embedding_dimension))
);

CREATE TABLE ai.operation_manual_index (
    manual_version character varying(160) NOT NULL, -- 手册版本
    embedding_model character varying(160) NOT NULL, -- 嵌入模型
    embedding_dimension integer NOT NULL, -- 向量维度
    status character varying(16) NOT NULL, -- 操作手册索引状态
    chunk_count integer DEFAULT 0 NOT NULL, -- 片段数量
    error_message character varying(500), -- 错误信息
    updated_at timestamp with time zone DEFAULT now() NOT NULL, -- 更新时间
    CONSTRAINT operation_manual_index_embedding_dimension_check CHECK ((embedding_dimension > 0)),
    CONSTRAINT operation_manual_index_status_check CHECK (((status)::text = ANY (ARRAY[('BUILDING'::character varying)::text, ('READY'::character varying)::text, ('FAILED'::character varying)::text])))
);

CREATE TABLE ai.prediction_record (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    prediction_type character varying(32) NOT NULL, -- 预测类型
    idempotency_key character varying(200) NOT NULL, -- 幂等键
    request_hash character(64) NOT NULL, -- 请求哈希
    request_jsonb jsonb NOT NULL, -- 请求内容（JSON）
    target_bindings_jsonb jsonb NOT NULL, -- 目标绑定（JSON）
    result_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 结果（JSON）
    error_jsonb jsonb, -- 错误详情（JSON）
    created_by uuid NOT NULL, -- 创建人ID
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    completed_at timestamp with time zone, -- 完成时间
    request_id character varying(160) NOT NULL, -- 请求ID
    contract_version character varying(40) DEFAULT 'ai-rnd.v1'::character varying NOT NULL, -- 契约版本
    execution_status character varying(24) NOT NULL, -- 执行状态
    outcome_status character varying(24), -- 结果状态
    target_results_jsonb jsonb DEFAULT '[]'::jsonb NOT NULL, -- 目标结果（JSON）
    terminal_http_status integer, -- 终态HTTP状态码
    terminal_response_jsonb jsonb, -- 终态响应（JSON）
    duration_ms bigint, -- 耗时（毫秒）
    heartbeat_at timestamp with time zone, -- 心跳时间
    revision bigint DEFAULT 0 NOT NULL, -- 修订号
    updated_at timestamp with time zone DEFAULT now() NOT NULL, -- 更新时间
    CONSTRAINT prediction_record_bindings_object CHECK ((jsonb_typeof(target_bindings_jsonb) = 'object'::text)),
    CONSTRAINT prediction_record_duration_check CHECK (((duration_ms IS NULL) OR (duration_ms >= 0))),
    CONSTRAINT prediction_record_error_object CHECK (((error_jsonb IS NULL) OR (jsonb_typeof(error_jsonb) = 'object'::text))),
    CONSTRAINT prediction_record_execution_status_check CHECK (((execution_status)::text = ANY (ARRAY[('RUNNING'::character varying)::text, ('SUCCEEDED'::character varying)::text, ('FAILED'::character varying)::text]))),
    CONSTRAINT prediction_record_hash_check CHECK ((request_hash ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT prediction_record_outcome_status_check CHECK (((outcome_status IS NULL) OR ((outcome_status)::text = ANY (ARRAY[('SUCCEEDED'::character varying)::text, ('PARTIAL'::character varying)::text, ('BLOCKED'::character varying)::text])))),
    CONSTRAINT prediction_record_request_object CHECK ((jsonb_typeof(request_jsonb) = 'object'::text)),
    CONSTRAINT prediction_record_result_object CHECK ((jsonb_typeof(result_jsonb) = 'object'::text)),
    CONSTRAINT prediction_record_status_consistency_check CHECK (((((execution_status)::text = 'RUNNING'::text) AND (outcome_status IS NULL) AND (terminal_http_status IS NULL) AND (terminal_response_jsonb IS NULL) AND (completed_at IS NULL)) OR (((execution_status)::text = 'SUCCEEDED'::text) AND (outcome_status IS NOT NULL) AND (terminal_http_status = ANY (ARRAY[200, 422])) AND (terminal_response_jsonb IS NOT NULL) AND (completed_at IS NOT NULL)) OR (((execution_status)::text = 'FAILED'::text) AND (outcome_status IS NULL) AND (terminal_http_status = 503) AND (terminal_response_jsonb IS NOT NULL) AND (completed_at IS NOT NULL)))),
    CONSTRAINT prediction_record_target_results_array CHECK ((jsonb_typeof(target_results_jsonb) = 'array'::text)),
    CONSTRAINT prediction_record_terminal_response_object CHECK (((terminal_response_jsonb IS NULL) OR (jsonb_typeof(terminal_response_jsonb) = 'object'::text))),
    CONSTRAINT prediction_record_type_check CHECK (((prediction_type)::text = ANY (ARRAY[('PERFORMANCE'::character varying)::text, ('FORMULA'::character varying)::text, ('OPTIMIZATION'::character varying)::text])))
);

CREATE TABLE ai.prediction_target (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    target_code character varying(160) NOT NULL, -- 目标编码
    name character varying(240) NOT NULL, -- 预测目标名称
    performance_project character varying(160) NOT NULL, -- 性能项目
    value_type character varying(24) NOT NULL, -- 取值类型
    status character varying(24) DEFAULT 'DRAFT'::character varying NOT NULL, -- 预测目标状态
    current_version_id uuid, -- 当前版本ID
    current_input_scheme_id uuid, -- 当前输入方案ID
    revision bigint DEFAULT 0 NOT NULL, -- 修订号
    created_by uuid NOT NULL, -- 创建人ID
    updated_by uuid NOT NULL, -- 更新人ID
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    updated_at timestamp with time zone DEFAULT now() NOT NULL, -- 更新时间
    current_quality_policy_version_id uuid, -- 当前质量策略版本ID
    current_domain_policy_version_id uuid, -- 当前领域策略版本ID
    CONSTRAINT prediction_target_status_check CHECK (((status)::text = ANY (ARRAY[('DRAFT'::character varying)::text, ('ACTIVE'::character varying)::text, ('PAUSED'::character varying)::text, ('RETIRED'::character varying)::text]))),
    CONSTRAINT prediction_target_value_type_check CHECK (((value_type)::text = ANY (ARRAY[('CONTINUOUS'::character varying)::text, ('ORDINAL'::character varying)::text, ('BINARY'::character varying)::text, ('CATEGORICAL'::character varying)::text])))
);

CREATE TABLE ai.research_candidate_history_observation_v2 (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    research_run_id uuid NOT NULL, -- 研究运行ID
    research_candidate_id uuid NOT NULL, -- 研究候选ID
    field_code character varying(240) NOT NULL, -- 字段编码
    observed_value_jsonb jsonb NOT NULL, -- 观测取值（JSON）
    created_by uuid NOT NULL, -- 创建人ID
    updated_by uuid NOT NULL, -- 更新人ID
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    updated_at timestamp with time zone DEFAULT now() NOT NULL, -- 更新时间
    CONSTRAINT research_candidate_history_observation_value_check CHECK ((jsonb_typeof(observed_value_jsonb) = 'number'::text))
);

CREATE TABLE ai.research_candidate_observation_v2 (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    research_run_id uuid NOT NULL, -- 研究运行ID
    research_candidate_id uuid NOT NULL, -- 研究候选ID
    target_id uuid NOT NULL, -- 目标ID
    observed_result_jsonb jsonb NOT NULL, -- 观测结果（JSON）
    created_by uuid NOT NULL, -- 创建人ID
    updated_by uuid NOT NULL, -- 更新人ID
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    updated_at timestamp with time zone DEFAULT now() NOT NULL, -- 更新时间
    CONSTRAINT research_candidate_observation_value_check CHECK ((jsonb_typeof(observed_result_jsonb) = ANY (ARRAY['number'::text, 'string'::text])))
);

CREATE TABLE ai.research_candidate_v2 (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    research_run_id uuid NOT NULL, -- 研究运行ID
    candidate_no integer NOT NULL, -- 候选编号
    title character varying(240) NOT NULL, -- 研究候选标题
    formula_jsonb jsonb NOT NULL, -- 配方（JSON）
    process_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 工艺（JSON）
    target_results_jsonb jsonb NOT NULL, -- 目标结果（JSON）
    rule_check_jsonb jsonb NOT NULL, -- 规则校验（JSON）
    applicability_jsonb jsonb NOT NULL, -- 适用性（JSON）
    evidence_jsonb jsonb NOT NULL, -- 证据（JSON）
    score numeric(24,12), -- 得分
    content_hash character(64) NOT NULL, -- 内容哈希
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    target_total numeric(18,8), -- 目标总量
    fixed_inputs_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 固定输入（JSON）
    search_space_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 搜索空间（JSON）
    target_gate_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 目标门限（JSON）
    quality_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 质量信息（JSON）
    preference_score numeric(24,12), -- 偏好得分
    diversity_score numeric(24,12), -- 多样性得分
    search_strategy character varying(80) DEFAULT 'CONTROLLED_POOL'::character varying NOT NULL, -- 检索策略
    baseline_distance numeric(24,12), -- 基线距离
    change_summary_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 变更摘要（JSON）
    strategy_evidence_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 策略证据（JSON）
    risk_flags_jsonb jsonb DEFAULT '[]'::jsonb NOT NULL, -- 风险标记（JSON）
    CONSTRAINT research_candidate_domain_object CHECK ((jsonb_typeof(applicability_jsonb) = 'object'::text)),
    CONSTRAINT research_candidate_evidence_object CHECK ((jsonb_typeof(evidence_jsonb) = 'object'::text)),
    CONSTRAINT research_candidate_formula_object CHECK ((jsonb_typeof(formula_jsonb) = 'object'::text)),
    CONSTRAINT research_candidate_hash_check CHECK ((content_hash ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT research_candidate_process_object CHECK ((jsonb_typeof(process_jsonb) = 'object'::text)),
    CONSTRAINT research_candidate_results_object CHECK ((jsonb_typeof(target_results_jsonb) = 'object'::text)),
    CONSTRAINT research_candidate_rule_object CHECK ((jsonb_typeof(rule_check_jsonb) = 'object'::text)),
    CONSTRAINT research_candidate_v2_candidate_no_check CHECK ((candidate_no > 0)),
    CONSTRAINT research_candidate_v2_change_object CHECK ((jsonb_typeof(change_summary_jsonb) = 'object'::text)),
    CONSTRAINT research_candidate_v2_distance_check CHECK (((baseline_distance IS NULL) OR (baseline_distance >= (0)::numeric))),
    CONSTRAINT research_candidate_v2_fixed_inputs_object CHECK ((jsonb_typeof(fixed_inputs_jsonb) = 'object'::text)),
    CONSTRAINT research_candidate_v2_gate_object CHECK ((jsonb_typeof(target_gate_jsonb) = 'object'::text)),
    CONSTRAINT research_candidate_v2_quality_object CHECK ((jsonb_typeof(quality_jsonb) = 'object'::text)),
    CONSTRAINT research_candidate_v2_risk_flags_array CHECK ((jsonb_typeof(risk_flags_jsonb) = 'array'::text)),
    CONSTRAINT research_candidate_v2_search_space_object CHECK ((jsonb_typeof(search_space_jsonb) = 'object'::text)),
    CONSTRAINT research_candidate_v2_strategy_check CHECK (((search_strategy)::text = ANY (ARRAY[('CONTROLLED_POOL'::character varying)::text, ('CONTROL'::character varying)::text, ('CONSERVATIVE'::character varying)::text, ('BALANCED'::character varying)::text, ('EXPLORATORY'::character varying)::text, ('HISTORICAL_BO'::character varying)::text]))),
    CONSTRAINT research_candidate_v2_strategy_evidence_object CHECK ((jsonb_typeof(strategy_evidence_jsonb) = 'object'::text))
);

CREATE TABLE ai.research_experiment_link_v2 (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    research_run_id uuid NOT NULL, -- 研究运行ID
    research_candidate_id uuid NOT NULL, -- 研究候选ID
    experiment_id uuid NOT NULL, -- 实验ID
    experiment_version_id uuid NOT NULL, -- 实验版本ID
    idempotency_key character varying(200) NOT NULL, -- 幂等键
    created_by uuid NOT NULL, -- 创建人ID
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    creation_intent_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 创建意图（JSON）
    creation_intent_hash character(64), -- 创建意图哈希
    source_evidence_id uuid, -- 来源证据ID
    CONSTRAINT research_experiment_link_v2_intent_hash_check CHECK (((creation_intent_hash IS NULL) OR (creation_intent_hash ~ '^[0-9a-f]{64}$'::text))),
    CONSTRAINT research_experiment_link_v2_intent_object CHECK ((jsonb_typeof(creation_intent_jsonb) = 'object'::text))
);

CREATE TABLE ai.research_run_v2 (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    run_type character varying(32) NOT NULL, -- 运行类型
    status character varying(24) DEFAULT 'QUEUED'::character varying NOT NULL, -- 研究运行状态
    idempotency_key character varying(200) NOT NULL, -- 幂等键
    request_hash character(64) NOT NULL, -- 请求哈希
    request_jsonb jsonb NOT NULL, -- 请求内容（JSON）
    model_bindings_jsonb jsonb NOT NULL, -- 模型绑定（JSON）
    result_summary_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 结果摘要（JSON）
    error_jsonb jsonb, -- 错误详情（JSON）
    revision bigint DEFAULT 0 NOT NULL, -- 修订号
    created_by uuid NOT NULL, -- 创建人ID
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    started_at timestamp with time zone, -- 开始时间
    finished_at timestamp with time zone, -- 完成时间
    updated_at timestamp with time zone DEFAULT now() NOT NULL, -- 更新时间
    request_id character varying(160) NOT NULL, -- 请求ID
    contract_version character varying(40) DEFAULT 'ai-rnd.v1'::character varying NOT NULL, -- 契约版本
    ops_job_id uuid, -- 运维任务ID
    seed bigint, -- 随机种子
    search_engine_version character varying(80) DEFAULT 'formula-search.v2'::character varying NOT NULL, -- 检索引擎版本
    search_config_jsonb jsonb DEFAULT '{"batchSize": 64, "initialDesign": 64, "maxEvaluations": 512, "diversityWeight": 0.15}'::jsonb NOT NULL, -- 检索配置（JSON）
    progress smallint DEFAULT 0 NOT NULL, -- 进度
    current_stage character varying(80), -- 当前阶段
    heartbeat_at timestamp with time zone, -- 心跳时间
    fencing_generation bigint DEFAULT 0 NOT NULL, -- 栅栏代数
    execution_status character varying(24), -- 执行状态
    outcome_status character varying(24), -- 结果状态
    baseline_type character varying(40), -- 基线类型
    baseline_entity_id uuid, -- 基线实体ID
    baseline_version_id uuid, -- 基线版本ID
    baseline_content_hash character(64), -- 基线内容哈希
    baseline_snapshot_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 基线快照（JSON）
    optimization_config_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 优化配置（JSON）
    CONSTRAINT research_run_v2_baseline_hash_check CHECK (((baseline_content_hash IS NULL) OR (baseline_content_hash ~ '^[0-9a-f]{64}$'::text))),
    CONSTRAINT research_run_v2_baseline_object CHECK ((jsonb_typeof(baseline_snapshot_jsonb) = 'object'::text)),
    CONSTRAINT research_run_v2_baseline_type_check CHECK (((baseline_type IS NULL) OR ((baseline_type)::text = ANY (ARRAY[('EXPERIMENT_VERSION'::character varying)::text, ('DATA_SAMPLE_REVISION'::character varying)::text, ('RESEARCH_CANDIDATE'::character varying)::text])))),
    CONSTRAINT research_run_v2_bindings_object CHECK ((jsonb_typeof(model_bindings_jsonb) = 'object'::text)),
    CONSTRAINT research_run_v2_error_object CHECK (((error_jsonb IS NULL) OR (jsonb_typeof(error_jsonb) = 'object'::text))),
    CONSTRAINT research_run_v2_execution_status_check CHECK (((execution_status)::text = ANY (ARRAY[('QUEUED'::character varying)::text, ('RUNNING'::character varying)::text, ('SUCCEEDED'::character varying)::text, ('FAILED'::character varying)::text, ('CANCELLED'::character varying)::text]))),
    CONSTRAINT research_run_v2_hash_check CHECK ((request_hash ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT research_run_v2_optimization_baseline_check CHECK ((((run_type)::text <> 'EXPERIMENT_OPTIMIZATION'::text) OR ((baseline_type IS NOT NULL) AND (baseline_entity_id IS NOT NULL) AND (baseline_version_id IS NOT NULL) AND (baseline_content_hash IS NOT NULL) AND (baseline_snapshot_jsonb <> '{}'::jsonb)))),
    CONSTRAINT research_run_v2_optimization_object CHECK ((jsonb_typeof(optimization_config_jsonb) = 'object'::text)),
    CONSTRAINT research_run_v2_outcome_status_check CHECK (((outcome_status IS NULL) OR ((outcome_status)::text = ANY (ARRAY[('SUCCEEDED'::character varying)::text, ('PARTIAL'::character varying)::text, ('BLOCKED'::character varying)::text])))),
    CONSTRAINT research_run_v2_progress_check CHECK (((progress >= 0) AND (progress <= 100))),
    CONSTRAINT research_run_v2_request_object CHECK ((jsonb_typeof(request_jsonb) = 'object'::text)),
    CONSTRAINT research_run_v2_result_object CHECK ((jsonb_typeof(result_summary_jsonb) = 'object'::text)),
    CONSTRAINT research_run_v2_search_config_object CHECK ((jsonb_typeof(search_config_jsonb) = 'object'::text)),
    CONSTRAINT research_run_v2_status_check CHECK (((status)::text = ANY (ARRAY[('QUEUED'::character varying)::text, ('RUNNING'::character varying)::text, ('SUCCEEDED'::character varying)::text, ('PARTIAL'::character varying)::text, ('BLOCKED'::character varying)::text, ('FAILED'::character varying)::text, ('CANCELLED'::character varying)::text]))),
    CONSTRAINT research_run_v2_status_consistency_check CHECK (((((execution_status)::text = ANY (ARRAY[('QUEUED'::character varying)::text, ('RUNNING'::character varying)::text, ('FAILED'::character varying)::text, ('CANCELLED'::character varying)::text])) AND (outcome_status IS NULL)) OR (((execution_status)::text = 'SUCCEEDED'::text) AND (outcome_status IS NOT NULL)))),
    CONSTRAINT research_run_v2_type_check CHECK (((run_type)::text = ANY (ARRAY[('FORMULA_PREDICTION'::character varying)::text, ('EXPERIMENT_OPTIMIZATION'::character varying)::text])))
);

CREATE TABLE ai.result (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    employee_role character varying(80) NOT NULL, -- 员工角色
    rule_code character varying(120) NOT NULL, -- 规则编码
    rule_version character varying(80) NOT NULL, -- 规则版本
    result_type character varying(32) NOT NULL, -- 结果类型
    severity character varying(16) NOT NULL, -- 严重级别
    status character varying(24) NOT NULL, -- 结果状态
    subject_type character varying(80) NOT NULL, -- 主体类型
    subject_id uuid NOT NULL, -- 主体ID
    discriminator character varying(160) DEFAULT ''::character varying NOT NULL, -- 判别标识
    fact_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 事实（JSON）
    inference_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 推理结果（JSON）
    recommendation_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 推荐建议（JSON）
    first_detected_at timestamp with time zone NOT NULL, -- 首次发现时间
    last_detected_at timestamp with time zone NOT NULL, -- 最近发现时间
    deferred_until timestamp with time zone, -- 延迟至
    resolved_at timestamp with time zone, -- 解决时间
    version bigint DEFAULT 0 NOT NULL, -- 结果版本号
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    updated_at timestamp with time zone DEFAULT now() NOT NULL, -- 更新时间
    CONSTRAINT result_result_type_check CHECK (((result_type)::text = ANY (ARRAY[('REMINDER'::character varying)::text, ('RISK'::character varying)::text, ('BLOCKER'::character varying)::text, ('SUMMARY'::character varying)::text, ('INSIGHT'::character varying)::text, ('RECOMMENDATION'::character varying)::text]))),
    CONSTRAINT result_severity_check CHECK (((severity)::text = ANY (ARRAY[('LOW'::character varying)::text, ('MEDIUM'::character varying)::text, ('HIGH'::character varying)::text]))),
    CONSTRAINT result_status_check CHECK (((status)::text = ANY (ARRAY[('OPEN'::character varying)::text, ('ACKNOWLEDGED'::character varying)::text, ('DEFERRED'::character varying)::text, ('RESOLVED'::character varying)::text, ('FALSE_POSITIVE'::character varying)::text]))),
    CONSTRAINT result_version_check CHECK ((version >= 0))
);

CREATE TABLE ai.result_evidence (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    result_id uuid NOT NULL, -- 结果ID
    business_object_type character varying(80) NOT NULL, -- 业务对象类型
    business_object_id uuid NOT NULL, -- 业务对象ID
    field_name character varying(160), -- 字段名称
    field_value_summary text DEFAULT ''::text NOT NULL, -- 字段取值摘要
    data_updated_at timestamp with time zone, -- 数据更新时间
    source_uri character varying(1000), -- 来源URI
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    occurrence_id uuid, -- 出现记录ID
    is_current boolean DEFAULT true NOT NULL -- 是否当前版本
);

CREATE TABLE ai.result_feedback (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    result_id uuid NOT NULL, -- 结果ID
    feedback_type character varying(24) NOT NULL, -- 反馈类型
    actor_id uuid NOT NULL, -- 操作者ID
    reason text DEFAULT ''::text NOT NULL, -- 原因
    result_version bigint NOT NULL, -- 结果版本
    deferred_until timestamp with time zone, -- 延迟至
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    CONSTRAINT result_feedback_feedback_type_check CHECK (((feedback_type)::text = ANY (ARRAY[('ACKNOWLEDGED'::character varying)::text, ('DEFERRED'::character varying)::text, ('RESOLVED'::character varying)::text, ('FALSE_POSITIVE'::character varying)::text]))),
    CONSTRAINT result_feedback_result_version_check CHECK ((result_version >= 0))
);

CREATE TABLE ai.result_occurrence (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    result_id uuid NOT NULL, -- 结果ID
    occurrence_type character varying(24) NOT NULL, -- 出现类型
    severity character varying(16) NOT NULL, -- 严重级别
    status_before character varying(24), -- 变更前状态
    status_after character varying(24) NOT NULL, -- 变更后状态
    fact_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 事实（JSON）
    context_snapshot_id uuid, -- 上下文快照ID
    detected_at timestamp with time zone NOT NULL, -- 发现时间
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    CONSTRAINT result_occurrence_occurrence_type_check CHECK (((occurrence_type)::text = ANY (ARRAY[('CREATED'::character varying)::text, ('DETECTED'::character varying)::text, ('ESCALATED'::character varying)::text, ('REOPENED'::character varying)::text, ('RESOLVED'::character varying)::text, ('ACKNOWLEDGED'::character varying)::text, ('DEFERRED'::character varying)::text, ('FALSE_POSITIVE'::character varying)::text]))),
    CONSTRAINT result_occurrence_severity_check CHECK (((severity)::text = ANY (ARRAY[('LOW'::character varying)::text, ('MEDIUM'::character varying)::text, ('HIGH'::character varying)::text]))),
    CONSTRAINT result_occurrence_status_after_check CHECK (((status_after)::text = ANY (ARRAY[('OPEN'::character varying)::text, ('ACKNOWLEDGED'::character varying)::text, ('DEFERRED'::character varying)::text, ('RESOLVED'::character varying)::text, ('FALSE_POSITIVE'::character varying)::text])))
);

CREATE TABLE ai.rule_definition (
    rule_code character varying(120) NOT NULL, -- 规则编码
    rule_name character varying(240) NOT NULL, -- 规则名称
    business_object character varying(80) NOT NULL, -- 业务对象
    trigger_types_jsonb jsonb DEFAULT '[]'::jsonb NOT NULL, -- 触发类型列表（JSON）
    condition_definition_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 条件定义（JSON）
    context_profile character varying(120), -- 上下文配置
    skill_codes_jsonb jsonb DEFAULT '[]'::jsonb NOT NULL, -- 技能编码列表（JSON）
    result_type character varying(32) NOT NULL, -- 结果类型
    default_severity character varying(16) NOT NULL, -- 默认严重级别
    enabled boolean DEFAULT true NOT NULL, -- 是否启用
    version bigint DEFAULT 0 NOT NULL, -- 规则定义版本号
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    updated_at timestamp with time zone DEFAULT now() NOT NULL, -- 更新时间
    id uuid DEFAULT gen_random_uuid() NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    CONSTRAINT rule_definition_default_severity_check CHECK (((default_severity)::text = ANY (ARRAY[('LOW'::character varying)::text, ('MEDIUM'::character varying)::text, ('HIGH'::character varying)::text]))),
    CONSTRAINT rule_definition_result_type_check CHECK (((result_type)::text = ANY (ARRAY[('REMINDER'::character varying)::text, ('RISK'::character varying)::text, ('BLOCKER'::character varying)::text, ('SUMMARY'::character varying)::text, ('RECOMMENDATION'::character varying)::text]))),
    CONSTRAINT rule_definition_version_check CHECK ((version >= 0))
);

CREATE TABLE ai.rule_version (
    id uuid NOT NULL, -- 主键ID
    rule_code character varying(120) NOT NULL, -- 规则编码
    version_no integer NOT NULL, -- 版本号
    rule_name character varying(240) NOT NULL, -- 规则名称
    business_object character varying(80) NOT NULL, -- 业务对象
    trigger_types_jsonb jsonb DEFAULT '[]'::jsonb NOT NULL, -- 触发类型列表（JSON）
    condition_definition_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 条件定义（JSON）
    context_profile character varying(120), -- 上下文配置
    skill_codes_jsonb jsonb DEFAULT '[]'::jsonb NOT NULL, -- 技能编码列表（JSON）
    result_type character varying(32) NOT NULL, -- 结果类型
    default_severity character varying(16) NOT NULL, -- 默认严重级别
    enabled boolean DEFAULT true NOT NULL, -- 是否启用
    created_by uuid, -- 创建人ID
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    organization_id uuid NOT NULL, -- 组织ID
    status character varying(16) DEFAULT 'DRAFT'::character varying NOT NULL, -- 规则版本状态
    change_note text DEFAULT ''::text NOT NULL, -- 变更说明
    effective_from timestamp with time zone, -- 生效开始时间
    effective_to timestamp with time zone, -- 生效结束时间
    CONSTRAINT ck_ai_rule_version_effective_window CHECK (((effective_to IS NULL) OR (effective_from IS NULL) OR (effective_to > effective_from))),
    CONSTRAINT ck_ai_rule_version_status CHECK (((status)::text = ANY (ARRAY[('DRAFT'::character varying)::text, ('PUBLISHED'::character varying)::text, ('RETIRED'::character varying)::text]))),
    CONSTRAINT rule_version_default_severity_check CHECK (((default_severity)::text = ANY (ARRAY[('LOW'::character varying)::text, ('MEDIUM'::character varying)::text, ('HIGH'::character varying)::text]))),
    CONSTRAINT rule_version_result_type_check CHECK (((result_type)::text = ANY (ARRAY[('REMINDER'::character varying)::text, ('RISK'::character varying)::text, ('BLOCKER'::character varying)::text, ('SUMMARY'::character varying)::text, ('RECOMMENDATION'::character varying)::text]))),
    CONSTRAINT rule_version_version_no_check CHECK ((version_no > 0))
);

CREATE TABLE ai.sample_identity_issue (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    training_sample_id uuid, -- 训练样本ID
    left_sample_source_id uuid NOT NULL, -- 左侧样品来源ID
    right_sample_source_id uuid NOT NULL, -- 右侧样品来源ID
    issue_type character varying(40) NOT NULL, -- 问题类型
    status character varying(24) DEFAULT 'OPEN'::character varying NOT NULL, -- 样品标识问题状态
    evidence_jsonb jsonb NOT NULL, -- 证据（JSON）
    resolution_jsonb jsonb, -- 处理结果（JSON）
    revision bigint DEFAULT 0 NOT NULL, -- 修订号
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    resolved_at timestamp with time zone, -- 解决时间
    resolved_by uuid, -- 解决人ID
    CONSTRAINT identity_issue_distinct_sources CHECK ((left_sample_source_id <> right_sample_source_id)),
    CONSTRAINT identity_issue_evidence_object CHECK ((jsonb_typeof(evidence_jsonb) = 'object'::text)),
    CONSTRAINT identity_issue_resolution_object CHECK (((resolution_jsonb IS NULL) OR (jsonb_typeof(resolution_jsonb) = 'object'::text))),
    CONSTRAINT identity_issue_status_check CHECK (((status)::text = ANY (ARRAY[('OPEN'::character varying)::text, ('RESOLVED'::character varying)::text, ('DISMISSED'::character varying)::text])))
);

CREATE TABLE ai.sample_revision (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    training_sample_id uuid NOT NULL, -- 训练样本ID
    sample_source_id uuid NOT NULL, -- 样品来源ID
    revision_no integer NOT NULL, -- 修订号
    composition_jsonb jsonb NOT NULL, -- 组成配方（JSON）
    process_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 工艺（JSON）
    conditions_jsonb jsonb NOT NULL, -- 条件（JSON）
    observations_jsonb jsonb NOT NULL, -- 观测数据（JSON）
    facts_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 事实列表（JSON）
    permission_scope_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 权限范围（JSON）
    source_coordinates_jsonb jsonb NOT NULL, -- 来源坐标（JSON）
    fact_hash character(64) NOT NULL, -- 事实哈希
    status character varying(24) DEFAULT 'CURRENT'::character varying NOT NULL, -- 样品修订状态
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    CONSTRAINT sample_revision_composition_object CHECK ((jsonb_typeof(composition_jsonb) = 'object'::text)),
    CONSTRAINT sample_revision_conditions_object CHECK ((jsonb_typeof(conditions_jsonb) = 'object'::text)),
    CONSTRAINT sample_revision_coordinates_object CHECK ((jsonb_typeof(source_coordinates_jsonb) = 'object'::text)),
    CONSTRAINT sample_revision_facts_object CHECK ((jsonb_typeof(facts_jsonb) = 'object'::text)),
    CONSTRAINT sample_revision_hash_check CHECK ((fact_hash ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT sample_revision_observations_object CHECK ((jsonb_typeof(observations_jsonb) = 'object'::text)),
    CONSTRAINT sample_revision_permission_scope_object CHECK ((jsonb_typeof(permission_scope_jsonb) = 'object'::text)),
    CONSTRAINT sample_revision_process_object CHECK ((jsonb_typeof(process_jsonb) = 'object'::text)),
    CONSTRAINT sample_revision_revision_no_check CHECK ((revision_no > 0)),
    CONSTRAINT sample_revision_status_check CHECK (((status)::text = ANY (ARRAY[('CURRENT'::character varying)::text, ('SUPERSEDED'::character varying)::text, ('INVALIDATED'::character varying)::text])))
);

CREATE TABLE ai.sample_source (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    training_sample_id uuid NOT NULL, -- 训练样本ID
    source_type character varying(24) NOT NULL, -- 来源类型
    confirmed_submission_item_id uuid, -- 已确认提交项ID
    experiment_version_id uuid, -- 实验版本ID
    source_business_key character varying(320) NOT NULL, -- 来源业务键
    sample_boundary_id character varying(240) NOT NULL, -- 样品边界ID
    logical_sample_key character varying(320) NOT NULL, -- 逻辑样品键
    source_group_keys_jsonb jsonb NOT NULL, -- 来源分组键（JSON）
    physical_identity_hash character(64) NOT NULL, -- 物理标识哈希
    source_version character varying(160) NOT NULL, -- 来源版本
    source_sequence bigint NOT NULL, -- 来源序号
    content_hash character(64) NOT NULL, -- 内容哈希
    priority smallint NOT NULL, -- 优先级
    status character varying(24) DEFAULT 'CURRENT'::character varying NOT NULL, -- 样品来源状态
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    CONSTRAINT sample_source_groups_array CHECK (((jsonb_typeof(source_group_keys_jsonb) = 'array'::text) AND (jsonb_array_length(source_group_keys_jsonb) > 0))),
    CONSTRAINT sample_source_hash_check CHECK ((content_hash ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT sample_source_physical_hash_check CHECK ((physical_identity_hash ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT sample_source_reference_check CHECK (((((source_type)::text = 'DATA_CENTER'::text) AND (confirmed_submission_item_id IS NOT NULL) AND (experiment_version_id IS NULL)) OR (((source_type)::text = 'EXPERIMENT'::text) AND (experiment_version_id IS NOT NULL) AND (confirmed_submission_item_id IS NULL)))),
    CONSTRAINT sample_source_status_check CHECK (((status)::text = ANY (ARRAY[('CURRENT'::character varying)::text, ('SUPERSEDED'::character varying)::text, ('INVALIDATED'::character varying)::text, ('TAKEN_OVER'::character varying)::text]))),
    CONSTRAINT sample_source_type_check CHECK (((source_type)::text = ANY (ARRAY[('DATA_CENTER'::character varying)::text, ('EXPERIMENT'::character varying)::text])))
);

CREATE TABLE ai.signal (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    trigger_type character varying(16) NOT NULL, -- 触发类型
    subject_type character varying(80) NOT NULL, -- 主体类型
    subject_id uuid NOT NULL, -- 主体ID
    deduplication_key character varying(300) NOT NULL, -- 去重键
    payload_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 载荷（JSON）
    occurred_at timestamp with time zone NOT NULL, -- 发生时间
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    CONSTRAINT signal_trigger_type_check CHECK (((trigger_type)::text = ANY (ARRAY[('TIME'::character varying)::text, ('EVENT'::character varying)::text, ('STATE'::character varying)::text, ('CONTEXT'::character varying)::text, ('PERIODIC'::character varying)::text])))
);

CREATE TABLE ai.source_mapping_version (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    target_id uuid NOT NULL, -- 目标ID
    target_version_id uuid NOT NULL, -- 目标版本ID
    source_type character varying(32) NOT NULL, -- 来源类型
    version_no integer NOT NULL, -- 版本号
    status character varying(24) NOT NULL, -- 来源映射版本状态
    mapping_jsonb jsonb NOT NULL, -- 映射（JSON）
    mapping_hash character(64) NOT NULL, -- 映射哈希
    published_at timestamp with time zone, -- 发布时间
    created_by uuid NOT NULL, -- 创建人ID
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    CONSTRAINT source_mapping_hash_check CHECK ((mapping_hash ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT source_mapping_json_object CHECK ((jsonb_typeof(mapping_jsonb) = 'object'::text)),
    CONSTRAINT source_mapping_status_check CHECK (((status)::text = ANY (ARRAY[('DRAFT'::character varying)::text, ('PUBLISHED'::character varying)::text, ('RETIRED'::character varying)::text]))),
    CONSTRAINT source_mapping_type_check CHECK (((source_type)::text = ANY (ARRAY[('DATA_CENTER'::character varying)::text, ('EXPERIMENT'::character varying)::text]))),
    CONSTRAINT source_mapping_version_version_no_check CHECK ((version_no > 0))
);

CREATE TABLE ai.target_version (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    target_id uuid NOT NULL, -- 目标ID
    version_no integer NOT NULL, -- 版本号
    status character varying(24) DEFAULT 'DRAFT'::character varying NOT NULL, -- 目标版本状态
    value_type character varying(24) NOT NULL, -- 取值类型
    unit character varying(80), -- 目标单位
    classes_jsonb jsonb DEFAULT '[]'::jsonb NOT NULL, -- 分类标签（JSON）
    definition_jsonb jsonb NOT NULL, -- 定义（JSON）
    observation_semantics_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 观测语义（JSON）
    config_hash character(64) NOT NULL, -- 配置哈希
    published_at timestamp with time zone, -- 发布时间
    created_by uuid NOT NULL, -- 创建人ID
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    result_standard_field_dictionary_id uuid, -- 结果标准字段字典ID
    catalog_proposal_id uuid, -- 字段目录提案ID
    CONSTRAINT target_version_classes_array CHECK ((jsonb_typeof(classes_jsonb) = 'array'::text)),
    CONSTRAINT target_version_definition_object CHECK ((jsonb_typeof(definition_jsonb) = 'object'::text)),
    CONSTRAINT target_version_hash_check CHECK ((config_hash ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT target_version_semantics_object CHECK ((jsonb_typeof(observation_semantics_jsonb) = 'object'::text)),
    CONSTRAINT target_version_status_check CHECK (((status)::text = ANY (ARRAY[('DRAFT'::character varying)::text, ('PUBLISHED'::character varying)::text, ('RETIRED'::character varying)::text]))),
    CONSTRAINT target_version_value_type_check CHECK (((value_type)::text = ANY (ARRAY[('CONTINUOUS'::character varying)::text, ('ORDINAL'::character varying)::text, ('BINARY'::character varying)::text, ('CATEGORICAL'::character varying)::text]))),
    CONSTRAINT target_version_version_no_check CHECK ((version_no > 0))
);

CREATE TABLE ai.training_eligibility (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    sample_revision_id uuid NOT NULL, -- 样品修订ID
    target_version_id uuid NOT NULL, -- 目标版本ID
    input_scheme_id uuid NOT NULL, -- 输入方案ID
    state character varying(24) NOT NULL, -- 状态
    reasons_jsonb jsonb DEFAULT '[]'::jsonb NOT NULL, -- 原因列表（JSON）
    warnings_jsonb jsonb DEFAULT '[]'::jsonb NOT NULL, -- 警告列表（JSON）
    evidence_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 证据（JSON）
    rule_fingerprint character(64) NOT NULL, -- 规则指纹
    revision bigint DEFAULT 0 NOT NULL, -- 修订号
    evaluated_at timestamp with time zone DEFAULT now() NOT NULL, -- 评估时间
    evaluation_run_id uuid, -- 评估运行ID
    source_mapping_version_id uuid, -- 来源映射版本ID
    material_dictionary_version_id uuid, -- 物料字典版本ID
    modeling_policy_version_id uuid, -- 建模策略版本ID
    CONSTRAINT eligibility_evidence_object CHECK ((jsonb_typeof(evidence_jsonb) = 'object'::text)),
    CONSTRAINT eligibility_reasons_array CHECK ((jsonb_typeof(reasons_jsonb) = 'array'::text)),
    CONSTRAINT eligibility_rule_hash_check CHECK ((rule_fingerprint ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT eligibility_state_check CHECK (((state)::text = ANY (ARRAY[('TRAINABLE'::character varying)::text, ('EXCLUDED'::character varying)::text, ('REVIEW_REQUIRED'::character varying)::text]))),
    CONSTRAINT eligibility_warnings_array CHECK ((jsonb_typeof(warnings_jsonb) = 'array'::text))
);

CREATE TABLE ai.training_job (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    training_snapshot_id uuid NOT NULL, -- 训练快照ID
    status character varying(24) DEFAULT 'QUEUED'::character varying NOT NULL, -- 训练任务状态
    idempotency_key character varying(200) NOT NULL, -- 幂等键
    request_hash character(64) NOT NULL, -- 请求哈希
    priority smallint DEFAULT 100 NOT NULL, -- 优先级
    max_attempts integer DEFAULT 3 NOT NULL, -- 最大尝试次数
    attempt_count integer DEFAULT 0 NOT NULL, -- 尝试次数
    lease_owner character varying(160), -- 租约持有者
    lease_expires_at timestamp with time zone, -- 租约过期时间
    next_attempt_at timestamp with time zone DEFAULT now() NOT NULL, -- 下次尝试时间
    last_error_code character varying(100), -- 最近错误码
    last_error_message text, -- 最近错误信息
    revision bigint DEFAULT 0 NOT NULL, -- 修订号
    requested_by uuid NOT NULL, -- 请求人ID
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    started_at timestamp with time zone, -- 开始时间
    finished_at timestamp with time zone, -- 完成时间
    updated_at timestamp with time zone DEFAULT now() NOT NULL, -- 更新时间
    target_id uuid, -- 目标ID
    target_version_id uuid, -- 目标版本ID
    modeling_policy_version_id uuid, -- 建模策略版本ID
    seed bigint, -- 随机种子
    business_key character(64), -- 业务键
    ops_job_id uuid, -- 运维任务ID
    current_stage character varying(40) DEFAULT 'QUEUED'::character varying NOT NULL, -- 当前阶段
    progress smallint DEFAULT 0 NOT NULL, -- 进度
    active_attempt_id uuid, -- 当前尝试ID
    fencing_generation bigint DEFAULT 0 NOT NULL, -- 栅栏代数
    cancellation_requested_at timestamp with time zone, -- 取消请求时间
    error_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 错误详情（JSON）
    CONSTRAINT training_job_attempt_count_check CHECK ((attempt_count >= 0)),
    CONSTRAINT training_job_business_hash_check CHECK (((business_key IS NULL) OR (business_key ~ '^[0-9a-f]{64}$'::text))),
    CONSTRAINT training_job_error_object CHECK ((jsonb_typeof(error_jsonb) = 'object'::text)),
    CONSTRAINT training_job_hash_check CHECK ((request_hash ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT training_job_max_attempts_check CHECK ((max_attempts > 0)),
    CONSTRAINT training_job_progress_check CHECK (((progress >= 0) AND (progress <= 100))),
    CONSTRAINT training_job_status_check CHECK (((status)::text = ANY (ARRAY[('QUEUED'::character varying)::text, ('MATERIALIZING'::character varying)::text, ('SNAPSHOT_VALIDATING'::character varying)::text, ('FOLDING'::character varying)::text, ('TRAINING'::character varying)::text, ('VALIDATING'::character varying)::text, ('SUCCEEDED'::character varying)::text, ('FAILED'::character varying)::text, ('CANCELLED'::character varying)::text])))
);

CREATE TABLE ai.training_job_attempt (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    training_job_id uuid NOT NULL, -- 训练任务ID
    attempt_no integer NOT NULL, -- 尝试编号
    status character varying(24) NOT NULL, -- 训练尝试状态
    worker_id character varying(160), -- 执行器ID
    frozen_request_jsonb jsonb NOT NULL, -- 冻结请求（JSON）
    request_hash character(64) NOT NULL, -- 请求哈希
    result_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 结果（JSON）
    error_code character varying(100), -- 错误码
    error_message text, -- 错误信息
    started_at timestamp with time zone DEFAULT now() NOT NULL, -- 开始时间
    finished_at timestamp with time zone, -- 完成时间
    lease_token uuid, -- 租约令牌
    fencing_generation bigint DEFAULT 0 NOT NULL, -- 栅栏代数
    heartbeat_at timestamp with time zone, -- 心跳时间
    current_stage character varying(40) DEFAULT 'QUEUED'::character varying NOT NULL, -- 当前阶段
    progress smallint DEFAULT 0 NOT NULL, -- 进度
    log_reference character varying(700), -- 日志引用
    artifact_refs_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 产物引用（JSON）
    CONSTRAINT training_attempt_artifacts_object CHECK ((jsonb_typeof(artifact_refs_jsonb) = 'object'::text)),
    CONSTRAINT training_attempt_hash_check CHECK ((request_hash ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT training_attempt_progress_check CHECK (((progress >= 0) AND (progress <= 100))),
    CONSTRAINT training_attempt_request_object CHECK ((jsonb_typeof(frozen_request_jsonb) = 'object'::text)),
    CONSTRAINT training_attempt_result_object CHECK ((jsonb_typeof(result_jsonb) = 'object'::text)),
    CONSTRAINT training_attempt_status_check CHECK (((status)::text = ANY (ARRAY[('RUNNING'::character varying)::text, ('SUCCEEDED'::character varying)::text, ('FAILED'::character varying)::text, ('INTERRUPTED'::character varying)::text, ('CANCELLED'::character varying)::text, ('STALE'::character varying)::text]))),
    CONSTRAINT training_job_attempt_attempt_no_check CHECK ((attempt_no > 0))
);

CREATE TABLE ai.training_sample (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    logical_sample_key character varying(320) NOT NULL, -- 逻辑样品键
    identity_version bigint DEFAULT 1 NOT NULL, -- 标识版本
    authority_source_type character varying(24), -- 权威来源类型
    current_sample_revision_id uuid, -- 当前样品修订ID
    status character varying(24) DEFAULT 'ACTIVE'::character varying NOT NULL, -- 训练样本状态
    takeover_experiment_id uuid, -- 承继实验ID
    takeover_experiment_version_id uuid, -- 承继实验版本ID
    revision bigint DEFAULT 0 NOT NULL, -- 修订号
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    updated_at timestamp with time zone DEFAULT now() NOT NULL, -- 更新时间
    CONSTRAINT training_sample_authority_check CHECK (((authority_source_type IS NULL) OR ((authority_source_type)::text = ANY (ARRAY[('DATA_CENTER'::character varying)::text, ('EXPERIMENT'::character varying)::text])))),
    CONSTRAINT training_sample_status_check CHECK (((status)::text = ANY (ARRAY[('ACTIVE'::character varying)::text, ('TAKEN_OVER'::character varying)::text, ('SUSPENDED'::character varying)::text, ('INVALIDATED'::character varying)::text, ('RETIRED'::character varying)::text])))
);

CREATE TABLE ai.training_schedule_intent (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    target_id uuid NOT NULL, -- 目标ID
    target_version_id uuid NOT NULL, -- 目标版本ID
    input_scheme_id uuid NOT NULL, -- 输入方案ID
    eligibility_run_id uuid NOT NULL, -- 准入判定运行ID
    configuration_fingerprint character(64) NOT NULL, -- 配置指纹
    fact_high_watermark bigint DEFAULT 0 NOT NULL, -- 事实高水位线
    status character varying(24) DEFAULT 'PENDING'::character varying NOT NULL, -- 训练调度意图状态
    block_code character varying(100), -- 区块编码
    block_detail_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 区块明细（JSON）
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    updated_at timestamp with time zone DEFAULT now() NOT NULL, -- 更新时间
    CONSTRAINT training_intent_detail_object CHECK ((jsonb_typeof(block_detail_jsonb) = 'object'::text)),
    CONSTRAINT training_intent_hash_check CHECK ((configuration_fingerprint ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT training_intent_status_check CHECK (((status)::text = ANY (ARRAY[('PENDING'::character varying)::text, ('CONSUMED'::character varying)::text, ('BLOCKED'::character varying)::text])))
);

CREATE TABLE ai.training_scheduler_setting (
    organization_id uuid NOT NULL, -- 组织ID
    auto_learning_enabled boolean DEFAULT false NOT NULL, -- 是否启用自动学习
    last_evaluated_at timestamp with time zone, -- 最近评估时间
    revision bigint DEFAULT 0 NOT NULL, -- 修订号
    updated_by uuid, -- 更新人ID
    updated_at timestamp with time zone DEFAULT now() NOT NULL -- 更新时间
);

CREATE TABLE ai.training_snapshot (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    target_version_id uuid NOT NULL, -- 目标版本ID
    input_scheme_id uuid NOT NULL, -- 输入方案ID
    material_dictionary_version_id uuid NOT NULL, -- 物料字典版本ID
    modeling_policy_version_id uuid NOT NULL, -- 建模策略版本ID
    status character varying(24) DEFAULT 'FROZEN'::character varying NOT NULL, -- 训练快照状态
    sample_count integer NOT NULL, -- 样品数量
    validation_groups_jsonb jsonb NOT NULL, -- 校验分组（JSON）
    manifest_jsonb jsonb NOT NULL, -- 清单（JSON）
    snapshot_hash character(64) NOT NULL, -- 快照哈希
    object_prefix character varying(500) NOT NULL, -- 对象存储前缀
    frozen_by uuid NOT NULL, -- 冻结人ID
    frozen_at timestamp with time zone DEFAULT now() NOT NULL, -- 冻结时间
    target_id uuid, -- 目标ID
    eligibility_run_id uuid, -- 准入判定运行ID
    source_mapping_versions_jsonb jsonb DEFAULT '[]'::jsonb NOT NULL, -- 来源映射版本列表（JSON）
    target_definition_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 目标定义（JSON）
    input_scheme_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 输入方案（JSON）
    material_dictionary_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 物料字典（JSON）
    preprocessing_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 预处理配置（JSON）
    training_policy_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 训练策略（JSON）
    authorization_scope_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 授权范围（JSON）
    fact_high_watermark bigint DEFAULT 0 NOT NULL, -- 事实高水位线
    data_nature character varying(24) DEFAULT 'REAL'::character varying NOT NULL, -- 数据性质
    business_fingerprint character(64), -- 业务指纹
    CONSTRAINT training_snapshot_business_hash_check CHECK (((business_fingerprint IS NULL) OR (business_fingerprint ~ '^[0-9a-f]{64}$'::text))),
    CONSTRAINT training_snapshot_data_nature_check CHECK (((data_nature)::text = ANY (ARRAY[('REAL'::character varying)::text, ('SYNTHETIC'::character varying)::text]))),
    CONSTRAINT training_snapshot_dictionary_object CHECK ((jsonb_typeof(material_dictionary_jsonb) = 'object'::text)),
    CONSTRAINT training_snapshot_groups_object CHECK ((jsonb_typeof(validation_groups_jsonb) = 'object'::text)),
    CONSTRAINT training_snapshot_hash_check CHECK ((snapshot_hash ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT training_snapshot_manifest_object CHECK ((jsonb_typeof(manifest_jsonb) = 'object'::text)),
    CONSTRAINT training_snapshot_mapping_array CHECK ((jsonb_typeof(source_mapping_versions_jsonb) = 'array'::text)),
    CONSTRAINT training_snapshot_policy_object CHECK ((jsonb_typeof(training_policy_jsonb) = 'object'::text)),
    CONSTRAINT training_snapshot_preprocessing_object CHECK ((jsonb_typeof(preprocessing_jsonb) = 'object'::text)),
    CONSTRAINT training_snapshot_sample_count_check CHECK ((sample_count >= 0)),
    CONSTRAINT training_snapshot_scheme_object CHECK ((jsonb_typeof(input_scheme_jsonb) = 'object'::text)),
    CONSTRAINT training_snapshot_scope_object CHECK ((jsonb_typeof(authorization_scope_jsonb) = 'object'::text)),
    CONSTRAINT training_snapshot_status_check CHECK (((status)::text = ANY (ARRAY[('FROZEN'::character varying)::text, ('RETIRED'::character varying)::text]))),
    CONSTRAINT training_snapshot_target_object CHECK ((jsonb_typeof(target_definition_jsonb) = 'object'::text))
);

CREATE TABLE ai.training_snapshot_item (
    organization_id uuid NOT NULL, -- 组织ID
    training_snapshot_id uuid NOT NULL, -- 训练快照ID
    sample_revision_id uuid NOT NULL, -- 样品修订ID
    eligibility_id uuid NOT NULL, -- 准入判定ID
    ordinal bigint NOT NULL, -- 序号
    split_group character varying(160) NOT NULL, -- 拆分组
    row_hash character(64) NOT NULL, -- 行哈希
    row_jsonb jsonb NOT NULL, -- 行数据（JSON）
    training_sample_id uuid, -- 训练样本ID
    observation_ids_jsonb jsonb DEFAULT '[]'::jsonb NOT NULL, -- 观测ID列表（JSON）
    replicate_group_keys_jsonb jsonb DEFAULT '[]'::jsonb NOT NULL, -- 重复组键列表（JSON）
    source_refs_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 来源引用（JSON）
    validation_groups_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 校验分组（JSON）
    value_hash character(64), -- 取值哈希
    CONSTRAINT training_snapshot_item_groups_object CHECK ((jsonb_typeof(validation_groups_jsonb) = 'object'::text)),
    CONSTRAINT training_snapshot_item_hash_check CHECK ((row_hash ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT training_snapshot_item_observations_array CHECK ((jsonb_typeof(observation_ids_jsonb) = 'array'::text)),
    CONSTRAINT training_snapshot_item_ordinal_check CHECK ((ordinal >= 0)),
    CONSTRAINT training_snapshot_item_replicates_array CHECK ((jsonb_typeof(replicate_group_keys_jsonb) = 'array'::text)),
    CONSTRAINT training_snapshot_item_row_object CHECK ((jsonb_typeof(row_jsonb) = 'object'::text)),
    CONSTRAINT training_snapshot_item_source_object CHECK ((jsonb_typeof(source_refs_jsonb) = 'object'::text)),
    CONSTRAINT training_snapshot_item_value_hash_check CHECK (((value_hash IS NULL) OR (value_hash ~ '^[0-9a-f]{64}$'::text)))
);


-- ==================== schema: core ====================

CREATE TABLE core.project_reference (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    project_id uuid NOT NULL, -- 项目ID
    stage_id uuid, -- 阶段ID
    task_id uuid, -- 任务ID
    resource_type character varying(40) NOT NULL, -- 资源类型
    resource_id uuid NOT NULL, -- 资源ID
    file_version_id uuid, -- 文件版本ID
    file_object_id uuid, -- 文件对象ID
    source_module character varying(32) NOT NULL, -- 来源模块
    title character varying(500) NOT NULL, -- 项目参考标题
    original_name character varying(500), -- 原始名称
    content_type character varying(260), -- 内容类型
    size_bytes bigint DEFAULT 0 NOT NULL, -- 大小（字节）
    summary text, -- 项目参考摘要
    status character varying(24) DEFAULT 'ACTIVE'::character varying NOT NULL, -- 项目参考状态
    added_by uuid NOT NULL, -- 添加人ID
    added_at timestamp with time zone DEFAULT now() NOT NULL, -- 添加时间
    removed_by uuid, -- 移除人ID
    removed_at timestamp with time zone, -- 移除时间
    CONSTRAINT project_reference_check CHECK (((task_id IS NULL) OR (stage_id IS NOT NULL))),
    CONSTRAINT project_reference_resource_type_check CHECK (((resource_type)::text = ANY (ARRAY[('KNOWLEDGE_DOCUMENT'::character varying)::text, ('DATA_IMPORT_JOB'::character varying)::text]))),
    CONSTRAINT project_reference_size_bytes_check CHECK ((size_bytes >= 0)),
    CONSTRAINT project_reference_source_module_check CHECK (((source_module)::text = ANY (ARRAY[('KNOWLEDGE'::character varying)::text, ('DATA_CENTER'::character varying)::text]))),
    CONSTRAINT project_reference_status_check CHECK (((status)::text = ANY (ARRAY[('ACTIVE'::character varying)::text, ('REMOVED'::character varying)::text])))
);

CREATE TABLE core.project_resource_link (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    resource_type character varying(40) NOT NULL, -- 资源类型
    resource_id uuid NOT NULL, -- 资源ID
    project_id uuid NOT NULL, -- 项目ID
    stage_id uuid, -- 阶段ID
    task_id uuid, -- 任务ID
    created_by uuid NOT NULL, -- 创建人ID
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    CONSTRAINT project_resource_link_check CHECK (((task_id IS NULL) OR (stage_id IS NOT NULL))),
    CONSTRAINT project_resource_link_resource_type_check CHECK (((resource_type)::text = ANY (ARRAY[('KNOWLEDGE_DOCUMENT'::character varying)::text, ('DATA_IMPORT_JOB'::character varying)::text])))
);


-- ==================== schema: data ====================

CREATE TABLE data.confirmed_submission (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    import_job_id uuid NOT NULL, -- 导入任务ID
    revision_no integer NOT NULL, -- 修订号
    status character varying(24) NOT NULL, -- 已确认提交状态
    content_hash character(64) NOT NULL, -- 内容哈希
    confirmed_by uuid NOT NULL, -- 确认人ID
    confirmed_at timestamp with time zone DEFAULT now() NOT NULL, -- 确认时间
    supersedes_submission_id uuid, -- 被替代的提交ID
    invalidated_at timestamp with time zone, -- 失效时间
    invalidation_reason text, -- 失效原因
    source_sequence bigint NOT NULL, -- 来源序号
    mapping_contract_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 映射契约（JSON）
    CONSTRAINT confirmed_submission_hash_check CHECK ((content_hash ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT confirmed_submission_mapping_contract_object CHECK ((jsonb_typeof(mapping_contract_jsonb) = 'object'::text)),
    CONSTRAINT confirmed_submission_revision_no_check CHECK ((revision_no > 0)),
    CONSTRAINT confirmed_submission_status_check CHECK (((status)::text = ANY (ARRAY[('CONFIRMED'::character varying)::text, ('SUPERSEDED'::character varying)::text, ('INVALIDATED'::character varying)::text])))
);

CREATE TABLE data.confirmed_submission_head (
    organization_id uuid NOT NULL, -- 组织ID
    import_job_id uuid NOT NULL, -- 导入任务ID
    confirmed_submission_id uuid NOT NULL, -- 已确认提交ID
    revision bigint DEFAULT 0 NOT NULL, -- 修订号
    updated_at timestamp with time zone DEFAULT now() NOT NULL -- 更新时间
);

CREATE TABLE data.confirmed_submission_item (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    confirmed_submission_id uuid NOT NULL, -- 已确认提交ID
    source_record_id uuid, -- 来源记录ID
    source_record_version bigint NOT NULL, -- 来源记录版本
    source_file_id uuid, -- 源文件ID
    sheet_name character varying(240), -- 工作表名称
    row_coordinate character varying(120), -- 行坐标
    mapping_version_id uuid, -- 映射版本ID
    source_identity_jsonb jsonb NOT NULL, -- 来源标识（JSON）
    raw_fact_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 原始事实（JSON）
    corrected_fact_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 修正后事实（JSON）
    fact_jsonb jsonb NOT NULL, -- 事实（JSON）
    source_coordinates_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 来源坐标（JSON）
    source_file_sha256 character(64) NOT NULL, -- 源文件哈希（SHA-256）
    content_hash character(64) NOT NULL, -- 内容哈希
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    CONSTRAINT confirmed_submission_coordinates_object CHECK ((jsonb_typeof(source_coordinates_jsonb) = 'object'::text)),
    CONSTRAINT confirmed_submission_corrected_fact_object CHECK ((jsonb_typeof(corrected_fact_jsonb) = 'object'::text)),
    CONSTRAINT confirmed_submission_fact_object CHECK ((jsonb_typeof(fact_jsonb) = 'object'::text)),
    CONSTRAINT confirmed_submission_file_hash_check CHECK ((source_file_sha256 ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT confirmed_submission_identity_object CHECK ((jsonb_typeof(source_identity_jsonb) = 'object'::text)),
    CONSTRAINT confirmed_submission_item_hash_check CHECK ((content_hash ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT confirmed_submission_raw_fact_object CHECK ((jsonb_typeof(raw_fact_jsonb) = 'object'::text))
);

CREATE TABLE data.data_category (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    name character varying(120) NOT NULL, -- 数据分类名称
    sort_order integer DEFAULT 0 NOT NULL, -- 排序号
    created_by uuid NOT NULL, -- 创建人ID
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    updated_at timestamp with time zone DEFAULT now() NOT NULL, -- 更新时间
    description character varying(240) -- 数据分类描述
);

CREATE TABLE data.data_record (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    import_job_id uuid NOT NULL, -- 导入任务ID
    record_key character varying(260) NOT NULL, -- 记录键
    record_index integer NOT NULL, -- 记录索引
    sheet_id character varying(160), -- 工作表ID
    sheet_name character varying(260), -- 工作表名称
    source_row_number integer, -- 来源行号
    raw_data_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 原始数据（JSON）
    normalized_data_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 归一化数据（JSON）
    corrected_data_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 修正后数据（JSON）
    effective_data_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 生效数据（JSON）
    quality_status character varying(24) DEFAULT 'VALID'::character varying NOT NULL, -- 质量状态
    synthetic_key boolean DEFAULT false NOT NULL, -- 合成键
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    CONSTRAINT data_record_quality_status_check CHECK (((quality_status)::text = ANY (ARRAY[('VALID'::character varying)::text, ('WARNING'::character varying)::text, ('BLOCKED'::character varying)::text])))
);

CREATE TABLE data.data_value (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    record_id uuid NOT NULL, -- 记录ID
    field_code character varying(160) NOT NULL, -- 字段编码
    data_path character varying(400), -- 数据路径
    value_jsonb jsonb DEFAULT 'null'::jsonb NOT NULL, -- 取值（JSON）
    value_text text, -- 取值文本
    normalized_unit character varying(64), -- 归一化单位
    source_anchor_id uuid, -- 来源锚点ID
    training_eligible boolean DEFAULT true NOT NULL, -- 是否可训练
    exclusion_reason character varying(200), -- 排除原因
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    binding_id character varying(120), -- 绑定ID
    value_path character varying(500), -- 取值路径
    label_path character varying(800), -- 标签路径
    rag_eligible boolean DEFAULT true NOT NULL, -- 是否可参与检索
    value_source character varying(24) DEFAULT 'INPUT'::character varying NOT NULL, -- 取值来源
    calculation_source character varying(24), -- 计算来源
    calculation_status character varying(24), -- 计算状态
    calculation_trust_status character varying(32), -- 计算可信状态
    CONSTRAINT data_value_calculation_source_check CHECK (((calculation_source IS NULL) OR ((calculation_source)::text = ANY (ARRAY[('RECALCULATED'::character varying)::text, ('CACHED'::character varying)::text, ('MISSING'::character varying)::text])))),
    CONSTRAINT data_value_calculation_status_check CHECK (((calculation_status IS NULL) OR ((calculation_status)::text = ANY (ARRAY[('VALID'::character varying)::text, ('STALE_POSSIBLE'::character varying)::text, ('FAILED'::character varying)::text])))),
    CONSTRAINT data_value_calculation_trust_status_check CHECK (((calculation_trust_status IS NULL) OR ((calculation_trust_status)::text = ANY (ARRAY[('NOT_APPLICABLE'::character varying)::text, ('TRUSTED_RECALCULATED'::character varying)::text, ('UNVERIFIED_CACHE'::character varying)::text, ('MISSING_RESULT'::character varying)::text])))),
    CONSTRAINT data_value_value_source_check CHECK (((value_source)::text = ANY (ARRAY[('INPUT'::character varying)::text, ('FORMULA'::character varying)::text, ('DERIVED'::character varying)::text, ('STATIC'::character varying)::text])))
);

CREATE TABLE data.import_component_override (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    import_job_id uuid NOT NULL, -- 导入任务ID
    component_id character varying(256) NOT NULL, -- 组件ID
    sheet_id character varying(256) NOT NULL, -- 工作表ID
    source_range character varying(64) NOT NULL, -- 来源范围
    reason character varying(500) NOT NULL, -- 原因
    created_by uuid NOT NULL, -- 创建人ID
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    updated_at timestamp with time zone DEFAULT now() NOT NULL -- 更新时间
);

CREATE TABLE data.import_experiment_link (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    import_job_id uuid NOT NULL, -- 导入任务ID
    assembly_key character varying(240) NOT NULL, -- 装配键
    parent_assembly_key character varying(240), -- 父装配键
    source_identity character varying(260), -- 来源标识
    source_identity_type character varying(32), -- 来源标识类型
    source_record_keys_jsonb jsonb DEFAULT '[]'::jsonb NOT NULL, -- 来源记录键列表（JSON）
    shared_context_record_keys_jsonb jsonb DEFAULT '[]'::jsonb NOT NULL, -- 共享上下文记录键（JSON）
    plan_hash character(64) NOT NULL, -- 计划哈希
    content_hash character(64) NOT NULL, -- 内容哈希
    status character varying(32) NOT NULL, -- 导入实验关联状态
    resolution_action character varying(32), -- 处理动作
    resolution_reason character varying(500), -- 处理原因
    conflicts_jsonb jsonb DEFAULT '[]'::jsonb NOT NULL, -- 冲突信息（JSON）
    warnings_jsonb jsonb DEFAULT '[]'::jsonb NOT NULL, -- 警告列表（JSON）
    experiment_id uuid, -- 实验ID
    experiment_version_id uuid, -- 实验版本ID
    experiment_no character varying(80), -- 实验编号
    error_message text, -- 错误信息
    created_by uuid NOT NULL, -- 创建人ID
    resolved_by uuid, -- 解决人ID
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    resolved_at timestamp with time zone, -- 解决时间
    updated_at timestamp with time zone DEFAULT now() NOT NULL, -- 更新时间
    recognition_job_id uuid NOT NULL, -- 识别任务ID
    confirmed_submission_id uuid, -- 已确认提交ID
    experiment_boundary_id character varying(240) NOT NULL, -- 实验边界ID
    sample_boundary_ids_jsonb jsonb DEFAULT '[]'::jsonb NOT NULL, -- 样品边界ID列表（JSON）
    logical_sample_keys_jsonb jsonb DEFAULT '[]'::jsonb NOT NULL, -- 逻辑样品键列表（JSON）
    source_group_keys_jsonb jsonb DEFAULT '[]'::jsonb NOT NULL, -- 来源分组键（JSON）
    source_snapshot_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 来源快照（JSON）
    source_updated boolean DEFAULT false NOT NULL, -- 来源更新时间
    CONSTRAINT import_experiment_link_logical_samples_array CHECK ((jsonb_typeof(logical_sample_keys_jsonb) = 'array'::text)),
    CONSTRAINT import_experiment_link_resolution_check CHECK (((resolution_action IS NULL) OR ((resolution_action)::text = 'SPLIT_BY_RECORD'::text))),
    CONSTRAINT import_experiment_link_sample_boundaries_array CHECK ((jsonb_typeof(sample_boundary_ids_jsonb) = 'array'::text)),
    CONSTRAINT import_experiment_link_source_groups_array CHECK ((jsonb_typeof(source_group_keys_jsonb) = 'array'::text)),
    CONSTRAINT import_experiment_link_source_snapshot_object CHECK ((jsonb_typeof(source_snapshot_jsonb) = 'object'::text)),
    CONSTRAINT import_experiment_link_status_check CHECK (((status)::text = ANY (ARRAY[('READY'::character varying)::text, ('NEEDS_REVIEW'::character varying)::text, ('RUNNING'::character varying)::text, ('SYNCED'::character varying)::text, ('ALREADY_CREATED'::character varying)::text, ('BLOCKED'::character varying)::text, ('FAILED'::character varying)::text, ('SKIPPED'::character varying)::text])))
);

CREATE TABLE data.import_issue (
    id uuid NOT NULL, -- 主键ID
    import_job_id uuid NOT NULL, -- 导入任务ID
    staging_row_id uuid, -- 暂存行ID
    import_sheet_id uuid, -- 导入工作表ID
    field_code character varying(160), -- 字段编码
    severity character varying(16) NOT NULL, -- 严重级别
    issue_type character varying(80) NOT NULL, -- 问题类型
    source_row_number integer, -- 来源行号
    source_column character varying(32), -- 来源列
    source_address character varying(80), -- 来源地址
    message text NOT NULL, -- 导入问题信息
    detail_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 明细（JSON）
    status character varying(24) DEFAULT 'OPEN'::character varying NOT NULL, -- 导入问题状态
    resolved_by uuid, -- 解决人ID
    resolved_at timestamp with time zone, -- 解决时间
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    resolution_reason character varying(500), -- 处理原因
    CONSTRAINT import_issue_severity_check CHECK (((severity)::text = ANY (ARRAY[('INFO'::character varying)::text, ('WARNING'::character varying)::text, ('BLOCKER'::character varying)::text]))),
    CONSTRAINT import_issue_status_check CHECK (((status)::text = ANY (ARRAY[('OPEN'::character varying)::text, ('CONFIRMED'::character varying)::text, ('RESOLVED'::character varying)::text, ('IGNORED'::character varying)::text])))
);

CREATE TABLE data.import_job (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    source_file_id uuid NOT NULL, -- 源文件ID
    source_sha256 character(64) NOT NULL, -- 来源哈希（SHA-256）
    source_file_name character varying(260) NOT NULL, -- 源文件名
    source_format character varying(16) NOT NULL, -- 来源格式
    template_version_id uuid, -- 模板版本ID
    status character varying(32) NOT NULL, -- 导入任务状态
    progress smallint DEFAULT 0 NOT NULL, -- 进度
    current_stage character varying(120), -- 当前阶段
    parser_version character varying(80), -- 解析器版本
    duplicate_override boolean DEFAULT false NOT NULL, -- 重复覆盖标记
    error_message text, -- 错误信息
    created_by uuid NOT NULL, -- 创建人ID
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    updated_at timestamp with time zone DEFAULT now() NOT NULL, -- 更新时间
    completed_at timestamp with time zone, -- 完成时间
    category_id uuid, -- 分类ID
    import_contract_version integer, -- 导入契约版本
    contract_hash character(64), -- 契约哈希
    source_file_hash character(64), -- 源文件哈希
    compatibility_status character varying(24) DEFAULT 'LEGACY'::character varying NOT NULL, -- 兼容性状态
    compatibility_report_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 兼容性报告（JSON）
    import_purpose character varying(32) DEFAULT 'DATA_ONLY'::character varying NOT NULL, -- 导入用途
    target_experiment_category_id uuid, -- 目标实验分类ID
    source_owner character varying(24) DEFAULT 'DATA_CENTER'::character varying NOT NULL, -- 来源所有者
    recognition_mode character varying(32) DEFAULT 'TEMPLATE_GUIDED'::character varying NOT NULL, -- 识别模式
    recognition_workspace_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 识别工作区（JSON）
    experiment_boundaries_jsonb jsonb DEFAULT '[]'::jsonb NOT NULL, -- 实验边界（JSON）
    recognition_revision bigint DEFAULT 0 NOT NULL, -- 识别修订号
    recognition_profile_id uuid, -- 识别配置ID
    visibility character varying(24) DEFAULT 'ALL'::character varying NOT NULL, -- 可见性
    source_sequence bigint DEFAULT 1 NOT NULL, -- 来源序号
    finalized_at timestamp with time zone, -- 定稿时间
    hidden_at timestamp with time zone, -- 实验导入历史软隐藏时间；不删除来源文件、追溯数据或已生成实验
    CONSTRAINT import_job_compatibility_status_check CHECK (((compatibility_status)::text = ANY (ARRAY[('LEGACY'::character varying)::text, ('EXACT'::character varying)::text, ('COMPATIBLE'::character varying)::text, ('REVIEW_REQUIRED'::character varying)::text, ('INCOMPATIBLE'::character varying)::text]))),
    CONSTRAINT import_job_experiment_boundaries_array CHECK ((jsonb_typeof(experiment_boundaries_jsonb) = 'array'::text)),
    CONSTRAINT import_job_import_purpose_check CHECK (((import_purpose)::text = ANY (ARRAY[('DATA_ONLY'::character varying)::text, ('EXPERIMENT_DRAFT'::character varying)::text]))),
    CONSTRAINT import_job_progress_check CHECK (((progress >= 0) AND (progress <= 100))),
    CONSTRAINT import_job_recognition_contract_check CHECK (((((recognition_mode)::text = 'TEMPLATE_GUIDED'::text) AND (template_version_id IS NOT NULL)) OR ((recognition_mode)::text = 'FREEFORM'::text))),
    CONSTRAINT import_job_recognition_mode_check CHECK (((recognition_mode)::text = ANY (ARRAY[('FREEFORM'::character varying)::text, ('TEMPLATE_GUIDED'::character varying)::text]))),
    CONSTRAINT import_job_recognition_workspace_object CHECK ((jsonb_typeof(recognition_workspace_jsonb) = 'object'::text)),
    CONSTRAINT import_job_source_format_check CHECK (((source_format)::text = ANY (ARRAY[('XLS'::character varying)::text, ('XLSX'::character varying)::text, ('CSV'::character varying)::text, ('DOCX'::character varying)::text, ('PDF'::character varying)::text, ('IMAGE'::character varying)::text]))),
    CONSTRAINT import_job_source_owner_check CHECK (((source_owner)::text = ANY (ARRAY[('DATA_CENTER'::character varying)::text, ('EXPERIMENT'::character varying)::text]))),
    CONSTRAINT import_job_status_check CHECK (((status)::text = ANY (ARRAY[('CREATED'::character varying)::text, ('QUEUED'::character varying)::text, ('PARSING'::character varying)::text, ('WAITING_SHEET'::character varying)::text, ('WAITING_MAPPING'::character varying)::text, ('VALIDATING'::character varying)::text, ('WAITING_CONFIRM'::character varying)::text, ('COMMITTING'::character varying)::text, ('COMPLETED'::character varying)::text, ('FAILED'::character varying)::text, ('CANCELLED'::character varying)::text]))),
    CONSTRAINT import_job_visibility_check CHECK (((visibility)::text = ANY (ARRAY[('ALL'::character varying)::text, ('QUALITY'::character varying)::text, ('PROJECT'::character varying)::text])))
);

CREATE TABLE data.import_job_attempt (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    import_job_id uuid NOT NULL, -- 导入任务ID
    attempt_no integer NOT NULL, -- 尝试编号
    reason character varying(40) NOT NULL, -- 原因
    status character varying(32) NOT NULL, -- 导入尝试状态
    snapshot_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 快照（JSON）
    created_by uuid, -- 创建人ID
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    CONSTRAINT import_job_attempt_attempt_no_check CHECK ((attempt_no > 0))
);

CREATE TABLE data.import_mapping (
    id uuid NOT NULL, -- 主键ID
    import_job_id uuid NOT NULL, -- 导入任务ID
    import_sheet_id uuid, -- 导入工作表ID
    source_column character varying(32) NOT NULL, -- 来源列
    source_header character varying(260), -- 来源表头
    field_code character varying(160), -- 字段编码
    field_name character varying(200), -- 字段名称
    action character varying(24) NOT NULL, -- 操作
    value_type character varying(32), -- 取值类型
    source_unit character varying(64), -- 来源单位
    standard_unit character varying(64), -- 标准单位
    mapping_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 映射（JSON）
    status character varying(24) DEFAULT 'PENDING'::character varying NOT NULL, -- 导入映射状态
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    updated_at timestamp with time zone DEFAULT now() NOT NULL, -- 更新时间
    CONSTRAINT import_mapping_action_check CHECK (((action)::text = ANY (ARRAY[('MAP'::character varying)::text, ('IGNORE'::character varying)::text, ('REQUEST_FIELD'::character varying)::text, ('PENDING'::character varying)::text]))),
    CONSTRAINT import_mapping_status_check CHECK (((status)::text = ANY (ARRAY[('PENDING'::character varying)::text, ('MATCHED'::character varying)::text, ('CONFIRMED'::character varying)::text, ('IGNORED'::character varying)::text, ('REQUESTED'::character varying)::text])))
);

CREATE TABLE data.import_mapping_profile (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    template_version_id uuid NOT NULL, -- 模板版本ID
    source_fingerprint character(64) NOT NULL, -- 来源指纹
    mapping_jsonb jsonb DEFAULT '[]'::jsonb NOT NULL, -- 映射（JSON）
    approved_by uuid, -- 审批人ID
    approved_at timestamp with time zone, -- 审批通过时间
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    updated_at timestamp with time zone DEFAULT now() NOT NULL -- 更新时间
);

CREATE TABLE data.import_sheet (
    id uuid NOT NULL, -- 主键ID
    import_job_id uuid NOT NULL, -- 导入任务ID
    sheet_id character varying(160) NOT NULL, -- 工作表ID
    sheet_name character varying(260) NOT NULL, -- 工作表名称
    sheet_order integer DEFAULT 0 NOT NULL, -- 工作表顺序
    selected boolean DEFAULT true NOT NULL, -- 是否选中
    header_rows_jsonb jsonb DEFAULT '[]'::jsonb NOT NULL, -- 表头行（JSON）
    data_start_row integer, -- 数据起始行
    data_end_row integer, -- 数据结束行
    structure_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 结构（JSON）
    confirmation_status character varying(24) DEFAULT 'PENDING'::character varying NOT NULL, -- 确认状态
    updated_at timestamp with time zone DEFAULT now() NOT NULL, -- 更新时间
    CONSTRAINT import_sheet_confirmation_status_check CHECK (((confirmation_status)::text = ANY (ARRAY[('PENDING'::character varying)::text, ('CONFIRMED'::character varying)::text, ('IGNORED'::character varying)::text]))),
    CONSTRAINT import_sheet_sheet_order_check CHECK ((sheet_order >= 0))
);

CREATE TABLE data.import_workbook_draft (
    organization_id uuid NOT NULL, -- 组织ID
    import_job_id uuid NOT NULL, -- 导入任务ID
    snapshot_jsonb jsonb NOT NULL, -- 快照（JSON）
    instance_jsonb jsonb NOT NULL, -- 实例数据（JSON）
    operations_jsonb jsonb DEFAULT '[]'::jsonb NOT NULL, -- 操作列表（JSON）
    updated_by uuid NOT NULL, -- 更新人ID
    updated_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL, -- 更新时间
    CONSTRAINT import_workbook_draft_instance_jsonb_check CHECK ((jsonb_typeof(instance_jsonb) = 'object'::text)),
    CONSTRAINT import_workbook_draft_operations_jsonb_check CHECK ((jsonb_typeof(operations_jsonb) = 'array'::text)),
    CONSTRAINT import_workbook_draft_snapshot_jsonb_check CHECK ((jsonb_typeof(snapshot_jsonb) = 'object'::text))
);

CREATE TABLE data.publication (
    organization_id uuid NOT NULL, -- 组织ID
    import_job_id uuid NOT NULL, -- 导入任务ID
    status character varying(24) DEFAULT 'DRAFT'::character varying NOT NULL, -- 发布状态
    confirmed_snapshot_id uuid, -- 已确认快照ID
    published_snapshot_id uuid, -- 已发布快照ID
    legacy_published boolean DEFAULT false NOT NULL, -- 历史发布标记
    published_enabled boolean DEFAULT true NOT NULL, -- 是否已发布启用
    reviewer_id uuid, -- 审核人ID
    updated_at timestamp with time zone DEFAULT now() NOT NULL, -- 更新时间
    CONSTRAINT publication_status_check CHECK (((status)::text = ANY ((ARRAY['DRAFT'::character varying, 'CONFIRMED'::character varying, 'PENDING_REVIEW'::character varying, 'RETURNED'::character varying, 'APPROVED'::character varying, 'PUBLISHED'::character varying, 'DISABLED'::character varying])::text[])))
);

CREATE TABLE data.publication_event (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    import_job_id uuid NOT NULL, -- 导入任务ID
    snapshot_id uuid, -- 快照ID
    action character varying(32) NOT NULL, -- 操作
    actor_id uuid NOT NULL, -- 操作者ID
    actor_name character varying(200) NOT NULL, -- 操作者名称
    comment text, -- 备注
    created_at timestamp with time zone DEFAULT now() NOT NULL -- 创建时间
);

CREATE TABLE data.publication_release (
    snapshot_id uuid NOT NULL, -- 快照ID
    confirmed_submission_id uuid NOT NULL, -- 已确认提交ID
    published_by uuid NOT NULL, -- 发布人ID
    published_at timestamp with time zone DEFAULT now() NOT NULL -- 发布时间
);

CREATE TABLE data.publication_snapshot (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    import_job_id uuid NOT NULL, -- 导入任务ID
    version_no integer NOT NULL, -- 版本号
    content_hash character varying(64) NOT NULL, -- 内容哈希
    payload_jsonb jsonb NOT NULL, -- 载荷（JSON）
    confirmed_submission_id uuid, -- 已确认提交ID
    confirmed_by uuid NOT NULL, -- 确认人ID
    confirmed_at timestamp with time zone DEFAULT now() NOT NULL -- 确认时间
);

CREATE TABLE data.published_mapping (
    id uuid NOT NULL, -- 主键ID
    import_job_id uuid NOT NULL, -- 导入任务ID
    import_sheet_id uuid, -- 导入工作表ID
    source_column character varying(32) NOT NULL, -- 来源列
    source_header character varying(260), -- 来源表头
    field_code character varying(160), -- 字段编码
    field_name character varying(200), -- 字段名称
    action character varying(24) NOT NULL, -- 操作
    value_type character varying(32), -- 取值类型
    source_unit character varying(64), -- 来源单位
    standard_unit character varying(64), -- 标准单位
    mapping_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 映射（JSON）
    status character varying(24) DEFAULT 'PENDING'::character varying NOT NULL, -- 已发布映射状态
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    updated_at timestamp with time zone DEFAULT now() NOT NULL -- 更新时间
);

CREATE TABLE data.recognition_profile (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    profile_code character varying(160) NOT NULL, -- 配置编码
    name character varying(240) NOT NULL, -- 识别配置名称
    source_format character varying(16) NOT NULL, -- 来源格式
    status character varying(24) DEFAULT 'ACTIVE'::character varying NOT NULL, -- 识别配置状态
    rules_jsonb jsonb NOT NULL, -- 规则列表（JSON）
    profile_hash character(64) NOT NULL, -- 配置哈希
    revision bigint DEFAULT 0 NOT NULL, -- 修订号
    created_by uuid NOT NULL, -- 创建人ID
    updated_by uuid NOT NULL, -- 更新人ID
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    updated_at timestamp with time zone DEFAULT now() NOT NULL, -- 更新时间
    CONSTRAINT recognition_profile_format_check CHECK (((source_format)::text = ANY (ARRAY[('XLS'::character varying)::text, ('XLSX'::character varying)::text, ('CSV'::character varying)::text, ('DOCX'::character varying)::text, ('PDF'::character varying)::text, ('IMAGE'::character varying)::text]))),
    CONSTRAINT recognition_profile_hash_check CHECK ((profile_hash ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT recognition_profile_rules_object CHECK ((jsonb_typeof(rules_jsonb) = 'object'::text)),
    CONSTRAINT recognition_profile_status_check CHECK (((status)::text = ANY (ARRAY[('ACTIVE'::character varying)::text, ('RETIRED'::character varying)::text])))
);

CREATE TABLE data.record_governance (
    organization_id uuid NOT NULL, -- 组织ID
    import_job_id uuid NOT NULL, -- 导入任务ID
    record_id uuid NOT NULL, -- 记录ID
    display_name character varying(200), -- 显示名称
    status character varying(16) DEFAULT 'ACTIVE'::character varying NOT NULL, -- 记录治理状态
    reason text, -- 原因
    updated_by uuid NOT NULL, -- 更新人ID
    updated_at timestamp with time zone DEFAULT now() NOT NULL, -- 更新时间
    CONSTRAINT record_governance_status_check CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'DISABLED'::character varying])::text[])))
);

CREATE TABLE data.source_anchor (
    id uuid NOT NULL, -- 主键ID
    import_job_id uuid NOT NULL, -- 导入任务ID
    field_code character varying(160), -- 字段编码
    file_id uuid NOT NULL, -- 文件ID
    sheet_id character varying(160), -- 工作表ID
    sheet_name character varying(260), -- 工作表名称
    row_number integer, -- 行号
    column_number integer, -- 列号
    column_name character varying(32), -- 列名
    cell_address character varying(80), -- 单元格地址
    raw_value_jsonb jsonb, -- 原始取值（JSON）
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    binding_id character varying(160), -- 绑定ID
    value_path text, -- 取值路径
    label_path text, -- 标签路径
    value_source character varying(40), -- 取值来源
    value_status character varying(40), -- 取值状态
    record_id uuid NOT NULL -- 记录ID
);

CREATE TABLE data.staging_row (
    id uuid NOT NULL, -- 主键ID
    import_job_id uuid NOT NULL, -- 导入任务ID
    import_sheet_id uuid NOT NULL, -- 导入工作表ID
    source_row_number integer NOT NULL, -- 来源行号
    raw_values_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 原始取值列表（JSON）
    normalized_values_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 归一化取值列表（JSON）
    corrected_values_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 修正后取值（JSON）
    row_hash character(64), -- 行哈希
    status character varying(24) DEFAULT 'STAGED'::character varying NOT NULL, -- 暂存行状态
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    updated_at timestamp with time zone DEFAULT now() NOT NULL, -- 更新时间
    source_metadata_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 来源元数据（JSON）
    excluded boolean DEFAULT false NOT NULL, -- 是否排除
    exclusion_reason character varying(500), -- 排除原因
    excluded_by uuid, -- 排除人ID
    excluded_at timestamp with time zone, -- 排除时间
    source_search_vector tsvector GENERATED ALWAYS AS (to_tsvector('simple'::regconfig, COALESCE((raw_values_jsonb)::text, ''::text))) STORED, -- 来源检索向量
    CONSTRAINT staging_row_source_row_number_check CHECK ((source_row_number > 0)),
    CONSTRAINT staging_row_status_check CHECK (((status)::text = ANY (ARRAY[('STAGED'::character varying)::text, ('VALID'::character varying)::text, ('WARNING'::character varying)::text, ('BLOCKED'::character varying)::text, ('CONFIRMED'::character varying)::text])))
);


-- ==================== schema: iam ====================

CREATE TABLE iam.app_user (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    username character varying(80) NOT NULL, -- 用户名
    display_name character varying(120) NOT NULL, -- 显示名称
    dictionary_admin boolean DEFAULT false NOT NULL, -- 字典管理员
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    password_hash character varying(500), -- 密码哈希
    status character varying(20) DEFAULT 'ACTIVE'::character varying NOT NULL, -- 用户状态
    email character varying(160), -- 邮箱
    phone character varying(40), -- 电话
    department_name character varying(160), -- 部门名称
    auth_version bigint DEFAULT 0 NOT NULL, -- 鉴权版本
    last_login_at timestamp with time zone, -- 最近登录时间
    failed_login_count integer DEFAULT 0 NOT NULL, -- 登录失败次数
    locked_until timestamp with time zone, -- 锁定截止时间
    role_id uuid, -- 角色ID
    CONSTRAINT app_user_status_check CHECK (((status)::text = ANY (ARRAY[('ACTIVE'::character varying)::text, ('DISABLED'::character varying)::text])))
);

CREATE TABLE iam.login_attempt (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid, -- 组织ID
    username character varying(80) NOT NULL, -- 用户名
    ip_address character varying(64), -- IP地址
    succeeded boolean DEFAULT false NOT NULL, -- 是否成功
    created_at timestamp with time zone DEFAULT now() NOT NULL -- 创建时间
);

CREATE TABLE iam.login_session (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    user_id uuid NOT NULL, -- 用户ID
    token_hash character(64) NOT NULL, -- 令牌哈希
    issued_at timestamp with time zone DEFAULT now() NOT NULL, -- 签发时间
    last_seen_at timestamp with time zone DEFAULT now() NOT NULL, -- 最近活跃时间
    expires_at timestamp with time zone NOT NULL, -- 过期时间
    absolute_expires_at timestamp with time zone NOT NULL, -- 绝对过期时间
    revoked_at timestamp with time zone, -- 撤销时间
    auth_version bigint DEFAULT 0 NOT NULL, -- 鉴权版本
    ip_address character varying(64), -- IP地址
    user_agent character varying(500), -- 客户端标识
    remember_me boolean DEFAULT false NOT NULL -- 记住我
);

CREATE TABLE iam.organization (
    id uuid NOT NULL, -- 主键ID
    name character varying(160) NOT NULL, -- 组织名称
    created_at timestamp with time zone DEFAULT now() NOT NULL -- 创建时间
);

CREATE TABLE iam.permission_definition (
    code character varying(160) NOT NULL, -- 权限编码
    module character varying(80) NOT NULL, -- 模块
    name character varying(160) NOT NULL, -- 权限名称
    risk character varying(20) DEFAULT 'MEDIUM'::character varying NOT NULL, -- 风险
    default_scope character varying(20) DEFAULT 'ALL'::character varying NOT NULL, -- 默认范围
    enabled boolean DEFAULT true NOT NULL, -- 是否启用
    definition_version integer DEFAULT 1 NOT NULL, -- 定义版本
    updated_at timestamp with time zone DEFAULT now() NOT NULL, -- 更新时间
    CONSTRAINT permission_definition_risk_check CHECK (((risk)::text = ANY (ARRAY[('LOW'::character varying)::text, ('MEDIUM'::character varying)::text, ('HIGH'::character varying)::text, ('CRITICAL'::character varying)::text]))),
    CONSTRAINT permission_definition_scope_check CHECK (((default_scope)::text = ANY (ARRAY[('ALL'::character varying)::text, ('SELF'::character varying)::text, ('ASSIGNED'::character varying)::text, ('PROJECT'::character varying)::text, ('CATEGORY'::character varying)::text, ('SELECTED'::character varying)::text])))
);

CREATE TABLE iam.permission_level (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    code character varying(80) NOT NULL, -- 权限级别编码
    name character varying(120) NOT NULL, -- 权限级别名称
    description character varying(500), -- 权限级别描述
    builtin boolean DEFAULT false NOT NULL, -- 是否内置
    enabled boolean DEFAULT true NOT NULL, -- 是否启用
    tier integer DEFAULT 0 NOT NULL, -- 层级
    policy_version bigint DEFAULT 0 NOT NULL, -- 策略版本
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    updated_at timestamp with time zone DEFAULT now() NOT NULL -- 更新时间
);

CREATE TABLE iam.permission_level_binding (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    permission_level_id uuid NOT NULL, -- 权限级别ID
    permission_code character varying(160) NOT NULL, -- 权限编码
    scope_type character varying(32) NOT NULL, -- 范围类型
    target_ids jsonb DEFAULT '[]'::jsonb NOT NULL, -- 目标ID列表
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    updated_at timestamp with time zone DEFAULT now() NOT NULL, -- 更新时间
    menu_key character varying(180), -- 菜单键
    CONSTRAINT permission_level_scope_check CHECK (((scope_type)::text = ANY (ARRAY[('ALL'::character varying)::text, ('SELF'::character varying)::text, ('ASSIGNED'::character varying)::text, ('PROJECT'::character varying)::text, ('CATEGORY'::character varying)::text, ('SELECTED'::character varying)::text, ('ORG'::character varying)::text, ('PROJECT_GROUP'::character varying)::text, ('CREATOR'::character varying)::text, ('OWNER'::character varying)::text, ('MANAGER'::character varying)::text, ('PARTICIPANT'::character varying)::text])))
);

CREATE TABLE iam.permission_migration_issue (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    user_id uuid, -- 用户ID
    source_type character varying(40) NOT NULL, -- 来源类型
    source_id character varying(160), -- 来源ID
    issue_code character varying(80) NOT NULL, -- 问题编码
    detail_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 明细（JSON）
    status character varying(20) DEFAULT 'OPEN'::character varying NOT NULL, -- 权限迁移问题状态
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    resolved_at timestamp with time zone, -- 解决时间
    CONSTRAINT permission_migration_issue_status_check CHECK (((status)::text = ANY (ARRAY[('OPEN'::character varying)::text, ('RESOLVED'::character varying)::text, ('IGNORED'::character varying)::text])))
);

CREATE TABLE iam.permission_scope_target (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    owner_type character varying(10) NOT NULL, -- 负责人类型
    owner_id uuid NOT NULL, -- 负责人ID
    permission_code character varying(160) NOT NULL, -- 权限编码
    target_type character varying(80) NOT NULL, -- 目标类型
    target_id uuid NOT NULL, -- 目标ID
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    CONSTRAINT scope_target_owner_check CHECK (((owner_type)::text = ANY (ARRAY[('ROLE'::character varying)::text, ('USER'::character varying)::text])))
);

CREATE TABLE iam.project_member (
    organization_id uuid NOT NULL, -- 组织ID
    project_id uuid NOT NULL, -- 项目ID
    user_id uuid NOT NULL, -- 用户ID
    member_role character varying(40), -- 成员角色
    created_at timestamp with time zone DEFAULT now() NOT NULL -- 创建时间
);

CREATE TABLE iam.resource_owner_identity (
    organization_id uuid NOT NULL, -- 组织ID
    resource_type character varying(80) NOT NULL, -- 资源类型
    resource_id uuid NOT NULL, -- 资源ID
    user_id uuid NOT NULL, -- 用户ID
    relation_type character varying(32) DEFAULT 'OWNER'::character varying NOT NULL, -- 关联类型
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    CONSTRAINT resource_owner_identity_relation_type_check CHECK (((relation_type)::text = ANY (ARRAY[('OWNER'::character varying)::text, ('MANAGER'::character varying)::text, ('ASSIGNED'::character varying)::text, ('CREATOR'::character varying)::text])))
);

CREATE TABLE iam.role (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    code character varying(80) NOT NULL, -- 角色编码
    name character varying(120) NOT NULL, -- 角色名称
    builtin boolean DEFAULT false NOT NULL, -- 是否内置
    enabled boolean DEFAULT true NOT NULL, -- 是否启用
    policy_version bigint DEFAULT 0 NOT NULL, -- 策略版本
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    updated_at timestamp with time zone DEFAULT now() NOT NULL -- 更新时间
);

CREATE TABLE iam.role_menu_binding (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    role_id uuid NOT NULL, -- 角色ID
    menu_key character varying(180) NOT NULL, -- 菜单键
    created_at timestamp with time zone DEFAULT now() NOT NULL -- 创建时间
);

CREATE TABLE iam.role_permission_binding (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    role_id uuid NOT NULL, -- 角色ID
    permission_code character varying(160) NOT NULL, -- 权限编码
    effect character varying(10) NOT NULL, -- 效果
    scope_type character varying(20) NOT NULL, -- 范围类型
    target_ids jsonb DEFAULT '[]'::jsonb NOT NULL, -- 目标ID列表
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    updated_at timestamp with time zone DEFAULT now() NOT NULL, -- 更新时间
    CONSTRAINT role_binding_effect_check CHECK (((effect)::text = ANY (ARRAY[('ALLOW'::character varying)::text, ('DENY'::character varying)::text]))),
    CONSTRAINT role_binding_scope_check CHECK (((scope_type)::text = ANY (ARRAY[('ALL'::character varying)::text, ('SELF'::character varying)::text, ('ASSIGNED'::character varying)::text, ('PROJECT'::character varying)::text, ('CATEGORY'::character varying)::text, ('SELECTED'::character varying)::text]))),
    CONSTRAINT role_binding_target_check CHECK (((((effect)::text = 'DENY'::text) AND (target_ids = '[]'::jsonb)) OR ((effect)::text = 'ALLOW'::text)))
);

CREATE TABLE iam.user_menu_override (
    organization_id uuid NOT NULL, -- 组织ID
    user_id uuid NOT NULL, -- 用户ID
    menu_key character varying(180) NOT NULL, -- 菜单键
    effect character varying(10) NOT NULL, -- 效果
    updated_at timestamp with time zone DEFAULT now() NOT NULL, -- 更新时间
    CONSTRAINT user_menu_override_effect_check CHECK (((effect)::text = ANY (ARRAY[('ALLOW'::character varying)::text, ('DENY'::character varying)::text])))
);

CREATE TABLE iam.user_operation_override (
    organization_id uuid NOT NULL, -- 组织ID
    user_id uuid NOT NULL, -- 用户ID
    permission_code character varying(160) NOT NULL, -- 权限编码
    effect character varying(10) NOT NULL, -- 效果
    updated_at timestamp with time zone DEFAULT now() NOT NULL, -- 更新时间
    menu_key character varying(180) DEFAULT ''::character varying NOT NULL, -- 菜单键
    CONSTRAINT user_operation_override_effect_check CHECK (((effect)::text = ANY (ARRAY[('ALLOW'::character varying)::text, ('DENY'::character varying)::text])))
);

CREATE TABLE iam.user_permission_override (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    user_id uuid NOT NULL, -- 用户ID
    permission_code character varying(160) NOT NULL, -- 权限编码
    effect character varying(10) NOT NULL, -- 效果
    scope_type character varying(20) NOT NULL, -- 范围类型
    target_ids jsonb DEFAULT '[]'::jsonb NOT NULL, -- 目标ID列表
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    updated_at timestamp with time zone DEFAULT now() NOT NULL, -- 更新时间
    CONSTRAINT user_override_effect_check CHECK (((effect)::text = ANY (ARRAY[('ALLOW'::character varying)::text, ('DENY'::character varying)::text]))),
    CONSTRAINT user_override_scope_check CHECK (((scope_type)::text = ANY (ARRAY[('ALL'::character varying)::text, ('SELF'::character varying)::text, ('ASSIGNED'::character varying)::text, ('PROJECT'::character varying)::text, ('CATEGORY'::character varying)::text, ('SELECTED'::character varying)::text]))),
    CONSTRAINT user_override_target_check CHECK (((((effect)::text = 'DENY'::text) AND (target_ids = '[]'::jsonb)) OR ((effect)::text = 'ALLOW'::text)))
);

CREATE TABLE iam.user_permission_profile (
    organization_id uuid NOT NULL, -- 组织ID
    user_id uuid NOT NULL, -- 用户ID
    permission_level_id uuid, -- 权限级别ID
    revision bigint DEFAULT 0 NOT NULL, -- 修订号
    updated_at timestamp with time zone DEFAULT now() NOT NULL -- 更新时间
);

CREATE TABLE iam.user_role (
    organization_id uuid NOT NULL, -- 组织ID
    user_id uuid NOT NULL, -- 用户ID
    role_id uuid NOT NULL, -- 角色ID
    primary_role boolean DEFAULT false NOT NULL, -- 主角色
    created_at timestamp with time zone DEFAULT now() NOT NULL -- 创建时间
);

CREATE TABLE iam.user_scope_override (
    organization_id uuid NOT NULL, -- 组织ID
    user_id uuid NOT NULL, -- 用户ID
    permission_code character varying(160) NOT NULL, -- 权限编码
    scope_type character varying(32) NOT NULL, -- 范围类型
    target_ids jsonb DEFAULT '[]'::jsonb NOT NULL, -- 目标ID列表
    updated_at timestamp with time zone DEFAULT now() NOT NULL, -- 更新时间
    menu_key character varying(180) DEFAULT ''::character varying NOT NULL, -- 菜单键
    CONSTRAINT user_scope_override_scope_check CHECK (((scope_type)::text = ANY (ARRAY[('ALL'::character varying)::text, ('SELF'::character varying)::text, ('ASSIGNED'::character varying)::text, ('PROJECT'::character varying)::text, ('CATEGORY'::character varying)::text, ('SELECTED'::character varying)::text, ('ORG'::character varying)::text, ('PROJECT_GROUP'::character varying)::text, ('CREATOR'::character varying)::text, ('OWNER'::character varying)::text, ('MANAGER'::character varying)::text, ('PARTICIPANT'::character varying)::text])))
);


-- ==================== schema: kb ====================

CREATE TABLE kb.chunk_term (
    chunk_id uuid NOT NULL, -- 片段ID
    term character varying(160) NOT NULL, -- 词项
    term_frequency integer NOT NULL, -- 词频
    CONSTRAINT chunk_term_term_frequency_check CHECK ((term_frequency > 0))
);

CREATE TABLE kb.document (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    title character varying(260) NOT NULL, -- 知识文档标题
    status character varying(24) DEFAULT 'QUEUED'::character varying NOT NULL, -- 知识文档状态
    scan_status character varying(24) DEFAULT 'PENDING'::character varying NOT NULL, -- 扫描状态
    current_version_no integer DEFAULT 1 NOT NULL, -- 当前版本号
    parse_error text, -- 解析错误
    created_by uuid NOT NULL, -- 创建人ID
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    updated_at timestamp with time zone DEFAULT now() NOT NULL, -- 更新时间
    library_scope character varying(16) DEFAULT 'INTERNAL'::character varying NOT NULL, -- 库范围
    category_id uuid, -- 分类ID
    lifecycle_status character varying(24) DEFAULT 'ACTIVE'::character varying NOT NULL, -- 生命周期状态
    disabled_by uuid, -- 停用人ID
    disabled_at timestamp with time zone, -- 停用时间
    disabled_reason character varying(500), -- 停用原因
    source_info_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 来源信息（JSON）
    current_publication_id uuid, -- 当前发布ID
    CONSTRAINT document_current_version_no_check CHECK ((current_version_no > 0)),
    CONSTRAINT document_library_scope_check CHECK (((library_scope)::text = ANY (ARRAY[('INTERNAL'::character varying)::text, ('EXTERNAL'::character varying)::text]))),
    CONSTRAINT document_lifecycle_status_check CHECK (((lifecycle_status)::text = ANY (ARRAY[('ACTIVE'::character varying)::text, ('DISABLED'::character varying)::text]))),
    CONSTRAINT document_scan_status_check CHECK (((scan_status)::text = ANY (ARRAY[('PENDING'::character varying)::text, ('SCANNING'::character varying)::text, ('SAFE'::character varying)::text, ('REJECTED'::character varying)::text, ('UNAVAILABLE'::character varying)::text]))),
    CONSTRAINT document_status_check CHECK (((status)::text = ANY (ARRAY[('QUEUED'::character varying)::text, ('PROCESSING'::character varying)::text, ('READY'::character varying)::text, ('FAILED'::character varying)::text, ('REJECTED'::character varying)::text, ('PENDING_PROVIDER'::character varying)::text])))
);

CREATE TABLE kb.document_ai_grant (
    document_id uuid NOT NULL, -- 文档ID
    organization_id uuid NOT NULL, -- 组织ID
    status character varying(24) NOT NULL, -- 文档AI授权状态
    reason character varying(500), -- 原因
    updated_by uuid NOT NULL, -- 更新人ID
    updated_at timestamp with time zone DEFAULT now() NOT NULL, -- 更新时间
    CONSTRAINT document_ai_grant_status_check CHECK (((status)::text = ANY (ARRAY[('APPROVED'::character varying)::text, ('REVOKED'::character varying)::text, ('REJECTED'::character varying)::text])))
);

CREATE TABLE kb.document_category (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    scope character varying(16) NOT NULL, -- 分类适用范围
    name character varying(120) NOT NULL, -- 文档分类名称
    sort_order integer DEFAULT 0 NOT NULL, -- 排序号
    created_by uuid NOT NULL, -- 创建人ID
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    updated_at timestamp with time zone DEFAULT now() NOT NULL, -- 更新时间
    description character varying(240), -- 文档分类描述
    CONSTRAINT document_category_scope_check CHECK (((scope)::text = ANY (ARRAY[('INTERNAL'::character varying)::text, ('EXTERNAL'::character varying)::text])))
);

CREATE TABLE kb.document_chunk (
    id uuid NOT NULL, -- 主键ID
    document_id uuid NOT NULL, -- 文档ID
    document_version_id uuid NOT NULL, -- 文档版本ID
    chunk_no integer NOT NULL, -- 片段序号
    page_no integer, -- 页码
    section character varying(500), -- 章节
    content text NOT NULL, -- 文档片段内容
    search_vector tsvector GENERATED ALWAYS AS (to_tsvector('simple'::regconfig, content)) STORED, -- 检索向量
    embedding public.vector, -- 向量嵌入
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    parent_chunk_id uuid, -- 父片段ID
    token_length integer DEFAULT 0 NOT NULL, -- 令牌长度
    analyzer_version character varying(40) DEFAULT 'material-smartcn-v3'::character varying NOT NULL, -- 分析器版本
    embedding_model character varying(160), -- 嵌入模型
    parse_run_id uuid, -- 解析运行ID
    sheet_name character varying(260), -- 工作表名称
    cell_range character varying(160), -- 单元格区域
    paragraph_id character varying(160), -- 段落ID
    bbox_jsonb jsonb, -- 位置框（JSON）
    start_time_ms bigint, -- 开始时间（毫秒）
    end_time_ms bigint, -- 结束时间（毫秒）
    review_revision_id uuid, -- 审核修订ID
    review_node_ids_jsonb jsonb DEFAULT '[]'::jsonb NOT NULL, -- 审核节点ID列表（JSON）
    source_node_keys_jsonb jsonb DEFAULT '[]'::jsonb NOT NULL, -- 来源节点键列表（JSON）
    source_anchor_jsonb jsonb, -- 来源锚点（JSON）
    chunk_role character varying(12) DEFAULT 'CHILD'::character varying NOT NULL, -- 片段角色
    heading_path_jsonb jsonb DEFAULT '[]'::jsonb NOT NULL, -- 标题路径（JSON）
    source_anchors_jsonb jsonb DEFAULT '[]'::jsonb NOT NULL, -- 来源锚点列表（JSON）
    relations_jsonb jsonb DEFAULT '[]'::jsonb NOT NULL, -- 关联关系（JSON）
    model_token_length integer DEFAULT 0 NOT NULL, -- 模型Token长度
    CONSTRAINT ck_kb_document_chunk_model_token_length CHECK ((model_token_length >= 0)),
    CONSTRAINT ck_kb_document_chunk_role CHECK (((chunk_role)::text = ANY (ARRAY[('PARENT'::character varying)::text, ('CHILD'::character varying)::text]))),
    CONSTRAINT document_chunk_chunk_no_check CHECK ((chunk_no >= 0)),
    CONSTRAINT document_chunk_token_length_check CHECK ((token_length >= 0))
);

CREATE TABLE kb.document_parse_issue (
    id uuid NOT NULL, -- 主键ID
    parse_run_id uuid NOT NULL, -- 解析运行ID
    issue_code character varying(80) NOT NULL, -- 问题编码
    severity character varying(16) NOT NULL, -- 严重级别
    message character varying(1000) NOT NULL, -- 解析问题信息
    status character varying(24) DEFAULT 'OPEN'::character varying NOT NULL, -- 解析问题状态
    resolution character varying(1000), -- 处理结论
    updated_at timestamp with time zone DEFAULT now() NOT NULL, -- 更新时间
    CONSTRAINT document_parse_issue_severity_check CHECK (((severity)::text = ANY (ARRAY[('INFO'::character varying)::text, ('WARNING'::character varying)::text, ('BLOCKER'::character varying)::text]))),
    CONSTRAINT document_parse_issue_status_check CHECK (((status)::text = ANY (ARRAY[('OPEN'::character varying)::text, ('RESOLVED'::character varying)::text, ('IGNORED'::character varying)::text])))
);

CREATE TABLE kb.document_parse_issue_source_node (
    parse_issue_id uuid NOT NULL, -- 解析问题ID
    source_node_key uuid NOT NULL -- 来源节点键
);

CREATE TABLE kb.document_parse_run (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    document_id uuid NOT NULL, -- 文档ID
    document_version_id uuid NOT NULL, -- 文档版本ID
    run_no integer NOT NULL, -- 运行编号
    status character varying(32) NOT NULL, -- 解析运行状态
    parser_version character varying(80), -- 解析器版本
    provider character varying(120), -- 提供方
    provider_task_id character varying(260), -- 提供方任务ID
    error_message character varying(2000), -- 错误信息
    result_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 结果（JSON）
    started_at timestamp with time zone, -- 开始时间
    finished_at timestamp with time zone, -- 完成时间
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    source_document_jsonb jsonb DEFAULT '{"type": "doc", "content": [], "schemaVersion": 1}'::jsonb NOT NULL, -- 来源文档（JSON）
    document_schema_version integer DEFAULT 1 NOT NULL, -- 文档结构版本
    CONSTRAINT document_parse_run_run_no_check CHECK ((run_no > 0)),
    CONSTRAINT document_parse_run_status_check CHECK (((status)::text = ANY (ARRAY[('QUEUED'::character varying)::text, ('PROCESSING'::character varying)::text, ('SUCCEEDED'::character varying)::text, ('FAILED'::character varying)::text])))
);

CREATE TABLE kb.document_processing_step (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    document_id uuid NOT NULL, -- 文档ID
    document_version_id uuid NOT NULL, -- 文档版本ID
    step_key character varying(32) NOT NULL, -- 步骤键
    status character varying(24) DEFAULT 'PENDING'::character varying NOT NULL, -- 处理步骤状态
    progress smallint DEFAULT 0 NOT NULL, -- 进度
    attempt integer DEFAULT 0 NOT NULL, -- 尝试次数
    provider character varying(120), -- 提供方
    model character varying(160), -- 模型
    input_sha256 character(64), -- 输入哈希（SHA-256）
    output_sha256 character(64), -- 输出哈希（SHA-256）
    error_message character varying(2000), -- 错误信息
    started_at timestamp with time zone, -- 开始时间
    finished_at timestamp with time zone, -- 完成时间
    updated_at timestamp with time zone DEFAULT now() NOT NULL, -- 更新时间
    parse_run_id uuid, -- 解析运行ID
    review_revision_id uuid, -- 审核修订ID
    CONSTRAINT document_processing_step_attempt_check CHECK ((attempt >= 0)),
    CONSTRAINT document_processing_step_progress_check CHECK (((progress >= 0) AND (progress <= 100))),
    CONSTRAINT document_processing_step_status_check CHECK (((status)::text = ANY (ARRAY[('PENDING'::character varying)::text, ('STALE'::character varying)::text, ('RUNNING'::character varying)::text, ('SUCCEEDED'::character varying)::text, ('FAILED'::character varying)::text, ('PENDING_PROVIDER'::character varying)::text, ('NOT_REQUIRED'::character varying)::text]))),
    CONSTRAINT document_processing_step_step_key_check CHECK (((step_key)::text = ANY (ARRAY[('SCAN'::character varying)::text, ('PARSE'::character varying)::text, ('CHUNK'::character varying)::text, ('EMBEDDING'::character varying)::text, ('BM25_INDEX'::character varying)::text, ('VECTOR_INDEX'::character varying)::text])))
);

CREATE TABLE kb.document_result_asset (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    document_id uuid NOT NULL, -- 文档ID
    document_version_id uuid NOT NULL, -- 文档版本ID
    result_file_id uuid NOT NULL, -- 结果文件ID
    asset_file_id uuid NOT NULL, -- 资产文件ID
    entry_path character varying(1000) NOT NULL, -- 入口路径
    content_type character varying(160) NOT NULL, -- 内容类型
    size_bytes bigint NOT NULL, -- 大小（字节）
    page_no integer, -- 页码
    bbox_jsonb jsonb DEFAULT '[]'::jsonb NOT NULL, -- 位置框（JSON）
    caption text DEFAULT ''::text NOT NULL, -- 图表说明
    footnote text DEFAULT ''::text NOT NULL, -- 脚注
    ocr_text text DEFAULT ''::text NOT NULL, -- OCR文本
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    CONSTRAINT document_result_asset_size_bytes_check CHECK ((size_bytes >= 0))
);

CREATE TABLE kb.document_review_issue_state (
    review_revision_id uuid NOT NULL, -- 审核修订ID
    parse_issue_id uuid NOT NULL, -- 解析问题ID
    status character varying(24) NOT NULL, -- 审核问题状态状态
    resolution character varying(1000), -- 处理结论
    updated_by uuid, -- 更新人ID
    updated_at timestamp with time zone DEFAULT now() NOT NULL, -- 更新时间
    CONSTRAINT document_review_issue_state_status_check CHECK (((status)::text = ANY (ARRAY[('OPEN'::character varying)::text, ('RESOLVED'::character varying)::text, ('IGNORED'::character varying)::text])))
);

CREATE TABLE kb.document_review_revision (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    document_id uuid NOT NULL, -- 文档ID
    document_version_id uuid NOT NULL, -- 文档版本ID
    parse_run_id uuid NOT NULL, -- 解析运行ID
    revision_no integer NOT NULL, -- 修订号
    lock_version integer DEFAULT 0 NOT NULL, -- 乐观锁版本号
    base_publication_id uuid, -- 基础发布ID
    confirmed_document_jsonb jsonb NOT NULL, -- 已确认文档（JSON）
    confirmed_text text DEFAULT ''::text NOT NULL, -- 已确认文本
    excluded_review_node_ids jsonb DEFAULT '[]'::jsonb NOT NULL, -- 排除的审核节点ID列表
    status character varying(24) NOT NULL, -- 审核修订状态
    failure_reason character varying(2000), -- 失败原因
    created_by uuid NOT NULL, -- 创建人ID
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    updated_by uuid NOT NULL, -- 更新人ID
    updated_at timestamp with time zone DEFAULT now() NOT NULL, -- 更新时间
    published_at timestamp with time zone, -- 发布时间
    CONSTRAINT document_review_revision_lock_version_check CHECK ((lock_version >= 0)),
    CONSTRAINT document_review_revision_revision_no_check CHECK ((revision_no > 0)),
    CONSTRAINT document_review_revision_status_check CHECK (((status)::text = ANY (ARRAY[('DRAFT'::character varying)::text, ('BUILDING'::character varying)::text, ('PUBLISHED'::character varying)::text, ('FAILED'::character varying)::text, ('SUPERSEDED'::character varying)::text])))
);

CREATE TABLE kb.document_review_table_cell_patch (
    review_revision_id uuid NOT NULL, -- 审核修订ID
    source_table_id uuid NOT NULL, -- 来源表ID
    row_no integer NOT NULL, -- 行序号
    column_no integer NOT NULL, -- 列序号
    confirmed_value text NOT NULL, -- 已确认值
    updated_by uuid NOT NULL, -- 更新人ID
    updated_at timestamp with time zone DEFAULT now() NOT NULL -- 更新时间
);

CREATE TABLE kb.document_review_table_row_state (
    review_revision_id uuid NOT NULL, -- 审核修订ID
    source_table_id uuid NOT NULL, -- 来源表ID
    row_no integer NOT NULL, -- 行序号
    excluded boolean DEFAULT true NOT NULL, -- 是否排除
    header boolean DEFAULT false NOT NULL, -- 表头
    updated_by uuid NOT NULL, -- 更新人ID
    updated_at timestamp with time zone DEFAULT now() NOT NULL -- 更新时间
);

CREATE TABLE kb.document_source_node (
    source_node_key uuid NOT NULL, -- 来源节点键
    organization_id uuid NOT NULL, -- 组织ID
    parse_run_id uuid NOT NULL, -- 解析运行ID
    node_no integer NOT NULL, -- 节点编号
    node_type character varying(40) NOT NULL, -- 节点类型
    raw_text text DEFAULT ''::text NOT NULL, -- 原始文本
    source_anchor_jsonb jsonb DEFAULT '{"kind": "none", "version": 1}'::jsonb NOT NULL, -- 来源锚点（JSON）
    confidence_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 置信度明细（JSON）
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    CONSTRAINT document_source_node_node_no_check CHECK ((node_no >= 0))
);

CREATE TABLE kb.document_source_table (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    parse_run_id uuid NOT NULL, -- 解析运行ID
    source_node_key uuid NOT NULL, -- 来源节点键
    sheet_key character varying(160) NOT NULL, -- 工作表键
    sheet_name character varying(260) NOT NULL, -- 工作表名称
    row_count integer NOT NULL, -- 行数
    column_count integer NOT NULL, -- 列数
    non_empty_count integer NOT NULL -- 非空数量
);

CREATE TABLE kb.document_source_table_cell (
    source_table_id uuid NOT NULL, -- 来源表ID
    row_no integer NOT NULL, -- 行序号
    column_no integer NOT NULL, -- 列序号
    display_value text DEFAULT ''::text NOT NULL, -- 显示值
    source_anchor_jsonb jsonb NOT NULL -- 来源锚点（JSON）
);

CREATE TABLE kb.document_tag (
    document_id uuid NOT NULL, -- 文档ID
    tag_id uuid NOT NULL, -- 标签ID
    created_at timestamp with time zone DEFAULT now() NOT NULL -- 创建时间
);

CREATE TABLE kb.document_version (
    id uuid NOT NULL, -- 主键ID
    document_id uuid NOT NULL, -- 文档ID
    version_no integer NOT NULL, -- 版本号
    file_object_id uuid NOT NULL, -- 文件对象ID
    original_name character varying(260) NOT NULL, -- 原始名称
    content_type character varying(160) NOT NULL, -- 内容类型
    size_bytes bigint NOT NULL, -- 大小（字节）
    sha256 character(64) NOT NULL, -- 哈希值（SHA-256）
    parser_version character varying(40), -- 解析器版本
    status character varying(24) DEFAULT 'QUEUED'::character varying NOT NULL, -- 文档版本状态
    extracted_text_sha256 character(64), -- 抽取文本哈希（SHA-256）
    error_message text, -- 错误信息
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    updated_at timestamp with time zone DEFAULT now() NOT NULL, -- 更新时间
    review_status character varying(32) DEFAULT 'PENDING_REVIEW'::character varying NOT NULL, -- 审核状态
    review_revision integer DEFAULT 0 NOT NULL, -- 审核修订号
    review_reason character varying(1000), -- 审核原因
    reviewed_by uuid, -- 审核人ID
    reviewed_at timestamp with time zone, -- 审核时间
    source_info_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 来源信息（JSON）
    ocr_mode character varying(8) DEFAULT 'AUTO'::character varying NOT NULL, -- OCR模式
    allow_agent_fallback boolean DEFAULT false NOT NULL, -- 是否允许Agent兜底
    effective_ocr boolean, -- 生效OCR标识
    parser_mode character varying(24), -- 解析器模式
    parser_metadata_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 解析器元数据（JSON）
    CONSTRAINT ck_kb_document_version_ocr_mode CHECK (((ocr_mode)::text = ANY (ARRAY[('AUTO'::character varying)::text, ('ON'::character varying)::text, ('OFF'::character varying)::text]))),
    CONSTRAINT ck_kb_document_version_parser_mode CHECK (((parser_mode IS NULL) OR ((parser_mode)::text = ANY (ARRAY[('PRECISE'::character varying)::text, ('AGENT_FALLBACK'::character varying)::text, ('LOCAL'::character varying)::text])))),
    CONSTRAINT document_version_review_revision_check CHECK ((review_revision >= 0)),
    CONSTRAINT document_version_review_status_check CHECK (((review_status)::text = ANY (ARRAY[('PENDING_REVIEW'::character varying)::text, ('REJECTED'::character varying)::text, ('PUBLISHED'::character varying)::text, ('SUPERSEDED'::character varying)::text]))),
    CONSTRAINT document_version_size_bytes_check CHECK ((size_bytes >= 0)),
    CONSTRAINT document_version_status_check CHECK (((status)::text = ANY (ARRAY[('QUEUED'::character varying)::text, ('PROCESSING'::character varying)::text, ('READY'::character varying)::text, ('FAILED'::character varying)::text, ('REJECTED'::character varying)::text, ('PENDING_PROVIDER'::character varying)::text]))),
    CONSTRAINT document_version_version_no_check CHECK ((version_no > 0))
);

CREATE TABLE kb.publication (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    document_id uuid NOT NULL, -- 文档ID
    document_version_id uuid NOT NULL, -- 文档版本ID
    parse_run_id uuid NOT NULL, -- 解析运行ID
    publication_no integer NOT NULL, -- 发布编号
    status character varying(24) DEFAULT 'CURRENT'::character varying NOT NULL, -- 知识库发布状态
    metadata_snapshot_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 元数据快照（JSON）
    published_by uuid NOT NULL, -- 发布人ID
    published_at timestamp with time zone DEFAULT now() NOT NULL, -- 发布时间
    review_revision_id uuid NOT NULL, -- 审核修订ID
    CONSTRAINT publication_publication_no_check CHECK ((publication_no > 0)),
    CONSTRAINT publication_status_check CHECK (((status)::text = ANY (ARRAY[('CURRENT'::character varying)::text, ('SUPERSEDED'::character varying)::text])))
);

CREATE TABLE kb.tag (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    normalized_name character varying(120) NOT NULL, -- 归一化名称
    display_name character varying(120) NOT NULL, -- 显示名称
    created_by uuid NOT NULL, -- 创建人ID
    created_at timestamp with time zone DEFAULT now() NOT NULL -- 创建时间
);

CREATE TABLE kb.term_stat (
    organization_id uuid NOT NULL, -- 组织ID
    term character varying(160) NOT NULL, -- 词项
    document_frequency integer DEFAULT 0 NOT NULL, -- 文档频次
    document_count integer DEFAULT 0 NOT NULL, -- 文档数量
    average_document_length double precision DEFAULT 0 NOT NULL, -- 平均文档长度
    analyzer_version character varying(40) DEFAULT 'material-smartcn-v3'::character varying NOT NULL, -- 分析器版本
    updated_at timestamp with time zone DEFAULT now() NOT NULL, -- 更新时间
    CONSTRAINT term_stat_document_count_check CHECK ((document_count >= 0)),
    CONSTRAINT term_stat_document_frequency_check CHECK ((document_frequency >= 0))
);

CREATE TABLE kb.upload_task (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    actor_id uuid NOT NULL, -- 操作者ID
    file_name character varying(500) NOT NULL, -- 文件名
    size_bytes bigint NOT NULL, -- 大小（字节）
    status character varying(24) NOT NULL, -- 上传任务状态
    progress smallint DEFAULT 0 NOT NULL, -- 进度
    document_id uuid, -- 文档ID
    document_version_id uuid, -- 文档版本ID
    message text, -- 上传任务信息
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    updated_at timestamp with time zone DEFAULT now() NOT NULL, -- 更新时间
    source_file_id uuid, -- 源文件ID
    upload_context_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 上传上下文（JSON）
    hidden_at timestamp with time zone, -- 隐藏时间
    CONSTRAINT upload_task_progress_check CHECK (((progress >= 0) AND (progress <= 100))),
    CONSTRAINT upload_task_size_bytes_check CHECK ((size_bytes >= 0)),
    CONSTRAINT upload_task_status_check CHECK (((status)::text = ANY ((ARRAY['STAGING'::character varying, 'CHECKING'::character varying, 'SUBMITTING'::character varying, 'QUEUED'::character varying, 'COMPLETED'::character varying, 'DUPLICATE'::character varying, 'FAILED'::character varying])::text[])))
);


-- ==================== schema: mdm ====================

CREATE TABLE mdm.business_partner (
    id uuid NOT NULL, -- 业务伙伴主键
    partner_code character varying(32) NOT NULL, -- 业务伙伴编码
    name character varying(200) NOT NULL, -- 业务伙伴名称
    normalized_name character varying(200) NOT NULL, -- 标准化名称，用于去重
    industry character varying(100), -- 所属行业
    address character varying(500), -- 联系地址
    status character varying(20) NOT NULL, -- 状态：ACTIVE-启用，INACTIVE-停用
    remark character varying(1000), -- 备注
    version bigint DEFAULT 0 NOT NULL, -- 乐观锁版本号
    created_at timestamp with time zone NOT NULL, -- 创建时间
    updated_at timestamp with time zone NOT NULL, -- 最后更新时间
    created_by character varying(100) NOT NULL, -- 创建人
    updated_by character varying(100) NOT NULL, -- 最后更新人
    customer_level character varying(50), -- 客户等级
    cooperation_status character varying(50), -- 合作状态
    main_business character varying(500), -- 主营业务
    custom_fields jsonb DEFAULT '{}'::jsonb NOT NULL, -- 自定义扩展字段
    deleted boolean DEFAULT false NOT NULL, -- 逻辑删除标记：true=已删除
    deleted_at timestamp with time zone, -- 删除时间
    deleted_by character varying(160), -- 删除操作人
    CONSTRAINT business_partner_status_check CHECK (((status)::text = ANY (ARRAY[('ACTIVE'::character varying)::text, ('INACTIVE'::character varying)::text])))
);

CREATE TABLE mdm.communication_record (
    id uuid NOT NULL, -- 沟通记录主键
    record_code character varying(32) NOT NULL, -- 沟通记录编码
    partner_id uuid NOT NULL, -- 业务伙伴ID
    communicated_at timestamp with time zone NOT NULL, -- 沟通时间
    internal_participants character varying(500), -- 内部参与人员
    communication_method character varying(30) NOT NULL, -- 沟通方式
    content text NOT NULL, -- 沟通内容
    status character varying(30) NOT NULL, -- 状态：OPEN-待处理，FOLLOWING-跟进中，CLOSED-已关闭
    version bigint DEFAULT 0 NOT NULL, -- 乐观锁版本号
    created_at timestamp with time zone NOT NULL, -- 创建时间
    updated_at timestamp with time zone NOT NULL, -- 最后更新时间
    created_by character varying(100) NOT NULL, -- 创建人
    updated_by character varying(100) NOT NULL, -- 最后更新人
    custom_fields jsonb DEFAULT '{}'::jsonb NOT NULL, -- 自定义字段（JSON）
    name character varying(200), -- 沟通记录名称/主题
    deleted boolean DEFAULT false NOT NULL, -- 是否删除
    deleted_at timestamp with time zone, -- 删除时间
    deleted_by character varying(160), -- 删除操作人
    CONSTRAINT communication_record_status_check CHECK (((status)::text = ANY (ARRAY[('OPEN'::character varying)::text, ('FOLLOWING'::character varying)::text, ('CLOSED'::character varying)::text])))
);

CREATE TABLE mdm.customer_requirement (
    id uuid NOT NULL, -- 客户需求主键
    requirement_code character varying(32) NOT NULL, -- 需求编码
    partner_id uuid, -- 客户业务伙伴ID
    title character varying(200) NOT NULL, -- 需求标题
    raised_at date, -- 提出日期
    delivery_date date, -- 预计完成日期
    status character varying(30) NOT NULL, -- 状态：DRAFT-草稿，CONFIRMED-已确认，IN_PROJECT-项目中，COMPLETED-已完成，CANCELLED-已取消
    version bigint DEFAULT 0 NOT NULL, -- 乐观锁版本号
    created_at timestamp with time zone NOT NULL, -- 创建时间
    updated_at timestamp with time zone NOT NULL, -- 最后更新时间
    created_by character varying(100) NOT NULL, -- 创建人
    updated_by character varying(100) NOT NULL, -- 最后更新人
    custom_fields jsonb DEFAULT '{}'::jsonb NOT NULL, -- 自定义字段（JSON）
    assigned_project_ids jsonb DEFAULT '[]'::jsonb NOT NULL, -- 负责人负责的项目ID列表
    custom_status_name character varying(50), -- 自定义状态名称
    urgency character varying(20), -- 紧急程度
    raw_requirement text, -- 需求总结
    project_id uuid, -- 项目ID
    deleted boolean DEFAULT false NOT NULL, -- 逻辑删除标记：true=已删除
    deleted_at timestamp with time zone, -- 删除时间
    deleted_by character varying(160), -- 删除操作人
    CONSTRAINT customer_requirement_status_check CHECK (((status)::text = ANY (ARRAY[('DRAFT'::character varying)::text, ('CONFIRMED'::character varying)::text, ('IN_PROJECT'::character varying)::text, ('COMPLETED'::character varying)::text, ('CANCELLED'::character varying)::text])))
);

CREATE TABLE mdm.customer_requirement_project (
    requirement_id uuid NOT NULL, -- 需求ID
    project_id uuid NOT NULL, -- 项目ID
    id uuid NOT NULL, -- 主键ID
    created_at timestamp with time zone NOT NULL, -- 创建时间
    updated_at timestamp with time zone NOT NULL, -- 更新时间
    created_by character varying(100) NOT NULL, -- 创建人ID
    updated_by character varying(100) NOT NULL -- 更新人ID
);

CREATE TABLE mdm.material (
    id uuid DEFAULT gen_random_uuid() NOT NULL, -- 主键ID
    code character varying(64) NOT NULL, -- 物料编码
    name character varying(200) NOT NULL, -- 物料名称
    category character varying(50) NOT NULL, -- 资料类型：标准/规范/图纸/文档/其他
    source_category character varying(50) NOT NULL, -- 来源分类：内部/外部
    source_module character varying(50) NOT NULL, -- 来源模块：项目库/工艺库/法规库/客户库
    stage character varying(30), -- 所属阶段：调研/方案/实施/验收/交付
    contact_person character varying(50), -- 联系人
    status character varying(30) DEFAULT 'DRAFT'::character varying NOT NULL, -- 资料状态：DRAFT/PUBLISHED/ARCHIVED
    description text, -- 物料描述
    version bigint DEFAULT 0 NOT NULL, -- 物料版本号
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    created_by character varying(100) DEFAULT 'system'::character varying NOT NULL, -- 创建人ID
    updated_at timestamp with time zone DEFAULT now() NOT NULL, -- 更新时间
    updated_by character varying(100) -- 更新人ID
);

CREATE TABLE mdm.material_alias (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    material_id uuid NOT NULL, -- 物料ID
    normalized_alias character varying(240) NOT NULL, -- 归一化别名
    display_alias character varying(240) NOT NULL, -- 显示别名
    status character varying(24) DEFAULT 'ACTIVE'::character varying NOT NULL, -- 物料别名状态
    revision bigint DEFAULT 0 NOT NULL, -- 修订号
    created_by uuid NOT NULL, -- 创建人ID
    updated_by uuid NOT NULL, -- 更新人ID
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    updated_at timestamp with time zone DEFAULT now() NOT NULL, -- 更新时间
    CONSTRAINT material_alias_status_check CHECK (((status)::text = ANY (ARRAY[('ACTIVE'::character varying)::text, ('RETIRED'::character varying)::text])))
);

CREATE TABLE mdm.meeting_minutes (
    id uuid DEFAULT gen_random_uuid() NOT NULL, -- 主键ID
    project_id uuid NOT NULL, -- 项目ID
    title character varying(200) NOT NULL, -- 会议纪要标题
    attendees jsonb DEFAULT '[]'::jsonb NOT NULL, -- 参会人员
    summary text, -- 会议纪要摘要
    occurred_at timestamp with time zone DEFAULT now() NOT NULL, -- 发生时间
    archived_to_kb boolean DEFAULT false NOT NULL, -- 是否归档到知识库
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    created_by character varying(100) DEFAULT 'system'::character varying NOT NULL, -- 创建人ID
    updated_at timestamp with time zone DEFAULT now() NOT NULL, -- 更新时间
    version bigint DEFAULT 0 NOT NULL, -- 会议纪要版本号
    updated_by character varying(100) DEFAULT 'system'::character varying NOT NULL -- 更新人ID
);

CREATE TABLE mdm.partner_contact (
    id uuid NOT NULL, -- 联系人主键
    partner_id uuid NOT NULL, -- 所属业务伙伴ID
    name character varying(100), -- 联系人姓名
    department character varying(100), -- 所属部门
    title character varying(100), -- 职务
    phone character varying(50), -- 联系电话
    email character varying(200), -- 电子邮箱
    wechat character varying(200), -- 微信号
    status character varying(20) NOT NULL, -- 状态：ACTIVE-启用，INACTIVE-停用
    version bigint DEFAULT 0 NOT NULL, -- 乐观锁版本号
    created_at timestamp with time zone NOT NULL, -- 创建时间
    updated_at timestamp with time zone NOT NULL, -- 最后更新时间
    created_by character varying(100) NOT NULL, -- 创建人
    updated_by character varying(100) NOT NULL, -- 最后更新人
    custom_fields jsonb DEFAULT '{}'::jsonb NOT NULL, -- 自定义扩展字段
    assigned_project_ids jsonb DEFAULT '[]'::jsonb NOT NULL, -- 关联的项目ID列表
    members character varying(1000) DEFAULT ''::character varying NOT NULL, -- 团队成员（逗号分隔）
    deleted boolean DEFAULT false NOT NULL, -- 逻辑删除标记：true=已删除
    deleted_at timestamp with time zone, -- 删除时间
    deleted_by character varying(160), -- 删除操作人
    CONSTRAINT partner_contact_status_check CHECK (((status)::text = ANY (ARRAY[('ACTIVE'::character varying)::text, ('INACTIVE'::character varying)::text])))
);

CREATE TABLE mdm.partner_contact_project (
    contact_id uuid NOT NULL, -- 联系人ID
    project_id uuid NOT NULL, -- 项目ID
    id uuid, -- 主键ID
    created_at timestamp with time zone NOT NULL, -- 创建时间
    updated_at timestamp with time zone NOT NULL, -- 更新时间
    created_by character varying(100) NOT NULL, -- 创建人ID
    updated_by character varying(100) NOT NULL -- 更新人ID
);

CREATE TABLE mdm.project (
    id uuid NOT NULL, -- 主键ID
    project_code character varying(64) NOT NULL, -- 项目编码
    name character varying(300) NOT NULL, -- 项目名称
    partner_id uuid, -- 往来单位ID
    partner_name character varying(300), -- 往来单位名称
    owner character varying(100), -- 负责人
    start_date date, -- 开始日期
    end_date date, -- 结束日期
    priority character varying(20) DEFAULT 'MEDIUM'::character varying NOT NULL, -- 优先级
    status character varying(30) DEFAULT 'PENDING'::character varying NOT NULL, -- 状态：NOT_STARTED/IN_PROGRESS/PAUSED/COMPLETED/CANCELLED
    team_size integer DEFAULT 0 NOT NULL, -- 团队人数
    background text, -- 背景
    deleted boolean DEFAULT false NOT NULL, -- 是否删除
    custom_fields jsonb DEFAULT '{}'::jsonb NOT NULL, -- 自定义字段
    version bigint DEFAULT 0 NOT NULL, -- 项目版本号
    created_at timestamp with time zone NOT NULL, -- 创建时间
    updated_at timestamp with time zone NOT NULL, -- 更新时间
    created_by character varying(100) NOT NULL, -- 创建人ID
    updated_by character varying(100) NOT NULL, -- 更新人ID
    team_members jsonb DEFAULT '[]'::jsonb NOT NULL, -- 团队成员
    organization_id uuid NOT NULL, -- 组织ID
    CONSTRAINT project_priority_check CHECK (((priority)::text = ANY (ARRAY[('HIGH'::character varying)::text, ('MEDIUM'::character varying)::text, ('LOW'::character varying)::text]))),
    CONSTRAINT project_status_check CHECK (((status)::text = ANY (ARRAY[('NOT_STARTED'::character varying)::text, ('IN_PROGRESS'::character varying)::text, ('PAUSED'::character varying)::text, ('COMPLETED'::character varying)::text, ('CANCELLED'::character varying)::text]))),
    CONSTRAINT project_team_size_check CHECK ((team_size >= 0))
);

CREATE TABLE mdm.project_document (
    id uuid NOT NULL, -- 主键ID
    project_id uuid NOT NULL, -- 所属研发项目ID
    title character varying(260) NOT NULL, -- 文档名称
    format character varying(16) NOT NULL, -- 文档格式：DOCX/Excel/其他
    source character varying(16) DEFAULT 'BLANK'::character varying NOT NULL, -- 创建来源：按模板新增/空白新增/导入文件
    template_id uuid, -- 源模板ID（按模板新增时填写）
    template_version_id uuid, -- 源模板版本ID（按模板新增时填写，用于引用模板中心查看）
    file_object_id uuid, -- 实际文件对象ID（复用 ops.file_object 存储）
    status character varying(24) DEFAULT 'DRAFT'::character varying NOT NULL, -- 文档状态：草稿/已发布/已归档
    version bigint DEFAULT 0 NOT NULL, -- 项目文档版本号
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    created_by character varying(100) DEFAULT 'system'::character varying NOT NULL, -- 创建人ID
    updated_at timestamp with time zone DEFAULT now() NOT NULL, -- 更新时间
    updated_by character varying(100), -- 更新人ID
    deleted boolean DEFAULT false NOT NULL, -- 逻辑删除标记
    content_snapshot jsonb, -- 项目文档独立编辑快照；首次打开模板文档时复制模板快照
    content_schema jsonb DEFAULT '{}'::jsonb NOT NULL, -- 内容结构定义
    content_mapping jsonb DEFAULT '[]'::jsonb NOT NULL, -- 内容映射
    content_data jsonb DEFAULT '{}'::jsonb NOT NULL, -- 内容数据
    content_recognition jsonb DEFAULT '{}'::jsonb NOT NULL, -- 项目文档独立识别结果：含解析结构(structureSummary)、编辑器快照(initialEditorSnapshot)与规则识别候选(ruleSuggestions)；与模板中心解耦
    content_structure jsonb DEFAULT '{}'::jsonb NOT NULL, -- 项目文档 Word 章节结构：含 DOCUMENT_TITLE / HEADING 节点，用于左侧文档目录展示；与模板中心解耦
    current_version_id uuid, -- 当前版本ID
    CONSTRAINT project_document_format_check CHECK (((format)::text = ANY (ARRAY[('DOCX'::character varying)::text, ('XLSX'::character varying)::text, ('OTHER'::character varying)::text]))),
    CONSTRAINT project_document_source_check CHECK (((source)::text = ANY (ARRAY[('TEMPLATE'::character varying)::text, ('BLANK'::character varying)::text, ('IMPORT'::character varying)::text]))),
    CONSTRAINT project_document_status_check CHECK (((status)::text = ANY (ARRAY[('DRAFT'::character varying)::text, ('PUBLISHED'::character varying)::text, ('ARCHIVED'::character varying)::text])))
);

CREATE TABLE mdm.project_document_audit (
    id uuid NOT NULL, -- 主键ID
    document_id uuid NOT NULL, -- 文档ID
    action character varying(32) NOT NULL, -- 操作
    before_jsonb jsonb, -- 变更前数据（JSON）
    after_jsonb jsonb, -- 变更后数据（JSON）
    operator_id character varying(64), -- 操作人ID
    operator_name character varying(128), -- 操作人名称
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    document_version_id uuid, -- 文档版本ID
    trace_id character varying(100) -- 链路追踪ID
);

CREATE TABLE mdm.project_document_review (
    id uuid NOT NULL, -- 主键ID
    document_id uuid NOT NULL, -- 文档ID
    action text NOT NULL, -- 操作
    operator_id uuid NOT NULL, -- 操作人ID
    operator_name text, -- 操作人名称
    comment text, -- 备注
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    document_version_id uuid -- 文档版本ID
);

CREATE TABLE mdm.project_document_version (
    id uuid NOT NULL, -- 主键ID
    document_id uuid NOT NULL, -- 文档ID
    version_no integer NOT NULL, -- 版本号
    content_snapshot jsonb, -- 内容快照
    content_schema jsonb DEFAULT '{}'::jsonb NOT NULL, -- 内容结构定义
    content_mapping jsonb DEFAULT '[]'::jsonb NOT NULL, -- 内容映射
    content_data jsonb DEFAULT '{}'::jsonb NOT NULL, -- 内容数据
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    created_by character varying(100) NOT NULL, -- 创建人ID
    template_version_id uuid, -- 模板版本ID
    template_snapshot_hash character varying(64), -- 模板快照哈希
    submitted_at timestamp with time zone, -- 提交时间
    published_at timestamp with time zone, -- 发布时间
    status character varying(32) DEFAULT 'DRAFT'::character varying NOT NULL, -- 项目文档版本状态
    content_jsonb jsonb DEFAULT '{}'::jsonb, -- 内容（JSON）
    snapshot_reason character varying(255), -- 快照原因
    template_snapshot_jsonb jsonb -- 模板快照（JSON）
);

CREATE TABLE mdm.project_material (
    id uuid DEFAULT gen_random_uuid() NOT NULL, -- 主键ID
    project_id uuid NOT NULL, -- 项目ID
    material_id uuid NOT NULL, -- 物料ID
    created_at timestamp with time zone NOT NULL, -- 创建时间
    updated_at timestamp with time zone NOT NULL, -- 更新时间
    created_by character varying(100) NOT NULL, -- 创建人ID
    updated_by character varying(100) NOT NULL -- 更新人ID
);

CREATE TABLE mdm.project_stage (
    id uuid NOT NULL, -- 主键ID
    project_id uuid NOT NULL, -- 项目ID
    name character varying(120) NOT NULL, -- 项目阶段名称
    order_no integer NOT NULL, -- 订单号
    status character varying(30) DEFAULT 'PENDING'::character varying NOT NULL, -- 项目阶段状态
    owner character varying(100), -- 负责人
    description text, -- 项目阶段描述
    planned_start date, -- 计划开始时间
    planned_end date, -- 计划结束时间
    actual_start timestamp with time zone, -- 实际开始时间
    actual_end timestamp with time zone, -- 实际结束时间
    version bigint DEFAULT 0 NOT NULL, -- 项目阶段版本号
    deleted boolean DEFAULT false NOT NULL, -- 是否删除
    created_at timestamp with time zone NOT NULL, -- 创建时间
    updated_at timestamp with time zone NOT NULL, -- 更新时间
    created_by character varying(100) NOT NULL, -- 创建人ID
    updated_by character varying(100) NOT NULL, -- 更新人ID
    deleted_at timestamp with time zone, -- 删除时间
    deleted_by character varying(100), -- 删除人ID
    stage_code character varying(40), -- 阶段编码
    CONSTRAINT ck_project_stage_planned_dates CHECK (((planned_end IS NULL) OR (planned_start IS NULL) OR (planned_end >= planned_start))),
    CONSTRAINT project_stage_order_no_check CHECK ((order_no > 0)),
    CONSTRAINT project_stage_status_check CHECK (((status)::text = ANY (ARRAY[('NOT_STARTED'::character varying)::text, ('PENDING'::character varying)::text, ('IN_PROGRESS'::character varying)::text, ('COMPLETED'::character varying)::text])))
);

CREATE TABLE mdm.project_task (
    id uuid NOT NULL, -- 主键ID
    task_code character varying(40) NOT NULL, -- 任务编码
    project_id uuid NOT NULL, -- 项目ID
    stage_id uuid NOT NULL, -- 阶段ID
    name character varying(300) NOT NULL, -- 项目任务名称
    owner character varying(100), -- 负责人
    planned_date date, -- 计划日期
    status character varying(30) DEFAULT 'PENDING'::character varying NOT NULL, -- 项目任务状态
    version bigint DEFAULT 0 NOT NULL, -- 项目任务版本号
    deleted boolean DEFAULT false NOT NULL, -- 是否删除
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    updated_at timestamp with time zone DEFAULT now() NOT NULL, -- 更新时间
    created_by character varying(100) NOT NULL, -- 创建人ID
    updated_by character varying(100) NOT NULL, -- 更新人ID
    priority character varying(20) DEFAULT 'MEDIUM'::character varying NOT NULL -- 优先级
);


-- ==================== schema: mfg ====================

CREATE TABLE mfg.inventory_policy (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    product_id uuid NOT NULL, -- 产品ID
    inventory_scope character varying(20) NOT NULL, -- 库存范围
    package_kg numeric(20,6), -- 包装重量（kg）
    low_stock_kg numeric(20,6) DEFAULT 0 NOT NULL, -- 低库存阈值（kg）
    created_by uuid NOT NULL, -- 创建人ID
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    updated_by uuid NOT NULL, -- 更新人ID
    updated_at timestamp with time zone DEFAULT now() NOT NULL, -- 更新时间
    CONSTRAINT inventory_policy_inventory_scope_check CHECK (((inventory_scope)::text = ANY (ARRAY[('RND'::character varying)::text, ('PRODUCTION'::character varying)::text]))),
    CONSTRAINT inventory_policy_low_stock_kg_check CHECK ((low_stock_kg >= (0)::numeric)),
    CONSTRAINT inventory_policy_package_kg_check CHECK (((package_kg IS NULL) OR (package_kg > (0)::numeric)))
);

CREATE TABLE mfg.inventory_product (
    organization_id uuid NOT NULL, -- 组织ID
    id uuid NOT NULL, -- 主键ID
    code character varying(64) NOT NULL, -- 库存产品编码
    name character varying(200) NOT NULL, -- 库存产品名称
    category character varying(50) DEFAULT '库存产品'::character varying NOT NULL, -- 库存产品分类
    status character varying(20) DEFAULT 'ACTIVE'::character varying NOT NULL, -- 库存产品状态
    created_by uuid NOT NULL, -- 创建人ID
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    updated_at timestamp with time zone DEFAULT now() NOT NULL, -- 更新时间
    inventory_scope character varying(20) NOT NULL, -- 库存范围
    CONSTRAINT inventory_product_scope_check CHECK (((inventory_scope)::text = ANY (ARRAY[('RND'::character varying)::text, ('PRODUCTION'::character varying)::text])))
);

CREATE TABLE mfg.inventory_sample_dispatch (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    dispatch_no character varying(100) NOT NULL, -- 样品寄送单号
    business_date date NOT NULL, -- 业务日期
    product_id uuid NOT NULL, -- 产品ID
    quantity_kg numeric(20,6) NOT NULL, -- 数量（kg）
    customer_name character varying(240) NOT NULL, -- 客户名称
    recipient_name character varying(120) NOT NULL, -- 收件人姓名
    sample_address character varying(500), -- 样品地址
    courier_company character varying(120), -- 快递公司
    tracking_no character varying(120), -- 运单号
    client_contact character varying(120), -- 客户联系人
    customer_requirement character varying(2000), -- 客户需求
    customer_feedback character varying(2000), -- 客户反馈
    inventory_transaction_id uuid, -- 库存流水ID
    actor_id uuid NOT NULL, -- 操作者ID
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    customer_id uuid, -- 客户ID
    inventory_scope character varying(32) DEFAULT 'RND'::character varying NOT NULL, -- 扣减库存域：RND=研发库存，PRODUCTION=生产库存
    recipient_phone character varying(64), -- 收件人电话
    client_contact_phone character varying(64), -- 客户联系电话
    sender_name character varying(128), -- 发件人姓名
    status character varying(16) DEFAULT 'PENDING'::character varying NOT NULL, -- 样品寄送状态
    specification character varying(200), -- 规格
    package_quantity numeric(18,6), -- 包装数量
    CONSTRAINT inventory_sample_dispatch_completion_transaction_check CHECK (((((status)::text = 'PENDING'::text) AND (inventory_transaction_id IS NULL)) OR (((status)::text = 'COMPLETED'::text) AND (inventory_transaction_id IS NOT NULL)))),
    CONSTRAINT inventory_sample_dispatch_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'COMPLETED'::character varying])::text[]))),
    CONSTRAINT sample_dispatch_quantity_kg_check CHECK ((quantity_kg > (0)::numeric))
);

CREATE TABLE mfg.inventory_shipment (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    shipment_no character varying(100) NOT NULL, -- 发货单号
    product_id uuid NOT NULL, -- 产品ID
    quantity_kg numeric(20,6) NOT NULL, -- 数量（kg）
    customer_name character varying(240) NOT NULL, -- 客户名称
    note character varying(2000), -- 备注
    inventory_transaction_id uuid, -- 库存流水ID
    actor_id uuid NOT NULL, -- 操作者ID
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    customer_id uuid, -- 客户ID
    inventory_scope character varying(32) DEFAULT 'PRODUCTION'::character varying NOT NULL, -- 扣减库存域：RND=研发库存，PRODUCTION=生产库存
    sender_name character varying(128), -- 发件人姓名
    batch_no character varying(128), -- 批次号
    specification character varying(255), -- 规格
    total_quantity_kg numeric(18,6), -- 总数量（kg）
    recipient_name character varying(128), -- 收件人姓名
    recipient_phone character varying(64), -- 收件人电话
    shipping_address text, -- 收货地址
    courier_company character varying(128), -- 快递公司
    tracking_no character varying(128), -- 运单号
    package_quantity numeric(18,6), -- 包装数量
    status character varying(16) DEFAULT 'PENDING'::character varying NOT NULL, -- 发货状态
    coa_no character varying(255), -- COA编号
    CONSTRAINT inventory_shipment_completion_transaction_check CHECK (((((status)::text = 'PENDING'::text) AND (inventory_transaction_id IS NULL)) OR (((status)::text = 'COMPLETED'::text) AND (inventory_transaction_id IS NOT NULL)))),
    CONSTRAINT inventory_shipment_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'COMPLETED'::character varying])::text[]))),
    CONSTRAINT shipment_quantity_kg_check CHECK ((quantity_kg > (0)::numeric))
);

CREATE TABLE mfg.inventory_shipment_header (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    shipment_no text NOT NULL, -- 发货单号
    business_date date NOT NULL, -- 业务日期
    actor_id uuid, -- 操作者ID
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    note text -- 备注
);

CREATE TABLE mfg.inventory_stock_balance (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    product_id uuid NOT NULL, -- 产品ID
    inventory_scope character varying(20) NOT NULL, -- 库存范围
    quantity_kg numeric(20,6) DEFAULT 0 NOT NULL, -- 数量（kg）
    version bigint DEFAULT 0 NOT NULL, -- 库存余额版本号
    updated_at timestamp with time zone DEFAULT now() NOT NULL, -- 更新时间
    CONSTRAINT stock_balance_inventory_scope_check CHECK (((inventory_scope)::text = ANY (ARRAY[('RND'::character varying)::text, ('PRODUCTION'::character varying)::text])))
);

CREATE TABLE mfg.inventory_stock_import (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    status character varying(20) NOT NULL, -- 库存导入状态
    rows_jsonb jsonb NOT NULL, -- 行数据列表（JSON）
    errors_jsonb jsonb DEFAULT '[]'::jsonb NOT NULL, -- 错误列表（JSON）
    actor_id uuid NOT NULL, -- 操作者ID
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    committed_at timestamp with time zone, -- 提交时间
    CONSTRAINT initial_stock_import_status_check CHECK (((status)::text = ANY (ARRAY[('VALIDATED'::character varying)::text, ('INVALID'::character varying)::text, ('COMMITTED'::character varying)::text])))
);

CREATE TABLE mfg.inventory_transaction (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    product_id uuid NOT NULL, -- 产品ID
    inventory_scope character varying(20) NOT NULL, -- 库存范围
    direction character varying(12) NOT NULL, -- 方向
    reason character varying(40) NOT NULL, -- 原因
    quantity_kg numeric(20,6) NOT NULL, -- 数量（kg）
    before_kg numeric(20,6) NOT NULL, -- 变更前重量（kg）
    after_kg numeric(20,6) NOT NULL, -- 变更后重量（kg）
    business_date date NOT NULL, -- 业务日期
    document_no character varying(100) NOT NULL, -- 文档编号
    business_type character varying(40) DEFAULT 'MANUAL'::character varying NOT NULL, -- 业务类型
    business_id uuid, -- 业务ID
    reversal_of uuid, -- 冲销来源ID
    idempotency_key character varying(120), -- 幂等键
    note character varying(2000), -- 备注
    actor_id uuid NOT NULL, -- 操作者ID
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    actor_name character varying(255), -- 经办人覆盖名；为空时回退到 actor_id 对应的用户显示名
    CONSTRAINT inventory_transaction_direction_check CHECK (((direction)::text = ANY (ARRAY[('INBOUND'::character varying)::text, ('OUTBOUND'::character varying)::text]))),
    CONSTRAINT inventory_transaction_inventory_scope_check CHECK (((inventory_scope)::text = ANY (ARRAY[('RND'::character varying)::text, ('PRODUCTION'::character varying)::text]))),
    CONSTRAINT inventory_transaction_quantity_kg_check CHECK ((quantity_kg > (0)::numeric))
);

CREATE TABLE mfg.production_ingest_item (
    id uuid NOT NULL, -- 主键ID
    ingest_job_id uuid NOT NULL, -- 摄入任务ID
    item_key character varying(240) NOT NULL, -- 条目键
    item_kind character varying(16) NOT NULL, -- 条目种类
    binding_id character varying(160), -- 绑定ID
    field_code character varying(160), -- 字段编码
    data_path character varying(400) NOT NULL, -- 数据路径
    record_key character varying(240), -- 记录键
    record_index integer, -- 记录索引
    raw_value_jsonb jsonb, -- 原始取值（JSON）
    normalized_value_jsonb jsonb, -- 归一化取值（JSON）
    user_value_jsonb jsonb, -- 用户取值（JSON）
    source_locator_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 来源定位（JSON）
    confidence numeric(6,5) DEFAULT 1.0 NOT NULL, -- 置信度
    review_status character varying(20) NOT NULL, -- 审核状态
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    updated_at timestamp with time zone DEFAULT now() NOT NULL, -- 更新时间
    CONSTRAINT production_ingest_item_item_kind_check CHECK (((item_kind)::text = ANY (ARRAY[('SCALAR'::character varying)::text, ('DETAIL'::character varying)::text]))),
    CONSTRAINT production_ingest_item_review_status_check CHECK (((review_status)::text = ANY (ARRAY[('EXTRACTED'::character varying)::text, ('NEEDS_REVIEW'::character varying)::text, ('CONFIRMED'::character varying)::text, ('REJECTED'::character varying)::text])))
);

CREATE TABLE mfg.production_ingest_job (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    production_order_id uuid NOT NULL, -- 生产订单ID
    requested_template_version_id uuid NOT NULL, -- 请求的模板版本ID
    selected_template_version_id uuid, -- 选定模板版本ID
    source_type character varying(16) NOT NULL, -- 来源类型
    match_mode character varying(32) DEFAULT 'PENDING'::character varying NOT NULL, -- 匹配模式
    status character varying(24) NOT NULL, -- 生产数据摄入任务状态
    template_match_score numeric(6,5), -- 模板匹配得分
    result_version integer DEFAULT 0 NOT NULL, -- 结果版本
    result_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 结果（JSON）
    error_message text, -- 错误信息
    created_by uuid NOT NULL, -- 创建人ID
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    updated_at timestamp with time zone DEFAULT now() NOT NULL, -- 更新时间
    confirmed_at timestamp with time zone, -- 确认时间
    CONSTRAINT production_ingest_job_match_mode_check CHECK (((match_mode)::text = ANY (ARRAY[('PENDING'::character varying)::text, ('EXACT_MANIFEST'::character varying)::text, ('SIMILAR_AUTO'::character varying)::text, ('USER_REVIEW'::character varying)::text, ('USER_SELECTED_TEMPLATE'::character varying)::text, ('INSTANCE_VALUE_EXTRACTION'::character varying)::text]))),
    CONSTRAINT production_ingest_job_source_type_check CHECK (((source_type)::text = ANY (ARRAY[('XLSX'::character varying)::text, ('PHOTO'::character varying)::text]))),
    CONSTRAINT production_ingest_job_status_check CHECK (((status)::text = ANY (ARRAY[('QUEUED'::character varying)::text, ('PROCESSING'::character varying)::text, ('REVIEW_REQUIRED'::character varying)::text, ('CONFIRMED'::character varying)::text, ('FAILED'::character varying)::text, ('CANCELLED'::character varying)::text])))
);

CREATE TABLE mfg.production_ingest_source (
    ingest_job_id uuid NOT NULL, -- 摄入任务ID
    file_object_id uuid NOT NULL, -- 文件对象ID
    page_order integer DEFAULT 0 NOT NULL, -- 页序
    CONSTRAINT production_ingest_source_page_order_check CHECK ((page_order >= 0))
);

CREATE TABLE mfg.production_order (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    order_no character varying(80) NOT NULL, -- 订单号
    status character varying(24) NOT NULL, -- 生产订单状态
    template_version_id uuid NOT NULL, -- 模板版本ID
    product_id uuid, -- 产品ID
    quantity numeric(20,6), -- 生产数量
    unit_code character varying(40), -- 单位编码
    planned_date date, -- 计划日期
    owner_id uuid, -- 负责人ID
    instance_schema_jsonb jsonb NOT NULL, -- 实例结构（JSON）
    instance_mapping_jsonb jsonb NOT NULL, -- 实例映射（JSON）
    draft_data_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 草稿数据（JSON）
    draft_editor_snapshot_file_id uuid, -- 草稿编辑器快照文件ID
    draft_editor_snapshot_hash character(64), -- 草稿编辑器快照哈希
    snapshot_kind character varying(32) NOT NULL, -- 快照种类
    editor_app_version character varying(80) NOT NULL, -- 编辑器应用版本
    plugin_manifest_hash character(64) NOT NULL, -- 插件清单哈希
    snapshot_format_version integer DEFAULT 1 NOT NULL, -- 快照格式版本
    schema_hash character(64) NOT NULL, -- 结构哈希
    mapping_hash character(64) NOT NULL, -- 映射哈希
    data_hash character(64) NOT NULL, -- 数据哈希
    workspace_hash character(64) NOT NULL, -- 工作区哈希
    lock_version bigint DEFAULT 0 NOT NULL, -- 乐观锁版本号
    created_by uuid NOT NULL, -- 创建人ID
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    updated_at timestamp with time zone DEFAULT now() NOT NULL, -- 更新时间
    updated_by uuid, -- 更新人ID
    CONSTRAINT production_order_snapshot_kind_check CHECK (((snapshot_kind)::text = ANY (ARRAY[('UNIVER_WORKBOOK'::character varying)::text, ('UNIVER_DOCUMENT'::character varying)::text]))),
    CONSTRAINT production_order_status_check CHECK (((status)::text = ANY (ARRAY[('DRAFT'::character varying)::text, ('SUBMITTED'::character varying)::text, ('CANCELLED'::character varying)::text])))
);

CREATE TABLE mfg.production_upload (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    file_id uuid NOT NULL, -- 文件ID
    production_name character varying(200), -- 生产名称
    order_no character varying(80), -- 订单号
    product_name character varying(200), -- 产品名称
    category character varying(120), -- 生产数据上传分类
    manufacture_date date, -- 生产日期
    project_id uuid, -- 项目ID
    project_name character varying(300), -- 项目名称
    stage_id uuid, -- 阶段ID
    stage_name character varying(200), -- 阶段名称
    task_id uuid, -- 任务ID
    task_name character varying(300), -- 任务名称
    visibility character varying(24) DEFAULT 'ALL'::character varying NOT NULL, -- 可见性
    status character varying(24) DEFAULT 'QUEUED'::character varying NOT NULL, -- 生产数据上传状态
    created_by uuid NOT NULL, -- 创建人ID
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    workbook_snapshot_jsonb jsonb, -- 工作簿快照（JSON）
    lock_version bigint DEFAULT 0 NOT NULL, -- 乐观锁版本号
    updated_by uuid, -- 更新人ID
    updated_at timestamp with time zone DEFAULT now() NOT NULL, -- 更新时间
    async_job_id uuid, -- 异步任务ID
    selected_template_version_id uuid, -- 选定模板版本ID
    recognition_progress integer DEFAULT 0 NOT NULL, -- 识别进度
    current_stage character varying(64), -- 当前阶段
    match_mode character varying(32), -- 匹配模式
    template_match_score numeric(6,5), -- 模板匹配得分
    recognition_result_jsonb jsonb, -- 识别结果（JSON）
    structure_summary_jsonb jsonb, -- 结构摘要（JSON）
    failure_message character varying(2000), -- 失败信息
    recognized_at timestamp with time zone, -- 识别时间
    source_type character varying(16) DEFAULT 'XLSX'::character varying NOT NULL, -- 来源类型
    CONSTRAINT production_upload_source_type_check CHECK (((source_type)::text = ANY (ARRAY[('XLSX'::character varying)::text, ('PHOTO'::character varying)::text]))),
    CONSTRAINT production_upload_status_check CHECK (((status)::text = ANY (ARRAY[('QUEUED'::character varying)::text, ('PARSING'::character varying)::text, ('MATCHING_TEMPLATE'::character varying)::text, ('EXTRACTING'::character varying)::text, ('REVIEW_REQUIRED'::character varying)::text, ('SAVED'::character varying)::text, ('PUBLISHED'::character varying)::text, ('FAILED'::character varying)::text, ('DELETED'::character varying)::text]))),
    CONSTRAINT production_upload_visibility_check CHECK (((visibility)::text = ANY (ARRAY[('ALL'::character varying)::text, ('QUALITY'::character varying)::text, ('PROJECT'::character varying)::text])))
);

CREATE TABLE mfg.production_upload_field (
    id uuid NOT NULL, -- 主键ID
    production_upload_id uuid NOT NULL, -- 生产上传ID
    item_key character varying(300) NOT NULL, -- 条目键
    item_kind character varying(32) NOT NULL, -- 条目种类
    binding_id character varying(300), -- 绑定ID
    field_code character varying(200), -- 字段编码
    data_path character varying(500), -- 数据路径
    record_index integer, -- 记录索引
    raw_value_jsonb jsonb, -- 原始取值（JSON）
    normalized_value_jsonb jsonb, -- 归一化取值（JSON）
    source_locator_jsonb jsonb, -- 来源定位（JSON）
    confidence numeric(6,5), -- 置信度
    review_status character varying(32) NOT NULL, -- 审核状态
    created_at timestamp with time zone DEFAULT now() NOT NULL -- 创建时间
);

CREATE TABLE mfg.production_upload_version (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    production_upload_id uuid NOT NULL, -- 生产上传ID
    version_no integer NOT NULL, -- 版本号
    workbook_snapshot_jsonb jsonb NOT NULL, -- 工作簿快照（JSON）
    lock_version bigint NOT NULL, -- 乐观锁版本号
    change_type character varying(24) DEFAULT 'PUBLISH'::character varying NOT NULL, -- 变更类型
    created_by uuid NOT NULL, -- 创建人ID
    created_at timestamp with time zone DEFAULT now() NOT NULL -- 创建时间
);

CREATE TABLE mfg.sample_recipient_preference (
    customer_id uuid NOT NULL, -- 客户ID
    recipient_name character varying(100) NOT NULL, -- 收件人姓名
    recipient_phone character varying(64) NOT NULL, -- 收件人电话
    sample_address character varying(500) NOT NULL, -- 样品地址
    updated_at timestamp with time zone DEFAULT now() NOT NULL -- 更新时间
);


-- ==================== schema: ops ====================

CREATE TABLE ops.async_job (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    job_type character varying(80) NOT NULL, -- 任务类型
    status character varying(20) NOT NULL, -- 异步任务状态
    priority smallint DEFAULT 100 NOT NULL, -- 优先级
    payload_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 载荷（JSON）
    result_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 结果（JSON）
    progress smallint DEFAULT 0 NOT NULL, -- 进度
    current_stage character varying(120), -- 当前阶段
    attempt_count integer DEFAULT 0 NOT NULL, -- 尝试次数
    max_attempts integer DEFAULT 5 NOT NULL, -- 最大尝试次数
    lease_owner character varying(160), -- 租约持有者
    lease_expires_at timestamp with time zone, -- 租约过期时间
    next_attempt_at timestamp with time zone DEFAULT now() NOT NULL, -- 下次尝试时间
    last_error text, -- 最近错误
    idempotency_key character varying(160), -- 幂等键
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    started_at timestamp with time zone, -- 开始时间
    finished_at timestamp with time zone, -- 完成时间
    updated_at timestamp with time zone DEFAULT now() NOT NULL, -- 更新时间
    lease_token uuid, -- 租约令牌
    lease_generation bigint DEFAULT 0 NOT NULL, -- 租约代数
    CONSTRAINT async_job_max_attempts_check CHECK ((max_attempts > 0)),
    CONSTRAINT async_job_no_legacy_ai_types CHECK (((job_type)::text <> ALL (ARRAY[('RESEARCH_RUN'::character varying)::text, ('FORMULA_MODEL_BUILD'::character varying)::text, ('AI_FORMULA_MODEL_BUILD'::character varying)::text]))),
    CONSTRAINT async_job_progress_check CHECK (((progress >= 0) AND (progress <= 100))),
    CONSTRAINT async_job_status_check CHECK (((status)::text = ANY (ARRAY[('READY'::character varying)::text, ('RUNNING'::character varying)::text, ('SUCCEEDED'::character varying)::text, ('FAILED'::character varying)::text, ('CANCELLED'::character varying)::text])))
);

CREATE TABLE ops.audit_log (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    actor_id uuid, -- 操作者ID
    action character varying(120) NOT NULL, -- 操作
    aggregate_type character varying(80) NOT NULL, -- 聚合根类型
    aggregate_id uuid NOT NULL, -- 聚合根ID
    detail_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 明细（JSON）
    created_at timestamp with time zone DEFAULT now() NOT NULL -- 创建时间
);

CREATE TABLE ops.file_object (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    bucket character varying(100) NOT NULL, -- 存储桶
    object_key character varying(500) NOT NULL, -- 对象存储键
    original_name character varying(260) NOT NULL, -- 原始名称
    content_type character varying(160) NOT NULL, -- 内容类型
    size_bytes bigint NOT NULL, -- 大小（字节）
    sha256 character(64) NOT NULL, -- 哈希值（SHA-256）
    status character varying(20) NOT NULL, -- 文件对象状态
    created_by uuid NOT NULL, -- 创建人ID
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    activated_at timestamp with time zone, -- 激活时间
    CONSTRAINT file_object_size_bytes_check CHECK ((size_bytes >= 0)),
    CONSTRAINT file_object_status_check CHECK (((status)::text = ANY (ARRAY[('STAGED'::character varying)::text, ('ACTIVE'::character varying)::text, ('DELETED'::character varying)::text])))
);

CREATE TABLE ops.outbox_event (
    id uuid NOT NULL, -- 主键ID
    aggregate_type character varying(80) NOT NULL, -- 聚合根类型
    aggregate_id uuid NOT NULL, -- 聚合根ID
    event_type character varying(120) NOT NULL, -- 事件类型
    payload_jsonb jsonb NOT NULL, -- 载荷（JSON）
    status character varying(20) DEFAULT 'PENDING'::character varying NOT NULL, -- 外发事件状态
    attempt_count integer DEFAULT 0 NOT NULL, -- 尝试次数
    next_attempt_at timestamp with time zone DEFAULT now() NOT NULL, -- 下次尝试时间
    lease_owner character varying(160), -- 租约持有者
    lease_expires_at timestamp with time zone, -- 租约过期时间
    last_error text, -- 最近错误
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    published_at timestamp with time zone, -- 发布时间
    organization_id uuid, -- 组织ID
    idempotency_key character varying(200), -- 幂等键
    CONSTRAINT outbox_event_status_check CHECK (((status)::text = ANY (ARRAY[('PENDING'::character varying)::text, ('PROCESSING'::character varying)::text, ('PUBLISHED'::character varying)::text, ('FAILED'::character varying)::text])))
);


-- ==================== schema: public ====================

CREATE TABLE public.flyway_schema_history (
    installed_rank integer NOT NULL, -- 安装顺位
    version character varying(50), -- Flyway迁移版本号
    description character varying(200) NOT NULL, -- 迁移脚本说明
    type character varying(20) NOT NULL, -- 迁移类型
    script character varying(1000) NOT NULL, -- 脚本
    checksum integer, -- 校验和
    installed_by character varying(100) NOT NULL, -- 安装人ID
    installed_on timestamp without time zone DEFAULT now() NOT NULL, -- 安装日期
    execution_time integer NOT NULL, -- 执行时间
    success boolean NOT NULL -- 是否成功
);


-- ==================== schema: quality ====================

CREATE TABLE quality.data_category (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    business_type character varying(24) NOT NULL, -- 业务类型
    name character varying(20) NOT NULL, -- 质量数据分类名称
    description character varying(80) NOT NULL, -- 质量数据分类描述
    system_default boolean DEFAULT false NOT NULL, -- 是否系统默认
    sort_order integer DEFAULT 0 NOT NULL, -- 排序号
    created_by uuid NOT NULL, -- 创建人ID
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    updated_at timestamp with time zone DEFAULT now() NOT NULL, -- 更新时间
    CONSTRAINT data_category_business_type_check CHECK (((business_type)::text = ANY (ARRAY[('standard'::character varying)::text, ('record'::character varying)::text, ('coa'::character varying)::text, ('defect'::character varying)::text, ('manual'::character varying)::text, ('msds'::character varying)::text, ('label'::character varying)::text])))
);

CREATE TABLE quality.data_record (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    business_type character varying(24) NOT NULL, -- 业务类型
    category_id uuid NOT NULL, -- 分类ID
    business_no character varying(96) NOT NULL, -- 业务单号
    data_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 数据（JSON）
    source_file_id uuid, -- 源文件ID
    project_id uuid, -- 项目ID
    project_name character varying(300), -- 项目名称
    stage_id uuid, -- 阶段ID
    stage_name character varying(300), -- 阶段名称
    task_id uuid, -- 任务ID
    task_name character varying(300), -- 任务名称
    visibility character varying(24) DEFAULT 'ALL'::character varying NOT NULL, -- 可见性
    created_by uuid NOT NULL, -- 创建人ID
    updated_by uuid NOT NULL, -- 更新人ID
    lock_version bigint DEFAULT 0 NOT NULL, -- 乐观锁版本号
    deleted boolean DEFAULT false NOT NULL, -- 是否删除
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    updated_at timestamp with time zone DEFAULT now() NOT NULL, -- 更新时间
    workbook_snapshot_jsonb jsonb, -- 工作簿快照（JSON）
    display_name character varying(300), -- 显示名称
    CONSTRAINT data_record_business_type_check CHECK (((business_type)::text = ANY (ARRAY[('standard'::character varying)::text, ('record'::character varying)::text, ('coa'::character varying)::text, ('defect'::character varying)::text, ('manual'::character varying)::text, ('msds'::character varying)::text, ('label'::character varying)::text]))),
    CONSTRAINT data_record_visibility_check CHECK (((visibility)::text = ANY (ARRAY[('ALL'::character varying)::text, ('RND'::character varying)::text, ('QUALITY'::character varying)::text, ('PROJECT'::character varying)::text])))
);

CREATE TABLE quality.data_record_version (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    record_id uuid NOT NULL, -- 记录ID
    version_no integer NOT NULL, -- 版本号
    business_no character varying(96) NOT NULL, -- 业务单号
    data_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 数据（JSON）
    lock_version bigint NOT NULL, -- 乐观锁版本号
    change_type character varying(24) DEFAULT 'EDIT'::character varying NOT NULL, -- 变更类型
    created_by uuid NOT NULL, -- 创建人ID
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    workbook_snapshot_jsonb jsonb, -- 工作簿快照（JSON）
    CONSTRAINT data_record_version_change_type_check CHECK (((change_type)::text = ANY (ARRAY[('INITIAL'::character varying)::text, ('EDIT'::character varying)::text, ('UPLOAD'::character varying)::text, ('PUBLISH'::character varying)::text]))),
    CONSTRAINT data_record_version_version_no_check CHECK ((version_no > 0))
);

CREATE TABLE quality.upload_file (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    file_id uuid NOT NULL, -- 文件ID
    category_id uuid NOT NULL, -- 分类ID
    original_name character varying(500) NOT NULL, -- 原始名称
    content_type character varying(160), -- 内容类型
    file_size bigint NOT NULL, -- 文件大小
    sha256 character varying(64), -- 哈希值（SHA-256）
    project_id uuid, -- 项目ID
    project_name character varying(300), -- 项目名称
    stage_id uuid, -- 阶段ID
    stage_name character varying(300), -- 阶段名称
    task_id uuid, -- 任务ID
    task_name character varying(300), -- 任务名称
    visibility character varying(24) NOT NULL, -- 可见性
    status character varying(24) DEFAULT 'UPLOADED'::character varying NOT NULL, -- 质量上传文件状态
    generated_record_id uuid, -- 生成记录ID
    error_message character varying(1000), -- 错误信息
    created_by uuid NOT NULL, -- 创建人ID
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    deleted boolean DEFAULT false NOT NULL, -- 是否删除
    CONSTRAINT upload_file_status_check CHECK (((status)::text = ANY (ARRAY[('UPLOADED'::character varying)::text, ('PARSING'::character varying)::text, ('DRAFT_CREATED'::character varying)::text, ('FAILED'::character varying)::text]))),
    CONSTRAINT upload_file_visibility_check CHECK (((visibility)::text = ANY (ARRAY[('ALL'::character varying)::text, ('RND'::character varying)::text, ('QUALITY'::character varying)::text, ('PROJECT'::character varying)::text])))
);


-- ==================== schema: rnd ====================

CREATE TABLE rnd.experiment (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    experiment_no character varying(80) NOT NULL, -- 实验编号
    legacy_experiment_no character varying(120), -- 历史实验编号
    title character varying(300) NOT NULL, -- 实验标题
    category_id uuid, -- 分类ID
    category_name character varying(100), -- 分类名称
    source_type character varying(30) NOT NULL, -- 来源类型
    status character varying(30) NOT NULL, -- 实验状态
    classification_status character varying(30) DEFAULT 'CLASSIFIED'::character varying NOT NULL, -- 分类状态
    project_id uuid, -- 项目ID
    stage_id uuid, -- 阶段ID
    task_id uuid, -- 任务ID
    owner_id uuid, -- 负责人ID
    owner_name character varying(100), -- 负责人名称
    experiment_date date, -- 实验日期
    current_version_id uuid, -- 当前版本ID
    revision bigint DEFAULT 0 NOT NULL, -- 修订号
    void_reason character varying(1000), -- 作废原因
    deleted boolean DEFAULT false NOT NULL, -- 是否删除
    created_by uuid NOT NULL, -- 创建人ID
    updated_by uuid NOT NULL, -- 更新人ID
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    updated_at timestamp with time zone DEFAULT now() NOT NULL, -- 更新时间
    source_file_id uuid, -- 源文件ID
    CONSTRAINT experiment_source_type_check CHECK (((source_type)::text = ANY (ARRAY[('PROJECT'::character varying)::text, ('TEMPLATE'::character varying)::text, ('MANUAL'::character varying)::text, ('EXCEL_IMPORT'::character varying)::text, ('OCR_IMPORT'::character varying)::text]))),
    CONSTRAINT experiment_status_check CHECK (((status)::text = ANY (ARRAY[('DRAFT'::character varying)::text, ('PENDING'::character varying)::text, ('IN_PROGRESS'::character varying)::text, ('PENDING_REVIEW'::character varying)::text, ('RETURNED'::character varying)::text, ('COMPLETED'::character varying)::text, ('VOIDED'::character varying)::text])))
);

CREATE TABLE rnd.experiment_ai_source_evidence (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    experiment_id uuid NOT NULL, -- 实验ID
    experiment_version_id uuid NOT NULL, -- 实验版本ID
    legacy_research_run_id uuid, -- 历史研究运行ID
    legacy_candidate_id uuid, -- 历史候选ID
    request_hash character(64) NOT NULL, -- 请求哈希
    formula_jsonb jsonb NOT NULL, -- 配方（JSON）
    predictions_jsonb jsonb NOT NULL, -- 预测数据（JSON）
    model_bindings_jsonb jsonb NOT NULL, -- 模型绑定（JSON）
    source_summary_jsonb jsonb NOT NULL, -- 来源摘要（JSON）
    evidence_hash character(64) NOT NULL, -- 证据哈希
    captured_at timestamp with time zone DEFAULT now() NOT NULL, -- 采集时间
    captured_by uuid, -- 采集人ID
    research_run_id uuid, -- 研究运行ID
    research_candidate_id uuid, -- 研究候选ID
    baseline_snapshot_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 基线快照（JSON）
    target_evidence_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 目标证据（JSON）
    request_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 请求内容（JSON）
    CONSTRAINT experiment_ai_baseline_object CHECK ((jsonb_typeof(baseline_snapshot_jsonb) = 'object'::text)),
    CONSTRAINT experiment_ai_bindings_object CHECK ((jsonb_typeof(model_bindings_jsonb) = 'object'::text)),
    CONSTRAINT experiment_ai_evidence_hash_check CHECK ((evidence_hash ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT experiment_ai_formula_object CHECK ((jsonb_typeof(formula_jsonb) = 'object'::text)),
    CONSTRAINT experiment_ai_predictions_object CHECK ((jsonb_typeof(predictions_jsonb) = 'object'::text)),
    CONSTRAINT experiment_ai_request_hash_check CHECK ((request_hash ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT experiment_ai_request_object CHECK ((jsonb_typeof(request_jsonb) = 'object'::text)),
    CONSTRAINT experiment_ai_source_object CHECK ((jsonb_typeof(source_summary_jsonb) = 'object'::text)),
    CONSTRAINT experiment_ai_target_evidence_object CHECK ((jsonb_typeof(target_evidence_jsonb) = 'object'::text))
);

CREATE TABLE rnd.experiment_attachment (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    experiment_id uuid NOT NULL, -- 实验ID
    experiment_version_id uuid NOT NULL, -- 实验版本ID
    file_id uuid NOT NULL, -- 文件ID
    file_version_id uuid, -- 文件版本ID
    attachment_type character varying(40) DEFAULT 'ATTACHMENT'::character varying NOT NULL, -- 附件类型
    section_key character varying(100), -- 章节键
    file_name character varying(500), -- 文件名
    description character varying(1000), -- 实验附件描述
    created_by uuid NOT NULL, -- 创建人ID
    created_at timestamp with time zone DEFAULT now() NOT NULL -- 创建时间
);

CREATE TABLE rnd.experiment_audit (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    experiment_id uuid NOT NULL, -- 实验ID
    experiment_version_id uuid, -- 实验版本ID
    action character varying(60) NOT NULL, -- 操作
    before_jsonb jsonb, -- 变更前数据（JSON）
    after_jsonb jsonb, -- 变更后数据（JSON）
    operator_id uuid NOT NULL, -- 操作人ID
    operator_name character varying(100), -- 操作人名称
    trace_id character varying(100), -- 链路追踪ID
    created_at timestamp with time zone DEFAULT now() NOT NULL -- 创建时间
);

CREATE TABLE rnd.experiment_category (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    code character varying(40) NOT NULL, -- 实验分类编码
    name character varying(100) NOT NULL, -- 实验分类名称
    active boolean DEFAULT true NOT NULL, -- 是否启用
    revision bigint DEFAULT 0 NOT NULL, -- 修订号
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    updated_at timestamp with time zone DEFAULT now() NOT NULL, -- 更新时间
    description character varying(500) DEFAULT ''::character varying NOT NULL -- 实验分类描述
);

CREATE TABLE rnd.experiment_import_job (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    source_file_id uuid NOT NULL, -- 源文件ID
    source_file_name character varying(500) NOT NULL, -- 源文件名
    source_sha256 character(64) NOT NULL, -- 来源哈希（SHA-256）
    status character varying(30) DEFAULT 'UPLOADED'::character varying NOT NULL, -- 实验导入任务状态
    duplicate_override boolean DEFAULT false NOT NULL, -- 重复覆盖标记
    duplicate_reason character varying(1000), -- 重复原因
    parse_result_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 解析结果（JSON）
    experiment_id uuid, -- 实验ID
    error_message character varying(2000), -- 错误信息
    created_by uuid NOT NULL, -- 创建人ID
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    updated_at timestamp with time zone DEFAULT now() NOT NULL, -- 更新时间
    source_format character varying(10) NOT NULL, -- 来源格式
    project_id uuid, -- 项目ID
    stage_id uuid, -- 阶段ID
    task_id uuid, -- 任务ID
    visibility character varying(24) DEFAULT 'ALL'::character varying NOT NULL, -- 可见性
    category_name character varying(200), -- 分类名称
    import_options_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 导入选项（JSON）
    CONSTRAINT ck_experiment_import_visibility CHECK (((visibility)::text = ANY (ARRAY[('ALL'::character varying)::text, ('QUALITY'::character varying)::text, ('PROJECT'::character varying)::text])))
);

CREATE TABLE rnd.experiment_outbox (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    aggregate_id uuid NOT NULL, -- 聚合根ID
    event_type character varying(100) NOT NULL, -- 事件类型
    payload_jsonb jsonb NOT NULL, -- 载荷（JSON）
    status character varying(20) DEFAULT 'PENDING'::character varying NOT NULL, -- 实验外发事件状态
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    published_at timestamp with time zone -- 发布时间
);

CREATE TABLE rnd.experiment_report (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    experiment_id uuid NOT NULL, -- 实验ID
    experiment_version_id uuid NOT NULL, -- 实验版本ID
    report_type character varying(30) NOT NULL, -- 报告类型
    status character varying(30) DEFAULT 'COMPLETED'::character varying NOT NULL, -- 实验报告状态
    file_id uuid, -- 文件ID
    created_by uuid NOT NULL, -- 创建人ID
    created_at timestamp with time zone DEFAULT now() NOT NULL -- 创建时间
);

CREATE TABLE rnd.experiment_review (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    experiment_id uuid NOT NULL, -- 实验ID
    experiment_version_id uuid NOT NULL, -- 实验版本ID
    action character varying(30) NOT NULL, -- 操作
    comment character varying(2000), -- 备注
    operator_id uuid NOT NULL, -- 操作人ID
    operator_name character varying(100), -- 操作人名称
    created_at timestamp with time zone DEFAULT now() NOT NULL -- 创建时间
);

CREATE TABLE rnd.experiment_source_reference (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    experiment_id uuid NOT NULL, -- 实验ID
    experiment_version_id uuid NOT NULL, -- 实验版本ID
    recognition_job_id uuid, -- 识别任务ID
    experiment_import_job_id uuid, -- 实验导入任务ID
    confirmed_submission_id uuid, -- 已确认提交ID
    experiment_boundary_id character varying(240) NOT NULL, -- 实验边界ID
    sample_boundary_id character varying(240) NOT NULL, -- 样品边界ID
    logical_sample_key character varying(320) NOT NULL, -- 逻辑样品键
    source_group_keys_jsonb jsonb NOT NULL, -- 来源分组键（JSON）
    source_file_id uuid NOT NULL, -- 源文件ID
    source_file_sha256 character(64) NOT NULL, -- 源文件哈希（SHA-256）
    source_coordinates_jsonb jsonb NOT NULL, -- 来源坐标（JSON）
    recognition_snapshot_jsonb jsonb NOT NULL, -- 识别快照（JSON）
    content_hash character(64) NOT NULL, -- 内容哈希
    created_by uuid NOT NULL, -- 创建人ID
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    CONSTRAINT experiment_source_content_hash_check CHECK ((content_hash ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT experiment_source_coordinates_object CHECK ((jsonb_typeof(source_coordinates_jsonb) = 'object'::text)),
    CONSTRAINT experiment_source_file_hash_check CHECK ((source_file_sha256 ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT experiment_source_groups_array CHECK (((jsonb_typeof(source_group_keys_jsonb) = 'array'::text) AND (jsonb_array_length(source_group_keys_jsonb) > 0))),
    CONSTRAINT experiment_source_job_owner_check CHECK (((((recognition_job_id IS NOT NULL))::integer + ((experiment_import_job_id IS NOT NULL))::integer) = 1)),
    CONSTRAINT experiment_source_snapshot_object CHECK ((jsonb_typeof(recognition_snapshot_jsonb) = 'object'::text))
);

CREATE TABLE rnd.experiment_version (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    experiment_id uuid NOT NULL, -- 实验ID
    version_no integer NOT NULL, -- 版本号
    status character varying(30) NOT NULL, -- 实验版本状态
    template_version_id uuid, -- 模板版本ID
    template_snapshot_hash character varying(64), -- 模板快照哈希
    template_snapshot_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 模板快照（JSON）
    edit_model_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 编辑模型（JSON）
    revision_reason character varying(1000), -- 修订原因
    submitted_at timestamp with time zone, -- 提交时间
    published_at timestamp with time zone, -- 发布时间
    created_by uuid NOT NULL, -- 创建人ID
    created_at timestamp with time zone DEFAULT now() NOT NULL -- 创建时间
);

CREATE TABLE rnd.research_test_audit (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    record_id uuid NOT NULL, -- 记录ID
    version_id uuid, -- 版本ID
    action character varying(40) NOT NULL, -- 操作
    before_jsonb jsonb, -- 变更前数据（JSON）
    after_jsonb jsonb, -- 变更后数据（JSON）
    operator_id uuid NOT NULL, -- 操作人ID
    operator_name character varying(120) NOT NULL, -- 操作人名称
    created_at timestamp with time zone DEFAULT now() NOT NULL -- 创建时间
);

CREATE TABLE rnd.research_test_record (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    record_type character varying(20) NOT NULL, -- 记录类型
    business_no character varying(96) NOT NULL, -- 业务单号
    name character varying(260) NOT NULL, -- 研发测试记录名称
    category character varying(100), -- 研发测试记录分类
    applicable_scope character varying(260), -- 适用范围
    owner_id uuid, -- 负责人ID
    owner_name character varying(120), -- 负责人名称
    business_date date, -- 业务日期
    document_format character varying(12) NOT NULL, -- 文档格式
    source_type character varying(20) NOT NULL, -- 来源类型
    status character varying(24) DEFAULT 'DRAFT'::character varying NOT NULL, -- 研发测试记录状态
    visibility character varying(24) DEFAULT 'ALL'::character varying NOT NULL, -- 可见性
    project_id uuid, -- 项目ID
    stage_id uuid, -- 阶段ID
    task_id uuid, -- 任务ID
    current_version_id uuid, -- 当前版本ID
    source_file_id uuid, -- 源文件ID
    lock_version bigint DEFAULT 0 NOT NULL, -- 乐观锁版本号
    deleted boolean DEFAULT false NOT NULL, -- 是否删除
    created_by uuid NOT NULL, -- 创建人ID
    updated_by uuid NOT NULL, -- 更新人ID
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    updated_at timestamp with time zone DEFAULT now() NOT NULL, -- 更新时间
    CONSTRAINT research_test_record_document_format_check CHECK (((document_format)::text = ANY (ARRAY[('WORD'::character varying)::text, ('EXCEL'::character varying)::text]))),
    CONSTRAINT research_test_record_record_type_check CHECK (((record_type)::text = ANY (ARRAY[('REPORT'::character varying)::text, ('STANDARD'::character varying)::text]))),
    CONSTRAINT research_test_record_source_type_check CHECK (((source_type)::text = ANY (ARRAY[('BLANK'::character varying)::text, ('TEMPLATE'::character varying)::text, ('UPLOAD'::character varying)::text, ('GENERATED'::character varying)::text, ('IMPORT'::character varying)::text]))),
    CONSTRAINT research_test_record_status_check CHECK (((status)::text = ANY (ARRAY[('DRAFT'::character varying)::text, ('PENDING_REVIEW'::character varying)::text, ('RETURNED'::character varying)::text, ('PUBLISHED'::character varying)::text, ('ARCHIVED'::character varying)::text]))),
    CONSTRAINT research_test_record_visibility_check CHECK (((visibility)::text = ANY (ARRAY[('ALL'::character varying)::text, ('RND'::character varying)::text, ('PROJECT'::character varying)::text])))
);

CREATE TABLE rnd.research_test_review (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    record_id uuid NOT NULL, -- 记录ID
    version_id uuid NOT NULL, -- 版本ID
    action character varying(24) NOT NULL, -- 操作
    comment character varying(1000), -- 备注
    operator_id uuid NOT NULL, -- 操作人ID
    operator_name character varying(120) NOT NULL, -- 操作人名称
    created_at timestamp with time zone DEFAULT now() NOT NULL -- 创建时间
);

CREATE TABLE rnd.research_test_upload (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    record_id uuid NOT NULL, -- 记录ID
    file_id uuid NOT NULL, -- 文件ID
    original_name character varying(500) NOT NULL, -- 原始名称
    content_type character varying(160) NOT NULL, -- 内容类型
    file_size bigint NOT NULL, -- 文件大小
    sha256 character varying(64), -- 哈希值（SHA-256）
    status character varying(24) DEFAULT 'DRAFT_CREATED'::character varying NOT NULL, -- 研发测试上传状态
    created_by uuid NOT NULL, -- 创建人ID
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    deleted boolean DEFAULT false NOT NULL -- 是否删除
);

CREATE TABLE rnd.research_test_version (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    record_id uuid NOT NULL, -- 记录ID
    version_no integer NOT NULL, -- 版本号
    status character varying(24) NOT NULL, -- 研发测试版本状态
    template_version_id uuid, -- 模板版本ID
    template_snapshot_hash character varying(64), -- 模板快照哈希
    template_snapshot_jsonb jsonb, -- 模板快照（JSON）
    edit_model_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 编辑模型（JSON）
    member_snapshot_jsonb jsonb DEFAULT '[]'::jsonb NOT NULL, -- 成员快照（JSON）
    effective_from date, -- 生效开始时间
    effective_to date, -- 生效结束时间
    change_summary character varying(1000), -- 变更摘要
    submitted_at timestamp with time zone, -- 提交时间
    published_at timestamp with time zone, -- 发布时间
    created_by uuid NOT NULL, -- 创建人ID
    created_at timestamp with time zone DEFAULT now() NOT NULL -- 创建时间
);


-- ==================== schema: spc ====================

CREATE TABLE spc.analysis_event (
    id bigint NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    analysis_run_id uuid NOT NULL, -- 分析运行ID
    event_type character varying(40) NOT NULL, -- 事件类型
    payload_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 载荷（JSON）
    created_at timestamp with time zone DEFAULT now() NOT NULL -- 创建时间
);

CREATE TABLE spc.analysis_run (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    session_id uuid NOT NULL, -- 会话ID
    user_message_id uuid, -- 用户消息ID
    mode character varying(40) DEFAULT 'AI_CHAT'::character varying NOT NULL, -- 谱图分析运行模式
    question text NOT NULL, -- 谱图分析运行提问
    chart_ids_jsonb jsonb DEFAULT '[]'::jsonb NOT NULL, -- 图谱ID列表（JSON）
    page_selections_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 选定页面（JSON）
    selected_categories_jsonb jsonb DEFAULT '[]'::jsonb NOT NULL, -- 选定分类（JSON）
    scenario_template character varying(80), -- 场景模板
    status character varying(20) DEFAULT 'QUEUED'::character varying NOT NULL, -- 谱图分析运行状态
    progress smallint DEFAULT 0 NOT NULL, -- 进度
    current_stage character varying(120), -- 当前阶段
    prompt_version character varying(80), -- 提示词版本
    model character varying(160), -- 模型
    result_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 结果（JSON）
    raw_response_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 原始响应（JSON）
    warning_jsonb jsonb DEFAULT '[]'::jsonb NOT NULL, -- 警告信息（JSON）
    error_message text, -- 错误信息
    created_by uuid NOT NULL, -- 创建人ID
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    started_at timestamp with time zone, -- 开始时间
    completed_at timestamp with time zone, -- 完成时间
    CONSTRAINT analysis_run_mode_check CHECK (((mode)::text = ANY (ARRAY[('AI_CHAT'::character varying)::text, ('CATEGORY_ASSISTED'::character varying)::text, ('OPTIONAL_SCENARIO'::character varying)::text]))),
    CONSTRAINT analysis_run_progress_check CHECK (((progress >= 0) AND (progress <= 100))),
    CONSTRAINT analysis_run_status_check CHECK (((status)::text = ANY (ARRAY[('QUEUED'::character varying)::text, ('RUNNING'::character varying)::text, ('SUCCEEDED'::character varying)::text, ('PARTIAL'::character varying)::text, ('FAILED'::character varying)::text, ('CANCELLED'::character varying)::text])))
);

CREATE TABLE spc.chart_asset (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    category_id uuid NOT NULL, -- 分类ID
    file_object_id uuid NOT NULL, -- 文件对象ID
    title character varying(260) NOT NULL, -- 谱图资产标题
    original_name character varying(260) NOT NULL, -- 原始名称
    content_type character varying(160) NOT NULL, -- 内容类型
    size_bytes bigint NOT NULL, -- 大小（字节）
    sha256 character(64) NOT NULL, -- 哈希值（SHA-256）
    sample_name character varying(260), -- 样品名称
    batch_no character varying(160), -- 批次号
    test_conditions text, -- 测试条件
    metadata_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 元数据（JSON）
    page_count integer DEFAULT 1 NOT NULL, -- 页数
    status character varying(20) DEFAULT 'READY'::character varying NOT NULL, -- 谱图资产状态
    created_by uuid NOT NULL, -- 创建人ID
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    updated_at timestamp with time zone DEFAULT now() NOT NULL, -- 更新时间
    request_id uuid, -- 请求ID
    CONSTRAINT chart_asset_page_count_check CHECK ((page_count > 0)),
    CONSTRAINT chart_asset_size_bytes_check CHECK ((size_bytes >= 0)),
    CONSTRAINT chart_asset_status_check CHECK (((status)::text = ANY (ARRAY[('READY'::character varying)::text, ('DELETED'::character varying)::text])))
);

CREATE TABLE spc.chart_category (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    code character varying(64) NOT NULL, -- 谱图分类编码
    name character varying(120) NOT NULL, -- 谱图分类名称
    description character varying(500), -- 谱图分类描述
    analysis_hint text, -- 分析提示
    fields_jsonb jsonb DEFAULT '[]'::jsonb NOT NULL, -- 字段列表（JSON）
    sort_order integer DEFAULT 100 NOT NULL, -- 排序号
    system_category boolean DEFAULT false NOT NULL, -- 系统分类
    created_by uuid NOT NULL, -- 创建人ID
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    updated_at timestamp with time zone DEFAULT now() NOT NULL -- 更新时间
);

CREATE TABLE spc.chart_upload_attempt (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    request_id uuid NOT NULL, -- 请求ID
    category_id uuid, -- 分类ID
    chart_id uuid, -- 图谱ID
    file_object_id uuid, -- 文件对象ID
    original_name character varying(500) NOT NULL, -- 原始名称
    size_bytes bigint DEFAULT 0 NOT NULL, -- 大小（字节）
    status character varying(24) NOT NULL, -- 谱图上传尝试状态
    error_code character varying(80), -- 错误码
    error_message character varying(2000), -- 错误信息
    created_by uuid NOT NULL, -- 创建人ID
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    upload_context_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 上传上下文（JSON）
    parent_request_id uuid, -- 父请求ID
    hidden_at timestamp with time zone, -- 隐藏时间
    CONSTRAINT chart_upload_attempt_size_bytes_check CHECK ((size_bytes >= 0)),
    CONSTRAINT chart_upload_attempt_status_check CHECK (((status)::text = ANY ((ARRAY['SUCCEEDED'::character varying, 'FAILED'::character varying, 'DUPLICATE'::character varying, 'CANCELLED'::character varying])::text[])))
);

CREATE TABLE spc.chat_message (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    session_id uuid NOT NULL, -- 会话ID
    analysis_run_id uuid, -- 分析运行ID
    role character varying(20) NOT NULL, -- 对话消息角色
    content text NOT NULL, -- 对话消息内容
    citations_jsonb jsonb DEFAULT '[]'::jsonb NOT NULL, -- 引用列表（JSON）
    result_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 结果（JSON）
    warning_jsonb jsonb DEFAULT '[]'::jsonb NOT NULL, -- 警告信息（JSON）
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    CONSTRAINT chat_message_role_check CHECK (((role)::text = ANY (ARRAY[('USER'::character varying)::text, ('ASSISTANT'::character varying)::text])))
);

CREATE TABLE spc.chat_session (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    title character varying(260) DEFAULT '新的图谱分析对话'::character varying NOT NULL, -- 对话会话标题
    created_by uuid NOT NULL, -- 创建人ID
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    updated_at timestamp with time zone DEFAULT now() NOT NULL, -- 更新时间
    title_source character varying(24) DEFAULT 'FIRST_QUESTION'::character varying NOT NULL, -- 标题来源
    chart_ids_jsonb jsonb DEFAULT '[]'::jsonb NOT NULL, -- 图谱ID列表（JSON）
    page_selections_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 选定页面（JSON）
    CONSTRAINT chk_spc_chat_session_title_source CHECK (((title_source)::text = ANY (ARRAY[('FIRST_QUESTION'::character varying)::text, ('MODEL'::character varying)::text, ('USER'::character varying)::text])))
);


-- ==================== schema: tpl ====================

CREATE TABLE tpl.recognition_call (
    id uuid NOT NULL, -- 主键ID
    recognition_run_id uuid NOT NULL, -- 识别运行ID
    region_id character varying(256) NOT NULL, -- 区域ID
    attempt integer DEFAULT 1 NOT NULL, -- 尝试次数
    provider character varying(100) NOT NULL, -- 提供方
    model character varying(100) NOT NULL, -- 模型
    prompt_version character varying(80) NOT NULL, -- 提示词版本
    status character varying(24) NOT NULL, -- 识别调用状态
    http_status integer, -- HTTP状态码
    started_at timestamp with time zone NOT NULL, -- 开始时间
    finished_at timestamp with time zone NOT NULL, -- 完成时间
    duration_ms bigint NOT NULL, -- 耗时（毫秒）
    prompt_tokens integer DEFAULT 0 NOT NULL, -- 提示Token数
    completion_tokens integer DEFAULT 0 NOT NULL, -- 输出Token数
    total_tokens integer DEFAULT 0 NOT NULL, -- 总Token数
    request_payload_gzip bytea, -- 请求载荷（gzip）
    response_payload_gzip bytea, -- 响应载荷（gzip）
    request_hash character(64) NOT NULL, -- 请求哈希
    response_hash character(64), -- 响应哈希
    error_type character varying(160), -- 错误类型
    error_message text, -- 错误信息
    finish_reason character varying(40), -- 结束原因
    outcome_code character varying(80), -- 结果码
    response_truncated boolean DEFAULT false NOT NULL, -- 响应是否截断
    payload_expires_at timestamp with time zone DEFAULT (now() + '90 days'::interval) NOT NULL, -- 载荷过期时间
    payload_purged_at timestamp with time zone, -- 载荷清除时间
    phase character varying(40) DEFAULT 'REGION_INFERENCE'::character varying NOT NULL, -- 阶段
    parent_call_id uuid, -- 父调用ID
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    CONSTRAINT recognition_call_duration_ms_check CHECK ((duration_ms >= 0)),
    CONSTRAINT recognition_call_status_check CHECK (((status)::text = ANY (ARRAY[('SUCCEEDED'::character varying)::text, ('FAILED'::character varying)::text])))
);

CREATE TABLE tpl.recognition_run (
    id uuid NOT NULL, -- 主键ID
    import_job_id uuid NOT NULL, -- 导入任务ID
    scope character varying(24) DEFAULT 'WORKBOOK'::character varying NOT NULL, -- 识别范围
    status character varying(24) NOT NULL, -- 识别运行状态
    structure_version integer NOT NULL, -- 结构版本
    snapshot_format_version integer NOT NULL, -- 快照格式版本
    region_count integer DEFAULT 0 NOT NULL, -- 区域数量
    call_count integer DEFAULT 0 NOT NULL, -- 调用次数
    succeeded_call_count integer DEFAULT 0 NOT NULL, -- 成功调用次数
    failed_call_count integer DEFAULT 0 NOT NULL, -- 失败调用次数
    prompt_tokens bigint DEFAULT 0 NOT NULL, -- 提示Token数
    completion_tokens bigint DEFAULT 0 NOT NULL, -- 输出Token数
    total_tokens bigint DEFAULT 0 NOT NULL, -- 总Token数
    started_at timestamp with time zone DEFAULT now() NOT NULL, -- 开始时间
    finished_at timestamp with time zone, -- 完成时间
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    recognition_protocol_version integer DEFAULT 1 NOT NULL, -- 识别协议版本
    source_snapshot_hash character(64), -- 来源快照哈希
    parent_run_id uuid, -- 父运行ID
    run_reason character varying(48) DEFAULT 'INITIAL_RECOGNITION'::character varying NOT NULL, -- 运行原因
    CONSTRAINT recognition_run_status_check CHECK (((status)::text = ANY (ARRAY[('RUNNING'::character varying)::text, ('COMPLETED'::character varying)::text, ('PARTIAL'::character varying)::text, ('FAILED'::character varying)::text])))
);

CREATE TABLE tpl.recognition_suggestion (
    id uuid NOT NULL, -- 主键ID
    import_job_id uuid NOT NULL, -- 导入任务ID
    source character varying(20) NOT NULL, -- 建议来源
    suggestion_type character varying(60) NOT NULL, -- 建议类型
    payload_jsonb jsonb NOT NULL, -- 载荷（JSON）
    confidence numeric(5,4), -- 置信度
    evidence_jsonb jsonb DEFAULT '[]'::jsonb NOT NULL, -- 证据（JSON）
    decision character varying(24) DEFAULT 'PENDING'::character varying NOT NULL, -- 决策
    provider character varying(100), -- 提供方
    model character varying(100), -- 模型
    prompt_version character varying(60), -- 提示词版本
    request_hash character(64), -- 请求哈希
    response_hash character(64), -- 响应哈希
    decided_by uuid, -- 决策人ID
    decided_at timestamp with time zone, -- 决策时间
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    recognition_run_id uuid, -- 识别运行ID
    recognition_call_id uuid, -- 识别调用ID
    region_id character varying(256), -- 区域ID
    relation_id character varying(256), -- 关联ID
    block_id character varying(256), -- 区块ID
    parent_suggestion_id uuid, -- 父建议ID
    field_id uuid, -- 字段ID
    semantic_fingerprint character(64), -- 语义指纹
    suggestion_level character varying(16) DEFAULT 'ROOT'::character varying NOT NULL, -- 建议级别
    filter_stage character varying(48), -- 过滤阶段
    filter_reason_code character varying(80), -- 过滤原因码
    filter_detail text, -- 过滤明细
    CONSTRAINT ck_recognition_suggestion_decision CHECK (((decision)::text = ANY (ARRAY[('PENDING'::character varying)::text, ('ACCEPTED'::character varying)::text, ('REJECTED'::character varying)::text]))),
    CONSTRAINT recognition_suggestion_source_check CHECK (((source)::text = ANY (ARRAY[('RULE'::character varying)::text, ('MODEL'::character varying)::text, ('PHYSICAL'::character varying)::text, ('HUMAN'::character varying)::text]))),
    CONSTRAINT recognition_suggestion_suggestion_level_check CHECK (((suggestion_level)::text = ANY (ARRAY[('ROOT'::character varying)::text, ('CHILD'::character varying)::text, ('SCALAR'::character varying)::text])))
);

CREATE TABLE tpl.recognition_trace (
    id uuid NOT NULL, -- 主键ID
    recognition_run_id uuid NOT NULL, -- 识别运行ID
    recognition_suggestion_id uuid, -- 识别建议ID
    stage character varying(48) NOT NULL, -- 识别轨迹阶段
    reason_code character varying(80) NOT NULL, -- 原因码
    message text NOT NULL, -- 识别轨迹信息
    detail_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 明细（JSON）
    created_at timestamp with time zone DEFAULT now() NOT NULL -- 创建时间
);

CREATE TABLE tpl.record_attachment (
    id uuid NOT NULL, -- 主键ID
    production_order_id uuid NOT NULL, -- 生产订单ID
    revision_id uuid, -- 修订ID
    file_object_id uuid NOT NULL, -- 文件对象ID
    attachment_type character varying(40) NOT NULL, -- 附件类型
    created_by uuid NOT NULL, -- 创建人ID
    created_at timestamp with time zone DEFAULT now() NOT NULL -- 创建时间
);

CREATE TABLE tpl.record_collection_item (
    id uuid NOT NULL, -- 主键ID
    revision_id uuid NOT NULL, -- 修订ID
    production_order_id uuid NOT NULL, -- 生产订单ID
    record_kind character varying(16) NOT NULL, -- 记录种类
    parent_field_code character varying(160) NOT NULL, -- 父字段编码
    parent_data_path character varying(400) NOT NULL, -- 父数据路径
    record_key character varying(240) NOT NULL, -- 记录键
    record_index integer NOT NULL, -- 记录索引
    member_key character varying(240), -- 成员键
    data_jsonb jsonb NOT NULL, -- 数据（JSON）
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    CONSTRAINT record_collection_item_record_index_check CHECK ((record_index >= 0)),
    CONSTRAINT record_collection_item_record_kind_check CHECK (((record_kind)::text = 'DETAIL'::text))
);

CREATE TABLE tpl.record_revision (
    id uuid NOT NULL, -- 主键ID
    production_order_id uuid NOT NULL, -- 生产订单ID
    revision_no integer NOT NULL, -- 修订号
    status character varying(24) NOT NULL, -- 记录修订状态
    core_snapshot_jsonb jsonb NOT NULL, -- 核心快照（JSON）
    schema_snapshot_jsonb jsonb NOT NULL, -- 结构快照（JSON）
    mapping_snapshot_jsonb jsonb NOT NULL, -- 映射快照（JSON）
    data_jsonb jsonb NOT NULL, -- 数据（JSON）
    editor_snapshot_file_id uuid, -- 编辑器快照文件ID
    editor_snapshot_hash character(64), -- 编辑器快照哈希
    export_office_file_id uuid, -- 导出Office文件ID
    export_office_hash character(64), -- 导出Office文件哈希
    schema_hash character(64) NOT NULL, -- 结构哈希
    mapping_hash character(64) NOT NULL, -- 映射哈希
    data_hash character(64) NOT NULL, -- 数据哈希
    workspace_hash character(64) NOT NULL, -- 工作区哈希
    created_by uuid NOT NULL, -- 创建人ID
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    CONSTRAINT record_revision_revision_no_check CHECK ((revision_no > 0)),
    CONSTRAINT record_revision_status_check CHECK (((status)::text = ANY (ARRAY[('SUBMITTED'::character varying)::text, ('SUPERSEDED'::character varying)::text])))
);

CREATE TABLE tpl.record_value_index (
    id uuid NOT NULL, -- 主键ID
    revision_id uuid NOT NULL, -- 修订ID
    production_order_id uuid NOT NULL, -- 生产订单ID
    field_code character varying(160) NOT NULL, -- 字段编码
    data_path character varying(400) NOT NULL, -- 数据路径
    value_type character varying(20) NOT NULL, -- 取值类型
    text_value text, -- 文本值
    numeric_value numeric, -- 数值
    boolean_value boolean, -- 布尔值
    date_value date, -- 日期值
    reference_value uuid, -- 参考值
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    collection_item_id uuid -- 集合项ID
);

CREATE TABLE tpl.standard_field_alias (
    id uuid NOT NULL, -- 主键ID
    dictionary_id uuid NOT NULL, -- 字典ID
    alias character varying(160) NOT NULL, -- 别名
    normalized_alias character varying(160) NOT NULL -- 归一化别名
);

CREATE TABLE tpl.standard_field_catalog_import (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    source_file_id uuid NOT NULL, -- 源文件ID
    source_sha256 character(64) NOT NULL, -- 来源哈希（SHA-256）
    source_name character varying(260) NOT NULL, -- 来源名称
    catalog_version integer NOT NULL, -- 目录版本
    parser_version character varying(80) NOT NULL, -- 解析器版本
    status character varying(24) DEFAULT 'PARSED'::character varying NOT NULL, -- 标准字段目录导入状态
    source_scope character varying(24) DEFAULT 'GLOBAL'::character varying NOT NULL, -- 来源范围
    source_row_count integer DEFAULT 0 NOT NULL, -- 来源行数
    proposal_count integer DEFAULT 0 NOT NULL, -- 提案数量
    summary_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 摘要（JSON）
    created_by uuid NOT NULL, -- 创建人ID
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    CONSTRAINT catalog_import_hash_check CHECK ((source_sha256 ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT catalog_import_summary_object CHECK ((jsonb_typeof(summary_jsonb) = 'object'::text)),
    CONSTRAINT standard_field_catalog_import_catalog_version_check CHECK ((catalog_version > 0)),
    CONSTRAINT standard_field_catalog_import_proposal_count_check CHECK ((proposal_count >= 0)),
    CONSTRAINT standard_field_catalog_import_source_row_count_check CHECK ((source_row_count >= 0)),
    CONSTRAINT standard_field_catalog_import_source_scope_check CHECK (((source_scope)::text = 'GLOBAL'::text)),
    CONSTRAINT standard_field_catalog_import_status_check CHECK (((status)::text = ANY (ARRAY[('PARSED'::character varying)::text, ('REVIEWED'::character varying)::text, ('SUPERSEDED'::character varying)::text])))
);

CREATE TABLE tpl.standard_field_catalog_proposal (
    id uuid NOT NULL, -- 主键ID
    import_id uuid NOT NULL, -- 导入ID
    source_row integer NOT NULL, -- 来源行
    atomic_ordinal integer NOT NULL, -- 原子序号
    semantic_key character varying(320) NOT NULL, -- 语义键
    raw_item_name character varying(500) NOT NULL, -- 原始项目名称
    raw_test_method character varying(500), -- 原始测试方法
    raw_example_result character varying(1000), -- 示例原始结果
    raw_influence_factors character varying(1000), -- 原始影响因素
    atomic_name character varying(240) NOT NULL, -- 原子名称
    suggested_code character varying(180), -- 建议编码
    suggested_value_type character varying(24) NOT NULL, -- 建议取值类型
    suggested_unit character varying(80), -- 建议单位
    standard_field_id uuid, -- 标准字段ID
    standard_field_code character varying(100), -- 标准字段编码
    qualifier_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 限定条件（JSON）
    aliases_jsonb jsonb DEFAULT '[]'::jsonb NOT NULL, -- 别名列表（JSON）
    factor_suggestions_jsonb jsonb DEFAULT '[]'::jsonb NOT NULL, -- 因子建议（JSON）
    evidence_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 证据（JSON）
    confidence integer DEFAULT 0 NOT NULL, -- 置信度
    change_status character varying(16) DEFAULT 'NEW'::character varying NOT NULL, -- 变更状态
    previous_proposal_id uuid, -- 上一提案ID
    diff_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 差异（JSON）
    review_status character varying(24) DEFAULT 'NEEDS_REVIEW'::character varying NOT NULL, -- 审核状态
    application_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 申请信息（JSON）
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    CONSTRAINT catalog_proposal_alias_array CHECK ((jsonb_typeof(aliases_jsonb) = 'array'::text)),
    CONSTRAINT catalog_proposal_application_object CHECK ((jsonb_typeof(application_jsonb) = 'object'::text)),
    CONSTRAINT catalog_proposal_diff_object CHECK ((jsonb_typeof(diff_jsonb) = 'object'::text)),
    CONSTRAINT catalog_proposal_evidence_object CHECK ((jsonb_typeof(evidence_jsonb) = 'object'::text)),
    CONSTRAINT catalog_proposal_factor_array CHECK ((jsonb_typeof(factor_suggestions_jsonb) = 'array'::text)),
    CONSTRAINT catalog_proposal_qualifier_object CHECK ((jsonb_typeof(qualifier_jsonb) = 'object'::text)),
    CONSTRAINT standard_field_catalog_proposal_atomic_ordinal_check CHECK ((atomic_ordinal >= 0)),
    CONSTRAINT standard_field_catalog_proposal_change_status_check CHECK (((change_status)::text = ANY (ARRAY[('NEW'::character varying)::text, ('CHANGED'::character varying)::text, ('UNCHANGED'::character varying)::text, ('MISSING'::character varying)::text]))),
    CONSTRAINT standard_field_catalog_proposal_confidence_check CHECK (((confidence >= 0) AND (confidence <= 100))),
    CONSTRAINT standard_field_catalog_proposal_review_status_check CHECK (((review_status)::text = ANY (ARRAY[('NEEDS_REVIEW'::character varying)::text, ('ACCEPTED'::character varying)::text, ('IGNORED'::character varying)::text, ('APPLIED'::character varying)::text]))),
    CONSTRAINT standard_field_catalog_proposal_source_row_check CHECK ((source_row > 0)),
    CONSTRAINT standard_field_catalog_proposal_suggested_value_type_check CHECK (((suggested_value_type)::text = ANY (ARRAY[('CONTINUOUS'::character varying)::text, ('ORDINAL'::character varying)::text, ('BINARY'::character varying)::text, ('CATEGORICAL'::character varying)::text, ('COMPOSITE'::character varying)::text, ('CENSORED'::character varying)::text, ('UNKNOWN'::character varying)::text])))
);

CREATE TABLE tpl.standard_field_dictionary (
    id uuid NOT NULL, -- 主键ID
    dictionary_code character varying(100) NOT NULL, -- 字典编码
    version_no integer NOT NULL, -- 版本号
    display_name character varying(160) NOT NULL, -- 显示名称
    data_type character varying(32) NOT NULL, -- 数据类型
    default_unit character varying(40), -- 默认单位
    description text, -- 标准字段字典描述
    repeatable boolean DEFAULT false NOT NULL, -- 是否可重复
    status character varying(24) DEFAULT 'ACTIVE'::character varying NOT NULL, -- 标准字段字典状态
    group_code character varying(100), -- 分组编码
    ui_type character varying(24) DEFAULT 'TEXT'::character varying NOT NULL, -- 前端控件类型
    supersedes_id uuid, -- 替代对象ID
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    CONSTRAINT standard_field_dictionary_status_check CHECK (((status)::text = ANY (ARRAY[('DRAFT'::character varying)::text, ('ACTIVE'::character varying)::text, ('RETIRED'::character varying)::text]))),
    CONSTRAINT standard_field_dictionary_ui_type_check CHECK (((ui_type)::text = ANY (ARRAY[('TEXT'::character varying)::text, ('SIGNATURE'::character varying)::text]))),
    CONSTRAINT standard_field_dictionary_version_no_check CHECK ((version_no > 0))
);

CREATE TABLE tpl.standard_field_request (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    template_version_id uuid, -- 模板版本ID
    field_id character varying(180), -- 字段ID
    display_name character varying(160) NOT NULL, -- 显示名称
    data_type character varying(32) NOT NULL, -- 数据类型
    ui_type character varying(24) DEFAULT 'TEXT'::character varying NOT NULL, -- 前端控件类型
    group_code character varying(100), -- 分组编码
    description text, -- 标准字段申请描述
    status character varying(24) DEFAULT 'PENDING'::character varying NOT NULL, -- 标准字段申请状态
    proposed_field_code character varying(160), -- 建议字段编码
    approved_dictionary_id uuid, -- 已审批字典ID
    review_comment text, -- 审核意见
    created_by uuid NOT NULL, -- 创建人ID
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    reviewed_by uuid, -- 审核人ID
    reviewed_at timestamp with time zone, -- 审核时间
    catalog_proposal_id uuid, -- 字段目录提案ID
    CONSTRAINT standard_field_request_status_check CHECK (((status)::text = ANY (ARRAY[('PENDING'::character varying)::text, ('APPROVED'::character varying)::text, ('REJECTED'::character varying)::text]))),
    CONSTRAINT standard_field_request_ui_type_check CHECK (((ui_type)::text = ANY (ARRAY[('TEXT'::character varying)::text, ('SIGNATURE'::character varying)::text])))
);

CREATE TABLE tpl.template (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    template_code character varying(80) NOT NULL, -- 模板编码
    name character varying(200) NOT NULL, -- 模板名称
    category character varying(120), -- 模板分类
    format character varying(16) NOT NULL, -- 格式
    current_published_version_id uuid, -- 当前已发布版本ID
    created_by uuid NOT NULL, -- 创建人ID
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    updated_at timestamp with time zone DEFAULT now() NOT NULL, -- 更新时间
    category_id uuid, -- 分类ID
    purpose character varying(160), -- 模板用途
    CONSTRAINT template_format_check CHECK (((format)::text = ANY (ARRAY[('XLSX'::character varying)::text, ('DOCX'::character varying)::text])))
);

CREATE TABLE tpl.template_category (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    name character varying(120) NOT NULL, -- 模板分类名称
    sort_order integer DEFAULT 0 NOT NULL, -- 排序号
    created_by uuid NOT NULL, -- 创建人ID
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    updated_at timestamp with time zone DEFAULT now() NOT NULL, -- 更新时间
    description character varying(240) -- 模板分类描述
);

CREATE TABLE tpl.template_import_contract (
    template_version_id uuid NOT NULL, -- 模板版本ID
    import_contract_version integer NOT NULL, -- 导入契约版本
    layout_structure_version integer NOT NULL, -- 版式结构版本
    contract_hash character(64) NOT NULL, -- 契约哈希
    contract_jsonb jsonb NOT NULL, -- 契约（JSON）
    created_by uuid, -- 创建人ID
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    CONSTRAINT template_import_contract_import_contract_version_check CHECK ((import_contract_version > 0)),
    CONSTRAINT template_import_contract_layout_structure_version_check CHECK ((layout_structure_version > 0))
);

CREATE TABLE tpl.template_import_issue (
    id uuid NOT NULL, -- 主键ID
    import_job_id uuid NOT NULL, -- 导入任务ID
    severity character varying(16) NOT NULL, -- 严重级别
    issue_code character varying(100) NOT NULL, -- 问题编码
    location_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 位置信息（JSON）
    message text NOT NULL, -- 模板导入问题信息
    resolution character varying(40) DEFAULT 'OPEN'::character varying NOT NULL, -- 处理结论
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    CONSTRAINT template_import_issue_severity_check CHECK (((severity)::text = ANY (ARRAY[('INFO'::character varying)::text, ('WARNING'::character varying)::text, ('BLOCKER'::character varying)::text])))
);

CREATE TABLE tpl.template_import_job (
    id uuid NOT NULL, -- 主键ID
    organization_id uuid NOT NULL, -- 组织ID
    source_file_id uuid NOT NULL, -- 源文件ID
    format character varying(16) NOT NULL, -- 格式
    status character varying(24) NOT NULL, -- 模板导入任务状态
    parser_version character varying(80), -- 解析器版本
    progress smallint DEFAULT 0 NOT NULL, -- 进度
    structure_summary_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 结构摘要（JSON）
    generated_template_version_id uuid, -- 生成的模板版本ID
    async_job_id uuid, -- 异步任务ID
    created_by uuid NOT NULL, -- 创建人ID
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    updated_at timestamp with time zone DEFAULT now() NOT NULL, -- 更新时间
    category_id uuid, -- 分类ID
    source_sha256 character(64), -- 来源哈希（SHA-256）
    duplicate_override boolean DEFAULT false NOT NULL, -- 重复覆盖标记
    duplicate_source_job_id uuid, -- 重复来源任务ID
    operation_source character varying(40) DEFAULT 'UPLOAD'::character varying NOT NULL, -- 操作来源
    original_source_file_id uuid, -- 原始源文件ID
    normalized_source_file_id uuid, -- 归一化源文件ID
    original_format character varying(20), -- 原始格式
    normalized_format character varying(20), -- 归一化格式
    normalization_status character varying(30), -- 归一化状态
    normalization_message text, -- 归一化信息
    hidden_at timestamp with time zone, -- 隐藏时间
    CONSTRAINT template_import_job_format_check CHECK (((format)::text = ANY (ARRAY[('XLSX'::character varying)::text, ('DOCX'::character varying)::text]))),
    CONSTRAINT template_import_job_progress_check CHECK (((progress >= 0) AND (progress <= 100)))
);

CREATE TABLE tpl.template_mapping (
    id uuid NOT NULL, -- 主键ID
    template_version_id uuid NOT NULL, -- 模板版本ID
    binding_id character varying(120) NOT NULL, -- 绑定ID
    field_id uuid, -- 字段ID
    parent_binding_id character varying(120), -- 父绑定ID
    marker_id character varying(160), -- 标记ID
    format character varying(16) NOT NULL, -- 格式
    field_code character varying(160), -- 字段编码
    data_path character varying(400) NOT NULL, -- 数据路径
    binding_role character varying(32) DEFAULT 'FIELD'::character varying NOT NULL, -- 绑定角色
    mapping_kind character varying(32) DEFAULT 'SCALAR'::character varying NOT NULL, -- 映射种类
    repeat_axis character varying(12), -- 重复方向
    record_height integer DEFAULT 1 NOT NULL, -- 记录高度
    record_width integer DEFAULT 1 NOT NULL, -- 记录宽度
    record_stride integer DEFAULT 1 NOT NULL, -- 记录步长
    termination_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 终止信息（JSON）
    locator_type character varying(60) NOT NULL, -- 定位类型
    locator_jsonb jsonb NOT NULL, -- 定位信息（JSON）
    sync_direction character varying(20) NOT NULL, -- 同步方向
    primary_binding boolean DEFAULT true NOT NULL, -- 是否主绑定
    binding_status character varying(20) DEFAULT 'VALID'::character varying NOT NULL, -- 绑定状态
    diagnostic_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 诊断信息（JSON）
    last_validated_at timestamp with time zone, -- 最近校验时间
    CONSTRAINT template_mapping_binding_role_check CHECK (((binding_role)::text = ANY (ARRAY[('FIELD'::character varying)::text, ('REPEAT_REGION'::character varying)::text, ('CONDITIONAL'::character varying)::text]))),
    CONSTRAINT template_mapping_binding_status_check CHECK (((binding_status)::text = ANY (ARRAY[('VALID'::character varying)::text, ('INVALID'::character varying)::text, ('AMBIGUOUS'::character varying)::text, ('MISSING'::character varying)::text]))),
    CONSTRAINT template_mapping_format_check CHECK (((format)::text = ANY (ARRAY[('XLSX'::character varying)::text, ('DOCX'::character varying)::text]))),
    CONSTRAINT template_mapping_mapping_kind_check CHECK (((mapping_kind)::text = ANY (ARRAY[('SCALAR'::character varying)::text, ('REPEAT_REGION'::character varying)::text, ('REPEAT_FIELD'::character varying)::text]))),
    CONSTRAINT template_mapping_record_height_check CHECK ((record_height > 0)),
    CONSTRAINT template_mapping_record_stride_check CHECK ((record_stride > 0)),
    CONSTRAINT template_mapping_record_width_check CHECK ((record_width > 0)),
    CONSTRAINT template_mapping_repeat_axis_check CHECK (((repeat_axis IS NULL) OR ((repeat_axis)::text = ANY (ARRAY[('ROW'::character varying)::text, ('COLUMN'::character varying)::text])))),
    CONSTRAINT template_mapping_sync_direction_check CHECK (((sync_direction)::text = ANY (ARRAY[('TWO_WAY'::character varying)::text, ('DATA_TO_EDITOR'::character varying)::text, ('EDITOR_TO_DATA'::character varying)::text])))
);

CREATE TABLE tpl.template_quality_issue (
    id uuid NOT NULL, -- 主键ID
    import_job_id uuid NOT NULL, -- 导入任务ID
    recognition_run_id uuid NOT NULL, -- 识别运行ID
    recognition_call_id uuid, -- 识别调用ID
    region_id character varying(256), -- 区域ID
    issue_type character varying(80) NOT NULL, -- 问题类型
    severity character varying(16) NOT NULL, -- 严重级别
    confidence numeric(5,4) NOT NULL, -- 置信度
    sheet_id character varying(160), -- 工作表ID
    sheet_name character varying(200), -- 工作表名称
    address character varying(80) NOT NULL, -- 地址
    title character varying(240) NOT NULL, -- 模板质量问题标题
    description text NOT NULL, -- 模板质量问题描述
    business_impact text NOT NULL, -- 业务影响
    evidence_jsonb jsonb DEFAULT '[]'::jsonb NOT NULL, -- 证据（JSON）
    suggested_patch_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 建议补丁（JSON）
    inverse_patch_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 反向补丁（JSON）
    auto_fixable boolean DEFAULT false NOT NULL, -- 是否可自动修复
    status character varying(24) NOT NULL, -- 模板质量问题状态
    before_snapshot_hash character(64), -- 变更前快照哈希
    after_snapshot_hash character(64), -- 变更后快照哈希
    root_block_id character varying(256), -- 根区块ID
    customer_issue_category character varying(80), -- 客户问题分类
    decided_by uuid, -- 决策人ID
    decided_at timestamp with time zone, -- 决策时间
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    updated_at timestamp with time zone DEFAULT now() NOT NULL, -- 更新时间
    CONSTRAINT template_quality_issue_severity_check CHECK (((severity)::text = ANY (ARRAY[('INFO'::character varying)::text, ('WARNING'::character varying)::text, ('BLOCKER'::character varying)::text]))),
    CONSTRAINT template_quality_issue_status_check CHECK (((status)::text = ANY (ARRAY[('DETECTED'::character varying)::text, ('AUTO_APPLIED'::character varying)::text, ('CONFIRMED'::character varying)::text, ('IGNORED'::character varying)::text, ('ROLLED_BACK'::character varying)::text, ('FAILED'::character varying)::text])))
);

CREATE TABLE tpl.template_static_region (
    id uuid NOT NULL, -- 主键ID
    template_version_id uuid NOT NULL, -- 模板版本ID
    sheet_id character varying(160) NOT NULL, -- 工作表ID
    sheet_name character varying(200), -- 工作表名称
    address character varying(120) NOT NULL, -- 地址
    region_type character varying(32) NOT NULL, -- 区域类型
    display_name character varying(160) NOT NULL, -- 显示名称
    source character varying(24) DEFAULT 'TEMPLATE_BASELINE'::character varying NOT NULL, -- 区域来源
    content_hash character(64), -- 内容哈希
    locked boolean DEFAULT true NOT NULL, -- 是否锁定
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    CONSTRAINT template_static_region_region_type_check CHECK (((region_type)::text = ANY (ARRAY[('STATIC_REFERENCE'::character varying)::text, ('INSTRUCTION'::character varying)::text, ('NOTE'::character varying)::text]))),
    CONSTRAINT template_static_region_source_check CHECK (((source)::text = ANY (ARRAY[('TEMPLATE_BASELINE'::character varying)::text, ('MODEL'::character varying)::text, ('HUMAN'::character varying)::text])))
);

CREATE TABLE tpl.template_structure_change (
    id uuid NOT NULL, -- 主键ID
    template_version_id uuid NOT NULL, -- 模板版本ID
    operation_order integer NOT NULL, -- 操作顺序
    operation_type character varying(32) NOT NULL, -- 操作类型
    sheet_id character varying(160) NOT NULL, -- 工作表ID
    operation_jsonb jsonb NOT NULL, -- 操作内容（JSON）
    source character varying(16) NOT NULL, -- 变更来源
    before_mapping_hash character(64) NOT NULL, -- 变更前映射哈希
    after_mapping_hash character(64) NOT NULL, -- 变更后映射哈希
    created_by uuid NOT NULL, -- 创建人ID
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    CONSTRAINT template_structure_change_operation_order_check CHECK ((operation_order >= 0)),
    CONSTRAINT template_structure_change_operation_type_check CHECK (((operation_type)::text = ANY (ARRAY[('INSERT_ROWS'::character varying)::text, ('DELETE_ROWS'::character varying)::text, ('INSERT_COLUMNS'::character varying)::text, ('DELETE_COLUMNS'::character varying)::text, ('RENAME_SHEET'::character varying)::text]))),
    CONSTRAINT template_structure_change_source_check CHECK (((source)::text = ANY (ARRAY[('CUSTOMER'::character varying)::text, ('AI'::character varying)::text])))
);

CREATE TABLE tpl.template_version (
    id uuid NOT NULL, -- 主键ID
    template_id uuid NOT NULL, -- 模板ID
    version_no integer NOT NULL, -- 版本号
    status character varying(20) NOT NULL, -- 模板版本状态
    schema_jsonb jsonb NOT NULL, -- 结构定义（JSON）
    layout_summary_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL, -- 版式摘要（JSON）
    source_file_id uuid, -- 源文件ID
    editor_snapshot_file_id uuid, -- 编辑器快照文件ID
    editor_snapshot_hash character(64), -- 编辑器快照哈希
    snapshot_kind character varying(32) NOT NULL, -- 快照种类
    editor_app_version character varying(80) NOT NULL, -- 编辑器应用版本
    plugin_manifest_hash character(64) NOT NULL, -- 插件清单哈希
    snapshot_format_version integer DEFAULT 1 NOT NULL, -- 快照格式版本
    export_office_file_id uuid, -- 导出Office文件ID
    export_office_hash character(64), -- 导出Office文件哈希
    schema_hash character(64) NOT NULL, -- 结构哈希
    mapping_hash character(64) NOT NULL, -- 映射哈希
    data_hash character(64) NOT NULL, -- 数据哈希
    workspace_hash character(64) NOT NULL, -- 工作区哈希
    lock_version bigint DEFAULT 0 NOT NULL, -- 乐观锁版本号
    derived_from_version_id uuid, -- 派生来源版本ID
    created_by uuid NOT NULL, -- 创建人ID
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    updated_at timestamp with time zone DEFAULT now() NOT NULL, -- 更新时间
    published_at timestamp with time zone, -- 发布时间
    CONSTRAINT template_version_snapshot_kind_check CHECK (((snapshot_kind)::text = ANY (ARRAY[('UNIVER_WORKBOOK'::character varying)::text, ('UNIVER_DOCUMENT'::character varying)::text]))),
    CONSTRAINT template_version_status_check CHECK (((status)::text = ANY (ARRAY[('DRAFT'::character varying)::text, ('PUBLISHED'::character varying)::text, ('RETIRED'::character varying)::text]))),
    CONSTRAINT template_version_version_no_check CHECK ((version_no > 0))
);

CREATE TABLE tpl.template_version_review (
    version_id uuid NOT NULL, -- 版本ID
    organization_id uuid NOT NULL, -- 组织ID
    review_status character varying(20) DEFAULT 'NOT_SUBMITTED'::character varying NOT NULL, -- 审核状态
    submitted_by uuid, -- 提交人ID
    submitted_at timestamp with time zone, -- 提交时间
    reviewed_by uuid, -- 审核人ID
    reviewed_at timestamp with time zone, -- 审核时间
    review_comment text, -- 审核意见
    lock_version bigint DEFAULT 0 NOT NULL, -- 乐观锁版本号
    updated_at timestamp with time zone DEFAULT now() NOT NULL, -- 更新时间
    CONSTRAINT template_version_review_status_check CHECK (((review_status)::text = ANY (ARRAY[('NOT_SUBMITTED'::character varying)::text, ('SUBMITTED'::character varying)::text, ('APPROVED'::character varying)::text, ('REJECTED'::character varying)::text])))
);

CREATE TABLE tpl.template_version_review_event (
    id uuid NOT NULL, -- 主键ID
    version_id uuid NOT NULL, -- 版本ID
    organization_id uuid NOT NULL, -- 组织ID
    from_status character varying(20), -- 变更前状态
    to_status character varying(20) NOT NULL, -- 变更后状态
    actor_id uuid, -- 操作者ID
    comment text, -- 备注
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间
    CONSTRAINT template_version_review_event_status_check CHECK (((to_status)::text = ANY (ARRAY[('NOT_SUBMITTED'::character varying)::text, ('SUBMITTED'::character varying)::text, ('APPROVED'::character varying)::text, ('REJECTED'::character varying)::text])))
);

CREATE TABLE tpl.unified_lab_template_package (
    template_code character varying(80) NOT NULL, -- 模板编码
    package_hash character(64) NOT NULL, -- 安装包哈希
    payload_jsonb jsonb NOT NULL, -- 载荷（JSON）
    installed_at timestamp with time zone DEFAULT now() NOT NULL -- 安装时间
);
