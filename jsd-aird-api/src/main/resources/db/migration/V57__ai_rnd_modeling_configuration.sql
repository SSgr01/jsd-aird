-- R01: single transactional migration. Sections remain ordered as
-- configuration -> facts/reviews -> snapshots/models -> prediction/research.
-- Existing V1 contracts remain untouched.

INSERT INTO iam.permission_definition (code, module, name, risk, default_scope)
VALUES
    ('ai.modeling.read', 'ai', '查看建模设置', 'LOW', 'ALL'),
    ('ai.model.read', 'ai', '查看模型', 'LOW', 'ALL'),
    ('ai.performance.predict', 'ai', '性能预测', 'MEDIUM', 'ALL'),
    ('ai.formula.predict', 'ai', '配方预测', 'MEDIUM', 'ALL'),
    ('ai.experiment.optimize', 'ai', '实验优化', 'MEDIUM', 'ALL'),
    ('ai.modeling.manage', 'ai', '管理建模设置', 'HIGH', 'ALL'),
    ('ai.data.review', 'ai', '审查训练数据', 'HIGH', 'ALL'),
    ('ai.model.publish', 'ai', '发布与回退模型', 'CRITICAL', 'ALL'),
    ('ai.training.operate', 'ai', '操作训练任务', 'HIGH', 'ALL'),
    ('ai.config.manage', 'ai', '管理AI配置', 'CRITICAL', 'ALL')
ON CONFLICT (code) DO UPDATE SET
    module = EXCLUDED.module, name = EXCLUDED.name, risk = EXCLUDED.risk,
    default_scope = EXCLUDED.default_scope, enabled = true, updated_at = now(),
    definition_version = iam.permission_definition.definition_version + 1;

CREATE OR REPLACE FUNCTION ai.reject_immutable_change() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION USING ERRCODE = '55000', MESSAGE = TG_TABLE_SCHEMA || '.' || TG_TABLE_NAME || ' is immutable';
END $$;

CREATE TABLE ai.prediction_target (
    id uuid PRIMARY KEY, organization_id uuid NOT NULL REFERENCES iam.organization(id),
    target_code varchar(160) NOT NULL, name varchar(240) NOT NULL, performance_project varchar(160) NOT NULL,
    value_type varchar(24) NOT NULL, status varchar(24) NOT NULL DEFAULT 'DRAFT', current_version_id uuid,
    current_input_scheme_id uuid,
    revision bigint NOT NULL DEFAULT 0, created_by uuid NOT NULL REFERENCES iam.app_user(id),
    updated_by uuid NOT NULL REFERENCES iam.app_user(id), created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(), UNIQUE (organization_id, id), UNIQUE (organization_id, target_code),
    CONSTRAINT prediction_target_value_type_check CHECK (value_type IN ('CONTINUOUS','ORDINAL','BINARY','CATEGORICAL')),
    CONSTRAINT prediction_target_status_check CHECK (status IN ('DRAFT','ACTIVE','PAUSED','RETIRED'))
);

CREATE TABLE ai.target_version (
    id uuid PRIMARY KEY, organization_id uuid NOT NULL REFERENCES iam.organization(id), target_id uuid NOT NULL,
    version_no integer NOT NULL CHECK (version_no > 0), status varchar(24) NOT NULL DEFAULT 'DRAFT',
    value_type varchar(24) NOT NULL,
    unit varchar(80), classes_jsonb jsonb NOT NULL DEFAULT '[]'::jsonb,
    definition_jsonb jsonb NOT NULL, observation_semantics_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    config_hash char(64) NOT NULL, published_at timestamptz, created_by uuid NOT NULL REFERENCES iam.app_user(id),
    created_at timestamptz NOT NULL DEFAULT now(), UNIQUE (organization_id, id),
    UNIQUE (organization_id, target_id, id), UNIQUE (organization_id, target_id, version_no),
    FOREIGN KEY (organization_id, target_id) REFERENCES ai.prediction_target(organization_id, id),
    CONSTRAINT target_version_status_check CHECK (status IN ('DRAFT','PUBLISHED','RETIRED')),
    CONSTRAINT target_version_value_type_check CHECK (value_type IN ('CONTINUOUS','ORDINAL','BINARY','CATEGORICAL')),
    CONSTRAINT target_version_classes_array CHECK (jsonb_typeof(classes_jsonb) = 'array'),
    CONSTRAINT target_version_definition_object CHECK (jsonb_typeof(definition_jsonb) = 'object'),
    CONSTRAINT target_version_semantics_object CHECK (jsonb_typeof(observation_semantics_jsonb) = 'object'),
    CONSTRAINT target_version_hash_check CHECK (config_hash ~ '^[0-9a-f]{64}$')
);
ALTER TABLE ai.prediction_target ADD CONSTRAINT prediction_target_current_version_fk
    FOREIGN KEY (organization_id, id, current_version_id)
    REFERENCES ai.target_version(organization_id, target_id, id);

CREATE TABLE ai.input_field (
    id uuid PRIMARY KEY, organization_id uuid NOT NULL REFERENCES iam.organization(id), field_code varchar(160) NOT NULL,
    name varchar(240) NOT NULL, value_type varchar(24) NOT NULL, availability_stage varchar(32) NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'DRAFT', current_version_id uuid, revision bigint NOT NULL DEFAULT 0,
    created_by uuid NOT NULL REFERENCES iam.app_user(id), updated_by uuid NOT NULL REFERENCES iam.app_user(id),
    created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, id), UNIQUE (organization_id, field_code),
    CONSTRAINT input_field_value_type_check CHECK (value_type IN ('NUMBER','STRING','BOOLEAN','CATEGORY','COMPOSITION')),
    CONSTRAINT input_field_stage_check CHECK (availability_stage IN ('PRE_EXPERIMENT','POST_EXPERIMENT')),
    CONSTRAINT input_field_status_check CHECK (status IN ('DRAFT','ACTIVE','PAUSED','RETIRED'))
);

CREATE TABLE ai.input_field_version (
    id uuid PRIMARY KEY, organization_id uuid NOT NULL REFERENCES iam.organization(id), input_field_id uuid NOT NULL,
    version_no integer NOT NULL CHECK (version_no > 0), status varchar(24) NOT NULL DEFAULT 'DRAFT',
    value_type varchar(24) NOT NULL, unit varchar(80), availability_stage varchar(32) NOT NULL,
    standard_field_dictionary_id uuid REFERENCES tpl.standard_field_dictionary(id),
    definition_jsonb jsonb NOT NULL, preprocessing_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb, config_hash char(64) NOT NULL,
    published_at timestamptz, created_by uuid NOT NULL REFERENCES iam.app_user(id), created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, id), UNIQUE (organization_id, input_field_id, id),
    UNIQUE (organization_id, input_field_id, version_no),
    FOREIGN KEY (organization_id, input_field_id) REFERENCES ai.input_field(organization_id, id),
    CONSTRAINT input_field_version_status_check CHECK (status IN ('DRAFT','PUBLISHED','RETIRED')),
    CONSTRAINT input_field_version_value_type_check CHECK (value_type IN ('NUMBER','STRING','BOOLEAN','CATEGORY','COMPOSITION')),
    CONSTRAINT input_field_version_stage_check CHECK (availability_stage IN ('PRE_EXPERIMENT','POST_EXPERIMENT')),
    CONSTRAINT input_field_definition_object CHECK (jsonb_typeof(definition_jsonb) = 'object'),
    CONSTRAINT input_field_preprocess_object CHECK (jsonb_typeof(preprocessing_jsonb) = 'object'),
    CONSTRAINT input_field_version_hash_check CHECK (config_hash ~ '^[0-9a-f]{64}$')
);
ALTER TABLE ai.input_field ADD CONSTRAINT input_field_current_version_fk
    FOREIGN KEY (organization_id, id, current_version_id)
    REFERENCES ai.input_field_version(organization_id, input_field_id, id);

CREATE TABLE ai.input_scheme (
    id uuid PRIMARY KEY, organization_id uuid NOT NULL REFERENCES iam.organization(id), target_id uuid NOT NULL,
    target_version_id uuid NOT NULL,
    material_dictionary_version_id uuid,
    scheme_code varchar(160) NOT NULL, name varchar(240) NOT NULL, version_no integer NOT NULL CHECK (version_no > 0),
    status varchar(24) NOT NULL DEFAULT 'DRAFT', preprocessing_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    config_hash char(64) NOT NULL, revision bigint NOT NULL DEFAULT 0, frozen_at timestamptz,
    created_by uuid NOT NULL REFERENCES iam.app_user(id), updated_by uuid NOT NULL REFERENCES iam.app_user(id),
    created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, id), UNIQUE (organization_id, target_id, id),
    UNIQUE (organization_id, target_id, scheme_code, version_no),
    FOREIGN KEY (organization_id, target_id) REFERENCES ai.prediction_target(organization_id, id),
    FOREIGN KEY (organization_id, target_id, target_version_id)
        REFERENCES ai.target_version(organization_id, target_id, id),
    CONSTRAINT input_scheme_status_check CHECK (status IN ('DRAFT','FROZEN','RETIRED')),
    CONSTRAINT input_scheme_preprocess_object CHECK (jsonb_typeof(preprocessing_jsonb) = 'object'),
    CONSTRAINT input_scheme_hash_check CHECK (config_hash ~ '^[0-9a-f]{64}$')
);

