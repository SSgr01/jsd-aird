ALTER TABLE ai.research_run_v2
    ADD COLUMN parent_run_id uuid,
    ADD CONSTRAINT research_run_parent_fk FOREIGN KEY (organization_id,parent_run_id)
        REFERENCES ai.research_run_v2(organization_id,id);

ALTER TABLE ai.research_experiment_link_v2 ADD COLUMN sample_key varchar(240);
CREATE INDEX idx_research_loop_parent ON ai.research_run_v2(organization_id,parent_run_id);
ALTER TABLE ai.research_run_v2 ADD CONSTRAINT research_run_not_own_parent CHECK(parent_run_id IS DISTINCT FROM id);
CREATE FUNCTION ai.guard_research_parent() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.parent_run_id IS DISTINCT FROM OLD.parent_run_id AND
        (OLD.parent_run_id IS NOT NULL OR OLD.execution_status <> 'QUEUED') THEN
        RAISE EXCEPTION 'Research parent is immutable';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER immutable_research_parent BEFORE UPDATE ON ai.research_run_v2
    FOR EACH ROW EXECUTE FUNCTION ai.guard_research_parent();

-- Run metadata is independent of the assistant conversation and survives a new session.
CREATE TABLE ai.research_activity_context (
    organization_id uuid NOT NULL REFERENCES iam.organization(id),
    resource_kind varchar(40) NOT NULL,
    resource_id uuid NOT NULL,
    project_id uuid REFERENCES mdm.project(id),
    source_refs_jsonb jsonb NOT NULL DEFAULT '[]'::jsonb CHECK(jsonb_typeof(source_refs_jsonb)='array'),
    created_by uuid NOT NULL REFERENCES iam.app_user(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(organization_id,resource_kind,resource_id)
);
CREATE INDEX idx_research_activity_project ON ai.research_activity_context(organization_id,project_id,created_at);
CREATE TRIGGER immutable_research_activity BEFORE UPDATE OR DELETE ON ai.research_activity_context
    FOR EACH ROW EXECUTE FUNCTION ai.reject_immutable_change();

-- An observation is a captured completed version, never an editable scalar over a proposal.
CREATE TABLE ai.research_observation_revision (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES iam.organization(id),
    research_run_id uuid NOT NULL,
    research_candidate_id uuid NOT NULL,
    experiment_id uuid NOT NULL,
    experiment_version_id uuid NOT NULL,
    sample_key varchar(240) NOT NULL,
    field_code varchar(240) NOT NULL,
    actual_inputs_jsonb jsonb NOT NULL CHECK(jsonb_typeof(actual_inputs_jsonb)='object'),
    observed_value_jsonb jsonb,
    predicted_value_jsonb jsonb,
    residual_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    source_refs_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    comparison_status varchar(24) NOT NULL CHECK(comparison_status IN('COMPARABLE','EXCLUDED')),
    reason_code varchar(100),
    content_hash char(64) NOT NULL,
    captured_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(organization_id,research_run_id,research_candidate_id,experiment_version_id,sample_key,field_code),
    FOREIGN KEY(organization_id,research_run_id,research_candidate_id)
        REFERENCES ai.research_candidate_v2(organization_id,research_run_id,id),
    FOREIGN KEY(organization_id,experiment_id) REFERENCES rnd.experiment(organization_id,id),
    FOREIGN KEY(organization_id,experiment_version_id) REFERENCES rnd.experiment_version(organization_id,id)
);
CREATE INDEX idx_research_observation_run ON ai.research_observation_revision(organization_id,research_run_id);
CREATE TRIGGER immutable_research_observation BEFORE UPDATE OR DELETE ON ai.research_observation_revision
    FOR EACH ROW EXECUTE FUNCTION ai.reject_immutable_change();

-- Chemical dossiers extend the material identities already used by formula/model mappings.
CREATE TABLE mdm.material_source_document (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES iam.organization(id),
    document_code varchar(120) NOT NULL,
    version_no integer NOT NULL CHECK(version_no>0),
    title varchar(300) NOT NULL,
    file_name varchar(300) NOT NULL,
    source_kind varchar(40) NOT NULL CHECK(source_kind IN('SUPPLIER_DOCUMENT','INTERNAL_REFERENCE','GENERATED_REFERENCE')),
    content_text text NOT NULL,
    content_hash char(64) NOT NULL,
    file_id uuid,
    created_by uuid NOT NULL REFERENCES iam.app_user(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(organization_id,id),
    UNIQUE(organization_id,document_code,version_no)
);
CREATE TABLE mdm.material_profile_version (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES iam.organization(id),
    material_id uuid NOT NULL REFERENCES mdm.material(id),
    version_no integer NOT NULL CHECK(version_no>0),
    facts_jsonb jsonb NOT NULL CHECK(jsonb_typeof(facts_jsonb)='object'),
    content_hash char(64) NOT NULL,
    idempotency_key varchar(200) NOT NULL,
    created_by uuid NOT NULL REFERENCES iam.app_user(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(organization_id,id),
    UNIQUE(organization_id,material_id,id),
    UNIQUE(organization_id,material_id,version_no),
    UNIQUE(organization_id,created_by,idempotency_key)
);
CREATE TABLE mdm.material_profile (
    organization_id uuid NOT NULL REFERENCES iam.organization(id),
    material_id uuid NOT NULL REFERENCES mdm.material(id),
    current_version_id uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(organization_id,material_id),
    FOREIGN KEY(organization_id,material_id,current_version_id)
        REFERENCES mdm.material_profile_version(organization_id,material_id,id)
);
CREATE INDEX idx_material_profile_facts ON mdm.material_profile_version USING gin(facts_jsonb);
CREATE TRIGGER immutable_material_source BEFORE UPDATE OR DELETE ON mdm.material_source_document
    FOR EACH ROW EXECUTE FUNCTION ai.reject_immutable_change();
CREATE TRIGGER immutable_material_profile_version BEFORE UPDATE OR DELETE ON mdm.material_profile_version
    FOR EACH ROW EXECUTE FUNCTION ai.reject_immutable_change();
