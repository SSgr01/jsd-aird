-- R01 V57 constraint rehearsal. Synthetic rows are rolled back at the end.
-- Expected failures are caught and treated as assertions.

BEGIN;

-- Keep this rehearsal self-contained. All fixture rows are rolled back with the
-- assertions below, so it is safe to run against an isolated migrated clone.
INSERT INTO iam.organization (id, name)
VALUES
    ('00000000-0000-0000-0000-000000000001', 'R01 rehearsal organization'),
    ('00000000-0000-0000-0000-000000000002', 'R01 foreign organization')
ON CONFLICT (id) DO NOTHING;

INSERT INTO iam.app_user (id, organization_id, username, display_name)
VALUES
    ('00000000-0000-0000-0000-000000000101', '00000000-0000-0000-0000-000000000001', 'r01-constraint-user', 'R01 constraint user'),
    ('00000000-0000-0000-0000-000000000102', '00000000-0000-0000-0000-000000000002', 'r01-constraint-foreign-user', 'R01 constraint foreign user')
ON CONFLICT (id) DO NOTHING;

INSERT INTO ai.prediction_target (
    id, organization_id, target_code, name, performance_project, value_type,
    status, created_by, updated_by
) VALUES (
    '10000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000001',
    'R01_GLOSS_60', 'R01 60 degree gloss', 'OPTICAL', 'CONTINUOUS',
    'ACTIVE',
    '00000000-0000-0000-0000-000000000101',
    '00000000-0000-0000-0000-000000000101'
);

INSERT INTO ai.target_version (
    id, organization_id, target_id, version_no, status, value_type, unit,
    definition_jsonb, config_hash, published_at, created_by
) VALUES (
    '10000000-0000-0000-0000-000000000002',
    '00000000-0000-0000-0000-000000000001',
    '10000000-0000-0000-0000-000000000001',
    1, 'PUBLISHED', 'CONTINUOUS', 'GU', '{"valueType":"CONTINUOUS"}'::jsonb,
    repeat('a', 64), now(),
    '00000000-0000-0000-0000-000000000101'
);

UPDATE ai.prediction_target
SET current_version_id = '10000000-0000-0000-0000-000000000002'
WHERE id = '10000000-0000-0000-0000-000000000001';

DO $$
BEGIN
    BEGIN
        INSERT INTO ai.target_version (
            id, organization_id, target_id, version_no, status, value_type,
            definition_jsonb, config_hash, created_by
        ) VALUES (
            '10000000-0000-0000-0000-000000000003',
            '00000000-0000-0000-0000-000000000002',
            '10000000-0000-0000-0000-000000000001',
            2, 'DRAFT', 'CONTINUOUS', '{}'::jsonb, repeat('b', 64),
            '00000000-0000-0000-0000-000000000102'
        );
        RAISE EXCEPTION 'R01 assertion failed: cross-organization FK was accepted';
    EXCEPTION WHEN foreign_key_violation THEN
        NULL;
    END;
END $$;

DO $$
BEGIN
    BEGIN
        UPDATE ai.target_version
        SET definition_jsonb = '{"mutated":true}'::jsonb
        WHERE id = '10000000-0000-0000-0000-000000000002';
        RAISE EXCEPTION 'R01 assertion failed: published target version was mutable';
    EXCEPTION WHEN object_not_in_prerequisite_state THEN
        NULL;
    END;
END $$;

INSERT INTO ai.input_field (
    id, organization_id, field_code, name, value_type, availability_stage,
    status, created_by, updated_by
) VALUES (
    '10000000-0000-0000-0000-000000000011',
    '00000000-0000-0000-0000-000000000001',
    'R01_SOLID_PERCENT', 'R01 solid percent', 'NUMBER', 'PRE_EXPERIMENT',
    'ACTIVE',
    '00000000-0000-0000-0000-000000000101',
    '00000000-0000-0000-0000-000000000101'
);

INSERT INTO ai.input_field_version (
    id, organization_id, input_field_id, version_no, status, value_type, availability_stage,
    definition_jsonb, preprocessing_jsonb, config_hash, published_at, created_by
) VALUES (
    '10000000-0000-0000-0000-000000000012',
    '00000000-0000-0000-0000-000000000001',
    '10000000-0000-0000-0000-000000000011',
    1, 'PUBLISHED', 'NUMBER', 'PRE_EXPERIMENT', '{"unit":"percent"}'::jsonb, '{"normalize":false}'::jsonb,
    repeat('c', 64), now(),
    '00000000-0000-0000-0000-000000000101'
);