ALTER TABLE ai.prediction_target ADD CONSTRAINT prediction_target_current_input_scheme_fk
    FOREIGN KEY (organization_id, id, current_input_scheme_id)
    REFERENCES ai.input_scheme(organization_id, target_id, id);

CREATE TABLE ai.input_scheme_field (
    organization_id uuid NOT NULL REFERENCES iam.organization(id), input_scheme_id uuid NOT NULL,
    input_field_version_id uuid NOT NULL, required boolean NOT NULL, ordinal integer NOT NULL CHECK (ordinal >= 0),
    override_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    PRIMARY KEY (input_scheme_id, input_field_version_id),
    FOREIGN KEY (organization_id, input_scheme_id) REFERENCES ai.input_scheme(organization_id, id),
    FOREIGN KEY (organization_id, input_field_version_id) REFERENCES ai.input_field_version(organization_id, id),
    CONSTRAINT input_scheme_field_override_object CHECK (jsonb_typeof(override_jsonb) = 'object')
);

CREATE TABLE ai.source_mapping_version (
    id uuid PRIMARY KEY, organization_id uuid NOT NULL REFERENCES iam.organization(id), target_id uuid NOT NULL,
    target_version_id uuid NOT NULL,
    source_type varchar(32) NOT NULL, version_no integer NOT NULL CHECK (version_no > 0), status varchar(24) NOT NULL,
    mapping_jsonb jsonb NOT NULL, mapping_hash char(64) NOT NULL, published_at timestamptz,
    created_by uuid NOT NULL REFERENCES iam.app_user(id), created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, id), UNIQUE (organization_id, target_version_id, source_type, version_no),
    FOREIGN KEY (organization_id, target_id) REFERENCES ai.prediction_target(organization_id, id),
    FOREIGN KEY (organization_id, target_id, target_version_id)
        REFERENCES ai.target_version(organization_id, target_id, id),
    CONSTRAINT source_mapping_type_check CHECK (source_type IN ('DATA_CENTER','EXPERIMENT')),
    CONSTRAINT source_mapping_status_check CHECK (status IN ('DRAFT','PUBLISHED','RETIRED')),
    CONSTRAINT source_mapping_json_object CHECK (jsonb_typeof(mapping_jsonb) = 'object'),
    CONSTRAINT source_mapping_hash_check CHECK (mapping_hash ~ '^[0-9a-f]{64}$')
);

CREATE TABLE ai.modeling_policy_version (
    id uuid PRIMARY KEY, organization_id uuid NOT NULL REFERENCES iam.organization(id), target_id uuid NOT NULL,
    version_no integer NOT NULL CHECK (version_no > 0), status varchar(24) NOT NULL,
    qualification_jsonb jsonb NOT NULL, validation_jsonb jsonb NOT NULL, training_jsonb jsonb NOT NULL,
    policy_hash char(64) NOT NULL, published_at timestamptz, created_by uuid NOT NULL REFERENCES iam.app_user(id),
    created_at timestamptz NOT NULL DEFAULT now(), UNIQUE (organization_id, id),
    UNIQUE (organization_id, target_id, version_no),
    FOREIGN KEY (organization_id, target_id) REFERENCES ai.prediction_target(organization_id, id),
    CONSTRAINT modeling_policy_status_check CHECK (status IN ('DRAFT','PUBLISHED','RETIRED')),
    CONSTRAINT modeling_policy_qualification_object CHECK (jsonb_typeof(qualification_jsonb) = 'object'),
    CONSTRAINT modeling_policy_validation_object CHECK (jsonb_typeof(validation_jsonb) = 'object'),
    CONSTRAINT modeling_policy_training_object CHECK (jsonb_typeof(training_jsonb) = 'object'),
    CONSTRAINT modeling_policy_hash_check CHECK (policy_hash ~ '^[0-9a-f]{64}$')
);

CREATE TABLE ai.material_dictionary_version (
    id uuid PRIMARY KEY, organization_id uuid NOT NULL REFERENCES iam.organization(id),
    dictionary_code varchar(160) NOT NULL, version_no integer NOT NULL CHECK (version_no > 0), status varchar(24) NOT NULL,
    vocabulary_jsonb jsonb NOT NULL, encoder_jsonb jsonb NOT NULL, dictionary_hash char(64) NOT NULL,
    revision bigint NOT NULL DEFAULT 0, frozen_at timestamptz,
    created_by uuid NOT NULL REFERENCES iam.app_user(id), created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, id), UNIQUE (organization_id, dictionary_code, version_no),
    UNIQUE (organization_id, dictionary_hash),
    CONSTRAINT material_dictionary_status_check CHECK (status IN ('DRAFT','FROZEN','RETIRED')),
    CONSTRAINT material_dictionary_vocabulary_array CHECK (jsonb_typeof(vocabulary_jsonb) = 'array'),
    CONSTRAINT material_dictionary_encoder_object CHECK (jsonb_typeof(encoder_jsonb) = 'object'),
    CONSTRAINT material_dictionary_hash_check CHECK (dictionary_hash ~ '^[0-9a-f]{64}$')
);

ALTER TABLE ai.input_scheme ADD CONSTRAINT input_scheme_material_dictionary_fk
    FOREIGN KEY (organization_id, material_dictionary_version_id)
    REFERENCES ai.material_dictionary_version(organization_id, id);

CREATE TABLE mdm.material_alias (
    id uuid PRIMARY KEY, organization_id uuid NOT NULL REFERENCES iam.organization(id), material_id uuid NOT NULL REFERENCES mdm.material(id),
    normalized_alias varchar(240) NOT NULL, display_alias varchar(240) NOT NULL, status varchar(24) NOT NULL DEFAULT 'ACTIVE',
    revision bigint NOT NULL DEFAULT 0, created_by uuid NOT NULL REFERENCES iam.app_user(id),
    updated_by uuid NOT NULL REFERENCES iam.app_user(id), created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(), UNIQUE (organization_id, id), UNIQUE (organization_id, normalized_alias),
    CONSTRAINT material_alias_status_check CHECK (status IN ('ACTIVE','RETIRED'))
);

CREATE TABLE ai.configuration_command_receipt (
    id uuid PRIMARY KEY, organization_id uuid NOT NULL REFERENCES iam.organization(id),
    operation varchar(120) NOT NULL, idempotency_key varchar(200) NOT NULL,
    request_hash char(64) NOT NULL, resource_type varchar(80) NOT NULL, resource_id uuid,
    response_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_by uuid NOT NULL REFERENCES iam.app_user(id), created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, operation, idempotency_key),
    CONSTRAINT configuration_receipt_hash_check CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT configuration_receipt_response_object CHECK (jsonb_typeof(response_jsonb) = 'object')
);

CREATE INDEX idx_prediction_target_project ON ai.prediction_target(organization_id, performance_project, target_code);
CREATE INDEX idx_prediction_target_list ON ai.prediction_target(organization_id, updated_at DESC, id);
CREATE INDEX idx_input_field_catalog ON ai.input_field(organization_id, availability_stage, field_code);
CREATE INDEX idx_input_field_list ON ai.input_field(organization_id, updated_at DESC, id);
CREATE INDEX idx_input_scheme_target ON ai.input_scheme(organization_id, target_id, created_at DESC, id);
CREATE INDEX idx_input_scheme_material_dictionary ON ai.input_scheme(organization_id, material_dictionary_version_id)
    WHERE material_dictionary_version_id IS NOT NULL;
CREATE INDEX idx_source_mapping_target_version ON ai.source_mapping_version(organization_id, target_version_id, source_type, version_no DESC, id);
CREATE INDEX idx_configuration_receipt_created ON ai.configuration_command_receipt(organization_id, created_at DESC, id);

CREATE TRIGGER immutable_target_version BEFORE UPDATE OR DELETE ON ai.target_version
    FOR EACH ROW WHEN (OLD.status = 'PUBLISHED') EXECUTE FUNCTION ai.reject_immutable_change();
CREATE TRIGGER immutable_input_field_version BEFORE UPDATE OR DELETE ON ai.input_field_version
    FOR EACH ROW WHEN (OLD.status = 'PUBLISHED') EXECUTE FUNCTION ai.reject_immutable_change();
CREATE TRIGGER immutable_input_scheme BEFORE UPDATE OR DELETE ON ai.input_scheme
    FOR EACH ROW WHEN (OLD.status = 'FROZEN') EXECUTE FUNCTION ai.reject_immutable_change();
CREATE OR REPLACE FUNCTION ai.protect_frozen_input_scheme_field() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP <> 'INSERT' AND EXISTS (
        SELECT 1 FROM ai.input_scheme s
        WHERE s.organization_id=OLD.organization_id AND s.id=OLD.input_scheme_id AND s.status='FROZEN'
    ) THEN
        RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='frozen input scheme fields are immutable';
    END IF;
    IF TG_OP <> 'DELETE' AND EXISTS (
        SELECT 1 FROM ai.input_scheme s
        WHERE s.organization_id=NEW.organization_id AND s.id=NEW.input_scheme_id AND s.status='FROZEN'
    ) THEN
        RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='frozen input scheme fields are immutable';
    END IF;
    IF TG_OP='DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER protect_frozen_input_scheme_field
    BEFORE INSERT OR UPDATE OR DELETE ON ai.input_scheme_field
    FOR EACH ROW EXECUTE FUNCTION ai.protect_frozen_input_scheme_field();
CREATE TRIGGER immutable_source_mapping BEFORE UPDATE OR DELETE ON ai.source_mapping_version
    FOR EACH ROW WHEN (OLD.status = 'PUBLISHED') EXECUTE FUNCTION ai.reject_immutable_change();
CREATE TRIGGER immutable_modeling_policy BEFORE UPDATE OR DELETE ON ai.modeling_policy_version
    FOR EACH ROW WHEN (OLD.status = 'PUBLISHED') EXECUTE FUNCTION ai.reject_immutable_change();
CREATE OR REPLACE FUNCTION ai.protect_frozen_material_dictionary() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status = 'RETIRED' THEN
        RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='retired material dictionary is immutable';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='frozen material dictionary is immutable';
    END IF;
    IF NEW.status <> 'RETIRED'
       OR NEW.dictionary_code IS DISTINCT FROM OLD.dictionary_code
       OR NEW.version_no IS DISTINCT FROM OLD.version_no
       OR NEW.vocabulary_jsonb IS DISTINCT FROM OLD.vocabulary_jsonb
       OR NEW.encoder_jsonb IS DISTINCT FROM OLD.encoder_jsonb
       OR NEW.dictionary_hash IS DISTINCT FROM OLD.dictionary_hash
       OR NEW.frozen_at IS DISTINCT FROM OLD.frozen_at
       OR NEW.created_by IS DISTINCT FROM OLD.created_by
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='frozen material dictionary content is immutable';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER immutable_material_dictionary BEFORE UPDATE OR DELETE ON ai.material_dictionary_version
    FOR EACH ROW WHEN (OLD.status IN ('FROZEN','RETIRED')) EXECUTE FUNCTION ai.protect_frozen_material_dictionary();

UPDATE iam.role SET policy_version = policy_version + 1;
UPDATE iam.app_user SET auth_version = auth_version + 1;

-- R01/2: confirmed revisions, canonical sample identity and per-target eligibility.

-- Legacy foundation tables used globally unique ids. Add organization-qualified
-- candidate keys so every new FK also proves tenant ownership.
ALTER TABLE data.import_job ADD CONSTRAINT uq_data_import_job_org_id UNIQUE (organization_id, id);
ALTER TABLE data.data_record ADD CONSTRAINT uq_data_record_org_id UNIQUE (organization_id, id);
ALTER TABLE rnd.experiment ADD CONSTRAINT uq_rnd_experiment_org_id UNIQUE (organization_id, id);
ALTER TABLE rnd.experiment_version ADD CONSTRAINT uq_rnd_experiment_version_org_id UNIQUE (organization_id, id);
ALTER TABLE rnd.experiment_import_job ADD CONSTRAINT uq_rnd_experiment_import_job_org_id UNIQUE (organization_id, id);

-- R03: data-center imports and experiment-notebook uploads share one recognition
-- task.  The owner controls lifecycle and authorization; it does not move the
-- source into another business module.
ALTER TABLE data.import_job
    ALTER COLUMN template_version_id DROP NOT NULL,
    ADD COLUMN source_owner varchar(24) NOT NULL DEFAULT 'DATA_CENTER',
    ADD COLUMN recognition_mode varchar(32) NOT NULL DEFAULT 'TEMPLATE_GUIDED',
    ADD COLUMN recognition_workspace_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN experiment_boundaries_jsonb jsonb NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN recognition_revision bigint NOT NULL DEFAULT 0,
    ADD COLUMN recognition_profile_id uuid,
    ADD COLUMN visibility varchar(24) NOT NULL DEFAULT 'ALL',
    ADD COLUMN source_sequence bigint NOT NULL DEFAULT 1,
    ADD COLUMN finalized_at timestamptz;

ALTER TABLE data.import_job DROP CONSTRAINT import_job_source_format_check;
ALTER TABLE data.import_job ADD CONSTRAINT import_job_source_format_check
    CHECK (source_format IN ('XLS','XLSX','CSV','DOCX','PDF','IMAGE'));
ALTER TABLE data.import_job ADD CONSTRAINT import_job_source_owner_check
    CHECK (source_owner IN ('DATA_CENTER','EXPERIMENT'));
ALTER TABLE data.import_job ADD CONSTRAINT import_job_recognition_mode_check
    CHECK (recognition_mode IN ('FREEFORM','TEMPLATE_GUIDED'));
ALTER TABLE data.import_job ADD CONSTRAINT import_job_recognition_workspace_object
    CHECK (jsonb_typeof(recognition_workspace_jsonb) = 'object');
ALTER TABLE data.import_job ADD CONSTRAINT import_job_experiment_boundaries_array
    CHECK (jsonb_typeof(experiment_boundaries_jsonb) = 'array');
ALTER TABLE data.import_job ADD CONSTRAINT import_job_visibility_check
    CHECK (visibility IN ('ALL','QUALITY','PROJECT'));
ALTER TABLE data.import_job ADD CONSTRAINT import_job_recognition_contract_check CHECK (
    (recognition_mode = 'TEMPLATE_GUIDED' AND template_version_id IS NOT NULL)
    OR recognition_mode = 'FREEFORM'
);

CREATE TABLE data.recognition_profile (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES iam.organization(id),
    profile_code varchar(160) NOT NULL,
    name varchar(240) NOT NULL,
    source_format varchar(16) NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'ACTIVE',
    rules_jsonb jsonb NOT NULL,
    profile_hash char(64) NOT NULL,
    revision bigint NOT NULL DEFAULT 0,
    created_by uuid NOT NULL REFERENCES iam.app_user(id),
    updated_by uuid NOT NULL REFERENCES iam.app_user(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, id),
    UNIQUE (organization_id, profile_code),
    CONSTRAINT recognition_profile_format_check CHECK (source_format IN ('XLS','XLSX','CSV','DOCX','PDF','IMAGE')),
    CONSTRAINT recognition_profile_status_check CHECK (status IN ('ACTIVE','RETIRED')),
    CONSTRAINT recognition_profile_rules_object CHECK (jsonb_typeof(rules_jsonb) = 'object'),
    CONSTRAINT recognition_profile_hash_check CHECK (profile_hash ~ '^[0-9a-f]{64}$')
);
ALTER TABLE data.import_job ADD CONSTRAINT import_job_recognition_profile_fk
    FOREIGN KEY (organization_id, recognition_profile_id)
    REFERENCES data.recognition_profile(organization_id, id);
CREATE INDEX idx_data_import_job_owner_list
    ON data.import_job(organization_id, source_owner, created_at DESC, id);
CREATE INDEX idx_data_import_job_recognition_status
    ON data.import_job(organization_id, source_owner, status, updated_at DESC, id);
CREATE INDEX idx_recognition_profile_list
    ON data.recognition_profile(organization_id, status, updated_at DESC, id);

CREATE TABLE data.confirmed_submission (
    id uuid PRIMARY KEY, organization_id uuid NOT NULL REFERENCES iam.organization(id), import_job_id uuid NOT NULL,
    revision_no integer NOT NULL CHECK (revision_no > 0), status varchar(24) NOT NULL,
    content_hash char(64) NOT NULL, confirmed_by uuid NOT NULL REFERENCES iam.app_user(id), confirmed_at timestamptz NOT NULL DEFAULT now(),
    supersedes_submission_id uuid, invalidated_at timestamptz, invalidation_reason text,
    source_sequence bigint NOT NULL, mapping_contract_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    UNIQUE (organization_id, id), UNIQUE (organization_id, import_job_id, id),
    UNIQUE (organization_id, import_job_id, revision_no),
    FOREIGN KEY (organization_id, import_job_id) REFERENCES data.import_job(organization_id, id),
    FOREIGN KEY (organization_id, supersedes_submission_id) REFERENCES data.confirmed_submission(organization_id, id),
    CONSTRAINT confirmed_submission_status_check CHECK (status IN ('CONFIRMED','SUPERSEDED','INVALIDATED')),
    CONSTRAINT confirmed_submission_hash_check CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT confirmed_submission_mapping_contract_object CHECK (jsonb_typeof(mapping_contract_jsonb) = 'object')
);