UPDATE ai.input_field
SET current_version_id = '10000000-0000-0000-0000-000000000012'
WHERE id = '10000000-0000-0000-0000-000000000011';

INSERT INTO ai.input_scheme (
    id, organization_id, target_id, target_version_id, scheme_code, name, version_no,
    status, preprocessing_jsonb, config_hash, created_by, updated_by
) VALUES (
    '10000000-0000-0000-0000-000000000013',
    '00000000-0000-0000-0000-000000000001',
    '10000000-0000-0000-0000-000000000001',
    '10000000-0000-0000-0000-000000000002',
    'R01_GLOSS_INPUT', 'R01 gloss input', 1, 'DRAFT',
    '{"compositionTotal":"PRESERVE"}'::jsonb, repeat('d', 64),
    '00000000-0000-0000-0000-000000000101',
    '00000000-0000-0000-0000-000000000101'
);

INSERT INTO ai.input_scheme_field (
    organization_id, input_scheme_id, input_field_version_id, required, ordinal
) VALUES (
    '00000000-0000-0000-0000-000000000001',
    '10000000-0000-0000-0000-000000000013',
    '10000000-0000-0000-0000-000000000012', true, 0
);

UPDATE ai.input_scheme
SET status = 'FROZEN', frozen_at = now()
WHERE id = '10000000-0000-0000-0000-000000000013';

DO $$
BEGIN
    BEGIN
        UPDATE ai.input_scheme_field
        SET required = false
        WHERE input_scheme_id = '10000000-0000-0000-0000-000000000013';
        RAISE EXCEPTION 'R01 assertion failed: frozen input scheme field was mutable';
    EXCEPTION WHEN object_not_in_prerequisite_state THEN
        NULL;
    END;
END $$;

INSERT INTO ai.material_dictionary_version (
    id, organization_id, dictionary_code, version_no, status,
    vocabulary_jsonb, encoder_jsonb, dictionary_hash, frozen_at, created_by
) VALUES (
    '10000000-0000-0000-0000-000000000021',
    '00000000-0000-0000-0000-000000000001',
    'R01_MATERIALS', 1, 'FROZEN',
    '[{"code":"R01-MAT-001"}]'::jsonb, '{"type":"dictionary"}'::jsonb,
    repeat('e', 64), now(),
    '00000000-0000-0000-0000-000000000101'
);

DO $$
BEGIN
    BEGIN
        UPDATE ai.material_dictionary_version
        SET encoder_jsonb = '{"type":"mutated"}'::jsonb
        WHERE id = '10000000-0000-0000-0000-000000000021';
        RAISE EXCEPTION 'R02 assertion failed: frozen material dictionary content was mutable';
    EXCEPTION WHEN object_not_in_prerequisite_state THEN
        NULL;
    END;
END $$;

INSERT INTO ai.modeling_policy_version (
    id, organization_id, target_id, version_no, status,
    qualification_jsonb, validation_jsonb, training_jsonb,
    policy_hash, published_at, created_by
) VALUES (
    '10000000-0000-0000-0000-000000000022',
    '00000000-0000-0000-0000-000000000001',
    '10000000-0000-0000-0000-000000000001',
    1, 'PUBLISHED', '{"minimumSamples":1}'::jsonb,
    '{"groupBy":"sampleIdentity"}'::jsonb, '{"algorithm":"R01"}'::jsonb,
    repeat('f', 64), now(),
    '00000000-0000-0000-0000-000000000101'
);

INSERT INTO ai.training_snapshot (
    id, organization_id, target_version_id, input_scheme_id,
    material_dictionary_version_id, modeling_policy_version_id,
    sample_count, validation_groups_jsonb, manifest_jsonb,
    snapshot_hash, object_prefix, frozen_by
) VALUES (
    '10000000-0000-0000-0000-000000000031',
    '00000000-0000-0000-0000-000000000001',
    '10000000-0000-0000-0000-000000000002',
    '10000000-0000-0000-0000-000000000013',
    '10000000-0000-0000-0000-000000000021',
    '10000000-0000-0000-0000-000000000022',
    1, '{"R01-SAMPLE-001":"fold-a"}'::jsonb, '{"synthetic":true}'::jsonb,
    repeat('0', 64), 'r01/snapshots/001',
    '00000000-0000-0000-0000-000000000101'
);