CREATE TABLE data.confirmed_submission_item (
    id uuid PRIMARY KEY, organization_id uuid NOT NULL REFERENCES iam.organization(id), confirmed_submission_id uuid NOT NULL,
    source_record_id uuid, source_record_version bigint NOT NULL,
    source_file_id uuid REFERENCES ops.file_object(id), sheet_name varchar(240), row_coordinate varchar(120),
    mapping_version_id uuid, source_identity_jsonb jsonb NOT NULL,
    raw_fact_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    corrected_fact_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    fact_jsonb jsonb NOT NULL, source_coordinates_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    source_file_sha256 char(64) NOT NULL, content_hash char(64) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(), UNIQUE (organization_id, id),
    UNIQUE (organization_id, confirmed_submission_id, source_record_id),
    FOREIGN KEY (organization_id, confirmed_submission_id) REFERENCES data.confirmed_submission(organization_id, id),
    -- source_record_id is an immutable historical identifier. The mutable
    -- data.data_record read model may be replaced by a later confirmation.
    FOREIGN KEY (organization_id, mapping_version_id) REFERENCES ai.source_mapping_version(organization_id, id),
    CONSTRAINT confirmed_submission_identity_object CHECK (jsonb_typeof(source_identity_jsonb) = 'object'),
    CONSTRAINT confirmed_submission_raw_fact_object CHECK (jsonb_typeof(raw_fact_jsonb) = 'object'),
    CONSTRAINT confirmed_submission_corrected_fact_object CHECK (jsonb_typeof(corrected_fact_jsonb) = 'object'),
    CONSTRAINT confirmed_submission_fact_object CHECK (jsonb_typeof(fact_jsonb) = 'object'),
    CONSTRAINT confirmed_submission_coordinates_object CHECK (jsonb_typeof(source_coordinates_jsonb) = 'object'),
    CONSTRAINT confirmed_submission_file_hash_check CHECK (source_file_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT confirmed_submission_item_hash_check CHECK (content_hash ~ '^[0-9a-f]{64}$')
);

CREATE TABLE data.confirmed_submission_head (
    organization_id uuid NOT NULL REFERENCES iam.organization(id), import_job_id uuid NOT NULL,
    confirmed_submission_id uuid NOT NULL, revision bigint NOT NULL DEFAULT 0, updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, import_job_id),
    FOREIGN KEY (organization_id, import_job_id) REFERENCES data.import_job(organization_id, id),
    FOREIGN KEY (organization_id, import_job_id, confirmed_submission_id)
        REFERENCES data.confirmed_submission(organization_id, import_job_id, id)
);

ALTER TABLE data.import_experiment_link
    ALTER COLUMN assembly_key TYPE varchar(240),
    ALTER COLUMN parent_assembly_key TYPE varchar(240),
    ADD COLUMN recognition_job_id uuid,
    ADD COLUMN confirmed_submission_id uuid,
    ADD COLUMN experiment_boundary_id varchar(240),
    ADD COLUMN sample_boundary_ids_jsonb jsonb NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN logical_sample_keys_jsonb jsonb NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN source_group_keys_jsonb jsonb NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN source_snapshot_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN source_updated boolean NOT NULL DEFAULT false;
UPDATE data.import_experiment_link SET recognition_job_id = import_job_id WHERE recognition_job_id IS NULL;
UPDATE data.import_experiment_link SET
    experiment_boundary_id = assembly_key,
    sample_boundary_ids_jsonb = jsonb_build_array(assembly_key || '/sample-1'),
    logical_sample_keys_jsonb = jsonb_build_array('LEGACY:' || import_job_id::text || ':' || assembly_key),
    source_group_keys_jsonb = source_record_keys_jsonb
WHERE experiment_boundary_id IS NULL;
ALTER TABLE data.import_experiment_link ALTER COLUMN recognition_job_id SET NOT NULL;
ALTER TABLE data.import_experiment_link ALTER COLUMN experiment_boundary_id SET NOT NULL;
ALTER TABLE data.import_experiment_link ADD CONSTRAINT import_experiment_link_recognition_job_fk
    FOREIGN KEY (organization_id, recognition_job_id) REFERENCES data.import_job(organization_id, id);
ALTER TABLE data.import_experiment_link ADD CONSTRAINT import_experiment_link_submission_fk
    FOREIGN KEY (organization_id, confirmed_submission_id) REFERENCES data.confirmed_submission(organization_id, id);
ALTER TABLE data.import_experiment_link ADD CONSTRAINT import_experiment_link_source_snapshot_object
    CHECK (jsonb_typeof(source_snapshot_jsonb) = 'object');
ALTER TABLE data.import_experiment_link ADD CONSTRAINT import_experiment_link_sample_boundaries_array
    CHECK (jsonb_typeof(sample_boundary_ids_jsonb) = 'array');
ALTER TABLE data.import_experiment_link ADD CONSTRAINT import_experiment_link_logical_samples_array
    CHECK (jsonb_typeof(logical_sample_keys_jsonb) = 'array');
ALTER TABLE data.import_experiment_link ADD CONSTRAINT import_experiment_link_source_groups_array
    CHECK (jsonb_typeof(source_group_keys_jsonb) = 'array');
CREATE INDEX idx_import_experiment_link_submission
    ON data.import_experiment_link(organization_id, confirmed_submission_id)
    WHERE confirmed_submission_id IS NOT NULL;
COMMENT ON COLUMN data.import_experiment_link.experiment_boundary_id IS
    'Confirmed experimentBoundaryId; unique with organization/import job through the existing assembly_key key.';

-- Frozen source evidence owned by RND.  It survives later workspace edits and is
-- the only supported bridge from a recognized region to an experiment version.
CREATE TABLE rnd.experiment_source_reference (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES iam.organization(id),
    experiment_id uuid NOT NULL,
    experiment_version_id uuid NOT NULL,
    recognition_job_id uuid,
    experiment_import_job_id uuid,
    confirmed_submission_id uuid,
    experiment_boundary_id varchar(240) NOT NULL,
    sample_boundary_id varchar(240) NOT NULL,
    logical_sample_key varchar(320) NOT NULL,
    source_group_keys_jsonb jsonb NOT NULL,
    source_file_id uuid NOT NULL REFERENCES ops.file_object(id),
    source_file_sha256 char(64) NOT NULL,
    source_coordinates_jsonb jsonb NOT NULL,
    recognition_snapshot_jsonb jsonb NOT NULL,
    content_hash char(64) NOT NULL,
    created_by uuid NOT NULL REFERENCES iam.app_user(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, id),
    UNIQUE (organization_id, experiment_version_id, recognition_job_id, sample_boundary_id),
    FOREIGN KEY (organization_id, experiment_id) REFERENCES rnd.experiment(organization_id, id),
    FOREIGN KEY (organization_id, experiment_version_id) REFERENCES rnd.experiment_version(organization_id, id),
    FOREIGN KEY (organization_id, recognition_job_id) REFERENCES data.import_job(organization_id, id),
    FOREIGN KEY (organization_id, experiment_import_job_id) REFERENCES rnd.experiment_import_job(organization_id, id),
    FOREIGN KEY (organization_id, confirmed_submission_id) REFERENCES data.confirmed_submission(organization_id, id),
    CONSTRAINT experiment_source_coordinates_object CHECK (jsonb_typeof(source_coordinates_jsonb) = 'object'),
    CONSTRAINT experiment_source_snapshot_object CHECK (jsonb_typeof(recognition_snapshot_jsonb) = 'object'),
    CONSTRAINT experiment_source_job_owner_check CHECK (
        (recognition_job_id IS NOT NULL)::integer + (experiment_import_job_id IS NOT NULL)::integer = 1),
    CONSTRAINT experiment_source_groups_array CHECK (
        jsonb_typeof(source_group_keys_jsonb) = 'array' AND jsonb_array_length(source_group_keys_jsonb) > 0),
    CONSTRAINT experiment_source_file_hash_check CHECK (source_file_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT experiment_source_content_hash_check CHECK (content_hash ~ '^[0-9a-f]{64}$')
);
CREATE INDEX idx_experiment_source_reference_lookup
    ON rnd.experiment_source_reference(organization_id, experiment_id, created_at DESC, id);
CREATE UNIQUE INDEX uq_experiment_source_reference_free_upload
    ON rnd.experiment_source_reference(organization_id, experiment_version_id, experiment_import_job_id, sample_boundary_id)
    WHERE experiment_import_job_id IS NOT NULL;

CREATE TABLE ai.training_sample (
    id uuid PRIMARY KEY, organization_id uuid NOT NULL REFERENCES iam.organization(id), logical_sample_key varchar(320) NOT NULL,
    identity_version bigint NOT NULL DEFAULT 1, authority_source_type varchar(24), current_sample_revision_id uuid,
    status varchar(24) NOT NULL DEFAULT 'ACTIVE', takeover_experiment_id uuid, takeover_experiment_version_id uuid,
    revision bigint NOT NULL DEFAULT 0, created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, id), UNIQUE (organization_id, logical_sample_key),
    FOREIGN KEY (organization_id, takeover_experiment_id) REFERENCES rnd.experiment(organization_id, id),
    FOREIGN KEY (organization_id, takeover_experiment_version_id) REFERENCES rnd.experiment_version(organization_id, id),
    CONSTRAINT training_sample_authority_check CHECK (authority_source_type IS NULL OR authority_source_type IN ('DATA_CENTER','EXPERIMENT')),
    CONSTRAINT training_sample_status_check CHECK (status IN ('ACTIVE','TAKEN_OVER','SUSPENDED','INVALIDATED','RETIRED'))
);

CREATE TABLE ai.sample_source (
    id uuid PRIMARY KEY, organization_id uuid NOT NULL REFERENCES iam.organization(id), training_sample_id uuid NOT NULL,
    source_type varchar(24) NOT NULL, confirmed_submission_item_id uuid, experiment_version_id uuid,
    source_business_key varchar(320) NOT NULL, sample_boundary_id varchar(240) NOT NULL,
    logical_sample_key varchar(320) NOT NULL, source_group_keys_jsonb jsonb NOT NULL,
    physical_identity_hash char(64) NOT NULL, source_version varchar(160) NOT NULL,
    source_sequence bigint NOT NULL, content_hash char(64) NOT NULL, priority smallint NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'CURRENT', created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, id), UNIQUE (organization_id, training_sample_id, id),
    UNIQUE (organization_id, source_type, source_business_key, source_version),
    FOREIGN KEY (organization_id, training_sample_id) REFERENCES ai.training_sample(organization_id, id),
    FOREIGN KEY (organization_id, confirmed_submission_item_id) REFERENCES data.confirmed_submission_item(organization_id, id),
    FOREIGN KEY (organization_id, experiment_version_id) REFERENCES rnd.experiment_version(organization_id, id),
    CONSTRAINT sample_source_type_check CHECK (source_type IN ('DATA_CENTER','EXPERIMENT')),
    CONSTRAINT sample_source_reference_check CHECK (
        (source_type = 'DATA_CENTER' AND confirmed_submission_item_id IS NOT NULL AND experiment_version_id IS NULL) OR
        (source_type = 'EXPERIMENT' AND experiment_version_id IS NOT NULL AND confirmed_submission_item_id IS NULL)),
    CONSTRAINT sample_source_status_check CHECK (status IN ('CURRENT','SUPERSEDED','INVALIDATED','TAKEN_OVER')),
    CONSTRAINT sample_source_groups_array CHECK (
        jsonb_typeof(source_group_keys_jsonb) = 'array' AND jsonb_array_length(source_group_keys_jsonb) > 0),
    CONSTRAINT sample_source_physical_hash_check CHECK (physical_identity_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT sample_source_hash_check CHECK (content_hash ~ '^[0-9a-f]{64}$')
);
CREATE UNIQUE INDEX uq_sample_source_current ON ai.sample_source(organization_id, training_sample_id, source_type) WHERE status = 'CURRENT';

CREATE TABLE ai.sample_revision (
    id uuid PRIMARY KEY, organization_id uuid NOT NULL REFERENCES iam.organization(id), training_sample_id uuid NOT NULL,
    sample_source_id uuid NOT NULL, revision_no integer NOT NULL CHECK (revision_no > 0),
    composition_jsonb jsonb NOT NULL, process_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    conditions_jsonb jsonb NOT NULL, observations_jsonb jsonb NOT NULL,
    facts_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb, permission_scope_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    source_coordinates_jsonb jsonb NOT NULL, fact_hash char(64) NOT NULL, status varchar(24) NOT NULL DEFAULT 'CURRENT',
    created_at timestamptz NOT NULL DEFAULT now(), UNIQUE (organization_id, id),
    UNIQUE (organization_id, training_sample_id, revision_no),
    FOREIGN KEY (organization_id, training_sample_id) REFERENCES ai.training_sample(organization_id, id),
    FOREIGN KEY (organization_id, training_sample_id, sample_source_id)
        REFERENCES ai.sample_source(organization_id, training_sample_id, id),
    CONSTRAINT sample_revision_composition_object CHECK (jsonb_typeof(composition_jsonb) = 'object'),
    CONSTRAINT sample_revision_process_object CHECK (jsonb_typeof(process_jsonb) = 'object'),
    CONSTRAINT sample_revision_conditions_object CHECK (jsonb_typeof(conditions_jsonb) = 'object'),
    CONSTRAINT sample_revision_observations_object CHECK (jsonb_typeof(observations_jsonb) = 'object'),
    CONSTRAINT sample_revision_facts_object CHECK (jsonb_typeof(facts_jsonb) = 'object'),
    CONSTRAINT sample_revision_permission_scope_object CHECK (jsonb_typeof(permission_scope_jsonb) = 'object'),
    CONSTRAINT sample_revision_coordinates_object CHECK (jsonb_typeof(source_coordinates_jsonb) = 'object'),
    CONSTRAINT sample_revision_status_check CHECK (status IN ('CURRENT','SUPERSEDED','INVALIDATED')),
    CONSTRAINT sample_revision_hash_check CHECK (fact_hash ~ '^[0-9a-f]{64}$')
);
ALTER TABLE ai.training_sample ADD CONSTRAINT training_sample_current_revision_fk
    FOREIGN KEY (organization_id, current_sample_revision_id)
    REFERENCES ai.sample_revision(organization_id, id);

CREATE TABLE ai.fact_projection_receipt (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES iam.organization(id),
    source_type varchar(24) NOT NULL,
    source_event_id uuid NOT NULL,
    source_business_key varchar(320) NOT NULL,
    source_sequence bigint NOT NULL,
    event_type varchar(100) NOT NULL,
    payload_hash char(64) NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'PROCESSING',
    attempt_count integer NOT NULL DEFAULT 1,
    error_message text,
    processed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, source_type, source_event_id),
    CONSTRAINT fact_projection_source_check CHECK (source_type IN ('DATA_CENTER','EXPERIMENT')),
    CONSTRAINT fact_projection_status_check CHECK (status IN ('PROCESSING','COMPLETED','FAILED','STALE')),
    CONSTRAINT fact_projection_payload_hash_check CHECK (payload_hash ~ '^[0-9a-f]{64}$')
);
CREATE INDEX idx_fact_projection_recovery
    ON ai.fact_projection_receipt(organization_id, status, updated_at, id)
    WHERE status IN ('PROCESSING','FAILED');

CREATE TABLE ai.training_eligibility (
    id uuid PRIMARY KEY, organization_id uuid NOT NULL REFERENCES iam.organization(id), sample_revision_id uuid NOT NULL,
    target_version_id uuid NOT NULL, input_scheme_id uuid NOT NULL, state varchar(24) NOT NULL,
    reasons_jsonb jsonb NOT NULL DEFAULT '[]'::jsonb, warnings_jsonb jsonb NOT NULL DEFAULT '[]'::jsonb,
    evidence_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb, rule_fingerprint char(64) NOT NULL,
    revision bigint NOT NULL DEFAULT 0, evaluated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, id), UNIQUE (organization_id, sample_revision_id, target_version_id, input_scheme_id),
    FOREIGN KEY (organization_id, sample_revision_id) REFERENCES ai.sample_revision(organization_id, id),
    FOREIGN KEY (organization_id, target_version_id) REFERENCES ai.target_version(organization_id, id),
    FOREIGN KEY (organization_id, input_scheme_id) REFERENCES ai.input_scheme(organization_id, id),
    CONSTRAINT eligibility_state_check CHECK (state IN ('TRAINABLE','EXCLUDED','REVIEW_REQUIRED')),
    CONSTRAINT eligibility_reasons_array CHECK (jsonb_typeof(reasons_jsonb) = 'array'),
    CONSTRAINT eligibility_warnings_array CHECK (jsonb_typeof(warnings_jsonb) = 'array'),
    CONSTRAINT eligibility_evidence_object CHECK (jsonb_typeof(evidence_jsonb) = 'object'),
    CONSTRAINT eligibility_rule_hash_check CHECK (rule_fingerprint ~ '^[0-9a-f]{64}$')
);

CREATE TABLE ai.sample_identity_issue (
    id uuid PRIMARY KEY, organization_id uuid NOT NULL REFERENCES iam.organization(id), training_sample_id uuid,
    left_sample_source_id uuid NOT NULL, right_sample_source_id uuid NOT NULL, issue_type varchar(40) NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'OPEN', evidence_jsonb jsonb NOT NULL, resolution_jsonb jsonb,
    revision bigint NOT NULL DEFAULT 0, created_at timestamptz NOT NULL DEFAULT now(), resolved_at timestamptz,
    resolved_by uuid REFERENCES iam.app_user(id), UNIQUE (organization_id, id),
    FOREIGN KEY (organization_id, training_sample_id) REFERENCES ai.training_sample(organization_id, id),
    FOREIGN KEY (organization_id, left_sample_source_id) REFERENCES ai.sample_source(organization_id, id),
    FOREIGN KEY (organization_id, right_sample_source_id) REFERENCES ai.sample_source(organization_id, id),
    CONSTRAINT identity_issue_distinct_sources CHECK (left_sample_source_id <> right_sample_source_id),
    CONSTRAINT identity_issue_status_check CHECK (status IN ('OPEN','RESOLVED','DISMISSED')),
    CONSTRAINT identity_issue_evidence_object CHECK (jsonb_typeof(evidence_jsonb) = 'object'),
    CONSTRAINT identity_issue_resolution_object CHECK (resolution_jsonb IS NULL OR jsonb_typeof(resolution_jsonb) = 'object')
);
CREATE UNIQUE INDEX uq_sample_identity_issue_open ON ai.sample_identity_issue(
    organization_id, LEAST(left_sample_source_id, right_sample_source_id), GREATEST(left_sample_source_id, right_sample_source_id), issue_type
) WHERE status = 'OPEN';

CREATE TABLE ai.data_review (
    id uuid PRIMARY KEY, organization_id uuid NOT NULL REFERENCES iam.organization(id), review_type varchar(32) NOT NULL,
    eligibility_id uuid, identity_issue_id uuid, status varchar(24) NOT NULL DEFAULT 'OPEN', priority smallint NOT NULL DEFAULT 100,
    assigned_to uuid REFERENCES iam.app_user(id), revision bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, id),
    FOREIGN KEY (organization_id, eligibility_id) REFERENCES ai.training_eligibility(organization_id, id),
    FOREIGN KEY (organization_id, identity_issue_id) REFERENCES ai.sample_identity_issue(organization_id, id),
    CONSTRAINT data_review_subject_check CHECK ((eligibility_id IS NOT NULL)::integer + (identity_issue_id IS NOT NULL)::integer = 1),
    CONSTRAINT data_review_type_check CHECK (review_type IN ('ELIGIBILITY','IDENTITY','UNKNOWN_MATERIAL','OUTLIER')),
    CONSTRAINT data_review_status_check CHECK (status IN ('OPEN','IN_REVIEW','RESOLVED','CANCELLED'))
);