DO $$
BEGIN
    BEGIN
        UPDATE ai.training_snapshot
        SET sample_count = 2
        WHERE id = '10000000-0000-0000-0000-000000000031';
        RAISE EXCEPTION 'R01 assertion failed: snapshot was mutable';
    EXCEPTION WHEN object_not_in_prerequisite_state THEN
        NULL;
    END;
END $$;

INSERT INTO ai.training_job (
    id, organization_id, training_snapshot_id, status,
    idempotency_key, request_hash, requested_by
) VALUES (
    '10000000-0000-0000-0000-000000000032',
    '00000000-0000-0000-0000-000000000001',
    '10000000-0000-0000-0000-000000000031',
    'SUCCEEDED', 'r01-constraint-job', repeat('1', 64),
    '00000000-0000-0000-0000-000000000101'
);

INSERT INTO ai.artifact (
    id, organization_id, artifact_type, training_snapshot_id,
    object_key, sha256, size_bytes, metadata_jsonb
) VALUES (
    '10000000-0000-0000-0000-000000000033',
    '00000000-0000-0000-0000-000000000001',
    'MODEL_BUNDLE', '10000000-0000-0000-0000-000000000031',
    'r01/models/001.bundle', repeat('2', 64), 128,
    '{"synthetic":true}'::jsonb
);

INSERT INTO ai.model_version (
    id, organization_id, target_id, target_version_id, input_scheme_id,
    training_snapshot_id, training_job_id, model_artifact_id, model_type,
    status, metrics_jsonb, applicability_domain_jsonb, model_card_jsonb,
    model_hash, created_by
) VALUES (
    '10000000-0000-0000-0000-000000000041',
    '00000000-0000-0000-0000-000000000001',
    '10000000-0000-0000-0000-000000000001',
    '10000000-0000-0000-0000-000000000002',
    '10000000-0000-0000-0000-000000000013',
    '10000000-0000-0000-0000-000000000031',
    '10000000-0000-0000-0000-000000000032',
    '10000000-0000-0000-0000-000000000033',
    'R01_REGRESSOR', 'ACTIVE', '{"mae":0.1}'::jsonb,
    '{"solidPercent":{"min":0,"max":100}}'::jsonb,
    '{"synthetic":true}'::jsonb, repeat('3', 64),
    '00000000-0000-0000-0000-000000000101'
);

DO $$
BEGIN
    BEGIN
        INSERT INTO ai.model_version (
            id, organization_id, target_id, target_version_id, input_scheme_id,
            training_snapshot_id, training_job_id, model_artifact_id, model_type,
            status, metrics_jsonb, applicability_domain_jsonb, model_card_jsonb,
            model_hash, created_by
        ) VALUES (
            '10000000-0000-0000-0000-000000000042',
            '00000000-0000-0000-0000-000000000001',
            '10000000-0000-0000-0000-000000000001',
            '10000000-0000-0000-0000-000000000002',
            '10000000-0000-0000-0000-000000000013',
            '10000000-0000-0000-0000-000000000031',
            '10000000-0000-0000-0000-000000000032',
            '10000000-0000-0000-0000-000000000033',
            'R01_REGRESSOR', 'ACTIVE', '{"mae":0.2}'::jsonb,
            '{"solidPercent":{"min":0,"max":100}}'::jsonb,
            '{"synthetic":true}'::jsonb, repeat('4', 64),
            '00000000-0000-0000-0000-000000000101'
        );
        RAISE EXCEPTION 'R01 assertion failed: a second ACTIVE model was accepted';
    EXCEPTION WHEN unique_violation THEN
        NULL;
    END;
END $$;

DO $$
BEGIN
    BEGIN
        UPDATE ai.model_version
        SET metrics_jsonb = '{"mae":99}'::jsonb
        WHERE id = '10000000-0000-0000-0000-000000000041';
        RAISE EXCEPTION 'R01 assertion failed: ACTIVE model payload was mutable';
    EXCEPTION WHEN object_not_in_prerequisite_state THEN
        NULL;
    END;
END $$;

ROLLBACK;

SELECT jsonb_build_object(
    'crossOrganizationForeignKeyRejected', true,
    'publishedTargetImmutable', true,
    'frozenInputSchemeImmutable', true,
    'frozenMaterialDictionaryImmutable', true,
    'snapshotImmutable', true,
    'singleActiveModelEnforced', true,
    'activeModelPayloadImmutable', true,
    'syntheticRowsRolledBack', true
);