CREATE TABLE ai.data_review_decision (
    id uuid PRIMARY KEY, organization_id uuid NOT NULL REFERENCES iam.organization(id), data_review_id uuid NOT NULL,
    decision varchar(32) NOT NULL, reason text NOT NULL, evidence_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    decided_by uuid NOT NULL REFERENCES iam.app_user(id), decided_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, id), UNIQUE (organization_id, data_review_id),
    FOREIGN KEY (organization_id, data_review_id) REFERENCES ai.data_review(organization_id, id),
    CONSTRAINT data_review_decision_check CHECK (decision IN ('INCLUDE','EXCLUDE','MERGE','KEEP_SEPARATE','REMAP','DISMISS')),
    CONSTRAINT data_review_decision_evidence_object CHECK (jsonb_typeof(evidence_jsonb) = 'object')
);

CREATE TABLE rnd.experiment_ai_source_evidence (
    id uuid PRIMARY KEY, organization_id uuid NOT NULL REFERENCES iam.organization(id), experiment_id uuid NOT NULL,
    experiment_version_id uuid NOT NULL, legacy_research_run_id uuid, legacy_candidate_id uuid,
    request_hash char(64) NOT NULL, formula_jsonb jsonb NOT NULL, predictions_jsonb jsonb NOT NULL,
    model_bindings_jsonb jsonb NOT NULL, source_summary_jsonb jsonb NOT NULL, evidence_hash char(64) NOT NULL,
    captured_at timestamptz NOT NULL DEFAULT now(), captured_by uuid REFERENCES iam.app_user(id),
    UNIQUE (organization_id, id), UNIQUE (organization_id, experiment_version_id, evidence_hash),
    FOREIGN KEY (organization_id, experiment_id) REFERENCES rnd.experiment(organization_id, id),
    FOREIGN KEY (organization_id, experiment_version_id) REFERENCES rnd.experiment_version(organization_id, id),
    CONSTRAINT experiment_ai_request_hash_check CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT experiment_ai_evidence_hash_check CHECK (evidence_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT experiment_ai_formula_object CHECK (jsonb_typeof(formula_jsonb) = 'object'),
    CONSTRAINT experiment_ai_predictions_object CHECK (jsonb_typeof(predictions_jsonb) = 'object'),
    CONSTRAINT experiment_ai_bindings_object CHECK (jsonb_typeof(model_bindings_jsonb) = 'object'),
    CONSTRAINT experiment_ai_source_object CHECK (jsonb_typeof(source_summary_jsonb) = 'object')
);

CREATE INDEX idx_submission_item_source ON data.confirmed_submission_item(organization_id, source_record_id, created_at DESC, id);
CREATE INDEX idx_sample_revision_sample ON ai.sample_revision(organization_id, training_sample_id, revision_no DESC);
CREATE INDEX idx_eligibility_funnel ON ai.training_eligibility(organization_id, target_version_id, input_scheme_id, state, evaluated_at DESC, id);
CREATE INDEX idx_data_review_queue ON ai.data_review(organization_id, status, priority, created_at, id);
CREATE INDEX idx_experiment_ai_evidence_lookup ON rnd.experiment_ai_source_evidence(organization_id, experiment_id, captured_at DESC, id);

CREATE TRIGGER immutable_confirmed_submission_item BEFORE UPDATE OR DELETE ON data.confirmed_submission_item
    FOR EACH ROW EXECUTE FUNCTION ai.reject_immutable_change();
CREATE TRIGGER immutable_experiment_source_reference BEFORE UPDATE OR DELETE ON rnd.experiment_source_reference
    FOR EACH ROW EXECUTE FUNCTION ai.reject_immutable_change();
CREATE TRIGGER immutable_sample_revision BEFORE UPDATE OR DELETE ON ai.sample_revision
    FOR EACH ROW EXECUTE FUNCTION ai.reject_immutable_change();
CREATE TRIGGER immutable_experiment_ai_evidence BEFORE UPDATE OR DELETE ON rnd.experiment_ai_source_evidence
   FOR EACH ROW EXECUTE FUNCTION ai.reject_immutable_change();

-- R01/3: immutable snapshots, retryable training attempts and atomic release history.

CREATE TABLE ai.training_snapshot (
    id uuid PRIMARY KEY, organization_id uuid NOT NULL REFERENCES iam.organization(id), target_version_id uuid NOT NULL,
    input_scheme_id uuid NOT NULL, material_dictionary_version_id uuid NOT NULL, modeling_policy_version_id uuid NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'FROZEN', sample_count integer NOT NULL CHECK (sample_count >= 0),
    validation_groups_jsonb jsonb NOT NULL, manifest_jsonb jsonb NOT NULL, snapshot_hash char(64) NOT NULL,
    object_prefix varchar(500) NOT NULL, frozen_by uuid NOT NULL REFERENCES iam.app_user(id), frozen_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, id), UNIQUE (organization_id, snapshot_hash),
    UNIQUE (organization_id, object_prefix),
    FOREIGN KEY (organization_id, target_version_id) REFERENCES ai.target_version(organization_id, id),
    FOREIGN KEY (organization_id, input_scheme_id) REFERENCES ai.input_scheme(organization_id, id),
    FOREIGN KEY (organization_id, material_dictionary_version_id) REFERENCES ai.material_dictionary_version(organization_id, id),
    FOREIGN KEY (organization_id, modeling_policy_version_id) REFERENCES ai.modeling_policy_version(organization_id, id),
    CONSTRAINT training_snapshot_status_check CHECK (status IN ('FROZEN','RETIRED')),
    CONSTRAINT training_snapshot_groups_object CHECK (jsonb_typeof(validation_groups_jsonb) = 'object'),
    CONSTRAINT training_snapshot_manifest_object CHECK (jsonb_typeof(manifest_jsonb) = 'object'),
    CONSTRAINT training_snapshot_hash_check CHECK (snapshot_hash ~ '^[0-9a-f]{64}$')
);

CREATE TABLE ai.training_snapshot_item (
    organization_id uuid NOT NULL REFERENCES iam.organization(id), training_snapshot_id uuid NOT NULL,
    sample_revision_id uuid NOT NULL, eligibility_id uuid NOT NULL, ordinal bigint NOT NULL CHECK (ordinal >= 0),
    split_group varchar(160) NOT NULL, row_hash char(64) NOT NULL, row_jsonb jsonb NOT NULL,
    PRIMARY KEY (training_snapshot_id, sample_revision_id), UNIQUE (training_snapshot_id, ordinal),
    FOREIGN KEY (organization_id, training_snapshot_id) REFERENCES ai.training_snapshot(organization_id, id),
    FOREIGN KEY (organization_id, sample_revision_id) REFERENCES ai.sample_revision(organization_id, id),
    FOREIGN KEY (organization_id, eligibility_id) REFERENCES ai.training_eligibility(organization_id, id),
    CONSTRAINT training_snapshot_item_row_object CHECK (jsonb_typeof(row_jsonb) = 'object'),
    CONSTRAINT training_snapshot_item_hash_check CHECK (row_hash ~ '^[0-9a-f]{64}$')
);
CREATE INDEX idx_training_snapshot_history
    ON ai.training_snapshot(organization_id, frozen_at DESC, id);

CREATE TABLE ai.training_job (
    id uuid PRIMARY KEY, organization_id uuid NOT NULL REFERENCES iam.organization(id), training_snapshot_id uuid NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'QUEUED', idempotency_key varchar(200) NOT NULL, request_hash char(64) NOT NULL,
    priority smallint NOT NULL DEFAULT 100, max_attempts integer NOT NULL DEFAULT 3 CHECK (max_attempts > 0),
    attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count >= 0), lease_owner varchar(160), lease_expires_at timestamptz,
    next_attempt_at timestamptz NOT NULL DEFAULT now(), last_error_code varchar(100), last_error_message text,
    revision bigint NOT NULL DEFAULT 0, requested_by uuid NOT NULL REFERENCES iam.app_user(id),
    created_at timestamptz NOT NULL DEFAULT now(), started_at timestamptz, finished_at timestamptz, updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, id), UNIQUE (organization_id, idempotency_key),
    FOREIGN KEY (organization_id, training_snapshot_id) REFERENCES ai.training_snapshot(organization_id, id),
    CONSTRAINT training_job_status_check CHECK (status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED','CANCELLED')),
    CONSTRAINT training_job_hash_check CHECK (request_hash ~ '^[0-9a-f]{64}$')
);
CREATE INDEX idx_training_job_claim ON ai.training_job(priority, next_attempt_at, created_at, id) WHERE status IN ('QUEUED','RUNNING');
CREATE INDEX idx_training_job_history ON ai.training_job(organization_id, created_at DESC, id);

CREATE TABLE ai.training_job_attempt (
    id uuid PRIMARY KEY, organization_id uuid NOT NULL REFERENCES iam.organization(id), training_job_id uuid NOT NULL,
    attempt_no integer NOT NULL CHECK (attempt_no > 0), status varchar(24) NOT NULL,
    worker_id varchar(160), frozen_request_jsonb jsonb NOT NULL, request_hash char(64) NOT NULL,
    result_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb, error_code varchar(100), error_message text,
    started_at timestamptz NOT NULL DEFAULT now(), finished_at timestamptz,
    UNIQUE (organization_id, id), UNIQUE (organization_id, training_job_id, attempt_no),
    FOREIGN KEY (organization_id, training_job_id) REFERENCES ai.training_job(organization_id, id),
    CONSTRAINT training_attempt_status_check CHECK (status IN ('RUNNING','SUCCEEDED','FAILED','INTERRUPTED')),
    CONSTRAINT training_attempt_request_object CHECK (jsonb_typeof(frozen_request_jsonb) = 'object'),
    CONSTRAINT training_attempt_result_object CHECK (jsonb_typeof(result_jsonb) = 'object'),
    CONSTRAINT training_attempt_hash_check CHECK (request_hash ~ '^[0-9a-f]{64}$')
);

CREATE TABLE ai.artifact (
    id uuid PRIMARY KEY, organization_id uuid NOT NULL REFERENCES iam.organization(id), artifact_type varchar(40) NOT NULL,
    training_snapshot_id uuid, training_job_attempt_id uuid, object_key varchar(700) NOT NULL,
    sha256 char(64) NOT NULL, size_bytes bigint NOT NULL CHECK (size_bytes >= 0), metadata_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(), UNIQUE (organization_id, id), UNIQUE (organization_id, object_key),
    FOREIGN KEY (organization_id, training_snapshot_id) REFERENCES ai.training_snapshot(organization_id, id),
    FOREIGN KEY (organization_id, training_job_attempt_id) REFERENCES ai.training_job_attempt(organization_id, id),
    CONSTRAINT artifact_owner_check CHECK (training_snapshot_id IS NOT NULL OR training_job_attempt_id IS NOT NULL),
    CONSTRAINT artifact_metadata_object CHECK (jsonb_typeof(metadata_jsonb) = 'object'),
    CONSTRAINT artifact_hash_check CHECK (sha256 ~ '^[0-9a-f]{64}$')
);

CREATE TABLE ai.model_version (
    id uuid PRIMARY KEY, organization_id uuid NOT NULL REFERENCES iam.organization(id), target_id uuid NOT NULL,
    target_version_id uuid NOT NULL, input_scheme_id uuid NOT NULL, training_snapshot_id uuid NOT NULL,
    training_job_id uuid NOT NULL, model_artifact_id uuid NOT NULL, model_type varchar(80) NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'CANDIDATE', metrics_jsonb jsonb NOT NULL,
    applicability_domain_jsonb jsonb NOT NULL, model_card_jsonb jsonb NOT NULL,
    contract_version varchar(40) NOT NULL DEFAULT 'formula-model.v2', model_hash char(64) NOT NULL,
    revision bigint NOT NULL DEFAULT 0, created_by uuid NOT NULL REFERENCES iam.app_user(id),
    created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, id), UNIQUE (organization_id, target_id, id),
    UNIQUE (organization_id, model_hash),
    FOREIGN KEY (organization_id, target_id) REFERENCES ai.prediction_target(organization_id, id),
    FOREIGN KEY (organization_id, target_id, target_version_id)
        REFERENCES ai.target_version(organization_id, target_id, id),
    FOREIGN KEY (organization_id, target_id, input_scheme_id)
        REFERENCES ai.input_scheme(organization_id, target_id, id),
    FOREIGN KEY (organization_id, training_snapshot_id) REFERENCES ai.training_snapshot(organization_id, id),
    FOREIGN KEY (organization_id, training_job_id) REFERENCES ai.training_job(organization_id, id),
    FOREIGN KEY (organization_id, model_artifact_id) REFERENCES ai.artifact(organization_id, id),
    CONSTRAINT model_version_status_check CHECK (status IN ('CANDIDATE','ACTIVE','PAUSED','RETIRED','FAILED')),
    CONSTRAINT model_metrics_object CHECK (jsonb_typeof(metrics_jsonb) = 'object'),
    CONSTRAINT model_domain_object CHECK (jsonb_typeof(applicability_domain_jsonb) = 'object'),
    CONSTRAINT model_card_object CHECK (jsonb_typeof(model_card_jsonb) = 'object'),
    CONSTRAINT model_hash_check CHECK (model_hash ~ '^[0-9a-f]{64}$')
);
CREATE UNIQUE INDEX uq_model_version_active_target ON ai.model_version(organization_id, target_id) WHERE status = 'ACTIVE';
CREATE INDEX idx_model_version_target_history ON ai.model_version(organization_id, target_id, created_at DESC, id);

CREATE TABLE ai.model_release (
    id uuid PRIMARY KEY, organization_id uuid NOT NULL REFERENCES iam.organization(id), target_id uuid NOT NULL,
    model_version_id uuid NOT NULL, previous_model_version_id uuid, action varchar(24) NOT NULL,
    expected_previous_revision bigint, reason text NOT NULL, actor_id uuid NOT NULL REFERENCES iam.app_user(id),
    request_id varchar(160) NOT NULL, idempotency_key varchar(200) NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, id), UNIQUE (organization_id, idempotency_key),
    FOREIGN KEY (organization_id, target_id) REFERENCES ai.prediction_target(organization_id, id),
    FOREIGN KEY (organization_id, target_id, model_version_id)
        REFERENCES ai.model_version(organization_id, target_id, id),
    FOREIGN KEY (organization_id, target_id, previous_model_version_id)
        REFERENCES ai.model_version(organization_id, target_id, id),
    CONSTRAINT model_release_action_check CHECK (action IN ('ACTIVATE','PAUSE','ROLLBACK','REPLACE'))
);
CREATE INDEX idx_model_release_history ON ai.model_release(organization_id, target_id, created_at DESC, id);

CREATE TRIGGER immutable_training_snapshot BEFORE UPDATE OR DELETE ON ai.training_snapshot
    FOR EACH ROW EXECUTE FUNCTION ai.reject_immutable_change();
CREATE TRIGGER immutable_training_snapshot_item BEFORE UPDATE OR DELETE ON ai.training_snapshot_item
    FOR EACH ROW EXECUTE FUNCTION ai.reject_immutable_change();
CREATE TRIGGER immutable_artifact BEFORE UPDATE OR DELETE ON ai.artifact
    FOR EACH ROW EXECUTE FUNCTION ai.reject_immutable_change();
CREATE TRIGGER immutable_model_release BEFORE UPDATE OR DELETE ON ai.model_release
    FOR EACH ROW EXECUTE FUNCTION ai.reject_immutable_change();

CREATE OR REPLACE FUNCTION ai.protect_model_version_payload() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status IN ('ACTIVE','PAUSED','RETIRED') AND (
        NEW.target_id IS DISTINCT FROM OLD.target_id OR NEW.target_version_id IS DISTINCT FROM OLD.target_version_id OR
        NEW.input_scheme_id IS DISTINCT FROM OLD.input_scheme_id OR NEW.training_snapshot_id IS DISTINCT FROM OLD.training_snapshot_id OR
        NEW.training_job_id IS DISTINCT FROM OLD.training_job_id OR NEW.model_artifact_id IS DISTINCT FROM OLD.model_artifact_id OR
        NEW.metrics_jsonb IS DISTINCT FROM OLD.metrics_jsonb OR NEW.applicability_domain_jsonb IS DISTINCT FROM OLD.applicability_domain_jsonb OR
        NEW.model_card_jsonb IS DISTINCT FROM OLD.model_card_jsonb OR NEW.model_hash IS DISTINCT FROM OLD.model_hash
    ) THEN
        RAISE EXCEPTION USING ERRCODE = '55000', MESSAGE = 'published model payload is immutable';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER protect_model_version_payload BEFORE UPDATE ON ai.model_version
   FOR EACH ROW EXECUTE FUNCTION ai.protect_model_version_payload();
CREATE TRIGGER immutable_released_model_version BEFORE DELETE ON ai.model_version
   FOR EACH ROW WHEN (OLD.status IN ('ACTIVE','PAUSED','RETIRED'))
   EXECUTE FUNCTION ai.reject_immutable_change();

-- R01/4: model-gated prediction, recommendation, experiment links and feedback records.

CREATE TABLE ai.prediction_record (
    id uuid PRIMARY KEY, organization_id uuid NOT NULL REFERENCES iam.organization(id), prediction_type varchar(32) NOT NULL,
    status varchar(24) NOT NULL, idempotency_key varchar(200) NOT NULL, request_hash char(64) NOT NULL,
    request_jsonb jsonb NOT NULL, target_bindings_jsonb jsonb NOT NULL, result_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    error_jsonb jsonb, created_by uuid NOT NULL REFERENCES iam.app_user(id),
    created_at timestamptz NOT NULL DEFAULT now(), completed_at timestamptz,
    UNIQUE (organization_id, id), UNIQUE (organization_id, idempotency_key),
    CONSTRAINT prediction_record_type_check CHECK (prediction_type IN ('PERFORMANCE','FORMULA','OPTIMIZATION')),
    CONSTRAINT prediction_record_status_check CHECK (status IN ('RUNNING','SUCCEEDED','PARTIAL','BLOCKED','FAILED')),
    CONSTRAINT prediction_record_request_object CHECK (jsonb_typeof(request_jsonb) = 'object'),
    CONSTRAINT prediction_record_bindings_object CHECK (jsonb_typeof(target_bindings_jsonb) = 'object'),
    CONSTRAINT prediction_record_result_object CHECK (jsonb_typeof(result_jsonb) = 'object'),
    CONSTRAINT prediction_record_error_object CHECK (error_jsonb IS NULL OR jsonb_typeof(error_jsonb) = 'object'),
    CONSTRAINT prediction_record_hash_check CHECK (request_hash ~ '^[0-9a-f]{64}$')
);
CREATE INDEX idx_prediction_record_history ON ai.prediction_record(organization_id, created_at DESC, id);

CREATE TABLE ai.research_run_v2 (
    id uuid PRIMARY KEY, organization_id uuid NOT NULL REFERENCES iam.organization(id), run_type varchar(32) NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'QUEUED', idempotency_key varchar(200) NOT NULL, request_hash char(64) NOT NULL,
    request_jsonb jsonb NOT NULL, model_bindings_jsonb jsonb NOT NULL, result_summary_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    error_jsonb jsonb, revision bigint NOT NULL DEFAULT 0, created_by uuid NOT NULL REFERENCES iam.app_user(id),
    created_at timestamptz NOT NULL DEFAULT now(), started_at timestamptz, finished_at timestamptz, updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, id), UNIQUE (organization_id, idempotency_key),
    CONSTRAINT research_run_v2_type_check CHECK (run_type IN ('FORMULA_PREDICTION','EXPERIMENT_OPTIMIZATION')),
    CONSTRAINT research_run_v2_status_check CHECK (status IN ('QUEUED','RUNNING','SUCCEEDED','PARTIAL','BLOCKED','FAILED','CANCELLED')),
    CONSTRAINT research_run_v2_request_object CHECK (jsonb_typeof(request_jsonb) = 'object'),
    CONSTRAINT research_run_v2_bindings_object CHECK (jsonb_typeof(model_bindings_jsonb) = 'object'),
    CONSTRAINT research_run_v2_result_object CHECK (jsonb_typeof(result_summary_jsonb) = 'object'),
    CONSTRAINT research_run_v2_error_object CHECK (error_jsonb IS NULL OR jsonb_typeof(error_jsonb) = 'object'),
    CONSTRAINT research_run_v2_hash_check CHECK (request_hash ~ '^[0-9a-f]{64}$')
);
CREATE INDEX idx_research_run_v2_history ON ai.research_run_v2(organization_id, created_at DESC, id);

CREATE TABLE ai.research_candidate_v2 (
    id uuid PRIMARY KEY, organization_id uuid NOT NULL REFERENCES iam.organization(id), research_run_id uuid NOT NULL,
    candidate_no integer NOT NULL CHECK (candidate_no > 0), title varchar(240) NOT NULL,
    formula_jsonb jsonb NOT NULL, process_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    target_results_jsonb jsonb NOT NULL, rule_check_jsonb jsonb NOT NULL, applicability_jsonb jsonb NOT NULL,
    evidence_jsonb jsonb NOT NULL, score numeric(24,12), content_hash char(64) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(), UNIQUE (organization_id, id),
    UNIQUE (organization_id, research_run_id, id),
    UNIQUE (organization_id, research_run_id, candidate_no), UNIQUE (organization_id, research_run_id, content_hash),
    FOREIGN KEY (organization_id, research_run_id) REFERENCES ai.research_run_v2(organization_id, id),
    CONSTRAINT research_candidate_formula_object CHECK (jsonb_typeof(formula_jsonb) = 'object'),
    CONSTRAINT research_candidate_process_object CHECK (jsonb_typeof(process_jsonb) = 'object'),
    CONSTRAINT research_candidate_results_object CHECK (jsonb_typeof(target_results_jsonb) = 'object'),
    CONSTRAINT research_candidate_rule_object CHECK (jsonb_typeof(rule_check_jsonb) = 'object'),
    CONSTRAINT research_candidate_domain_object CHECK (jsonb_typeof(applicability_jsonb) = 'object'),
    CONSTRAINT research_candidate_evidence_object CHECK (jsonb_typeof(evidence_jsonb) = 'object'),
    CONSTRAINT research_candidate_hash_check CHECK (content_hash ~ '^[0-9a-f]{64}$')
);

CREATE TABLE ai.research_experiment_link_v2 (
    id uuid PRIMARY KEY, organization_id uuid NOT NULL REFERENCES iam.organization(id), research_run_id uuid NOT NULL,
    research_candidate_id uuid NOT NULL, experiment_id uuid NOT NULL, experiment_version_id uuid NOT NULL,
    idempotency_key varchar(200) NOT NULL, created_by uuid NOT NULL REFERENCES iam.app_user(id), created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, id), UNIQUE (organization_id, idempotency_key), UNIQUE (organization_id, research_candidate_id),
    FOREIGN KEY (organization_id, research_run_id) REFERENCES ai.research_run_v2(organization_id, id),
    FOREIGN KEY (organization_id, research_run_id, research_candidate_id)
        REFERENCES ai.research_candidate_v2(organization_id, research_run_id, id),
    FOREIGN KEY (organization_id, experiment_id) REFERENCES rnd.experiment(organization_id, id),
    FOREIGN KEY (organization_id, experiment_version_id) REFERENCES rnd.experiment_version(organization_id, id)
);
CREATE INDEX idx_research_experiment_link_v2 ON ai.research_experiment_link_v2(organization_id, experiment_id, created_at DESC);

CREATE TABLE ai.model_feedback (
    id uuid PRIMARY KEY, organization_id uuid NOT NULL REFERENCES iam.organization(id), model_version_id uuid NOT NULL,
    target_id uuid NOT NULL, experiment_id uuid NOT NULL, experiment_version_id uuid NOT NULL,
    prediction_record_id uuid, research_candidate_id uuid, predicted_result_jsonb jsonb NOT NULL,
    observed_result_jsonb jsonb NOT NULL, residual_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    status varchar(24) NOT NULL DEFAULT 'RECORDED', content_hash char(64) NOT NULL,
    created_by uuid NOT NULL REFERENCES iam.app_user(id), created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, id), UNIQUE (organization_id, target_id, experiment_version_id, content_hash),
    FOREIGN KEY (organization_id, target_id, model_version_id)
        REFERENCES ai.model_version(organization_id, target_id, id),
    FOREIGN KEY (organization_id, target_id) REFERENCES ai.prediction_target(organization_id, id),
    FOREIGN KEY (organization_id, experiment_id) REFERENCES rnd.experiment(organization_id, id),
    FOREIGN KEY (organization_id, experiment_version_id) REFERENCES rnd.experiment_version(organization_id, id),
    FOREIGN KEY (organization_id, prediction_record_id) REFERENCES ai.prediction_record(organization_id, id),
    FOREIGN KEY (organization_id, research_candidate_id) REFERENCES ai.research_candidate_v2(organization_id, id),
    CONSTRAINT model_feedback_prediction_object CHECK (jsonb_typeof(predicted_result_jsonb) = 'object'),
    CONSTRAINT model_feedback_observed_object CHECK (jsonb_typeof(observed_result_jsonb) = 'object'),
    CONSTRAINT model_feedback_residual_object CHECK (jsonb_typeof(residual_jsonb) = 'object'),
    CONSTRAINT model_feedback_status_check CHECK (status IN ('RECORDED','QUALIFIED','EXCLUDED')),
    CONSTRAINT model_feedback_hash_check CHECK (content_hash ~ '^[0-9a-f]{64}$')
);
CREATE INDEX idx_model_feedback_target ON ai.model_feedback(organization_id, target_id, created_at DESC, id);

CREATE TRIGGER immutable_research_candidate_v2 BEFORE UPDATE OR DELETE ON ai.research_candidate_v2
    FOR EACH ROW EXECUTE FUNCTION ai.reject_immutable_change();
CREATE TRIGGER immutable_research_experiment_link_v2 BEFORE UPDATE OR DELETE ON ai.research_experiment_link_v2
    FOR EACH ROW EXECUTE FUNCTION ai.reject_immutable_change();
CREATE TRIGGER immutable_model_feedback BEFORE UPDATE OR DELETE ON ai.model_feedback
    FOR EACH ROW EXECUTE FUNCTION ai.reject_immutable_change();

CREATE OR REPLACE FUNCTION ai.protect_completed_request() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status IN ('SUCCEEDED','PARTIAL','BLOCKED','FAILED','CANCELLED') THEN
        RAISE EXCEPTION USING ERRCODE = '55000', MESSAGE = TG_TABLE_SCHEMA || '.' || TG_TABLE_NAME || ' completed record is immutable';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER protect_prediction_record BEFORE UPDATE OR DELETE ON ai.prediction_record
    FOR EACH ROW EXECUTE FUNCTION ai.protect_completed_request();
CREATE TRIGGER protect_research_run_v2 BEFORE UPDATE OR DELETE ON ai.research_run_v2
    FOR EACH ROW EXECUTE FUNCTION ai.protect_completed_request();
