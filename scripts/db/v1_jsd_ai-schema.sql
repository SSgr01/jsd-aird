--
-- PostgreSQL database dump
--

\restrict WYQxcCbPBwL2bLyMPZCzWRKefuk3xNCC13UQjqh3nMvLYP9656PtXN1ZMWjhFzn

-- Dumped from database version 15.19
-- Dumped by pg_dump version 15.19

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- Name: ai; Type: SCHEMA; Schema: -; Owner: -
--

CREATE SCHEMA ai;


--
-- Name: SCHEMA ai; Type: COMMENT; Schema: -; Owner: -
--

COMMENT ON SCHEMA ai IS '数据集、分析、模型、预测与优化';


--
-- Name: core; Type: SCHEMA; Schema: -; Owner: -
--

CREATE SCHEMA core;


--
-- Name: SCHEMA core; Type: COMMENT; Schema: -; Owner: -
--

COMMENT ON SCHEMA core IS '统一业务对象注册与跨域引用';


--
-- Name: data; Type: SCHEMA; Schema: -; Owner: -
--

CREATE SCHEMA data;


--
-- Name: SCHEMA data; Type: COMMENT; Schema: -; Owner: -
--

COMMENT ON SCHEMA data IS '模板驱动的数据资产导入、暂存、质量和追溯';


--
-- Name: iam; Type: SCHEMA; Schema: -; Owner: -
--

CREATE SCHEMA iam;


--
-- Name: SCHEMA iam; Type: COMMENT; Schema: -; Owner: -
--

COMMENT ON SCHEMA iam IS '组织、用户、角色、权限与数据范围';


--
-- Name: kb; Type: SCHEMA; Schema: -; Owner: -
--

CREATE SCHEMA kb;


--
-- Name: SCHEMA kb; Type: COMMENT; Schema: -; Owner: -
--

COMMENT ON SCHEMA kb IS '知识版本、ACL、全文与向量';


--
-- Name: mdm; Type: SCHEMA; Schema: -; Owner: -
--

CREATE SCHEMA mdm;


--
-- Name: SCHEMA mdm; Type: COMMENT; Schema: -; Owner: -
--

COMMENT ON SCHEMA mdm IS '主数据、别名、重复识别与合并';


--
-- Name: mfg; Type: SCHEMA; Schema: -; Owner: -
--

CREATE SCHEMA mfg;


--
-- Name: SCHEMA mfg; Type: COMMENT; Schema: -; Owner: -
--

COMMENT ON SCHEMA mfg IS '生产、批次、库存与追溯';


--
-- Name: ops; Type: SCHEMA; Schema: -; Owner: -
--

CREATE SCHEMA ops;


--
-- Name: SCHEMA ops; Type: COMMENT; Schema: -; Owner: -
--

COMMENT ON SCHEMA ops IS '文件、导入、任务、审计与Outbox';


--
-- Name: quality; Type: SCHEMA; Schema: -; Owner: -
--

CREATE SCHEMA quality;


--
-- Name: SCHEMA quality; Type: COMMENT; Schema: -; Owner: -
--

COMMENT ON SCHEMA quality IS '标准、检验、判定与报告';


--
-- Name: rnd; Type: SCHEMA; Schema: -; Owner: -
--

CREATE SCHEMA rnd;


--
-- Name: SCHEMA rnd; Type: COMMENT; Schema: -; Owner: -
--

COMMENT ON SCHEMA rnd IS '需求、项目、实验、配方与样品';


--
-- Name: spc; Type: SCHEMA; Schema: -; Owner: -
--

CREATE SCHEMA spc;


--
-- Name: SCHEMA spc; Type: COMMENT; Schema: -; Owner: -
--

COMMENT ON SCHEMA spc IS '图谱、曲线、处理、特征与比对';


--
-- Name: tpl; Type: SCHEMA; Schema: -; Owner: -
--

CREATE SCHEMA tpl;


--
-- Name: SCHEMA tpl; Type: COMMENT; Schema: -; Owner: -
--

COMMENT ON SCHEMA tpl IS '统一模板中心与运行时动态值';


--
-- Name: pg_trgm; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA public;


--
-- Name: EXTENSION pg_trgm; Type: COMMENT; Schema: -; Owner: -
--

COMMENT ON EXTENSION pg_trgm IS 'text similarity measurement and index searching based on trigrams';


--
-- Name: pgcrypto; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA public;


--
-- Name: EXTENSION pgcrypto; Type: COMMENT; Schema: -; Owner: -
--

COMMENT ON EXTENSION pgcrypto IS 'cryptographic functions';


--
-- Name: vector; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public;


--
-- Name: EXTENSION vector; Type: COMMENT; Schema: -; Owner: -
--

COMMENT ON EXTENSION vector IS 'vector data type and ivfflat and hnsw access methods';


--
-- Name: guard_published_input_field_binding(); Type: FUNCTION; Schema: ai; Owner: -
--

CREATE FUNCTION ai.guard_published_input_field_binding() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    missing_code text;
BEGIN
    IF NEW.status <> 'PUBLISHED' THEN
        RETURN NEW;
    END IF;

    IF NEW.value_type = 'COMPOSITION' THEN
        IF jsonb_typeof(NEW.definition_jsonb -> 'standardFieldCodes') <> 'array'
           OR NOT (NEW.definition_jsonb -> 'standardFieldCodes' ? 'FORMULA.ITEM.MATERIAL_CODE')
           OR NOT (NEW.definition_jsonb -> 'standardFieldCodes' ? 'FORMULA.ITEM.RATIO') THEN
            RAISE EXCEPTION 'published composition X must bind material code and ratio fields'
                USING ERRCODE = '23514', CONSTRAINT = 'published_composition_data_fields_required';
        END IF;

        SELECT code INTO missing_code
        FROM unnest(ARRAY['FORMULA.ITEM.MATERIAL_CODE','FORMULA.ITEM.RATIO']) AS code
        WHERE NOT EXISTS (
            SELECT 1 FROM tpl.standard_field_dictionary d
            WHERE d.dictionary_code = code AND d.status = 'ACTIVE'
        )
        LIMIT 1;
        IF missing_code IS NOT NULL THEN
            RAISE EXCEPTION 'published composition X references inactive data field: %', missing_code
                USING ERRCODE = '23503', CONSTRAINT = 'published_composition_data_field_active';
        END IF;
    ELSE
        IF NEW.standard_field_dictionary_id IS NULL OR NOT EXISTS (
            SELECT 1 FROM tpl.standard_field_dictionary d
            WHERE d.id = NEW.standard_field_dictionary_id AND d.status = 'ACTIVE'
        ) THEN
            RAISE EXCEPTION 'published X must bind an active data field'
                USING ERRCODE = '23503', CONSTRAINT = 'published_input_data_field_required';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;


--
-- Name: FUNCTION guard_published_input_field_binding(); Type: COMMENT; Schema: ai; Owner: -
--

COMMENT ON FUNCTION ai.guard_published_input_field_binding() IS 'Prevents publishing ordinary X without an active data field and composition X without material-code/ratio bindings.';


--
-- Name: prevent_published_rule_version_content_update(); Type: FUNCTION; Schema: ai; Owner: -
--

CREATE FUNCTION ai.prevent_published_rule_version_content_update() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF OLD.status IN ('PUBLISHED', 'RETIRED') AND (
        NEW.organization_id IS DISTINCT FROM OLD.organization_id OR
        NEW.rule_code IS DISTINCT FROM OLD.rule_code OR
        NEW.version_no IS DISTINCT FROM OLD.version_no OR
        NEW.rule_name IS DISTINCT FROM OLD.rule_name OR
        NEW.business_object IS DISTINCT FROM OLD.business_object OR
        NEW.trigger_types_jsonb IS DISTINCT FROM OLD.trigger_types_jsonb OR
        NEW.condition_definition_jsonb IS DISTINCT FROM OLD.condition_definition_jsonb OR
        NEW.context_profile IS DISTINCT FROM OLD.context_profile OR
        NEW.skill_codes_jsonb IS DISTINCT FROM OLD.skill_codes_jsonb OR
        NEW.result_type IS DISTINCT FROM OLD.result_type OR
        NEW.default_severity IS DISTINCT FROM OLD.default_severity OR
        NEW.enabled IS DISTINCT FROM OLD.enabled OR
        NEW.created_by IS DISTINCT FROM OLD.created_by OR
        NEW.created_at IS DISTINCT FROM OLD.created_at
    ) THEN
        RAISE EXCEPTION 'published AI rule version content is immutable';
    END IF;
    RETURN NEW;
END
$$;


--
-- Name: protect_completed_request(); Type: FUNCTION; Schema: ai; Owner: -
--

CREATE FUNCTION ai.protect_completed_request() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF OLD.status IN ('SUCCEEDED','PARTIAL','BLOCKED','FAILED','CANCELLED') THEN
        RAISE EXCEPTION USING ERRCODE = '55000', MESSAGE = TG_TABLE_SCHEMA || '.' || TG_TABLE_NAME || ' completed record is immutable';
    END IF;
    RETURN NEW;
END $$;


--
-- Name: protect_frozen_input_scheme_field(); Type: FUNCTION; Schema: ai; Owner: -
--

CREATE FUNCTION ai.protect_frozen_input_scheme_field() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
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


--
-- Name: protect_frozen_material_dictionary(); Type: FUNCTION; Schema: ai; Owner: -
--

CREATE FUNCTION ai.protect_frozen_material_dictionary() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
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


--
-- Name: protect_model_version_payload(); Type: FUNCTION; Schema: ai; Owner: -
--

CREATE FUNCTION ai.protect_model_version_payload() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
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


--
-- Name: protect_prediction_record_v2(); Type: FUNCTION; Schema: ai; Owner: -
--

CREATE FUNCTION ai.protect_prediction_record_v2() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF TG_OP='DELETE' THEN
        RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='ai.prediction_record is immutable';
    END IF;
    IF OLD.execution_status IN ('SUCCEEDED','FAILED') THEN
        RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='ai.prediction_record completed record is immutable';
    END IF;
    IF NEW.organization_id IS DISTINCT FROM OLD.organization_id
       OR NEW.prediction_type IS DISTINCT FROM OLD.prediction_type
       OR NEW.idempotency_key IS DISTINCT FROM OLD.idempotency_key
       OR NEW.request_hash IS DISTINCT FROM OLD.request_hash
       OR NEW.request_jsonb IS DISTINCT FROM OLD.request_jsonb
       OR NEW.target_bindings_jsonb IS DISTINCT FROM OLD.target_bindings_jsonb
       OR NEW.created_by IS DISTINCT FROM OLD.created_by
       OR NEW.created_at IS DISTINCT FROM OLD.created_at
       OR NEW.request_id IS DISTINCT FROM OLD.request_id
       OR NEW.contract_version IS DISTINCT FROM OLD.contract_version THEN
        RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='ai.prediction_record request payload is immutable';
    END IF;
    RETURN NEW;
END $$;


--
-- Name: protect_research_run_v2_r08(); Type: FUNCTION; Schema: ai; Owner: -
--

CREATE FUNCTION ai.protect_research_run_v2_r08() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF TG_OP='DELETE' THEN
        RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='ai.research_run_v2 is immutable';
    END IF;
    IF OLD.execution_status IN ('SUCCEEDED','FAILED','CANCELLED') OR OLD.status IN ('SUCCEEDED','PARTIAL','BLOCKED','FAILED','CANCELLED') THEN
        RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='ai.research_run_v2 terminal record is immutable';
    END IF;
    IF NEW.organization_id IS DISTINCT FROM OLD.organization_id
       OR NEW.created_by IS DISTINCT FROM OLD.created_by
       OR NEW.idempotency_key IS DISTINCT FROM OLD.idempotency_key
       OR NEW.request_hash IS DISTINCT FROM OLD.request_hash
       OR NEW.request_jsonb IS DISTINCT FROM OLD.request_jsonb
       OR NEW.model_bindings_jsonb IS DISTINCT FROM OLD.model_bindings_jsonb
       OR NEW.request_id IS DISTINCT FROM OLD.request_id
       OR NEW.contract_version IS DISTINCT FROM OLD.contract_version
       OR NEW.seed IS DISTINCT FROM OLD.seed
       OR NEW.search_config_jsonb IS DISTINCT FROM OLD.search_config_jsonb
       OR NEW.baseline_type IS DISTINCT FROM OLD.baseline_type
       OR NEW.baseline_entity_id IS DISTINCT FROM OLD.baseline_entity_id
       OR NEW.baseline_version_id IS DISTINCT FROM OLD.baseline_version_id
       OR NEW.baseline_content_hash IS DISTINCT FROM OLD.baseline_content_hash
       OR NEW.baseline_snapshot_jsonb IS DISTINCT FROM OLD.baseline_snapshot_jsonb
       OR NEW.optimization_config_jsonb IS DISTINCT FROM OLD.optimization_config_jsonb
    THEN
        RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='ai.research_run_v2 frozen request cannot change';
    END IF;
    RETURN NEW;
END $$;


--
-- Name: protect_terminal_training_attempt(); Type: FUNCTION; Schema: ai; Owner: -
--

CREATE FUNCTION ai.protect_terminal_training_attempt() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF OLD.status IN ('SUCCEEDED','FAILED','INTERRUPTED','CANCELLED','STALE') THEN
        RAISE EXCEPTION USING ERRCODE = '55000', MESSAGE = 'terminal training attempt is immutable';
    END IF;
    RETURN NEW;
END $$;


--
-- Name: provision_ai_employee_defaults(uuid); Type: FUNCTION; Schema: ai; Owner: -
--

CREATE FUNCTION ai.provision_ai_employee_defaults(p_organization_id uuid) RETURNS void
    LANGUAGE plpgsql
    AS $$
BEGIN
    INSERT INTO ai.rule_definition (
        id, organization_id, rule_code, rule_name, business_object,
        trigger_types_jsonb, condition_definition_jsonb, context_profile,
        skill_codes_jsonb, result_type, default_severity, enabled, version
    )
    SELECT gen_random_uuid(), p_organization_id, rule_code, rule_name, business_object,
           '["PERIODIC", "EVENT"]'::jsonb, condition_definition::jsonb,
           context_profile, '[]'::jsonb, result_type, default_severity, true, 0
    FROM (VALUES
        ('PROJECT_TASK_OVERDUE', '项目任务逾期', 'PROJECT_TASK',
         '{"field":"plannedDate","operator":"beforeToday","excludeStatuses":["COMPLETED","CANCELLED"]}',
         'PROJECT_TASK_RISK', 'RISK', 'MEDIUM'),
        ('PROJECT_DEADLINE_OVERDUE', '项目计划日期逾期', 'PROJECT',
         '{"field":"plannedEndDate","operator":"beforeToday","excludeStatuses":["COMPLETED","CANCELLED","CLOSED","ARCHIVED"]}',
         'PROJECT_HEALTH', 'RISK', 'MEDIUM'),
        ('PROJECT_STAGE_OVERDUE', '项目阶段逾期', 'PROJECT_STAGE',
         '{"field":"plannedEndDate","operator":"beforeToday","excludeStatuses":["COMPLETED","CANCELLED","CLOSED","ARCHIVED"]}',
         'PROJECT_STAGE_RISK', 'BLOCKER', 'MEDIUM'),
        ('PROJECT_TASK_MISSING_OWNER', '项目任务缺少负责人', 'PROJECT_TASK',
         '{"field":"owner","operator":"blank","excludeStatuses":["COMPLETED","CANCELLED"]}',
         'PROJECT_TASK_RISK', 'REMINDER', 'MEDIUM'),
        ('PROJECT_TASK_NO_EXPERIMENT', '进行中任务长期无实验', 'PROJECT_TASK',
         '{"operator":"AND","conditions":[{"field":"status","operator":"equals","value":"IN_PROGRESS"},{"field":"experimentCount","operator":"equals","value":0},{"field":"createdAt","operator":"olderThanDays","value":7}]}',
         'PROJECT_TASK_RISK', 'BLOCKER', 'MEDIUM'),
        ('PROJECT_TASK_STALE', '项目任务长期无更新', 'PROJECT_TASK',
         '{"field":"updatedAt","operator":"olderThanDays","value":14,"excludeStatuses":["COMPLETED","CANCELLED"]}',
         'PROJECT_TASK_RISK', 'RISK', 'MEDIUM')
    ) seeded(rule_code, rule_name, business_object, condition_definition,
             context_profile, result_type, default_severity)
    ON CONFLICT (organization_id, rule_code) DO NOTHING;

    INSERT INTO ai.rule_version (
        id, organization_id, rule_code, version_no, rule_name, business_object,
        trigger_types_jsonb, condition_definition_jsonb, context_profile,
        skill_codes_jsonb, result_type, default_severity, enabled, status,
        change_note, effective_from, effective_to
    )
    SELECT gen_random_uuid(), d.organization_id, d.rule_code, 1, d.rule_name, d.business_object,
           d.trigger_types_jsonb, d.condition_definition_jsonb, d.context_profile,
           d.skill_codes_jsonb, d.result_type, d.default_severity, true, 'PUBLISHED',
           '项目管理AI员工最小健康规则集', now(), null
    FROM ai.rule_definition d
    WHERE d.organization_id = p_organization_id
      AND d.rule_code IN (
          'PROJECT_TASK_OVERDUE', 'PROJECT_DEADLINE_OVERDUE', 'PROJECT_STAGE_OVERDUE',
          'PROJECT_TASK_MISSING_OWNER', 'PROJECT_TASK_NO_EXPERIMENT', 'PROJECT_TASK_STALE')
      AND NOT EXISTS (
          SELECT 1 FROM ai.rule_version v
          WHERE v.organization_id = d.organization_id
            AND v.rule_code = d.rule_code
            AND v.version_no = 1
      );

    INSERT INTO ai.employee_scheduled_task (
        id, organization_id, task_code, task_name, description, cadence,
        day_of_week, run_time, timezone, enabled, next_run_at
    )
    SELECT gen_random_uuid(), p_organization_id, task_code, task_name, description,
           cadence, day_of_week, run_time::time, 'Asia/Shanghai', true,
           CASE
             WHEN cadence = 'DAILY' THEN
               CASE WHEN ((CURRENT_DATE + run_time::time) AT TIME ZONE 'Asia/Shanghai') > now()
                    THEN ((CURRENT_DATE + run_time::time) AT TIME ZONE 'Asia/Shanghai')
                    ELSE (((CURRENT_DATE + 1) + run_time::time) AT TIME ZONE 'Asia/Shanghai') END
             ELSE
               CASE WHEN ((CURRENT_DATE + ((day_of_week - EXTRACT(ISODOW FROM CURRENT_DATE)::int + 7) % 7) + run_time::time) AT TIME ZONE 'Asia/Shanghai') > now()
                    THEN ((CURRENT_DATE + ((day_of_week - EXTRACT(ISODOW FROM CURRENT_DATE)::int + 7) % 7) + run_time::time) AT TIME ZONE 'Asia/Shanghai')
                    ELSE ((CURRENT_DATE + ((day_of_week - EXTRACT(ISODOW FROM CURRENT_DATE)::int + 7) % 7) + 7 + run_time::time) AT TIME ZONE 'Asia/Shanghai') END
           END
    FROM (VALUES
        ('PROJECT_HEALTH_CHECK', '项目健康巡检', '检查项目、阶段、任务计划与项目风险结果。', 'DAILY', NULL::smallint, '09:00'),
        ('PROJECT_RISK_RESCAN', '项目风险复扫', '重新评估项目范围内的确定性风险。', 'DAILY', NULL::smallint, '15:30'),
        ('PROJECT_WEEKLY_REPORT', '项目周报', '汇总项目任务风险变化和处理情况。', 'WEEKLY', 1::smallint, '09:30'),
        ('NEXT_WEEK_FOCUS', '下周重点', '根据当前项目风险提示下一周期关注重点。', 'WEEKLY', 5::smallint, '16:30')
    ) defaults(task_code, task_name, description, cadence, day_of_week, run_time)
    ON CONFLICT (organization_id, task_code) DO NOTHING;
END
$$;


--
-- Name: provision_ai_employee_defaults_for_new_org(); Type: FUNCTION; Schema: ai; Owner: -
--

CREATE FUNCTION ai.provision_ai_employee_defaults_for_new_org() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    PERFORM ai.provision_ai_employee_defaults(NEW.id);
    RETURN NEW;
END
$$;


--
-- Name: reject_immutable_change(); Type: FUNCTION; Schema: ai; Owner: -
--

CREATE FUNCTION ai.reject_immutable_change() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    RAISE EXCEPTION USING ERRCODE = '55000', MESSAGE = TG_TABLE_SCHEMA || '.' || TG_TABLE_NAME || ' is immutable';
END $$;


--
-- Name: validate_model_domain_policy(); Type: FUNCTION; Schema: ai; Owner: -
--

CREATE FUNCTION ai.validate_model_domain_policy() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NEW.domain_policy_version_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM ai.modeling_policy_version p
        WHERE p.organization_id=NEW.organization_id AND p.target_id=NEW.target_id
          AND p.id=NEW.domain_policy_version_id AND p.kind='DOMAIN' AND p.status='PUBLISHED'
    ) THEN
        RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='model domain policy must be a published DOMAIN policy for the target';
    END IF;
    RETURN NEW;
END $$;


--
-- Name: validate_prediction_target_policy_pointer(); Type: FUNCTION; Schema: ai; Owner: -
--

CREATE FUNCTION ai.validate_prediction_target_policy_pointer() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NEW.current_quality_policy_version_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM ai.modeling_policy_version p
        WHERE p.organization_id=NEW.organization_id AND p.target_id=NEW.id
          AND p.id=NEW.current_quality_policy_version_id AND p.kind='QUALITY' AND p.status='PUBLISHED'
    ) THEN
        RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='current quality policy must be a published QUALITY policy for the target';
    END IF;
    IF NEW.current_domain_policy_version_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM ai.modeling_policy_version p
        WHERE p.organization_id=NEW.organization_id AND p.target_id=NEW.id
          AND p.id=NEW.current_domain_policy_version_id AND p.kind='DOMAIN' AND p.status='PUBLISHED'
    ) THEN
        RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='current domain policy must be a published DOMAIN policy for the target';
    END IF;
    RETURN NEW;
END $$;


--
-- Name: protect_publication_snapshot(); Type: FUNCTION; Schema: data; Owner: -
--

CREATE FUNCTION data.protect_publication_snapshot() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    RAISE EXCEPTION 'Publication snapshots are immutable';
END;
$$;


--
-- Name: reject_duplicate_version_sha256(); Type: FUNCTION; Schema: kb; Owner: -
--

CREATE FUNCTION kb.reject_duplicate_version_sha256() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    target_organization_id uuid;
BEGIN
    SELECT organization_id INTO target_organization_id FROM kb.document WHERE id = NEW.document_id;
    IF target_organization_id IS NULL THEN RETURN NEW; END IF;
    PERFORM pg_advisory_xact_lock(hashtextextended(target_organization_id::text || ':' || NEW.sha256, 0));
    IF EXISTS (
        SELECT 1
        FROM kb.document_version existing
        JOIN kb.document d ON d.id = existing.document_id
        WHERE d.organization_id = target_organization_id
          AND existing.sha256 = NEW.sha256
          AND existing.document_id <> NEW.document_id
    ) THEN
        RAISE unique_violation USING
            MESSAGE = 'duplicate knowledge file sha256 in organization',
            CONSTRAINT = 'uq_kb_document_version_org_sha256';
    END IF;
    RETURN NEW;
END;
$$;


--
-- Name: clone_business_partner(uuid, uuid, character varying, character varying); Type: FUNCTION; Schema: mdm; Owner: -
--

CREATE FUNCTION mdm.clone_business_partner(p_source_id uuid, p_new_id uuid, p_partner_code character varying, p_operator character varying) RETURNS TABLE(new_id uuid)
    LANGUAGE plpgsql
    AS $$
DECLARE
    v_now timestamptz := now();
    r record;
    v_id uuid;
    v_contact_id uuid;
    v_communication_id uuid;
    v_requirement_id uuid;
    v_receipt_id uuid;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM mdm.business_partner WHERE id=p_source_id) THEN
        RAISE EXCEPTION 'business partner % does not exist', p_source_id;
    END IF;

    CREATE TEMP TABLE IF NOT EXISTS clone_contact_map(old_id uuid PRIMARY KEY,new_id uuid) ON COMMIT DROP;
    CREATE TEMP TABLE IF NOT EXISTS clone_communication_map(old_id uuid PRIMARY KEY,new_id uuid) ON COMMIT DROP;
    CREATE TEMP TABLE IF NOT EXISTS clone_requirement_map(old_id uuid PRIMARY KEY,new_id uuid) ON COMMIT DROP;
    TRUNCATE clone_contact_map,clone_communication_map,clone_requirement_map;

    INSERT INTO mdm.business_partner(id,partner_code,name,normalized_name,industry,address,status,remark,version,created_at,updated_at,created_by,updated_by)
    SELECT p_new_id,p_partner_code,name||'（复制）',lower(regexp_replace(name||'（复制）-'||p_new_id::text,'\s','','g')),
           industry,address,'ACTIVE',remark,0,v_now,v_now,p_operator,p_operator
      FROM mdm.business_partner WHERE id=p_source_id;
    INSERT INTO mdm.business_partner_role(partner_id,role,created_at,created_by)
    SELECT p_new_id,role,v_now,p_operator FROM mdm.business_partner_role WHERE partner_id=p_source_id;

    FOR r IN SELECT * FROM mdm.partner_contact WHERE partner_id=p_source_id ORDER BY created_at LOOP
        v_id:=gen_random_uuid(); INSERT INTO clone_contact_map VALUES(r.id,v_id);
        INSERT INTO mdm.partner_contact(id,partner_id,name,department,title,phone,email,responsibility,primary_contact,status,valid_from,valid_to,version,created_at,updated_at,created_by,updated_by)
        VALUES(v_id,p_new_id,r.name,r.department,r.title,r.phone,r.email,r.responsibility,r.primary_contact,r.status,r.valid_from,r.valid_to,0,v_now,v_now,p_operator,p_operator);
    END LOOP;

    FOR r IN SELECT * FROM mdm.communication_record WHERE partner_id=p_source_id ORDER BY created_at LOOP
        v_id:=gen_random_uuid(); INSERT INTO clone_communication_map VALUES(r.id,v_id);
        SELECT m.new_id INTO v_contact_id FROM clone_contact_map m WHERE m.old_id=r.contact_id;
        INSERT INTO mdm.communication_record(id,record_code,partner_id,contact_id,communicated_at,internal_participants,communication_method,application_scenario,problem,target,competitor,content,next_action,next_follow_up_at,status,converted_project_id,version,created_at,updated_at,created_by,updated_by)
        VALUES(v_id,'COM-'||upper(substr(replace(v_id::text,'-',''),1,12)),p_new_id,v_contact_id,r.communicated_at,r.internal_participants,r.communication_method,r.application_scenario,r.problem,r.target,r.competitor,r.content,r.next_action,r.next_follow_up_at,r.status,NULL,0,v_now,v_now,p_operator,p_operator);
        INSERT INTO mdm.communication_follow_up(id,communication_id,followed_up_at,followed_by,content,result,next_action,next_follow_up_at,created_at,created_by)
        SELECT gen_random_uuid(),v_id,followed_up_at,followed_by,content,result,next_action,next_follow_up_at,v_now,p_operator FROM mdm.communication_follow_up WHERE communication_id=r.id;
    END LOOP;

    FOR r IN SELECT * FROM mdm.customer_requirement WHERE partner_id=p_source_id ORDER BY created_at LOOP
        v_id:=gen_random_uuid(); INSERT INTO clone_requirement_map VALUES(r.id,v_id);
        SELECT m.new_id INTO v_contact_id FROM clone_contact_map m WHERE m.old_id=r.contact_id;
        SELECT m.new_id INTO v_communication_id FROM clone_communication_map m WHERE m.old_id=r.source_communication_id;
        INSERT INTO mdm.customer_requirement(id,requirement_code,partner_id,contact_id,title,application_scenario,substrate,target_description,competitor,delivery_date,status,source_communication_id,project_id,version,created_at,updated_at,created_by,updated_by)
        VALUES(v_id,'REQ-'||upper(substr(replace(v_id::text,'-',''),1,12)),p_new_id,v_contact_id,r.title,r.application_scenario,r.substrate,r.target_description,r.competitor,r.delivery_date,'DRAFT',v_communication_id,NULL,0,v_now,v_now,p_operator,p_operator);
        INSERT INTO mdm.requirement_metric(id,requirement_id,metric_name,unit,comparator,target_value,lower_value,upper_value,test_standard,priority,remark,sort_order,created_at,updated_at,created_by,updated_by)
        SELECT gen_random_uuid(),v_id,metric_name,unit,comparator,target_value,lower_value,upper_value,test_standard,priority,remark,sort_order,v_now,v_now,p_operator,p_operator FROM mdm.requirement_metric WHERE requirement_id=r.id;
    END LOOP;

    FOR r IN SELECT * FROM mdm.customer_feedback WHERE partner_id=p_source_id ORDER BY created_at LOOP
        SELECT m.new_id INTO v_contact_id FROM clone_contact_map m WHERE m.old_id=r.contact_id;
        SELECT m.new_id INTO v_requirement_id FROM clone_requirement_map m WHERE m.old_id=r.requirement_id;
        v_id:=gen_random_uuid();
        INSERT INTO mdm.customer_feedback(id,feedback_code,partner_id,contact_id,requirement_id,feedback_date,sample_reference,target_description,test_result,problem,improvement_direction,improvement_plan,verdict,status,experiment_id,report_id,project_id,review_id,next_experiment_id,version,created_at,updated_at,created_by,updated_by)
        VALUES(v_id,'FB-'||upper(substr(replace(v_id::text,'-',''),1,12)),p_new_id,v_contact_id,v_requirement_id,r.feedback_date,r.sample_reference,r.target_description,r.test_result,r.problem,r.improvement_direction,r.improvement_plan,'PENDING','DRAFT',NULL,NULL,NULL,NULL,NULL,0,v_now,v_now,p_operator,p_operator);
    END LOOP;

    FOR r IN SELECT * FROM mdm.supplier_trial_sample WHERE supplier_id=p_source_id ORDER BY created_at LOOP
        SELECT m.new_id INTO v_contact_id FROM clone_contact_map m WHERE m.old_id=r.contact_id;
        v_id:=gen_random_uuid();
        INSERT INTO mdm.supplier_trial_sample(id,sample_code,supplier_id,contact_id,name,material_type,specification,supplier_batch,provided_date,quantity,unit,storage_condition,safety_note,test_status,conclusion,conclusion_reason,conclusion_by,conclusion_at,substitute_sample_id,substitution_note,version,created_at,updated_at,created_by,updated_by)
        VALUES(v_id,'SMP-'||upper(substr(replace(v_id::text,'-',''),1,12)),p_new_id,v_contact_id,r.name,r.material_type,r.specification,r.supplier_batch,r.provided_date,r.quantity,r.unit,r.storage_condition,r.safety_note,'RECEIVED','PENDING',NULL,NULL,NULL,NULL,NULL,0,v_now,v_now,p_operator,p_operator);
    END LOOP;

    FOR r IN SELECT * FROM mfg.inbound_receipt WHERE supplier_id=p_source_id ORDER BY created_at LOOP
        SELECT m.new_id INTO v_contact_id FROM clone_contact_map m WHERE m.old_id=r.contact_id;
        v_receipt_id:=gen_random_uuid();
        INSERT INTO mfg.inbound_receipt(id,receipt_code,supplier_id,contact_id,arrival_date,delivery_note,receiver,status,remark,version,created_at,updated_at,created_by,updated_by)
        VALUES(v_receipt_id,'IN-'||upper(substr(replace(v_receipt_id::text,'-',''),1,12)),p_new_id,v_contact_id,r.arrival_date,r.delivery_note,r.receiver,'DRAFT',r.remark,0,v_now,v_now,p_operator,p_operator);
        INSERT INTO mfg.inbound_receipt_item(id,receipt_id,material_id,material_code,material_name,supplier_batch,quantity,unit,production_date,expiry_date,package_spec,batch_id,release_status,version,created_at,updated_at,created_by,updated_by)
        SELECT gen_random_uuid(),v_receipt_id,material_id,material_code,material_name,supplier_batch,quantity,unit,production_date,expiry_date,package_spec,NULL,'QUARANTINED',0,v_now,v_now,p_operator,p_operator FROM mfg.inbound_receipt_item WHERE receipt_id=r.id;
    END LOOP;

    INSERT INTO ops.audit_log(id,object_type,object_id,action,operator,detail,created_at)
    VALUES(gen_random_uuid(),'BUSINESS_PARTNER',p_new_id,'COPIED',p_operator,'copied from '||p_source_id,v_now);
    INSERT INTO ops.outbox_event(id,event_type,aggregate_type,aggregate_id,payload,status,created_at)
    VALUES(gen_random_uuid(),'BusinessPartnerCopied','BUSINESS_PARTNER',p_new_id,json_build_object('sourceId',p_source_id,'newId',p_new_id)::text,'PENDING',v_now);
    RETURN QUERY SELECT p_new_id;
END $$;


--
-- Name: project_stage_open_task_count(uuid); Type: FUNCTION; Schema: mdm; Owner: -
--

CREATE FUNCTION mdm.project_stage_open_task_count(p_stage_id uuid) RETURNS bigint
    LANGUAGE plpgsql STABLE
    AS $_$
DECLARE result bigint;
BEGIN
    IF to_regclass('mdm.project_task') IS NULL THEN
        RETURN 0;
    END IF;
    EXECUTE 'SELECT count(*) FROM mdm.project_task WHERE stage_id = $1 AND deleted = false AND status NOT IN (''COMPLETED'', ''CANCELLED'')'
        INTO result USING p_stage_id;
    RETURN result;
END $_$;


--
-- Name: project_stage_task_count(uuid); Type: FUNCTION; Schema: mdm; Owner: -
--

CREATE FUNCTION mdm.project_stage_task_count(p_stage_id uuid) RETURNS bigint
    LANGUAGE plpgsql STABLE
    AS $_$
DECLARE result bigint;
BEGIN
    IF to_regclass('mdm.project_task') IS NULL THEN
        RETURN 0;
    END IF;
    EXECUTE 'SELECT count(*) FROM mdm.project_task WHERE stage_id = $1 AND deleted = false'
        INTO result USING p_stage_id;
    RETURN result;
END $_$;


--
-- Name: enforce_async_capacity(); Type: FUNCTION; Schema: ops; Owner: -
--

CREATE FUNCTION ops.enforce_async_capacity() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    total_limit integer := coalesce(nullif(current_setting('app.capacity_pending_jobs',true),''),'100')::integer;
    org_limit integer := coalesce(nullif(current_setting('app.capacity_org_pending_jobs',true),''),'20')::integer;
BEGIN
    IF NEW.status NOT IN ('READY','RUNNING') THEN RETURN NEW; END IF;
    IF TG_OP='UPDATE' AND OLD.status IN ('READY','RUNNING') AND OLD.organization_id=NEW.organization_id THEN RETURN NEW; END IF;
    PERFORM pg_advisory_xact_lock(hashtextextended('aird:async-capacity',0));
    IF TG_OP='INSERT' AND NEW.idempotency_key IS NOT NULL AND EXISTS(
        SELECT 1 FROM ops.async_job WHERE organization_id=NEW.organization_id AND idempotency_key=NEW.idempotency_key
    ) THEN RETURN NEW; END IF;
    IF (SELECT count(*) FROM ops.async_job WHERE status IN ('READY','RUNNING')) >= total_limit
       OR (SELECT count(*) FROM ops.async_job WHERE organization_id=NEW.organization_id AND status IN ('READY','RUNNING')) >= org_limit THEN
        RAISE EXCEPTION USING ERRCODE='55P03', MESSAGE='aird:queue-capacity';
    END IF;
    RETURN NEW;
END $$;


--
-- Name: install_unified_lab_templates(uuid, uuid); Type: FUNCTION; Schema: tpl; Owner: -
--

CREATE FUNCTION tpl.install_unified_lab_templates(target_org uuid, target_actor uuid) RETURNS integer
    LANGUAGE plpgsql
    AS $$
DECLARE p record; new_template_id uuid; new_version_id uuid; b jsonb; n integer:=0;
BEGIN
    IF NOT EXISTS(SELECT 1 FROM iam.app_user WHERE id=target_actor AND organization_id=target_org) THEN
        RAISE EXCEPTION 'Template installer actor must belong to target organization';
    END IF;
    PERFORM pg_advisory_xact_lock(hashtextextended('unified-lab:'||target_org::text,0));
    FOR p IN SELECT * FROM tpl.unified_lab_template_package ORDER BY template_code LOOP
        INSERT INTO tpl.template(id,organization_id,template_code,name,format,created_by)
        VALUES(gen_random_uuid(),target_org,p.template_code,p.payload_jsonb->>'name','XLSX',target_actor)
        ON CONFLICT(organization_id,template_code) DO NOTHING;
        SELECT id INTO new_template_id FROM tpl.template WHERE organization_id=target_org AND template_code=p.template_code;
        SELECT id INTO new_version_id FROM tpl.template_version WHERE template_id=new_template_id AND version_no=1;
        IF new_version_id IS NOT NULL THEN
            IF (SELECT layout_summary_jsonb->>'seedPackageHash' FROM tpl.template_version WHERE id=new_version_id) IS DISTINCT FROM p.package_hash::text THEN
                RAISE EXCEPTION 'Unified template % version 1 has different content; publish a new version instead',p.template_code;
            END IF;
            CONTINUE;
        END IF;
        new_version_id:=gen_random_uuid();
        INSERT INTO tpl.template_version(id,template_id,version_no,status,schema_jsonb,layout_summary_jsonb,
          editor_snapshot_hash,snapshot_kind,editor_app_version,plugin_manifest_hash,snapshot_format_version,
          schema_hash,mapping_hash,data_hash,workspace_hash,created_by,published_at)
        VALUES(new_version_id,new_template_id,1,'PUBLISHED',p.payload_jsonb->'schema',
          (p.payload_jsonb->'layout')||jsonb_build_object('seedPackageHash',p.package_hash),
          p.payload_jsonb->>'snapshotHash','UNIVER_WORKBOOK','univer-1.2.0',p.payload_jsonb->>'pluginHash',3,
          p.payload_jsonb->>'schemaHash',p.payload_jsonb->>'mappingHash',p.payload_jsonb->>'dataHash',p.payload_jsonb->>'workspaceHash',target_actor,now());
        FOR b IN SELECT value FROM jsonb_array_elements(p.payload_jsonb->'mapping') LOOP
            INSERT INTO tpl.template_mapping(id,template_version_id,binding_id,parent_binding_id,format,field_code,data_path,
              binding_role,mapping_kind,repeat_axis,record_height,record_width,record_stride,locator_type,locator_jsonb,
              sync_direction,primary_binding,binding_status,diagnostic_jsonb,termination_jsonb,last_validated_at)
            VALUES(gen_random_uuid(),new_version_id,b->>'bindingId',b->>'parentBindingId','XLSX',b->>'fieldCode',b->>'dataPath',
              b->>'role',b->>'mappingKind',b->>'repeatAxis',coalesce((b->>'recordHeight')::int,1),coalesce((b->>'recordWidth')::int,1),coalesce((b->>'recordStride')::int,1),
              b->>'locatorType',b->'locator',b->>'syncDirection',true,'VALID',
              coalesce(b->'diagnostic','{}'::jsonb)||(b-'locator'-'diagnostic'),coalesce(b->'termination','{}'::jsonb),now());
        END LOOP;
        INSERT INTO tpl.template_import_contract(template_version_id,import_contract_version,layout_structure_version,contract_hash,contract_jsonb,created_by)
        VALUES(new_version_id,9,(p.payload_jsonb#>>'{contract,layoutStructureVersion}')::int,p.payload_jsonb#>>'{contract,contractHash}',p.payload_jsonb->'contract',target_actor);
        INSERT INTO tpl.template_version_review(version_id,organization_id,review_status,reviewed_by,reviewed_at,review_comment)
        VALUES(new_version_id,target_org,'APPROVED',target_actor,now(),'Reviewed unified laboratory template package');
        UPDATE tpl.template SET current_published_version_id=new_version_id,updated_at=now() WHERE id=new_template_id;
        n:=n+1;
    END LOOP;
    RETURN n;
END $$;


SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: ai_call_audit; Type: TABLE; Schema: ai; Owner: -
--

CREATE TABLE ai.ai_call_audit (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    actor_id uuid NOT NULL,
    conversation_id uuid,
    request_kind character varying(40) NOT NULL,
    model character varying(160),
    prompt_version character varying(80) NOT NULL,
    request_sha256 character(64) NOT NULL,
    response_sha256 character(64),
    input_tokens integer DEFAULT 0 NOT NULL,
    output_tokens integer DEFAULT 0 NOT NULL,
    total_tokens integer DEFAULT 0 NOT NULL,
    status character varying(24) NOT NULL,
    error_message character varying(500),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT ai_call_audit_status_check CHECK (((status)::text = ANY (ARRAY[('SUCCEEDED'::character varying)::text, ('FAILED'::character varying)::text])))
);


--
-- Name: artifact; Type: TABLE; Schema: ai; Owner: -
--

CREATE TABLE ai.artifact (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    artifact_type character varying(40) NOT NULL,
    training_snapshot_id uuid,
    training_job_attempt_id uuid,
    object_key character varying(700) NOT NULL,
    sha256 character(64) NOT NULL,
    size_bytes bigint NOT NULL,
    metadata_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    media_type character varying(160),
    content_addressed boolean DEFAULT true NOT NULL,
    workbench_run_id uuid,
    CONSTRAINT artifact_hash_check CHECK ((sha256 ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT artifact_metadata_object CHECK ((jsonb_typeof(metadata_jsonb) = 'object'::text)),
    CONSTRAINT artifact_owner_check CHECK (((training_snapshot_id IS NOT NULL) OR (training_job_attempt_id IS NOT NULL) OR (workbench_run_id IS NOT NULL))),
    CONSTRAINT artifact_size_bytes_check CHECK ((size_bytes >= 0))
);


--
-- Name: assistant_conversation; Type: TABLE; Schema: ai; Owner: -
--

CREATE TABLE ai.assistant_conversation (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    title character varying(240),
    created_by uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    summary text,
    summary_version character varying(40),
    summary_token_count integer DEFAULT 0 NOT NULL,
    last_summarized_message_id uuid,
    title_source character varying(24) DEFAULT 'FIRST_QUESTION'::character varying NOT NULL,
    conversation_type character varying(40) DEFAULT 'AI_QA'::character varying NOT NULL,
    scope_snapshot_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL
);


--
-- Name: assistant_message; Type: TABLE; Schema: ai; Owner: -
--

CREATE TABLE ai.assistant_message (
    id uuid NOT NULL,
    conversation_id uuid NOT NULL,
    role character varying(16) NOT NULL,
    content text NOT NULL,
    citations_jsonb jsonb DEFAULT '[]'::jsonb NOT NULL,
    warnings_jsonb jsonb DEFAULT '[]'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    query_plan_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    retrieval_trace_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    CONSTRAINT assistant_message_role_check CHECK (((role)::text = ANY (ARRAY[('USER'::character varying)::text, ('ASSISTANT'::character varying)::text])))
);


--
-- Name: configuration_command_receipt; Type: TABLE; Schema: ai; Owner: -
--

CREATE TABLE ai.configuration_command_receipt (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    operation character varying(120) NOT NULL,
    idempotency_key character varying(200) NOT NULL,
    request_hash character(64) NOT NULL,
    resource_type character varying(80) NOT NULL,
    resource_id uuid,
    response_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_by uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT configuration_receipt_hash_check CHECK ((request_hash ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT configuration_receipt_response_object CHECK ((jsonb_typeof(response_jsonb) = 'object'::text))
);


--
-- Name: context_snapshot; Type: TABLE; Schema: ai; Owner: -
--

CREATE TABLE ai.context_snapshot (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    actor_id uuid NOT NULL,
    context_profile character varying(120) NOT NULL,
    subject_type character varying(80) NOT NULL,
    subject_id uuid,
    permission_scope_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    visible_data_summary_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    snapshot_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    model character varying(160),
    prompt_version character varying(80),
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: TABLE context_snapshot; Type: COMMENT; Schema: ai; Owner: -
--

COMMENT ON TABLE ai.context_snapshot IS 'Permission-filtered, reproducible context visible to one AI execution.';


--
-- Name: data_review; Type: TABLE; Schema: ai; Owner: -
--

CREATE TABLE ai.data_review (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    review_type character varying(32) NOT NULL,
    eligibility_id uuid,
    identity_issue_id uuid,
    status character varying(24) DEFAULT 'OPEN'::character varying NOT NULL,
    priority smallint DEFAULT 100 NOT NULL,
    assigned_to uuid,
    revision bigint DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    reason_code character varying(80),
    evidence_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    CONSTRAINT data_review_evidence_object CHECK ((jsonb_typeof(evidence_jsonb) = 'object'::text)),
    CONSTRAINT data_review_status_check CHECK (((status)::text = ANY (ARRAY[('OPEN'::character varying)::text, ('IN_REVIEW'::character varying)::text, ('RESOLVED'::character varying)::text, ('CANCELLED'::character varying)::text]))),
    CONSTRAINT data_review_subject_check CHECK (((((eligibility_id IS NOT NULL))::integer + ((identity_issue_id IS NOT NULL))::integer) = 1)),
    CONSTRAINT data_review_type_check CHECK (((review_type)::text = ANY (ARRAY[('ELIGIBILITY'::character varying)::text, ('IDENTITY'::character varying)::text, ('UNKNOWN_MATERIAL'::character varying)::text, ('OUTLIER'::character varying)::text])))
);


--
-- Name: data_review_decision; Type: TABLE; Schema: ai; Owner: -
--

CREATE TABLE ai.data_review_decision (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    data_review_id uuid NOT NULL,
    decision character varying(32) NOT NULL,
    reason text NOT NULL,
    evidence_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    decided_by uuid NOT NULL,
    decided_at timestamp with time zone DEFAULT now() NOT NULL,
    decision_no integer,
    request_id character varying(160),
    idempotency_key character varying(200),
    before_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    after_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    CONSTRAINT data_review_decision_after_object CHECK ((jsonb_typeof(after_jsonb) = 'object'::text)),
    CONSTRAINT data_review_decision_before_object CHECK ((jsonb_typeof(before_jsonb) = 'object'::text)),
    CONSTRAINT data_review_decision_check CHECK (((decision)::text = ANY (ARRAY[('KEEP'::character varying)::text, ('EXCLUDE'::character varying)::text, ('INCLUDE'::character varying)::text, ('MERGE'::character varying)::text, ('KEEP_SEPARATE'::character varying)::text, ('REMAP'::character varying)::text, ('DISMISS'::character varying)::text]))),
    CONSTRAINT data_review_decision_evidence_object CHECK ((jsonb_typeof(evidence_jsonb) = 'object'::text))
);


--
-- Name: eligibility_evaluation_run; Type: TABLE; Schema: ai; Owner: -
--

CREATE TABLE ai.eligibility_evaluation_run (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    target_id uuid NOT NULL,
    target_version_id uuid NOT NULL,
    input_scheme_id uuid NOT NULL,
    source_mapping_versions_jsonb jsonb DEFAULT '[]'::jsonb NOT NULL,
    material_dictionary_version_id uuid,
    modeling_policy_version_id uuid NOT NULL,
    rule_fingerprint character(64) NOT NULL,
    async_job_id uuid,
    status character varying(24) DEFAULT 'QUEUED'::character varying NOT NULL,
    total_samples bigint,
    trainable_count bigint,
    excluded_count bigint,
    review_required_count bigint,
    funnel_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    error_code character varying(100),
    error_message text,
    requested_by uuid,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    started_at timestamp with time zone,
    finished_at timestamp with time zone,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT eligibility_run_funnel_object CHECK ((jsonb_typeof(funnel_jsonb) = 'object'::text)),
    CONSTRAINT eligibility_run_hash_check CHECK ((rule_fingerprint ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT eligibility_run_mapping_array CHECK ((jsonb_typeof(source_mapping_versions_jsonb) = 'array'::text)),
    CONSTRAINT eligibility_run_status_check CHECK (((status)::text = ANY (ARRAY[('QUEUED'::character varying)::text, ('RUNNING'::character varying)::text, ('SUCCEEDED'::character varying)::text, ('FAILED'::character varying)::text, ('CANCELLED'::character varying)::text])))
);


--
-- Name: employee_scheduled_task; Type: TABLE; Schema: ai; Owner: -
--

CREATE TABLE ai.employee_scheduled_task (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    task_code character varying(64) NOT NULL,
    task_name character varying(120) NOT NULL,
    description character varying(500) DEFAULT ''::character varying NOT NULL,
    cadence character varying(16) NOT NULL,
    day_of_week smallint,
    run_time time without time zone NOT NULL,
    timezone character varying(64) DEFAULT 'Asia/Shanghai'::character varying NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    next_run_at timestamp with time zone,
    last_run_at timestamp with time zone,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT employee_scheduled_task_cadence_check CHECK (((cadence)::text = ANY (ARRAY[('DAILY'::character varying)::text, ('WEEKLY'::character varying)::text]))),
    CONSTRAINT employee_scheduled_task_check CHECK (((((cadence)::text = 'DAILY'::text) AND (day_of_week IS NULL)) OR (((cadence)::text = 'WEEKLY'::text) AND (day_of_week IS NOT NULL)))),
    CONSTRAINT employee_scheduled_task_day_of_week_check CHECK (((day_of_week >= 1) AND (day_of_week <= 7)))
);


--
-- Name: experiment_analysis; Type: TABLE; Schema: ai; Owner: -
--

CREATE TABLE ai.experiment_analysis (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    created_by uuid NOT NULL,
    conversation_id uuid,
    analysis_type character varying(40) NOT NULL,
    primary_experiment_id uuid NOT NULL,
    experiment_ids_jsonb jsonb DEFAULT '[]'::jsonb NOT NULL,
    parameters_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    source_versions_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    context_snapshot_id uuid,
    async_job_id uuid,
    result_id uuid,
    parent_analysis_id uuid,
    status character varying(20) NOT NULL,
    idempotency_key character varying(160) NOT NULL,
    error_message text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    finished_at timestamp with time zone,
    CONSTRAINT experiment_analysis_analysis_type_check CHECK (((analysis_type)::text = ANY (ARRAY[('SINGLE_REVIEW'::character varying)::text, ('MULTI_COMPARE'::character varying)::text, ('FAILURE_DIAGNOSIS'::character varying)::text, ('SIMILAR_SEARCH'::character varying)::text, ('FORMULA_PERFORMANCE'::character varying)::text, ('FOLLOW_UP'::character varying)::text, ('NEXT_EXPERIMENT_DRAFT'::character varying)::text]))),
    CONSTRAINT experiment_analysis_status_check CHECK (((status)::text = ANY (ARRAY[('QUEUED'::character varying)::text, ('RUNNING'::character varying)::text, ('SUCCEEDED'::character varying)::text, ('FAILED'::character varying)::text, ('CANCELLED'::character varying)::text, ('STALE'::character varying)::text])))
);


--
-- Name: fact_projection_receipt; Type: TABLE; Schema: ai; Owner: -
--

CREATE TABLE ai.fact_projection_receipt (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    source_type character varying(24) NOT NULL,
    source_event_id uuid NOT NULL,
    source_business_key character varying(320) NOT NULL,
    source_sequence bigint NOT NULL,
    event_type character varying(100) NOT NULL,
    payload_hash character(64) NOT NULL,
    status character varying(24) DEFAULT 'PROCESSING'::character varying NOT NULL,
    attempt_count integer DEFAULT 1 NOT NULL,
    error_message text,
    processed_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT fact_projection_payload_hash_check CHECK ((payload_hash ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT fact_projection_source_check CHECK (((source_type)::text = ANY (ARRAY[('DATA_CENTER'::character varying)::text, ('EXPERIMENT'::character varying)::text]))),
    CONSTRAINT fact_projection_status_check CHECK (((status)::text = ANY (ARRAY[('PROCESSING'::character varying)::text, ('COMPLETED'::character varying)::text, ('FAILED'::character varying)::text, ('STALE'::character varying)::text])))
);


--
-- Name: input_field; Type: TABLE; Schema: ai; Owner: -
--

CREATE TABLE ai.input_field (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    field_code character varying(160) NOT NULL,
    name character varying(240) NOT NULL,
    value_type character varying(24) NOT NULL,
    availability_stage character varying(32) NOT NULL,
    status character varying(24) DEFAULT 'DRAFT'::character varying NOT NULL,
    current_version_id uuid,
    revision bigint DEFAULT 0 NOT NULL,
    created_by uuid NOT NULL,
    updated_by uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT input_field_stage_check CHECK (((availability_stage)::text = ANY (ARRAY[('PRE_EXPERIMENT'::character varying)::text, ('POST_EXPERIMENT'::character varying)::text]))),
    CONSTRAINT input_field_status_check CHECK (((status)::text = ANY (ARRAY[('DRAFT'::character varying)::text, ('ACTIVE'::character varying)::text, ('PAUSED'::character varying)::text, ('RETIRED'::character varying)::text]))),
    CONSTRAINT input_field_value_type_check CHECK (((value_type)::text = ANY (ARRAY[('NUMBER'::character varying)::text, ('STRING'::character varying)::text, ('BOOLEAN'::character varying)::text, ('CATEGORY'::character varying)::text, ('COMPOSITION'::character varying)::text])))
);


--
-- Name: input_field_version; Type: TABLE; Schema: ai; Owner: -
--

CREATE TABLE ai.input_field_version (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    input_field_id uuid NOT NULL,
    version_no integer NOT NULL,
    status character varying(24) DEFAULT 'DRAFT'::character varying NOT NULL,
    value_type character varying(24) NOT NULL,
    unit character varying(80),
    availability_stage character varying(32) NOT NULL,
    standard_field_dictionary_id uuid,
    definition_jsonb jsonb NOT NULL,
    preprocessing_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    config_hash character(64) NOT NULL,
    published_at timestamp with time zone,
    created_by uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT input_field_definition_object CHECK ((jsonb_typeof(definition_jsonb) = 'object'::text)),
    CONSTRAINT input_field_preprocess_object CHECK ((jsonb_typeof(preprocessing_jsonb) = 'object'::text)),
    CONSTRAINT input_field_version_hash_check CHECK ((config_hash ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT input_field_version_stage_check CHECK (((availability_stage)::text = ANY (ARRAY[('PRE_EXPERIMENT'::character varying)::text, ('POST_EXPERIMENT'::character varying)::text]))),
    CONSTRAINT input_field_version_status_check CHECK (((status)::text = ANY (ARRAY[('DRAFT'::character varying)::text, ('PUBLISHED'::character varying)::text, ('RETIRED'::character varying)::text]))),
    CONSTRAINT input_field_version_value_type_check CHECK (((value_type)::text = ANY (ARRAY[('NUMBER'::character varying)::text, ('STRING'::character varying)::text, ('BOOLEAN'::character varying)::text, ('CATEGORY'::character varying)::text, ('COMPOSITION'::character varying)::text]))),
    CONSTRAINT input_field_version_version_no_check CHECK ((version_no > 0))
);


--
-- Name: input_scheme; Type: TABLE; Schema: ai; Owner: -
--

CREATE TABLE ai.input_scheme (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    target_id uuid NOT NULL,
    target_version_id uuid NOT NULL,
    material_dictionary_version_id uuid,
    scheme_code character varying(160) NOT NULL,
    name character varying(240) NOT NULL,
    version_no integer NOT NULL,
    status character varying(24) DEFAULT 'DRAFT'::character varying NOT NULL,
    preprocessing_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    config_hash character(64) NOT NULL,
    revision bigint DEFAULT 0 NOT NULL,
    frozen_at timestamp with time zone,
    created_by uuid NOT NULL,
    updated_by uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT input_scheme_hash_check CHECK ((config_hash ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT input_scheme_preprocess_object CHECK ((jsonb_typeof(preprocessing_jsonb) = 'object'::text)),
    CONSTRAINT input_scheme_status_check CHECK (((status)::text = ANY (ARRAY[('DRAFT'::character varying)::text, ('FROZEN'::character varying)::text, ('RETIRED'::character varying)::text]))),
    CONSTRAINT input_scheme_version_no_check CHECK ((version_no > 0))
);


--
-- Name: input_scheme_field; Type: TABLE; Schema: ai; Owner: -
--

CREATE TABLE ai.input_scheme_field (
    organization_id uuid NOT NULL,
    input_scheme_id uuid NOT NULL,
    input_field_version_id uuid NOT NULL,
    required boolean NOT NULL,
    ordinal integer NOT NULL,
    override_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    CONSTRAINT input_scheme_field_ordinal_check CHECK ((ordinal >= 0)),
    CONSTRAINT input_scheme_field_override_object CHECK ((jsonb_typeof(override_jsonb) = 'object'::text))
);


--
-- Name: material_dictionary_version; Type: TABLE; Schema: ai; Owner: -
--

CREATE TABLE ai.material_dictionary_version (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    dictionary_code character varying(160) NOT NULL,
    version_no integer NOT NULL,
    status character varying(24) NOT NULL,
    vocabulary_jsonb jsonb NOT NULL,
    encoder_jsonb jsonb NOT NULL,
    dictionary_hash character(64) NOT NULL,
    revision bigint DEFAULT 0 NOT NULL,
    frozen_at timestamp with time zone,
    created_by uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT material_dictionary_encoder_object CHECK ((jsonb_typeof(encoder_jsonb) = 'object'::text)),
    CONSTRAINT material_dictionary_hash_check CHECK ((dictionary_hash ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT material_dictionary_status_check CHECK (((status)::text = ANY (ARRAY[('DRAFT'::character varying)::text, ('FROZEN'::character varying)::text, ('RETIRED'::character varying)::text]))),
    CONSTRAINT material_dictionary_version_version_no_check CHECK ((version_no > 0)),
    CONSTRAINT material_dictionary_vocabulary_array CHECK ((jsonb_typeof(vocabulary_jsonb) = 'array'::text))
);


--
-- Name: model_feedback; Type: TABLE; Schema: ai; Owner: -
--

CREATE TABLE ai.model_feedback (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    model_version_id uuid NOT NULL,
    target_id uuid NOT NULL,
    experiment_id uuid NOT NULL,
    experiment_version_id uuid NOT NULL,
    prediction_record_id uuid,
    research_candidate_id uuid,
    predicted_result_jsonb jsonb NOT NULL,
    observed_result_jsonb jsonb NOT NULL,
    residual_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    status character varying(24) DEFAULT 'RECORDED'::character varying NOT NULL,
    content_hash character(64) NOT NULL,
    created_by uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    target_version_id uuid,
    source_mapping_version_id uuid,
    sample_revision_id uuid,
    comparison_status character varying(24),
    reason_code character varying(100),
    observation_refs_jsonb jsonb DEFAULT '[]'::jsonb NOT NULL,
    metrics_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    prediction_evidence_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    projection_event_id uuid,
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


--
-- Name: COLUMN model_feedback.prediction_evidence_jsonb; Type: COMMENT; Schema: ai; Owner: -
--

COMMENT ON COLUMN ai.model_feedback.prediction_evidence_jsonb IS 'Immutable prediction and model evidence compared with completed experiment facts';


--
-- Name: model_feedback_projection_receipt; Type: TABLE; Schema: ai; Owner: -
--

CREATE TABLE ai.model_feedback_projection_receipt (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    experiment_event_id uuid NOT NULL,
    experiment_id uuid NOT NULL,
    experiment_version_id uuid NOT NULL,
    event_type character varying(100) NOT NULL,
    payload_hash character(64) NOT NULL,
    status character varying(24) DEFAULT 'PROCESSING'::character varying NOT NULL,
    attempt_count integer DEFAULT 1 NOT NULL,
    feedback_count integer DEFAULT 0 NOT NULL,
    error_message text,
    processed_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT model_feedback_receipt_hash_check CHECK ((payload_hash ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT model_feedback_receipt_status_check CHECK (((status)::text = ANY (ARRAY[('PROCESSING'::character varying)::text, ('COMPLETED'::character varying)::text, ('FAILED'::character varying)::text, ('STALE'::character varying)::text])))
);


--
-- Name: model_release; Type: TABLE; Schema: ai; Owner: -
--

CREATE TABLE ai.model_release (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    target_id uuid NOT NULL,
    model_version_id uuid NOT NULL,
    previous_model_version_id uuid,
    action character varying(24) NOT NULL,
    expected_previous_revision bigint,
    reason text NOT NULL,
    actor_id uuid NOT NULL,
    request_id character varying(160) NOT NULL,
    idempotency_key character varying(200) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT model_release_action_check CHECK (((action)::text = ANY (ARRAY[('ACTIVATE'::character varying)::text, ('PAUSE'::character varying)::text, ('ROLLBACK'::character varying)::text, ('REPLACE'::character varying)::text])))
);


--
-- Name: model_version; Type: TABLE; Schema: ai; Owner: -
--

CREATE TABLE ai.model_version (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    target_id uuid NOT NULL,
    target_version_id uuid NOT NULL,
    input_scheme_id uuid NOT NULL,
    training_snapshot_id uuid,
    training_job_id uuid,
    model_artifact_id uuid,
    model_type character varying(80) NOT NULL,
    status character varying(24) DEFAULT 'CANDIDATE'::character varying NOT NULL,
    metrics_jsonb jsonb NOT NULL,
    applicability_domain_jsonb jsonb NOT NULL,
    model_card_jsonb jsonb NOT NULL,
    contract_version character varying(40) DEFAULT 'formula-model.v2'::character varying NOT NULL,
    model_hash character(64) NOT NULL,
    revision bigint DEFAULT 0 NOT NULL,
    created_by uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    version_no integer,
    data_nature character varying(24) DEFAULT 'REAL'::character varying NOT NULL,
    production_eligible boolean DEFAULT false NOT NULL,
    comparison_status character varying(32) DEFAULT 'NOT_COMPARABLE'::character varying NOT NULL,
    rejection_reasons_jsonb jsonb DEFAULT '[]'::jsonb NOT NULL,
    domain_policy_version_id uuid,
    workbench_run_id uuid,
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


--
-- Name: modeling_policy_version; Type: TABLE; Schema: ai; Owner: -
--

CREATE TABLE ai.modeling_policy_version (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    target_id uuid NOT NULL,
    version_no integer NOT NULL,
    status character varying(24) NOT NULL,
    qualification_jsonb jsonb NOT NULL,
    validation_jsonb jsonb NOT NULL,
    training_jsonb jsonb NOT NULL,
    policy_hash character(64) NOT NULL,
    published_at timestamp with time zone,
    created_by uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    kind character varying(24) DEFAULT 'TRAINING'::character varying NOT NULL,
    configuration_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    revision bigint DEFAULT 0 NOT NULL,
    published_by uuid,
    CONSTRAINT modeling_policy_configuration_object CHECK ((jsonb_typeof(configuration_jsonb) = 'object'::text)),
    CONSTRAINT modeling_policy_hash_check CHECK ((policy_hash ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT modeling_policy_kind_check CHECK (((kind)::text = ANY (ARRAY[('ELIGIBILITY'::character varying)::text, ('PREPROCESSING'::character varying)::text, ('TRAINING'::character varying)::text, ('DOMAIN'::character varying)::text, ('QUALITY'::character varying)::text]))),
    CONSTRAINT modeling_policy_qualification_object CHECK ((jsonb_typeof(qualification_jsonb) = 'object'::text)),
    CONSTRAINT modeling_policy_status_check CHECK (((status)::text = ANY (ARRAY[('DRAFT'::character varying)::text, ('PUBLISHED'::character varying)::text, ('RETIRED'::character varying)::text]))),
    CONSTRAINT modeling_policy_training_object CHECK ((jsonb_typeof(training_jsonb) = 'object'::text)),
    CONSTRAINT modeling_policy_validation_object CHECK ((jsonb_typeof(validation_jsonb) = 'object'::text)),
    CONSTRAINT modeling_policy_version_version_no_check CHECK ((version_no > 0))
);


--
-- Name: modeling_workbench_run; Type: TABLE; Schema: ai; Owner: -
--

CREATE TABLE ai.modeling_workbench_run (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    created_by uuid NOT NULL,
    dataset_id uuid NOT NULL,
    dataset_name character varying(240) NOT NULL,
    idempotency_key character varying(200) NOT NULL,
    request_hash character(64) NOT NULL,
    request_jsonb jsonb NOT NULL,
    selected_x_jsonb jsonb NOT NULL,
    selected_y_jsonb jsonb NOT NULL,
    status character varying(24) DEFAULT 'QUEUED'::character varying NOT NULL,
    progress smallint DEFAULT 0 NOT NULL,
    current_stage character varying(40) DEFAULT 'QUEUED'::character varying NOT NULL,
    result_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    error_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    model_artifact_key character varying(700),
    model_artifact_sha256 character(64),
    ops_job_id uuid,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    finished_at timestamp with time zone,
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


--
-- Name: operation_manual_chunk; Type: TABLE; Schema: ai; Owner: -
--

CREATE TABLE ai.operation_manual_chunk (
    manual_version character varying(160) NOT NULL,
    embedding_model character varying(160) NOT NULL,
    embedding_dimension integer NOT NULL,
    chunk_id character varying(160) NOT NULL,
    section_id character varying(120) NOT NULL,
    content text NOT NULL,
    content_hash character varying(64) NOT NULL,
    embedding public.vector NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT operation_manual_chunk_check CHECK ((public.vector_dims(embedding) = embedding_dimension))
);


--
-- Name: operation_manual_index; Type: TABLE; Schema: ai; Owner: -
--

CREATE TABLE ai.operation_manual_index (
    manual_version character varying(160) NOT NULL,
    embedding_model character varying(160) NOT NULL,
    embedding_dimension integer NOT NULL,
    status character varying(16) NOT NULL,
    chunk_count integer DEFAULT 0 NOT NULL,
    error_message character varying(500),
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT operation_manual_index_embedding_dimension_check CHECK ((embedding_dimension > 0)),
    CONSTRAINT operation_manual_index_status_check CHECK (((status)::text = ANY (ARRAY[('BUILDING'::character varying)::text, ('READY'::character varying)::text, ('FAILED'::character varying)::text])))
);


--
-- Name: prediction_record; Type: TABLE; Schema: ai; Owner: -
--

CREATE TABLE ai.prediction_record (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    prediction_type character varying(32) NOT NULL,
    idempotency_key character varying(200) NOT NULL,
    request_hash character(64) NOT NULL,
    request_jsonb jsonb NOT NULL,
    target_bindings_jsonb jsonb NOT NULL,
    result_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    error_jsonb jsonb,
    created_by uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    completed_at timestamp with time zone,
    request_id character varying(160) NOT NULL,
    contract_version character varying(40) DEFAULT 'ai-rnd.v1'::character varying NOT NULL,
    execution_status character varying(24) NOT NULL,
    outcome_status character varying(24),
    target_results_jsonb jsonb DEFAULT '[]'::jsonb NOT NULL,
    terminal_http_status integer,
    terminal_response_jsonb jsonb,
    duration_ms bigint,
    heartbeat_at timestamp with time zone,
    revision bigint DEFAULT 0 NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
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


--
-- Name: prediction_target; Type: TABLE; Schema: ai; Owner: -
--

CREATE TABLE ai.prediction_target (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    target_code character varying(160) NOT NULL,
    name character varying(240) NOT NULL,
    performance_project character varying(160) NOT NULL,
    value_type character varying(24) NOT NULL,
    status character varying(24) DEFAULT 'DRAFT'::character varying NOT NULL,
    current_version_id uuid,
    current_input_scheme_id uuid,
    revision bigint DEFAULT 0 NOT NULL,
    created_by uuid NOT NULL,
    updated_by uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    current_quality_policy_version_id uuid,
    current_domain_policy_version_id uuid,
    CONSTRAINT prediction_target_status_check CHECK (((status)::text = ANY (ARRAY[('DRAFT'::character varying)::text, ('ACTIVE'::character varying)::text, ('PAUSED'::character varying)::text, ('RETIRED'::character varying)::text]))),
    CONSTRAINT prediction_target_value_type_check CHECK (((value_type)::text = ANY (ARRAY[('CONTINUOUS'::character varying)::text, ('ORDINAL'::character varying)::text, ('BINARY'::character varying)::text, ('CATEGORICAL'::character varying)::text])))
);


--
-- Name: research_candidate_history_observation_v2; Type: TABLE; Schema: ai; Owner: -
--

CREATE TABLE ai.research_candidate_history_observation_v2 (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    research_run_id uuid NOT NULL,
    research_candidate_id uuid NOT NULL,
    field_code character varying(240) NOT NULL,
    observed_value_jsonb jsonb NOT NULL,
    created_by uuid NOT NULL,
    updated_by uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT research_candidate_history_observation_value_check CHECK ((jsonb_typeof(observed_value_jsonb) = 'number'::text))
);


--
-- Name: research_candidate_observation_v2; Type: TABLE; Schema: ai; Owner: -
--

CREATE TABLE ai.research_candidate_observation_v2 (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    research_run_id uuid NOT NULL,
    research_candidate_id uuid NOT NULL,
    target_id uuid NOT NULL,
    observed_result_jsonb jsonb NOT NULL,
    created_by uuid NOT NULL,
    updated_by uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT research_candidate_observation_value_check CHECK ((jsonb_typeof(observed_result_jsonb) = ANY (ARRAY['number'::text, 'string'::text])))
);


--
-- Name: research_candidate_v2; Type: TABLE; Schema: ai; Owner: -
--

CREATE TABLE ai.research_candidate_v2 (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    research_run_id uuid NOT NULL,
    candidate_no integer NOT NULL,
    title character varying(240) NOT NULL,
    formula_jsonb jsonb NOT NULL,
    process_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    target_results_jsonb jsonb NOT NULL,
    rule_check_jsonb jsonb NOT NULL,
    applicability_jsonb jsonb NOT NULL,
    evidence_jsonb jsonb NOT NULL,
    score numeric(24,12),
    content_hash character(64) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    target_total numeric(18,8),
    fixed_inputs_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    search_space_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    target_gate_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    quality_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    preference_score numeric(24,12),
    diversity_score numeric(24,12),
    search_strategy character varying(80) DEFAULT 'CONTROLLED_POOL'::character varying NOT NULL,
    baseline_distance numeric(24,12),
    change_summary_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    strategy_evidence_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    risk_flags_jsonb jsonb DEFAULT '[]'::jsonb NOT NULL,
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


--
-- Name: research_experiment_link_v2; Type: TABLE; Schema: ai; Owner: -
--

CREATE TABLE ai.research_experiment_link_v2 (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    research_run_id uuid NOT NULL,
    research_candidate_id uuid NOT NULL,
    experiment_id uuid NOT NULL,
    experiment_version_id uuid NOT NULL,
    idempotency_key character varying(200) NOT NULL,
    created_by uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    creation_intent_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    creation_intent_hash character(64),
    source_evidence_id uuid,
    CONSTRAINT research_experiment_link_v2_intent_hash_check CHECK (((creation_intent_hash IS NULL) OR (creation_intent_hash ~ '^[0-9a-f]{64}$'::text))),
    CONSTRAINT research_experiment_link_v2_intent_object CHECK ((jsonb_typeof(creation_intent_jsonb) = 'object'::text))
);


--
-- Name: research_run_v2; Type: TABLE; Schema: ai; Owner: -
--

CREATE TABLE ai.research_run_v2 (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    run_type character varying(32) NOT NULL,
    status character varying(24) DEFAULT 'QUEUED'::character varying NOT NULL,
    idempotency_key character varying(200) NOT NULL,
    request_hash character(64) NOT NULL,
    request_jsonb jsonb NOT NULL,
    model_bindings_jsonb jsonb NOT NULL,
    result_summary_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    error_jsonb jsonb,
    revision bigint DEFAULT 0 NOT NULL,
    created_by uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    started_at timestamp with time zone,
    finished_at timestamp with time zone,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    request_id character varying(160) NOT NULL,
    contract_version character varying(40) DEFAULT 'ai-rnd.v1'::character varying NOT NULL,
    ops_job_id uuid,
    seed bigint,
    search_engine_version character varying(80) DEFAULT 'formula-search.v2'::character varying NOT NULL,
    search_config_jsonb jsonb DEFAULT '{"batchSize": 64, "initialDesign": 64, "maxEvaluations": 512, "diversityWeight": 0.15}'::jsonb NOT NULL,
    progress smallint DEFAULT 0 NOT NULL,
    current_stage character varying(80),
    heartbeat_at timestamp with time zone,
    fencing_generation bigint DEFAULT 0 NOT NULL,
    execution_status character varying(24),
    outcome_status character varying(24),
    baseline_type character varying(40),
    baseline_entity_id uuid,
    baseline_version_id uuid,
    baseline_content_hash character(64),
    baseline_snapshot_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    optimization_config_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
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


--
-- Name: COLUMN research_run_v2.execution_status; Type: COMMENT; Schema: ai; Owner: -
--

COMMENT ON COLUMN ai.research_run_v2.execution_status IS 'Request execution lifecycle; independent from business outcome';


--
-- Name: COLUMN research_run_v2.outcome_status; Type: COMMENT; Schema: ai; Owner: -
--

COMMENT ON COLUMN ai.research_run_v2.outcome_status IS 'Candidate outcome, populated only after successful execution';


--
-- Name: COLUMN research_run_v2.baseline_snapshot_jsonb; Type: COMMENT; Schema: ai; Owner: -
--

COMMENT ON COLUMN ai.research_run_v2.baseline_snapshot_jsonb IS 'Immutable baseline facts used by an R09 optimization run';


--
-- Name: result; Type: TABLE; Schema: ai; Owner: -
--

CREATE TABLE ai.result (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    employee_role character varying(80) NOT NULL,
    rule_code character varying(120) NOT NULL,
    rule_version character varying(80) NOT NULL,
    result_type character varying(32) NOT NULL,
    severity character varying(16) NOT NULL,
    status character varying(24) NOT NULL,
    subject_type character varying(80) NOT NULL,
    subject_id uuid NOT NULL,
    discriminator character varying(160) DEFAULT ''::character varying NOT NULL,
    fact_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    inference_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    recommendation_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    first_detected_at timestamp with time zone NOT NULL,
    last_detected_at timestamp with time zone NOT NULL,
    deferred_until timestamp with time zone,
    resolved_at timestamp with time zone,
    version bigint DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT result_result_type_check CHECK (((result_type)::text = ANY (ARRAY[('REMINDER'::character varying)::text, ('RISK'::character varying)::text, ('BLOCKER'::character varying)::text, ('SUMMARY'::character varying)::text, ('INSIGHT'::character varying)::text, ('RECOMMENDATION'::character varying)::text]))),
    CONSTRAINT result_severity_check CHECK (((severity)::text = ANY (ARRAY[('LOW'::character varying)::text, ('MEDIUM'::character varying)::text, ('HIGH'::character varying)::text]))),
    CONSTRAINT result_status_check CHECK (((status)::text = ANY (ARRAY[('OPEN'::character varying)::text, ('ACKNOWLEDGED'::character varying)::text, ('DEFERRED'::character varying)::text, ('RESOLVED'::character varying)::text, ('FALSE_POSITIVE'::character varying)::text]))),
    CONSTRAINT result_version_check CHECK ((version >= 0))
);


--
-- Name: TABLE result; Type: COMMENT; Schema: ai; Owner: -
--

COMMENT ON TABLE ai.result IS 'Shared structured AI work result. Business modules remain the source of formal facts.';


--
-- Name: result_evidence; Type: TABLE; Schema: ai; Owner: -
--

CREATE TABLE ai.result_evidence (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    result_id uuid NOT NULL,
    business_object_type character varying(80) NOT NULL,
    business_object_id uuid NOT NULL,
    field_name character varying(160),
    field_value_summary text DEFAULT ''::text NOT NULL,
    data_updated_at timestamp with time zone,
    source_uri character varying(1000),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    occurrence_id uuid,
    is_current boolean DEFAULT true NOT NULL
);


--
-- Name: COLUMN result_evidence.occurrence_id; Type: COMMENT; Schema: ai; Owner: -
--

COMMENT ON COLUMN ai.result_evidence.occurrence_id IS 'Occurrence whose immutable evidence snapshot produced this evidence row.';


--
-- Name: result_feedback; Type: TABLE; Schema: ai; Owner: -
--

CREATE TABLE ai.result_feedback (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    result_id uuid NOT NULL,
    feedback_type character varying(24) NOT NULL,
    actor_id uuid NOT NULL,
    reason text DEFAULT ''::text NOT NULL,
    result_version bigint NOT NULL,
    deferred_until timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT result_feedback_feedback_type_check CHECK (((feedback_type)::text = ANY (ARRAY[('ACKNOWLEDGED'::character varying)::text, ('DEFERRED'::character varying)::text, ('RESOLVED'::character varying)::text, ('FALSE_POSITIVE'::character varying)::text]))),
    CONSTRAINT result_feedback_result_version_check CHECK ((result_version >= 0))
);


--
-- Name: result_occurrence; Type: TABLE; Schema: ai; Owner: -
--

CREATE TABLE ai.result_occurrence (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    result_id uuid NOT NULL,
    occurrence_type character varying(24) NOT NULL,
    severity character varying(16) NOT NULL,
    status_before character varying(24),
    status_after character varying(24) NOT NULL,
    fact_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    context_snapshot_id uuid,
    detected_at timestamp with time zone NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT result_occurrence_occurrence_type_check CHECK (((occurrence_type)::text = ANY (ARRAY[('CREATED'::character varying)::text, ('DETECTED'::character varying)::text, ('ESCALATED'::character varying)::text, ('REOPENED'::character varying)::text, ('RESOLVED'::character varying)::text, ('ACKNOWLEDGED'::character varying)::text, ('DEFERRED'::character varying)::text, ('FALSE_POSITIVE'::character varying)::text]))),
    CONSTRAINT result_occurrence_severity_check CHECK (((severity)::text = ANY (ARRAY[('LOW'::character varying)::text, ('MEDIUM'::character varying)::text, ('HIGH'::character varying)::text]))),
    CONSTRAINT result_occurrence_status_after_check CHECK (((status_after)::text = ANY (ARRAY[('OPEN'::character varying)::text, ('ACKNOWLEDGED'::character varying)::text, ('DEFERRED'::character varying)::text, ('RESOLVED'::character varying)::text, ('FALSE_POSITIVE'::character varying)::text])))
);


--
-- Name: rule_definition; Type: TABLE; Schema: ai; Owner: -
--

CREATE TABLE ai.rule_definition (
    rule_code character varying(120) NOT NULL,
    rule_name character varying(240) NOT NULL,
    business_object character varying(80) NOT NULL,
    trigger_types_jsonb jsonb DEFAULT '[]'::jsonb NOT NULL,
    condition_definition_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    context_profile character varying(120),
    skill_codes_jsonb jsonb DEFAULT '[]'::jsonb NOT NULL,
    result_type character varying(32) NOT NULL,
    default_severity character varying(16) NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    organization_id uuid NOT NULL,
    CONSTRAINT rule_definition_default_severity_check CHECK (((default_severity)::text = ANY (ARRAY[('LOW'::character varying)::text, ('MEDIUM'::character varying)::text, ('HIGH'::character varying)::text]))),
    CONSTRAINT rule_definition_result_type_check CHECK (((result_type)::text = ANY (ARRAY[('REMINDER'::character varying)::text, ('RISK'::character varying)::text, ('BLOCKER'::character varying)::text, ('SUMMARY'::character varying)::text, ('RECOMMENDATION'::character varying)::text]))),
    CONSTRAINT rule_definition_version_check CHECK ((version >= 0))
);


--
-- Name: rule_version; Type: TABLE; Schema: ai; Owner: -
--

CREATE TABLE ai.rule_version (
    id uuid NOT NULL,
    rule_code character varying(120) NOT NULL,
    version_no integer NOT NULL,
    rule_name character varying(240) NOT NULL,
    business_object character varying(80) NOT NULL,
    trigger_types_jsonb jsonb DEFAULT '[]'::jsonb NOT NULL,
    condition_definition_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    context_profile character varying(120),
    skill_codes_jsonb jsonb DEFAULT '[]'::jsonb NOT NULL,
    result_type character varying(32) NOT NULL,
    default_severity character varying(16) NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    created_by uuid,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    organization_id uuid NOT NULL,
    status character varying(16) DEFAULT 'DRAFT'::character varying NOT NULL,
    change_note text DEFAULT ''::text NOT NULL,
    effective_from timestamp with time zone,
    effective_to timestamp with time zone,
    CONSTRAINT ck_ai_rule_version_effective_window CHECK (((effective_to IS NULL) OR (effective_from IS NULL) OR (effective_to > effective_from))),
    CONSTRAINT ck_ai_rule_version_status CHECK (((status)::text = ANY (ARRAY[('DRAFT'::character varying)::text, ('PUBLISHED'::character varying)::text, ('RETIRED'::character varying)::text]))),
    CONSTRAINT rule_version_default_severity_check CHECK (((default_severity)::text = ANY (ARRAY[('LOW'::character varying)::text, ('MEDIUM'::character varying)::text, ('HIGH'::character varying)::text]))),
    CONSTRAINT rule_version_result_type_check CHECK (((result_type)::text = ANY (ARRAY[('REMINDER'::character varying)::text, ('RISK'::character varying)::text, ('BLOCKER'::character varying)::text, ('SUMMARY'::character varying)::text, ('RECOMMENDATION'::character varying)::text]))),
    CONSTRAINT rule_version_version_no_check CHECK ((version_no > 0))
);


--
-- Name: COLUMN rule_version.status; Type: COMMENT; Schema: ai; Owner: -
--

COMMENT ON COLUMN ai.rule_version.status IS 'DRAFT, PUBLISHED, or RETIRED; published rule content is immutable';


--
-- Name: COLUMN rule_version.effective_from; Type: COMMENT; Schema: ai; Owner: -
--

COMMENT ON COLUMN ai.rule_version.effective_from IS 'Inclusive instant from which this published version may be resolved';


--
-- Name: COLUMN rule_version.effective_to; Type: COMMENT; Schema: ai; Owner: -
--

COMMENT ON COLUMN ai.rule_version.effective_to IS 'Exclusive instant after which this version is no longer current';


--
-- Name: sample_identity_issue; Type: TABLE; Schema: ai; Owner: -
--

CREATE TABLE ai.sample_identity_issue (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    training_sample_id uuid,
    left_sample_source_id uuid NOT NULL,
    right_sample_source_id uuid NOT NULL,
    issue_type character varying(40) NOT NULL,
    status character varying(24) DEFAULT 'OPEN'::character varying NOT NULL,
    evidence_jsonb jsonb NOT NULL,
    resolution_jsonb jsonb,
    revision bigint DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    resolved_at timestamp with time zone,
    resolved_by uuid,
    CONSTRAINT identity_issue_distinct_sources CHECK ((left_sample_source_id <> right_sample_source_id)),
    CONSTRAINT identity_issue_evidence_object CHECK ((jsonb_typeof(evidence_jsonb) = 'object'::text)),
    CONSTRAINT identity_issue_resolution_object CHECK (((resolution_jsonb IS NULL) OR (jsonb_typeof(resolution_jsonb) = 'object'::text))),
    CONSTRAINT identity_issue_status_check CHECK (((status)::text = ANY (ARRAY[('OPEN'::character varying)::text, ('RESOLVED'::character varying)::text, ('DISMISSED'::character varying)::text])))
);


--
-- Name: sample_revision; Type: TABLE; Schema: ai; Owner: -
--

CREATE TABLE ai.sample_revision (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    training_sample_id uuid NOT NULL,
    sample_source_id uuid NOT NULL,
    revision_no integer NOT NULL,
    composition_jsonb jsonb NOT NULL,
    process_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    conditions_jsonb jsonb NOT NULL,
    observations_jsonb jsonb NOT NULL,
    facts_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    permission_scope_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    source_coordinates_jsonb jsonb NOT NULL,
    fact_hash character(64) NOT NULL,
    status character varying(24) DEFAULT 'CURRENT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
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


--
-- Name: sample_source; Type: TABLE; Schema: ai; Owner: -
--

CREATE TABLE ai.sample_source (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    training_sample_id uuid NOT NULL,
    source_type character varying(24) NOT NULL,
    confirmed_submission_item_id uuid,
    experiment_version_id uuid,
    source_business_key character varying(320) NOT NULL,
    sample_boundary_id character varying(240) NOT NULL,
    logical_sample_key character varying(320) NOT NULL,
    source_group_keys_jsonb jsonb NOT NULL,
    physical_identity_hash character(64) NOT NULL,
    source_version character varying(160) NOT NULL,
    source_sequence bigint NOT NULL,
    content_hash character(64) NOT NULL,
    priority smallint NOT NULL,
    status character varying(24) DEFAULT 'CURRENT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT sample_source_groups_array CHECK (((jsonb_typeof(source_group_keys_jsonb) = 'array'::text) AND (jsonb_array_length(source_group_keys_jsonb) > 0))),
    CONSTRAINT sample_source_hash_check CHECK ((content_hash ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT sample_source_physical_hash_check CHECK ((physical_identity_hash ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT sample_source_reference_check CHECK (((((source_type)::text = 'DATA_CENTER'::text) AND (confirmed_submission_item_id IS NOT NULL) AND (experiment_version_id IS NULL)) OR (((source_type)::text = 'EXPERIMENT'::text) AND (experiment_version_id IS NOT NULL) AND (confirmed_submission_item_id IS NULL)))),
    CONSTRAINT sample_source_status_check CHECK (((status)::text = ANY (ARRAY[('CURRENT'::character varying)::text, ('SUPERSEDED'::character varying)::text, ('INVALIDATED'::character varying)::text, ('TAKEN_OVER'::character varying)::text]))),
    CONSTRAINT sample_source_type_check CHECK (((source_type)::text = ANY (ARRAY[('DATA_CENTER'::character varying)::text, ('EXPERIMENT'::character varying)::text])))
);


--
-- Name: signal; Type: TABLE; Schema: ai; Owner: -
--

CREATE TABLE ai.signal (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    trigger_type character varying(16) NOT NULL,
    subject_type character varying(80) NOT NULL,
    subject_id uuid NOT NULL,
    deduplication_key character varying(300) NOT NULL,
    payload_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    occurred_at timestamp with time zone NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT signal_trigger_type_check CHECK (((trigger_type)::text = ANY (ARRAY[('TIME'::character varying)::text, ('EVENT'::character varying)::text, ('STATE'::character varying)::text, ('CONTEXT'::character varying)::text, ('PERIODIC'::character varying)::text])))
);


--
-- Name: source_mapping_version; Type: TABLE; Schema: ai; Owner: -
--

CREATE TABLE ai.source_mapping_version (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    target_id uuid NOT NULL,
    target_version_id uuid NOT NULL,
    source_type character varying(32) NOT NULL,
    version_no integer NOT NULL,
    status character varying(24) NOT NULL,
    mapping_jsonb jsonb NOT NULL,
    mapping_hash character(64) NOT NULL,
    published_at timestamp with time zone,
    created_by uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT source_mapping_hash_check CHECK ((mapping_hash ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT source_mapping_json_object CHECK ((jsonb_typeof(mapping_jsonb) = 'object'::text)),
    CONSTRAINT source_mapping_status_check CHECK (((status)::text = ANY (ARRAY[('DRAFT'::character varying)::text, ('PUBLISHED'::character varying)::text, ('RETIRED'::character varying)::text]))),
    CONSTRAINT source_mapping_type_check CHECK (((source_type)::text = ANY (ARRAY[('DATA_CENTER'::character varying)::text, ('EXPERIMENT'::character varying)::text]))),
    CONSTRAINT source_mapping_version_version_no_check CHECK ((version_no > 0))
);


--
-- Name: data_record; Type: TABLE; Schema: data; Owner: -
--

CREATE TABLE data.data_record (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    import_job_id uuid NOT NULL,
    record_key character varying(260) NOT NULL,
    record_index integer NOT NULL,
    sheet_id character varying(160),
    sheet_name character varying(260),
    source_row_number integer,
    raw_data_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    normalized_data_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    corrected_data_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    effective_data_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    quality_status character varying(24) DEFAULT 'VALID'::character varying NOT NULL,
    synthetic_key boolean DEFAULT false NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT data_record_quality_status_check CHECK (((quality_status)::text = ANY (ARRAY[('VALID'::character varying)::text, ('WARNING'::character varying)::text, ('BLOCKED'::character varying)::text])))
);


--
-- Name: data_value; Type: TABLE; Schema: data; Owner: -
--

CREATE TABLE data.data_value (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    record_id uuid NOT NULL,
    field_code character varying(160) NOT NULL,
    data_path character varying(400),
    value_jsonb jsonb DEFAULT 'null'::jsonb NOT NULL,
    value_text text,
    normalized_unit character varying(64),
    source_anchor_id uuid,
    training_eligible boolean DEFAULT true NOT NULL,
    exclusion_reason character varying(200),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    binding_id character varying(120),
    value_path character varying(500),
    label_path character varying(800),
    rag_eligible boolean DEFAULT true NOT NULL,
    value_source character varying(24) DEFAULT 'INPUT'::character varying NOT NULL,
    calculation_source character varying(24),
    calculation_status character varying(24),
    calculation_trust_status character varying(32),
    CONSTRAINT data_value_calculation_source_check CHECK (((calculation_source IS NULL) OR ((calculation_source)::text = ANY (ARRAY[('RECALCULATED'::character varying)::text, ('CACHED'::character varying)::text, ('MISSING'::character varying)::text])))),
    CONSTRAINT data_value_calculation_status_check CHECK (((calculation_status IS NULL) OR ((calculation_status)::text = ANY (ARRAY[('VALID'::character varying)::text, ('STALE_POSSIBLE'::character varying)::text, ('FAILED'::character varying)::text])))),
    CONSTRAINT data_value_calculation_trust_status_check CHECK (((calculation_trust_status IS NULL) OR ((calculation_trust_status)::text = ANY (ARRAY[('NOT_APPLICABLE'::character varying)::text, ('TRUSTED_RECALCULATED'::character varying)::text, ('UNVERIFIED_CACHE'::character varying)::text, ('MISSING_RESULT'::character varying)::text])))),
    CONSTRAINT data_value_value_source_check CHECK (((value_source)::text = ANY (ARRAY[('INPUT'::character varying)::text, ('FORMULA'::character varying)::text, ('DERIVED'::character varying)::text, ('STATIC'::character varying)::text])))
);


--
-- Name: import_experiment_link; Type: TABLE; Schema: data; Owner: -
--

CREATE TABLE data.import_experiment_link (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    import_job_id uuid NOT NULL,
    assembly_key character varying(240) NOT NULL,
    parent_assembly_key character varying(240),
    source_identity character varying(260),
    source_identity_type character varying(32),
    source_record_keys_jsonb jsonb DEFAULT '[]'::jsonb NOT NULL,
    shared_context_record_keys_jsonb jsonb DEFAULT '[]'::jsonb NOT NULL,
    plan_hash character(64) NOT NULL,
    content_hash character(64) NOT NULL,
    status character varying(32) NOT NULL,
    resolution_action character varying(32),
    resolution_reason character varying(500),
    conflicts_jsonb jsonb DEFAULT '[]'::jsonb NOT NULL,
    warnings_jsonb jsonb DEFAULT '[]'::jsonb NOT NULL,
    experiment_id uuid,
    experiment_version_id uuid,
    experiment_no character varying(80),
    error_message text,
    created_by uuid NOT NULL,
    resolved_by uuid,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    resolved_at timestamp with time zone,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    recognition_job_id uuid NOT NULL,
    confirmed_submission_id uuid,
    experiment_boundary_id character varying(240) NOT NULL,
    sample_boundary_ids_jsonb jsonb DEFAULT '[]'::jsonb NOT NULL,
    logical_sample_keys_jsonb jsonb DEFAULT '[]'::jsonb NOT NULL,
    source_group_keys_jsonb jsonb DEFAULT '[]'::jsonb NOT NULL,
    source_snapshot_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    source_updated boolean DEFAULT false NOT NULL,
    CONSTRAINT import_experiment_link_logical_samples_array CHECK ((jsonb_typeof(logical_sample_keys_jsonb) = 'array'::text)),
    CONSTRAINT import_experiment_link_resolution_check CHECK (((resolution_action IS NULL) OR ((resolution_action)::text = 'SPLIT_BY_RECORD'::text))),
    CONSTRAINT import_experiment_link_sample_boundaries_array CHECK ((jsonb_typeof(sample_boundary_ids_jsonb) = 'array'::text)),
    CONSTRAINT import_experiment_link_source_groups_array CHECK ((jsonb_typeof(source_group_keys_jsonb) = 'array'::text)),
    CONSTRAINT import_experiment_link_source_snapshot_object CHECK ((jsonb_typeof(source_snapshot_jsonb) = 'object'::text)),
    CONSTRAINT import_experiment_link_status_check CHECK (((status)::text = ANY (ARRAY[('READY'::character varying)::text, ('NEEDS_REVIEW'::character varying)::text, ('RUNNING'::character varying)::text, ('SYNCED'::character varying)::text, ('ALREADY_CREATED'::character varying)::text, ('BLOCKED'::character varying)::text, ('FAILED'::character varying)::text, ('SKIPPED'::character varying)::text])))
);


--
-- Name: TABLE import_experiment_link; Type: COMMENT; Schema: data; Owner: -
--

COMMENT ON TABLE data.import_experiment_link IS 'Auditable and idempotent link between committed data-center source records and generated ELN V2 drafts';


--
-- Name: COLUMN import_experiment_link.experiment_boundary_id; Type: COMMENT; Schema: data; Owner: -
--

COMMENT ON COLUMN data.import_experiment_link.experiment_boundary_id IS 'Confirmed experimentBoundaryId; unique with organization/import job through the existing assembly_key key.';


--
-- Name: import_job; Type: TABLE; Schema: data; Owner: -
--

CREATE TABLE data.import_job (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    source_file_id uuid NOT NULL,
    source_sha256 character(64) NOT NULL,
    source_file_name character varying(260) NOT NULL,
    source_format character varying(16) NOT NULL,
    template_version_id uuid,
    status character varying(32) NOT NULL,
    progress smallint DEFAULT 0 NOT NULL,
    current_stage character varying(120),
    parser_version character varying(80),
    duplicate_override boolean DEFAULT false NOT NULL,
    error_message text,
    created_by uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    completed_at timestamp with time zone,
    category_id uuid,
    import_contract_version integer,
    contract_hash character(64),
    source_file_hash character(64),
    compatibility_status character varying(24) DEFAULT 'LEGACY'::character varying NOT NULL,
    compatibility_report_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    import_purpose character varying(32) DEFAULT 'DATA_ONLY'::character varying NOT NULL,
    target_experiment_category_id uuid,
    source_owner character varying(24) DEFAULT 'DATA_CENTER'::character varying NOT NULL,
    recognition_mode character varying(32) DEFAULT 'TEMPLATE_GUIDED'::character varying NOT NULL,
    recognition_workspace_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    experiment_boundaries_jsonb jsonb DEFAULT '[]'::jsonb NOT NULL,
    recognition_revision bigint DEFAULT 0 NOT NULL,
    recognition_profile_id uuid,
    visibility character varying(24) DEFAULT 'ALL'::character varying NOT NULL,
    source_sequence bigint DEFAULT 1 NOT NULL,
    finalized_at timestamp with time zone,
    hidden_at timestamp with time zone,
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


--
-- Name: COLUMN import_job.import_purpose; Type: COMMENT; Schema: data; Owner: -
--

COMMENT ON COLUMN data.import_job.import_purpose IS 'DATA_ONLY keeps committed source facts only; EXPERIMENT_DRAFT requests a later explicit ELN draft assembly';


--
-- Name: COLUMN import_job.target_experiment_category_id; Type: COMMENT; Schema: data; Owner: -
--

COMMENT ON COLUMN data.import_job.target_experiment_category_id IS 'Opaque RND category identity selected by the user; the data module does not own or join the RND category table';


--
-- Name: COLUMN import_job.hidden_at; Type: COMMENT; Schema: data; Owner: -
--

COMMENT ON COLUMN data.import_job.hidden_at IS '实验导入历史软隐藏时间；不删除来源文件、追溯数据或已生成实验';


--
-- Name: import_mapping; Type: TABLE; Schema: data; Owner: -
--

CREATE TABLE data.import_mapping (
    id uuid NOT NULL,
    import_job_id uuid NOT NULL,
    import_sheet_id uuid,
    source_column character varying(32) NOT NULL,
    source_header character varying(260),
    field_code character varying(160),
    field_name character varying(200),
    action character varying(24) NOT NULL,
    value_type character varying(32),
    source_unit character varying(64),
    standard_unit character varying(64),
    mapping_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    status character varying(24) DEFAULT 'PENDING'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT import_mapping_action_check CHECK (((action)::text = ANY (ARRAY[('MAP'::character varying)::text, ('IGNORE'::character varying)::text, ('REQUEST_FIELD'::character varying)::text, ('PENDING'::character varying)::text]))),
    CONSTRAINT import_mapping_status_check CHECK (((status)::text = ANY (ARRAY[('PENDING'::character varying)::text, ('MATCHED'::character varying)::text, ('CONFIRMED'::character varying)::text, ('IGNORED'::character varying)::text, ('REQUESTED'::character varying)::text])))
);


--
-- Name: publication; Type: TABLE; Schema: data; Owner: -
--

CREATE TABLE data.publication (
    organization_id uuid NOT NULL,
    import_job_id uuid NOT NULL,
    status character varying(24) DEFAULT 'DRAFT'::character varying NOT NULL,
    confirmed_snapshot_id uuid,
    published_snapshot_id uuid,
    legacy_published boolean DEFAULT false NOT NULL,
    published_enabled boolean DEFAULT true NOT NULL,
    reviewer_id uuid,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT publication_status_check CHECK (((status)::text = ANY ((ARRAY['DRAFT'::character varying, 'CONFIRMED'::character varying, 'PENDING_REVIEW'::character varying, 'RETURNED'::character varying, 'APPROVED'::character varying, 'PUBLISHED'::character varying, 'DISABLED'::character varying])::text[])))
);


--
-- Name: published_mapping; Type: TABLE; Schema: data; Owner: -
--

CREATE TABLE data.published_mapping (
    id uuid NOT NULL,
    import_job_id uuid NOT NULL,
    import_sheet_id uuid,
    source_column character varying(32) NOT NULL,
    source_header character varying(260),
    field_code character varying(160),
    field_name character varying(200),
    action character varying(24) NOT NULL,
    value_type character varying(32),
    source_unit character varying(64),
    standard_unit character varying(64),
    mapping_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    status character varying(24) DEFAULT 'PENDING'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: record_governance; Type: TABLE; Schema: data; Owner: -
--

CREATE TABLE data.record_governance (
    organization_id uuid NOT NULL,
    import_job_id uuid NOT NULL,
    record_id uuid NOT NULL,
    display_name character varying(200),
    status character varying(16) DEFAULT 'ACTIVE'::character varying NOT NULL,
    reason text,
    updated_by uuid NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT record_governance_status_check CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'DISABLED'::character varying])::text[])))
);


--
-- Name: source_anchor; Type: TABLE; Schema: data; Owner: -
--

CREATE TABLE data.source_anchor (
    id uuid NOT NULL,
    import_job_id uuid NOT NULL,
    field_code character varying(160),
    file_id uuid NOT NULL,
    sheet_id character varying(160),
    sheet_name character varying(260),
    row_number integer,
    column_number integer,
    column_name character varying(32),
    cell_address character varying(80),
    raw_value_jsonb jsonb,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    binding_id character varying(160),
    value_path text,
    label_path text,
    value_source character varying(40),
    value_status character varying(40),
    record_id uuid NOT NULL
);


--
-- Name: project; Type: TABLE; Schema: mdm; Owner: -
--

CREATE TABLE mdm.project (
    id uuid NOT NULL,
    project_code character varying(64) NOT NULL,
    name character varying(300) NOT NULL,
    partner_id uuid,
    partner_name character varying(300),
    owner character varying(100),
    start_date date,
    end_date date,
    priority character varying(20) DEFAULT 'MEDIUM'::character varying NOT NULL,
    status character varying(30) DEFAULT 'PENDING'::character varying NOT NULL,
    team_size integer DEFAULT 0 NOT NULL,
    background text,
    deleted boolean DEFAULT false NOT NULL,
    custom_fields jsonb DEFAULT '{}'::jsonb NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    created_by character varying(100) NOT NULL,
    updated_by character varying(100) NOT NULL,
    team_members jsonb DEFAULT '[]'::jsonb NOT NULL,
    organization_id uuid NOT NULL,
    CONSTRAINT project_priority_check CHECK (((priority)::text = ANY (ARRAY[('HIGH'::character varying)::text, ('MEDIUM'::character varying)::text, ('LOW'::character varying)::text]))),
    CONSTRAINT project_status_check CHECK (((status)::text = ANY (ARRAY[('NOT_STARTED'::character varying)::text, ('IN_PROGRESS'::character varying)::text, ('PAUSED'::character varying)::text, ('COMPLETED'::character varying)::text, ('CANCELLED'::character varying)::text]))),
    CONSTRAINT project_team_size_check CHECK ((team_size >= 0))
);


--
-- Name: COLUMN project.status; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON COLUMN mdm.project.status IS '状态：NOT_STARTED/IN_PROGRESS/PAUSED/COMPLETED/CANCELLED';


--
-- Name: project_stage; Type: TABLE; Schema: mdm; Owner: -
--

CREATE TABLE mdm.project_stage (
    id uuid NOT NULL,
    project_id uuid NOT NULL,
    name character varying(120) NOT NULL,
    order_no integer NOT NULL,
    status character varying(30) DEFAULT 'PENDING'::character varying NOT NULL,
    owner character varying(100),
    description text,
    planned_start date,
    planned_end date,
    actual_start timestamp with time zone,
    actual_end timestamp with time zone,
    version bigint DEFAULT 0 NOT NULL,
    deleted boolean DEFAULT false NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    created_by character varying(100) NOT NULL,
    updated_by character varying(100) NOT NULL,
    deleted_at timestamp with time zone,
    deleted_by character varying(100),
    stage_code character varying(40),
    CONSTRAINT ck_project_stage_planned_dates CHECK (((planned_end IS NULL) OR (planned_start IS NULL) OR (planned_end >= planned_start))),
    CONSTRAINT project_stage_order_no_check CHECK ((order_no > 0)),
    CONSTRAINT project_stage_status_check CHECK (((status)::text = ANY (ARRAY[('NOT_STARTED'::character varying)::text, ('PENDING'::character varying)::text, ('IN_PROGRESS'::character varying)::text, ('COMPLETED'::character varying)::text])))
);


--
-- Name: project_task; Type: TABLE; Schema: mdm; Owner: -
--

CREATE TABLE mdm.project_task (
    id uuid NOT NULL,
    task_code character varying(40) NOT NULL,
    project_id uuid NOT NULL,
    stage_id uuid NOT NULL,
    name character varying(300) NOT NULL,
    owner character varying(100),
    planned_date date,
    status character varying(30) DEFAULT 'PENDING'::character varying NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    deleted boolean DEFAULT false NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(100) NOT NULL,
    updated_by character varying(100) NOT NULL,
    priority character varying(20) DEFAULT 'MEDIUM'::character varying NOT NULL
);


--
-- Name: experiment; Type: TABLE; Schema: rnd; Owner: -
--

CREATE TABLE rnd.experiment (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    experiment_no character varying(80) NOT NULL,
    legacy_experiment_no character varying(120),
    title character varying(300) NOT NULL,
    category_id uuid,
    category_name character varying(100),
    source_type character varying(30) NOT NULL,
    status character varying(30) NOT NULL,
    classification_status character varying(30) DEFAULT 'CLASSIFIED'::character varying NOT NULL,
    project_id uuid,
    stage_id uuid,
    task_id uuid,
    owner_id uuid,
    owner_name character varying(100),
    experiment_date date,
    current_version_id uuid,
    revision bigint DEFAULT 0 NOT NULL,
    void_reason character varying(1000),
    deleted boolean DEFAULT false NOT NULL,
    created_by uuid NOT NULL,
    updated_by uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    source_file_id uuid,
    CONSTRAINT experiment_source_type_check CHECK (((source_type)::text = ANY (ARRAY[('PROJECT'::character varying)::text, ('TEMPLATE'::character varying)::text, ('MANUAL'::character varying)::text, ('EXCEL_IMPORT'::character varying)::text, ('OCR_IMPORT'::character varying)::text]))),
    CONSTRAINT experiment_status_check CHECK (((status)::text = ANY (ARRAY[('DRAFT'::character varying)::text, ('PENDING'::character varying)::text, ('IN_PROGRESS'::character varying)::text, ('PENDING_REVIEW'::character varying)::text, ('RETURNED'::character varying)::text, ('COMPLETED'::character varying)::text, ('VOIDED'::character varying)::text])))
);


--
-- Name: experiment_version; Type: TABLE; Schema: rnd; Owner: -
--

CREATE TABLE rnd.experiment_version (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    experiment_id uuid NOT NULL,
    version_no integer NOT NULL,
    status character varying(30) NOT NULL,
    template_version_id uuid,
    template_snapshot_hash character varying(64),
    template_snapshot_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    edit_model_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    revision_reason character varying(1000),
    submitted_at timestamp with time zone,
    published_at timestamp with time zone,
    created_by uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: structured_retrieval_projection; Type: VIEW; Schema: ai; Owner: -
--

CREATE VIEW ai.structured_retrieval_projection AS
 SELECT j.organization_id,
    j.id AS import_job_id,
    j.source_file_id AS file_object_id,
    j.source_file_name AS original_name,
    j.source_format,
    j.source_owner,
    j.recognition_mode,
    j.created_by AS import_created_by,
    j.category_id AS data_category_id,
    COALESCE(e.category_id, j.target_experiment_category_id) AS experiment_category_id,
    COALESCE(j.completed_at, j.updated_at) AS updated_at,
    r.id AS record_id,
    r.record_key,
    r.record_index,
    r.synthetic_key,
    r.sheet_id,
    r.sheet_name,
    r.source_row_number,
    v.id AS value_id,
    v.binding_id,
    v.value_path,
    v.field_code,
    COALESCE(meta.field_name, meta.source_header, v.field_code) AS field_name,
    meta.value_type,
    v.value_text AS field_value,
    v.normalized_unit,
    a.column_name,
    a.cell_address,
    a.column_number,
    a.row_number AS anchor_row_number,
    link.experiment_id,
    link.experiment_version_id,
    e.experiment_no,
    e.owner_id AS experiment_owner_id,
    e.created_by AS experiment_created_by,
    e.project_id AS experiment_project_id,
    e.stage_id AS experiment_stage_id,
    e.task_id AS experiment_task_id,
    e.status AS experiment_status,
    meta.source_label,
    meta.semantic_domain,
    meta.semantic_field,
    meta.declared_condition,
    meta.source_unit,
    COALESCE(meta.standard_unit, (v.normalized_unit)::text) AS standard_unit,
    meta.mapping_kind,
    meta.component_id,
    meta.parent_binding_id,
    meta.target_path,
    e.title AS experiment_title,
    e.category_name AS experiment_category_name,
    NULLIF(btrim((ev.edit_model_jsonb ->> 'purpose'::text)), ''::text) AS experiment_purpose,
    project_context.name AS project_name,
    stage_context.name AS experiment_stage_name,
    task_context.name AS experiment_task_name
   FROM ((((((((((data.import_job j
     JOIN data.data_record r ON (((r.organization_id = j.organization_id) AND (r.import_job_id = j.id))))
     JOIN data.data_value v ON (((v.organization_id = r.organization_id) AND (v.record_id = r.id))))
     LEFT JOIN LATERAL ( SELECT m.field_name,
            m.source_header,
            NULLIF(btrim((m.mapping_jsonb ->> 'labelPath'::text)), ''::text) AS source_label,
            NULLIF(((m.mapping_jsonb -> 'experimentField'::text) ->> 'domain'::text), ''::text) AS semantic_domain,
            NULLIF(((m.mapping_jsonb -> 'experimentField'::text) ->> 'field'::text), ''::text) AS semantic_field,
            NULLIF(btrim((m.mapping_jsonb ->> 'condition'::text)), ''::text) AS declared_condition,
            COALESCE(NULLIF(btrim((m.source_unit)::text), ''::text), NULLIF(btrim((m.mapping_jsonb ->> 'sourceUnit'::text)), ''::text)) AS source_unit,
            COALESCE(NULLIF(btrim((m.standard_unit)::text), ''::text), NULLIF(btrim((m.mapping_jsonb ->> 'standardUnit'::text)), ''::text)) AS standard_unit,
            NULLIF((m.mapping_jsonb ->> 'mappingKind'::text), ''::text) AS mapping_kind,
            NULLIF((m.mapping_jsonb ->> 'componentId'::text), ''::text) AS component_id,
            NULLIF((m.mapping_jsonb ->> 'parentBindingId'::text), ''::text) AS parent_binding_id,
            NULLIF((m.mapping_jsonb ->> 'dataPath'::text), ''::text) AS target_path,
            m.value_type,
            m.mapping_jsonb
           FROM ( SELECT published_mapping.id,
                    published_mapping.import_job_id,
                    published_mapping.import_sheet_id,
                    published_mapping.source_column,
                    published_mapping.source_header,
                    published_mapping.field_code,
                    published_mapping.field_name,
                    published_mapping.action,
                    published_mapping.value_type,
                    published_mapping.source_unit,
                    published_mapping.standard_unit,
                    published_mapping.mapping_jsonb,
                    published_mapping.status,
                    published_mapping.created_at,
                    published_mapping.updated_at
                   FROM data.published_mapping
                UNION ALL
                 SELECT live.id,
                    live.import_job_id,
                    live.import_sheet_id,
                    live.source_column,
                    live.source_header,
                    live.field_code,
                    live.field_name,
                    live.action,
                    live.value_type,
                    live.source_unit,
                    live.standard_unit,
                    live.mapping_jsonb,
                    live.status,
                    live.created_at,
                    live.updated_at
                   FROM data.import_mapping live
                  WHERE (NOT (EXISTS ( SELECT 1
                           FROM data.publication pub
                          WHERE ((pub.import_job_id = live.import_job_id) AND ((pub.published_snapshot_id IS NOT NULL) OR pub.legacy_published)))))) m
          WHERE ((m.import_job_id = j.id) AND (((m.mapping_jsonb ->> 'dataPath'::text) = (v.value_path)::text) OR (COALESCE((m.mapping_jsonb ->> 'bindingId'::text), (m.field_code)::text) = (v.binding_id)::text) OR ((m.field_code)::text = (v.field_code)::text)))
          ORDER BY
                CASE
                    WHEN ((m.mapping_jsonb ->> 'dataPath'::text) = (v.value_path)::text) THEN 0
                    WHEN (COALESCE((m.mapping_jsonb ->> 'bindingId'::text), (m.field_code)::text) = (v.binding_id)::text) THEN 1
                    ELSE 2
                END,
                CASE
                    WHEN ((m.status)::text = ANY ((ARRAY['CONFIRMED'::character varying, 'MATCHED'::character varying])::text[])) THEN 0
                    ELSE 1
                END, m.updated_at DESC
         LIMIT 1) meta ON (true))
     LEFT JOIN LATERAL ( SELECT candidate.sheet_name,
            candidate.row_number,
            candidate.column_number,
            candidate.column_name,
            candidate.cell_address
           FROM data.source_anchor candidate
          WHERE ((candidate.record_id = r.id) AND ((candidate.value_path = (v.value_path)::text) OR (((candidate.binding_id)::text = (v.binding_id)::text) AND ((candidate.field_code)::text = (v.field_code)::text)) OR (candidate.id = v.source_anchor_id)))
          ORDER BY
                CASE
                    WHEN (candidate.value_path = (v.value_path)::text) THEN 0
                    WHEN (((candidate.binding_id)::text = (v.binding_id)::text) AND ((candidate.field_code)::text = (v.field_code)::text)) THEN 1
                    ELSE 2
                END, candidate.created_at DESC
         LIMIT 1) a ON (true))
     LEFT JOIN LATERAL ( SELECT l.experiment_id,
            l.experiment_version_id
           FROM data.import_experiment_link l
          WHERE ((l.organization_id = j.organization_id) AND (l.import_job_id = j.id) AND (l.experiment_id IS NOT NULL) AND ((l.status)::text <> ALL ((ARRAY['BLOCKED'::character varying, 'FAILED'::character varying, 'SKIPPED'::character varying])::text[])))
          ORDER BY l.updated_at DESC, l.id DESC
         LIMIT 1) link ON (true))
     LEFT JOIN rnd.experiment e ON (((e.organization_id = j.organization_id) AND (e.id = link.experiment_id) AND (e.deleted = false))))
     LEFT JOIN rnd.experiment_version ev ON (((ev.organization_id = e.organization_id) AND (ev.experiment_id = e.id) AND (ev.id = COALESCE(link.experiment_version_id, e.current_version_id)))))
     LEFT JOIN mdm.project project_context ON (((project_context.id = e.project_id) AND (project_context.deleted = false))))
     LEFT JOIN mdm.project_stage stage_context ON (((stage_context.id = e.stage_id) AND (stage_context.deleted = false))))
     LEFT JOIN mdm.project_task task_context ON (((task_context.id = e.task_id) AND (task_context.deleted = false))))
  WHERE (((((j.source_owner)::text = 'DATA_CENTER'::text) AND (EXISTS ( SELECT 1
           FROM data.publication pub
          WHERE ((pub.organization_id = j.organization_id) AND (pub.import_job_id = j.id) AND ((pub.published_snapshot_id IS NOT NULL) OR pub.legacy_published))))) OR (((j.status)::text = 'COMPLETED'::text) AND (NOT (EXISTS ( SELECT 1
           FROM data.publication pub
          WHERE ((pub.organization_id = j.organization_id) AND (pub.import_job_id = j.id))))))) AND (NOT (EXISTS ( SELECT 1
           FROM data.record_governance g
          WHERE ((g.organization_id = r.organization_id) AND (g.record_id = r.id) AND ((g.status)::text <> 'ACTIVE'::text))))) AND (NOT (EXISTS ( SELECT 1
           FROM data.publication pub
          WHERE ((pub.organization_id = j.organization_id) AND (pub.import_job_id = j.id) AND (NOT pub.published_enabled))))) AND ((r.quality_status)::text <> 'BLOCKED'::text) AND (v.rag_eligible = true) AND (((v.value_source)::text <> 'FORMULA'::text) OR ((v.calculation_status)::text = 'VALID'::text)) AND (NULLIF(btrim(v.value_text), ''::text) IS NOT NULL) AND (((j.source_owner)::text = 'DATA_CENTER'::text) OR (((j.source_owner)::text = 'EXPERIMENT'::text) AND ((j.recognition_mode)::text = 'TEMPLATE_GUIDED'::text) AND (link.experiment_id IS NOT NULL) AND (e.id IS NOT NULL))));


--
-- Name: target_version; Type: TABLE; Schema: ai; Owner: -
--

CREATE TABLE ai.target_version (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    target_id uuid NOT NULL,
    version_no integer NOT NULL,
    status character varying(24) DEFAULT 'DRAFT'::character varying NOT NULL,
    value_type character varying(24) NOT NULL,
    unit character varying(80),
    classes_jsonb jsonb DEFAULT '[]'::jsonb NOT NULL,
    definition_jsonb jsonb NOT NULL,
    observation_semantics_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    config_hash character(64) NOT NULL,
    published_at timestamp with time zone,
    created_by uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    result_standard_field_dictionary_id uuid,
    catalog_proposal_id uuid,
    CONSTRAINT target_version_classes_array CHECK ((jsonb_typeof(classes_jsonb) = 'array'::text)),
    CONSTRAINT target_version_definition_object CHECK ((jsonb_typeof(definition_jsonb) = 'object'::text)),
    CONSTRAINT target_version_hash_check CHECK ((config_hash ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT target_version_semantics_object CHECK ((jsonb_typeof(observation_semantics_jsonb) = 'object'::text)),
    CONSTRAINT target_version_status_check CHECK (((status)::text = ANY (ARRAY[('DRAFT'::character varying)::text, ('PUBLISHED'::character varying)::text, ('RETIRED'::character varying)::text]))),
    CONSTRAINT target_version_value_type_check CHECK (((value_type)::text = ANY (ARRAY[('CONTINUOUS'::character varying)::text, ('ORDINAL'::character varying)::text, ('BINARY'::character varying)::text, ('CATEGORICAL'::character varying)::text]))),
    CONSTRAINT target_version_version_no_check CHECK ((version_no > 0))
);


--
-- Name: training_eligibility; Type: TABLE; Schema: ai; Owner: -
--

CREATE TABLE ai.training_eligibility (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    sample_revision_id uuid NOT NULL,
    target_version_id uuid NOT NULL,
    input_scheme_id uuid NOT NULL,
    state character varying(24) NOT NULL,
    reasons_jsonb jsonb DEFAULT '[]'::jsonb NOT NULL,
    warnings_jsonb jsonb DEFAULT '[]'::jsonb NOT NULL,
    evidence_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    rule_fingerprint character(64) NOT NULL,
    revision bigint DEFAULT 0 NOT NULL,
    evaluated_at timestamp with time zone DEFAULT now() NOT NULL,
    evaluation_run_id uuid,
    source_mapping_version_id uuid,
    material_dictionary_version_id uuid,
    modeling_policy_version_id uuid,
    CONSTRAINT eligibility_evidence_object CHECK ((jsonb_typeof(evidence_jsonb) = 'object'::text)),
    CONSTRAINT eligibility_reasons_array CHECK ((jsonb_typeof(reasons_jsonb) = 'array'::text)),
    CONSTRAINT eligibility_rule_hash_check CHECK ((rule_fingerprint ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT eligibility_state_check CHECK (((state)::text = ANY (ARRAY[('TRAINABLE'::character varying)::text, ('EXCLUDED'::character varying)::text, ('REVIEW_REQUIRED'::character varying)::text]))),
    CONSTRAINT eligibility_warnings_array CHECK ((jsonb_typeof(warnings_jsonb) = 'array'::text))
);


--
-- Name: training_job; Type: TABLE; Schema: ai; Owner: -
--

CREATE TABLE ai.training_job (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    training_snapshot_id uuid NOT NULL,
    status character varying(24) DEFAULT 'QUEUED'::character varying NOT NULL,
    idempotency_key character varying(200) NOT NULL,
    request_hash character(64) NOT NULL,
    priority smallint DEFAULT 100 NOT NULL,
    max_attempts integer DEFAULT 3 NOT NULL,
    attempt_count integer DEFAULT 0 NOT NULL,
    lease_owner character varying(160),
    lease_expires_at timestamp with time zone,
    next_attempt_at timestamp with time zone DEFAULT now() NOT NULL,
    last_error_code character varying(100),
    last_error_message text,
    revision bigint DEFAULT 0 NOT NULL,
    requested_by uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    started_at timestamp with time zone,
    finished_at timestamp with time zone,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    target_id uuid,
    target_version_id uuid,
    modeling_policy_version_id uuid,
    seed bigint,
    business_key character(64),
    ops_job_id uuid,
    current_stage character varying(40) DEFAULT 'QUEUED'::character varying NOT NULL,
    progress smallint DEFAULT 0 NOT NULL,
    active_attempt_id uuid,
    fencing_generation bigint DEFAULT 0 NOT NULL,
    cancellation_requested_at timestamp with time zone,
    error_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    CONSTRAINT training_job_attempt_count_check CHECK ((attempt_count >= 0)),
    CONSTRAINT training_job_business_hash_check CHECK (((business_key IS NULL) OR (business_key ~ '^[0-9a-f]{64}$'::text))),
    CONSTRAINT training_job_error_object CHECK ((jsonb_typeof(error_jsonb) = 'object'::text)),
    CONSTRAINT training_job_hash_check CHECK ((request_hash ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT training_job_max_attempts_check CHECK ((max_attempts > 0)),
    CONSTRAINT training_job_progress_check CHECK (((progress >= 0) AND (progress <= 100))),
    CONSTRAINT training_job_status_check CHECK (((status)::text = ANY (ARRAY[('QUEUED'::character varying)::text, ('MATERIALIZING'::character varying)::text, ('SNAPSHOT_VALIDATING'::character varying)::text, ('FOLDING'::character varying)::text, ('TRAINING'::character varying)::text, ('VALIDATING'::character varying)::text, ('SUCCEEDED'::character varying)::text, ('FAILED'::character varying)::text, ('CANCELLED'::character varying)::text])))
);


--
-- Name: training_job_attempt; Type: TABLE; Schema: ai; Owner: -
--

CREATE TABLE ai.training_job_attempt (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    training_job_id uuid NOT NULL,
    attempt_no integer NOT NULL,
    status character varying(24) NOT NULL,
    worker_id character varying(160),
    frozen_request_jsonb jsonb NOT NULL,
    request_hash character(64) NOT NULL,
    result_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    error_code character varying(100),
    error_message text,
    started_at timestamp with time zone DEFAULT now() NOT NULL,
    finished_at timestamp with time zone,
    lease_token uuid,
    fencing_generation bigint DEFAULT 0 NOT NULL,
    heartbeat_at timestamp with time zone,
    current_stage character varying(40) DEFAULT 'QUEUED'::character varying NOT NULL,
    progress smallint DEFAULT 0 NOT NULL,
    log_reference character varying(700),
    artifact_refs_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    CONSTRAINT training_attempt_artifacts_object CHECK ((jsonb_typeof(artifact_refs_jsonb) = 'object'::text)),
    CONSTRAINT training_attempt_hash_check CHECK ((request_hash ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT training_attempt_progress_check CHECK (((progress >= 0) AND (progress <= 100))),
    CONSTRAINT training_attempt_request_object CHECK ((jsonb_typeof(frozen_request_jsonb) = 'object'::text)),
    CONSTRAINT training_attempt_result_object CHECK ((jsonb_typeof(result_jsonb) = 'object'::text)),
    CONSTRAINT training_attempt_status_check CHECK (((status)::text = ANY (ARRAY[('RUNNING'::character varying)::text, ('SUCCEEDED'::character varying)::text, ('FAILED'::character varying)::text, ('INTERRUPTED'::character varying)::text, ('CANCELLED'::character varying)::text, ('STALE'::character varying)::text]))),
    CONSTRAINT training_job_attempt_attempt_no_check CHECK ((attempt_no > 0))
);


--
-- Name: training_sample; Type: TABLE; Schema: ai; Owner: -
--

CREATE TABLE ai.training_sample (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    logical_sample_key character varying(320) NOT NULL,
    identity_version bigint DEFAULT 1 NOT NULL,
    authority_source_type character varying(24),
    current_sample_revision_id uuid,
    status character varying(24) DEFAULT 'ACTIVE'::character varying NOT NULL,
    takeover_experiment_id uuid,
    takeover_experiment_version_id uuid,
    revision bigint DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT training_sample_authority_check CHECK (((authority_source_type IS NULL) OR ((authority_source_type)::text = ANY (ARRAY[('DATA_CENTER'::character varying)::text, ('EXPERIMENT'::character varying)::text])))),
    CONSTRAINT training_sample_status_check CHECK (((status)::text = ANY (ARRAY[('ACTIVE'::character varying)::text, ('TAKEN_OVER'::character varying)::text, ('SUSPENDED'::character varying)::text, ('INVALIDATED'::character varying)::text, ('RETIRED'::character varying)::text])))
);


--
-- Name: training_schedule_intent; Type: TABLE; Schema: ai; Owner: -
--

CREATE TABLE ai.training_schedule_intent (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    target_id uuid NOT NULL,
    target_version_id uuid NOT NULL,
    input_scheme_id uuid NOT NULL,
    eligibility_run_id uuid NOT NULL,
    configuration_fingerprint character(64) NOT NULL,
    fact_high_watermark bigint DEFAULT 0 NOT NULL,
    status character varying(24) DEFAULT 'PENDING'::character varying NOT NULL,
    block_code character varying(100),
    block_detail_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT training_intent_detail_object CHECK ((jsonb_typeof(block_detail_jsonb) = 'object'::text)),
    CONSTRAINT training_intent_hash_check CHECK ((configuration_fingerprint ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT training_intent_status_check CHECK (((status)::text = ANY (ARRAY[('PENDING'::character varying)::text, ('CONSUMED'::character varying)::text, ('BLOCKED'::character varying)::text])))
);


--
-- Name: training_scheduler_setting; Type: TABLE; Schema: ai; Owner: -
--

CREATE TABLE ai.training_scheduler_setting (
    organization_id uuid NOT NULL,
    auto_learning_enabled boolean DEFAULT false NOT NULL,
    last_evaluated_at timestamp with time zone,
    revision bigint DEFAULT 0 NOT NULL,
    updated_by uuid,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: training_snapshot; Type: TABLE; Schema: ai; Owner: -
--

CREATE TABLE ai.training_snapshot (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    target_version_id uuid NOT NULL,
    input_scheme_id uuid NOT NULL,
    material_dictionary_version_id uuid NOT NULL,
    modeling_policy_version_id uuid NOT NULL,
    status character varying(24) DEFAULT 'FROZEN'::character varying NOT NULL,
    sample_count integer NOT NULL,
    validation_groups_jsonb jsonb NOT NULL,
    manifest_jsonb jsonb NOT NULL,
    snapshot_hash character(64) NOT NULL,
    object_prefix character varying(500) NOT NULL,
    frozen_by uuid NOT NULL,
    frozen_at timestamp with time zone DEFAULT now() NOT NULL,
    target_id uuid,
    eligibility_run_id uuid,
    source_mapping_versions_jsonb jsonb DEFAULT '[]'::jsonb NOT NULL,
    target_definition_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    input_scheme_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    material_dictionary_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    preprocessing_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    training_policy_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    authorization_scope_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    fact_high_watermark bigint DEFAULT 0 NOT NULL,
    data_nature character varying(24) DEFAULT 'REAL'::character varying NOT NULL,
    business_fingerprint character(64),
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


--
-- Name: training_snapshot_item; Type: TABLE; Schema: ai; Owner: -
--

CREATE TABLE ai.training_snapshot_item (
    organization_id uuid NOT NULL,
    training_snapshot_id uuid NOT NULL,
    sample_revision_id uuid NOT NULL,
    eligibility_id uuid NOT NULL,
    ordinal bigint NOT NULL,
    split_group character varying(160) NOT NULL,
    row_hash character(64) NOT NULL,
    row_jsonb jsonb NOT NULL,
    training_sample_id uuid,
    observation_ids_jsonb jsonb DEFAULT '[]'::jsonb NOT NULL,
    replicate_group_keys_jsonb jsonb DEFAULT '[]'::jsonb NOT NULL,
    source_refs_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    validation_groups_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    value_hash character(64),
    CONSTRAINT training_snapshot_item_groups_object CHECK ((jsonb_typeof(validation_groups_jsonb) = 'object'::text)),
    CONSTRAINT training_snapshot_item_hash_check CHECK ((row_hash ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT training_snapshot_item_observations_array CHECK ((jsonb_typeof(observation_ids_jsonb) = 'array'::text)),
    CONSTRAINT training_snapshot_item_ordinal_check CHECK ((ordinal >= 0)),
    CONSTRAINT training_snapshot_item_replicates_array CHECK ((jsonb_typeof(replicate_group_keys_jsonb) = 'array'::text)),
    CONSTRAINT training_snapshot_item_row_object CHECK ((jsonb_typeof(row_jsonb) = 'object'::text)),
    CONSTRAINT training_snapshot_item_source_object CHECK ((jsonb_typeof(source_refs_jsonb) = 'object'::text)),
    CONSTRAINT training_snapshot_item_value_hash_check CHECK (((value_hash IS NULL) OR (value_hash ~ '^[0-9a-f]{64}$'::text)))
);


--
-- Name: project_reference; Type: TABLE; Schema: core; Owner: -
--

CREATE TABLE core.project_reference (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    project_id uuid NOT NULL,
    stage_id uuid,
    task_id uuid,
    resource_type character varying(40) NOT NULL,
    resource_id uuid NOT NULL,
    file_version_id uuid,
    file_object_id uuid,
    source_module character varying(32) NOT NULL,
    title character varying(500) NOT NULL,
    original_name character varying(500),
    content_type character varying(260),
    size_bytes bigint DEFAULT 0 NOT NULL,
    summary text,
    status character varying(24) DEFAULT 'ACTIVE'::character varying NOT NULL,
    added_by uuid NOT NULL,
    added_at timestamp with time zone DEFAULT now() NOT NULL,
    removed_by uuid,
    removed_at timestamp with time zone,
    CONSTRAINT project_reference_check CHECK (((task_id IS NULL) OR (stage_id IS NOT NULL))),
    CONSTRAINT project_reference_resource_type_check CHECK (((resource_type)::text = ANY (ARRAY[('KNOWLEDGE_DOCUMENT'::character varying)::text, ('DATA_IMPORT_JOB'::character varying)::text]))),
    CONSTRAINT project_reference_size_bytes_check CHECK ((size_bytes >= 0)),
    CONSTRAINT project_reference_source_module_check CHECK (((source_module)::text = ANY (ARRAY[('KNOWLEDGE'::character varying)::text, ('DATA_CENTER'::character varying)::text]))),
    CONSTRAINT project_reference_status_check CHECK (((status)::text = ANY (ARRAY[('ACTIVE'::character varying)::text, ('REMOVED'::character varying)::text])))
);


--
-- Name: project_resource_link; Type: TABLE; Schema: core; Owner: -
--

CREATE TABLE core.project_resource_link (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    resource_type character varying(40) NOT NULL,
    resource_id uuid NOT NULL,
    project_id uuid NOT NULL,
    stage_id uuid,
    task_id uuid,
    created_by uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT project_resource_link_check CHECK (((task_id IS NULL) OR (stage_id IS NOT NULL))),
    CONSTRAINT project_resource_link_resource_type_check CHECK (((resource_type)::text = ANY (ARRAY[('KNOWLEDGE_DOCUMENT'::character varying)::text, ('DATA_IMPORT_JOB'::character varying)::text])))
);


--
-- Name: file_object; Type: TABLE; Schema: ops; Owner: -
--

CREATE TABLE ops.file_object (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    bucket character varying(100) NOT NULL,
    object_key character varying(500) NOT NULL,
    original_name character varying(260) NOT NULL,
    content_type character varying(160) NOT NULL,
    size_bytes bigint NOT NULL,
    sha256 character(64) NOT NULL,
    status character varying(20) NOT NULL,
    created_by uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    activated_at timestamp with time zone,
    CONSTRAINT file_object_size_bytes_check CHECK ((size_bytes >= 0)),
    CONSTRAINT file_object_status_check CHECK (((status)::text = ANY (ARRAY[('STAGED'::character varying)::text, ('ACTIVE'::character varying)::text, ('DELETED'::character varying)::text])))
);


--
-- Name: completed_source_file_projection; Type: VIEW; Schema: data; Owner: -
--

CREATE VIEW data.completed_source_file_projection AS
 SELECT j.organization_id,
    j.id AS import_job_id,
    j.source_file_id AS file_object_id,
    j.source_file_name AS original_name,
    j.source_format,
    COALESCE(j.completed_at, j.updated_at) AS updated_at
   FROM (data.import_job j
     JOIN ops.file_object f ON (((f.id = j.source_file_id) AND (f.organization_id = j.organization_id) AND ((f.status)::text <> 'DELETED'::text))))
  WHERE ((j.status)::text = 'COMPLETED'::text);


--
-- Name: confirmed_submission; Type: TABLE; Schema: data; Owner: -
--

CREATE TABLE data.confirmed_submission (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    import_job_id uuid NOT NULL,
    revision_no integer NOT NULL,
    status character varying(24) NOT NULL,
    content_hash character(64) NOT NULL,
    confirmed_by uuid NOT NULL,
    confirmed_at timestamp with time zone DEFAULT now() NOT NULL,
    supersedes_submission_id uuid,
    invalidated_at timestamp with time zone,
    invalidation_reason text,
    source_sequence bigint NOT NULL,
    mapping_contract_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    CONSTRAINT confirmed_submission_hash_check CHECK ((content_hash ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT confirmed_submission_mapping_contract_object CHECK ((jsonb_typeof(mapping_contract_jsonb) = 'object'::text)),
    CONSTRAINT confirmed_submission_revision_no_check CHECK ((revision_no > 0)),
    CONSTRAINT confirmed_submission_status_check CHECK (((status)::text = ANY (ARRAY[('CONFIRMED'::character varying)::text, ('SUPERSEDED'::character varying)::text, ('INVALIDATED'::character varying)::text])))
);


--
-- Name: confirmed_submission_head; Type: TABLE; Schema: data; Owner: -
--

CREATE TABLE data.confirmed_submission_head (
    organization_id uuid NOT NULL,
    import_job_id uuid NOT NULL,
    confirmed_submission_id uuid NOT NULL,
    revision bigint DEFAULT 0 NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: confirmed_submission_item; Type: TABLE; Schema: data; Owner: -
--

CREATE TABLE data.confirmed_submission_item (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    confirmed_submission_id uuid NOT NULL,
    source_record_id uuid,
    source_record_version bigint NOT NULL,
    source_file_id uuid,
    sheet_name character varying(240),
    row_coordinate character varying(120),
    mapping_version_id uuid,
    source_identity_jsonb jsonb NOT NULL,
    raw_fact_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    corrected_fact_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    fact_jsonb jsonb NOT NULL,
    source_coordinates_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    source_file_sha256 character(64) NOT NULL,
    content_hash character(64) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT confirmed_submission_coordinates_object CHECK ((jsonb_typeof(source_coordinates_jsonb) = 'object'::text)),
    CONSTRAINT confirmed_submission_corrected_fact_object CHECK ((jsonb_typeof(corrected_fact_jsonb) = 'object'::text)),
    CONSTRAINT confirmed_submission_fact_object CHECK ((jsonb_typeof(fact_jsonb) = 'object'::text)),
    CONSTRAINT confirmed_submission_file_hash_check CHECK ((source_file_sha256 ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT confirmed_submission_identity_object CHECK ((jsonb_typeof(source_identity_jsonb) = 'object'::text)),
    CONSTRAINT confirmed_submission_item_hash_check CHECK ((content_hash ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT confirmed_submission_raw_fact_object CHECK ((jsonb_typeof(raw_fact_jsonb) = 'object'::text))
);


--
-- Name: data_category; Type: TABLE; Schema: data; Owner: -
--

CREATE TABLE data.data_category (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    name character varying(120) NOT NULL,
    sort_order integer DEFAULT 0 NOT NULL,
    created_by uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    description character varying(240)
);


--
-- Name: import_component_override; Type: TABLE; Schema: data; Owner: -
--

CREATE TABLE data.import_component_override (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    import_job_id uuid NOT NULL,
    component_id character varying(256) NOT NULL,
    sheet_id character varying(256) NOT NULL,
    source_range character varying(64) NOT NULL,
    reason character varying(500) NOT NULL,
    created_by uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: import_issue; Type: TABLE; Schema: data; Owner: -
--

CREATE TABLE data.import_issue (
    id uuid NOT NULL,
    import_job_id uuid NOT NULL,
    staging_row_id uuid,
    import_sheet_id uuid,
    field_code character varying(160),
    severity character varying(16) NOT NULL,
    issue_type character varying(80) NOT NULL,
    source_row_number integer,
    source_column character varying(32),
    source_address character varying(80),
    message text NOT NULL,
    detail_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    status character varying(24) DEFAULT 'OPEN'::character varying NOT NULL,
    resolved_by uuid,
    resolved_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    resolution_reason character varying(500),
    CONSTRAINT import_issue_severity_check CHECK (((severity)::text = ANY (ARRAY[('INFO'::character varying)::text, ('WARNING'::character varying)::text, ('BLOCKER'::character varying)::text]))),
    CONSTRAINT import_issue_status_check CHECK (((status)::text = ANY (ARRAY[('OPEN'::character varying)::text, ('CONFIRMED'::character varying)::text, ('RESOLVED'::character varying)::text, ('IGNORED'::character varying)::text])))
);


--
-- Name: import_job_attempt; Type: TABLE; Schema: data; Owner: -
--

CREATE TABLE data.import_job_attempt (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    import_job_id uuid NOT NULL,
    attempt_no integer NOT NULL,
    reason character varying(40) NOT NULL,
    status character varying(32) NOT NULL,
    snapshot_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_by uuid,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT import_job_attempt_attempt_no_check CHECK ((attempt_no > 0))
);


--
-- Name: import_mapping_profile; Type: TABLE; Schema: data; Owner: -
--

CREATE TABLE data.import_mapping_profile (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    template_version_id uuid NOT NULL,
    source_fingerprint character(64) NOT NULL,
    mapping_jsonb jsonb DEFAULT '[]'::jsonb NOT NULL,
    approved_by uuid,
    approved_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: import_sheet; Type: TABLE; Schema: data; Owner: -
--

CREATE TABLE data.import_sheet (
    id uuid NOT NULL,
    import_job_id uuid NOT NULL,
    sheet_id character varying(160) NOT NULL,
    sheet_name character varying(260) NOT NULL,
    sheet_order integer DEFAULT 0 NOT NULL,
    selected boolean DEFAULT true NOT NULL,
    header_rows_jsonb jsonb DEFAULT '[]'::jsonb NOT NULL,
    data_start_row integer,
    data_end_row integer,
    structure_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    confirmation_status character varying(24) DEFAULT 'PENDING'::character varying NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT import_sheet_confirmation_status_check CHECK (((confirmation_status)::text = ANY (ARRAY[('PENDING'::character varying)::text, ('CONFIRMED'::character varying)::text, ('IGNORED'::character varying)::text]))),
    CONSTRAINT import_sheet_sheet_order_check CHECK ((sheet_order >= 0))
);


--
-- Name: import_workbook_draft; Type: TABLE; Schema: data; Owner: -
--

CREATE TABLE data.import_workbook_draft (
    organization_id uuid NOT NULL,
    import_job_id uuid NOT NULL,
    snapshot_jsonb jsonb NOT NULL,
    instance_jsonb jsonb NOT NULL,
    operations_jsonb jsonb DEFAULT '[]'::jsonb NOT NULL,
    updated_by uuid NOT NULL,
    updated_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    CONSTRAINT import_workbook_draft_instance_jsonb_check CHECK ((jsonb_typeof(instance_jsonb) = 'object'::text)),
    CONSTRAINT import_workbook_draft_operations_jsonb_check CHECK ((jsonb_typeof(operations_jsonb) = 'array'::text)),
    CONSTRAINT import_workbook_draft_snapshot_jsonb_check CHECK ((jsonb_typeof(snapshot_jsonb) = 'object'::text))
);


--
-- Name: publication_event; Type: TABLE; Schema: data; Owner: -
--

CREATE TABLE data.publication_event (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    import_job_id uuid NOT NULL,
    snapshot_id uuid,
    action character varying(32) NOT NULL,
    actor_id uuid NOT NULL,
    actor_name character varying(200) NOT NULL,
    comment text,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: publication_release; Type: TABLE; Schema: data; Owner: -
--

CREATE TABLE data.publication_release (
    snapshot_id uuid NOT NULL,
    confirmed_submission_id uuid NOT NULL,
    published_by uuid NOT NULL,
    published_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: publication_snapshot; Type: TABLE; Schema: data; Owner: -
--

CREATE TABLE data.publication_snapshot (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    import_job_id uuid NOT NULL,
    version_no integer NOT NULL,
    content_hash character varying(64) NOT NULL,
    payload_jsonb jsonb NOT NULL,
    confirmed_submission_id uuid,
    confirmed_by uuid NOT NULL,
    confirmed_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: recognition_profile; Type: TABLE; Schema: data; Owner: -
--

CREATE TABLE data.recognition_profile (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    profile_code character varying(160) NOT NULL,
    name character varying(240) NOT NULL,
    source_format character varying(16) NOT NULL,
    status character varying(24) DEFAULT 'ACTIVE'::character varying NOT NULL,
    rules_jsonb jsonb NOT NULL,
    profile_hash character(64) NOT NULL,
    revision bigint DEFAULT 0 NOT NULL,
    created_by uuid NOT NULL,
    updated_by uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT recognition_profile_format_check CHECK (((source_format)::text = ANY (ARRAY[('XLS'::character varying)::text, ('XLSX'::character varying)::text, ('CSV'::character varying)::text, ('DOCX'::character varying)::text, ('PDF'::character varying)::text, ('IMAGE'::character varying)::text]))),
    CONSTRAINT recognition_profile_hash_check CHECK ((profile_hash ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT recognition_profile_rules_object CHECK ((jsonb_typeof(rules_jsonb) = 'object'::text)),
    CONSTRAINT recognition_profile_status_check CHECK (((status)::text = ANY (ARRAY[('ACTIVE'::character varying)::text, ('RETIRED'::character varying)::text])))
);


--
-- Name: staging_row; Type: TABLE; Schema: data; Owner: -
--

CREATE TABLE data.staging_row (
    id uuid NOT NULL,
    import_job_id uuid NOT NULL,
    import_sheet_id uuid NOT NULL,
    source_row_number integer NOT NULL,
    raw_values_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    normalized_values_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    corrected_values_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    row_hash character(64),
    status character varying(24) DEFAULT 'STAGED'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    source_metadata_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    excluded boolean DEFAULT false NOT NULL,
    exclusion_reason character varying(500),
    excluded_by uuid,
    excluded_at timestamp with time zone,
    source_search_vector tsvector GENERATED ALWAYS AS (to_tsvector('simple'::regconfig, COALESCE((raw_values_jsonb)::text, ''::text))) STORED,
    CONSTRAINT staging_row_source_row_number_check CHECK ((source_row_number > 0)),
    CONSTRAINT staging_row_status_check CHECK (((status)::text = ANY (ARRAY[('STAGED'::character varying)::text, ('VALID'::character varying)::text, ('WARNING'::character varying)::text, ('BLOCKED'::character varying)::text, ('CONFIRMED'::character varying)::text])))
);


--
-- Name: app_user; Type: TABLE; Schema: iam; Owner: -
--

CREATE TABLE iam.app_user (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    username character varying(80) NOT NULL,
    display_name character varying(120) NOT NULL,
    dictionary_admin boolean DEFAULT false NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    password_hash character varying(500),
    status character varying(20) DEFAULT 'ACTIVE'::character varying NOT NULL,
    email character varying(160),
    phone character varying(40),
    department_name character varying(160),
    auth_version bigint DEFAULT 0 NOT NULL,
    last_login_at timestamp with time zone,
    failed_login_count integer DEFAULT 0 NOT NULL,
    locked_until timestamp with time zone,
    role_id uuid,
    CONSTRAINT app_user_status_check CHECK (((status)::text = ANY (ARRAY[('ACTIVE'::character varying)::text, ('DISABLED'::character varying)::text])))
);


--
-- Name: login_attempt; Type: TABLE; Schema: iam; Owner: -
--

CREATE TABLE iam.login_attempt (
    id uuid NOT NULL,
    organization_id uuid,
    username character varying(80) NOT NULL,
    ip_address character varying(64),
    succeeded boolean DEFAULT false NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: login_session; Type: TABLE; Schema: iam; Owner: -
--

CREATE TABLE iam.login_session (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    user_id uuid NOT NULL,
    token_hash character(64) NOT NULL,
    issued_at timestamp with time zone DEFAULT now() NOT NULL,
    last_seen_at timestamp with time zone DEFAULT now() NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    absolute_expires_at timestamp with time zone NOT NULL,
    revoked_at timestamp with time zone,
    auth_version bigint DEFAULT 0 NOT NULL,
    ip_address character varying(64),
    user_agent character varying(500),
    remember_me boolean DEFAULT false NOT NULL
);


--
-- Name: organization; Type: TABLE; Schema: iam; Owner: -
--

CREATE TABLE iam.organization (
    id uuid NOT NULL,
    name character varying(160) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: permission_definition; Type: TABLE; Schema: iam; Owner: -
--

CREATE TABLE iam.permission_definition (
    code character varying(160) NOT NULL,
    module character varying(80) NOT NULL,
    name character varying(160) NOT NULL,
    risk character varying(20) DEFAULT 'MEDIUM'::character varying NOT NULL,
    default_scope character varying(20) DEFAULT 'ALL'::character varying NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    definition_version integer DEFAULT 1 NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT permission_definition_risk_check CHECK (((risk)::text = ANY (ARRAY[('LOW'::character varying)::text, ('MEDIUM'::character varying)::text, ('HIGH'::character varying)::text, ('CRITICAL'::character varying)::text]))),
    CONSTRAINT permission_definition_scope_check CHECK (((default_scope)::text = ANY (ARRAY[('ALL'::character varying)::text, ('SELF'::character varying)::text, ('ASSIGNED'::character varying)::text, ('PROJECT'::character varying)::text, ('CATEGORY'::character varying)::text, ('SELECTED'::character varying)::text])))
);


--
-- Name: permission_level; Type: TABLE; Schema: iam; Owner: -
--

CREATE TABLE iam.permission_level (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    code character varying(80) NOT NULL,
    name character varying(120) NOT NULL,
    description character varying(500),
    builtin boolean DEFAULT false NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    tier integer DEFAULT 0 NOT NULL,
    policy_version bigint DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: permission_level_binding; Type: TABLE; Schema: iam; Owner: -
--

CREATE TABLE iam.permission_level_binding (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    permission_level_id uuid NOT NULL,
    permission_code character varying(160) NOT NULL,
    scope_type character varying(32) NOT NULL,
    target_ids jsonb DEFAULT '[]'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    menu_key character varying(180),
    CONSTRAINT permission_level_scope_check CHECK (((scope_type)::text = ANY (ARRAY[('ALL'::character varying)::text, ('SELF'::character varying)::text, ('ASSIGNED'::character varying)::text, ('PROJECT'::character varying)::text, ('CATEGORY'::character varying)::text, ('SELECTED'::character varying)::text, ('ORG'::character varying)::text, ('PROJECT_GROUP'::character varying)::text, ('CREATOR'::character varying)::text, ('OWNER'::character varying)::text, ('MANAGER'::character varying)::text, ('PARTICIPANT'::character varying)::text])))
);


--
-- Name: permission_migration_issue; Type: TABLE; Schema: iam; Owner: -
--

CREATE TABLE iam.permission_migration_issue (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    user_id uuid,
    source_type character varying(40) NOT NULL,
    source_id character varying(160),
    issue_code character varying(80) NOT NULL,
    detail_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    status character varying(20) DEFAULT 'OPEN'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    resolved_at timestamp with time zone,
    CONSTRAINT permission_migration_issue_status_check CHECK (((status)::text = ANY (ARRAY[('OPEN'::character varying)::text, ('RESOLVED'::character varying)::text, ('IGNORED'::character varying)::text])))
);


--
-- Name: permission_scope_target; Type: TABLE; Schema: iam; Owner: -
--

CREATE TABLE iam.permission_scope_target (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    owner_type character varying(10) NOT NULL,
    owner_id uuid NOT NULL,
    permission_code character varying(160) NOT NULL,
    target_type character varying(80) NOT NULL,
    target_id uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT scope_target_owner_check CHECK (((owner_type)::text = ANY (ARRAY[('ROLE'::character varying)::text, ('USER'::character varying)::text])))
);


--
-- Name: project_member; Type: TABLE; Schema: iam; Owner: -
--

CREATE TABLE iam.project_member (
    organization_id uuid NOT NULL,
    project_id uuid NOT NULL,
    user_id uuid NOT NULL,
    member_role character varying(40),
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: resource_owner_identity; Type: TABLE; Schema: iam; Owner: -
--

CREATE TABLE iam.resource_owner_identity (
    organization_id uuid NOT NULL,
    resource_type character varying(80) NOT NULL,
    resource_id uuid NOT NULL,
    user_id uuid NOT NULL,
    relation_type character varying(32) DEFAULT 'OWNER'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT resource_owner_identity_relation_type_check CHECK (((relation_type)::text = ANY (ARRAY[('OWNER'::character varying)::text, ('MANAGER'::character varying)::text, ('ASSIGNED'::character varying)::text, ('CREATOR'::character varying)::text])))
);


--
-- Name: role; Type: TABLE; Schema: iam; Owner: -
--

CREATE TABLE iam.role (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    code character varying(80) NOT NULL,
    name character varying(120) NOT NULL,
    builtin boolean DEFAULT false NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    policy_version bigint DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: role_menu_binding; Type: TABLE; Schema: iam; Owner: -
--

CREATE TABLE iam.role_menu_binding (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    role_id uuid NOT NULL,
    menu_key character varying(180) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: role_permission_binding; Type: TABLE; Schema: iam; Owner: -
--

CREATE TABLE iam.role_permission_binding (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    role_id uuid NOT NULL,
    permission_code character varying(160) NOT NULL,
    effect character varying(10) NOT NULL,
    scope_type character varying(20) NOT NULL,
    target_ids jsonb DEFAULT '[]'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT role_binding_effect_check CHECK (((effect)::text = ANY (ARRAY[('ALLOW'::character varying)::text, ('DENY'::character varying)::text]))),
    CONSTRAINT role_binding_scope_check CHECK (((scope_type)::text = ANY (ARRAY[('ALL'::character varying)::text, ('SELF'::character varying)::text, ('ASSIGNED'::character varying)::text, ('PROJECT'::character varying)::text, ('CATEGORY'::character varying)::text, ('SELECTED'::character varying)::text]))),
    CONSTRAINT role_binding_target_check CHECK (((((effect)::text = 'DENY'::text) AND (target_ids = '[]'::jsonb)) OR ((effect)::text = 'ALLOW'::text)))
);


--
-- Name: user_menu_override; Type: TABLE; Schema: iam; Owner: -
--

CREATE TABLE iam.user_menu_override (
    organization_id uuid NOT NULL,
    user_id uuid NOT NULL,
    menu_key character varying(180) NOT NULL,
    effect character varying(10) NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT user_menu_override_effect_check CHECK (((effect)::text = ANY (ARRAY[('ALLOW'::character varying)::text, ('DENY'::character varying)::text])))
);


--
-- Name: user_operation_override; Type: TABLE; Schema: iam; Owner: -
--

CREATE TABLE iam.user_operation_override (
    organization_id uuid NOT NULL,
    user_id uuid NOT NULL,
    permission_code character varying(160) NOT NULL,
    effect character varying(10) NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    menu_key character varying(180) DEFAULT ''::character varying NOT NULL,
    CONSTRAINT user_operation_override_effect_check CHECK (((effect)::text = ANY (ARRAY[('ALLOW'::character varying)::text, ('DENY'::character varying)::text])))
);


--
-- Name: user_permission_override; Type: TABLE; Schema: iam; Owner: -
--

CREATE TABLE iam.user_permission_override (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    user_id uuid NOT NULL,
    permission_code character varying(160) NOT NULL,
    effect character varying(10) NOT NULL,
    scope_type character varying(20) NOT NULL,
    target_ids jsonb DEFAULT '[]'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT user_override_effect_check CHECK (((effect)::text = ANY (ARRAY[('ALLOW'::character varying)::text, ('DENY'::character varying)::text]))),
    CONSTRAINT user_override_scope_check CHECK (((scope_type)::text = ANY (ARRAY[('ALL'::character varying)::text, ('SELF'::character varying)::text, ('ASSIGNED'::character varying)::text, ('PROJECT'::character varying)::text, ('CATEGORY'::character varying)::text, ('SELECTED'::character varying)::text]))),
    CONSTRAINT user_override_target_check CHECK (((((effect)::text = 'DENY'::text) AND (target_ids = '[]'::jsonb)) OR ((effect)::text = 'ALLOW'::text)))
);


--
-- Name: user_permission_profile; Type: TABLE; Schema: iam; Owner: -
--

CREATE TABLE iam.user_permission_profile (
    organization_id uuid NOT NULL,
    user_id uuid NOT NULL,
    permission_level_id uuid,
    revision bigint DEFAULT 0 NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: user_role; Type: TABLE; Schema: iam; Owner: -
--

CREATE TABLE iam.user_role (
    organization_id uuid NOT NULL,
    user_id uuid NOT NULL,
    role_id uuid NOT NULL,
    primary_role boolean DEFAULT false NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: user_scope_override; Type: TABLE; Schema: iam; Owner: -
--

CREATE TABLE iam.user_scope_override (
    organization_id uuid NOT NULL,
    user_id uuid NOT NULL,
    permission_code character varying(160) NOT NULL,
    scope_type character varying(32) NOT NULL,
    target_ids jsonb DEFAULT '[]'::jsonb NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    menu_key character varying(180) DEFAULT ''::character varying NOT NULL,
    CONSTRAINT user_scope_override_scope_check CHECK (((scope_type)::text = ANY (ARRAY[('ALL'::character varying)::text, ('SELF'::character varying)::text, ('ASSIGNED'::character varying)::text, ('PROJECT'::character varying)::text, ('CATEGORY'::character varying)::text, ('SELECTED'::character varying)::text, ('ORG'::character varying)::text, ('PROJECT_GROUP'::character varying)::text, ('CREATOR'::character varying)::text, ('OWNER'::character varying)::text, ('MANAGER'::character varying)::text, ('PARTICIPANT'::character varying)::text])))
);


--
-- Name: chunk_term; Type: TABLE; Schema: kb; Owner: -
--

CREATE TABLE kb.chunk_term (
    chunk_id uuid NOT NULL,
    term character varying(160) NOT NULL,
    term_frequency integer NOT NULL,
    CONSTRAINT chunk_term_term_frequency_check CHECK ((term_frequency > 0))
);


--
-- Name: document; Type: TABLE; Schema: kb; Owner: -
--

CREATE TABLE kb.document (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    title character varying(260) NOT NULL,
    status character varying(24) DEFAULT 'QUEUED'::character varying NOT NULL,
    scan_status character varying(24) DEFAULT 'PENDING'::character varying NOT NULL,
    current_version_no integer DEFAULT 1 NOT NULL,
    parse_error text,
    created_by uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    library_scope character varying(16) DEFAULT 'INTERNAL'::character varying NOT NULL,
    category_id uuid,
    lifecycle_status character varying(24) DEFAULT 'ACTIVE'::character varying NOT NULL,
    disabled_by uuid,
    disabled_at timestamp with time zone,
    disabled_reason character varying(500),
    source_info_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    current_publication_id uuid,
    CONSTRAINT document_current_version_no_check CHECK ((current_version_no > 0)),
    CONSTRAINT document_library_scope_check CHECK (((library_scope)::text = ANY (ARRAY[('INTERNAL'::character varying)::text, ('EXTERNAL'::character varying)::text]))),
    CONSTRAINT document_lifecycle_status_check CHECK (((lifecycle_status)::text = ANY (ARRAY[('ACTIVE'::character varying)::text, ('DISABLED'::character varying)::text]))),
    CONSTRAINT document_scan_status_check CHECK (((scan_status)::text = ANY (ARRAY[('PENDING'::character varying)::text, ('SCANNING'::character varying)::text, ('SAFE'::character varying)::text, ('REJECTED'::character varying)::text, ('UNAVAILABLE'::character varying)::text]))),
    CONSTRAINT document_status_check CHECK (((status)::text = ANY (ARRAY[('QUEUED'::character varying)::text, ('PROCESSING'::character varying)::text, ('READY'::character varying)::text, ('FAILED'::character varying)::text, ('REJECTED'::character varying)::text, ('PENDING_PROVIDER'::character varying)::text])))
);


--
-- Name: document_version; Type: TABLE; Schema: kb; Owner: -
--

CREATE TABLE kb.document_version (
    id uuid NOT NULL,
    document_id uuid NOT NULL,
    version_no integer NOT NULL,
    file_object_id uuid NOT NULL,
    original_name character varying(260) NOT NULL,
    content_type character varying(160) NOT NULL,
    size_bytes bigint NOT NULL,
    sha256 character(64) NOT NULL,
    parser_version character varying(40),
    status character varying(24) DEFAULT 'QUEUED'::character varying NOT NULL,
    extracted_text_sha256 character(64),
    error_message text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    review_status character varying(32) DEFAULT 'PENDING_REVIEW'::character varying NOT NULL,
    review_revision integer DEFAULT 0 NOT NULL,
    review_reason character varying(1000),
    reviewed_by uuid,
    reviewed_at timestamp with time zone,
    source_info_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    ocr_mode character varying(8) DEFAULT 'AUTO'::character varying NOT NULL,
    allow_agent_fallback boolean DEFAULT false NOT NULL,
    effective_ocr boolean,
    parser_mode character varying(24),
    parser_metadata_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    CONSTRAINT ck_kb_document_version_ocr_mode CHECK (((ocr_mode)::text = ANY (ARRAY[('AUTO'::character varying)::text, ('ON'::character varying)::text, ('OFF'::character varying)::text]))),
    CONSTRAINT ck_kb_document_version_parser_mode CHECK (((parser_mode IS NULL) OR ((parser_mode)::text = ANY (ARRAY[('PRECISE'::character varying)::text, ('AGENT_FALLBACK'::character varying)::text, ('LOCAL'::character varying)::text])))),
    CONSTRAINT document_version_review_revision_check CHECK ((review_revision >= 0)),
    CONSTRAINT document_version_review_status_check CHECK (((review_status)::text = ANY (ARRAY[('PENDING_REVIEW'::character varying)::text, ('REJECTED'::character varying)::text, ('PUBLISHED'::character varying)::text, ('SUPERSEDED'::character varying)::text]))),
    CONSTRAINT document_version_size_bytes_check CHECK ((size_bytes >= 0)),
    CONSTRAINT document_version_status_check CHECK (((status)::text = ANY (ARRAY[('QUEUED'::character varying)::text, ('PROCESSING'::character varying)::text, ('READY'::character varying)::text, ('FAILED'::character varying)::text, ('REJECTED'::character varying)::text, ('PENDING_PROVIDER'::character varying)::text]))),
    CONSTRAINT document_version_version_no_check CHECK ((version_no > 0))
);


--
-- Name: publication; Type: TABLE; Schema: kb; Owner: -
--

CREATE TABLE kb.publication (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    document_id uuid NOT NULL,
    document_version_id uuid NOT NULL,
    parse_run_id uuid NOT NULL,
    publication_no integer NOT NULL,
    status character varying(24) DEFAULT 'CURRENT'::character varying NOT NULL,
    metadata_snapshot_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    published_by uuid NOT NULL,
    published_at timestamp with time zone DEFAULT now() NOT NULL,
    review_revision_id uuid NOT NULL,
    CONSTRAINT publication_publication_no_check CHECK ((publication_no > 0)),
    CONSTRAINT publication_status_check CHECK (((status)::text = ANY (ARRAY[('CURRENT'::character varying)::text, ('SUPERSEDED'::character varying)::text])))
);


--
-- Name: current_file_search_projection; Type: VIEW; Schema: kb; Owner: -
--

CREATE VIEW kb.current_file_search_projection AS
 SELECT d.organization_id,
    d.id AS logical_document_id,
    p.id AS publication_id,
    v.id AS file_version_id,
    v.file_object_id,
    COALESCE((p.metadata_snapshot_jsonb ->> 'title'::text), (d.title)::text) AS title,
    v.original_name,
    v.content_type,
    v.size_bytes,
    v.version_no,
    p.published_at AS updated_at
   FROM (((kb.document d
     JOIN kb.publication p ON (((p.id = d.current_publication_id) AND ((p.status)::text = 'CURRENT'::text))))
     JOIN kb.document_version v ON ((v.id = p.document_version_id)))
     JOIN ops.file_object f ON (((f.id = v.file_object_id) AND (f.organization_id = d.organization_id) AND ((f.status)::text <> 'DELETED'::text))))
  WHERE ((d.lifecycle_status)::text = 'ACTIVE'::text);


--
-- Name: document_ai_grant; Type: TABLE; Schema: kb; Owner: -
--

CREATE TABLE kb.document_ai_grant (
    document_id uuid NOT NULL,
    organization_id uuid NOT NULL,
    status character varying(24) NOT NULL,
    reason character varying(500),
    updated_by uuid NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT document_ai_grant_status_check CHECK (((status)::text = ANY (ARRAY[('APPROVED'::character varying)::text, ('REVOKED'::character varying)::text, ('REJECTED'::character varying)::text])))
);


--
-- Name: document_category; Type: TABLE; Schema: kb; Owner: -
--

CREATE TABLE kb.document_category (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    scope character varying(16) NOT NULL,
    name character varying(120) NOT NULL,
    sort_order integer DEFAULT 0 NOT NULL,
    created_by uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    description character varying(240),
    CONSTRAINT document_category_scope_check CHECK (((scope)::text = ANY (ARRAY[('INTERNAL'::character varying)::text, ('EXTERNAL'::character varying)::text])))
);


--
-- Name: document_chunk; Type: TABLE; Schema: kb; Owner: -
--

CREATE TABLE kb.document_chunk (
    id uuid NOT NULL,
    document_id uuid NOT NULL,
    document_version_id uuid NOT NULL,
    chunk_no integer NOT NULL,
    page_no integer,
    section character varying(500),
    content text NOT NULL,
    search_vector tsvector GENERATED ALWAYS AS (to_tsvector('simple'::regconfig, content)) STORED,
    embedding public.vector,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    parent_chunk_id uuid,
    token_length integer DEFAULT 0 NOT NULL,
    analyzer_version character varying(40) DEFAULT 'material-smartcn-v3'::character varying NOT NULL,
    embedding_model character varying(160),
    parse_run_id uuid,
    sheet_name character varying(260),
    cell_range character varying(160),
    paragraph_id character varying(160),
    bbox_jsonb jsonb,
    start_time_ms bigint,
    end_time_ms bigint,
    review_revision_id uuid,
    review_node_ids_jsonb jsonb DEFAULT '[]'::jsonb NOT NULL,
    source_node_keys_jsonb jsonb DEFAULT '[]'::jsonb NOT NULL,
    source_anchor_jsonb jsonb,
    chunk_role character varying(12) DEFAULT 'CHILD'::character varying NOT NULL,
    heading_path_jsonb jsonb DEFAULT '[]'::jsonb NOT NULL,
    source_anchors_jsonb jsonb DEFAULT '[]'::jsonb NOT NULL,
    relations_jsonb jsonb DEFAULT '[]'::jsonb NOT NULL,
    model_token_length integer DEFAULT 0 NOT NULL,
    CONSTRAINT ck_kb_document_chunk_model_token_length CHECK ((model_token_length >= 0)),
    CONSTRAINT ck_kb_document_chunk_role CHECK (((chunk_role)::text = ANY (ARRAY[('PARENT'::character varying)::text, ('CHILD'::character varying)::text]))),
    CONSTRAINT document_chunk_chunk_no_check CHECK ((chunk_no >= 0)),
    CONSTRAINT document_chunk_token_length_check CHECK ((token_length >= 0))
);


--
-- Name: COLUMN document_chunk.token_length; Type: COMMENT; Schema: kb; Owner: -
--

COMMENT ON COLUMN kb.document_chunk.token_length IS 'BM25 analyzer position count (document length), not the model token budget';


--
-- Name: COLUMN document_chunk.source_anchor_jsonb; Type: COMMENT; Schema: kb; Owner: -
--

COMMENT ON COLUMN kb.document_chunk.source_anchor_jsonb IS 'Primary source anchor used by single-anchor API fields; source_anchors_jsonb is authoritative';


--
-- Name: COLUMN document_chunk.model_token_length; Type: COMMENT; Schema: kb; Owner: -
--

COMMENT ON COLUMN kb.document_chunk.model_token_length IS 'Deterministic model-token estimate used by the block-aware chunker';


--
-- Name: document_parse_issue; Type: TABLE; Schema: kb; Owner: -
--

CREATE TABLE kb.document_parse_issue (
    id uuid NOT NULL,
    parse_run_id uuid NOT NULL,
    issue_code character varying(80) NOT NULL,
    severity character varying(16) NOT NULL,
    message character varying(1000) NOT NULL,
    status character varying(24) DEFAULT 'OPEN'::character varying NOT NULL,
    resolution character varying(1000),
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT document_parse_issue_severity_check CHECK (((severity)::text = ANY (ARRAY[('INFO'::character varying)::text, ('WARNING'::character varying)::text, ('BLOCKER'::character varying)::text]))),
    CONSTRAINT document_parse_issue_status_check CHECK (((status)::text = ANY (ARRAY[('OPEN'::character varying)::text, ('RESOLVED'::character varying)::text, ('IGNORED'::character varying)::text])))
);


--
-- Name: document_parse_issue_source_node; Type: TABLE; Schema: kb; Owner: -
--

CREATE TABLE kb.document_parse_issue_source_node (
    parse_issue_id uuid NOT NULL,
    source_node_key uuid NOT NULL
);


--
-- Name: document_parse_run; Type: TABLE; Schema: kb; Owner: -
--

CREATE TABLE kb.document_parse_run (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    document_id uuid NOT NULL,
    document_version_id uuid NOT NULL,
    run_no integer NOT NULL,
    status character varying(32) NOT NULL,
    parser_version character varying(80),
    provider character varying(120),
    provider_task_id character varying(260),
    error_message character varying(2000),
    result_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    started_at timestamp with time zone,
    finished_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    source_document_jsonb jsonb DEFAULT '{"type": "doc", "content": [], "schemaVersion": 1}'::jsonb NOT NULL,
    document_schema_version integer DEFAULT 1 NOT NULL,
    CONSTRAINT document_parse_run_run_no_check CHECK ((run_no > 0)),
    CONSTRAINT document_parse_run_status_check CHECK (((status)::text = ANY (ARRAY[('QUEUED'::character varying)::text, ('PROCESSING'::character varying)::text, ('SUCCEEDED'::character varying)::text, ('FAILED'::character varying)::text])))
);


--
-- Name: document_processing_step; Type: TABLE; Schema: kb; Owner: -
--

CREATE TABLE kb.document_processing_step (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    document_id uuid NOT NULL,
    document_version_id uuid NOT NULL,
    step_key character varying(32) NOT NULL,
    status character varying(24) DEFAULT 'PENDING'::character varying NOT NULL,
    progress smallint DEFAULT 0 NOT NULL,
    attempt integer DEFAULT 0 NOT NULL,
    provider character varying(120),
    model character varying(160),
    input_sha256 character(64),
    output_sha256 character(64),
    error_message character varying(2000),
    started_at timestamp with time zone,
    finished_at timestamp with time zone,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    parse_run_id uuid,
    review_revision_id uuid,
    CONSTRAINT document_processing_step_attempt_check CHECK ((attempt >= 0)),
    CONSTRAINT document_processing_step_progress_check CHECK (((progress >= 0) AND (progress <= 100))),
    CONSTRAINT document_processing_step_status_check CHECK (((status)::text = ANY (ARRAY[('PENDING'::character varying)::text, ('STALE'::character varying)::text, ('RUNNING'::character varying)::text, ('SUCCEEDED'::character varying)::text, ('FAILED'::character varying)::text, ('PENDING_PROVIDER'::character varying)::text, ('NOT_REQUIRED'::character varying)::text]))),
    CONSTRAINT document_processing_step_step_key_check CHECK (((step_key)::text = ANY (ARRAY[('SCAN'::character varying)::text, ('PARSE'::character varying)::text, ('CHUNK'::character varying)::text, ('EMBEDDING'::character varying)::text, ('BM25_INDEX'::character varying)::text, ('VECTOR_INDEX'::character varying)::text])))
);


--
-- Name: document_result_asset; Type: TABLE; Schema: kb; Owner: -
--

CREATE TABLE kb.document_result_asset (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    document_id uuid NOT NULL,
    document_version_id uuid NOT NULL,
    result_file_id uuid NOT NULL,
    asset_file_id uuid NOT NULL,
    entry_path character varying(1000) NOT NULL,
    content_type character varying(160) NOT NULL,
    size_bytes bigint NOT NULL,
    page_no integer,
    bbox_jsonb jsonb DEFAULT '[]'::jsonb NOT NULL,
    caption text DEFAULT ''::text NOT NULL,
    footnote text DEFAULT ''::text NOT NULL,
    ocr_text text DEFAULT ''::text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT document_result_asset_size_bytes_check CHECK ((size_bytes >= 0))
);


--
-- Name: TABLE document_result_asset; Type: COMMENT; Schema: kb; Owner: -
--

COMMENT ON TABLE kb.document_result_asset IS 'Stable authenticated access mapping for MinerU result images; asset_file_id is the durable asset identifier.';


--
-- Name: document_review_issue_state; Type: TABLE; Schema: kb; Owner: -
--

CREATE TABLE kb.document_review_issue_state (
    review_revision_id uuid NOT NULL,
    parse_issue_id uuid NOT NULL,
    status character varying(24) NOT NULL,
    resolution character varying(1000),
    updated_by uuid,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT document_review_issue_state_status_check CHECK (((status)::text = ANY (ARRAY[('OPEN'::character varying)::text, ('RESOLVED'::character varying)::text, ('IGNORED'::character varying)::text])))
);


--
-- Name: document_review_revision; Type: TABLE; Schema: kb; Owner: -
--

CREATE TABLE kb.document_review_revision (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    document_id uuid NOT NULL,
    document_version_id uuid NOT NULL,
    parse_run_id uuid NOT NULL,
    revision_no integer NOT NULL,
    lock_version integer DEFAULT 0 NOT NULL,
    base_publication_id uuid,
    confirmed_document_jsonb jsonb NOT NULL,
    confirmed_text text DEFAULT ''::text NOT NULL,
    excluded_review_node_ids jsonb DEFAULT '[]'::jsonb NOT NULL,
    status character varying(24) NOT NULL,
    failure_reason character varying(2000),
    created_by uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_by uuid NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    published_at timestamp with time zone,
    CONSTRAINT document_review_revision_lock_version_check CHECK ((lock_version >= 0)),
    CONSTRAINT document_review_revision_revision_no_check CHECK ((revision_no > 0)),
    CONSTRAINT document_review_revision_status_check CHECK (((status)::text = ANY (ARRAY[('DRAFT'::character varying)::text, ('BUILDING'::character varying)::text, ('PUBLISHED'::character varying)::text, ('FAILED'::character varying)::text, ('SUPERSEDED'::character varying)::text])))
);


--
-- Name: TABLE document_review_revision; Type: COMMENT; Schema: kb; Owner: -
--

COMMENT ON TABLE kb.document_review_revision IS 'Editable human-confirmed document revision, independent from parser executions';


--
-- Name: COLUMN document_review_revision.confirmed_text; Type: COMMENT; Schema: kb; Owner: -
--

COMMENT ON COLUMN kb.document_review_revision.confirmed_text IS 'Server-generated deterministic projection; never accepted from clients';


--
-- Name: document_review_table_cell_patch; Type: TABLE; Schema: kb; Owner: -
--

CREATE TABLE kb.document_review_table_cell_patch (
    review_revision_id uuid NOT NULL,
    source_table_id uuid NOT NULL,
    row_no integer NOT NULL,
    column_no integer NOT NULL,
    confirmed_value text NOT NULL,
    updated_by uuid NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: document_review_table_row_state; Type: TABLE; Schema: kb; Owner: -
--

CREATE TABLE kb.document_review_table_row_state (
    review_revision_id uuid NOT NULL,
    source_table_id uuid NOT NULL,
    row_no integer NOT NULL,
    excluded boolean DEFAULT true NOT NULL,
    header boolean DEFAULT false NOT NULL,
    updated_by uuid NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: document_source_node; Type: TABLE; Schema: kb; Owner: -
--

CREATE TABLE kb.document_source_node (
    source_node_key uuid NOT NULL,
    organization_id uuid NOT NULL,
    parse_run_id uuid NOT NULL,
    node_no integer NOT NULL,
    node_type character varying(40) NOT NULL,
    raw_text text DEFAULT ''::text NOT NULL,
    source_anchor_jsonb jsonb DEFAULT '{"kind": "none", "version": 1}'::jsonb NOT NULL,
    confidence_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT document_source_node_node_no_check CHECK ((node_no >= 0))
);


--
-- Name: TABLE document_source_node; Type: COMMENT; Schema: kb; Owner: -
--

COMMENT ON TABLE kb.document_source_node IS 'Immutable semantic nodes and authoritative source anchors for a parse run';


--
-- Name: document_source_table; Type: TABLE; Schema: kb; Owner: -
--

CREATE TABLE kb.document_source_table (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    parse_run_id uuid NOT NULL,
    source_node_key uuid NOT NULL,
    sheet_key character varying(160) NOT NULL,
    sheet_name character varying(260) NOT NULL,
    row_count integer NOT NULL,
    column_count integer NOT NULL,
    non_empty_count integer NOT NULL
);


--
-- Name: document_source_table_cell; Type: TABLE; Schema: kb; Owner: -
--

CREATE TABLE kb.document_source_table_cell (
    source_table_id uuid NOT NULL,
    row_no integer NOT NULL,
    column_no integer NOT NULL,
    display_value text DEFAULT ''::text NOT NULL,
    source_anchor_jsonb jsonb NOT NULL
);


--
-- Name: document_tag; Type: TABLE; Schema: kb; Owner: -
--

CREATE TABLE kb.document_tag (
    document_id uuid NOT NULL,
    tag_id uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: tag; Type: TABLE; Schema: kb; Owner: -
--

CREATE TABLE kb.tag (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    normalized_name character varying(120) NOT NULL,
    display_name character varying(120) NOT NULL,
    created_by uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: term_stat; Type: TABLE; Schema: kb; Owner: -
--

CREATE TABLE kb.term_stat (
    organization_id uuid NOT NULL,
    term character varying(160) NOT NULL,
    document_frequency integer DEFAULT 0 NOT NULL,
    document_count integer DEFAULT 0 NOT NULL,
    average_document_length double precision DEFAULT 0 NOT NULL,
    analyzer_version character varying(40) DEFAULT 'material-smartcn-v3'::character varying NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT term_stat_document_count_check CHECK ((document_count >= 0)),
    CONSTRAINT term_stat_document_frequency_check CHECK ((document_frequency >= 0))
);


--
-- Name: upload_task; Type: TABLE; Schema: kb; Owner: -
--

CREATE TABLE kb.upload_task (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    actor_id uuid NOT NULL,
    file_name character varying(500) NOT NULL,
    size_bytes bigint NOT NULL,
    status character varying(24) NOT NULL,
    progress smallint DEFAULT 0 NOT NULL,
    document_id uuid,
    document_version_id uuid,
    message text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    source_file_id uuid,
    upload_context_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    hidden_at timestamp with time zone,
    CONSTRAINT upload_task_progress_check CHECK (((progress >= 0) AND (progress <= 100))),
    CONSTRAINT upload_task_size_bytes_check CHECK ((size_bytes >= 0)),
    CONSTRAINT upload_task_status_check CHECK (((status)::text = ANY ((ARRAY['STAGING'::character varying, 'CHECKING'::character varying, 'SUBMITTING'::character varying, 'QUEUED'::character varying, 'COMPLETED'::character varying, 'DUPLICATE'::character varying, 'FAILED'::character varying])::text[])))
);


--
-- Name: business_partner; Type: TABLE; Schema: mdm; Owner: -
--

CREATE TABLE mdm.business_partner (
    id uuid NOT NULL,
    partner_code character varying(32) NOT NULL,
    name character varying(200) NOT NULL,
    normalized_name character varying(200) NOT NULL,
    industry character varying(100),
    address character varying(500),
    status character varying(20) NOT NULL,
    remark character varying(1000),
    version bigint DEFAULT 0 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    created_by character varying(100) NOT NULL,
    updated_by character varying(100) NOT NULL,
    customer_level character varying(50),
    cooperation_status character varying(50),
    main_business character varying(500),
    custom_fields jsonb DEFAULT '{}'::jsonb NOT NULL,
    deleted boolean DEFAULT false NOT NULL,
    deleted_at timestamp with time zone,
    deleted_by character varying(160),
    CONSTRAINT business_partner_status_check CHECK (((status)::text = ANY (ARRAY[('ACTIVE'::character varying)::text, ('INACTIVE'::character varying)::text])))
);


--
-- Name: TABLE business_partner; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON TABLE mdm.business_partner IS '统一业务伙伴档案，包括客户、供应商及其他合作方';


--
-- Name: COLUMN business_partner.id; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON COLUMN mdm.business_partner.id IS '业务伙伴主键';


--
-- Name: COLUMN business_partner.partner_code; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON COLUMN mdm.business_partner.partner_code IS '业务伙伴编码';


--
-- Name: COLUMN business_partner.name; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON COLUMN mdm.business_partner.name IS '业务伙伴名称';


--
-- Name: COLUMN business_partner.normalized_name; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON COLUMN mdm.business_partner.normalized_name IS '标准化名称，用于去重';


--
-- Name: COLUMN business_partner.industry; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON COLUMN mdm.business_partner.industry IS '所属行业';


--
-- Name: COLUMN business_partner.address; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON COLUMN mdm.business_partner.address IS '联系地址';


--
-- Name: COLUMN business_partner.status; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON COLUMN mdm.business_partner.status IS '状态：ACTIVE-启用，INACTIVE-停用';


--
-- Name: COLUMN business_partner.remark; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON COLUMN mdm.business_partner.remark IS '备注';


--
-- Name: COLUMN business_partner.version; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON COLUMN mdm.business_partner.version IS '乐观锁版本号';


--
-- Name: COLUMN business_partner.created_at; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON COLUMN mdm.business_partner.created_at IS '创建时间';


--
-- Name: COLUMN business_partner.updated_at; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON COLUMN mdm.business_partner.updated_at IS '最后更新时间';


--
-- Name: COLUMN business_partner.created_by; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON COLUMN mdm.business_partner.created_by IS '创建人';


--
-- Name: COLUMN business_partner.updated_by; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON COLUMN mdm.business_partner.updated_by IS '最后更新人';


--
-- Name: COLUMN business_partner.customer_level; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON COLUMN mdm.business_partner.customer_level IS '客户等级';


--
-- Name: COLUMN business_partner.cooperation_status; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON COLUMN mdm.business_partner.cooperation_status IS '合作状态';


--
-- Name: COLUMN business_partner.main_business; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON COLUMN mdm.business_partner.main_business IS '主营业务';


--
-- Name: COLUMN business_partner.custom_fields; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON COLUMN mdm.business_partner.custom_fields IS '自定义扩展字段';


--
-- Name: COLUMN business_partner.deleted; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON COLUMN mdm.business_partner.deleted IS '逻辑删除标记：true=已删除';


--
-- Name: COLUMN business_partner.deleted_at; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON COLUMN mdm.business_partner.deleted_at IS '删除时间';


--
-- Name: COLUMN business_partner.deleted_by; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON COLUMN mdm.business_partner.deleted_by IS '删除操作人';


--
-- Name: communication_record; Type: TABLE; Schema: mdm; Owner: -
--

CREATE TABLE mdm.communication_record (
    id uuid NOT NULL,
    record_code character varying(32) NOT NULL,
    partner_id uuid NOT NULL,
    communicated_at timestamp with time zone NOT NULL,
    internal_participants character varying(500),
    communication_method character varying(30) NOT NULL,
    content text NOT NULL,
    status character varying(30) NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    created_by character varying(100) NOT NULL,
    updated_by character varying(100) NOT NULL,
    custom_fields jsonb DEFAULT '{}'::jsonb NOT NULL,
    name character varying(200),
    deleted boolean DEFAULT false NOT NULL,
    deleted_at timestamp with time zone,
    deleted_by character varying(160),
    CONSTRAINT communication_record_status_check CHECK (((status)::text = ANY (ARRAY[('OPEN'::character varying)::text, ('FOLLOWING'::character varying)::text, ('CLOSED'::character varying)::text])))
);


--
-- Name: TABLE communication_record; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON TABLE mdm.communication_record IS '客户技术沟通记录，可独立保存或作为项目来源';


--
-- Name: COLUMN communication_record.id; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON COLUMN mdm.communication_record.id IS '沟通记录主键';


--
-- Name: COLUMN communication_record.record_code; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON COLUMN mdm.communication_record.record_code IS '沟通记录编码';


--
-- Name: COLUMN communication_record.partner_id; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON COLUMN mdm.communication_record.partner_id IS '业务伙伴ID';


--
-- Name: COLUMN communication_record.communicated_at; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON COLUMN mdm.communication_record.communicated_at IS '沟通时间';


--
-- Name: COLUMN communication_record.internal_participants; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON COLUMN mdm.communication_record.internal_participants IS '内部参与人员';


--
-- Name: COLUMN communication_record.communication_method; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON COLUMN mdm.communication_record.communication_method IS '沟通方式';


--
-- Name: COLUMN communication_record.content; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON COLUMN mdm.communication_record.content IS '沟通内容';


--
-- Name: COLUMN communication_record.status; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON COLUMN mdm.communication_record.status IS '状态：OPEN-待处理，FOLLOWING-跟进中，CLOSED-已关闭';


--
-- Name: COLUMN communication_record.version; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON COLUMN mdm.communication_record.version IS '乐观锁版本号';


--
-- Name: COLUMN communication_record.created_at; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON COLUMN mdm.communication_record.created_at IS '创建时间';


--
-- Name: COLUMN communication_record.updated_at; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON COLUMN mdm.communication_record.updated_at IS '最后更新时间';


--
-- Name: COLUMN communication_record.created_by; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON COLUMN mdm.communication_record.created_by IS '创建人';


--
-- Name: COLUMN communication_record.updated_by; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON COLUMN mdm.communication_record.updated_by IS '最后更新人';


--
-- Name: COLUMN communication_record.custom_fields; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON COLUMN mdm.communication_record.custom_fields IS '自定义字段（JSON）';


--
-- Name: COLUMN communication_record.name; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON COLUMN mdm.communication_record.name IS '沟通记录名称/主题';


--
-- Name: COLUMN communication_record.deleted_at; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON COLUMN mdm.communication_record.deleted_at IS '删除时间';


--
-- Name: COLUMN communication_record.deleted_by; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON COLUMN mdm.communication_record.deleted_by IS '删除操作人';


--
-- Name: customer_requirement; Type: TABLE; Schema: mdm; Owner: -
--

CREATE TABLE mdm.customer_requirement (
    id uuid NOT NULL,
    requirement_code character varying(32) NOT NULL,
    partner_id uuid,
    title character varying(200) NOT NULL,
    raised_at date,
    delivery_date date,
    status character varying(30) NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    created_by character varying(100) NOT NULL,
    updated_by character varying(100) NOT NULL,
    custom_fields jsonb DEFAULT '{}'::jsonb NOT NULL,
    assigned_project_ids jsonb DEFAULT '[]'::jsonb NOT NULL,
    custom_status_name character varying(50),
    urgency character varying(20),
    raw_requirement text,
    project_id uuid,
    deleted boolean DEFAULT false NOT NULL,
    deleted_at timestamp with time zone,
    deleted_by character varying(160),
    CONSTRAINT customer_requirement_status_check CHECK (((status)::text = ANY (ARRAY[('DRAFT'::character varying)::text, ('CONFIRMED'::character varying)::text, ('IN_PROJECT'::character varying)::text, ('COMPLETED'::character varying)::text, ('CANCELLED'::character varying)::text])))
);


--
-- Name: TABLE customer_requirement; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON TABLE mdm.customer_requirement IS '客户研发需求及结构化性能指标';


--
-- Name: COLUMN customer_requirement.id; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON COLUMN mdm.customer_requirement.id IS '客户需求主键';


--
-- Name: COLUMN customer_requirement.requirement_code; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON COLUMN mdm.customer_requirement.requirement_code IS '需求编码';


--
-- Name: COLUMN customer_requirement.partner_id; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON COLUMN mdm.customer_requirement.partner_id IS '客户业务伙伴ID';


--
-- Name: COLUMN customer_requirement.title; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON COLUMN mdm.customer_requirement.title IS '需求标题';


--
-- Name: COLUMN customer_requirement.raised_at; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON COLUMN mdm.customer_requirement.raised_at IS '提出日期';


--
-- Name: COLUMN customer_requirement.delivery_date; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON COLUMN mdm.customer_requirement.delivery_date IS '预计完成日期';


--
-- Name: COLUMN customer_requirement.status; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON COLUMN mdm.customer_requirement.status IS '状态：DRAFT-草稿，CONFIRMED-已确认，IN_PROJECT-项目中，COMPLETED-已完成，CANCELLED-已取消';


--
-- Name: COLUMN customer_requirement.version; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON COLUMN mdm.customer_requirement.version IS '乐观锁版本号';


--
-- Name: COLUMN customer_requirement.created_at; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON COLUMN mdm.customer_requirement.created_at IS '创建时间';


--
-- Name: COLUMN customer_requirement.updated_at; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON COLUMN mdm.customer_requirement.updated_at IS '最后更新时间';


--
-- Name: COLUMN customer_requirement.created_by; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON COLUMN mdm.customer_requirement.created_by IS '创建人';


--
-- Name: COLUMN customer_requirement.updated_by; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON COLUMN mdm.customer_requirement.updated_by IS '最后更新人';


--
-- Name: COLUMN customer_requirement.custom_fields; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON COLUMN mdm.customer_requirement.custom_fields IS '自定义字段（JSON）';


--
-- Name: COLUMN customer_requirement.assigned_project_ids; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON COLUMN mdm.customer_requirement.assigned_project_ids IS '负责人负责的项目ID列表';


--
-- Name: COLUMN customer_requirement.custom_status_name; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON COLUMN mdm.customer_requirement.custom_status_name IS '自定义状态名称';


--
-- Name: COLUMN customer_requirement.urgency; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON COLUMN mdm.customer_requirement.urgency IS '紧急程度';


--
-- Name: COLUMN customer_requirement.raw_requirement; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON COLUMN mdm.customer_requirement.raw_requirement IS '需求总结';


--
-- Name: COLUMN customer_requirement.deleted; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON COLUMN mdm.customer_requirement.deleted IS '逻辑删除标记：true=已删除';


--
-- Name: COLUMN customer_requirement.deleted_at; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON COLUMN mdm.customer_requirement.deleted_at IS '删除时间';


--
-- Name: COLUMN customer_requirement.deleted_by; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON COLUMN mdm.customer_requirement.deleted_by IS '删除操作人';


--
-- Name: customer_requirement_project; Type: TABLE; Schema: mdm; Owner: -
--

CREATE TABLE mdm.customer_requirement_project (
    requirement_id uuid NOT NULL,
    project_id uuid NOT NULL,
    id uuid NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    created_by character varying(100) NOT NULL,
    updated_by character varying(100) NOT NULL
);


--
-- Name: material; Type: TABLE; Schema: mdm; Owner: -
--

CREATE TABLE mdm.material (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    code character varying(64) NOT NULL,
    name character varying(200) NOT NULL,
    category character varying(50) NOT NULL,
    source_category character varying(50) NOT NULL,
    source_module character varying(50) NOT NULL,
    stage character varying(30),
    contact_person character varying(50),
    status character varying(30) DEFAULT 'DRAFT'::character varying NOT NULL,
    description text,
    version bigint DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(100) DEFAULT 'system'::character varying NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_by character varying(100)
);


--
-- Name: TABLE material; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON TABLE mdm.material IS '资料主表（独立于项目，全局共享）';


--
-- Name: COLUMN material.category; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON COLUMN mdm.material.category IS '资料类型：标准/规范/图纸/文档/其他';


--
-- Name: COLUMN material.source_category; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON COLUMN mdm.material.source_category IS '来源分类：内部/外部';


--
-- Name: COLUMN material.source_module; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON COLUMN mdm.material.source_module IS '来源模块：项目库/工艺库/法规库/客户库';


--
-- Name: COLUMN material.stage; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON COLUMN mdm.material.stage IS '所属阶段：调研/方案/实施/验收/交付';


--
-- Name: COLUMN material.status; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON COLUMN mdm.material.status IS '资料状态：DRAFT/PUBLISHED/ARCHIVED';


--
-- Name: material_alias; Type: TABLE; Schema: mdm; Owner: -
--

CREATE TABLE mdm.material_alias (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    material_id uuid NOT NULL,
    normalized_alias character varying(240) NOT NULL,
    display_alias character varying(240) NOT NULL,
    status character varying(24) DEFAULT 'ACTIVE'::character varying NOT NULL,
    revision bigint DEFAULT 0 NOT NULL,
    created_by uuid NOT NULL,
    updated_by uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT material_alias_status_check CHECK (((status)::text = ANY (ARRAY[('ACTIVE'::character varying)::text, ('RETIRED'::character varying)::text])))
);


--
-- Name: meeting_minutes; Type: TABLE; Schema: mdm; Owner: -
--

CREATE TABLE mdm.meeting_minutes (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    project_id uuid NOT NULL,
    title character varying(200) NOT NULL,
    attendees jsonb DEFAULT '[]'::jsonb NOT NULL,
    summary text,
    occurred_at timestamp with time zone DEFAULT now() NOT NULL,
    archived_to_kb boolean DEFAULT false NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(100) DEFAULT 'system'::character varying NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    updated_by character varying(100) DEFAULT 'system'::character varying NOT NULL
);


--
-- Name: partner_contact; Type: TABLE; Schema: mdm; Owner: -
--

CREATE TABLE mdm.partner_contact (
    id uuid NOT NULL,
    partner_id uuid NOT NULL,
    name character varying(100),
    department character varying(100),
    title character varying(100),
    phone character varying(50),
    email character varying(200),
    wechat character varying(200),
    status character varying(20) NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    created_by character varying(100) NOT NULL,
    updated_by character varying(100) NOT NULL,
    custom_fields jsonb DEFAULT '{}'::jsonb NOT NULL,
    assigned_project_ids jsonb DEFAULT '[]'::jsonb NOT NULL,
    members character varying(1000) DEFAULT ''::character varying NOT NULL,
    deleted boolean DEFAULT false NOT NULL,
    deleted_at timestamp with time zone,
    deleted_by character varying(160),
    CONSTRAINT partner_contact_status_check CHECK (((status)::text = ANY (ARRAY[('ACTIVE'::character varying)::text, ('INACTIVE'::character varying)::text])))
);


--
-- Name: TABLE partner_contact; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON TABLE mdm.partner_contact IS '业务伙伴联系人，停用后保留历史关联';


--
-- Name: COLUMN partner_contact.id; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON COLUMN mdm.partner_contact.id IS '联系人主键';


--
-- Name: COLUMN partner_contact.partner_id; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON COLUMN mdm.partner_contact.partner_id IS '所属业务伙伴ID';


--
-- Name: COLUMN partner_contact.name; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON COLUMN mdm.partner_contact.name IS '联系人姓名';


--
-- Name: COLUMN partner_contact.department; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON COLUMN mdm.partner_contact.department IS '所属部门';


--
-- Name: COLUMN partner_contact.title; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON COLUMN mdm.partner_contact.title IS '职务';


--
-- Name: COLUMN partner_contact.phone; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON COLUMN mdm.partner_contact.phone IS '联系电话';


--
-- Name: COLUMN partner_contact.email; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON COLUMN mdm.partner_contact.email IS '电子邮箱';


--
-- Name: COLUMN partner_contact.wechat; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON COLUMN mdm.partner_contact.wechat IS '微信号';


--
-- Name: COLUMN partner_contact.status; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON COLUMN mdm.partner_contact.status IS '状态：ACTIVE-启用，INACTIVE-停用';


--
-- Name: COLUMN partner_contact.version; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON COLUMN mdm.partner_contact.version IS '乐观锁版本号';


--
-- Name: COLUMN partner_contact.created_at; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON COLUMN mdm.partner_contact.created_at IS '创建时间';


--
-- Name: COLUMN partner_contact.updated_at; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON COLUMN mdm.partner_contact.updated_at IS '最后更新时间';


--
-- Name: COLUMN partner_contact.created_by; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON COLUMN mdm.partner_contact.created_by IS '创建人';


--
-- Name: COLUMN partner_contact.updated_by; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON COLUMN mdm.partner_contact.updated_by IS '最后更新人';


--
-- Name: COLUMN partner_contact.custom_fields; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON COLUMN mdm.partner_contact.custom_fields IS '自定义扩展字段';


--
-- Name: COLUMN partner_contact.assigned_project_ids; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON COLUMN mdm.partner_contact.assigned_project_ids IS '关联的项目ID列表';


--
-- Name: COLUMN partner_contact.members; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON COLUMN mdm.partner_contact.members IS '团队成员（逗号分隔）';


--
-- Name: COLUMN partner_contact.deleted; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON COLUMN mdm.partner_contact.deleted IS '逻辑删除标记：true=已删除';


--
-- Name: COLUMN partner_contact.deleted_at; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON COLUMN mdm.partner_contact.deleted_at IS '删除时间';


--
-- Name: COLUMN partner_contact.deleted_by; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON COLUMN mdm.partner_contact.deleted_by IS '删除操作人';


--
-- Name: partner_contact_project; Type: TABLE; Schema: mdm; Owner: -
--

CREATE TABLE mdm.partner_contact_project (
    contact_id uuid NOT NULL,
    project_id uuid NOT NULL,
    id uuid,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    created_by character varying(100) NOT NULL,
    updated_by character varying(100) NOT NULL
);


--
-- Name: project_document; Type: TABLE; Schema: mdm; Owner: -
--

CREATE TABLE mdm.project_document (
    id uuid NOT NULL,
    project_id uuid NOT NULL,
    title character varying(260) NOT NULL,
    format character varying(16) NOT NULL,
    source character varying(16) DEFAULT 'BLANK'::character varying NOT NULL,
    template_id uuid,
    template_version_id uuid,
    file_object_id uuid,
    status character varying(24) DEFAULT 'DRAFT'::character varying NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(100) DEFAULT 'system'::character varying NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_by character varying(100),
    deleted boolean DEFAULT false NOT NULL,
    content_snapshot jsonb,
    content_schema jsonb DEFAULT '{}'::jsonb NOT NULL,
    content_mapping jsonb DEFAULT '[]'::jsonb NOT NULL,
    content_data jsonb DEFAULT '{}'::jsonb NOT NULL,
    content_recognition jsonb DEFAULT '{}'::jsonb NOT NULL,
    content_structure jsonb DEFAULT '{}'::jsonb NOT NULL,
    current_version_id uuid,
    CONSTRAINT project_document_format_check CHECK (((format)::text = ANY (ARRAY[('DOCX'::character varying)::text, ('XLSX'::character varying)::text, ('OTHER'::character varying)::text]))),
    CONSTRAINT project_document_source_check CHECK (((source)::text = ANY (ARRAY[('TEMPLATE'::character varying)::text, ('BLANK'::character varying)::text, ('IMPORT'::character varying)::text]))),
    CONSTRAINT project_document_status_check CHECK (((status)::text = ANY (ARRAY[('DRAFT'::character varying)::text, ('PUBLISHED'::character varying)::text, ('ARCHIVED'::character varying)::text])))
);


--
-- Name: TABLE project_document; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON TABLE mdm.project_document IS '项目文档：归属研发项目，可基于模板中心模板、空白或导入文件创建';


--
-- Name: COLUMN project_document.project_id; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON COLUMN mdm.project_document.project_id IS '所属研发项目ID';


--
-- Name: COLUMN project_document.title; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON COLUMN mdm.project_document.title IS '文档名称';


--
-- Name: COLUMN project_document.format; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON COLUMN mdm.project_document.format IS '文档格式：DOCX/Excel/其他';


--
-- Name: COLUMN project_document.source; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON COLUMN mdm.project_document.source IS '创建来源：按模板新增/空白新增/导入文件';


--
-- Name: COLUMN project_document.template_id; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON COLUMN mdm.project_document.template_id IS '源模板ID（按模板新增时填写）';


--
-- Name: COLUMN project_document.template_version_id; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON COLUMN mdm.project_document.template_version_id IS '源模板版本ID（按模板新增时填写，用于引用模板中心查看）';


--
-- Name: COLUMN project_document.file_object_id; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON COLUMN mdm.project_document.file_object_id IS '实际文件对象ID（复用 ops.file_object 存储）';


--
-- Name: COLUMN project_document.status; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON COLUMN mdm.project_document.status IS '文档状态：草稿/已发布/已归档';


--
-- Name: COLUMN project_document.deleted; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON COLUMN mdm.project_document.deleted IS '逻辑删除标记';


--
-- Name: COLUMN project_document.content_snapshot; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON COLUMN mdm.project_document.content_snapshot IS '项目文档独立编辑快照；首次打开模板文档时复制模板快照';


--
-- Name: COLUMN project_document.content_recognition; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON COLUMN mdm.project_document.content_recognition IS '项目文档独立识别结果：含解析结构(structureSummary)、编辑器快照(initialEditorSnapshot)与规则识别候选(ruleSuggestions)；与模板中心解耦';


--
-- Name: COLUMN project_document.content_structure; Type: COMMENT; Schema: mdm; Owner: -
--

COMMENT ON COLUMN mdm.project_document.content_structure IS '项目文档 Word 章节结构：含 DOCUMENT_TITLE / HEADING 节点，用于左侧文档目录展示；与模板中心解耦';


--
-- Name: project_document_audit; Type: TABLE; Schema: mdm; Owner: -
--

CREATE TABLE mdm.project_document_audit (
    id uuid NOT NULL,
    document_id uuid NOT NULL,
    action character varying(32) NOT NULL,
    before_jsonb jsonb,
    after_jsonb jsonb,
    operator_id character varying(64),
    operator_name character varying(128),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    document_version_id uuid,
    trace_id character varying(100)
);


--
-- Name: project_document_review; Type: TABLE; Schema: mdm; Owner: -
--

CREATE TABLE mdm.project_document_review (
    id uuid NOT NULL,
    document_id uuid NOT NULL,
    action text NOT NULL,
    operator_id uuid NOT NULL,
    operator_name text,
    comment text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    document_version_id uuid
);


--
-- Name: project_document_version; Type: TABLE; Schema: mdm; Owner: -
--

CREATE TABLE mdm.project_document_version (
    id uuid NOT NULL,
    document_id uuid NOT NULL,
    version_no integer NOT NULL,
    content_snapshot jsonb,
    content_schema jsonb DEFAULT '{}'::jsonb NOT NULL,
    content_mapping jsonb DEFAULT '[]'::jsonb NOT NULL,
    content_data jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(100) NOT NULL,
    template_version_id uuid,
    template_snapshot_hash character varying(64),
    submitted_at timestamp with time zone,
    published_at timestamp with time zone,
    status character varying(32) DEFAULT 'DRAFT'::character varying NOT NULL,
    content_jsonb jsonb DEFAULT '{}'::jsonb,
    snapshot_reason character varying(255),
    template_snapshot_jsonb jsonb
);


--
-- Name: project_material; Type: TABLE; Schema: mdm; Owner: -
--

CREATE TABLE mdm.project_material (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    project_id uuid NOT NULL,
    material_id uuid NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    created_by character varying(100) NOT NULL,
    updated_by character varying(100) NOT NULL
);


--
-- Name: inventory_policy; Type: TABLE; Schema: mfg; Owner: -
--

CREATE TABLE mfg.inventory_policy (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    product_id uuid NOT NULL,
    inventory_scope character varying(20) NOT NULL,
    package_kg numeric(20,6),
    low_stock_kg numeric(20,6) DEFAULT 0 NOT NULL,
    created_by uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_by uuid NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT inventory_policy_inventory_scope_check CHECK (((inventory_scope)::text = ANY (ARRAY[('RND'::character varying)::text, ('PRODUCTION'::character varying)::text]))),
    CONSTRAINT inventory_policy_low_stock_kg_check CHECK ((low_stock_kg >= (0)::numeric)),
    CONSTRAINT inventory_policy_package_kg_check CHECK (((package_kg IS NULL) OR (package_kg > (0)::numeric)))
);


--
-- Name: inventory_product; Type: TABLE; Schema: mfg; Owner: -
--

CREATE TABLE mfg.inventory_product (
    organization_id uuid NOT NULL,
    id uuid NOT NULL,
    code character varying(64) NOT NULL,
    name character varying(200) NOT NULL,
    category character varying(50) DEFAULT '库存产品'::character varying NOT NULL,
    status character varying(20) DEFAULT 'ACTIVE'::character varying NOT NULL,
    created_by uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    inventory_scope character varying(20) NOT NULL,
    CONSTRAINT inventory_product_scope_check CHECK (((inventory_scope)::text = ANY (ARRAY[('RND'::character varying)::text, ('PRODUCTION'::character varying)::text])))
);


--
-- Name: inventory_sample_dispatch; Type: TABLE; Schema: mfg; Owner: -
--

CREATE TABLE mfg.inventory_sample_dispatch (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    dispatch_no character varying(100) NOT NULL,
    business_date date NOT NULL,
    product_id uuid NOT NULL,
    quantity_kg numeric(20,6) NOT NULL,
    customer_name character varying(240) NOT NULL,
    recipient_name character varying(120) NOT NULL,
    sample_address character varying(500),
    courier_company character varying(120),
    tracking_no character varying(120),
    client_contact character varying(120),
    customer_requirement character varying(2000),
    customer_feedback character varying(2000),
    inventory_transaction_id uuid,
    actor_id uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    customer_id uuid,
    inventory_scope character varying(32) DEFAULT 'RND'::character varying NOT NULL,
    recipient_phone character varying(64),
    client_contact_phone character varying(64),
    sender_name character varying(128),
    status character varying(16) DEFAULT 'PENDING'::character varying NOT NULL,
    specification character varying(200),
    package_quantity numeric(18,6),
    CONSTRAINT inventory_sample_dispatch_completion_transaction_check CHECK (((((status)::text = 'PENDING'::text) AND (inventory_transaction_id IS NULL)) OR (((status)::text = 'COMPLETED'::text) AND (inventory_transaction_id IS NOT NULL)))),
    CONSTRAINT inventory_sample_dispatch_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'COMPLETED'::character varying])::text[]))),
    CONSTRAINT sample_dispatch_quantity_kg_check CHECK ((quantity_kg > (0)::numeric))
);


--
-- Name: COLUMN inventory_sample_dispatch.inventory_scope; Type: COMMENT; Schema: mfg; Owner: -
--

COMMENT ON COLUMN mfg.inventory_sample_dispatch.inventory_scope IS '扣减库存域：RND=研发库存，PRODUCTION=生产库存';


--
-- Name: inventory_shipment; Type: TABLE; Schema: mfg; Owner: -
--

CREATE TABLE mfg.inventory_shipment (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    shipment_no character varying(100) NOT NULL,
    product_id uuid NOT NULL,
    quantity_kg numeric(20,6) NOT NULL,
    customer_name character varying(240) NOT NULL,
    note character varying(2000),
    inventory_transaction_id uuid,
    actor_id uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    customer_id uuid,
    inventory_scope character varying(32) DEFAULT 'PRODUCTION'::character varying NOT NULL,
    sender_name character varying(128),
    batch_no character varying(128),
    specification character varying(255),
    total_quantity_kg numeric(18,6),
    recipient_name character varying(128),
    recipient_phone character varying(64),
    shipping_address text,
    courier_company character varying(128),
    tracking_no character varying(128),
    package_quantity numeric(18,6),
    status character varying(16) DEFAULT 'PENDING'::character varying NOT NULL,
    coa_no character varying(255),
    CONSTRAINT inventory_shipment_completion_transaction_check CHECK (((((status)::text = 'PENDING'::text) AND (inventory_transaction_id IS NULL)) OR (((status)::text = 'COMPLETED'::text) AND (inventory_transaction_id IS NOT NULL)))),
    CONSTRAINT inventory_shipment_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'COMPLETED'::character varying])::text[]))),
    CONSTRAINT shipment_quantity_kg_check CHECK ((quantity_kg > (0)::numeric))
);


--
-- Name: COLUMN inventory_shipment.inventory_scope; Type: COMMENT; Schema: mfg; Owner: -
--

COMMENT ON COLUMN mfg.inventory_shipment.inventory_scope IS '扣减库存域：RND=研发库存，PRODUCTION=生产库存';


--
-- Name: inventory_shipment_header; Type: TABLE; Schema: mfg; Owner: -
--

CREATE TABLE mfg.inventory_shipment_header (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    shipment_no text NOT NULL,
    business_date date NOT NULL,
    actor_id uuid,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    note text
);


--
-- Name: inventory_stock_balance; Type: TABLE; Schema: mfg; Owner: -
--

CREATE TABLE mfg.inventory_stock_balance (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    product_id uuid NOT NULL,
    inventory_scope character varying(20) NOT NULL,
    quantity_kg numeric(20,6) DEFAULT 0 NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT stock_balance_inventory_scope_check CHECK (((inventory_scope)::text = ANY (ARRAY[('RND'::character varying)::text, ('PRODUCTION'::character varying)::text])))
);


--
-- Name: inventory_stock_import; Type: TABLE; Schema: mfg; Owner: -
--

CREATE TABLE mfg.inventory_stock_import (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    status character varying(20) NOT NULL,
    rows_jsonb jsonb NOT NULL,
    errors_jsonb jsonb DEFAULT '[]'::jsonb NOT NULL,
    actor_id uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    committed_at timestamp with time zone,
    CONSTRAINT initial_stock_import_status_check CHECK (((status)::text = ANY (ARRAY[('VALIDATED'::character varying)::text, ('INVALID'::character varying)::text, ('COMMITTED'::character varying)::text])))
);


--
-- Name: inventory_transaction; Type: TABLE; Schema: mfg; Owner: -
--

CREATE TABLE mfg.inventory_transaction (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    product_id uuid NOT NULL,
    inventory_scope character varying(20) NOT NULL,
    direction character varying(12) NOT NULL,
    reason character varying(40) NOT NULL,
    quantity_kg numeric(20,6) NOT NULL,
    before_kg numeric(20,6) NOT NULL,
    after_kg numeric(20,6) NOT NULL,
    business_date date NOT NULL,
    document_no character varying(100) NOT NULL,
    business_type character varying(40) DEFAULT 'MANUAL'::character varying NOT NULL,
    business_id uuid,
    reversal_of uuid,
    idempotency_key character varying(120),
    note character varying(2000),
    actor_id uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    actor_name character varying(255),
    CONSTRAINT inventory_transaction_direction_check CHECK (((direction)::text = ANY (ARRAY[('INBOUND'::character varying)::text, ('OUTBOUND'::character varying)::text]))),
    CONSTRAINT inventory_transaction_inventory_scope_check CHECK (((inventory_scope)::text = ANY (ARRAY[('RND'::character varying)::text, ('PRODUCTION'::character varying)::text]))),
    CONSTRAINT inventory_transaction_quantity_kg_check CHECK ((quantity_kg > (0)::numeric))
);


--
-- Name: COLUMN inventory_transaction.actor_name; Type: COMMENT; Schema: mfg; Owner: -
--

COMMENT ON COLUMN mfg.inventory_transaction.actor_name IS '经办人覆盖名；为空时回退到 actor_id 对应的用户显示名';


--
-- Name: production_ingest_item; Type: TABLE; Schema: mfg; Owner: -
--

CREATE TABLE mfg.production_ingest_item (
    id uuid NOT NULL,
    ingest_job_id uuid NOT NULL,
    item_key character varying(240) NOT NULL,
    item_kind character varying(16) NOT NULL,
    binding_id character varying(160),
    field_code character varying(160),
    data_path character varying(400) NOT NULL,
    record_key character varying(240),
    record_index integer,
    raw_value_jsonb jsonb,
    normalized_value_jsonb jsonb,
    user_value_jsonb jsonb,
    source_locator_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    confidence numeric(6,5) DEFAULT 1.0 NOT NULL,
    review_status character varying(20) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT production_ingest_item_item_kind_check CHECK (((item_kind)::text = ANY (ARRAY[('SCALAR'::character varying)::text, ('DETAIL'::character varying)::text]))),
    CONSTRAINT production_ingest_item_review_status_check CHECK (((review_status)::text = ANY (ARRAY[('EXTRACTED'::character varying)::text, ('NEEDS_REVIEW'::character varying)::text, ('CONFIRMED'::character varying)::text, ('REJECTED'::character varying)::text])))
);


--
-- Name: production_ingest_job; Type: TABLE; Schema: mfg; Owner: -
--

CREATE TABLE mfg.production_ingest_job (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    production_order_id uuid NOT NULL,
    requested_template_version_id uuid NOT NULL,
    selected_template_version_id uuid,
    source_type character varying(16) NOT NULL,
    match_mode character varying(32) DEFAULT 'PENDING'::character varying NOT NULL,
    status character varying(24) NOT NULL,
    template_match_score numeric(6,5),
    result_version integer DEFAULT 0 NOT NULL,
    result_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    error_message text,
    created_by uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    confirmed_at timestamp with time zone,
    CONSTRAINT production_ingest_job_match_mode_check CHECK (((match_mode)::text = ANY (ARRAY[('PENDING'::character varying)::text, ('EXACT_MANIFEST'::character varying)::text, ('SIMILAR_AUTO'::character varying)::text, ('USER_REVIEW'::character varying)::text, ('USER_SELECTED_TEMPLATE'::character varying)::text, ('INSTANCE_VALUE_EXTRACTION'::character varying)::text]))),
    CONSTRAINT production_ingest_job_source_type_check CHECK (((source_type)::text = ANY (ARRAY[('XLSX'::character varying)::text, ('PHOTO'::character varying)::text]))),
    CONSTRAINT production_ingest_job_status_check CHECK (((status)::text = ANY (ARRAY[('QUEUED'::character varying)::text, ('PROCESSING'::character varying)::text, ('REVIEW_REQUIRED'::character varying)::text, ('CONFIRMED'::character varying)::text, ('FAILED'::character varying)::text, ('CANCELLED'::character varying)::text])))
);


--
-- Name: production_ingest_source; Type: TABLE; Schema: mfg; Owner: -
--

CREATE TABLE mfg.production_ingest_source (
    ingest_job_id uuid NOT NULL,
    file_object_id uuid NOT NULL,
    page_order integer DEFAULT 0 NOT NULL,
    CONSTRAINT production_ingest_source_page_order_check CHECK ((page_order >= 0))
);


--
-- Name: production_order; Type: TABLE; Schema: mfg; Owner: -
--

CREATE TABLE mfg.production_order (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    order_no character varying(80) NOT NULL,
    status character varying(24) NOT NULL,
    template_version_id uuid NOT NULL,
    product_id uuid,
    quantity numeric(20,6),
    unit_code character varying(40),
    planned_date date,
    owner_id uuid,
    instance_schema_jsonb jsonb NOT NULL,
    instance_mapping_jsonb jsonb NOT NULL,
    draft_data_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    draft_editor_snapshot_file_id uuid,
    draft_editor_snapshot_hash character(64),
    snapshot_kind character varying(32) NOT NULL,
    editor_app_version character varying(80) NOT NULL,
    plugin_manifest_hash character(64) NOT NULL,
    snapshot_format_version integer DEFAULT 1 NOT NULL,
    schema_hash character(64) NOT NULL,
    mapping_hash character(64) NOT NULL,
    data_hash character(64) NOT NULL,
    workspace_hash character(64) NOT NULL,
    lock_version bigint DEFAULT 0 NOT NULL,
    created_by uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_by uuid,
    CONSTRAINT production_order_snapshot_kind_check CHECK (((snapshot_kind)::text = ANY (ARRAY[('UNIVER_WORKBOOK'::character varying)::text, ('UNIVER_DOCUMENT'::character varying)::text]))),
    CONSTRAINT production_order_status_check CHECK (((status)::text = ANY (ARRAY[('DRAFT'::character varying)::text, ('SUBMITTED'::character varying)::text, ('CANCELLED'::character varying)::text])))
);


--
-- Name: production_upload; Type: TABLE; Schema: mfg; Owner: -
--

CREATE TABLE mfg.production_upload (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    file_id uuid NOT NULL,
    production_name character varying(200),
    order_no character varying(80),
    product_name character varying(200),
    category character varying(120),
    manufacture_date date,
    project_id uuid,
    project_name character varying(300),
    stage_id uuid,
    stage_name character varying(200),
    task_id uuid,
    task_name character varying(300),
    visibility character varying(24) DEFAULT 'ALL'::character varying NOT NULL,
    status character varying(24) DEFAULT 'QUEUED'::character varying NOT NULL,
    created_by uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    workbook_snapshot_jsonb jsonb,
    lock_version bigint DEFAULT 0 NOT NULL,
    updated_by uuid,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    async_job_id uuid,
    selected_template_version_id uuid,
    recognition_progress integer DEFAULT 0 NOT NULL,
    current_stage character varying(64),
    match_mode character varying(32),
    template_match_score numeric(6,5),
    recognition_result_jsonb jsonb,
    structure_summary_jsonb jsonb,
    failure_message character varying(2000),
    recognized_at timestamp with time zone,
    source_type character varying(16) DEFAULT 'XLSX'::character varying NOT NULL,
    CONSTRAINT production_upload_source_type_check CHECK (((source_type)::text = ANY (ARRAY[('XLSX'::character varying)::text, ('PHOTO'::character varying)::text]))),
    CONSTRAINT production_upload_status_check CHECK (((status)::text = ANY (ARRAY[('QUEUED'::character varying)::text, ('PARSING'::character varying)::text, ('MATCHING_TEMPLATE'::character varying)::text, ('EXTRACTING'::character varying)::text, ('REVIEW_REQUIRED'::character varying)::text, ('SAVED'::character varying)::text, ('PUBLISHED'::character varying)::text, ('FAILED'::character varying)::text, ('DELETED'::character varying)::text]))),
    CONSTRAINT production_upload_visibility_check CHECK (((visibility)::text = ANY (ARRAY[('ALL'::character varying)::text, ('QUALITY'::character varying)::text, ('PROJECT'::character varying)::text])))
);


--
-- Name: production_upload_field; Type: TABLE; Schema: mfg; Owner: -
--

CREATE TABLE mfg.production_upload_field (
    id uuid NOT NULL,
    production_upload_id uuid NOT NULL,
    item_key character varying(300) NOT NULL,
    item_kind character varying(32) NOT NULL,
    binding_id character varying(300),
    field_code character varying(200),
    data_path character varying(500),
    record_index integer,
    raw_value_jsonb jsonb,
    normalized_value_jsonb jsonb,
    source_locator_jsonb jsonb,
    confidence numeric(6,5),
    review_status character varying(32) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: production_upload_version; Type: TABLE; Schema: mfg; Owner: -
--

CREATE TABLE mfg.production_upload_version (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    production_upload_id uuid NOT NULL,
    version_no integer NOT NULL,
    workbook_snapshot_jsonb jsonb NOT NULL,
    lock_version bigint NOT NULL,
    change_type character varying(24) DEFAULT 'PUBLISH'::character varying NOT NULL,
    created_by uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: sample_recipient_preference; Type: TABLE; Schema: mfg; Owner: -
--

CREATE TABLE mfg.sample_recipient_preference (
    customer_id uuid NOT NULL,
    recipient_name character varying(100) NOT NULL,
    recipient_phone character varying(64) NOT NULL,
    sample_address character varying(500) NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: async_job; Type: TABLE; Schema: ops; Owner: -
--

CREATE TABLE ops.async_job (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    job_type character varying(80) NOT NULL,
    status character varying(20) NOT NULL,
    priority smallint DEFAULT 100 NOT NULL,
    payload_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    result_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    progress smallint DEFAULT 0 NOT NULL,
    current_stage character varying(120),
    attempt_count integer DEFAULT 0 NOT NULL,
    max_attempts integer DEFAULT 5 NOT NULL,
    lease_owner character varying(160),
    lease_expires_at timestamp with time zone,
    next_attempt_at timestamp with time zone DEFAULT now() NOT NULL,
    last_error text,
    idempotency_key character varying(160),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    started_at timestamp with time zone,
    finished_at timestamp with time zone,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    lease_token uuid,
    lease_generation bigint DEFAULT 0 NOT NULL,
    CONSTRAINT async_job_max_attempts_check CHECK ((max_attempts > 0)),
    CONSTRAINT async_job_no_legacy_ai_types CHECK (((job_type)::text <> ALL (ARRAY[('RESEARCH_RUN'::character varying)::text, ('FORMULA_MODEL_BUILD'::character varying)::text, ('AI_FORMULA_MODEL_BUILD'::character varying)::text]))),
    CONSTRAINT async_job_progress_check CHECK (((progress >= 0) AND (progress <= 100))),
    CONSTRAINT async_job_status_check CHECK (((status)::text = ANY (ARRAY[('READY'::character varying)::text, ('RUNNING'::character varying)::text, ('SUCCEEDED'::character varying)::text, ('FAILED'::character varying)::text, ('CANCELLED'::character varying)::text])))
);


--
-- Name: audit_log; Type: TABLE; Schema: ops; Owner: -
--

CREATE TABLE ops.audit_log (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    actor_id uuid,
    action character varying(120) NOT NULL,
    aggregate_type character varying(80) NOT NULL,
    aggregate_id uuid NOT NULL,
    detail_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: outbox_event; Type: TABLE; Schema: ops; Owner: -
--

CREATE TABLE ops.outbox_event (
    id uuid NOT NULL,
    aggregate_type character varying(80) NOT NULL,
    aggregate_id uuid NOT NULL,
    event_type character varying(120) NOT NULL,
    payload_jsonb jsonb NOT NULL,
    status character varying(20) DEFAULT 'PENDING'::character varying NOT NULL,
    attempt_count integer DEFAULT 0 NOT NULL,
    next_attempt_at timestamp with time zone DEFAULT now() NOT NULL,
    lease_owner character varying(160),
    lease_expires_at timestamp with time zone,
    last_error text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    published_at timestamp with time zone,
    organization_id uuid,
    idempotency_key character varying(200),
    CONSTRAINT outbox_event_status_check CHECK (((status)::text = ANY (ARRAY[('PENDING'::character varying)::text, ('PROCESSING'::character varying)::text, ('PUBLISHED'::character varying)::text, ('FAILED'::character varying)::text])))
);


--
-- Name: flyway_schema_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.flyway_schema_history (
    installed_rank integer NOT NULL,
    version character varying(50),
    description character varying(200) NOT NULL,
    type character varying(20) NOT NULL,
    script character varying(1000) NOT NULL,
    checksum integer,
    installed_by character varying(100) NOT NULL,
    installed_on timestamp without time zone DEFAULT now() NOT NULL,
    execution_time integer NOT NULL,
    success boolean NOT NULL
);


--
-- Name: data_category; Type: TABLE; Schema: quality; Owner: -
--

CREATE TABLE quality.data_category (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    business_type character varying(24) NOT NULL,
    name character varying(20) NOT NULL,
    description character varying(80) NOT NULL,
    system_default boolean DEFAULT false NOT NULL,
    sort_order integer DEFAULT 0 NOT NULL,
    created_by uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT data_category_business_type_check CHECK (((business_type)::text = ANY (ARRAY[('standard'::character varying)::text, ('record'::character varying)::text, ('coa'::character varying)::text, ('defect'::character varying)::text, ('manual'::character varying)::text, ('msds'::character varying)::text, ('label'::character varying)::text])))
);


--
-- Name: data_record; Type: TABLE; Schema: quality; Owner: -
--

CREATE TABLE quality.data_record (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    business_type character varying(24) NOT NULL,
    category_id uuid NOT NULL,
    business_no character varying(96) NOT NULL,
    data_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    source_file_id uuid,
    project_id uuid,
    project_name character varying(300),
    stage_id uuid,
    stage_name character varying(300),
    task_id uuid,
    task_name character varying(300),
    visibility character varying(24) DEFAULT 'ALL'::character varying NOT NULL,
    created_by uuid NOT NULL,
    updated_by uuid NOT NULL,
    lock_version bigint DEFAULT 0 NOT NULL,
    deleted boolean DEFAULT false NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    workbook_snapshot_jsonb jsonb,
    display_name character varying(300),
    CONSTRAINT data_record_business_type_check CHECK (((business_type)::text = ANY (ARRAY[('standard'::character varying)::text, ('record'::character varying)::text, ('coa'::character varying)::text, ('defect'::character varying)::text, ('manual'::character varying)::text, ('msds'::character varying)::text, ('label'::character varying)::text]))),
    CONSTRAINT data_record_visibility_check CHECK (((visibility)::text = ANY (ARRAY[('ALL'::character varying)::text, ('RND'::character varying)::text, ('QUALITY'::character varying)::text, ('PROJECT'::character varying)::text])))
);


--
-- Name: data_record_version; Type: TABLE; Schema: quality; Owner: -
--

CREATE TABLE quality.data_record_version (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    record_id uuid NOT NULL,
    version_no integer NOT NULL,
    business_no character varying(96) NOT NULL,
    data_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    lock_version bigint NOT NULL,
    change_type character varying(24) DEFAULT 'EDIT'::character varying NOT NULL,
    created_by uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    workbook_snapshot_jsonb jsonb,
    CONSTRAINT data_record_version_change_type_check CHECK (((change_type)::text = ANY (ARRAY[('INITIAL'::character varying)::text, ('EDIT'::character varying)::text, ('UPLOAD'::character varying)::text, ('PUBLISH'::character varying)::text]))),
    CONSTRAINT data_record_version_version_no_check CHECK ((version_no > 0))
);


--
-- Name: upload_file; Type: TABLE; Schema: quality; Owner: -
--

CREATE TABLE quality.upload_file (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    file_id uuid NOT NULL,
    category_id uuid NOT NULL,
    original_name character varying(500) NOT NULL,
    content_type character varying(160),
    file_size bigint NOT NULL,
    sha256 character varying(64),
    project_id uuid,
    project_name character varying(300),
    stage_id uuid,
    stage_name character varying(300),
    task_id uuid,
    task_name character varying(300),
    visibility character varying(24) NOT NULL,
    status character varying(24) DEFAULT 'UPLOADED'::character varying NOT NULL,
    generated_record_id uuid,
    error_message character varying(1000),
    created_by uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    deleted boolean DEFAULT false NOT NULL,
    CONSTRAINT upload_file_status_check CHECK (((status)::text = ANY (ARRAY[('UPLOADED'::character varying)::text, ('PARSING'::character varying)::text, ('DRAFT_CREATED'::character varying)::text, ('FAILED'::character varying)::text]))),
    CONSTRAINT upload_file_visibility_check CHECK (((visibility)::text = ANY (ARRAY[('ALL'::character varying)::text, ('RND'::character varying)::text, ('QUALITY'::character varying)::text, ('PROJECT'::character varying)::text])))
);


--
-- Name: comprehensive_report_no_seq; Type: SEQUENCE; Schema: rnd; Owner: -
--

CREATE SEQUENCE rnd.comprehensive_report_no_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: experiment_ai_source_evidence; Type: TABLE; Schema: rnd; Owner: -
--

CREATE TABLE rnd.experiment_ai_source_evidence (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    experiment_id uuid NOT NULL,
    experiment_version_id uuid NOT NULL,
    legacy_research_run_id uuid,
    legacy_candidate_id uuid,
    request_hash character(64) NOT NULL,
    formula_jsonb jsonb NOT NULL,
    predictions_jsonb jsonb NOT NULL,
    model_bindings_jsonb jsonb NOT NULL,
    source_summary_jsonb jsonb NOT NULL,
    evidence_hash character(64) NOT NULL,
    captured_at timestamp with time zone DEFAULT now() NOT NULL,
    captured_by uuid,
    research_run_id uuid,
    research_candidate_id uuid,
    baseline_snapshot_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    target_evidence_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    request_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
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


--
-- Name: TABLE experiment_ai_source_evidence; Type: COMMENT; Schema: rnd; Owner: -
--

COMMENT ON TABLE rnd.experiment_ai_source_evidence IS 'Immutable experiment-side evidence retained across R10 legacy AI runtime removal';


--
-- Name: experiment_attachment; Type: TABLE; Schema: rnd; Owner: -
--

CREATE TABLE rnd.experiment_attachment (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    experiment_id uuid NOT NULL,
    experiment_version_id uuid NOT NULL,
    file_id uuid NOT NULL,
    file_version_id uuid,
    attachment_type character varying(40) DEFAULT 'ATTACHMENT'::character varying NOT NULL,
    section_key character varying(100),
    file_name character varying(500),
    description character varying(1000),
    created_by uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: experiment_audit; Type: TABLE; Schema: rnd; Owner: -
--

CREATE TABLE rnd.experiment_audit (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    experiment_id uuid NOT NULL,
    experiment_version_id uuid,
    action character varying(60) NOT NULL,
    before_jsonb jsonb,
    after_jsonb jsonb,
    operator_id uuid NOT NULL,
    operator_name character varying(100),
    trace_id character varying(100),
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: experiment_category; Type: TABLE; Schema: rnd; Owner: -
--

CREATE TABLE rnd.experiment_category (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    code character varying(40) NOT NULL,
    name character varying(100) NOT NULL,
    active boolean DEFAULT true NOT NULL,
    revision bigint DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    description character varying(500) DEFAULT ''::character varying NOT NULL
);


--
-- Name: experiment_import_job; Type: TABLE; Schema: rnd; Owner: -
--

CREATE TABLE rnd.experiment_import_job (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    source_file_id uuid NOT NULL,
    source_file_name character varying(500) NOT NULL,
    source_sha256 character(64) NOT NULL,
    status character varying(30) DEFAULT 'UPLOADED'::character varying NOT NULL,
    duplicate_override boolean DEFAULT false NOT NULL,
    duplicate_reason character varying(1000),
    parse_result_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    experiment_id uuid,
    error_message character varying(2000),
    created_by uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    source_format character varying(10) NOT NULL,
    project_id uuid,
    stage_id uuid,
    task_id uuid,
    visibility character varying(24) DEFAULT 'ALL'::character varying NOT NULL,
    category_name character varying(200),
    import_options_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    CONSTRAINT ck_experiment_import_visibility CHECK (((visibility)::text = ANY (ARRAY[('ALL'::character varying)::text, ('QUALITY'::character varying)::text, ('PROJECT'::character varying)::text])))
);


--
-- Name: experiment_no_seq; Type: SEQUENCE; Schema: rnd; Owner: -
--

CREATE SEQUENCE rnd.experiment_no_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: experiment_outbox; Type: TABLE; Schema: rnd; Owner: -
--

CREATE TABLE rnd.experiment_outbox (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    aggregate_id uuid NOT NULL,
    event_type character varying(100) NOT NULL,
    payload_jsonb jsonb NOT NULL,
    status character varying(20) DEFAULT 'PENDING'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    published_at timestamp with time zone
);


--
-- Name: experiment_report; Type: TABLE; Schema: rnd; Owner: -
--

CREATE TABLE rnd.experiment_report (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    experiment_id uuid NOT NULL,
    experiment_version_id uuid NOT NULL,
    report_type character varying(30) NOT NULL,
    status character varying(30) DEFAULT 'COMPLETED'::character varying NOT NULL,
    file_id uuid,
    created_by uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: experiment_review; Type: TABLE; Schema: rnd; Owner: -
--

CREATE TABLE rnd.experiment_review (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    experiment_id uuid NOT NULL,
    experiment_version_id uuid NOT NULL,
    action character varying(30) NOT NULL,
    comment character varying(2000),
    operator_id uuid NOT NULL,
    operator_name character varying(100),
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: experiment_source_reference; Type: TABLE; Schema: rnd; Owner: -
--

CREATE TABLE rnd.experiment_source_reference (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    experiment_id uuid NOT NULL,
    experiment_version_id uuid NOT NULL,
    recognition_job_id uuid,
    experiment_import_job_id uuid,
    confirmed_submission_id uuid,
    experiment_boundary_id character varying(240) NOT NULL,
    sample_boundary_id character varying(240) NOT NULL,
    logical_sample_key character varying(320) NOT NULL,
    source_group_keys_jsonb jsonb NOT NULL,
    source_file_id uuid NOT NULL,
    source_file_sha256 character(64) NOT NULL,
    source_coordinates_jsonb jsonb NOT NULL,
    recognition_snapshot_jsonb jsonb NOT NULL,
    content_hash character(64) NOT NULL,
    created_by uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT experiment_source_content_hash_check CHECK ((content_hash ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT experiment_source_coordinates_object CHECK ((jsonb_typeof(source_coordinates_jsonb) = 'object'::text)),
    CONSTRAINT experiment_source_file_hash_check CHECK ((source_file_sha256 ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT experiment_source_groups_array CHECK (((jsonb_typeof(source_group_keys_jsonb) = 'array'::text) AND (jsonb_array_length(source_group_keys_jsonb) > 0))),
    CONSTRAINT experiment_source_job_owner_check CHECK (((((recognition_job_id IS NOT NULL))::integer + ((experiment_import_job_id IS NOT NULL))::integer) = 1)),
    CONSTRAINT experiment_source_snapshot_object CHECK ((jsonb_typeof(recognition_snapshot_jsonb) = 'object'::text))
);


--
-- Name: research_test_audit; Type: TABLE; Schema: rnd; Owner: -
--

CREATE TABLE rnd.research_test_audit (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    record_id uuid NOT NULL,
    version_id uuid,
    action character varying(40) NOT NULL,
    before_jsonb jsonb,
    after_jsonb jsonb,
    operator_id uuid NOT NULL,
    operator_name character varying(120) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: research_test_record; Type: TABLE; Schema: rnd; Owner: -
--

CREATE TABLE rnd.research_test_record (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    record_type character varying(20) NOT NULL,
    business_no character varying(96) NOT NULL,
    name character varying(260) NOT NULL,
    category character varying(100),
    applicable_scope character varying(260),
    owner_id uuid,
    owner_name character varying(120),
    business_date date,
    document_format character varying(12) NOT NULL,
    source_type character varying(20) NOT NULL,
    status character varying(24) DEFAULT 'DRAFT'::character varying NOT NULL,
    visibility character varying(24) DEFAULT 'ALL'::character varying NOT NULL,
    project_id uuid,
    stage_id uuid,
    task_id uuid,
    current_version_id uuid,
    source_file_id uuid,
    lock_version bigint DEFAULT 0 NOT NULL,
    deleted boolean DEFAULT false NOT NULL,
    created_by uuid NOT NULL,
    updated_by uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT research_test_record_document_format_check CHECK (((document_format)::text = ANY (ARRAY[('WORD'::character varying)::text, ('EXCEL'::character varying)::text]))),
    CONSTRAINT research_test_record_record_type_check CHECK (((record_type)::text = ANY (ARRAY[('REPORT'::character varying)::text, ('STANDARD'::character varying)::text]))),
    CONSTRAINT research_test_record_source_type_check CHECK (((source_type)::text = ANY (ARRAY[('BLANK'::character varying)::text, ('TEMPLATE'::character varying)::text, ('UPLOAD'::character varying)::text, ('GENERATED'::character varying)::text, ('IMPORT'::character varying)::text]))),
    CONSTRAINT research_test_record_status_check CHECK (((status)::text = ANY (ARRAY[('DRAFT'::character varying)::text, ('PENDING_REVIEW'::character varying)::text, ('RETURNED'::character varying)::text, ('PUBLISHED'::character varying)::text, ('ARCHIVED'::character varying)::text]))),
    CONSTRAINT research_test_record_visibility_check CHECK (((visibility)::text = ANY (ARRAY[('ALL'::character varying)::text, ('RND'::character varying)::text, ('PROJECT'::character varying)::text])))
);


--
-- Name: research_test_review; Type: TABLE; Schema: rnd; Owner: -
--

CREATE TABLE rnd.research_test_review (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    record_id uuid NOT NULL,
    version_id uuid NOT NULL,
    action character varying(24) NOT NULL,
    comment character varying(1000),
    operator_id uuid NOT NULL,
    operator_name character varying(120) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: research_test_upload; Type: TABLE; Schema: rnd; Owner: -
--

CREATE TABLE rnd.research_test_upload (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    record_id uuid NOT NULL,
    file_id uuid NOT NULL,
    original_name character varying(500) NOT NULL,
    content_type character varying(160) NOT NULL,
    file_size bigint NOT NULL,
    sha256 character varying(64),
    status character varying(24) DEFAULT 'DRAFT_CREATED'::character varying NOT NULL,
    created_by uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    deleted boolean DEFAULT false NOT NULL
);


--
-- Name: research_test_version; Type: TABLE; Schema: rnd; Owner: -
--

CREATE TABLE rnd.research_test_version (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    record_id uuid NOT NULL,
    version_no integer NOT NULL,
    status character varying(24) NOT NULL,
    template_version_id uuid,
    template_snapshot_hash character varying(64),
    template_snapshot_jsonb jsonb,
    edit_model_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    member_snapshot_jsonb jsonb DEFAULT '[]'::jsonb NOT NULL,
    effective_from date,
    effective_to date,
    change_summary character varying(1000),
    submitted_at timestamp with time zone,
    published_at timestamp with time zone,
    created_by uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: analysis_event; Type: TABLE; Schema: spc; Owner: -
--

CREATE TABLE spc.analysis_event (
    id bigint NOT NULL,
    organization_id uuid NOT NULL,
    analysis_run_id uuid NOT NULL,
    event_type character varying(40) NOT NULL,
    payload_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: analysis_event_id_seq; Type: SEQUENCE; Schema: spc; Owner: -
--

CREATE SEQUENCE spc.analysis_event_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: analysis_event_id_seq; Type: SEQUENCE OWNED BY; Schema: spc; Owner: -
--

ALTER SEQUENCE spc.analysis_event_id_seq OWNED BY spc.analysis_event.id;


--
-- Name: analysis_run; Type: TABLE; Schema: spc; Owner: -
--

CREATE TABLE spc.analysis_run (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    session_id uuid NOT NULL,
    user_message_id uuid,
    mode character varying(40) DEFAULT 'AI_CHAT'::character varying NOT NULL,
    question text NOT NULL,
    chart_ids_jsonb jsonb DEFAULT '[]'::jsonb NOT NULL,
    page_selections_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    selected_categories_jsonb jsonb DEFAULT '[]'::jsonb NOT NULL,
    scenario_template character varying(80),
    status character varying(20) DEFAULT 'QUEUED'::character varying NOT NULL,
    progress smallint DEFAULT 0 NOT NULL,
    current_stage character varying(120),
    prompt_version character varying(80),
    model character varying(160),
    result_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    raw_response_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    warning_jsonb jsonb DEFAULT '[]'::jsonb NOT NULL,
    error_message text,
    created_by uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    started_at timestamp with time zone,
    completed_at timestamp with time zone,
    CONSTRAINT analysis_run_mode_check CHECK (((mode)::text = ANY (ARRAY[('AI_CHAT'::character varying)::text, ('CATEGORY_ASSISTED'::character varying)::text, ('OPTIONAL_SCENARIO'::character varying)::text]))),
    CONSTRAINT analysis_run_progress_check CHECK (((progress >= 0) AND (progress <= 100))),
    CONSTRAINT analysis_run_status_check CHECK (((status)::text = ANY (ARRAY[('QUEUED'::character varying)::text, ('RUNNING'::character varying)::text, ('SUCCEEDED'::character varying)::text, ('PARTIAL'::character varying)::text, ('FAILED'::character varying)::text, ('CANCELLED'::character varying)::text])))
);


--
-- Name: chart_asset; Type: TABLE; Schema: spc; Owner: -
--

CREATE TABLE spc.chart_asset (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    category_id uuid NOT NULL,
    file_object_id uuid NOT NULL,
    title character varying(260) NOT NULL,
    original_name character varying(260) NOT NULL,
    content_type character varying(160) NOT NULL,
    size_bytes bigint NOT NULL,
    sha256 character(64) NOT NULL,
    sample_name character varying(260),
    batch_no character varying(160),
    test_conditions text,
    metadata_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    page_count integer DEFAULT 1 NOT NULL,
    status character varying(20) DEFAULT 'READY'::character varying NOT NULL,
    created_by uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    request_id uuid,
    CONSTRAINT chart_asset_page_count_check CHECK ((page_count > 0)),
    CONSTRAINT chart_asset_size_bytes_check CHECK ((size_bytes >= 0)),
    CONSTRAINT chart_asset_status_check CHECK (((status)::text = ANY (ARRAY[('READY'::character varying)::text, ('DELETED'::character varying)::text])))
);


--
-- Name: chart_category; Type: TABLE; Schema: spc; Owner: -
--

CREATE TABLE spc.chart_category (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    code character varying(64) NOT NULL,
    name character varying(120) NOT NULL,
    description character varying(500),
    analysis_hint text,
    fields_jsonb jsonb DEFAULT '[]'::jsonb NOT NULL,
    sort_order integer DEFAULT 100 NOT NULL,
    system_category boolean DEFAULT false NOT NULL,
    created_by uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: chart_upload_attempt; Type: TABLE; Schema: spc; Owner: -
--

CREATE TABLE spc.chart_upload_attempt (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    request_id uuid NOT NULL,
    category_id uuid,
    chart_id uuid,
    file_object_id uuid,
    original_name character varying(500) NOT NULL,
    size_bytes bigint DEFAULT 0 NOT NULL,
    status character varying(24) NOT NULL,
    error_code character varying(80),
    error_message character varying(2000),
    created_by uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    upload_context_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    parent_request_id uuid,
    hidden_at timestamp with time zone,
    CONSTRAINT chart_upload_attempt_size_bytes_check CHECK ((size_bytes >= 0)),
    CONSTRAINT chart_upload_attempt_status_check CHECK (((status)::text = ANY ((ARRAY['SUCCEEDED'::character varying, 'FAILED'::character varying, 'DUPLICATE'::character varying, 'CANCELLED'::character varying])::text[])))
);


--
-- Name: chat_message; Type: TABLE; Schema: spc; Owner: -
--

CREATE TABLE spc.chat_message (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    session_id uuid NOT NULL,
    analysis_run_id uuid,
    role character varying(20) NOT NULL,
    content text NOT NULL,
    citations_jsonb jsonb DEFAULT '[]'::jsonb NOT NULL,
    result_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    warning_jsonb jsonb DEFAULT '[]'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chat_message_role_check CHECK (((role)::text = ANY (ARRAY[('USER'::character varying)::text, ('ASSISTANT'::character varying)::text])))
);


--
-- Name: chat_session; Type: TABLE; Schema: spc; Owner: -
--

CREATE TABLE spc.chat_session (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    title character varying(260) DEFAULT '新的图谱分析对话'::character varying NOT NULL,
    created_by uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    title_source character varying(24) DEFAULT 'FIRST_QUESTION'::character varying NOT NULL,
    chart_ids_jsonb jsonb DEFAULT '[]'::jsonb NOT NULL,
    page_selections_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    CONSTRAINT chk_spc_chat_session_title_source CHECK (((title_source)::text = ANY (ARRAY[('FIRST_QUESTION'::character varying)::text, ('MODEL'::character varying)::text, ('USER'::character varying)::text])))
);


--
-- Name: recognition_call; Type: TABLE; Schema: tpl; Owner: -
--

CREATE TABLE tpl.recognition_call (
    id uuid NOT NULL,
    recognition_run_id uuid NOT NULL,
    region_id character varying(256) NOT NULL,
    attempt integer DEFAULT 1 NOT NULL,
    provider character varying(100) NOT NULL,
    model character varying(100) NOT NULL,
    prompt_version character varying(80) NOT NULL,
    status character varying(24) NOT NULL,
    http_status integer,
    started_at timestamp with time zone NOT NULL,
    finished_at timestamp with time zone NOT NULL,
    duration_ms bigint NOT NULL,
    prompt_tokens integer DEFAULT 0 NOT NULL,
    completion_tokens integer DEFAULT 0 NOT NULL,
    total_tokens integer DEFAULT 0 NOT NULL,
    request_payload_gzip bytea,
    response_payload_gzip bytea,
    request_hash character(64) NOT NULL,
    response_hash character(64),
    error_type character varying(160),
    error_message text,
    finish_reason character varying(40),
    outcome_code character varying(80),
    response_truncated boolean DEFAULT false NOT NULL,
    payload_expires_at timestamp with time zone DEFAULT (now() + '90 days'::interval) NOT NULL,
    payload_purged_at timestamp with time zone,
    phase character varying(40) DEFAULT 'REGION_INFERENCE'::character varying NOT NULL,
    parent_call_id uuid,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT recognition_call_duration_ms_check CHECK ((duration_ms >= 0)),
    CONSTRAINT recognition_call_status_check CHECK (((status)::text = ANY (ARRAY[('SUCCEEDED'::character varying)::text, ('FAILED'::character varying)::text])))
);


--
-- Name: recognition_run; Type: TABLE; Schema: tpl; Owner: -
--

CREATE TABLE tpl.recognition_run (
    id uuid NOT NULL,
    import_job_id uuid NOT NULL,
    scope character varying(24) DEFAULT 'WORKBOOK'::character varying NOT NULL,
    status character varying(24) NOT NULL,
    structure_version integer NOT NULL,
    snapshot_format_version integer NOT NULL,
    region_count integer DEFAULT 0 NOT NULL,
    call_count integer DEFAULT 0 NOT NULL,
    succeeded_call_count integer DEFAULT 0 NOT NULL,
    failed_call_count integer DEFAULT 0 NOT NULL,
    prompt_tokens bigint DEFAULT 0 NOT NULL,
    completion_tokens bigint DEFAULT 0 NOT NULL,
    total_tokens bigint DEFAULT 0 NOT NULL,
    started_at timestamp with time zone DEFAULT now() NOT NULL,
    finished_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    recognition_protocol_version integer DEFAULT 1 NOT NULL,
    source_snapshot_hash character(64),
    parent_run_id uuid,
    run_reason character varying(48) DEFAULT 'INITIAL_RECOGNITION'::character varying NOT NULL,
    CONSTRAINT recognition_run_status_check CHECK (((status)::text = ANY (ARRAY[('RUNNING'::character varying)::text, ('COMPLETED'::character varying)::text, ('PARTIAL'::character varying)::text, ('FAILED'::character varying)::text])))
);


--
-- Name: recognition_suggestion; Type: TABLE; Schema: tpl; Owner: -
--

CREATE TABLE tpl.recognition_suggestion (
    id uuid NOT NULL,
    import_job_id uuid NOT NULL,
    source character varying(20) NOT NULL,
    suggestion_type character varying(60) NOT NULL,
    payload_jsonb jsonb NOT NULL,
    confidence numeric(5,4),
    evidence_jsonb jsonb DEFAULT '[]'::jsonb NOT NULL,
    decision character varying(24) DEFAULT 'PENDING'::character varying NOT NULL,
    provider character varying(100),
    model character varying(100),
    prompt_version character varying(60),
    request_hash character(64),
    response_hash character(64),
    decided_by uuid,
    decided_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    recognition_run_id uuid,
    recognition_call_id uuid,
    region_id character varying(256),
    relation_id character varying(256),
    block_id character varying(256),
    parent_suggestion_id uuid,
    field_id uuid,
    semantic_fingerprint character(64),
    suggestion_level character varying(16) DEFAULT 'ROOT'::character varying NOT NULL,
    filter_stage character varying(48),
    filter_reason_code character varying(80),
    filter_detail text,
    CONSTRAINT ck_recognition_suggestion_decision CHECK (((decision)::text = ANY (ARRAY[('PENDING'::character varying)::text, ('ACCEPTED'::character varying)::text, ('REJECTED'::character varying)::text]))),
    CONSTRAINT recognition_suggestion_source_check CHECK (((source)::text = ANY (ARRAY[('RULE'::character varying)::text, ('MODEL'::character varying)::text, ('PHYSICAL'::character varying)::text, ('HUMAN'::character varying)::text]))),
    CONSTRAINT recognition_suggestion_suggestion_level_check CHECK (((suggestion_level)::text = ANY (ARRAY[('ROOT'::character varying)::text, ('CHILD'::character varying)::text, ('SCALAR'::character varying)::text])))
);


--
-- Name: recognition_trace; Type: TABLE; Schema: tpl; Owner: -
--

CREATE TABLE tpl.recognition_trace (
    id uuid NOT NULL,
    recognition_run_id uuid NOT NULL,
    recognition_suggestion_id uuid,
    stage character varying(48) NOT NULL,
    reason_code character varying(80) NOT NULL,
    message text NOT NULL,
    detail_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: record_attachment; Type: TABLE; Schema: tpl; Owner: -
--

CREATE TABLE tpl.record_attachment (
    id uuid NOT NULL,
    production_order_id uuid NOT NULL,
    revision_id uuid,
    file_object_id uuid NOT NULL,
    attachment_type character varying(40) NOT NULL,
    created_by uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: record_collection_item; Type: TABLE; Schema: tpl; Owner: -
--

CREATE TABLE tpl.record_collection_item (
    id uuid NOT NULL,
    revision_id uuid NOT NULL,
    production_order_id uuid NOT NULL,
    record_kind character varying(16) NOT NULL,
    parent_field_code character varying(160) NOT NULL,
    parent_data_path character varying(400) NOT NULL,
    record_key character varying(240) NOT NULL,
    record_index integer NOT NULL,
    member_key character varying(240),
    data_jsonb jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT record_collection_item_record_index_check CHECK ((record_index >= 0)),
    CONSTRAINT record_collection_item_record_kind_check CHECK (((record_kind)::text = 'DETAIL'::text))
);


--
-- Name: record_revision; Type: TABLE; Schema: tpl; Owner: -
--

CREATE TABLE tpl.record_revision (
    id uuid NOT NULL,
    production_order_id uuid NOT NULL,
    revision_no integer NOT NULL,
    status character varying(24) NOT NULL,
    core_snapshot_jsonb jsonb NOT NULL,
    schema_snapshot_jsonb jsonb NOT NULL,
    mapping_snapshot_jsonb jsonb NOT NULL,
    data_jsonb jsonb NOT NULL,
    editor_snapshot_file_id uuid,
    editor_snapshot_hash character(64),
    export_office_file_id uuid,
    export_office_hash character(64),
    schema_hash character(64) NOT NULL,
    mapping_hash character(64) NOT NULL,
    data_hash character(64) NOT NULL,
    workspace_hash character(64) NOT NULL,
    created_by uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT record_revision_revision_no_check CHECK ((revision_no > 0)),
    CONSTRAINT record_revision_status_check CHECK (((status)::text = ANY (ARRAY[('SUBMITTED'::character varying)::text, ('SUPERSEDED'::character varying)::text])))
);


--
-- Name: record_value_index; Type: TABLE; Schema: tpl; Owner: -
--

CREATE TABLE tpl.record_value_index (
    id uuid NOT NULL,
    revision_id uuid NOT NULL,
    production_order_id uuid NOT NULL,
    field_code character varying(160) NOT NULL,
    data_path character varying(400) NOT NULL,
    value_type character varying(20) NOT NULL,
    text_value text,
    numeric_value numeric,
    boolean_value boolean,
    date_value date,
    reference_value uuid,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    collection_item_id uuid
);


--
-- Name: standard_field_alias; Type: TABLE; Schema: tpl; Owner: -
--

CREATE TABLE tpl.standard_field_alias (
    id uuid NOT NULL,
    dictionary_id uuid NOT NULL,
    alias character varying(160) NOT NULL,
    normalized_alias character varying(160) NOT NULL
);


--
-- Name: standard_field_catalog_import; Type: TABLE; Schema: tpl; Owner: -
--

CREATE TABLE tpl.standard_field_catalog_import (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    source_file_id uuid NOT NULL,
    source_sha256 character(64) NOT NULL,
    source_name character varying(260) NOT NULL,
    catalog_version integer NOT NULL,
    parser_version character varying(80) NOT NULL,
    status character varying(24) DEFAULT 'PARSED'::character varying NOT NULL,
    source_scope character varying(24) DEFAULT 'GLOBAL'::character varying NOT NULL,
    source_row_count integer DEFAULT 0 NOT NULL,
    proposal_count integer DEFAULT 0 NOT NULL,
    summary_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_by uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT catalog_import_hash_check CHECK ((source_sha256 ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT catalog_import_summary_object CHECK ((jsonb_typeof(summary_jsonb) = 'object'::text)),
    CONSTRAINT standard_field_catalog_import_catalog_version_check CHECK ((catalog_version > 0)),
    CONSTRAINT standard_field_catalog_import_proposal_count_check CHECK ((proposal_count >= 0)),
    CONSTRAINT standard_field_catalog_import_source_row_count_check CHECK ((source_row_count >= 0)),
    CONSTRAINT standard_field_catalog_import_source_scope_check CHECK (((source_scope)::text = 'GLOBAL'::text)),
    CONSTRAINT standard_field_catalog_import_status_check CHECK (((status)::text = ANY (ARRAY[('PARSED'::character varying)::text, ('REVIEWED'::character varying)::text, ('SUPERSEDED'::character varying)::text])))
);


--
-- Name: TABLE standard_field_catalog_import; Type: COMMENT; Schema: tpl; Owner: -
--

COMMENT ON TABLE tpl.standard_field_catalog_import IS 'Shared customer test-method reference import; never a training fact source.';


--
-- Name: standard_field_catalog_proposal; Type: TABLE; Schema: tpl; Owner: -
--

CREATE TABLE tpl.standard_field_catalog_proposal (
    id uuid NOT NULL,
    import_id uuid NOT NULL,
    source_row integer NOT NULL,
    atomic_ordinal integer NOT NULL,
    semantic_key character varying(320) NOT NULL,
    raw_item_name character varying(500) NOT NULL,
    raw_test_method character varying(500),
    raw_example_result character varying(1000),
    raw_influence_factors character varying(1000),
    atomic_name character varying(240) NOT NULL,
    suggested_code character varying(180),
    suggested_value_type character varying(24) NOT NULL,
    suggested_unit character varying(80),
    standard_field_id uuid,
    standard_field_code character varying(100),
    qualifier_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    aliases_jsonb jsonb DEFAULT '[]'::jsonb NOT NULL,
    factor_suggestions_jsonb jsonb DEFAULT '[]'::jsonb NOT NULL,
    evidence_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    confidence integer DEFAULT 0 NOT NULL,
    change_status character varying(16) DEFAULT 'NEW'::character varying NOT NULL,
    previous_proposal_id uuid,
    diff_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    review_status character varying(24) DEFAULT 'NEEDS_REVIEW'::character varying NOT NULL,
    application_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
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


--
-- Name: TABLE standard_field_catalog_proposal; Type: COMMENT; Schema: tpl; Owner: -
--

COMMENT ON TABLE tpl.standard_field_catalog_proposal IS 'Atomic semantic proposals derived from a customer catalog row; application remains explicit.';


--
-- Name: standard_field_dictionary; Type: TABLE; Schema: tpl; Owner: -
--

CREATE TABLE tpl.standard_field_dictionary (
    id uuid NOT NULL,
    dictionary_code character varying(100) NOT NULL,
    version_no integer NOT NULL,
    display_name character varying(160) NOT NULL,
    data_type character varying(32) NOT NULL,
    default_unit character varying(40),
    description text,
    repeatable boolean DEFAULT false NOT NULL,
    status character varying(24) DEFAULT 'ACTIVE'::character varying NOT NULL,
    group_code character varying(100),
    ui_type character varying(24) DEFAULT 'TEXT'::character varying NOT NULL,
    supersedes_id uuid,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT standard_field_dictionary_status_check CHECK (((status)::text = ANY (ARRAY[('DRAFT'::character varying)::text, ('ACTIVE'::character varying)::text, ('RETIRED'::character varying)::text]))),
    CONSTRAINT standard_field_dictionary_ui_type_check CHECK (((ui_type)::text = ANY (ARRAY[('TEXT'::character varying)::text, ('SIGNATURE'::character varying)::text]))),
    CONSTRAINT standard_field_dictionary_version_no_check CHECK ((version_no > 0))
);


--
-- Name: standard_field_request; Type: TABLE; Schema: tpl; Owner: -
--

CREATE TABLE tpl.standard_field_request (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    template_version_id uuid,
    field_id character varying(180),
    display_name character varying(160) NOT NULL,
    data_type character varying(32) NOT NULL,
    ui_type character varying(24) DEFAULT 'TEXT'::character varying NOT NULL,
    group_code character varying(100),
    description text,
    status character varying(24) DEFAULT 'PENDING'::character varying NOT NULL,
    proposed_field_code character varying(160),
    approved_dictionary_id uuid,
    review_comment text,
    created_by uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    reviewed_by uuid,
    reviewed_at timestamp with time zone,
    catalog_proposal_id uuid,
    CONSTRAINT standard_field_request_status_check CHECK (((status)::text = ANY (ARRAY[('PENDING'::character varying)::text, ('APPROVED'::character varying)::text, ('REJECTED'::character varying)::text]))),
    CONSTRAINT standard_field_request_ui_type_check CHECK (((ui_type)::text = ANY (ARRAY[('TEXT'::character varying)::text, ('SIGNATURE'::character varying)::text])))
);


--
-- Name: template; Type: TABLE; Schema: tpl; Owner: -
--

CREATE TABLE tpl.template (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    template_code character varying(80) NOT NULL,
    name character varying(200) NOT NULL,
    category character varying(120),
    format character varying(16) NOT NULL,
    current_published_version_id uuid,
    created_by uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    category_id uuid,
    purpose character varying(160),
    CONSTRAINT template_format_check CHECK (((format)::text = ANY (ARRAY[('XLSX'::character varying)::text, ('DOCX'::character varying)::text])))
);


--
-- Name: template_category; Type: TABLE; Schema: tpl; Owner: -
--

CREATE TABLE tpl.template_category (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    name character varying(120) NOT NULL,
    sort_order integer DEFAULT 0 NOT NULL,
    created_by uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    description character varying(240)
);


--
-- Name: template_import_contract; Type: TABLE; Schema: tpl; Owner: -
--

CREATE TABLE tpl.template_import_contract (
    template_version_id uuid NOT NULL,
    import_contract_version integer NOT NULL,
    layout_structure_version integer NOT NULL,
    contract_hash character(64) NOT NULL,
    contract_jsonb jsonb NOT NULL,
    created_by uuid,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT template_import_contract_import_contract_version_check CHECK ((import_contract_version > 0)),
    CONSTRAINT template_import_contract_layout_structure_version_check CHECK ((layout_structure_version > 0))
);


--
-- Name: TABLE template_import_contract; Type: COMMENT; Schema: tpl; Owner: -
--

COMMENT ON TABLE tpl.template_import_contract IS '已发布模板的不可变导入契约；业务数据类型属于数据中心任务，不属于模板契约';


--
-- Name: template_import_issue; Type: TABLE; Schema: tpl; Owner: -
--

CREATE TABLE tpl.template_import_issue (
    id uuid NOT NULL,
    import_job_id uuid NOT NULL,
    severity character varying(16) NOT NULL,
    issue_code character varying(100) NOT NULL,
    location_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    message text NOT NULL,
    resolution character varying(40) DEFAULT 'OPEN'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT template_import_issue_severity_check CHECK (((severity)::text = ANY (ARRAY[('INFO'::character varying)::text, ('WARNING'::character varying)::text, ('BLOCKER'::character varying)::text])))
);


--
-- Name: template_import_job; Type: TABLE; Schema: tpl; Owner: -
--

CREATE TABLE tpl.template_import_job (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    source_file_id uuid NOT NULL,
    format character varying(16) NOT NULL,
    status character varying(24) NOT NULL,
    parser_version character varying(80),
    progress smallint DEFAULT 0 NOT NULL,
    structure_summary_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    generated_template_version_id uuid,
    async_job_id uuid,
    created_by uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    category_id uuid,
    source_sha256 character(64),
    duplicate_override boolean DEFAULT false NOT NULL,
    duplicate_source_job_id uuid,
    operation_source character varying(40) DEFAULT 'UPLOAD'::character varying NOT NULL,
    original_source_file_id uuid,
    normalized_source_file_id uuid,
    original_format character varying(20),
    normalized_format character varying(20),
    normalization_status character varying(30),
    normalization_message text,
    hidden_at timestamp with time zone,
    CONSTRAINT template_import_job_format_check CHECK (((format)::text = ANY (ARRAY[('XLSX'::character varying)::text, ('DOCX'::character varying)::text]))),
    CONSTRAINT template_import_job_progress_check CHECK (((progress >= 0) AND (progress <= 100)))
);


--
-- Name: template_mapping; Type: TABLE; Schema: tpl; Owner: -
--

CREATE TABLE tpl.template_mapping (
    id uuid NOT NULL,
    template_version_id uuid NOT NULL,
    binding_id character varying(120) NOT NULL,
    field_id uuid,
    parent_binding_id character varying(120),
    marker_id character varying(160),
    format character varying(16) NOT NULL,
    field_code character varying(160),
    data_path character varying(400) NOT NULL,
    binding_role character varying(32) DEFAULT 'FIELD'::character varying NOT NULL,
    mapping_kind character varying(32) DEFAULT 'SCALAR'::character varying NOT NULL,
    repeat_axis character varying(12),
    record_height integer DEFAULT 1 NOT NULL,
    record_width integer DEFAULT 1 NOT NULL,
    record_stride integer DEFAULT 1 NOT NULL,
    termination_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    locator_type character varying(60) NOT NULL,
    locator_jsonb jsonb NOT NULL,
    sync_direction character varying(20) NOT NULL,
    primary_binding boolean DEFAULT true NOT NULL,
    binding_status character varying(20) DEFAULT 'VALID'::character varying NOT NULL,
    diagnostic_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    last_validated_at timestamp with time zone,
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


--
-- Name: template_quality_issue; Type: TABLE; Schema: tpl; Owner: -
--

CREATE TABLE tpl.template_quality_issue (
    id uuid NOT NULL,
    import_job_id uuid NOT NULL,
    recognition_run_id uuid NOT NULL,
    recognition_call_id uuid,
    region_id character varying(256),
    issue_type character varying(80) NOT NULL,
    severity character varying(16) NOT NULL,
    confidence numeric(5,4) NOT NULL,
    sheet_id character varying(160),
    sheet_name character varying(200),
    address character varying(80) NOT NULL,
    title character varying(240) NOT NULL,
    description text NOT NULL,
    business_impact text NOT NULL,
    evidence_jsonb jsonb DEFAULT '[]'::jsonb NOT NULL,
    suggested_patch_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    inverse_patch_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    auto_fixable boolean DEFAULT false NOT NULL,
    status character varying(24) NOT NULL,
    before_snapshot_hash character(64),
    after_snapshot_hash character(64),
    root_block_id character varying(256),
    customer_issue_category character varying(80),
    decided_by uuid,
    decided_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT template_quality_issue_severity_check CHECK (((severity)::text = ANY (ARRAY[('INFO'::character varying)::text, ('WARNING'::character varying)::text, ('BLOCKER'::character varying)::text]))),
    CONSTRAINT template_quality_issue_status_check CHECK (((status)::text = ANY (ARRAY[('DETECTED'::character varying)::text, ('AUTO_APPLIED'::character varying)::text, ('CONFIRMED'::character varying)::text, ('IGNORED'::character varying)::text, ('ROLLED_BACK'::character varying)::text, ('FAILED'::character varying)::text])))
);


--
-- Name: template_static_region; Type: TABLE; Schema: tpl; Owner: -
--

CREATE TABLE tpl.template_static_region (
    id uuid NOT NULL,
    template_version_id uuid NOT NULL,
    sheet_id character varying(160) NOT NULL,
    sheet_name character varying(200),
    address character varying(120) NOT NULL,
    region_type character varying(32) NOT NULL,
    display_name character varying(160) NOT NULL,
    source character varying(24) DEFAULT 'TEMPLATE_BASELINE'::character varying NOT NULL,
    content_hash character(64),
    locked boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT template_static_region_region_type_check CHECK (((region_type)::text = ANY (ARRAY[('STATIC_REFERENCE'::character varying)::text, ('INSTRUCTION'::character varying)::text, ('NOTE'::character varying)::text]))),
    CONSTRAINT template_static_region_source_check CHECK (((source)::text = ANY (ARRAY[('TEMPLATE_BASELINE'::character varying)::text, ('MODEL'::character varying)::text, ('HUMAN'::character varying)::text])))
);


--
-- Name: template_structure_change; Type: TABLE; Schema: tpl; Owner: -
--

CREATE TABLE tpl.template_structure_change (
    id uuid NOT NULL,
    template_version_id uuid NOT NULL,
    operation_order integer NOT NULL,
    operation_type character varying(32) NOT NULL,
    sheet_id character varying(160) NOT NULL,
    operation_jsonb jsonb NOT NULL,
    source character varying(16) NOT NULL,
    before_mapping_hash character(64) NOT NULL,
    after_mapping_hash character(64) NOT NULL,
    created_by uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT template_structure_change_operation_order_check CHECK ((operation_order >= 0)),
    CONSTRAINT template_structure_change_operation_type_check CHECK (((operation_type)::text = ANY (ARRAY[('INSERT_ROWS'::character varying)::text, ('DELETE_ROWS'::character varying)::text, ('INSERT_COLUMNS'::character varying)::text, ('DELETE_COLUMNS'::character varying)::text, ('RENAME_SHEET'::character varying)::text]))),
    CONSTRAINT template_structure_change_source_check CHECK (((source)::text = ANY (ARRAY[('CUSTOMER'::character varying)::text, ('AI'::character varying)::text])))
);


--
-- Name: template_version; Type: TABLE; Schema: tpl; Owner: -
--

CREATE TABLE tpl.template_version (
    id uuid NOT NULL,
    template_id uuid NOT NULL,
    version_no integer NOT NULL,
    status character varying(20) NOT NULL,
    schema_jsonb jsonb NOT NULL,
    layout_summary_jsonb jsonb DEFAULT '{}'::jsonb NOT NULL,
    source_file_id uuid,
    editor_snapshot_file_id uuid,
    editor_snapshot_hash character(64),
    snapshot_kind character varying(32) NOT NULL,
    editor_app_version character varying(80) NOT NULL,
    plugin_manifest_hash character(64) NOT NULL,
    snapshot_format_version integer DEFAULT 1 NOT NULL,
    export_office_file_id uuid,
    export_office_hash character(64),
    schema_hash character(64) NOT NULL,
    mapping_hash character(64) NOT NULL,
    data_hash character(64) NOT NULL,
    workspace_hash character(64) NOT NULL,
    lock_version bigint DEFAULT 0 NOT NULL,
    derived_from_version_id uuid,
    created_by uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    published_at timestamp with time zone,
    CONSTRAINT template_version_snapshot_kind_check CHECK (((snapshot_kind)::text = ANY (ARRAY[('UNIVER_WORKBOOK'::character varying)::text, ('UNIVER_DOCUMENT'::character varying)::text]))),
    CONSTRAINT template_version_status_check CHECK (((status)::text = ANY (ARRAY[('DRAFT'::character varying)::text, ('PUBLISHED'::character varying)::text, ('RETIRED'::character varying)::text]))),
    CONSTRAINT template_version_version_no_check CHECK ((version_no > 0))
);


--
-- Name: template_version_review; Type: TABLE; Schema: tpl; Owner: -
--

CREATE TABLE tpl.template_version_review (
    version_id uuid NOT NULL,
    organization_id uuid NOT NULL,
    review_status character varying(20) DEFAULT 'NOT_SUBMITTED'::character varying NOT NULL,
    submitted_by uuid,
    submitted_at timestamp with time zone,
    reviewed_by uuid,
    reviewed_at timestamp with time zone,
    review_comment text,
    lock_version bigint DEFAULT 0 NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT template_version_review_status_check CHECK (((review_status)::text = ANY (ARRAY[('NOT_SUBMITTED'::character varying)::text, ('SUBMITTED'::character varying)::text, ('APPROVED'::character varying)::text, ('REJECTED'::character varying)::text])))
);


--
-- Name: template_version_review_event; Type: TABLE; Schema: tpl; Owner: -
--

CREATE TABLE tpl.template_version_review_event (
    id uuid NOT NULL,
    version_id uuid NOT NULL,
    organization_id uuid NOT NULL,
    from_status character varying(20),
    to_status character varying(20) NOT NULL,
    actor_id uuid,
    comment text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT template_version_review_event_status_check CHECK (((to_status)::text = ANY (ARRAY[('NOT_SUBMITTED'::character varying)::text, ('SUBMITTED'::character varying)::text, ('APPROVED'::character varying)::text, ('REJECTED'::character varying)::text])))
);


--
-- Name: unified_lab_template_package; Type: TABLE; Schema: tpl; Owner: -
--

CREATE TABLE tpl.unified_lab_template_package (
    template_code character varying(80) NOT NULL,
    package_hash character(64) NOT NULL,
    payload_jsonb jsonb NOT NULL,
    installed_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: analysis_event id; Type: DEFAULT; Schema: spc; Owner: -
--

ALTER TABLE ONLY spc.analysis_event ALTER COLUMN id SET DEFAULT nextval('spc.analysis_event_id_seq'::regclass);


--
-- Name: ai_call_audit ai_call_audit_pkey; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.ai_call_audit
    ADD CONSTRAINT ai_call_audit_pkey PRIMARY KEY (id);


--
-- Name: artifact artifact_organization_id_id_key; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.artifact
    ADD CONSTRAINT artifact_organization_id_id_key UNIQUE (organization_id, id);


--
-- Name: artifact artifact_organization_id_object_key_key; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.artifact
    ADD CONSTRAINT artifact_organization_id_object_key_key UNIQUE (organization_id, object_key);


--
-- Name: artifact artifact_pkey; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.artifact
    ADD CONSTRAINT artifact_pkey PRIMARY KEY (id);


--
-- Name: assistant_conversation assistant_conversation_pkey; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.assistant_conversation
    ADD CONSTRAINT assistant_conversation_pkey PRIMARY KEY (id);


--
-- Name: assistant_message assistant_message_pkey; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.assistant_message
    ADD CONSTRAINT assistant_message_pkey PRIMARY KEY (id);


--
-- Name: configuration_command_receipt configuration_command_receipt_organization_id_operation_ide_key; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.configuration_command_receipt
    ADD CONSTRAINT configuration_command_receipt_organization_id_operation_ide_key UNIQUE (organization_id, operation, idempotency_key);


--
-- Name: configuration_command_receipt configuration_command_receipt_pkey; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.configuration_command_receipt
    ADD CONSTRAINT configuration_command_receipt_pkey PRIMARY KEY (id);


--
-- Name: context_snapshot context_snapshot_organization_id_id_key; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.context_snapshot
    ADD CONSTRAINT context_snapshot_organization_id_id_key UNIQUE (organization_id, id);


--
-- Name: context_snapshot context_snapshot_pkey; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.context_snapshot
    ADD CONSTRAINT context_snapshot_pkey PRIMARY KEY (id);


--
-- Name: data_review_decision data_review_decision_organization_id_id_key; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.data_review_decision
    ADD CONSTRAINT data_review_decision_organization_id_id_key UNIQUE (organization_id, id);


--
-- Name: data_review_decision data_review_decision_pkey; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.data_review_decision
    ADD CONSTRAINT data_review_decision_pkey PRIMARY KEY (id);


--
-- Name: data_review data_review_organization_id_id_key; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.data_review
    ADD CONSTRAINT data_review_organization_id_id_key UNIQUE (organization_id, id);


--
-- Name: data_review data_review_pkey; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.data_review
    ADD CONSTRAINT data_review_pkey PRIMARY KEY (id);


--
-- Name: eligibility_evaluation_run eligibility_evaluation_run_organization_id_id_key; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.eligibility_evaluation_run
    ADD CONSTRAINT eligibility_evaluation_run_organization_id_id_key UNIQUE (organization_id, id);


--
-- Name: eligibility_evaluation_run eligibility_evaluation_run_pkey; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.eligibility_evaluation_run
    ADD CONSTRAINT eligibility_evaluation_run_pkey PRIMARY KEY (id);


--
-- Name: employee_scheduled_task employee_scheduled_task_organization_id_task_code_key; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.employee_scheduled_task
    ADD CONSTRAINT employee_scheduled_task_organization_id_task_code_key UNIQUE (organization_id, task_code);


--
-- Name: employee_scheduled_task employee_scheduled_task_pkey; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.employee_scheduled_task
    ADD CONSTRAINT employee_scheduled_task_pkey PRIMARY KEY (id);


--
-- Name: experiment_analysis experiment_analysis_organization_id_idempotency_key_key; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.experiment_analysis
    ADD CONSTRAINT experiment_analysis_organization_id_idempotency_key_key UNIQUE (organization_id, idempotency_key);


--
-- Name: experiment_analysis experiment_analysis_pkey; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.experiment_analysis
    ADD CONSTRAINT experiment_analysis_pkey PRIMARY KEY (id);


--
-- Name: fact_projection_receipt fact_projection_receipt_organization_id_source_type_source__key; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.fact_projection_receipt
    ADD CONSTRAINT fact_projection_receipt_organization_id_source_type_source__key UNIQUE (organization_id, source_type, source_event_id);


--
-- Name: fact_projection_receipt fact_projection_receipt_pkey; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.fact_projection_receipt
    ADD CONSTRAINT fact_projection_receipt_pkey PRIMARY KEY (id);


--
-- Name: input_field input_field_organization_id_field_code_key; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.input_field
    ADD CONSTRAINT input_field_organization_id_field_code_key UNIQUE (organization_id, field_code);


--
-- Name: input_field input_field_organization_id_id_key; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.input_field
    ADD CONSTRAINT input_field_organization_id_id_key UNIQUE (organization_id, id);


--
-- Name: input_field input_field_pkey; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.input_field
    ADD CONSTRAINT input_field_pkey PRIMARY KEY (id);


--
-- Name: input_field_version input_field_version_organization_id_id_key; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.input_field_version
    ADD CONSTRAINT input_field_version_organization_id_id_key UNIQUE (organization_id, id);


--
-- Name: input_field_version input_field_version_organization_id_input_field_id_id_key; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.input_field_version
    ADD CONSTRAINT input_field_version_organization_id_input_field_id_id_key UNIQUE (organization_id, input_field_id, id);


--
-- Name: input_field_version input_field_version_organization_id_input_field_id_version__key; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.input_field_version
    ADD CONSTRAINT input_field_version_organization_id_input_field_id_version__key UNIQUE (organization_id, input_field_id, version_no);


--
-- Name: input_field_version input_field_version_pkey; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.input_field_version
    ADD CONSTRAINT input_field_version_pkey PRIMARY KEY (id);


--
-- Name: input_scheme_field input_scheme_field_pkey; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.input_scheme_field
    ADD CONSTRAINT input_scheme_field_pkey PRIMARY KEY (input_scheme_id, input_field_version_id);


--
-- Name: input_scheme input_scheme_organization_id_id_key; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.input_scheme
    ADD CONSTRAINT input_scheme_organization_id_id_key UNIQUE (organization_id, id);


--
-- Name: input_scheme input_scheme_organization_id_target_id_id_key; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.input_scheme
    ADD CONSTRAINT input_scheme_organization_id_target_id_id_key UNIQUE (organization_id, target_id, id);


--
-- Name: input_scheme input_scheme_organization_id_target_id_scheme_code_version__key; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.input_scheme
    ADD CONSTRAINT input_scheme_organization_id_target_id_scheme_code_version__key UNIQUE (organization_id, target_id, scheme_code, version_no);


--
-- Name: input_scheme input_scheme_pkey; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.input_scheme
    ADD CONSTRAINT input_scheme_pkey PRIMARY KEY (id);


--
-- Name: material_dictionary_version material_dictionary_version_organization_id_dictionary_code_key; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.material_dictionary_version
    ADD CONSTRAINT material_dictionary_version_organization_id_dictionary_code_key UNIQUE (organization_id, dictionary_code, version_no);


--
-- Name: material_dictionary_version material_dictionary_version_organization_id_dictionary_hash_key; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.material_dictionary_version
    ADD CONSTRAINT material_dictionary_version_organization_id_dictionary_hash_key UNIQUE (organization_id, dictionary_hash);


--
-- Name: material_dictionary_version material_dictionary_version_organization_id_id_key; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.material_dictionary_version
    ADD CONSTRAINT material_dictionary_version_organization_id_id_key UNIQUE (organization_id, id);


--
-- Name: material_dictionary_version material_dictionary_version_pkey; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.material_dictionary_version
    ADD CONSTRAINT material_dictionary_version_pkey PRIMARY KEY (id);


--
-- Name: model_feedback model_feedback_organization_id_id_key; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.model_feedback
    ADD CONSTRAINT model_feedback_organization_id_id_key UNIQUE (organization_id, id);


--
-- Name: model_feedback model_feedback_organization_id_target_id_experiment_version_key; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.model_feedback
    ADD CONSTRAINT model_feedback_organization_id_target_id_experiment_version_key UNIQUE (organization_id, target_id, experiment_version_id, content_hash);


--
-- Name: model_feedback model_feedback_pkey; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.model_feedback
    ADD CONSTRAINT model_feedback_pkey PRIMARY KEY (id);


--
-- Name: model_feedback_projection_receipt model_feedback_projection_rec_organization_id_experiment_ev_key; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.model_feedback_projection_receipt
    ADD CONSTRAINT model_feedback_projection_rec_organization_id_experiment_ev_key UNIQUE (organization_id, experiment_event_id);


--
-- Name: model_feedback_projection_receipt model_feedback_projection_receipt_organization_id_id_key; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.model_feedback_projection_receipt
    ADD CONSTRAINT model_feedback_projection_receipt_organization_id_id_key UNIQUE (organization_id, id);


--
-- Name: model_feedback_projection_receipt model_feedback_projection_receipt_pkey; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.model_feedback_projection_receipt
    ADD CONSTRAINT model_feedback_projection_receipt_pkey PRIMARY KEY (id);


--
-- Name: model_release model_release_organization_id_id_key; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.model_release
    ADD CONSTRAINT model_release_organization_id_id_key UNIQUE (organization_id, id);


--
-- Name: model_release model_release_organization_id_idempotency_key_key; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.model_release
    ADD CONSTRAINT model_release_organization_id_idempotency_key_key UNIQUE (organization_id, idempotency_key);


--
-- Name: model_release model_release_pkey; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.model_release
    ADD CONSTRAINT model_release_pkey PRIMARY KEY (id);


--
-- Name: model_version model_version_organization_id_id_key; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.model_version
    ADD CONSTRAINT model_version_organization_id_id_key UNIQUE (organization_id, id);


--
-- Name: model_version model_version_organization_id_model_hash_key; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.model_version
    ADD CONSTRAINT model_version_organization_id_model_hash_key UNIQUE (organization_id, model_hash);


--
-- Name: model_version model_version_organization_id_target_id_id_key; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.model_version
    ADD CONSTRAINT model_version_organization_id_target_id_id_key UNIQUE (organization_id, target_id, id);


--
-- Name: model_version model_version_pkey; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.model_version
    ADD CONSTRAINT model_version_pkey PRIMARY KEY (id);


--
-- Name: modeling_policy_version modeling_policy_version_organization_id_id_key; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.modeling_policy_version
    ADD CONSTRAINT modeling_policy_version_organization_id_id_key UNIQUE (organization_id, id);


--
-- Name: modeling_policy_version modeling_policy_version_pkey; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.modeling_policy_version
    ADD CONSTRAINT modeling_policy_version_pkey PRIMARY KEY (id);


--
-- Name: modeling_workbench_run modeling_workbench_run_organization_id_id_key; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.modeling_workbench_run
    ADD CONSTRAINT modeling_workbench_run_organization_id_id_key UNIQUE (organization_id, id);


--
-- Name: modeling_workbench_run modeling_workbench_run_organization_id_idempotency_key_key; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.modeling_workbench_run
    ADD CONSTRAINT modeling_workbench_run_organization_id_idempotency_key_key UNIQUE (organization_id, idempotency_key);


--
-- Name: modeling_workbench_run modeling_workbench_run_pkey; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.modeling_workbench_run
    ADD CONSTRAINT modeling_workbench_run_pkey PRIMARY KEY (id);


--
-- Name: operation_manual_chunk operation_manual_chunk_pkey; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.operation_manual_chunk
    ADD CONSTRAINT operation_manual_chunk_pkey PRIMARY KEY (manual_version, embedding_model, embedding_dimension, chunk_id);


--
-- Name: operation_manual_index operation_manual_index_pkey; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.operation_manual_index
    ADD CONSTRAINT operation_manual_index_pkey PRIMARY KEY (manual_version, embedding_model, embedding_dimension);


--
-- Name: rule_definition pk_ai_rule_definition; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.rule_definition
    ADD CONSTRAINT pk_ai_rule_definition PRIMARY KEY (id);


--
-- Name: prediction_record prediction_record_organization_id_id_key; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.prediction_record
    ADD CONSTRAINT prediction_record_organization_id_id_key UNIQUE (organization_id, id);


--
-- Name: prediction_record prediction_record_pkey; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.prediction_record
    ADD CONSTRAINT prediction_record_pkey PRIMARY KEY (id);


--
-- Name: prediction_target prediction_target_organization_id_id_key; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.prediction_target
    ADD CONSTRAINT prediction_target_organization_id_id_key UNIQUE (organization_id, id);


--
-- Name: prediction_target prediction_target_organization_id_target_code_key; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.prediction_target
    ADD CONSTRAINT prediction_target_organization_id_target_code_key UNIQUE (organization_id, target_code);


--
-- Name: prediction_target prediction_target_pkey; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.prediction_target
    ADD CONSTRAINT prediction_target_pkey PRIMARY KEY (id);


--
-- Name: research_candidate_history_observation_v2 research_candidate_history_ob_organization_id_research_run__key; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.research_candidate_history_observation_v2
    ADD CONSTRAINT research_candidate_history_ob_organization_id_research_run__key UNIQUE (organization_id, research_run_id, research_candidate_id, field_code);


--
-- Name: research_candidate_history_observation_v2 research_candidate_history_observation_v2_pkey; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.research_candidate_history_observation_v2
    ADD CONSTRAINT research_candidate_history_observation_v2_pkey PRIMARY KEY (id);


--
-- Name: research_candidate_observation_v2 research_candidate_observatio_organization_id_research_run__key; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.research_candidate_observation_v2
    ADD CONSTRAINT research_candidate_observatio_organization_id_research_run__key UNIQUE (organization_id, research_run_id, research_candidate_id, target_id);


--
-- Name: research_candidate_observation_v2 research_candidate_observation_v2_organization_id_id_key; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.research_candidate_observation_v2
    ADD CONSTRAINT research_candidate_observation_v2_organization_id_id_key UNIQUE (organization_id, id);


--
-- Name: research_candidate_observation_v2 research_candidate_observation_v2_pkey; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.research_candidate_observation_v2
    ADD CONSTRAINT research_candidate_observation_v2_pkey PRIMARY KEY (id);


--
-- Name: research_candidate_v2 research_candidate_v2_organization_id_id_key; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.research_candidate_v2
    ADD CONSTRAINT research_candidate_v2_organization_id_id_key UNIQUE (organization_id, id);


--
-- Name: research_candidate_v2 research_candidate_v2_organization_id_research_run_id_candi_key; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.research_candidate_v2
    ADD CONSTRAINT research_candidate_v2_organization_id_research_run_id_candi_key UNIQUE (organization_id, research_run_id, candidate_no);


--
-- Name: research_candidate_v2 research_candidate_v2_organization_id_research_run_id_conte_key; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.research_candidate_v2
    ADD CONSTRAINT research_candidate_v2_organization_id_research_run_id_conte_key UNIQUE (organization_id, research_run_id, content_hash);


--
-- Name: research_candidate_v2 research_candidate_v2_organization_id_research_run_id_id_key; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.research_candidate_v2
    ADD CONSTRAINT research_candidate_v2_organization_id_research_run_id_id_key UNIQUE (organization_id, research_run_id, id);


--
-- Name: research_candidate_v2 research_candidate_v2_pkey; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.research_candidate_v2
    ADD CONSTRAINT research_candidate_v2_pkey PRIMARY KEY (id);


--
-- Name: research_experiment_link_v2 research_experiment_link_v2_organization_id_id_key; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.research_experiment_link_v2
    ADD CONSTRAINT research_experiment_link_v2_organization_id_id_key UNIQUE (organization_id, id);


--
-- Name: research_experiment_link_v2 research_experiment_link_v2_organization_id_idempotency_key_key; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.research_experiment_link_v2
    ADD CONSTRAINT research_experiment_link_v2_organization_id_idempotency_key_key UNIQUE (organization_id, idempotency_key);


--
-- Name: research_experiment_link_v2 research_experiment_link_v2_pkey; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.research_experiment_link_v2
    ADD CONSTRAINT research_experiment_link_v2_pkey PRIMARY KEY (id);


--
-- Name: research_run_v2 research_run_v2_organization_id_id_key; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.research_run_v2
    ADD CONSTRAINT research_run_v2_organization_id_id_key UNIQUE (organization_id, id);


--
-- Name: research_run_v2 research_run_v2_organization_id_idempotency_key_key; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.research_run_v2
    ADD CONSTRAINT research_run_v2_organization_id_idempotency_key_key UNIQUE (organization_id, idempotency_key);


--
-- Name: research_run_v2 research_run_v2_pkey; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.research_run_v2
    ADD CONSTRAINT research_run_v2_pkey PRIMARY KEY (id);


--
-- Name: result_evidence result_evidence_pkey; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.result_evidence
    ADD CONSTRAINT result_evidence_pkey PRIMARY KEY (id);


--
-- Name: result_feedback result_feedback_pkey; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.result_feedback
    ADD CONSTRAINT result_feedback_pkey PRIMARY KEY (id);


--
-- Name: result_occurrence result_occurrence_pkey; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.result_occurrence
    ADD CONSTRAINT result_occurrence_pkey PRIMARY KEY (id);


--
-- Name: result result_organization_id_id_key; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.result
    ADD CONSTRAINT result_organization_id_id_key UNIQUE (organization_id, id);


--
-- Name: result result_organization_id_rule_code_subject_type_subject_id_di_key; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.result
    ADD CONSTRAINT result_organization_id_rule_code_subject_type_subject_id_di_key UNIQUE (organization_id, rule_code, subject_type, subject_id, discriminator);


--
-- Name: result result_pkey; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.result
    ADD CONSTRAINT result_pkey PRIMARY KEY (id);


--
-- Name: rule_version rule_version_pkey; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.rule_version
    ADD CONSTRAINT rule_version_pkey PRIMARY KEY (id);


--
-- Name: sample_identity_issue sample_identity_issue_organization_id_id_key; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.sample_identity_issue
    ADD CONSTRAINT sample_identity_issue_organization_id_id_key UNIQUE (organization_id, id);


--
-- Name: sample_identity_issue sample_identity_issue_pkey; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.sample_identity_issue
    ADD CONSTRAINT sample_identity_issue_pkey PRIMARY KEY (id);


--
-- Name: sample_revision sample_revision_organization_id_id_key; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.sample_revision
    ADD CONSTRAINT sample_revision_organization_id_id_key UNIQUE (organization_id, id);


--
-- Name: sample_revision sample_revision_organization_id_training_sample_id_revision_key; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.sample_revision
    ADD CONSTRAINT sample_revision_organization_id_training_sample_id_revision_key UNIQUE (organization_id, training_sample_id, revision_no);


--
-- Name: sample_revision sample_revision_pkey; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.sample_revision
    ADD CONSTRAINT sample_revision_pkey PRIMARY KEY (id);


--
-- Name: sample_source sample_source_organization_id_id_key; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.sample_source
    ADD CONSTRAINT sample_source_organization_id_id_key UNIQUE (organization_id, id);


--
-- Name: sample_source sample_source_organization_id_source_type_source_business_k_key; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.sample_source
    ADD CONSTRAINT sample_source_organization_id_source_type_source_business_k_key UNIQUE (organization_id, source_type, source_business_key, source_version);


--
-- Name: sample_source sample_source_organization_id_training_sample_id_id_key; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.sample_source
    ADD CONSTRAINT sample_source_organization_id_training_sample_id_id_key UNIQUE (organization_id, training_sample_id, id);


--
-- Name: sample_source sample_source_pkey; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.sample_source
    ADD CONSTRAINT sample_source_pkey PRIMARY KEY (id);


--
-- Name: signal signal_organization_id_deduplication_key_occurred_at_key; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.signal
    ADD CONSTRAINT signal_organization_id_deduplication_key_occurred_at_key UNIQUE (organization_id, deduplication_key, occurred_at);


--
-- Name: signal signal_pkey; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.signal
    ADD CONSTRAINT signal_pkey PRIMARY KEY (id);


--
-- Name: source_mapping_version source_mapping_version_organization_id_id_key; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.source_mapping_version
    ADD CONSTRAINT source_mapping_version_organization_id_id_key UNIQUE (organization_id, id);


--
-- Name: source_mapping_version source_mapping_version_organization_id_target_version_id_so_key; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.source_mapping_version
    ADD CONSTRAINT source_mapping_version_organization_id_target_version_id_so_key UNIQUE (organization_id, target_version_id, source_type, version_no);


--
-- Name: source_mapping_version source_mapping_version_pkey; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.source_mapping_version
    ADD CONSTRAINT source_mapping_version_pkey PRIMARY KEY (id);


--
-- Name: target_version target_version_organization_id_id_key; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.target_version
    ADD CONSTRAINT target_version_organization_id_id_key UNIQUE (organization_id, id);


--
-- Name: target_version target_version_organization_id_target_id_id_key; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.target_version
    ADD CONSTRAINT target_version_organization_id_target_id_id_key UNIQUE (organization_id, target_id, id);


--
-- Name: target_version target_version_organization_id_target_id_version_no_key; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.target_version
    ADD CONSTRAINT target_version_organization_id_target_id_version_no_key UNIQUE (organization_id, target_id, version_no);


--
-- Name: target_version target_version_pkey; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.target_version
    ADD CONSTRAINT target_version_pkey PRIMARY KEY (id);


--
-- Name: training_eligibility training_eligibility_organization_id_id_key; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.training_eligibility
    ADD CONSTRAINT training_eligibility_organization_id_id_key UNIQUE (organization_id, id);


--
-- Name: training_eligibility training_eligibility_pkey; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.training_eligibility
    ADD CONSTRAINT training_eligibility_pkey PRIMARY KEY (id);


--
-- Name: training_job_attempt training_job_attempt_organization_id_id_key; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.training_job_attempt
    ADD CONSTRAINT training_job_attempt_organization_id_id_key UNIQUE (organization_id, id);


--
-- Name: training_job_attempt training_job_attempt_organization_id_training_job_id_attemp_key; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.training_job_attempt
    ADD CONSTRAINT training_job_attempt_organization_id_training_job_id_attemp_key UNIQUE (organization_id, training_job_id, attempt_no);


--
-- Name: training_job_attempt training_job_attempt_pkey; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.training_job_attempt
    ADD CONSTRAINT training_job_attempt_pkey PRIMARY KEY (id);


--
-- Name: training_job training_job_organization_id_id_key; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.training_job
    ADD CONSTRAINT training_job_organization_id_id_key UNIQUE (organization_id, id);


--
-- Name: training_job training_job_organization_id_idempotency_key_key; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.training_job
    ADD CONSTRAINT training_job_organization_id_idempotency_key_key UNIQUE (organization_id, idempotency_key);


--
-- Name: training_job training_job_pkey; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.training_job
    ADD CONSTRAINT training_job_pkey PRIMARY KEY (id);


--
-- Name: training_sample training_sample_organization_id_id_key; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.training_sample
    ADD CONSTRAINT training_sample_organization_id_id_key UNIQUE (organization_id, id);


--
-- Name: training_sample training_sample_organization_id_logical_sample_key_key; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.training_sample
    ADD CONSTRAINT training_sample_organization_id_logical_sample_key_key UNIQUE (organization_id, logical_sample_key);


--
-- Name: training_sample training_sample_pkey; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.training_sample
    ADD CONSTRAINT training_sample_pkey PRIMARY KEY (id);


--
-- Name: training_schedule_intent training_schedule_intent_organization_id_id_key; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.training_schedule_intent
    ADD CONSTRAINT training_schedule_intent_organization_id_id_key UNIQUE (organization_id, id);


--
-- Name: training_schedule_intent training_schedule_intent_organization_id_target_id_key; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.training_schedule_intent
    ADD CONSTRAINT training_schedule_intent_organization_id_target_id_key UNIQUE (organization_id, target_id);


--
-- Name: training_schedule_intent training_schedule_intent_pkey; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.training_schedule_intent
    ADD CONSTRAINT training_schedule_intent_pkey PRIMARY KEY (id);


--
-- Name: training_scheduler_setting training_scheduler_setting_pkey; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.training_scheduler_setting
    ADD CONSTRAINT training_scheduler_setting_pkey PRIMARY KEY (organization_id);


--
-- Name: training_snapshot_item training_snapshot_item_pkey; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.training_snapshot_item
    ADD CONSTRAINT training_snapshot_item_pkey PRIMARY KEY (training_snapshot_id, sample_revision_id);


--
-- Name: training_snapshot_item training_snapshot_item_training_snapshot_id_ordinal_key; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.training_snapshot_item
    ADD CONSTRAINT training_snapshot_item_training_snapshot_id_ordinal_key UNIQUE (training_snapshot_id, ordinal);


--
-- Name: training_snapshot training_snapshot_organization_id_id_key; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.training_snapshot
    ADD CONSTRAINT training_snapshot_organization_id_id_key UNIQUE (organization_id, id);


--
-- Name: training_snapshot training_snapshot_organization_id_object_prefix_key; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.training_snapshot
    ADD CONSTRAINT training_snapshot_organization_id_object_prefix_key UNIQUE (organization_id, object_prefix);


--
-- Name: training_snapshot training_snapshot_organization_id_snapshot_hash_key; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.training_snapshot
    ADD CONSTRAINT training_snapshot_organization_id_snapshot_hash_key UNIQUE (organization_id, snapshot_hash);


--
-- Name: training_snapshot training_snapshot_pkey; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.training_snapshot
    ADD CONSTRAINT training_snapshot_pkey PRIMARY KEY (id);


--
-- Name: rule_definition uq_ai_rule_definition_org_code; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.rule_definition
    ADD CONSTRAINT uq_ai_rule_definition_org_code UNIQUE (organization_id, rule_code);


--
-- Name: rule_version uq_ai_rule_version_org_code_version; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.rule_version
    ADD CONSTRAINT uq_ai_rule_version_org_code_version UNIQUE (organization_id, rule_code, version_no);


--
-- Name: modeling_policy_version uq_modeling_policy_kind_version; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.modeling_policy_version
    ADD CONSTRAINT uq_modeling_policy_kind_version UNIQUE (organization_id, target_id, kind, version_no);


--
-- Name: modeling_policy_version uq_modeling_policy_target_identity; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.modeling_policy_version
    ADD CONSTRAINT uq_modeling_policy_target_identity UNIQUE (organization_id, target_id, id);


--
-- Name: prediction_record uq_prediction_record_idempotency_scope; Type: CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.prediction_record
    ADD CONSTRAINT uq_prediction_record_idempotency_scope UNIQUE (organization_id, created_by, prediction_type, idempotency_key);


--
-- Name: project_reference project_reference_organization_id_resource_type_resource_id_key; Type: CONSTRAINT; Schema: core; Owner: -
--

ALTER TABLE ONLY core.project_reference
    ADD CONSTRAINT project_reference_organization_id_resource_type_resource_id_key UNIQUE NULLS NOT DISTINCT (organization_id, resource_type, resource_id, project_id, stage_id, task_id);


--
-- Name: project_reference project_reference_pkey; Type: CONSTRAINT; Schema: core; Owner: -
--

ALTER TABLE ONLY core.project_reference
    ADD CONSTRAINT project_reference_pkey PRIMARY KEY (id);


--
-- Name: project_resource_link project_resource_link_organization_id_resource_type_resourc_key; Type: CONSTRAINT; Schema: core; Owner: -
--

ALTER TABLE ONLY core.project_resource_link
    ADD CONSTRAINT project_resource_link_organization_id_resource_type_resourc_key UNIQUE NULLS NOT DISTINCT (organization_id, resource_type, resource_id, project_id, stage_id, task_id);


--
-- Name: project_resource_link project_resource_link_pkey; Type: CONSTRAINT; Schema: core; Owner: -
--

ALTER TABLE ONLY core.project_resource_link
    ADD CONSTRAINT project_resource_link_pkey PRIMARY KEY (id);


--
-- Name: confirmed_submission_head confirmed_submission_head_pkey; Type: CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.confirmed_submission_head
    ADD CONSTRAINT confirmed_submission_head_pkey PRIMARY KEY (organization_id, import_job_id);


--
-- Name: confirmed_submission_item confirmed_submission_item_organization_id_confirmed_submiss_key; Type: CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.confirmed_submission_item
    ADD CONSTRAINT confirmed_submission_item_organization_id_confirmed_submiss_key UNIQUE (organization_id, confirmed_submission_id, source_record_id);


--
-- Name: confirmed_submission_item confirmed_submission_item_organization_id_id_key; Type: CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.confirmed_submission_item
    ADD CONSTRAINT confirmed_submission_item_organization_id_id_key UNIQUE (organization_id, id);


--
-- Name: confirmed_submission_item confirmed_submission_item_pkey; Type: CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.confirmed_submission_item
    ADD CONSTRAINT confirmed_submission_item_pkey PRIMARY KEY (id);


--
-- Name: confirmed_submission confirmed_submission_organization_id_id_key; Type: CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.confirmed_submission
    ADD CONSTRAINT confirmed_submission_organization_id_id_key UNIQUE (organization_id, id);


--
-- Name: confirmed_submission confirmed_submission_organization_id_import_job_id_id_key; Type: CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.confirmed_submission
    ADD CONSTRAINT confirmed_submission_organization_id_import_job_id_id_key UNIQUE (organization_id, import_job_id, id);


--
-- Name: confirmed_submission confirmed_submission_organization_id_import_job_id_revision_key; Type: CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.confirmed_submission
    ADD CONSTRAINT confirmed_submission_organization_id_import_job_id_revision_key UNIQUE (organization_id, import_job_id, revision_no);


--
-- Name: confirmed_submission confirmed_submission_pkey; Type: CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.confirmed_submission
    ADD CONSTRAINT confirmed_submission_pkey PRIMARY KEY (id);


--
-- Name: data_category data_category_organization_id_name_key; Type: CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.data_category
    ADD CONSTRAINT data_category_organization_id_name_key UNIQUE (organization_id, name);


--
-- Name: data_category data_category_pkey; Type: CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.data_category
    ADD CONSTRAINT data_category_pkey PRIMARY KEY (id);


--
-- Name: data_record data_record_pkey; Type: CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.data_record
    ADD CONSTRAINT data_record_pkey PRIMARY KEY (id);


--
-- Name: data_value data_value_pkey; Type: CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.data_value
    ADD CONSTRAINT data_value_pkey PRIMARY KEY (id);


--
-- Name: import_component_override import_component_override_import_job_id_component_id_key; Type: CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.import_component_override
    ADD CONSTRAINT import_component_override_import_job_id_component_id_key UNIQUE (import_job_id, component_id);


--
-- Name: import_component_override import_component_override_pkey; Type: CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.import_component_override
    ADD CONSTRAINT import_component_override_pkey PRIMARY KEY (id);


--
-- Name: import_experiment_link import_experiment_link_organization_id_import_job_id_assemb_key; Type: CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.import_experiment_link
    ADD CONSTRAINT import_experiment_link_organization_id_import_job_id_assemb_key UNIQUE (organization_id, import_job_id, assembly_key);


--
-- Name: import_experiment_link import_experiment_link_pkey; Type: CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.import_experiment_link
    ADD CONSTRAINT import_experiment_link_pkey PRIMARY KEY (id);


--
-- Name: import_issue import_issue_pkey; Type: CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.import_issue
    ADD CONSTRAINT import_issue_pkey PRIMARY KEY (id);


--
-- Name: import_job_attempt import_job_attempt_organization_id_import_job_id_attempt_no_key; Type: CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.import_job_attempt
    ADD CONSTRAINT import_job_attempt_organization_id_import_job_id_attempt_no_key UNIQUE (organization_id, import_job_id, attempt_no);


--
-- Name: import_job_attempt import_job_attempt_pkey; Type: CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.import_job_attempt
    ADD CONSTRAINT import_job_attempt_pkey PRIMARY KEY (id);


--
-- Name: import_job import_job_pkey; Type: CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.import_job
    ADD CONSTRAINT import_job_pkey PRIMARY KEY (id);


--
-- Name: import_mapping import_mapping_pkey; Type: CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.import_mapping
    ADD CONSTRAINT import_mapping_pkey PRIMARY KEY (id);


--
-- Name: import_mapping_profile import_mapping_profile_organization_id_template_version_id__key; Type: CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.import_mapping_profile
    ADD CONSTRAINT import_mapping_profile_organization_id_template_version_id__key UNIQUE (organization_id, template_version_id, source_fingerprint);


--
-- Name: import_mapping_profile import_mapping_profile_pkey; Type: CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.import_mapping_profile
    ADD CONSTRAINT import_mapping_profile_pkey PRIMARY KEY (id);


--
-- Name: import_sheet import_sheet_import_job_id_sheet_id_key; Type: CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.import_sheet
    ADD CONSTRAINT import_sheet_import_job_id_sheet_id_key UNIQUE (import_job_id, sheet_id);


--
-- Name: import_sheet import_sheet_pkey; Type: CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.import_sheet
    ADD CONSTRAINT import_sheet_pkey PRIMARY KEY (id);


--
-- Name: import_workbook_draft import_workbook_draft_pkey; Type: CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.import_workbook_draft
    ADD CONSTRAINT import_workbook_draft_pkey PRIMARY KEY (organization_id, import_job_id);


--
-- Name: publication_event publication_event_pkey; Type: CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.publication_event
    ADD CONSTRAINT publication_event_pkey PRIMARY KEY (id);


--
-- Name: publication publication_pkey; Type: CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.publication
    ADD CONSTRAINT publication_pkey PRIMARY KEY (organization_id, import_job_id);


--
-- Name: publication_release publication_release_pkey; Type: CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.publication_release
    ADD CONSTRAINT publication_release_pkey PRIMARY KEY (snapshot_id);


--
-- Name: publication_snapshot publication_snapshot_organization_id_import_job_id_version__key; Type: CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.publication_snapshot
    ADD CONSTRAINT publication_snapshot_organization_id_import_job_id_version__key UNIQUE (organization_id, import_job_id, version_no);


--
-- Name: publication_snapshot publication_snapshot_pkey; Type: CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.publication_snapshot
    ADD CONSTRAINT publication_snapshot_pkey PRIMARY KEY (id);


--
-- Name: recognition_profile recognition_profile_organization_id_id_key; Type: CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.recognition_profile
    ADD CONSTRAINT recognition_profile_organization_id_id_key UNIQUE (organization_id, id);


--
-- Name: recognition_profile recognition_profile_organization_id_profile_code_key; Type: CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.recognition_profile
    ADD CONSTRAINT recognition_profile_organization_id_profile_code_key UNIQUE (organization_id, profile_code);


--
-- Name: recognition_profile recognition_profile_pkey; Type: CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.recognition_profile
    ADD CONSTRAINT recognition_profile_pkey PRIMARY KEY (id);


--
-- Name: record_governance record_governance_pkey; Type: CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.record_governance
    ADD CONSTRAINT record_governance_pkey PRIMARY KEY (organization_id, record_id);


--
-- Name: source_anchor source_anchor_pkey; Type: CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.source_anchor
    ADD CONSTRAINT source_anchor_pkey PRIMARY KEY (id);


--
-- Name: staging_row staging_row_pkey; Type: CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.staging_row
    ADD CONSTRAINT staging_row_pkey PRIMARY KEY (id);


--
-- Name: import_job uq_data_import_job_org_id; Type: CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.import_job
    ADD CONSTRAINT uq_data_import_job_org_id UNIQUE (organization_id, id);


--
-- Name: data_record uq_data_record_org_id; Type: CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.data_record
    ADD CONSTRAINT uq_data_record_org_id UNIQUE (organization_id, id);


--
-- Name: app_user app_user_organization_id_username_key; Type: CONSTRAINT; Schema: iam; Owner: -
--

ALTER TABLE ONLY iam.app_user
    ADD CONSTRAINT app_user_organization_id_username_key UNIQUE (organization_id, username);


--
-- Name: app_user app_user_pkey; Type: CONSTRAINT; Schema: iam; Owner: -
--

ALTER TABLE ONLY iam.app_user
    ADD CONSTRAINT app_user_pkey PRIMARY KEY (id);


--
-- Name: login_attempt login_attempt_pkey; Type: CONSTRAINT; Schema: iam; Owner: -
--

ALTER TABLE ONLY iam.login_attempt
    ADD CONSTRAINT login_attempt_pkey PRIMARY KEY (id);


--
-- Name: login_session login_session_pkey; Type: CONSTRAINT; Schema: iam; Owner: -
--

ALTER TABLE ONLY iam.login_session
    ADD CONSTRAINT login_session_pkey PRIMARY KEY (id);


--
-- Name: login_session login_session_token_hash_key; Type: CONSTRAINT; Schema: iam; Owner: -
--

ALTER TABLE ONLY iam.login_session
    ADD CONSTRAINT login_session_token_hash_key UNIQUE (token_hash);


--
-- Name: organization organization_pkey; Type: CONSTRAINT; Schema: iam; Owner: -
--

ALTER TABLE ONLY iam.organization
    ADD CONSTRAINT organization_pkey PRIMARY KEY (id);


--
-- Name: permission_definition permission_definition_pkey; Type: CONSTRAINT; Schema: iam; Owner: -
--

ALTER TABLE ONLY iam.permission_definition
    ADD CONSTRAINT permission_definition_pkey PRIMARY KEY (code);


--
-- Name: permission_level_binding permission_level_binding_pkey; Type: CONSTRAINT; Schema: iam; Owner: -
--

ALTER TABLE ONLY iam.permission_level_binding
    ADD CONSTRAINT permission_level_binding_pkey PRIMARY KEY (id);


--
-- Name: permission_level permission_level_organization_id_code_key; Type: CONSTRAINT; Schema: iam; Owner: -
--

ALTER TABLE ONLY iam.permission_level
    ADD CONSTRAINT permission_level_organization_id_code_key UNIQUE (organization_id, code);


--
-- Name: permission_level permission_level_pkey; Type: CONSTRAINT; Schema: iam; Owner: -
--

ALTER TABLE ONLY iam.permission_level
    ADD CONSTRAINT permission_level_pkey PRIMARY KEY (id);


--
-- Name: permission_migration_issue permission_migration_issue_pkey; Type: CONSTRAINT; Schema: iam; Owner: -
--

ALTER TABLE ONLY iam.permission_migration_issue
    ADD CONSTRAINT permission_migration_issue_pkey PRIMARY KEY (id);


--
-- Name: permission_scope_target permission_scope_target_organization_id_owner_type_owner_id_key; Type: CONSTRAINT; Schema: iam; Owner: -
--

ALTER TABLE ONLY iam.permission_scope_target
    ADD CONSTRAINT permission_scope_target_organization_id_owner_type_owner_id_key UNIQUE (organization_id, owner_type, owner_id, permission_code, target_type, target_id);


--
-- Name: permission_scope_target permission_scope_target_pkey; Type: CONSTRAINT; Schema: iam; Owner: -
--

ALTER TABLE ONLY iam.permission_scope_target
    ADD CONSTRAINT permission_scope_target_pkey PRIMARY KEY (id);


--
-- Name: project_member project_member_pkey; Type: CONSTRAINT; Schema: iam; Owner: -
--

ALTER TABLE ONLY iam.project_member
    ADD CONSTRAINT project_member_pkey PRIMARY KEY (organization_id, project_id, user_id);


--
-- Name: resource_owner_identity resource_owner_identity_pkey; Type: CONSTRAINT; Schema: iam; Owner: -
--

ALTER TABLE ONLY iam.resource_owner_identity
    ADD CONSTRAINT resource_owner_identity_pkey PRIMARY KEY (organization_id, resource_type, resource_id, user_id, relation_type);


--
-- Name: role_menu_binding role_menu_binding_organization_id_role_id_menu_key_key; Type: CONSTRAINT; Schema: iam; Owner: -
--

ALTER TABLE ONLY iam.role_menu_binding
    ADD CONSTRAINT role_menu_binding_organization_id_role_id_menu_key_key UNIQUE (organization_id, role_id, menu_key);


--
-- Name: role_menu_binding role_menu_binding_pkey; Type: CONSTRAINT; Schema: iam; Owner: -
--

ALTER TABLE ONLY iam.role_menu_binding
    ADD CONSTRAINT role_menu_binding_pkey PRIMARY KEY (id);


--
-- Name: role role_organization_id_code_key; Type: CONSTRAINT; Schema: iam; Owner: -
--

ALTER TABLE ONLY iam.role
    ADD CONSTRAINT role_organization_id_code_key UNIQUE (organization_id, code);


--
-- Name: role_permission_binding role_permission_binding_organization_id_role_id_permission__key; Type: CONSTRAINT; Schema: iam; Owner: -
--

ALTER TABLE ONLY iam.role_permission_binding
    ADD CONSTRAINT role_permission_binding_organization_id_role_id_permission__key UNIQUE (organization_id, role_id, permission_code);


--
-- Name: role_permission_binding role_permission_binding_pkey; Type: CONSTRAINT; Schema: iam; Owner: -
--

ALTER TABLE ONLY iam.role_permission_binding
    ADD CONSTRAINT role_permission_binding_pkey PRIMARY KEY (id);


--
-- Name: role role_pkey; Type: CONSTRAINT; Schema: iam; Owner: -
--

ALTER TABLE ONLY iam.role
    ADD CONSTRAINT role_pkey PRIMARY KEY (id);


--
-- Name: user_menu_override user_menu_override_pkey; Type: CONSTRAINT; Schema: iam; Owner: -
--

ALTER TABLE ONLY iam.user_menu_override
    ADD CONSTRAINT user_menu_override_pkey PRIMARY KEY (organization_id, user_id, menu_key);


--
-- Name: user_operation_override user_operation_override_pkey; Type: CONSTRAINT; Schema: iam; Owner: -
--

ALTER TABLE ONLY iam.user_operation_override
    ADD CONSTRAINT user_operation_override_pkey PRIMARY KEY (organization_id, user_id, menu_key, permission_code);


--
-- Name: user_permission_override user_permission_override_organization_id_user_id_permission_key; Type: CONSTRAINT; Schema: iam; Owner: -
--

ALTER TABLE ONLY iam.user_permission_override
    ADD CONSTRAINT user_permission_override_organization_id_user_id_permission_key UNIQUE (organization_id, user_id, permission_code);


--
-- Name: user_permission_override user_permission_override_pkey; Type: CONSTRAINT; Schema: iam; Owner: -
--

ALTER TABLE ONLY iam.user_permission_override
    ADD CONSTRAINT user_permission_override_pkey PRIMARY KEY (id);


--
-- Name: user_permission_profile user_permission_profile_pkey; Type: CONSTRAINT; Schema: iam; Owner: -
--

ALTER TABLE ONLY iam.user_permission_profile
    ADD CONSTRAINT user_permission_profile_pkey PRIMARY KEY (user_id);


--
-- Name: user_role user_role_pkey; Type: CONSTRAINT; Schema: iam; Owner: -
--

ALTER TABLE ONLY iam.user_role
    ADD CONSTRAINT user_role_pkey PRIMARY KEY (organization_id, user_id, role_id);


--
-- Name: user_scope_override user_scope_override_pkey; Type: CONSTRAINT; Schema: iam; Owner: -
--

ALTER TABLE ONLY iam.user_scope_override
    ADD CONSTRAINT user_scope_override_pkey PRIMARY KEY (organization_id, user_id, menu_key, permission_code);


--
-- Name: chunk_term chunk_term_pkey; Type: CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.chunk_term
    ADD CONSTRAINT chunk_term_pkey PRIMARY KEY (chunk_id, term);


--
-- Name: document_ai_grant document_ai_grant_pkey; Type: CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.document_ai_grant
    ADD CONSTRAINT document_ai_grant_pkey PRIMARY KEY (document_id);


--
-- Name: document_category document_category_organization_id_scope_name_key; Type: CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.document_category
    ADD CONSTRAINT document_category_organization_id_scope_name_key UNIQUE (organization_id, scope, name);


--
-- Name: document_category document_category_pkey; Type: CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.document_category
    ADD CONSTRAINT document_category_pkey PRIMARY KEY (id);


--
-- Name: document_chunk document_chunk_pkey; Type: CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.document_chunk
    ADD CONSTRAINT document_chunk_pkey PRIMARY KEY (id);


--
-- Name: document document_organization_id_title_current_version_no_key; Type: CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.document
    ADD CONSTRAINT document_organization_id_title_current_version_no_key UNIQUE (organization_id, title, current_version_no);


--
-- Name: document_parse_issue document_parse_issue_pkey; Type: CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.document_parse_issue
    ADD CONSTRAINT document_parse_issue_pkey PRIMARY KEY (id);


--
-- Name: document_parse_issue_source_node document_parse_issue_source_node_pkey; Type: CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.document_parse_issue_source_node
    ADD CONSTRAINT document_parse_issue_source_node_pkey PRIMARY KEY (parse_issue_id, source_node_key);


--
-- Name: document_parse_run document_parse_run_document_version_id_run_no_key; Type: CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.document_parse_run
    ADD CONSTRAINT document_parse_run_document_version_id_run_no_key UNIQUE (document_version_id, run_no);


--
-- Name: document_parse_run document_parse_run_pkey; Type: CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.document_parse_run
    ADD CONSTRAINT document_parse_run_pkey PRIMARY KEY (id);


--
-- Name: document document_pkey; Type: CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.document
    ADD CONSTRAINT document_pkey PRIMARY KEY (id);


--
-- Name: document_processing_step document_processing_step_pkey; Type: CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.document_processing_step
    ADD CONSTRAINT document_processing_step_pkey PRIMARY KEY (id);


--
-- Name: document_result_asset document_result_asset_asset_file_id_key; Type: CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.document_result_asset
    ADD CONSTRAINT document_result_asset_asset_file_id_key UNIQUE (asset_file_id);


--
-- Name: document_result_asset document_result_asset_pkey; Type: CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.document_result_asset
    ADD CONSTRAINT document_result_asset_pkey PRIMARY KEY (id);


--
-- Name: document_result_asset document_result_asset_result_file_id_entry_path_key; Type: CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.document_result_asset
    ADD CONSTRAINT document_result_asset_result_file_id_entry_path_key UNIQUE (result_file_id, entry_path);


--
-- Name: document_review_issue_state document_review_issue_state_pkey; Type: CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.document_review_issue_state
    ADD CONSTRAINT document_review_issue_state_pkey PRIMARY KEY (review_revision_id, parse_issue_id);


--
-- Name: document_review_revision document_review_revision_document_id_revision_no_key; Type: CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.document_review_revision
    ADD CONSTRAINT document_review_revision_document_id_revision_no_key UNIQUE (document_id, revision_no);


--
-- Name: document_review_revision document_review_revision_pkey; Type: CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.document_review_revision
    ADD CONSTRAINT document_review_revision_pkey PRIMARY KEY (id);


--
-- Name: document_review_table_cell_patch document_review_table_cell_patch_pkey; Type: CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.document_review_table_cell_patch
    ADD CONSTRAINT document_review_table_cell_patch_pkey PRIMARY KEY (review_revision_id, source_table_id, row_no, column_no);


--
-- Name: document_review_table_row_state document_review_table_row_state_pkey; Type: CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.document_review_table_row_state
    ADD CONSTRAINT document_review_table_row_state_pkey PRIMARY KEY (review_revision_id, source_table_id, row_no);


--
-- Name: document_source_node document_source_node_parse_run_id_node_no_key; Type: CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.document_source_node
    ADD CONSTRAINT document_source_node_parse_run_id_node_no_key UNIQUE (parse_run_id, node_no);


--
-- Name: document_source_node document_source_node_pkey; Type: CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.document_source_node
    ADD CONSTRAINT document_source_node_pkey PRIMARY KEY (source_node_key);


--
-- Name: document_source_table_cell document_source_table_cell_pkey; Type: CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.document_source_table_cell
    ADD CONSTRAINT document_source_table_cell_pkey PRIMARY KEY (source_table_id, row_no, column_no);


--
-- Name: document_source_table document_source_table_parse_run_id_sheet_key_key; Type: CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.document_source_table
    ADD CONSTRAINT document_source_table_parse_run_id_sheet_key_key UNIQUE (parse_run_id, sheet_key);


--
-- Name: document_source_table document_source_table_pkey; Type: CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.document_source_table
    ADD CONSTRAINT document_source_table_pkey PRIMARY KEY (id);


--
-- Name: document_tag document_tag_pkey; Type: CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.document_tag
    ADD CONSTRAINT document_tag_pkey PRIMARY KEY (document_id, tag_id);


--
-- Name: document_version document_version_document_id_version_no_key; Type: CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.document_version
    ADD CONSTRAINT document_version_document_id_version_no_key UNIQUE (document_id, version_no);


--
-- Name: document_version document_version_pkey; Type: CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.document_version
    ADD CONSTRAINT document_version_pkey PRIMARY KEY (id);


--
-- Name: publication publication_document_id_publication_no_key; Type: CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.publication
    ADD CONSTRAINT publication_document_id_publication_no_key UNIQUE (document_id, publication_no);


--
-- Name: publication publication_pkey; Type: CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.publication
    ADD CONSTRAINT publication_pkey PRIMARY KEY (id);


--
-- Name: tag tag_organization_id_normalized_name_key; Type: CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.tag
    ADD CONSTRAINT tag_organization_id_normalized_name_key UNIQUE (organization_id, normalized_name);


--
-- Name: tag tag_pkey; Type: CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.tag
    ADD CONSTRAINT tag_pkey PRIMARY KEY (id);


--
-- Name: term_stat term_stat_pkey; Type: CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.term_stat
    ADD CONSTRAINT term_stat_pkey PRIMARY KEY (organization_id, term, analyzer_version);


--
-- Name: upload_task upload_task_pkey; Type: CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.upload_task
    ADD CONSTRAINT upload_task_pkey PRIMARY KEY (id);


--
-- Name: business_partner business_partner_normalized_name_key; Type: CONSTRAINT; Schema: mdm; Owner: -
--

ALTER TABLE ONLY mdm.business_partner
    ADD CONSTRAINT business_partner_normalized_name_key UNIQUE (normalized_name);


--
-- Name: business_partner business_partner_partner_code_key; Type: CONSTRAINT; Schema: mdm; Owner: -
--

ALTER TABLE ONLY mdm.business_partner
    ADD CONSTRAINT business_partner_partner_code_key UNIQUE (partner_code);


--
-- Name: business_partner business_partner_pkey; Type: CONSTRAINT; Schema: mdm; Owner: -
--

ALTER TABLE ONLY mdm.business_partner
    ADD CONSTRAINT business_partner_pkey PRIMARY KEY (id);


--
-- Name: communication_record communication_record_pkey; Type: CONSTRAINT; Schema: mdm; Owner: -
--

ALTER TABLE ONLY mdm.communication_record
    ADD CONSTRAINT communication_record_pkey PRIMARY KEY (id);


--
-- Name: communication_record communication_record_record_code_key; Type: CONSTRAINT; Schema: mdm; Owner: -
--

ALTER TABLE ONLY mdm.communication_record
    ADD CONSTRAINT communication_record_record_code_key UNIQUE (record_code);


--
-- Name: customer_requirement customer_requirement_pkey; Type: CONSTRAINT; Schema: mdm; Owner: -
--

ALTER TABLE ONLY mdm.customer_requirement
    ADD CONSTRAINT customer_requirement_pkey PRIMARY KEY (id);


--
-- Name: customer_requirement customer_requirement_requirement_code_key; Type: CONSTRAINT; Schema: mdm; Owner: -
--

ALTER TABLE ONLY mdm.customer_requirement
    ADD CONSTRAINT customer_requirement_requirement_code_key UNIQUE (requirement_code);


--
-- Name: material_alias material_alias_organization_id_id_key; Type: CONSTRAINT; Schema: mdm; Owner: -
--

ALTER TABLE ONLY mdm.material_alias
    ADD CONSTRAINT material_alias_organization_id_id_key UNIQUE (organization_id, id);


--
-- Name: material_alias material_alias_organization_id_normalized_alias_key; Type: CONSTRAINT; Schema: mdm; Owner: -
--

ALTER TABLE ONLY mdm.material_alias
    ADD CONSTRAINT material_alias_organization_id_normalized_alias_key UNIQUE (organization_id, normalized_alias);


--
-- Name: material_alias material_alias_pkey; Type: CONSTRAINT; Schema: mdm; Owner: -
--

ALTER TABLE ONLY mdm.material_alias
    ADD CONSTRAINT material_alias_pkey PRIMARY KEY (id);


--
-- Name: material material_pkey; Type: CONSTRAINT; Schema: mdm; Owner: -
--

ALTER TABLE ONLY mdm.material
    ADD CONSTRAINT material_pkey PRIMARY KEY (id);


--
-- Name: meeting_minutes meeting_minutes_pkey; Type: CONSTRAINT; Schema: mdm; Owner: -
--

ALTER TABLE ONLY mdm.meeting_minutes
    ADD CONSTRAINT meeting_minutes_pkey PRIMARY KEY (id);


--
-- Name: partner_contact partner_contact_pkey; Type: CONSTRAINT; Schema: mdm; Owner: -
--

ALTER TABLE ONLY mdm.partner_contact
    ADD CONSTRAINT partner_contact_pkey PRIMARY KEY (id);


--
-- Name: customer_requirement_project partner_requirement_project_pkey; Type: CONSTRAINT; Schema: mdm; Owner: -
--

ALTER TABLE ONLY mdm.customer_requirement_project
    ADD CONSTRAINT partner_requirement_project_pkey PRIMARY KEY (requirement_id, project_id);


--
-- Name: project_document_audit project_document_audit_pkey; Type: CONSTRAINT; Schema: mdm; Owner: -
--

ALTER TABLE ONLY mdm.project_document_audit
    ADD CONSTRAINT project_document_audit_pkey PRIMARY KEY (id);


--
-- Name: project_document project_document_pkey; Type: CONSTRAINT; Schema: mdm; Owner: -
--

ALTER TABLE ONLY mdm.project_document
    ADD CONSTRAINT project_document_pkey PRIMARY KEY (id);


--
-- Name: project_document_review project_document_review_pkey; Type: CONSTRAINT; Schema: mdm; Owner: -
--

ALTER TABLE ONLY mdm.project_document_review
    ADD CONSTRAINT project_document_review_pkey PRIMARY KEY (id);


--
-- Name: project_document_version project_document_version_document_id_version_no_key; Type: CONSTRAINT; Schema: mdm; Owner: -
--

ALTER TABLE ONLY mdm.project_document_version
    ADD CONSTRAINT project_document_version_document_id_version_no_key UNIQUE (document_id, version_no);


--
-- Name: project_document_version project_document_version_pkey; Type: CONSTRAINT; Schema: mdm; Owner: -
--

ALTER TABLE ONLY mdm.project_document_version
    ADD CONSTRAINT project_document_version_pkey PRIMARY KEY (id);


--
-- Name: project project_pkey; Type: CONSTRAINT; Schema: mdm; Owner: -
--

ALTER TABLE ONLY mdm.project
    ADD CONSTRAINT project_pkey PRIMARY KEY (id);


--
-- Name: project project_project_code_key; Type: CONSTRAINT; Schema: mdm; Owner: -
--

ALTER TABLE ONLY mdm.project
    ADD CONSTRAINT project_project_code_key UNIQUE (project_code);


--
-- Name: project_stage project_stage_pkey; Type: CONSTRAINT; Schema: mdm; Owner: -
--

ALTER TABLE ONLY mdm.project_stage
    ADD CONSTRAINT project_stage_pkey PRIMARY KEY (id);


--
-- Name: project_task project_task_pkey; Type: CONSTRAINT; Schema: mdm; Owner: -
--

ALTER TABLE ONLY mdm.project_task
    ADD CONSTRAINT project_task_pkey PRIMARY KEY (id);


--
-- Name: project_task project_task_task_code_key; Type: CONSTRAINT; Schema: mdm; Owner: -
--

ALTER TABLE ONLY mdm.project_task
    ADD CONSTRAINT project_task_task_code_key UNIQUE (task_code);


--
-- Name: inventory_stock_import initial_stock_import_pkey; Type: CONSTRAINT; Schema: mfg; Owner: -
--

ALTER TABLE ONLY mfg.inventory_stock_import
    ADD CONSTRAINT initial_stock_import_pkey PRIMARY KEY (id);


--
-- Name: inventory_policy inventory_policy_organization_id_product_id_inventory_scope_key; Type: CONSTRAINT; Schema: mfg; Owner: -
--

ALTER TABLE ONLY mfg.inventory_policy
    ADD CONSTRAINT inventory_policy_organization_id_product_id_inventory_scope_key UNIQUE (organization_id, product_id, inventory_scope);


--
-- Name: inventory_policy inventory_policy_pkey; Type: CONSTRAINT; Schema: mfg; Owner: -
--

ALTER TABLE ONLY mfg.inventory_policy
    ADD CONSTRAINT inventory_policy_pkey PRIMARY KEY (id);


--
-- Name: inventory_product inventory_product_organization_id_code_key; Type: CONSTRAINT; Schema: mfg; Owner: -
--

ALTER TABLE ONLY mfg.inventory_product
    ADD CONSTRAINT inventory_product_organization_id_code_key UNIQUE (organization_id, code);


--
-- Name: inventory_product inventory_product_pkey; Type: CONSTRAINT; Schema: mfg; Owner: -
--

ALTER TABLE ONLY mfg.inventory_product
    ADD CONSTRAINT inventory_product_pkey PRIMARY KEY (organization_id, id);


--
-- Name: inventory_shipment_header inventory_shipment_header_org_no_unique; Type: CONSTRAINT; Schema: mfg; Owner: -
--

ALTER TABLE ONLY mfg.inventory_shipment_header
    ADD CONSTRAINT inventory_shipment_header_org_no_unique UNIQUE (organization_id, shipment_no);


--
-- Name: inventory_shipment_header inventory_shipment_header_pkey; Type: CONSTRAINT; Schema: mfg; Owner: -
--

ALTER TABLE ONLY mfg.inventory_shipment_header
    ADD CONSTRAINT inventory_shipment_header_pkey PRIMARY KEY (id);


--
-- Name: inventory_transaction inventory_transaction_organization_id_idempotency_key_key; Type: CONSTRAINT; Schema: mfg; Owner: -
--

ALTER TABLE ONLY mfg.inventory_transaction
    ADD CONSTRAINT inventory_transaction_organization_id_idempotency_key_key UNIQUE (organization_id, idempotency_key);


--
-- Name: inventory_transaction inventory_transaction_organization_id_inventory_scope_docum_key; Type: CONSTRAINT; Schema: mfg; Owner: -
--

ALTER TABLE ONLY mfg.inventory_transaction
    ADD CONSTRAINT inventory_transaction_organization_id_inventory_scope_docum_key UNIQUE (organization_id, inventory_scope, document_no, reason);


--
-- Name: inventory_transaction inventory_transaction_pkey; Type: CONSTRAINT; Schema: mfg; Owner: -
--

ALTER TABLE ONLY mfg.inventory_transaction
    ADD CONSTRAINT inventory_transaction_pkey PRIMARY KEY (id);


--
-- Name: production_ingest_item production_ingest_item_ingest_job_id_item_key_key; Type: CONSTRAINT; Schema: mfg; Owner: -
--

ALTER TABLE ONLY mfg.production_ingest_item
    ADD CONSTRAINT production_ingest_item_ingest_job_id_item_key_key UNIQUE (ingest_job_id, item_key);


--
-- Name: production_ingest_item production_ingest_item_pkey; Type: CONSTRAINT; Schema: mfg; Owner: -
--

ALTER TABLE ONLY mfg.production_ingest_item
    ADD CONSTRAINT production_ingest_item_pkey PRIMARY KEY (id);


--
-- Name: production_ingest_job production_ingest_job_pkey; Type: CONSTRAINT; Schema: mfg; Owner: -
--

ALTER TABLE ONLY mfg.production_ingest_job
    ADD CONSTRAINT production_ingest_job_pkey PRIMARY KEY (id);


--
-- Name: production_ingest_source production_ingest_source_pkey; Type: CONSTRAINT; Schema: mfg; Owner: -
--

ALTER TABLE ONLY mfg.production_ingest_source
    ADD CONSTRAINT production_ingest_source_pkey PRIMARY KEY (ingest_job_id, file_object_id);


--
-- Name: production_order production_order_organization_id_order_no_key; Type: CONSTRAINT; Schema: mfg; Owner: -
--

ALTER TABLE ONLY mfg.production_order
    ADD CONSTRAINT production_order_organization_id_order_no_key UNIQUE (organization_id, order_no);


--
-- Name: production_order production_order_pkey; Type: CONSTRAINT; Schema: mfg; Owner: -
--

ALTER TABLE ONLY mfg.production_order
    ADD CONSTRAINT production_order_pkey PRIMARY KEY (id);


--
-- Name: production_upload_field production_upload_field_pkey; Type: CONSTRAINT; Schema: mfg; Owner: -
--

ALTER TABLE ONLY mfg.production_upload_field
    ADD CONSTRAINT production_upload_field_pkey PRIMARY KEY (id);


--
-- Name: production_upload_field production_upload_field_production_upload_id_item_key_key; Type: CONSTRAINT; Schema: mfg; Owner: -
--

ALTER TABLE ONLY mfg.production_upload_field
    ADD CONSTRAINT production_upload_field_production_upload_id_item_key_key UNIQUE (production_upload_id, item_key);


--
-- Name: production_upload production_upload_pkey; Type: CONSTRAINT; Schema: mfg; Owner: -
--

ALTER TABLE ONLY mfg.production_upload
    ADD CONSTRAINT production_upload_pkey PRIMARY KEY (id);


--
-- Name: production_upload_version production_upload_version_pkey; Type: CONSTRAINT; Schema: mfg; Owner: -
--

ALTER TABLE ONLY mfg.production_upload_version
    ADD CONSTRAINT production_upload_version_pkey PRIMARY KEY (id);


--
-- Name: inventory_sample_dispatch sample_dispatch_inventory_transaction_id_key; Type: CONSTRAINT; Schema: mfg; Owner: -
--

ALTER TABLE ONLY mfg.inventory_sample_dispatch
    ADD CONSTRAINT sample_dispatch_inventory_transaction_id_key UNIQUE (inventory_transaction_id);


--
-- Name: inventory_sample_dispatch sample_dispatch_organization_id_dispatch_no_key; Type: CONSTRAINT; Schema: mfg; Owner: -
--

ALTER TABLE ONLY mfg.inventory_sample_dispatch
    ADD CONSTRAINT sample_dispatch_organization_id_dispatch_no_key UNIQUE (organization_id, dispatch_no);


--
-- Name: inventory_sample_dispatch sample_dispatch_pkey; Type: CONSTRAINT; Schema: mfg; Owner: -
--

ALTER TABLE ONLY mfg.inventory_sample_dispatch
    ADD CONSTRAINT sample_dispatch_pkey PRIMARY KEY (id);


--
-- Name: sample_recipient_preference sample_recipient_preference_pkey; Type: CONSTRAINT; Schema: mfg; Owner: -
--

ALTER TABLE ONLY mfg.sample_recipient_preference
    ADD CONSTRAINT sample_recipient_preference_pkey PRIMARY KEY (customer_id);


--
-- Name: inventory_shipment shipment_inventory_transaction_id_key; Type: CONSTRAINT; Schema: mfg; Owner: -
--

ALTER TABLE ONLY mfg.inventory_shipment
    ADD CONSTRAINT shipment_inventory_transaction_id_key UNIQUE (inventory_transaction_id);


--
-- Name: inventory_shipment shipment_pkey; Type: CONSTRAINT; Schema: mfg; Owner: -
--

ALTER TABLE ONLY mfg.inventory_shipment
    ADD CONSTRAINT shipment_pkey PRIMARY KEY (id);


--
-- Name: inventory_stock_balance stock_balance_organization_id_product_id_inventory_scope_key; Type: CONSTRAINT; Schema: mfg; Owner: -
--

ALTER TABLE ONLY mfg.inventory_stock_balance
    ADD CONSTRAINT stock_balance_organization_id_product_id_inventory_scope_key UNIQUE (organization_id, product_id, inventory_scope);


--
-- Name: inventory_stock_balance stock_balance_pkey; Type: CONSTRAINT; Schema: mfg; Owner: -
--

ALTER TABLE ONLY mfg.inventory_stock_balance
    ADD CONSTRAINT stock_balance_pkey PRIMARY KEY (id);


--
-- Name: production_upload uq_production_upload_org_file; Type: CONSTRAINT; Schema: mfg; Owner: -
--

ALTER TABLE ONLY mfg.production_upload
    ADD CONSTRAINT uq_production_upload_org_file UNIQUE (organization_id, file_id);


--
-- Name: production_upload_version uq_production_upload_version; Type: CONSTRAINT; Schema: mfg; Owner: -
--

ALTER TABLE ONLY mfg.production_upload_version
    ADD CONSTRAINT uq_production_upload_version UNIQUE (organization_id, production_upload_id, version_no);


--
-- Name: async_job async_job_organization_id_idempotency_key_key; Type: CONSTRAINT; Schema: ops; Owner: -
--

ALTER TABLE ONLY ops.async_job
    ADD CONSTRAINT async_job_organization_id_idempotency_key_key UNIQUE (organization_id, idempotency_key);


--
-- Name: async_job async_job_pkey; Type: CONSTRAINT; Schema: ops; Owner: -
--

ALTER TABLE ONLY ops.async_job
    ADD CONSTRAINT async_job_pkey PRIMARY KEY (id);


--
-- Name: audit_log audit_log_pkey; Type: CONSTRAINT; Schema: ops; Owner: -
--

ALTER TABLE ONLY ops.audit_log
    ADD CONSTRAINT audit_log_pkey PRIMARY KEY (id);


--
-- Name: file_object file_object_bucket_object_key_key; Type: CONSTRAINT; Schema: ops; Owner: -
--

ALTER TABLE ONLY ops.file_object
    ADD CONSTRAINT file_object_bucket_object_key_key UNIQUE (bucket, object_key);


--
-- Name: file_object file_object_pkey; Type: CONSTRAINT; Schema: ops; Owner: -
--

ALTER TABLE ONLY ops.file_object
    ADD CONSTRAINT file_object_pkey PRIMARY KEY (id);


--
-- Name: outbox_event outbox_event_pkey; Type: CONSTRAINT; Schema: ops; Owner: -
--

ALTER TABLE ONLY ops.outbox_event
    ADD CONSTRAINT outbox_event_pkey PRIMARY KEY (id);


--
-- Name: flyway_schema_history flyway_schema_history_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.flyway_schema_history
    ADD CONSTRAINT flyway_schema_history_pk PRIMARY KEY (installed_rank);


--
-- Name: data_category data_category_organization_id_business_type_name_key; Type: CONSTRAINT; Schema: quality; Owner: -
--

ALTER TABLE ONLY quality.data_category
    ADD CONSTRAINT data_category_organization_id_business_type_name_key UNIQUE (organization_id, business_type, name);


--
-- Name: data_category data_category_pkey; Type: CONSTRAINT; Schema: quality; Owner: -
--

ALTER TABLE ONLY quality.data_category
    ADD CONSTRAINT data_category_pkey PRIMARY KEY (id);


--
-- Name: data_record data_record_pkey; Type: CONSTRAINT; Schema: quality; Owner: -
--

ALTER TABLE ONLY quality.data_record
    ADD CONSTRAINT data_record_pkey PRIMARY KEY (id);


--
-- Name: data_record_version data_record_version_pkey; Type: CONSTRAINT; Schema: quality; Owner: -
--

ALTER TABLE ONLY quality.data_record_version
    ADD CONSTRAINT data_record_version_pkey PRIMARY KEY (id);


--
-- Name: data_record_version data_record_version_record_id_version_no_key; Type: CONSTRAINT; Schema: quality; Owner: -
--

ALTER TABLE ONLY quality.data_record_version
    ADD CONSTRAINT data_record_version_record_id_version_no_key UNIQUE (record_id, version_no);


--
-- Name: upload_file upload_file_pkey; Type: CONSTRAINT; Schema: quality; Owner: -
--

ALTER TABLE ONLY quality.upload_file
    ADD CONSTRAINT upload_file_pkey PRIMARY KEY (id);


--
-- Name: experiment_ai_source_evidence experiment_ai_source_evidence_organization_id_experiment_ve_key; Type: CONSTRAINT; Schema: rnd; Owner: -
--

ALTER TABLE ONLY rnd.experiment_ai_source_evidence
    ADD CONSTRAINT experiment_ai_source_evidence_organization_id_experiment_ve_key UNIQUE (organization_id, experiment_version_id, evidence_hash);


--
-- Name: experiment_ai_source_evidence experiment_ai_source_evidence_organization_id_id_key; Type: CONSTRAINT; Schema: rnd; Owner: -
--

ALTER TABLE ONLY rnd.experiment_ai_source_evidence
    ADD CONSTRAINT experiment_ai_source_evidence_organization_id_id_key UNIQUE (organization_id, id);


--
-- Name: experiment_ai_source_evidence experiment_ai_source_evidence_pkey; Type: CONSTRAINT; Schema: rnd; Owner: -
--

ALTER TABLE ONLY rnd.experiment_ai_source_evidence
    ADD CONSTRAINT experiment_ai_source_evidence_pkey PRIMARY KEY (id);


--
-- Name: experiment_attachment experiment_attachment_pkey; Type: CONSTRAINT; Schema: rnd; Owner: -
--

ALTER TABLE ONLY rnd.experiment_attachment
    ADD CONSTRAINT experiment_attachment_pkey PRIMARY KEY (id);


--
-- Name: experiment_audit experiment_audit_pkey; Type: CONSTRAINT; Schema: rnd; Owner: -
--

ALTER TABLE ONLY rnd.experiment_audit
    ADD CONSTRAINT experiment_audit_pkey PRIMARY KEY (id);


--
-- Name: experiment_category experiment_category_organization_id_code_key; Type: CONSTRAINT; Schema: rnd; Owner: -
--

ALTER TABLE ONLY rnd.experiment_category
    ADD CONSTRAINT experiment_category_organization_id_code_key UNIQUE (organization_id, code);


--
-- Name: experiment_category experiment_category_organization_id_name_key; Type: CONSTRAINT; Schema: rnd; Owner: -
--

ALTER TABLE ONLY rnd.experiment_category
    ADD CONSTRAINT experiment_category_organization_id_name_key UNIQUE (organization_id, name);


--
-- Name: experiment_category experiment_category_pkey; Type: CONSTRAINT; Schema: rnd; Owner: -
--

ALTER TABLE ONLY rnd.experiment_category
    ADD CONSTRAINT experiment_category_pkey PRIMARY KEY (id);


--
-- Name: experiment_import_job experiment_import_job_pkey; Type: CONSTRAINT; Schema: rnd; Owner: -
--

ALTER TABLE ONLY rnd.experiment_import_job
    ADD CONSTRAINT experiment_import_job_pkey PRIMARY KEY (id);


--
-- Name: experiment experiment_organization_id_experiment_no_key; Type: CONSTRAINT; Schema: rnd; Owner: -
--

ALTER TABLE ONLY rnd.experiment
    ADD CONSTRAINT experiment_organization_id_experiment_no_key UNIQUE (organization_id, experiment_no);


--
-- Name: experiment_outbox experiment_outbox_pkey; Type: CONSTRAINT; Schema: rnd; Owner: -
--

ALTER TABLE ONLY rnd.experiment_outbox
    ADD CONSTRAINT experiment_outbox_pkey PRIMARY KEY (id);


--
-- Name: experiment experiment_pkey; Type: CONSTRAINT; Schema: rnd; Owner: -
--

ALTER TABLE ONLY rnd.experiment
    ADD CONSTRAINT experiment_pkey PRIMARY KEY (id);


--
-- Name: experiment_report experiment_report_pkey; Type: CONSTRAINT; Schema: rnd; Owner: -
--

ALTER TABLE ONLY rnd.experiment_report
    ADD CONSTRAINT experiment_report_pkey PRIMARY KEY (id);


--
-- Name: experiment_review experiment_review_pkey; Type: CONSTRAINT; Schema: rnd; Owner: -
--

ALTER TABLE ONLY rnd.experiment_review
    ADD CONSTRAINT experiment_review_pkey PRIMARY KEY (id);


--
-- Name: experiment_source_reference experiment_source_reference_organization_id_experiment_vers_key; Type: CONSTRAINT; Schema: rnd; Owner: -
--

ALTER TABLE ONLY rnd.experiment_source_reference
    ADD CONSTRAINT experiment_source_reference_organization_id_experiment_vers_key UNIQUE (organization_id, experiment_version_id, recognition_job_id, sample_boundary_id);


--
-- Name: experiment_source_reference experiment_source_reference_organization_id_id_key; Type: CONSTRAINT; Schema: rnd; Owner: -
--

ALTER TABLE ONLY rnd.experiment_source_reference
    ADD CONSTRAINT experiment_source_reference_organization_id_id_key UNIQUE (organization_id, id);


--
-- Name: experiment_source_reference experiment_source_reference_pkey; Type: CONSTRAINT; Schema: rnd; Owner: -
--

ALTER TABLE ONLY rnd.experiment_source_reference
    ADD CONSTRAINT experiment_source_reference_pkey PRIMARY KEY (id);


--
-- Name: experiment_version experiment_version_experiment_id_version_no_key; Type: CONSTRAINT; Schema: rnd; Owner: -
--

ALTER TABLE ONLY rnd.experiment_version
    ADD CONSTRAINT experiment_version_experiment_id_version_no_key UNIQUE (experiment_id, version_no);


--
-- Name: experiment_version experiment_version_pkey; Type: CONSTRAINT; Schema: rnd; Owner: -
--

ALTER TABLE ONLY rnd.experiment_version
    ADD CONSTRAINT experiment_version_pkey PRIMARY KEY (id);


--
-- Name: research_test_audit research_test_audit_pkey; Type: CONSTRAINT; Schema: rnd; Owner: -
--

ALTER TABLE ONLY rnd.research_test_audit
    ADD CONSTRAINT research_test_audit_pkey PRIMARY KEY (id);


--
-- Name: research_test_record research_test_record_organization_id_record_type_business_n_key; Type: CONSTRAINT; Schema: rnd; Owner: -
--

ALTER TABLE ONLY rnd.research_test_record
    ADD CONSTRAINT research_test_record_organization_id_record_type_business_n_key UNIQUE (organization_id, record_type, business_no);


--
-- Name: research_test_record research_test_record_pkey; Type: CONSTRAINT; Schema: rnd; Owner: -
--

ALTER TABLE ONLY rnd.research_test_record
    ADD CONSTRAINT research_test_record_pkey PRIMARY KEY (id);


--
-- Name: research_test_review research_test_review_pkey; Type: CONSTRAINT; Schema: rnd; Owner: -
--

ALTER TABLE ONLY rnd.research_test_review
    ADD CONSTRAINT research_test_review_pkey PRIMARY KEY (id);


--
-- Name: research_test_upload research_test_upload_pkey; Type: CONSTRAINT; Schema: rnd; Owner: -
--

ALTER TABLE ONLY rnd.research_test_upload
    ADD CONSTRAINT research_test_upload_pkey PRIMARY KEY (id);


--
-- Name: research_test_version research_test_version_pkey; Type: CONSTRAINT; Schema: rnd; Owner: -
--

ALTER TABLE ONLY rnd.research_test_version
    ADD CONSTRAINT research_test_version_pkey PRIMARY KEY (id);


--
-- Name: research_test_version research_test_version_record_id_version_no_key; Type: CONSTRAINT; Schema: rnd; Owner: -
--

ALTER TABLE ONLY rnd.research_test_version
    ADD CONSTRAINT research_test_version_record_id_version_no_key UNIQUE (record_id, version_no);


--
-- Name: experiment_import_job uq_rnd_experiment_import_job_org_id; Type: CONSTRAINT; Schema: rnd; Owner: -
--

ALTER TABLE ONLY rnd.experiment_import_job
    ADD CONSTRAINT uq_rnd_experiment_import_job_org_id UNIQUE (organization_id, id);


--
-- Name: experiment uq_rnd_experiment_org_id; Type: CONSTRAINT; Schema: rnd; Owner: -
--

ALTER TABLE ONLY rnd.experiment
    ADD CONSTRAINT uq_rnd_experiment_org_id UNIQUE (organization_id, id);


--
-- Name: experiment_version uq_rnd_experiment_version_org_id; Type: CONSTRAINT; Schema: rnd; Owner: -
--

ALTER TABLE ONLY rnd.experiment_version
    ADD CONSTRAINT uq_rnd_experiment_version_org_id UNIQUE (organization_id, id);


--
-- Name: analysis_event analysis_event_pkey; Type: CONSTRAINT; Schema: spc; Owner: -
--

ALTER TABLE ONLY spc.analysis_event
    ADD CONSTRAINT analysis_event_pkey PRIMARY KEY (id);


--
-- Name: analysis_run analysis_run_pkey; Type: CONSTRAINT; Schema: spc; Owner: -
--

ALTER TABLE ONLY spc.analysis_run
    ADD CONSTRAINT analysis_run_pkey PRIMARY KEY (id);


--
-- Name: chart_asset chart_asset_organization_id_file_object_id_key; Type: CONSTRAINT; Schema: spc; Owner: -
--

ALTER TABLE ONLY spc.chart_asset
    ADD CONSTRAINT chart_asset_organization_id_file_object_id_key UNIQUE (organization_id, file_object_id);


--
-- Name: chart_asset chart_asset_pkey; Type: CONSTRAINT; Schema: spc; Owner: -
--

ALTER TABLE ONLY spc.chart_asset
    ADD CONSTRAINT chart_asset_pkey PRIMARY KEY (id);


--
-- Name: chart_category chart_category_organization_id_code_key; Type: CONSTRAINT; Schema: spc; Owner: -
--

ALTER TABLE ONLY spc.chart_category
    ADD CONSTRAINT chart_category_organization_id_code_key UNIQUE (organization_id, code);


--
-- Name: chart_category chart_category_pkey; Type: CONSTRAINT; Schema: spc; Owner: -
--

ALTER TABLE ONLY spc.chart_category
    ADD CONSTRAINT chart_category_pkey PRIMARY KEY (id);


--
-- Name: chart_upload_attempt chart_upload_attempt_organization_id_request_id_key; Type: CONSTRAINT; Schema: spc; Owner: -
--

ALTER TABLE ONLY spc.chart_upload_attempt
    ADD CONSTRAINT chart_upload_attempt_organization_id_request_id_key UNIQUE (organization_id, request_id);


--
-- Name: chart_upload_attempt chart_upload_attempt_pkey; Type: CONSTRAINT; Schema: spc; Owner: -
--

ALTER TABLE ONLY spc.chart_upload_attempt
    ADD CONSTRAINT chart_upload_attempt_pkey PRIMARY KEY (id);


--
-- Name: chat_message chat_message_pkey; Type: CONSTRAINT; Schema: spc; Owner: -
--

ALTER TABLE ONLY spc.chat_message
    ADD CONSTRAINT chat_message_pkey PRIMARY KEY (id);


--
-- Name: chat_session chat_session_pkey; Type: CONSTRAINT; Schema: spc; Owner: -
--

ALTER TABLE ONLY spc.chat_session
    ADD CONSTRAINT chat_session_pkey PRIMARY KEY (id);


--
-- Name: recognition_call recognition_call_pkey; Type: CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.recognition_call
    ADD CONSTRAINT recognition_call_pkey PRIMARY KEY (id);


--
-- Name: recognition_run recognition_run_pkey; Type: CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.recognition_run
    ADD CONSTRAINT recognition_run_pkey PRIMARY KEY (id);


--
-- Name: recognition_suggestion recognition_suggestion_pkey; Type: CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.recognition_suggestion
    ADD CONSTRAINT recognition_suggestion_pkey PRIMARY KEY (id);


--
-- Name: recognition_trace recognition_trace_pkey; Type: CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.recognition_trace
    ADD CONSTRAINT recognition_trace_pkey PRIMARY KEY (id);


--
-- Name: record_attachment record_attachment_pkey; Type: CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.record_attachment
    ADD CONSTRAINT record_attachment_pkey PRIMARY KEY (id);


--
-- Name: record_collection_item record_collection_item_pkey; Type: CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.record_collection_item
    ADD CONSTRAINT record_collection_item_pkey PRIMARY KEY (id);


--
-- Name: record_collection_item record_collection_item_revision_id_parent_data_path_record__key; Type: CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.record_collection_item
    ADD CONSTRAINT record_collection_item_revision_id_parent_data_path_record__key UNIQUE (revision_id, parent_data_path, record_key);


--
-- Name: record_revision record_revision_pkey; Type: CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.record_revision
    ADD CONSTRAINT record_revision_pkey PRIMARY KEY (id);


--
-- Name: record_revision record_revision_production_order_id_revision_no_key; Type: CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.record_revision
    ADD CONSTRAINT record_revision_production_order_id_revision_no_key UNIQUE (production_order_id, revision_no);


--
-- Name: record_value_index record_value_index_pkey; Type: CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.record_value_index
    ADD CONSTRAINT record_value_index_pkey PRIMARY KEY (id);


--
-- Name: record_value_index record_value_index_revision_id_data_path_key; Type: CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.record_value_index
    ADD CONSTRAINT record_value_index_revision_id_data_path_key UNIQUE (revision_id, data_path);


--
-- Name: standard_field_alias standard_field_alias_dictionary_id_normalized_alias_key; Type: CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.standard_field_alias
    ADD CONSTRAINT standard_field_alias_dictionary_id_normalized_alias_key UNIQUE (dictionary_id, normalized_alias);


--
-- Name: standard_field_alias standard_field_alias_pkey; Type: CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.standard_field_alias
    ADD CONSTRAINT standard_field_alias_pkey PRIMARY KEY (id);


--
-- Name: standard_field_catalog_import standard_field_catalog_import_catalog_version_key; Type: CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.standard_field_catalog_import
    ADD CONSTRAINT standard_field_catalog_import_catalog_version_key UNIQUE (catalog_version);


--
-- Name: standard_field_catalog_import standard_field_catalog_import_pkey; Type: CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.standard_field_catalog_import
    ADD CONSTRAINT standard_field_catalog_import_pkey PRIMARY KEY (id);


--
-- Name: standard_field_catalog_import standard_field_catalog_import_source_sha256_key; Type: CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.standard_field_catalog_import
    ADD CONSTRAINT standard_field_catalog_import_source_sha256_key UNIQUE (source_sha256);


--
-- Name: standard_field_catalog_proposal standard_field_catalog_proposal_import_id_semantic_key_key; Type: CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.standard_field_catalog_proposal
    ADD CONSTRAINT standard_field_catalog_proposal_import_id_semantic_key_key UNIQUE (import_id, semantic_key);


--
-- Name: standard_field_catalog_proposal standard_field_catalog_proposal_pkey; Type: CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.standard_field_catalog_proposal
    ADD CONSTRAINT standard_field_catalog_proposal_pkey PRIMARY KEY (id);


--
-- Name: standard_field_dictionary standard_field_dictionary_dictionary_code_version_no_key; Type: CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.standard_field_dictionary
    ADD CONSTRAINT standard_field_dictionary_dictionary_code_version_no_key UNIQUE (dictionary_code, version_no);


--
-- Name: standard_field_dictionary standard_field_dictionary_pkey; Type: CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.standard_field_dictionary
    ADD CONSTRAINT standard_field_dictionary_pkey PRIMARY KEY (id);


--
-- Name: standard_field_request standard_field_request_pkey; Type: CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.standard_field_request
    ADD CONSTRAINT standard_field_request_pkey PRIMARY KEY (id);


--
-- Name: template_category template_category_organization_id_name_key; Type: CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.template_category
    ADD CONSTRAINT template_category_organization_id_name_key UNIQUE (organization_id, name);


--
-- Name: template_category template_category_pkey; Type: CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.template_category
    ADD CONSTRAINT template_category_pkey PRIMARY KEY (id);


--
-- Name: template_import_contract template_import_contract_pkey; Type: CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.template_import_contract
    ADD CONSTRAINT template_import_contract_pkey PRIMARY KEY (template_version_id);


--
-- Name: template_import_issue template_import_issue_pkey; Type: CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.template_import_issue
    ADD CONSTRAINT template_import_issue_pkey PRIMARY KEY (id);


--
-- Name: template_import_job template_import_job_pkey; Type: CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.template_import_job
    ADD CONSTRAINT template_import_job_pkey PRIMARY KEY (id);


--
-- Name: template_mapping template_mapping_pkey; Type: CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.template_mapping
    ADD CONSTRAINT template_mapping_pkey PRIMARY KEY (id);


--
-- Name: template_mapping template_mapping_template_version_id_binding_id_key; Type: CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.template_mapping
    ADD CONSTRAINT template_mapping_template_version_id_binding_id_key UNIQUE (template_version_id, binding_id);


--
-- Name: template template_organization_id_template_code_key; Type: CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.template
    ADD CONSTRAINT template_organization_id_template_code_key UNIQUE (organization_id, template_code);


--
-- Name: template template_pkey; Type: CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.template
    ADD CONSTRAINT template_pkey PRIMARY KEY (id);


--
-- Name: template_quality_issue template_quality_issue_pkey; Type: CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.template_quality_issue
    ADD CONSTRAINT template_quality_issue_pkey PRIMARY KEY (id);


--
-- Name: template_static_region template_static_region_pkey; Type: CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.template_static_region
    ADD CONSTRAINT template_static_region_pkey PRIMARY KEY (id);


--
-- Name: template_static_region template_static_region_template_version_id_sheet_id_address_key; Type: CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.template_static_region
    ADD CONSTRAINT template_static_region_template_version_id_sheet_id_address_key UNIQUE (template_version_id, sheet_id, address);


--
-- Name: template_structure_change template_structure_change_pkey; Type: CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.template_structure_change
    ADD CONSTRAINT template_structure_change_pkey PRIMARY KEY (id);


--
-- Name: template_version template_version_pkey; Type: CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.template_version
    ADD CONSTRAINT template_version_pkey PRIMARY KEY (id);


--
-- Name: template_version_review_event template_version_review_event_pkey; Type: CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.template_version_review_event
    ADD CONSTRAINT template_version_review_event_pkey PRIMARY KEY (id);


--
-- Name: template_version_review template_version_review_pkey; Type: CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.template_version_review
    ADD CONSTRAINT template_version_review_pkey PRIMARY KEY (version_id);


--
-- Name: template_version template_version_template_id_version_no_key; Type: CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.template_version
    ADD CONSTRAINT template_version_template_id_version_no_key UNIQUE (template_id, version_no);


--
-- Name: unified_lab_template_package unified_lab_template_package_pkey; Type: CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.unified_lab_template_package
    ADD CONSTRAINT unified_lab_template_package_pkey PRIMARY KEY (template_code);


--
-- Name: idx_ai_call_audit_org; Type: INDEX; Schema: ai; Owner: -
--

CREATE INDEX idx_ai_call_audit_org ON ai.ai_call_audit USING btree (organization_id, created_at DESC);


--
-- Name: idx_ai_context_snapshot_org_created; Type: INDEX; Schema: ai; Owner: -
--

CREATE INDEX idx_ai_context_snapshot_org_created ON ai.context_snapshot USING btree (organization_id, created_at DESC);


--
-- Name: idx_ai_conversation_type_updated; Type: INDEX; Schema: ai; Owner: -
--

CREATE INDEX idx_ai_conversation_type_updated ON ai.assistant_conversation USING btree (organization_id, conversation_type, updated_at DESC);


--
-- Name: idx_ai_conversation_updated; Type: INDEX; Schema: ai; Owner: -
--

CREATE INDEX idx_ai_conversation_updated ON ai.assistant_conversation USING btree (organization_id, updated_at DESC);


--
-- Name: idx_ai_message_conversation; Type: INDEX; Schema: ai; Owner: -
--

CREATE INDEX idx_ai_message_conversation ON ai.assistant_message USING btree (conversation_id, created_at);


--
-- Name: idx_ai_result_evidence_current; Type: INDEX; Schema: ai; Owner: -
--

CREATE INDEX idx_ai_result_evidence_current ON ai.result_evidence USING btree (organization_id, result_id, is_current);


--
-- Name: idx_ai_result_evidence_result; Type: INDEX; Schema: ai; Owner: -
--

CREATE INDEX idx_ai_result_evidence_result ON ai.result_evidence USING btree (organization_id, result_id);


--
-- Name: idx_ai_result_feedback_result; Type: INDEX; Schema: ai; Owner: -
--

CREATE INDEX idx_ai_result_feedback_result ON ai.result_feedback USING btree (organization_id, result_id, created_at DESC);


--
-- Name: idx_ai_result_occurrence_result; Type: INDEX; Schema: ai; Owner: -
--

CREATE INDEX idx_ai_result_occurrence_result ON ai.result_occurrence USING btree (organization_id, result_id, detected_at DESC);


--
-- Name: idx_ai_result_org_status; Type: INDEX; Schema: ai; Owner: -
--

CREATE INDEX idx_ai_result_org_status ON ai.result USING btree (organization_id, status, severity, last_detected_at DESC);


--
-- Name: idx_ai_result_org_subject; Type: INDEX; Schema: ai; Owner: -
--

CREATE INDEX idx_ai_result_org_subject ON ai.result USING btree (organization_id, subject_type, subject_id, updated_at DESC);


--
-- Name: idx_ai_rule_definition_enabled; Type: INDEX; Schema: ai; Owner: -
--

CREATE INDEX idx_ai_rule_definition_enabled ON ai.rule_definition USING btree (enabled, business_object);


--
-- Name: idx_ai_rule_definition_org_enabled; Type: INDEX; Schema: ai; Owner: -
--

CREATE INDEX idx_ai_rule_definition_org_enabled ON ai.rule_definition USING btree (organization_id, enabled, business_object);


--
-- Name: idx_ai_rule_version_org_lookup; Type: INDEX; Schema: ai; Owner: -
--

CREATE INDEX idx_ai_rule_version_org_lookup ON ai.rule_version USING btree (organization_id, rule_code, status, enabled, effective_from DESC, version_no DESC);


--
-- Name: idx_ai_signal_org_subject; Type: INDEX; Schema: ai; Owner: -
--

CREATE INDEX idx_ai_signal_org_subject ON ai.signal USING btree (organization_id, subject_type, subject_id, occurred_at DESC);


--
-- Name: idx_configuration_receipt_created; Type: INDEX; Schema: ai; Owner: -
--

CREATE INDEX idx_configuration_receipt_created ON ai.configuration_command_receipt USING btree (organization_id, created_at DESC, id);


--
-- Name: idx_data_review_queue; Type: INDEX; Schema: ai; Owner: -
--

CREATE INDEX idx_data_review_queue ON ai.data_review USING btree (organization_id, status, priority, created_at, id);


--
-- Name: idx_eligibility_funnel; Type: INDEX; Schema: ai; Owner: -
--

CREATE INDEX idx_eligibility_funnel ON ai.training_eligibility USING btree (organization_id, target_version_id, input_scheme_id, state, evaluated_at DESC, id);


--
-- Name: idx_eligibility_run_status; Type: INDEX; Schema: ai; Owner: -
--

CREATE INDEX idx_eligibility_run_status ON ai.eligibility_evaluation_run USING btree (organization_id, status, updated_at DESC, id);


--
-- Name: idx_eligibility_run_target; Type: INDEX; Schema: ai; Owner: -
--

CREATE INDEX idx_eligibility_run_target ON ai.eligibility_evaluation_run USING btree (organization_id, target_id, created_at DESC, id);


--
-- Name: idx_employee_scheduled_task_due; Type: INDEX; Schema: ai; Owner: -
--

CREATE INDEX idx_employee_scheduled_task_due ON ai.employee_scheduled_task USING btree (next_run_at) WHERE enabled;


--
-- Name: idx_experiment_analysis_org_created; Type: INDEX; Schema: ai; Owner: -
--

CREATE INDEX idx_experiment_analysis_org_created ON ai.experiment_analysis USING btree (organization_id, created_at DESC);


--
-- Name: idx_experiment_analysis_primary; Type: INDEX; Schema: ai; Owner: -
--

CREATE INDEX idx_experiment_analysis_primary ON ai.experiment_analysis USING btree (organization_id, primary_experiment_id, created_at DESC);


--
-- Name: idx_fact_projection_recovery; Type: INDEX; Schema: ai; Owner: -
--

CREATE INDEX idx_fact_projection_recovery ON ai.fact_projection_receipt USING btree (organization_id, status, updated_at, id) WHERE ((status)::text = ANY (ARRAY[('PROCESSING'::character varying)::text, ('FAILED'::character varying)::text]));


--
-- Name: idx_input_field_catalog; Type: INDEX; Schema: ai; Owner: -
--

CREATE INDEX idx_input_field_catalog ON ai.input_field USING btree (organization_id, availability_stage, field_code);


--
-- Name: idx_input_field_list; Type: INDEX; Schema: ai; Owner: -
--

CREATE INDEX idx_input_field_list ON ai.input_field USING btree (organization_id, updated_at DESC, id);


--
-- Name: idx_input_scheme_material_dictionary; Type: INDEX; Schema: ai; Owner: -
--

CREATE INDEX idx_input_scheme_material_dictionary ON ai.input_scheme USING btree (organization_id, material_dictionary_version_id) WHERE (material_dictionary_version_id IS NOT NULL);


--
-- Name: idx_input_scheme_target; Type: INDEX; Schema: ai; Owner: -
--

CREATE INDEX idx_input_scheme_target ON ai.input_scheme USING btree (organization_id, target_id, created_at DESC, id);


--
-- Name: idx_model_feedback_experiment_history; Type: INDEX; Schema: ai; Owner: -
--

CREATE INDEX idx_model_feedback_experiment_history ON ai.model_feedback USING btree (organization_id, experiment_id, experiment_version_id, created_at, id);


--
-- Name: idx_model_feedback_projection_recovery; Type: INDEX; Schema: ai; Owner: -
--

CREATE INDEX idx_model_feedback_projection_recovery ON ai.model_feedback_projection_receipt USING btree (organization_id, status, updated_at, id) WHERE ((status)::text = ANY (ARRAY[('PROCESSING'::character varying)::text, ('FAILED'::character varying)::text]));


--
-- Name: idx_model_feedback_target; Type: INDEX; Schema: ai; Owner: -
--

CREATE INDEX idx_model_feedback_target ON ai.model_feedback USING btree (organization_id, target_id, created_at DESC, id);


--
-- Name: idx_model_release_history; Type: INDEX; Schema: ai; Owner: -
--

CREATE INDEX idx_model_release_history ON ai.model_release USING btree (organization_id, target_id, created_at DESC, id);


--
-- Name: idx_model_version_target_history; Type: INDEX; Schema: ai; Owner: -
--

CREATE INDEX idx_model_version_target_history ON ai.model_version USING btree (organization_id, target_id, created_at DESC, id);


--
-- Name: idx_modeling_policy_kind_history; Type: INDEX; Schema: ai; Owner: -
--

CREATE INDEX idx_modeling_policy_kind_history ON ai.modeling_policy_version USING btree (organization_id, target_id, kind, version_no DESC, id);


--
-- Name: idx_modeling_workbench_run_history; Type: INDEX; Schema: ai; Owner: -
--

CREATE INDEX idx_modeling_workbench_run_history ON ai.modeling_workbench_run USING btree (organization_id, created_by, created_at DESC, id);


--
-- Name: idx_prediction_record_history; Type: INDEX; Schema: ai; Owner: -
--

CREATE INDEX idx_prediction_record_history ON ai.prediction_record USING btree (organization_id, created_at DESC, id);


--
-- Name: idx_prediction_record_owner_history; Type: INDEX; Schema: ai; Owner: -
--

CREATE INDEX idx_prediction_record_owner_history ON ai.prediction_record USING btree (organization_id, created_by, created_at DESC, id);


--
-- Name: idx_prediction_record_running_reconcile; Type: INDEX; Schema: ai; Owner: -
--

CREATE INDEX idx_prediction_record_running_reconcile ON ai.prediction_record USING btree (heartbeat_at, id) WHERE ((execution_status)::text = 'RUNNING'::text);


--
-- Name: idx_prediction_target_list; Type: INDEX; Schema: ai; Owner: -
--

CREATE INDEX idx_prediction_target_list ON ai.prediction_target USING btree (organization_id, updated_at DESC, id);


--
-- Name: idx_prediction_target_project; Type: INDEX; Schema: ai; Owner: -
--

CREATE INDEX idx_prediction_target_project ON ai.prediction_target USING btree (organization_id, performance_project, target_code);


--
-- Name: idx_research_candidate_history_observation_run; Type: INDEX; Schema: ai; Owner: -
--

CREATE INDEX idx_research_candidate_history_observation_run ON ai.research_candidate_history_observation_v2 USING btree (organization_id, research_run_id, research_candidate_id);


--
-- Name: idx_research_candidate_observation_run; Type: INDEX; Schema: ai; Owner: -
--

CREATE INDEX idx_research_candidate_observation_run ON ai.research_candidate_observation_v2 USING btree (organization_id, research_run_id, research_candidate_id, target_id);


--
-- Name: idx_research_candidate_v2_score; Type: INDEX; Schema: ai; Owner: -
--

CREATE INDEX idx_research_candidate_v2_score ON ai.research_candidate_v2 USING btree (organization_id, research_run_id, score DESC NULLS LAST, candidate_no);


--
-- Name: idx_research_experiment_link_v2; Type: INDEX; Schema: ai; Owner: -
--

CREATE INDEX idx_research_experiment_link_v2 ON ai.research_experiment_link_v2 USING btree (organization_id, experiment_id, created_at DESC);


--
-- Name: idx_research_experiment_link_v2_candidate; Type: INDEX; Schema: ai; Owner: -
--

CREATE INDEX idx_research_experiment_link_v2_candidate ON ai.research_experiment_link_v2 USING btree (organization_id, research_candidate_id, created_at DESC, id);


--
-- Name: idx_research_run_v2_active; Type: INDEX; Schema: ai; Owner: -
--

CREATE INDEX idx_research_run_v2_active ON ai.research_run_v2 USING btree (organization_id, run_type, execution_status, created_at DESC) WHERE ((execution_status)::text = ANY (ARRAY[('QUEUED'::character varying)::text, ('RUNNING'::character varying)::text]));


--
-- Name: idx_research_run_v2_baseline_history; Type: INDEX; Schema: ai; Owner: -
--

CREATE INDEX idx_research_run_v2_baseline_history ON ai.research_run_v2 USING btree (organization_id, baseline_type, baseline_entity_id, created_at DESC, id) WHERE (baseline_type IS NOT NULL);


--
-- Name: idx_research_run_v2_history; Type: INDEX; Schema: ai; Owner: -
--

CREATE INDEX idx_research_run_v2_history ON ai.research_run_v2 USING btree (organization_id, created_at DESC, id);


--
-- Name: idx_research_run_v2_owner_history; Type: INDEX; Schema: ai; Owner: -
--

CREATE INDEX idx_research_run_v2_owner_history ON ai.research_run_v2 USING btree (organization_id, created_by, created_at DESC, id);


--
-- Name: idx_sample_revision_sample; Type: INDEX; Schema: ai; Owner: -
--

CREATE INDEX idx_sample_revision_sample ON ai.sample_revision USING btree (organization_id, training_sample_id, revision_no DESC);


--
-- Name: idx_source_mapping_target_version; Type: INDEX; Schema: ai; Owner: -
--

CREATE INDEX idx_source_mapping_target_version ON ai.source_mapping_version USING btree (organization_id, target_version_id, source_type, version_no DESC, id);


--
-- Name: idx_target_version_result_field; Type: INDEX; Schema: ai; Owner: -
--

CREATE INDEX idx_target_version_result_field ON ai.target_version USING btree (organization_id, result_standard_field_dictionary_id) WHERE (result_standard_field_dictionary_id IS NOT NULL);


--
-- Name: idx_training_eligibility_reason; Type: INDEX; Schema: ai; Owner: -
--

CREATE INDEX idx_training_eligibility_reason ON ai.training_eligibility USING btree (organization_id, target_version_id, input_scheme_id, state, evaluated_at DESC, id);


--
-- Name: idx_training_intent_pending; Type: INDEX; Schema: ai; Owner: -
--

CREATE INDEX idx_training_intent_pending ON ai.training_schedule_intent USING btree (organization_id, updated_at, target_id) WHERE ((status)::text = 'PENDING'::text);


--
-- Name: idx_training_job_claim; Type: INDEX; Schema: ai; Owner: -
--

CREATE INDEX idx_training_job_claim ON ai.training_job USING btree (priority, next_attempt_at, created_at, id) WHERE ((status)::text = ANY (ARRAY[('QUEUED'::character varying)::text, ('RUNNING'::character varying)::text]));


--
-- Name: idx_training_job_history; Type: INDEX; Schema: ai; Owner: -
--

CREATE INDEX idx_training_job_history ON ai.training_job USING btree (organization_id, created_at DESC, id);


--
-- Name: idx_training_snapshot_history; Type: INDEX; Schema: ai; Owner: -
--

CREATE INDEX idx_training_snapshot_history ON ai.training_snapshot USING btree (organization_id, frozen_at DESC, id);


--
-- Name: operation_manual_section; Type: INDEX; Schema: ai; Owner: -
--

CREATE INDEX operation_manual_section ON ai.operation_manual_chunk USING btree (manual_version, embedding_model, embedding_dimension, section_id);


--
-- Name: operation_manual_vector_reuse; Type: INDEX; Schema: ai; Owner: -
--

CREATE INDEX operation_manual_vector_reuse ON ai.operation_manual_chunk USING btree (embedding_model, embedding_dimension, content_hash);


--
-- Name: uq_ai_rule_version_one_open_published; Type: INDEX; Schema: ai; Owner: -
--

CREATE UNIQUE INDEX uq_ai_rule_version_one_open_published ON ai.rule_version USING btree (organization_id, rule_code) WHERE (((status)::text = 'PUBLISHED'::text) AND (enabled = true) AND (effective_to IS NULL));


--
-- Name: uq_artifact_content_kind; Type: INDEX; Schema: ai; Owner: -
--

CREATE UNIQUE INDEX uq_artifact_content_kind ON ai.artifact USING btree (organization_id, sha256, artifact_type);


--
-- Name: uq_data_review_decision_idempotency; Type: INDEX; Schema: ai; Owner: -
--

CREATE UNIQUE INDEX uq_data_review_decision_idempotency ON ai.data_review_decision USING btree (organization_id, data_review_id, idempotency_key) WHERE (idempotency_key IS NOT NULL);


--
-- Name: uq_data_review_decision_no; Type: INDEX; Schema: ai; Owner: -
--

CREATE UNIQUE INDEX uq_data_review_decision_no ON ai.data_review_decision USING btree (organization_id, data_review_id, decision_no) WHERE (decision_no IS NOT NULL);


--
-- Name: uq_data_review_open_reason; Type: INDEX; Schema: ai; Owner: -
--

CREATE UNIQUE INDEX uq_data_review_open_reason ON ai.data_review USING btree (organization_id, COALESCE(eligibility_id, identity_issue_id), review_type, COALESCE(reason_code, ''::character varying)) WHERE ((status)::text = ANY (ARRAY[('OPEN'::character varying)::text, ('IN_REVIEW'::character varying)::text]));


--
-- Name: uq_model_version_active_target; Type: INDEX; Schema: ai; Owner: -
--

CREATE UNIQUE INDEX uq_model_version_active_target ON ai.model_version USING btree (organization_id, target_id) WHERE ((status)::text = 'ACTIVE'::text);


--
-- Name: uq_model_version_number; Type: INDEX; Schema: ai; Owner: -
--

CREATE UNIQUE INDEX uq_model_version_number ON ai.model_version USING btree (organization_id, target_id, version_no) WHERE (version_no IS NOT NULL);


--
-- Name: uq_model_workbench_run; Type: INDEX; Schema: ai; Owner: -
--

CREATE UNIQUE INDEX uq_model_workbench_run ON ai.model_version USING btree (organization_id, workbench_run_id) WHERE (workbench_run_id IS NOT NULL);


--
-- Name: uq_research_run_v2_active_optimization_org; Type: INDEX; Schema: ai; Owner: -
--

CREATE UNIQUE INDEX uq_research_run_v2_active_optimization_org ON ai.research_run_v2 USING btree (organization_id) WHERE (((run_type)::text = 'EXPERIMENT_OPTIMIZATION'::text) AND ((execution_status)::text = ANY (ARRAY[('QUEUED'::character varying)::text, ('RUNNING'::character varying)::text])));


--
-- Name: uq_research_run_v2_ops_job; Type: INDEX; Schema: ai; Owner: -
--

CREATE UNIQUE INDEX uq_research_run_v2_ops_job ON ai.research_run_v2 USING btree (organization_id, ops_job_id) WHERE (ops_job_id IS NOT NULL);


--
-- Name: uq_sample_identity_issue_open; Type: INDEX; Schema: ai; Owner: -
--

CREATE UNIQUE INDEX uq_sample_identity_issue_open ON ai.sample_identity_issue USING btree (organization_id, LEAST(left_sample_source_id, right_sample_source_id), GREATEST(left_sample_source_id, right_sample_source_id), issue_type) WHERE ((status)::text = 'OPEN'::text);


--
-- Name: uq_sample_source_current; Type: INDEX; Schema: ai; Owner: -
--

CREATE UNIQUE INDEX uq_sample_source_current ON ai.sample_source USING btree (organization_id, training_sample_id, source_type) WHERE ((status)::text = 'CURRENT'::text);


--
-- Name: uq_training_eligibility_config_sample; Type: INDEX; Schema: ai; Owner: -
--

CREATE UNIQUE INDEX uq_training_eligibility_config_sample ON ai.training_eligibility USING btree (organization_id, sample_revision_id, target_version_id, input_scheme_id, COALESCE(source_mapping_version_id, '00000000-0000-0000-0000-000000000000'::uuid), COALESCE(material_dictionary_version_id, '00000000-0000-0000-0000-000000000000'::uuid), COALESCE(modeling_policy_version_id, '00000000-0000-0000-0000-000000000000'::uuid));


--
-- Name: uq_training_job_active_target; Type: INDEX; Schema: ai; Owner: -
--

CREATE UNIQUE INDEX uq_training_job_active_target ON ai.training_job USING btree (organization_id, target_id) WHERE ((target_id IS NOT NULL) AND ((status)::text = ANY (ARRAY[('QUEUED'::character varying)::text, ('MATERIALIZING'::character varying)::text, ('SNAPSHOT_VALIDATING'::character varying)::text, ('FOLDING'::character varying)::text, ('TRAINING'::character varying)::text, ('VALIDATING'::character varying)::text])));


--
-- Name: uq_training_job_business_key; Type: INDEX; Schema: ai; Owner: -
--

CREATE UNIQUE INDEX uq_training_job_business_key ON ai.training_job USING btree (organization_id, business_key) WHERE (business_key IS NOT NULL);


--
-- Name: uq_training_job_running_organization; Type: INDEX; Schema: ai; Owner: -
--

CREATE UNIQUE INDEX uq_training_job_running_organization ON ai.training_job USING btree (organization_id) WHERE ((status)::text = ANY (ARRAY[('MATERIALIZING'::character varying)::text, ('SNAPSHOT_VALIDATING'::character varying)::text, ('FOLDING'::character varying)::text, ('TRAINING'::character varying)::text, ('VALIDATING'::character varying)::text]));


--
-- Name: uq_training_snapshot_business_fingerprint; Type: INDEX; Schema: ai; Owner: -
--

CREATE UNIQUE INDEX uq_training_snapshot_business_fingerprint ON ai.training_snapshot USING btree (organization_id, business_fingerprint) WHERE (business_fingerprint IS NOT NULL);


--
-- Name: idx_core_project_reference_project; Type: INDEX; Schema: core; Owner: -
--

CREATE INDEX idx_core_project_reference_project ON core.project_reference USING btree (organization_id, project_id, status, added_at DESC);


--
-- Name: idx_core_project_reference_resource; Type: INDEX; Schema: core; Owner: -
--

CREATE INDEX idx_core_project_reference_resource ON core.project_reference USING btree (organization_id, resource_type, resource_id);


--
-- Name: idx_core_project_resource_link_project; Type: INDEX; Schema: core; Owner: -
--

CREATE INDEX idx_core_project_resource_link_project ON core.project_resource_link USING btree (organization_id, project_id, resource_type, resource_id);


--
-- Name: idx_core_project_resource_link_resource; Type: INDEX; Schema: core; Owner: -
--

CREATE INDEX idx_core_project_resource_link_resource ON core.project_resource_link USING btree (organization_id, resource_type, resource_id);


--
-- Name: idx_data_category_list; Type: INDEX; Schema: data; Owner: -
--

CREATE INDEX idx_data_category_list ON data.data_category USING btree (organization_id, sort_order, created_at);


--
-- Name: idx_data_import_experiment_link_structured; Type: INDEX; Schema: data; Owner: -
--

CREATE INDEX idx_data_import_experiment_link_structured ON data.import_experiment_link USING btree (organization_id, import_job_id, status, updated_at DESC) WHERE (experiment_id IS NOT NULL);


--
-- Name: idx_data_import_issue_job; Type: INDEX; Schema: data; Owner: -
--

CREATE INDEX idx_data_import_issue_job ON data.import_issue USING btree (import_job_id, severity, status, source_row_number);


--
-- Name: idx_data_import_job_attempt_recent; Type: INDEX; Schema: data; Owner: -
--

CREATE INDEX idx_data_import_job_attempt_recent ON data.import_job_attempt USING btree (organization_id, import_job_id, created_at DESC);


--
-- Name: idx_data_import_job_duplicate; Type: INDEX; Schema: data; Owner: -
--

CREATE INDEX idx_data_import_job_duplicate ON data.import_job USING btree (organization_id, source_sha256, template_version_id, status);


--
-- Name: idx_data_import_job_list; Type: INDEX; Schema: data; Owner: -
--

CREATE INDEX idx_data_import_job_list ON data.import_job USING btree (organization_id, created_at DESC);


--
-- Name: idx_data_import_job_owner_list; Type: INDEX; Schema: data; Owner: -
--

CREATE INDEX idx_data_import_job_owner_list ON data.import_job USING btree (organization_id, source_owner, created_at DESC, id);


--
-- Name: idx_data_import_job_recognition_status; Type: INDEX; Schema: data; Owner: -
--

CREATE INDEX idx_data_import_job_recognition_status ON data.import_job USING btree (organization_id, source_owner, status, updated_at DESC, id);


--
-- Name: idx_data_import_job_source_name_trgm; Type: INDEX; Schema: data; Owner: -
--

CREATE INDEX idx_data_import_job_source_name_trgm ON data.import_job USING gin (source_file_name public.gin_trgm_ops) WHERE (((status)::text = 'COMPLETED'::text) AND (((source_owner)::text = 'DATA_CENTER'::text) OR (((source_owner)::text = 'EXPERIMENT'::text) AND ((recognition_mode)::text = 'TEMPLATE_GUIDED'::text))));


--
-- Name: idx_data_import_job_structured_owner; Type: INDEX; Schema: data; Owner: -
--

CREATE INDEX idx_data_import_job_structured_owner ON data.import_job USING btree (organization_id, source_owner, recognition_mode, status, updated_at DESC, id);


--
-- Name: idx_data_import_job_visible_owner_created; Type: INDEX; Schema: data; Owner: -
--

CREATE INDEX idx_data_import_job_visible_owner_created ON data.import_job USING btree (organization_id, source_owner, created_at DESC) WHERE (hidden_at IS NULL);


--
-- Name: idx_data_import_mapping_field_name_trgm; Type: INDEX; Schema: data; Owner: -
--

CREATE INDEX idx_data_import_mapping_field_name_trgm ON data.import_mapping USING gin (field_name public.gin_trgm_ops);


--
-- Name: idx_data_import_mapping_source_header_trgm; Type: INDEX; Schema: data; Owner: -
--

CREATE INDEX idx_data_import_mapping_source_header_trgm ON data.import_mapping USING gin (source_header public.gin_trgm_ops);


--
-- Name: idx_data_publication_event_job; Type: INDEX; Schema: data; Owner: -
--

CREATE INDEX idx_data_publication_event_job ON data.publication_event USING btree (organization_id, import_job_id, created_at);


--
-- Name: idx_data_published_mapping_job; Type: INDEX; Schema: data; Owner: -
--

CREATE INDEX idx_data_published_mapping_job ON data.published_mapping USING btree (import_job_id);


--
-- Name: idx_data_record_job; Type: INDEX; Schema: data; Owner: -
--

CREATE INDEX idx_data_record_job ON data.data_record USING btree (organization_id, import_job_id, record_index);


--
-- Name: idx_data_record_key_trgm; Type: INDEX; Schema: data; Owner: -
--

CREATE INDEX idx_data_record_key_trgm ON data.data_record USING gin (record_key public.gin_trgm_ops) WHERE ((quality_status)::text <> 'BLOCKED'::text);


--
-- Name: idx_data_source_anchor_binding; Type: INDEX; Schema: data; Owner: -
--

CREATE INDEX idx_data_source_anchor_binding ON data.source_anchor USING btree (record_id, binding_id);


--
-- Name: idx_data_source_anchor_record; Type: INDEX; Schema: data; Owner: -
--

CREATE INDEX idx_data_source_anchor_record ON data.source_anchor USING btree (record_id, field_code);


--
-- Name: idx_data_staging_row_job; Type: INDEX; Schema: data; Owner: -
--

CREATE INDEX idx_data_staging_row_job ON data.staging_row USING btree (import_job_id, import_sheet_id, source_row_number);


--
-- Name: idx_data_staging_source_search; Type: INDEX; Schema: data; Owner: -
--

CREATE INDEX idx_data_staging_source_search ON data.staging_row USING gin (source_search_vector);


--
-- Name: idx_data_value_rag_text_trgm; Type: INDEX; Schema: data; Owner: -
--

CREATE INDEX idx_data_value_rag_text_trgm ON data.data_value USING gin (value_text public.gin_trgm_ops) WHERE (rag_eligible = true);


--
-- Name: idx_data_value_search; Type: INDEX; Schema: data; Owner: -
--

CREATE INDEX idx_data_value_search ON data.data_value USING btree (organization_id, field_code, training_eligible);


--
-- Name: idx_import_component_override_job; Type: INDEX; Schema: data; Owner: -
--

CREATE INDEX idx_import_component_override_job ON data.import_component_override USING btree (organization_id, import_job_id);


--
-- Name: idx_import_experiment_link_experiment; Type: INDEX; Schema: data; Owner: -
--

CREATE INDEX idx_import_experiment_link_experiment ON data.import_experiment_link USING btree (organization_id, experiment_id) WHERE (experiment_id IS NOT NULL);


--
-- Name: idx_import_experiment_link_job; Type: INDEX; Schema: data; Owner: -
--

CREATE INDEX idx_import_experiment_link_job ON data.import_experiment_link USING btree (organization_id, import_job_id, status, updated_at DESC);


--
-- Name: idx_import_experiment_link_submission; Type: INDEX; Schema: data; Owner: -
--

CREATE INDEX idx_import_experiment_link_submission ON data.import_experiment_link USING btree (organization_id, confirmed_submission_id) WHERE (confirmed_submission_id IS NOT NULL);


--
-- Name: idx_recognition_profile_list; Type: INDEX; Schema: data; Owner: -
--

CREATE INDEX idx_recognition_profile_list ON data.recognition_profile USING btree (organization_id, status, updated_at DESC, id);


--
-- Name: idx_staging_row_job_sheet_row; Type: INDEX; Schema: data; Owner: -
--

CREATE INDEX idx_staging_row_job_sheet_row ON data.staging_row USING btree (import_job_id, import_sheet_id, source_row_number);


--
-- Name: idx_submission_item_source; Type: INDEX; Schema: data; Owner: -
--

CREATE INDEX idx_submission_item_source ON data.confirmed_submission_item USING btree (organization_id, source_record_id, created_at DESC, id);


--
-- Name: uq_data_import_mapping_component_value; Type: INDEX; Schema: data; Owner: -
--

CREATE UNIQUE INDEX uq_data_import_mapping_component_value ON data.import_mapping USING btree (import_job_id, import_sheet_id, COALESCE((mapping_jsonb ->> 'componentId'::text), ''::text), COALESCE((mapping_jsonb ->> 'bindingId'::text), (source_column)::text), source_column);


--
-- Name: uq_data_record_job_index; Type: INDEX; Schema: data; Owner: -
--

CREATE UNIQUE INDEX uq_data_record_job_index ON data.data_record USING btree (import_job_id, record_index);


--
-- Name: uq_data_value_locator_v2; Type: INDEX; Schema: data; Owner: -
--

CREATE UNIQUE INDEX uq_data_value_locator_v2 ON data.data_value USING btree (record_id, binding_id, value_path) WHERE ((binding_id IS NOT NULL) AND (value_path IS NOT NULL));


--
-- Name: uq_staging_row_component_record_v7; Type: INDEX; Schema: data; Owner: -
--

CREATE UNIQUE INDEX uq_staging_row_component_record_v7 ON data.staging_row USING btree (import_job_id, import_sheet_id, COALESCE((source_metadata_jsonb ->> 'componentId'::text), ''::text), source_row_number);


--
-- Name: idx_login_attempt_account; Type: INDEX; Schema: iam; Owner: -
--

CREATE INDEX idx_login_attempt_account ON iam.login_attempt USING btree (organization_id, username, created_at);


--
-- Name: idx_login_attempt_ip; Type: INDEX; Schema: iam; Owner: -
--

CREATE INDEX idx_login_attempt_ip ON iam.login_attempt USING btree (ip_address, created_at);


--
-- Name: idx_login_session_expiry; Type: INDEX; Schema: iam; Owner: -
--

CREATE INDEX idx_login_session_expiry ON iam.login_session USING btree (expires_at) WHERE (revoked_at IS NULL);


--
-- Name: idx_login_session_user; Type: INDEX; Schema: iam; Owner: -
--

CREATE INDEX idx_login_session_user ON iam.login_session USING btree (organization_id, user_id, revoked_at);


--
-- Name: idx_permission_level_binding_menu_row; Type: INDEX; Schema: iam; Owner: -
--

CREATE INDEX idx_permission_level_binding_menu_row ON iam.permission_level_binding USING btree (organization_id, permission_level_id, menu_key);


--
-- Name: idx_permission_migration_issue_status; Type: INDEX; Schema: iam; Owner: -
--

CREATE INDEX idx_permission_migration_issue_status ON iam.permission_migration_issue USING btree (organization_id, status, created_at DESC);


--
-- Name: idx_project_member_user; Type: INDEX; Schema: iam; Owner: -
--

CREATE INDEX idx_project_member_user ON iam.project_member USING btree (organization_id, user_id, project_id);


--
-- Name: idx_resource_owner_identity_user; Type: INDEX; Schema: iam; Owner: -
--

CREATE INDEX idx_resource_owner_identity_user ON iam.resource_owner_identity USING btree (organization_id, user_id, resource_type, resource_id);


--
-- Name: idx_role_menu_binding_role; Type: INDEX; Schema: iam; Owner: -
--

CREATE INDEX idx_role_menu_binding_role ON iam.role_menu_binding USING btree (organization_id, role_id, menu_key);


--
-- Name: idx_user_role_user; Type: INDEX; Schema: iam; Owner: -
--

CREATE INDEX idx_user_role_user ON iam.user_role USING btree (organization_id, user_id, primary_role);


--
-- Name: uq_permission_level_binding_menu_row; Type: INDEX; Schema: iam; Owner: -
--

CREATE UNIQUE INDEX uq_permission_level_binding_menu_row ON iam.permission_level_binding USING btree (organization_id, permission_level_id, COALESCE(menu_key, ''::character varying), permission_code);


--
-- Name: idx_kb_chunk_child_document; Type: INDEX; Schema: kb; Owner: -
--

CREATE INDEX idx_kb_chunk_child_document ON kb.document_chunk USING btree (document_id, document_version_id, chunk_no) WHERE ((chunk_role)::text = 'CHILD'::text);


--
-- Name: idx_kb_chunk_content_trgm; Type: INDEX; Schema: kb; Owner: -
--

CREATE INDEX idx_kb_chunk_content_trgm ON kb.document_chunk USING gin (content public.gin_trgm_ops) WHERE ((chunk_role)::text = 'CHILD'::text);


--
-- Name: idx_kb_chunk_document; Type: INDEX; Schema: kb; Owner: -
--

CREATE INDEX idx_kb_chunk_document ON kb.document_chunk USING btree (document_id, document_version_id, chunk_no);


--
-- Name: idx_kb_chunk_search; Type: INDEX; Schema: kb; Owner: -
--

CREATE INDEX idx_kb_chunk_search ON kb.document_chunk USING gin (search_vector);


--
-- Name: idx_kb_chunk_term_lookup; Type: INDEX; Schema: kb; Owner: -
--

CREATE INDEX idx_kb_chunk_term_lookup ON kb.chunk_term USING btree (term, chunk_id);


--
-- Name: idx_kb_document_ai_grant_status; Type: INDEX; Schema: kb; Owner: -
--

CREATE INDEX idx_kb_document_ai_grant_status ON kb.document_ai_grant USING btree (organization_id, status, updated_at DESC);


--
-- Name: idx_kb_document_category; Type: INDEX; Schema: kb; Owner: -
--

CREATE INDEX idx_kb_document_category ON kb.document USING btree (organization_id, library_scope, category_id, updated_at DESC);


--
-- Name: idx_kb_document_category_list; Type: INDEX; Schema: kb; Owner: -
--

CREATE INDEX idx_kb_document_category_list ON kb.document_category USING btree (organization_id, scope, sort_order, created_at);


--
-- Name: idx_kb_document_duplicate_candidates; Type: INDEX; Schema: kb; Owner: -
--

CREATE INDEX idx_kb_document_duplicate_candidates ON kb.document USING btree (organization_id, category_id, updated_at DESC);


--
-- Name: idx_kb_document_list; Type: INDEX; Schema: kb; Owner: -
--

CREATE INDEX idx_kb_document_list ON kb.document USING btree (organization_id, updated_at DESC);


--
-- Name: idx_kb_document_status; Type: INDEX; Schema: kb; Owner: -
--

CREATE INDEX idx_kb_document_status ON kb.document USING btree (organization_id, status);


--
-- Name: idx_kb_document_version_file; Type: INDEX; Schema: kb; Owner: -
--

CREATE INDEX idx_kb_document_version_file ON kb.document_version USING btree (file_object_id);


--
-- Name: idx_kb_document_version_sha256; Type: INDEX; Schema: kb; Owner: -
--

CREATE INDEX idx_kb_document_version_sha256 ON kb.document_version USING btree (sha256, document_id);


--
-- Name: idx_kb_parse_issue_run; Type: INDEX; Schema: kb; Owner: -
--

CREATE INDEX idx_kb_parse_issue_run ON kb.document_parse_issue USING btree (parse_run_id, status, severity);


--
-- Name: idx_kb_parse_run_current; Type: INDEX; Schema: kb; Owner: -
--

CREATE INDEX idx_kb_parse_run_current ON kb.document_parse_run USING btree (organization_id, document_id, document_version_id, run_no DESC);


--
-- Name: idx_kb_processing_step_poll; Type: INDEX; Schema: kb; Owner: -
--

CREATE INDEX idx_kb_processing_step_poll ON kb.document_processing_step USING btree (status, updated_at);


--
-- Name: idx_kb_result_asset_entry; Type: INDEX; Schema: kb; Owner: -
--

CREATE INDEX idx_kb_result_asset_entry ON kb.document_result_asset USING btree (result_file_id, entry_path);


--
-- Name: idx_kb_result_asset_version; Type: INDEX; Schema: kb; Owner: -
--

CREATE INDEX idx_kb_result_asset_version ON kb.document_result_asset USING btree (organization_id, document_id, document_version_id);


--
-- Name: idx_kb_review_revision_version; Type: INDEX; Schema: kb; Owner: -
--

CREATE INDEX idx_kb_review_revision_version ON kb.document_review_revision USING btree (organization_id, document_version_id, revision_no DESC);


--
-- Name: idx_kb_source_node_run; Type: INDEX; Schema: kb; Owner: -
--

CREATE INDEX idx_kb_source_node_run ON kb.document_source_node USING btree (parse_run_id, node_no);


--
-- Name: idx_kb_upload_task_actor_recent; Type: INDEX; Schema: kb; Owner: -
--

CREATE INDEX idx_kb_upload_task_actor_recent ON kb.upload_task USING btree (organization_id, actor_id, created_at DESC);


--
-- Name: idx_kb_upload_task_visible_recent; Type: INDEX; Schema: kb; Owner: -
--

CREATE INDEX idx_kb_upload_task_visible_recent ON kb.upload_task USING btree (organization_id, actor_id, created_at DESC) WHERE (hidden_at IS NULL);


--
-- Name: uq_kb_chunk_review_revision_no; Type: INDEX; Schema: kb; Owner: -
--

CREATE UNIQUE INDEX uq_kb_chunk_review_revision_no ON kb.document_chunk USING btree (review_revision_id, chunk_no) WHERE (review_revision_id IS NOT NULL);


--
-- Name: uq_kb_processing_step_parse_run; Type: INDEX; Schema: kb; Owner: -
--

CREATE UNIQUE INDEX uq_kb_processing_step_parse_run ON kb.document_processing_step USING btree (parse_run_id, step_key) WHERE ((parse_run_id IS NOT NULL) AND (review_revision_id IS NULL));


--
-- Name: uq_kb_processing_step_review_revision; Type: INDEX; Schema: kb; Owner: -
--

CREATE UNIQUE INDEX uq_kb_processing_step_review_revision ON kb.document_processing_step USING btree (review_revision_id, step_key) WHERE (review_revision_id IS NOT NULL);


--
-- Name: uq_kb_publication_current; Type: INDEX; Schema: kb; Owner: -
--

CREATE UNIQUE INDEX uq_kb_publication_current ON kb.publication USING btree (document_id) WHERE ((status)::text = 'CURRENT'::text);


--
-- Name: uq_kb_review_revision_draft; Type: INDEX; Schema: kb; Owner: -
--

CREATE UNIQUE INDEX uq_kb_review_revision_draft ON kb.document_review_revision USING btree (document_version_id) WHERE ((status)::text = 'DRAFT'::text);


--
-- Name: uq_kb_upload_task_document_version; Type: INDEX; Schema: kb; Owner: -
--

CREATE UNIQUE INDEX uq_kb_upload_task_document_version ON kb.upload_task USING btree (organization_id, document_version_id) WHERE (document_version_id IS NOT NULL);


--
-- Name: idx_business_partner_status_updated; Type: INDEX; Schema: mdm; Owner: -
--

CREATE INDEX idx_business_partner_status_updated ON mdm.business_partner USING btree (status, updated_at DESC);


--
-- Name: idx_communication_partner_deleted_time; Type: INDEX; Schema: mdm; Owner: -
--

CREATE INDEX idx_communication_partner_deleted_time ON mdm.communication_record USING btree (partner_id, deleted, communicated_at DESC);


--
-- Name: idx_communication_partner_time; Type: INDEX; Schema: mdm; Owner: -
--

CREATE INDEX idx_communication_partner_time ON mdm.communication_record USING btree (partner_id, communicated_at DESC);


--
-- Name: idx_customer_requirement_assigned_project_ids; Type: INDEX; Schema: mdm; Owner: -
--

CREATE INDEX idx_customer_requirement_assigned_project_ids ON mdm.customer_requirement USING gin (assigned_project_ids);


--
-- Name: idx_doc_audit_document; Type: INDEX; Schema: mdm; Owner: -
--

CREATE INDEX idx_doc_audit_document ON mdm.project_document_audit USING btree (document_id, created_at DESC);


--
-- Name: idx_doc_review_document; Type: INDEX; Schema: mdm; Owner: -
--

CREATE INDEX idx_doc_review_document ON mdm.project_document_review USING btree (document_id);


--
-- Name: idx_doc_version_document; Type: INDEX; Schema: mdm; Owner: -
--

CREATE INDEX idx_doc_version_document ON mdm.project_document_version USING btree (document_id, version_no DESC);


--
-- Name: idx_mdm_project_org_updated; Type: INDEX; Schema: mdm; Owner: -
--

CREATE INDEX idx_mdm_project_org_updated ON mdm.project USING btree (organization_id, updated_at DESC) WHERE (deleted = false);


--
-- Name: idx_partner_contact_partner_status; Type: INDEX; Schema: mdm; Owner: -
--

CREATE INDEX idx_partner_contact_partner_status ON mdm.partner_contact USING btree (partner_id, status);


--
-- Name: idx_partner_contact_project; Type: INDEX; Schema: mdm; Owner: -
--

CREATE INDEX idx_partner_contact_project ON mdm.partner_contact_project USING btree (project_id);


--
-- Name: idx_pda_document; Type: INDEX; Schema: mdm; Owner: -
--

CREATE INDEX idx_pda_document ON mdm.project_document_audit USING btree (document_id);


--
-- Name: idx_pda_document_version; Type: INDEX; Schema: mdm; Owner: -
--

CREATE INDEX idx_pda_document_version ON mdm.project_document_audit USING btree (document_id, document_version_id);


--
-- Name: idx_pdv_document; Type: INDEX; Schema: mdm; Owner: -
--

CREATE INDEX idx_pdv_document ON mdm.project_document_version USING btree (document_id);


--
-- Name: idx_pdv_document_version_jsonb; Type: INDEX; Schema: mdm; Owner: -
--

CREATE INDEX idx_pdv_document_version_jsonb ON mdm.project_document_version USING btree (document_id, version_no DESC);


--
-- Name: idx_project_document_project; Type: INDEX; Schema: mdm; Owner: -
--

CREATE INDEX idx_project_document_project ON mdm.project_document USING btree (project_id);


--
-- Name: idx_project_document_version_document; Type: INDEX; Schema: mdm; Owner: -
--

CREATE INDEX idx_project_document_version_document ON mdm.project_document_version USING btree (document_id, version_no DESC);


--
-- Name: idx_project_stage_code; Type: INDEX; Schema: mdm; Owner: -
--

CREATE INDEX idx_project_stage_code ON mdm.project_stage USING btree (stage_code) WHERE (deleted = false);


--
-- Name: idx_project_task_stage; Type: INDEX; Schema: mdm; Owner: -
--

CREATE INDEX idx_project_task_stage ON mdm.project_task USING btree (stage_id, status) WHERE (deleted = false);


--
-- Name: idx_requirement_partner; Type: INDEX; Schema: mdm; Owner: -
--

CREATE INDEX idx_requirement_partner ON mdm.customer_requirement USING btree (partner_id, updated_at DESC) WHERE (deleted = false);


--
-- Name: idx_requirement_partner_status; Type: INDEX; Schema: mdm; Owner: -
--

CREATE INDEX idx_requirement_partner_status ON mdm.customer_requirement USING btree (partner_id, status, updated_at DESC);


--
-- Name: idx_requirement_project; Type: INDEX; Schema: mdm; Owner: -
--

CREATE INDEX idx_requirement_project ON mdm.customer_requirement USING btree (project_id);


--
-- Name: idx_rnd_project_document_project; Type: INDEX; Schema: mdm; Owner: -
--

CREATE INDEX idx_rnd_project_document_project ON mdm.project_document USING btree (project_id, updated_at DESC) WHERE (deleted = false);


--
-- Name: idx_rnd_project_document_status; Type: INDEX; Schema: mdm; Owner: -
--

CREATE INDEX idx_rnd_project_document_status ON mdm.project_document USING btree (project_id, status) WHERE (deleted = false);


--
-- Name: idx_rnd_project_document_template; Type: INDEX; Schema: mdm; Owner: -
--

CREATE INDEX idx_rnd_project_document_template ON mdm.project_document USING btree (template_id) WHERE (template_id IS NOT NULL);


--
-- Name: ix_material_category; Type: INDEX; Schema: mdm; Owner: -
--

CREATE INDEX ix_material_category ON mdm.material USING btree (category);


--
-- Name: ix_material_status; Type: INDEX; Schema: mdm; Owner: -
--

CREATE INDEX ix_material_status ON mdm.material USING btree (status);


--
-- Name: ix_meeting_minutes_project; Type: INDEX; Schema: mdm; Owner: -
--

CREATE INDEX ix_meeting_minutes_project ON mdm.meeting_minutes USING btree (project_id, occurred_at DESC);


--
-- Name: ix_project_stage_owner; Type: INDEX; Schema: mdm; Owner: -
--

CREATE INDEX ix_project_stage_owner ON mdm.project_stage USING btree (owner) WHERE (deleted = false);


--
-- Name: ix_project_stage_project_status_order; Type: INDEX; Schema: mdm; Owner: -
--

CREATE INDEX ix_project_stage_project_status_order ON mdm.project_stage USING btree (project_id, status, order_no) WHERE (deleted = false);


--
-- Name: ix_project_stage_updated; Type: INDEX; Schema: mdm; Owner: -
--

CREATE INDEX ix_project_stage_updated ON mdm.project_stage USING btree (updated_at DESC) WHERE (deleted = false);


--
-- Name: uq_material_code; Type: INDEX; Schema: mdm; Owner: -
--

CREATE UNIQUE INDEX uq_material_code ON mdm.material USING btree (code);


--
-- Name: uq_project_stage_name_active; Type: INDEX; Schema: mdm; Owner: -
--

CREATE UNIQUE INDEX uq_project_stage_name_active ON mdm.project_stage USING btree (project_id, lower((name)::text)) WHERE (deleted = false);


--
-- Name: uq_project_stage_order_active; Type: INDEX; Schema: mdm; Owner: -
--

CREATE UNIQUE INDEX uq_project_stage_order_active ON mdm.project_stage USING btree (project_id, order_no) WHERE (deleted = false);


--
-- Name: idx_inventory_product_scope; Type: INDEX; Schema: mfg; Owner: -
--

CREATE INDEX idx_inventory_product_scope ON mfg.inventory_product USING btree (organization_id, inventory_scope, status, name);


--
-- Name: idx_inventory_sample_customer; Type: INDEX; Schema: mfg; Owner: -
--

CREATE INDEX idx_inventory_sample_customer ON mfg.inventory_sample_dispatch USING btree (customer_id);


--
-- Name: idx_inventory_shipment_customer; Type: INDEX; Schema: mfg; Owner: -
--

CREATE INDEX idx_inventory_shipment_customer ON mfg.inventory_shipment USING btree (customer_id);


--
-- Name: idx_inventory_tx_query; Type: INDEX; Schema: mfg; Owner: -
--

CREATE INDEX idx_inventory_tx_query ON mfg.inventory_transaction USING btree (organization_id, inventory_scope, business_date DESC, created_at DESC);


--
-- Name: idx_production_ingest_item_job; Type: INDEX; Schema: mfg; Owner: -
--

CREATE INDEX idx_production_ingest_item_job ON mfg.production_ingest_item USING btree (ingest_job_id, item_kind, record_index);


--
-- Name: idx_production_ingest_job_order; Type: INDEX; Schema: mfg; Owner: -
--

CREATE INDEX idx_production_ingest_job_order ON mfg.production_ingest_job USING btree (production_order_id, created_at DESC);


--
-- Name: idx_production_upload_field_code; Type: INDEX; Schema: mfg; Owner: -
--

CREATE INDEX idx_production_upload_field_code ON mfg.production_upload_field USING btree (production_upload_id, field_code, record_index);


--
-- Name: idx_production_upload_list; Type: INDEX; Schema: mfg; Owner: -
--

CREATE INDEX idx_production_upload_list ON mfg.production_upload USING btree (organization_id, created_at DESC) WHERE ((status)::text = 'SAVED'::text);


--
-- Name: idx_production_upload_order; Type: INDEX; Schema: mfg; Owner: -
--

CREATE INDEX idx_production_upload_order ON mfg.production_upload USING btree (organization_id, order_no);


--
-- Name: idx_production_upload_recognition_queue; Type: INDEX; Schema: mfg; Owner: -
--

CREATE INDEX idx_production_upload_recognition_queue ON mfg.production_upload USING btree (organization_id, status, created_at DESC) WHERE ((status)::text <> 'DELETED'::text);


--
-- Name: idx_production_upload_source_type; Type: INDEX; Schema: mfg; Owner: -
--

CREATE INDEX idx_production_upload_source_type ON mfg.production_upload USING btree (organization_id, source_type, created_at DESC);


--
-- Name: idx_production_upload_version_list; Type: INDEX; Schema: mfg; Owner: -
--

CREATE INDEX idx_production_upload_version_list ON mfg.production_upload_version USING btree (organization_id, production_upload_id, version_no DESC);


--
-- Name: inventory_shipment_header_lookup; Type: INDEX; Schema: mfg; Owner: -
--

CREATE INDEX inventory_shipment_header_lookup ON mfg.inventory_shipment USING btree (organization_id, shipment_no);


--
-- Name: uq_inventory_single_reversal; Type: INDEX; Schema: mfg; Owner: -
--

CREATE UNIQUE INDEX uq_inventory_single_reversal ON mfg.inventory_transaction USING btree (reversal_of) WHERE (reversal_of IS NOT NULL);


--
-- Name: idx_async_job_capacity_active; Type: INDEX; Schema: ops; Owner: -
--

CREATE INDEX idx_async_job_capacity_active ON ops.async_job USING btree (organization_id) WHERE ((status)::text = ANY ((ARRAY['READY'::character varying, 'RUNNING'::character varying])::text[]));


--
-- Name: idx_async_job_claim; Type: INDEX; Schema: ops; Owner: -
--

CREATE INDEX idx_async_job_claim ON ops.async_job USING btree (priority, next_attempt_at, created_at) WHERE ((status)::text = ANY (ARRAY[('READY'::character varying)::text, ('RUNNING'::character varying)::text]));


--
-- Name: idx_audit_log_org_actor; Type: INDEX; Schema: ops; Owner: -
--

CREATE INDEX idx_audit_log_org_actor ON ops.audit_log USING btree (organization_id, actor_id, created_at DESC);


--
-- Name: idx_audit_log_org_created; Type: INDEX; Schema: ops; Owner: -
--

CREATE INDEX idx_audit_log_org_created ON ops.audit_log USING btree (organization_id, created_at DESC);


--
-- Name: idx_file_object_staged; Type: INDEX; Schema: ops; Owner: -
--

CREATE INDEX idx_file_object_staged ON ops.file_object USING btree (created_at) WHERE ((status)::text = 'STAGED'::text);


--
-- Name: idx_outbox_pending; Type: INDEX; Schema: ops; Owner: -
--

CREATE INDEX idx_outbox_pending ON ops.outbox_event USING btree (next_attempt_at, created_at) WHERE ((status)::text = ANY (ARRAY[('PENDING'::character varying)::text, ('PROCESSING'::character varying)::text]));


--
-- Name: uq_outbox_event_idempotency; Type: INDEX; Schema: ops; Owner: -
--

CREATE UNIQUE INDEX uq_outbox_event_idempotency ON ops.outbox_event USING btree (organization_id, idempotency_key) WHERE ((organization_id IS NOT NULL) AND (idempotency_key IS NOT NULL));


--
-- Name: flyway_schema_history_s_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX flyway_schema_history_s_idx ON public.flyway_schema_history USING btree (success);


--
-- Name: idx_quality_record_list; Type: INDEX; Schema: quality; Owner: -
--

CREATE INDEX idx_quality_record_list ON quality.data_record USING btree (organization_id, business_type, category_id, updated_at DESC) WHERE (deleted = false);


--
-- Name: idx_quality_record_version_history; Type: INDEX; Schema: quality; Owner: -
--

CREATE INDEX idx_quality_record_version_history ON quality.data_record_version USING btree (organization_id, record_id, version_no DESC);


--
-- Name: idx_quality_upload_category; Type: INDEX; Schema: quality; Owner: -
--

CREATE INDEX idx_quality_upload_category ON quality.upload_file USING btree (organization_id, category_id) WHERE (deleted = false);


--
-- Name: idx_quality_upload_list; Type: INDEX; Schema: quality; Owner: -
--

CREATE INDEX idx_quality_upload_list ON quality.upload_file USING btree (organization_id, created_at DESC) WHERE (deleted = false);


--
-- Name: idx_quality_upload_status; Type: INDEX; Schema: quality; Owner: -
--

CREATE INDEX idx_quality_upload_status ON quality.upload_file USING btree (organization_id, status) WHERE (deleted = false);


--
-- Name: uq_quality_record_no; Type: INDEX; Schema: quality; Owner: -
--

CREATE UNIQUE INDEX uq_quality_record_no ON quality.data_record USING btree (organization_id, business_type, business_no) WHERE (deleted = false);


--
-- Name: experiment_content_import_source_uidx; Type: INDEX; Schema: rnd; Owner: -
--

CREATE UNIQUE INDEX experiment_content_import_source_uidx ON rnd.experiment_import_job USING btree (organization_id, source_file_id) WHERE ((import_options_jsonb ->> 'processingMode'::text) = 'CONTENT_ONLY'::text);


--
-- Name: idx_eln_audit; Type: INDEX; Schema: rnd; Owner: -
--

CREATE INDEX idx_eln_audit ON rnd.experiment_audit USING btree (experiment_id, created_at DESC);


--
-- Name: idx_eln_list; Type: INDEX; Schema: rnd; Owner: -
--

CREATE INDEX idx_eln_list ON rnd.experiment USING btree (organization_id, status, updated_at DESC) WHERE (deleted = false);


--
-- Name: idx_eln_project; Type: INDEX; Schema: rnd; Owner: -
--

CREATE INDEX idx_eln_project ON rnd.experiment USING btree (organization_id, project_id, stage_id, task_id) WHERE (deleted = false);


--
-- Name: idx_eln_version_history; Type: INDEX; Schema: rnd; Owner: -
--

CREATE INDEX idx_eln_version_history ON rnd.experiment_version USING btree (experiment_id, version_no DESC);


--
-- Name: idx_experiment_ai_evidence_candidate; Type: INDEX; Schema: rnd; Owner: -
--

CREATE INDEX idx_experiment_ai_evidence_candidate ON rnd.experiment_ai_source_evidence USING btree (organization_id, research_candidate_id, captured_at DESC, id) WHERE (research_candidate_id IS NOT NULL);


--
-- Name: idx_experiment_ai_evidence_lookup; Type: INDEX; Schema: rnd; Owner: -
--

CREATE INDEX idx_experiment_ai_evidence_lookup ON rnd.experiment_ai_source_evidence USING btree (organization_id, experiment_id, captured_at DESC, id);


--
-- Name: idx_experiment_import_job_context; Type: INDEX; Schema: rnd; Owner: -
--

CREATE INDEX idx_experiment_import_job_context ON rnd.experiment_import_job USING btree (organization_id, project_id, stage_id, task_id, created_at DESC);


--
-- Name: idx_experiment_import_job_status; Type: INDEX; Schema: rnd; Owner: -
--

CREATE INDEX idx_experiment_import_job_status ON rnd.experiment_import_job USING btree (organization_id, status, created_at DESC);


--
-- Name: idx_experiment_source_reference_lookup; Type: INDEX; Schema: rnd; Owner: -
--

CREATE INDEX idx_experiment_source_reference_lookup ON rnd.experiment_source_reference USING btree (organization_id, experiment_id, created_at DESC, id);


--
-- Name: idx_research_test_list; Type: INDEX; Schema: rnd; Owner: -
--

CREATE INDEX idx_research_test_list ON rnd.research_test_record USING btree (organization_id, record_type, updated_at DESC) WHERE (deleted = false);


--
-- Name: idx_research_test_project; Type: INDEX; Schema: rnd; Owner: -
--

CREATE INDEX idx_research_test_project ON rnd.research_test_record USING btree (organization_id, project_id, stage_id) WHERE (deleted = false);


--
-- Name: idx_research_test_uploads; Type: INDEX; Schema: rnd; Owner: -
--

CREATE INDEX idx_research_test_uploads ON rnd.research_test_upload USING btree (organization_id, created_at DESC) WHERE (deleted = false);


--
-- Name: idx_rnd_experiment_source_file; Type: INDEX; Schema: rnd; Owner: -
--

CREATE INDEX idx_rnd_experiment_source_file ON rnd.experiment USING btree (organization_id, source_file_id) WHERE (source_file_id IS NOT NULL);


--
-- Name: uq_experiment_source_reference_free_upload; Type: INDEX; Schema: rnd; Owner: -
--

CREATE UNIQUE INDEX uq_experiment_source_reference_free_upload ON rnd.experiment_source_reference USING btree (organization_id, experiment_version_id, experiment_import_job_id, sample_boundary_id) WHERE (experiment_import_job_id IS NOT NULL);


--
-- Name: uq_research_test_upload_hash; Type: INDEX; Schema: rnd; Owner: -
--

CREATE UNIQUE INDEX uq_research_test_upload_hash ON rnd.research_test_upload USING btree (organization_id, sha256) WHERE ((sha256 IS NOT NULL) AND (deleted = false));


--
-- Name: idx_spc_analysis_event_stream; Type: INDEX; Schema: spc; Owner: -
--

CREATE INDEX idx_spc_analysis_event_stream ON spc.analysis_event USING btree (organization_id, analysis_run_id, id);


--
-- Name: idx_spc_analysis_run_session; Type: INDEX; Schema: spc; Owner: -
--

CREATE INDEX idx_spc_analysis_run_session ON spc.analysis_run USING btree (organization_id, session_id, created_at);


--
-- Name: idx_spc_analysis_run_status; Type: INDEX; Schema: spc; Owner: -
--

CREATE INDEX idx_spc_analysis_run_status ON spc.analysis_run USING btree (organization_id, status, created_at DESC);


--
-- Name: idx_spc_chart_asset_list; Type: INDEX; Schema: spc; Owner: -
--

CREATE INDEX idx_spc_chart_asset_list ON spc.chart_asset USING btree (organization_id, category_id, status, updated_at DESC);


--
-- Name: idx_spc_chart_asset_search; Type: INDEX; Schema: spc; Owner: -
--

CREATE INDEX idx_spc_chart_asset_search ON spc.chart_asset USING btree (organization_id, title, original_name, sample_name, batch_no);


--
-- Name: idx_spc_chart_category_order; Type: INDEX; Schema: spc; Owner: -
--

CREATE INDEX idx_spc_chart_category_order ON spc.chart_category USING btree (organization_id, sort_order, name);


--
-- Name: idx_spc_chart_upload_attempt_recent; Type: INDEX; Schema: spc; Owner: -
--

CREATE INDEX idx_spc_chart_upload_attempt_recent ON spc.chart_upload_attempt USING btree (organization_id, created_at DESC);


--
-- Name: idx_spc_chart_upload_attempt_visible_recent; Type: INDEX; Schema: spc; Owner: -
--

CREATE INDEX idx_spc_chart_upload_attempt_visible_recent ON spc.chart_upload_attempt USING btree (organization_id, created_at DESC) WHERE (hidden_at IS NULL);


--
-- Name: idx_spc_chat_message_session; Type: INDEX; Schema: spc; Owner: -
--

CREATE INDEX idx_spc_chat_message_session ON spc.chat_message USING btree (organization_id, session_id, created_at);


--
-- Name: idx_spc_chat_session_list; Type: INDEX; Schema: spc; Owner: -
--

CREATE INDEX idx_spc_chat_session_list ON spc.chat_session USING btree (organization_id, created_by, updated_at DESC);


--
-- Name: uq_spc_chart_asset_request; Type: INDEX; Schema: spc; Owner: -
--

CREATE UNIQUE INDEX uq_spc_chart_asset_request ON spc.chart_asset USING btree (organization_id, request_id) WHERE (request_id IS NOT NULL);


--
-- Name: idx_catalog_import_created; Type: INDEX; Schema: tpl; Owner: -
--

CREATE INDEX idx_catalog_import_created ON tpl.standard_field_catalog_import USING btree (created_at DESC, id);


--
-- Name: idx_catalog_proposal_review; Type: INDEX; Schema: tpl; Owner: -
--

CREATE INDEX idx_catalog_proposal_review ON tpl.standard_field_catalog_proposal USING btree (review_status, atomic_name, id);


--
-- Name: idx_catalog_proposal_semantic; Type: INDEX; Schema: tpl; Owner: -
--

CREATE INDEX idx_catalog_proposal_semantic ON tpl.standard_field_catalog_proposal USING btree (semantic_key, id);


--
-- Name: idx_recognition_call_expiry; Type: INDEX; Schema: tpl; Owner: -
--

CREATE INDEX idx_recognition_call_expiry ON tpl.recognition_call USING btree (payload_expires_at) WHERE (payload_purged_at IS NULL);


--
-- Name: idx_recognition_call_region_phase_attempt; Type: INDEX; Schema: tpl; Owner: -
--

CREATE INDEX idx_recognition_call_region_phase_attempt ON tpl.recognition_call USING btree (recognition_run_id, region_id, phase, attempt, created_at DESC);


--
-- Name: idx_recognition_run_job; Type: INDEX; Schema: tpl; Owner: -
--

CREATE INDEX idx_recognition_run_job ON tpl.recognition_run USING btree (import_job_id, created_at DESC);


--
-- Name: idx_recognition_suggestion_parent; Type: INDEX; Schema: tpl; Owner: -
--

CREATE INDEX idx_recognition_suggestion_parent ON tpl.recognition_suggestion USING btree (parent_suggestion_id, decision);


--
-- Name: idx_recognition_suggestion_region; Type: INDEX; Schema: tpl; Owner: -
--

CREATE INDEX idx_recognition_suggestion_region ON tpl.recognition_suggestion USING btree (recognition_run_id, region_id, confidence DESC);


--
-- Name: idx_recognition_suggestion_relation; Type: INDEX; Schema: tpl; Owner: -
--

CREATE INDEX idx_recognition_suggestion_relation ON tpl.recognition_suggestion USING btree (recognition_run_id, relation_id) WHERE (relation_id IS NOT NULL);


--
-- Name: idx_recognition_suggestion_review; Type: INDEX; Schema: tpl; Owner: -
--

CREATE INDEX idx_recognition_suggestion_review ON tpl.recognition_suggestion USING btree (import_job_id, decision, confidence DESC);


--
-- Name: idx_recognition_trace_run; Type: INDEX; Schema: tpl; Owner: -
--

CREATE INDEX idx_recognition_trace_run ON tpl.recognition_trace USING btree (recognition_run_id, created_at);


--
-- Name: idx_record_collection_order; Type: INDEX; Schema: tpl; Owner: -
--

CREATE INDEX idx_record_collection_order ON tpl.record_collection_item USING btree (production_order_id, parent_field_code, record_index);


--
-- Name: idx_record_value_collection; Type: INDEX; Schema: tpl; Owner: -
--

CREATE INDEX idx_record_value_collection ON tpl.record_value_index USING btree (collection_item_id) WHERE (collection_item_id IS NOT NULL);


--
-- Name: idx_record_value_numeric; Type: INDEX; Schema: tpl; Owner: -
--

CREATE INDEX idx_record_value_numeric ON tpl.record_value_index USING btree (field_code, numeric_value) WHERE (numeric_value IS NOT NULL);


--
-- Name: idx_record_value_text; Type: INDEX; Schema: tpl; Owner: -
--

CREATE INDEX idx_record_value_text ON tpl.record_value_index USING btree (field_code, text_value) WHERE (text_value IS NOT NULL);


--
-- Name: idx_standard_field_dictionary_name; Type: INDEX; Schema: tpl; Owner: -
--

CREATE INDEX idx_standard_field_dictionary_name ON tpl.standard_field_dictionary USING btree (lower((display_name)::text), status);


--
-- Name: idx_standard_field_request_catalog; Type: INDEX; Schema: tpl; Owner: -
--

CREATE INDEX idx_standard_field_request_catalog ON tpl.standard_field_request USING btree (catalog_proposal_id) WHERE (catalog_proposal_id IS NOT NULL);


--
-- Name: idx_standard_field_request_queue; Type: INDEX; Schema: tpl; Owner: -
--

CREATE INDEX idx_standard_field_request_queue ON tpl.standard_field_request USING btree (organization_id, status, created_at DESC);


--
-- Name: idx_template_category_id; Type: INDEX; Schema: tpl; Owner: -
--

CREATE INDEX idx_template_category_id ON tpl.template USING btree (category_id);


--
-- Name: idx_template_category_organization_sort; Type: INDEX; Schema: tpl; Owner: -
--

CREATE INDEX idx_template_category_organization_sort ON tpl.template_category USING btree (organization_id, sort_order, created_at);


--
-- Name: idx_template_import_job_category; Type: INDEX; Schema: tpl; Owner: -
--

CREATE INDEX idx_template_import_job_category ON tpl.template_import_job USING btree (organization_id, category_id, created_at DESC);


--
-- Name: idx_template_import_job_duplicate_lookup; Type: INDEX; Schema: tpl; Owner: -
--

CREATE INDEX idx_template_import_job_duplicate_lookup ON tpl.template_import_job USING btree (organization_id, source_sha256, format, created_at DESC) WHERE (source_sha256 IS NOT NULL);


--
-- Name: idx_template_import_job_normalized_source; Type: INDEX; Schema: tpl; Owner: -
--

CREATE INDEX idx_template_import_job_normalized_source ON tpl.template_import_job USING btree (organization_id, normalized_source_file_id);


--
-- Name: idx_template_logical_list; Type: INDEX; Schema: tpl; Owner: -
--

CREATE INDEX idx_template_logical_list ON tpl.template USING btree (organization_id, updated_at DESC, id);


--
-- Name: idx_template_mapping_parent; Type: INDEX; Schema: tpl; Owner: -
--

CREATE INDEX idx_template_mapping_parent ON tpl.template_mapping USING btree (template_version_id, parent_binding_id);


--
-- Name: idx_template_mapping_path; Type: INDEX; Schema: tpl; Owner: -
--

CREATE INDEX idx_template_mapping_path ON tpl.template_mapping USING btree (template_version_id, data_path);


--
-- Name: idx_template_quality_issue_customer_root; Type: INDEX; Schema: tpl; Owner: -
--

CREATE INDEX idx_template_quality_issue_customer_root ON tpl.template_quality_issue USING btree (recognition_run_id, sheet_id, root_block_id, customer_issue_category, status);


--
-- Name: idx_template_quality_issue_location; Type: INDEX; Schema: tpl; Owner: -
--

CREATE INDEX idx_template_quality_issue_location ON tpl.template_quality_issue USING btree (import_job_id, sheet_id, address);


--
-- Name: idx_template_quality_issue_run; Type: INDEX; Schema: tpl; Owner: -
--

CREATE INDEX idx_template_quality_issue_run ON tpl.template_quality_issue USING btree (recognition_run_id, status, severity, created_at);


--
-- Name: idx_template_structure_change_version; Type: INDEX; Schema: tpl; Owner: -
--

CREATE INDEX idx_template_structure_change_version ON tpl.template_structure_change USING btree (template_version_id, created_at, operation_order);


--
-- Name: idx_template_version_current_draft; Type: INDEX; Schema: tpl; Owner: -
--

CREATE INDEX idx_template_version_current_draft ON tpl.template_version USING btree (template_id, version_no DESC) WHERE ((status)::text = 'DRAFT'::text);


--
-- Name: idx_template_version_review_event_version; Type: INDEX; Schema: tpl; Owner: -
--

CREATE INDEX idx_template_version_review_event_version ON tpl.template_version_review_event USING btree (organization_id, version_id, created_at DESC);


--
-- Name: idx_template_version_review_org_status; Type: INDEX; Schema: tpl; Owner: -
--

CREATE INDEX idx_template_version_review_org_status ON tpl.template_version_review USING btree (organization_id, review_status, updated_at DESC);


--
-- Name: idx_tpl_template_import_job_visible_recent; Type: INDEX; Schema: tpl; Owner: -
--

CREATE INDEX idx_tpl_template_import_job_visible_recent ON tpl.template_import_job USING btree (organization_id, created_at DESC) WHERE (hidden_at IS NULL);


--
-- Name: uq_recognition_suggestion_fingerprint; Type: INDEX; Schema: tpl; Owner: -
--

CREATE UNIQUE INDEX uq_recognition_suggestion_fingerprint ON tpl.recognition_suggestion USING btree (recognition_run_id, semantic_fingerprint) WHERE (semantic_fingerprint IS NOT NULL);


--
-- Name: uq_template_import_contract_hash; Type: INDEX; Schema: tpl; Owner: -
--

CREATE UNIQUE INDEX uq_template_import_contract_hash ON tpl.template_import_contract USING btree (template_version_id, contract_hash);


--
-- Name: uq_template_open_draft; Type: INDEX; Schema: tpl; Owner: -
--

CREATE UNIQUE INDEX uq_template_open_draft ON tpl.template_version USING btree (template_id) WHERE ((status)::text = 'DRAFT'::text);


--
-- Name: artifact immutable_artifact; Type: TRIGGER; Schema: ai; Owner: -
--

CREATE TRIGGER immutable_artifact BEFORE DELETE OR UPDATE ON ai.artifact FOR EACH ROW EXECUTE FUNCTION ai.reject_immutable_change();


--
-- Name: input_field_version immutable_input_field_version; Type: TRIGGER; Schema: ai; Owner: -
--

CREATE TRIGGER immutable_input_field_version BEFORE DELETE OR UPDATE ON ai.input_field_version FOR EACH ROW WHEN (((old.status)::text = 'PUBLISHED'::text)) EXECUTE FUNCTION ai.reject_immutable_change();


--
-- Name: input_scheme immutable_input_scheme; Type: TRIGGER; Schema: ai; Owner: -
--

CREATE TRIGGER immutable_input_scheme BEFORE DELETE OR UPDATE ON ai.input_scheme FOR EACH ROW WHEN (((old.status)::text = 'FROZEN'::text)) EXECUTE FUNCTION ai.reject_immutable_change();


--
-- Name: material_dictionary_version immutable_material_dictionary; Type: TRIGGER; Schema: ai; Owner: -
--

CREATE TRIGGER immutable_material_dictionary BEFORE DELETE OR UPDATE ON ai.material_dictionary_version FOR EACH ROW WHEN (((old.status)::text = ANY (ARRAY[('FROZEN'::character varying)::text, ('RETIRED'::character varying)::text]))) EXECUTE FUNCTION ai.protect_frozen_material_dictionary();


--
-- Name: model_feedback immutable_model_feedback; Type: TRIGGER; Schema: ai; Owner: -
--

CREATE TRIGGER immutable_model_feedback BEFORE DELETE OR UPDATE ON ai.model_feedback FOR EACH ROW EXECUTE FUNCTION ai.reject_immutable_change();


--
-- Name: model_release immutable_model_release; Type: TRIGGER; Schema: ai; Owner: -
--

CREATE TRIGGER immutable_model_release BEFORE DELETE OR UPDATE ON ai.model_release FOR EACH ROW EXECUTE FUNCTION ai.reject_immutable_change();


--
-- Name: modeling_policy_version immutable_modeling_policy; Type: TRIGGER; Schema: ai; Owner: -
--

CREATE TRIGGER immutable_modeling_policy BEFORE DELETE OR UPDATE ON ai.modeling_policy_version FOR EACH ROW WHEN (((old.status)::text = 'PUBLISHED'::text)) EXECUTE FUNCTION ai.reject_immutable_change();


--
-- Name: model_version immutable_released_model_version; Type: TRIGGER; Schema: ai; Owner: -
--

CREATE TRIGGER immutable_released_model_version BEFORE DELETE ON ai.model_version FOR EACH ROW WHEN (((old.status)::text = ANY (ARRAY[('ACTIVE'::character varying)::text, ('PAUSED'::character varying)::text, ('RETIRED'::character varying)::text]))) EXECUTE FUNCTION ai.reject_immutable_change();


--
-- Name: research_candidate_v2 immutable_research_candidate_v2; Type: TRIGGER; Schema: ai; Owner: -
--

CREATE TRIGGER immutable_research_candidate_v2 BEFORE DELETE OR UPDATE ON ai.research_candidate_v2 FOR EACH ROW EXECUTE FUNCTION ai.reject_immutable_change();


--
-- Name: research_experiment_link_v2 immutable_research_experiment_link_v2; Type: TRIGGER; Schema: ai; Owner: -
--

CREATE TRIGGER immutable_research_experiment_link_v2 BEFORE DELETE OR UPDATE ON ai.research_experiment_link_v2 FOR EACH ROW EXECUTE FUNCTION ai.reject_immutable_change();


--
-- Name: sample_revision immutable_sample_revision; Type: TRIGGER; Schema: ai; Owner: -
--

CREATE TRIGGER immutable_sample_revision BEFORE DELETE OR UPDATE ON ai.sample_revision FOR EACH ROW EXECUTE FUNCTION ai.reject_immutable_change();


--
-- Name: source_mapping_version immutable_source_mapping; Type: TRIGGER; Schema: ai; Owner: -
--

CREATE TRIGGER immutable_source_mapping BEFORE DELETE OR UPDATE ON ai.source_mapping_version FOR EACH ROW WHEN (((old.status)::text = 'PUBLISHED'::text)) EXECUTE FUNCTION ai.reject_immutable_change();


--
-- Name: target_version immutable_target_version; Type: TRIGGER; Schema: ai; Owner: -
--

CREATE TRIGGER immutable_target_version BEFORE DELETE OR UPDATE ON ai.target_version FOR EACH ROW WHEN (((old.status)::text = 'PUBLISHED'::text)) EXECUTE FUNCTION ai.reject_immutable_change();


--
-- Name: training_snapshot immutable_training_snapshot; Type: TRIGGER; Schema: ai; Owner: -
--

CREATE TRIGGER immutable_training_snapshot BEFORE DELETE OR UPDATE ON ai.training_snapshot FOR EACH ROW EXECUTE FUNCTION ai.reject_immutable_change();


--
-- Name: training_snapshot_item immutable_training_snapshot_item; Type: TRIGGER; Schema: ai; Owner: -
--

CREATE TRIGGER immutable_training_snapshot_item BEFORE DELETE OR UPDATE ON ai.training_snapshot_item FOR EACH ROW EXECUTE FUNCTION ai.reject_immutable_change();


--
-- Name: input_scheme_field protect_frozen_input_scheme_field; Type: TRIGGER; Schema: ai; Owner: -
--

CREATE TRIGGER protect_frozen_input_scheme_field BEFORE INSERT OR DELETE OR UPDATE ON ai.input_scheme_field FOR EACH ROW EXECUTE FUNCTION ai.protect_frozen_input_scheme_field();


--
-- Name: model_version protect_model_version_payload; Type: TRIGGER; Schema: ai; Owner: -
--

CREATE TRIGGER protect_model_version_payload BEFORE UPDATE ON ai.model_version FOR EACH ROW EXECUTE FUNCTION ai.protect_model_version_payload();


--
-- Name: prediction_record protect_prediction_record; Type: TRIGGER; Schema: ai; Owner: -
--

CREATE TRIGGER protect_prediction_record BEFORE DELETE OR UPDATE ON ai.prediction_record FOR EACH ROW EXECUTE FUNCTION ai.protect_prediction_record_v2();


--
-- Name: research_run_v2 protect_research_run_v2; Type: TRIGGER; Schema: ai; Owner: -
--

CREATE TRIGGER protect_research_run_v2 BEFORE DELETE OR UPDATE ON ai.research_run_v2 FOR EACH ROW EXECUTE FUNCTION ai.protect_completed_request();


--
-- Name: research_run_v2 protect_research_run_v2_r08; Type: TRIGGER; Schema: ai; Owner: -
--

CREATE TRIGGER protect_research_run_v2_r08 BEFORE DELETE OR UPDATE ON ai.research_run_v2 FOR EACH ROW EXECUTE FUNCTION ai.protect_research_run_v2_r08();


--
-- Name: training_job_attempt protect_terminal_training_attempt; Type: TRIGGER; Schema: ai; Owner: -
--

CREATE TRIGGER protect_terminal_training_attempt BEFORE DELETE OR UPDATE ON ai.training_job_attempt FOR EACH ROW EXECUTE FUNCTION ai.protect_terminal_training_attempt();


--
-- Name: rule_version trg_ai_rule_version_content_immutable; Type: TRIGGER; Schema: ai; Owner: -
--

CREATE TRIGGER trg_ai_rule_version_content_immutable BEFORE UPDATE ON ai.rule_version FOR EACH ROW EXECUTE FUNCTION ai.prevent_published_rule_version_content_update();


--
-- Name: input_field_version trg_input_field_version_published_binding; Type: TRIGGER; Schema: ai; Owner: -
--

CREATE TRIGGER trg_input_field_version_published_binding BEFORE INSERT OR UPDATE OF status, value_type, standard_field_dictionary_id, definition_jsonb ON ai.input_field_version FOR EACH ROW EXECUTE FUNCTION ai.guard_published_input_field_binding();


--
-- Name: model_version validate_model_domain_policy; Type: TRIGGER; Schema: ai; Owner: -
--

CREATE TRIGGER validate_model_domain_policy BEFORE INSERT OR UPDATE OF domain_policy_version_id ON ai.model_version FOR EACH ROW EXECUTE FUNCTION ai.validate_model_domain_policy();


--
-- Name: prediction_target validate_prediction_target_policy_pointer; Type: TRIGGER; Schema: ai; Owner: -
--

CREATE TRIGGER validate_prediction_target_policy_pointer BEFORE INSERT OR UPDATE OF current_quality_policy_version_id, current_domain_policy_version_id ON ai.prediction_target FOR EACH ROW EXECUTE FUNCTION ai.validate_prediction_target_policy_pointer();


--
-- Name: confirmed_submission_item immutable_confirmed_submission_item; Type: TRIGGER; Schema: data; Owner: -
--

CREATE TRIGGER immutable_confirmed_submission_item BEFORE DELETE OR UPDATE ON data.confirmed_submission_item FOR EACH ROW EXECUTE FUNCTION ai.reject_immutable_change();


--
-- Name: publication_release immutable_data_publication_release; Type: TRIGGER; Schema: data; Owner: -
--

CREATE TRIGGER immutable_data_publication_release BEFORE DELETE OR UPDATE ON data.publication_release FOR EACH ROW EXECUTE FUNCTION data.protect_publication_snapshot();


--
-- Name: publication_snapshot immutable_data_publication_snapshot; Type: TRIGGER; Schema: data; Owner: -
--

CREATE TRIGGER immutable_data_publication_snapshot BEFORE DELETE OR UPDATE ON data.publication_snapshot FOR EACH ROW EXECUTE FUNCTION data.protect_publication_snapshot();


--
-- Name: organization trg_ai_employee_defaults_after_organization_insert; Type: TRIGGER; Schema: iam; Owner: -
--

CREATE TRIGGER trg_ai_employee_defaults_after_organization_insert AFTER INSERT ON iam.organization FOR EACH ROW EXECUTE FUNCTION ai.provision_ai_employee_defaults_for_new_org();


--
-- Name: document_version trg_kb_document_version_sha256; Type: TRIGGER; Schema: kb; Owner: -
--

CREATE TRIGGER trg_kb_document_version_sha256 BEFORE INSERT ON kb.document_version FOR EACH ROW EXECUTE FUNCTION kb.reject_duplicate_version_sha256();


--
-- Name: async_job bounded_async_admission; Type: TRIGGER; Schema: ops; Owner: -
--

CREATE TRIGGER bounded_async_admission BEFORE INSERT OR UPDATE OF status, organization_id ON ops.async_job FOR EACH ROW EXECUTE FUNCTION ops.enforce_async_capacity();


--
-- Name: experiment_ai_source_evidence immutable_experiment_ai_evidence; Type: TRIGGER; Schema: rnd; Owner: -
--

CREATE TRIGGER immutable_experiment_ai_evidence BEFORE DELETE OR UPDATE ON rnd.experiment_ai_source_evidence FOR EACH ROW EXECUTE FUNCTION ai.reject_immutable_change();


--
-- Name: experiment_source_reference immutable_experiment_source_reference; Type: TRIGGER; Schema: rnd; Owner: -
--

CREATE TRIGGER immutable_experiment_source_reference BEFORE DELETE OR UPDATE ON rnd.experiment_source_reference FOR EACH ROW EXECUTE FUNCTION ai.reject_immutable_change();


--
-- Name: ai_call_audit ai_call_audit_actor_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.ai_call_audit
    ADD CONSTRAINT ai_call_audit_actor_id_fkey FOREIGN KEY (actor_id) REFERENCES iam.app_user(id);


--
-- Name: ai_call_audit ai_call_audit_conversation_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.ai_call_audit
    ADD CONSTRAINT ai_call_audit_conversation_id_fkey FOREIGN KEY (conversation_id) REFERENCES ai.assistant_conversation(id) ON DELETE SET NULL;


--
-- Name: ai_call_audit ai_call_audit_organization_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.ai_call_audit
    ADD CONSTRAINT ai_call_audit_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: artifact artifact_organization_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.artifact
    ADD CONSTRAINT artifact_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: artifact artifact_organization_id_training_job_attempt_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.artifact
    ADD CONSTRAINT artifact_organization_id_training_job_attempt_id_fkey FOREIGN KEY (organization_id, training_job_attempt_id) REFERENCES ai.training_job_attempt(organization_id, id);


--
-- Name: artifact artifact_organization_id_training_snapshot_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.artifact
    ADD CONSTRAINT artifact_organization_id_training_snapshot_id_fkey FOREIGN KEY (organization_id, training_snapshot_id) REFERENCES ai.training_snapshot(organization_id, id);


--
-- Name: artifact artifact_organization_id_workbench_run_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.artifact
    ADD CONSTRAINT artifact_organization_id_workbench_run_id_fkey FOREIGN KEY (organization_id, workbench_run_id) REFERENCES ai.modeling_workbench_run(organization_id, id);


--
-- Name: assistant_conversation assistant_conversation_created_by_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.assistant_conversation
    ADD CONSTRAINT assistant_conversation_created_by_fkey FOREIGN KEY (created_by) REFERENCES iam.app_user(id);


--
-- Name: assistant_conversation assistant_conversation_organization_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.assistant_conversation
    ADD CONSTRAINT assistant_conversation_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: assistant_message assistant_message_conversation_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.assistant_message
    ADD CONSTRAINT assistant_message_conversation_id_fkey FOREIGN KEY (conversation_id) REFERENCES ai.assistant_conversation(id) ON DELETE CASCADE;


--
-- Name: configuration_command_receipt configuration_command_receipt_created_by_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.configuration_command_receipt
    ADD CONSTRAINT configuration_command_receipt_created_by_fkey FOREIGN KEY (created_by) REFERENCES iam.app_user(id);


--
-- Name: configuration_command_receipt configuration_command_receipt_organization_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.configuration_command_receipt
    ADD CONSTRAINT configuration_command_receipt_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: context_snapshot context_snapshot_actor_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.context_snapshot
    ADD CONSTRAINT context_snapshot_actor_id_fkey FOREIGN KEY (actor_id) REFERENCES iam.app_user(id);


--
-- Name: context_snapshot context_snapshot_organization_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.context_snapshot
    ADD CONSTRAINT context_snapshot_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: data_review data_review_assigned_to_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.data_review
    ADD CONSTRAINT data_review_assigned_to_fkey FOREIGN KEY (assigned_to) REFERENCES iam.app_user(id);


--
-- Name: data_review_decision data_review_decision_decided_by_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.data_review_decision
    ADD CONSTRAINT data_review_decision_decided_by_fkey FOREIGN KEY (decided_by) REFERENCES iam.app_user(id);


--
-- Name: data_review_decision data_review_decision_organization_id_data_review_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.data_review_decision
    ADD CONSTRAINT data_review_decision_organization_id_data_review_id_fkey FOREIGN KEY (organization_id, data_review_id) REFERENCES ai.data_review(organization_id, id);


--
-- Name: data_review_decision data_review_decision_organization_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.data_review_decision
    ADD CONSTRAINT data_review_decision_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: data_review data_review_organization_id_eligibility_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.data_review
    ADD CONSTRAINT data_review_organization_id_eligibility_id_fkey FOREIGN KEY (organization_id, eligibility_id) REFERENCES ai.training_eligibility(organization_id, id);


--
-- Name: data_review data_review_organization_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.data_review
    ADD CONSTRAINT data_review_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: data_review data_review_organization_id_identity_issue_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.data_review
    ADD CONSTRAINT data_review_organization_id_identity_issue_id_fkey FOREIGN KEY (organization_id, identity_issue_id) REFERENCES ai.sample_identity_issue(organization_id, id);


--
-- Name: eligibility_evaluation_run eligibility_evaluation_run_organization_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.eligibility_evaluation_run
    ADD CONSTRAINT eligibility_evaluation_run_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: eligibility_evaluation_run eligibility_evaluation_run_organization_id_material_dictio_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.eligibility_evaluation_run
    ADD CONSTRAINT eligibility_evaluation_run_organization_id_material_dictio_fkey FOREIGN KEY (organization_id, material_dictionary_version_id) REFERENCES ai.material_dictionary_version(organization_id, id);


--
-- Name: eligibility_evaluation_run eligibility_evaluation_run_organization_id_modeling_policy_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.eligibility_evaluation_run
    ADD CONSTRAINT eligibility_evaluation_run_organization_id_modeling_policy_fkey FOREIGN KEY (organization_id, modeling_policy_version_id) REFERENCES ai.modeling_policy_version(organization_id, id);


--
-- Name: eligibility_evaluation_run eligibility_evaluation_run_organization_id_target_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.eligibility_evaluation_run
    ADD CONSTRAINT eligibility_evaluation_run_organization_id_target_id_fkey FOREIGN KEY (organization_id, target_id) REFERENCES ai.prediction_target(organization_id, id);


--
-- Name: eligibility_evaluation_run eligibility_evaluation_run_organization_id_target_id_input_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.eligibility_evaluation_run
    ADD CONSTRAINT eligibility_evaluation_run_organization_id_target_id_input_fkey FOREIGN KEY (organization_id, target_id, input_scheme_id) REFERENCES ai.input_scheme(organization_id, target_id, id);


--
-- Name: eligibility_evaluation_run eligibility_evaluation_run_organization_id_target_id_targe_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.eligibility_evaluation_run
    ADD CONSTRAINT eligibility_evaluation_run_organization_id_target_id_targe_fkey FOREIGN KEY (organization_id, target_id, target_version_id) REFERENCES ai.target_version(organization_id, target_id, id);


--
-- Name: eligibility_evaluation_run eligibility_evaluation_run_requested_by_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.eligibility_evaluation_run
    ADD CONSTRAINT eligibility_evaluation_run_requested_by_fkey FOREIGN KEY (requested_by) REFERENCES iam.app_user(id);


--
-- Name: employee_scheduled_task employee_scheduled_task_organization_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.employee_scheduled_task
    ADD CONSTRAINT employee_scheduled_task_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id) ON DELETE CASCADE;


--
-- Name: employee_scheduled_task employee_scheduled_task_updated_by_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.employee_scheduled_task
    ADD CONSTRAINT employee_scheduled_task_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES iam.app_user(id);


--
-- Name: experiment_analysis experiment_analysis_created_by_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.experiment_analysis
    ADD CONSTRAINT experiment_analysis_created_by_fkey FOREIGN KEY (created_by) REFERENCES iam.app_user(id);


--
-- Name: experiment_analysis experiment_analysis_organization_id_context_snapshot_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.experiment_analysis
    ADD CONSTRAINT experiment_analysis_organization_id_context_snapshot_id_fkey FOREIGN KEY (organization_id, context_snapshot_id) REFERENCES ai.context_snapshot(organization_id, id);


--
-- Name: experiment_analysis experiment_analysis_organization_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.experiment_analysis
    ADD CONSTRAINT experiment_analysis_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: experiment_analysis experiment_analysis_organization_id_result_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.experiment_analysis
    ADD CONSTRAINT experiment_analysis_organization_id_result_id_fkey FOREIGN KEY (organization_id, result_id) REFERENCES ai.result(organization_id, id);


--
-- Name: experiment_analysis experiment_analysis_parent_analysis_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.experiment_analysis
    ADD CONSTRAINT experiment_analysis_parent_analysis_id_fkey FOREIGN KEY (parent_analysis_id) REFERENCES ai.experiment_analysis(id);


--
-- Name: fact_projection_receipt fact_projection_receipt_organization_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.fact_projection_receipt
    ADD CONSTRAINT fact_projection_receipt_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: result_evidence fk_ai_result_evidence_occurrence; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.result_evidence
    ADD CONSTRAINT fk_ai_result_evidence_occurrence FOREIGN KEY (occurrence_id) REFERENCES ai.result_occurrence(id);


--
-- Name: rule_definition fk_ai_rule_definition_organization; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.rule_definition
    ADD CONSTRAINT fk_ai_rule_definition_organization FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: rule_version fk_ai_rule_version_definition; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.rule_version
    ADD CONSTRAINT fk_ai_rule_version_definition FOREIGN KEY (organization_id, rule_code) REFERENCES ai.rule_definition(organization_id, rule_code) ON DELETE CASCADE;


--
-- Name: rule_version fk_ai_rule_version_organization; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.rule_version
    ADD CONSTRAINT fk_ai_rule_version_organization FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: input_field input_field_created_by_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.input_field
    ADD CONSTRAINT input_field_created_by_fkey FOREIGN KEY (created_by) REFERENCES iam.app_user(id);


--
-- Name: input_field input_field_current_version_fk; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.input_field
    ADD CONSTRAINT input_field_current_version_fk FOREIGN KEY (organization_id, id, current_version_id) REFERENCES ai.input_field_version(organization_id, input_field_id, id);


--
-- Name: input_field input_field_organization_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.input_field
    ADD CONSTRAINT input_field_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: input_field input_field_updated_by_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.input_field
    ADD CONSTRAINT input_field_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES iam.app_user(id);


--
-- Name: input_field_version input_field_version_created_by_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.input_field_version
    ADD CONSTRAINT input_field_version_created_by_fkey FOREIGN KEY (created_by) REFERENCES iam.app_user(id);


--
-- Name: input_field_version input_field_version_organization_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.input_field_version
    ADD CONSTRAINT input_field_version_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: input_field_version input_field_version_organization_id_input_field_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.input_field_version
    ADD CONSTRAINT input_field_version_organization_id_input_field_id_fkey FOREIGN KEY (organization_id, input_field_id) REFERENCES ai.input_field(organization_id, id);


--
-- Name: input_field_version input_field_version_standard_field_dictionary_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.input_field_version
    ADD CONSTRAINT input_field_version_standard_field_dictionary_id_fkey FOREIGN KEY (standard_field_dictionary_id) REFERENCES tpl.standard_field_dictionary(id);


--
-- Name: input_scheme input_scheme_created_by_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.input_scheme
    ADD CONSTRAINT input_scheme_created_by_fkey FOREIGN KEY (created_by) REFERENCES iam.app_user(id);


--
-- Name: input_scheme_field input_scheme_field_organization_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.input_scheme_field
    ADD CONSTRAINT input_scheme_field_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: input_scheme_field input_scheme_field_organization_id_input_field_version_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.input_scheme_field
    ADD CONSTRAINT input_scheme_field_organization_id_input_field_version_id_fkey FOREIGN KEY (organization_id, input_field_version_id) REFERENCES ai.input_field_version(organization_id, id);


--
-- Name: input_scheme_field input_scheme_field_organization_id_input_scheme_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.input_scheme_field
    ADD CONSTRAINT input_scheme_field_organization_id_input_scheme_id_fkey FOREIGN KEY (organization_id, input_scheme_id) REFERENCES ai.input_scheme(organization_id, id);


--
-- Name: input_scheme input_scheme_material_dictionary_fk; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.input_scheme
    ADD CONSTRAINT input_scheme_material_dictionary_fk FOREIGN KEY (organization_id, material_dictionary_version_id) REFERENCES ai.material_dictionary_version(organization_id, id);


--
-- Name: input_scheme input_scheme_organization_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.input_scheme
    ADD CONSTRAINT input_scheme_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: input_scheme input_scheme_organization_id_target_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.input_scheme
    ADD CONSTRAINT input_scheme_organization_id_target_id_fkey FOREIGN KEY (organization_id, target_id) REFERENCES ai.prediction_target(organization_id, id);


--
-- Name: input_scheme input_scheme_organization_id_target_id_target_version_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.input_scheme
    ADD CONSTRAINT input_scheme_organization_id_target_id_target_version_id_fkey FOREIGN KEY (organization_id, target_id, target_version_id) REFERENCES ai.target_version(organization_id, target_id, id);


--
-- Name: input_scheme input_scheme_updated_by_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.input_scheme
    ADD CONSTRAINT input_scheme_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES iam.app_user(id);


--
-- Name: material_dictionary_version material_dictionary_version_created_by_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.material_dictionary_version
    ADD CONSTRAINT material_dictionary_version_created_by_fkey FOREIGN KEY (created_by) REFERENCES iam.app_user(id);


--
-- Name: material_dictionary_version material_dictionary_version_organization_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.material_dictionary_version
    ADD CONSTRAINT material_dictionary_version_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: model_feedback model_feedback_created_by_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.model_feedback
    ADD CONSTRAINT model_feedback_created_by_fkey FOREIGN KEY (created_by) REFERENCES iam.app_user(id);


--
-- Name: model_feedback model_feedback_organization_id_experiment_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.model_feedback
    ADD CONSTRAINT model_feedback_organization_id_experiment_id_fkey FOREIGN KEY (organization_id, experiment_id) REFERENCES rnd.experiment(organization_id, id);


--
-- Name: model_feedback model_feedback_organization_id_experiment_version_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.model_feedback
    ADD CONSTRAINT model_feedback_organization_id_experiment_version_id_fkey FOREIGN KEY (organization_id, experiment_version_id) REFERENCES rnd.experiment_version(organization_id, id);


--
-- Name: model_feedback model_feedback_organization_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.model_feedback
    ADD CONSTRAINT model_feedback_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: model_feedback model_feedback_organization_id_prediction_record_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.model_feedback
    ADD CONSTRAINT model_feedback_organization_id_prediction_record_id_fkey FOREIGN KEY (organization_id, prediction_record_id) REFERENCES ai.prediction_record(organization_id, id);


--
-- Name: model_feedback model_feedback_organization_id_research_candidate_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.model_feedback
    ADD CONSTRAINT model_feedback_organization_id_research_candidate_id_fkey FOREIGN KEY (organization_id, research_candidate_id) REFERENCES ai.research_candidate_v2(organization_id, id);


--
-- Name: model_feedback model_feedback_organization_id_target_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.model_feedback
    ADD CONSTRAINT model_feedback_organization_id_target_id_fkey FOREIGN KEY (organization_id, target_id) REFERENCES ai.prediction_target(organization_id, id);


--
-- Name: model_feedback model_feedback_organization_id_target_id_model_version_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.model_feedback
    ADD CONSTRAINT model_feedback_organization_id_target_id_model_version_id_fkey FOREIGN KEY (organization_id, target_id, model_version_id) REFERENCES ai.model_version(organization_id, target_id, id);


--
-- Name: model_feedback_projection_receipt model_feedback_projection_rec_organization_id_experiment_i_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.model_feedback_projection_receipt
    ADD CONSTRAINT model_feedback_projection_rec_organization_id_experiment_i_fkey FOREIGN KEY (organization_id, experiment_id) REFERENCES rnd.experiment(organization_id, id);


--
-- Name: model_feedback_projection_receipt model_feedback_projection_rec_organization_id_experiment_v_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.model_feedback_projection_receipt
    ADD CONSTRAINT model_feedback_projection_rec_organization_id_experiment_v_fkey FOREIGN KEY (organization_id, experiment_version_id) REFERENCES rnd.experiment_version(organization_id, id);


--
-- Name: model_feedback_projection_receipt model_feedback_projection_receipt_organization_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.model_feedback_projection_receipt
    ADD CONSTRAINT model_feedback_projection_receipt_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: model_feedback model_feedback_sample_revision_fk; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.model_feedback
    ADD CONSTRAINT model_feedback_sample_revision_fk FOREIGN KEY (organization_id, sample_revision_id) REFERENCES ai.sample_revision(organization_id, id);


--
-- Name: model_feedback model_feedback_source_mapping_fk; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.model_feedback
    ADD CONSTRAINT model_feedback_source_mapping_fk FOREIGN KEY (organization_id, source_mapping_version_id) REFERENCES ai.source_mapping_version(organization_id, id);


--
-- Name: model_feedback model_feedback_target_version_fk; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.model_feedback
    ADD CONSTRAINT model_feedback_target_version_fk FOREIGN KEY (organization_id, target_id, target_version_id) REFERENCES ai.target_version(organization_id, target_id, id);


--
-- Name: model_release model_release_actor_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.model_release
    ADD CONSTRAINT model_release_actor_id_fkey FOREIGN KEY (actor_id) REFERENCES iam.app_user(id);


--
-- Name: model_release model_release_organization_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.model_release
    ADD CONSTRAINT model_release_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: model_release model_release_organization_id_target_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.model_release
    ADD CONSTRAINT model_release_organization_id_target_id_fkey FOREIGN KEY (organization_id, target_id) REFERENCES ai.prediction_target(organization_id, id);


--
-- Name: model_release model_release_organization_id_target_id_model_version_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.model_release
    ADD CONSTRAINT model_release_organization_id_target_id_model_version_id_fkey FOREIGN KEY (organization_id, target_id, model_version_id) REFERENCES ai.model_version(organization_id, target_id, id);


--
-- Name: model_release model_release_organization_id_target_id_previous_model_ver_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.model_release
    ADD CONSTRAINT model_release_organization_id_target_id_previous_model_ver_fkey FOREIGN KEY (organization_id, target_id, previous_model_version_id) REFERENCES ai.model_version(organization_id, target_id, id);


--
-- Name: model_version model_version_created_by_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.model_version
    ADD CONSTRAINT model_version_created_by_fkey FOREIGN KEY (created_by) REFERENCES iam.app_user(id);


--
-- Name: model_version model_version_domain_policy_fk; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.model_version
    ADD CONSTRAINT model_version_domain_policy_fk FOREIGN KEY (organization_id, target_id, domain_policy_version_id) REFERENCES ai.modeling_policy_version(organization_id, target_id, id);


--
-- Name: model_version model_version_organization_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.model_version
    ADD CONSTRAINT model_version_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: model_version model_version_organization_id_model_artifact_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.model_version
    ADD CONSTRAINT model_version_organization_id_model_artifact_id_fkey FOREIGN KEY (organization_id, model_artifact_id) REFERENCES ai.artifact(organization_id, id);


--
-- Name: model_version model_version_organization_id_target_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.model_version
    ADD CONSTRAINT model_version_organization_id_target_id_fkey FOREIGN KEY (organization_id, target_id) REFERENCES ai.prediction_target(organization_id, id);


--
-- Name: model_version model_version_organization_id_target_id_input_scheme_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.model_version
    ADD CONSTRAINT model_version_organization_id_target_id_input_scheme_id_fkey FOREIGN KEY (organization_id, target_id, input_scheme_id) REFERENCES ai.input_scheme(organization_id, target_id, id);


--
-- Name: model_version model_version_organization_id_target_id_target_version_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.model_version
    ADD CONSTRAINT model_version_organization_id_target_id_target_version_id_fkey FOREIGN KEY (organization_id, target_id, target_version_id) REFERENCES ai.target_version(organization_id, target_id, id);


--
-- Name: model_version model_version_organization_id_training_job_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.model_version
    ADD CONSTRAINT model_version_organization_id_training_job_id_fkey FOREIGN KEY (organization_id, training_job_id) REFERENCES ai.training_job(organization_id, id);


--
-- Name: model_version model_version_organization_id_training_snapshot_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.model_version
    ADD CONSTRAINT model_version_organization_id_training_snapshot_id_fkey FOREIGN KEY (organization_id, training_snapshot_id) REFERENCES ai.training_snapshot(organization_id, id);


--
-- Name: model_version model_version_organization_id_workbench_run_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.model_version
    ADD CONSTRAINT model_version_organization_id_workbench_run_id_fkey FOREIGN KEY (organization_id, workbench_run_id) REFERENCES ai.modeling_workbench_run(organization_id, id);


--
-- Name: modeling_policy_version modeling_policy_version_created_by_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.modeling_policy_version
    ADD CONSTRAINT modeling_policy_version_created_by_fkey FOREIGN KEY (created_by) REFERENCES iam.app_user(id);


--
-- Name: modeling_policy_version modeling_policy_version_organization_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.modeling_policy_version
    ADD CONSTRAINT modeling_policy_version_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: modeling_policy_version modeling_policy_version_organization_id_target_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.modeling_policy_version
    ADD CONSTRAINT modeling_policy_version_organization_id_target_id_fkey FOREIGN KEY (organization_id, target_id) REFERENCES ai.prediction_target(organization_id, id);


--
-- Name: modeling_policy_version modeling_policy_version_published_by_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.modeling_policy_version
    ADD CONSTRAINT modeling_policy_version_published_by_fkey FOREIGN KEY (published_by) REFERENCES iam.app_user(id);


--
-- Name: modeling_workbench_run modeling_workbench_run_created_by_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.modeling_workbench_run
    ADD CONSTRAINT modeling_workbench_run_created_by_fkey FOREIGN KEY (created_by) REFERENCES iam.app_user(id);


--
-- Name: modeling_workbench_run modeling_workbench_run_ops_job_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.modeling_workbench_run
    ADD CONSTRAINT modeling_workbench_run_ops_job_id_fkey FOREIGN KEY (ops_job_id) REFERENCES ops.async_job(id);


--
-- Name: modeling_workbench_run modeling_workbench_run_organization_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.modeling_workbench_run
    ADD CONSTRAINT modeling_workbench_run_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: operation_manual_chunk operation_manual_chunk_manual_version_embedding_model_embe_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.operation_manual_chunk
    ADD CONSTRAINT operation_manual_chunk_manual_version_embedding_model_embe_fkey FOREIGN KEY (manual_version, embedding_model, embedding_dimension) REFERENCES ai.operation_manual_index(manual_version, embedding_model, embedding_dimension);


--
-- Name: prediction_record prediction_record_created_by_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.prediction_record
    ADD CONSTRAINT prediction_record_created_by_fkey FOREIGN KEY (created_by) REFERENCES iam.app_user(id);


--
-- Name: prediction_record prediction_record_organization_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.prediction_record
    ADD CONSTRAINT prediction_record_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: prediction_target prediction_target_created_by_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.prediction_target
    ADD CONSTRAINT prediction_target_created_by_fkey FOREIGN KEY (created_by) REFERENCES iam.app_user(id);


--
-- Name: prediction_target prediction_target_current_input_scheme_fk; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.prediction_target
    ADD CONSTRAINT prediction_target_current_input_scheme_fk FOREIGN KEY (organization_id, id, current_input_scheme_id) REFERENCES ai.input_scheme(organization_id, target_id, id);


--
-- Name: prediction_target prediction_target_current_version_fk; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.prediction_target
    ADD CONSTRAINT prediction_target_current_version_fk FOREIGN KEY (organization_id, id, current_version_id) REFERENCES ai.target_version(organization_id, target_id, id);


--
-- Name: prediction_target prediction_target_domain_policy_fk; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.prediction_target
    ADD CONSTRAINT prediction_target_domain_policy_fk FOREIGN KEY (organization_id, id, current_domain_policy_version_id) REFERENCES ai.modeling_policy_version(organization_id, target_id, id);


--
-- Name: prediction_target prediction_target_organization_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.prediction_target
    ADD CONSTRAINT prediction_target_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: prediction_target prediction_target_quality_policy_fk; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.prediction_target
    ADD CONSTRAINT prediction_target_quality_policy_fk FOREIGN KEY (organization_id, id, current_quality_policy_version_id) REFERENCES ai.modeling_policy_version(organization_id, target_id, id);


--
-- Name: prediction_target prediction_target_updated_by_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.prediction_target
    ADD CONSTRAINT prediction_target_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES iam.app_user(id);


--
-- Name: research_candidate_history_observation_v2 research_candidate_history_ob_organization_id_research_run_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.research_candidate_history_observation_v2
    ADD CONSTRAINT research_candidate_history_ob_organization_id_research_run_fkey FOREIGN KEY (organization_id, research_run_id, research_candidate_id) REFERENCES ai.research_candidate_v2(organization_id, research_run_id, id);


--
-- Name: research_candidate_history_observation_v2 research_candidate_history_observation_v2_created_by_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.research_candidate_history_observation_v2
    ADD CONSTRAINT research_candidate_history_observation_v2_created_by_fkey FOREIGN KEY (created_by) REFERENCES iam.app_user(id);


--
-- Name: research_candidate_history_observation_v2 research_candidate_history_observation_v2_organization_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.research_candidate_history_observation_v2
    ADD CONSTRAINT research_candidate_history_observation_v2_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: research_candidate_history_observation_v2 research_candidate_history_observation_v2_updated_by_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.research_candidate_history_observation_v2
    ADD CONSTRAINT research_candidate_history_observation_v2_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES iam.app_user(id);


--
-- Name: research_candidate_observation_v2 research_candidate_observatio_organization_id_research_run_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.research_candidate_observation_v2
    ADD CONSTRAINT research_candidate_observatio_organization_id_research_run_fkey FOREIGN KEY (organization_id, research_run_id, research_candidate_id) REFERENCES ai.research_candidate_v2(organization_id, research_run_id, id);


--
-- Name: research_candidate_observation_v2 research_candidate_observation_v2_created_by_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.research_candidate_observation_v2
    ADD CONSTRAINT research_candidate_observation_v2_created_by_fkey FOREIGN KEY (created_by) REFERENCES iam.app_user(id);


--
-- Name: research_candidate_observation_v2 research_candidate_observation_v2_organization_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.research_candidate_observation_v2
    ADD CONSTRAINT research_candidate_observation_v2_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: research_candidate_observation_v2 research_candidate_observation_v2_updated_by_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.research_candidate_observation_v2
    ADD CONSTRAINT research_candidate_observation_v2_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES iam.app_user(id);


--
-- Name: research_candidate_observation_v2 research_candidate_observation_v_organization_id_target_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.research_candidate_observation_v2
    ADD CONSTRAINT research_candidate_observation_v_organization_id_target_id_fkey FOREIGN KEY (organization_id, target_id) REFERENCES ai.prediction_target(organization_id, id);


--
-- Name: research_candidate_v2 research_candidate_v2_organization_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.research_candidate_v2
    ADD CONSTRAINT research_candidate_v2_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: research_candidate_v2 research_candidate_v2_organization_id_research_run_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.research_candidate_v2
    ADD CONSTRAINT research_candidate_v2_organization_id_research_run_id_fkey FOREIGN KEY (organization_id, research_run_id) REFERENCES ai.research_run_v2(organization_id, id);


--
-- Name: research_experiment_link_v2 research_experiment_link_v2_created_by_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.research_experiment_link_v2
    ADD CONSTRAINT research_experiment_link_v2_created_by_fkey FOREIGN KEY (created_by) REFERENCES iam.app_user(id);


--
-- Name: research_experiment_link_v2 research_experiment_link_v2_evidence_fk; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.research_experiment_link_v2
    ADD CONSTRAINT research_experiment_link_v2_evidence_fk FOREIGN KEY (organization_id, source_evidence_id) REFERENCES rnd.experiment_ai_source_evidence(organization_id, id);


--
-- Name: research_experiment_link_v2 research_experiment_link_v2_organization_id_experiment_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.research_experiment_link_v2
    ADD CONSTRAINT research_experiment_link_v2_organization_id_experiment_id_fkey FOREIGN KEY (organization_id, experiment_id) REFERENCES rnd.experiment(organization_id, id);


--
-- Name: research_experiment_link_v2 research_experiment_link_v2_organization_id_experiment_ver_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.research_experiment_link_v2
    ADD CONSTRAINT research_experiment_link_v2_organization_id_experiment_ver_fkey FOREIGN KEY (organization_id, experiment_version_id) REFERENCES rnd.experiment_version(organization_id, id);


--
-- Name: research_experiment_link_v2 research_experiment_link_v2_organization_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.research_experiment_link_v2
    ADD CONSTRAINT research_experiment_link_v2_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: research_experiment_link_v2 research_experiment_link_v2_organization_id_research_run__fkey1; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.research_experiment_link_v2
    ADD CONSTRAINT research_experiment_link_v2_organization_id_research_run__fkey1 FOREIGN KEY (organization_id, research_run_id, research_candidate_id) REFERENCES ai.research_candidate_v2(organization_id, research_run_id, id);


--
-- Name: research_experiment_link_v2 research_experiment_link_v2_organization_id_research_run_i_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.research_experiment_link_v2
    ADD CONSTRAINT research_experiment_link_v2_organization_id_research_run_i_fkey FOREIGN KEY (organization_id, research_run_id) REFERENCES ai.research_run_v2(organization_id, id);


--
-- Name: research_run_v2 research_run_v2_created_by_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.research_run_v2
    ADD CONSTRAINT research_run_v2_created_by_fkey FOREIGN KEY (created_by) REFERENCES iam.app_user(id);


--
-- Name: research_run_v2 research_run_v2_organization_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.research_run_v2
    ADD CONSTRAINT research_run_v2_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: result_evidence result_evidence_organization_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.result_evidence
    ADD CONSTRAINT result_evidence_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: result_evidence result_evidence_organization_id_result_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.result_evidence
    ADD CONSTRAINT result_evidence_organization_id_result_id_fkey FOREIGN KEY (organization_id, result_id) REFERENCES ai.result(organization_id, id) ON DELETE CASCADE;


--
-- Name: result_feedback result_feedback_actor_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.result_feedback
    ADD CONSTRAINT result_feedback_actor_id_fkey FOREIGN KEY (actor_id) REFERENCES iam.app_user(id);


--
-- Name: result_feedback result_feedback_organization_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.result_feedback
    ADD CONSTRAINT result_feedback_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: result_feedback result_feedback_organization_id_result_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.result_feedback
    ADD CONSTRAINT result_feedback_organization_id_result_id_fkey FOREIGN KEY (organization_id, result_id) REFERENCES ai.result(organization_id, id) ON DELETE CASCADE;


--
-- Name: result_occurrence result_occurrence_organization_id_context_snapshot_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.result_occurrence
    ADD CONSTRAINT result_occurrence_organization_id_context_snapshot_id_fkey FOREIGN KEY (organization_id, context_snapshot_id) REFERENCES ai.context_snapshot(organization_id, id);


--
-- Name: result_occurrence result_occurrence_organization_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.result_occurrence
    ADD CONSTRAINT result_occurrence_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: result_occurrence result_occurrence_organization_id_result_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.result_occurrence
    ADD CONSTRAINT result_occurrence_organization_id_result_id_fkey FOREIGN KEY (organization_id, result_id) REFERENCES ai.result(organization_id, id) ON DELETE CASCADE;


--
-- Name: result result_organization_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.result
    ADD CONSTRAINT result_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: rule_version rule_version_created_by_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.rule_version
    ADD CONSTRAINT rule_version_created_by_fkey FOREIGN KEY (created_by) REFERENCES iam.app_user(id);


--
-- Name: sample_identity_issue sample_identity_issue_organization_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.sample_identity_issue
    ADD CONSTRAINT sample_identity_issue_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: sample_identity_issue sample_identity_issue_organization_id_left_sample_source_i_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.sample_identity_issue
    ADD CONSTRAINT sample_identity_issue_organization_id_left_sample_source_i_fkey FOREIGN KEY (organization_id, left_sample_source_id) REFERENCES ai.sample_source(organization_id, id);


--
-- Name: sample_identity_issue sample_identity_issue_organization_id_right_sample_source__fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.sample_identity_issue
    ADD CONSTRAINT sample_identity_issue_organization_id_right_sample_source__fkey FOREIGN KEY (organization_id, right_sample_source_id) REFERENCES ai.sample_source(organization_id, id);


--
-- Name: sample_identity_issue sample_identity_issue_organization_id_training_sample_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.sample_identity_issue
    ADD CONSTRAINT sample_identity_issue_organization_id_training_sample_id_fkey FOREIGN KEY (organization_id, training_sample_id) REFERENCES ai.training_sample(organization_id, id);


--
-- Name: sample_identity_issue sample_identity_issue_resolved_by_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.sample_identity_issue
    ADD CONSTRAINT sample_identity_issue_resolved_by_fkey FOREIGN KEY (resolved_by) REFERENCES iam.app_user(id);


--
-- Name: sample_revision sample_revision_organization_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.sample_revision
    ADD CONSTRAINT sample_revision_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: sample_revision sample_revision_organization_id_training_sample_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.sample_revision
    ADD CONSTRAINT sample_revision_organization_id_training_sample_id_fkey FOREIGN KEY (organization_id, training_sample_id) REFERENCES ai.training_sample(organization_id, id);


--
-- Name: sample_revision sample_revision_organization_id_training_sample_id_sample__fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.sample_revision
    ADD CONSTRAINT sample_revision_organization_id_training_sample_id_sample__fkey FOREIGN KEY (organization_id, training_sample_id, sample_source_id) REFERENCES ai.sample_source(organization_id, training_sample_id, id);


--
-- Name: sample_source sample_source_organization_id_confirmed_submission_item_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.sample_source
    ADD CONSTRAINT sample_source_organization_id_confirmed_submission_item_id_fkey FOREIGN KEY (organization_id, confirmed_submission_item_id) REFERENCES data.confirmed_submission_item(organization_id, id);


--
-- Name: sample_source sample_source_organization_id_experiment_version_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.sample_source
    ADD CONSTRAINT sample_source_organization_id_experiment_version_id_fkey FOREIGN KEY (organization_id, experiment_version_id) REFERENCES rnd.experiment_version(organization_id, id);


--
-- Name: sample_source sample_source_organization_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.sample_source
    ADD CONSTRAINT sample_source_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: sample_source sample_source_organization_id_training_sample_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.sample_source
    ADD CONSTRAINT sample_source_organization_id_training_sample_id_fkey FOREIGN KEY (organization_id, training_sample_id) REFERENCES ai.training_sample(organization_id, id);


--
-- Name: signal signal_organization_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.signal
    ADD CONSTRAINT signal_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: source_mapping_version source_mapping_version_created_by_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.source_mapping_version
    ADD CONSTRAINT source_mapping_version_created_by_fkey FOREIGN KEY (created_by) REFERENCES iam.app_user(id);


--
-- Name: source_mapping_version source_mapping_version_organization_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.source_mapping_version
    ADD CONSTRAINT source_mapping_version_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: source_mapping_version source_mapping_version_organization_id_target_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.source_mapping_version
    ADD CONSTRAINT source_mapping_version_organization_id_target_id_fkey FOREIGN KEY (organization_id, target_id) REFERENCES ai.prediction_target(organization_id, id);


--
-- Name: source_mapping_version source_mapping_version_organization_id_target_id_target_ve_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.source_mapping_version
    ADD CONSTRAINT source_mapping_version_organization_id_target_id_target_ve_fkey FOREIGN KEY (organization_id, target_id, target_version_id) REFERENCES ai.target_version(organization_id, target_id, id);


--
-- Name: target_version target_version_catalog_proposal_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.target_version
    ADD CONSTRAINT target_version_catalog_proposal_id_fkey FOREIGN KEY (catalog_proposal_id) REFERENCES tpl.standard_field_catalog_proposal(id) ON DELETE SET NULL;


--
-- Name: target_version target_version_created_by_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.target_version
    ADD CONSTRAINT target_version_created_by_fkey FOREIGN KEY (created_by) REFERENCES iam.app_user(id);


--
-- Name: target_version target_version_organization_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.target_version
    ADD CONSTRAINT target_version_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: target_version target_version_organization_id_target_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.target_version
    ADD CONSTRAINT target_version_organization_id_target_id_fkey FOREIGN KEY (organization_id, target_id) REFERENCES ai.prediction_target(organization_id, id);


--
-- Name: target_version target_version_result_standard_field_dictionary_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.target_version
    ADD CONSTRAINT target_version_result_standard_field_dictionary_id_fkey FOREIGN KEY (result_standard_field_dictionary_id) REFERENCES tpl.standard_field_dictionary(id);


--
-- Name: training_eligibility training_eligibility_dictionary_fk; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.training_eligibility
    ADD CONSTRAINT training_eligibility_dictionary_fk FOREIGN KEY (organization_id, material_dictionary_version_id) REFERENCES ai.material_dictionary_version(organization_id, id);


--
-- Name: training_eligibility training_eligibility_mapping_fk; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.training_eligibility
    ADD CONSTRAINT training_eligibility_mapping_fk FOREIGN KEY (organization_id, source_mapping_version_id) REFERENCES ai.source_mapping_version(organization_id, id);


--
-- Name: training_eligibility training_eligibility_organization_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.training_eligibility
    ADD CONSTRAINT training_eligibility_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: training_eligibility training_eligibility_organization_id_input_scheme_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.training_eligibility
    ADD CONSTRAINT training_eligibility_organization_id_input_scheme_id_fkey FOREIGN KEY (organization_id, input_scheme_id) REFERENCES ai.input_scheme(organization_id, id);


--
-- Name: training_eligibility training_eligibility_organization_id_sample_revision_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.training_eligibility
    ADD CONSTRAINT training_eligibility_organization_id_sample_revision_id_fkey FOREIGN KEY (organization_id, sample_revision_id) REFERENCES ai.sample_revision(organization_id, id);


--
-- Name: training_eligibility training_eligibility_organization_id_target_version_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.training_eligibility
    ADD CONSTRAINT training_eligibility_organization_id_target_version_id_fkey FOREIGN KEY (organization_id, target_version_id) REFERENCES ai.target_version(organization_id, id);


--
-- Name: training_eligibility training_eligibility_policy_fk; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.training_eligibility
    ADD CONSTRAINT training_eligibility_policy_fk FOREIGN KEY (organization_id, modeling_policy_version_id) REFERENCES ai.modeling_policy_version(organization_id, id);


--
-- Name: training_eligibility training_eligibility_run_fk; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.training_eligibility
    ADD CONSTRAINT training_eligibility_run_fk FOREIGN KEY (organization_id, evaluation_run_id) REFERENCES ai.eligibility_evaluation_run(organization_id, id);


--
-- Name: training_job training_job_active_attempt_fk; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.training_job
    ADD CONSTRAINT training_job_active_attempt_fk FOREIGN KEY (organization_id, active_attempt_id) REFERENCES ai.training_job_attempt(organization_id, id);


--
-- Name: training_job_attempt training_job_attempt_organization_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.training_job_attempt
    ADD CONSTRAINT training_job_attempt_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: training_job_attempt training_job_attempt_organization_id_training_job_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.training_job_attempt
    ADD CONSTRAINT training_job_attempt_organization_id_training_job_id_fkey FOREIGN KEY (organization_id, training_job_id) REFERENCES ai.training_job(organization_id, id);


--
-- Name: training_job training_job_ops_job_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.training_job
    ADD CONSTRAINT training_job_ops_job_id_fkey FOREIGN KEY (ops_job_id) REFERENCES ops.async_job(id);


--
-- Name: training_job training_job_organization_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.training_job
    ADD CONSTRAINT training_job_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: training_job training_job_organization_id_training_snapshot_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.training_job
    ADD CONSTRAINT training_job_organization_id_training_snapshot_id_fkey FOREIGN KEY (organization_id, training_snapshot_id) REFERENCES ai.training_snapshot(organization_id, id);


--
-- Name: training_job training_job_policy_fk; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.training_job
    ADD CONSTRAINT training_job_policy_fk FOREIGN KEY (organization_id, modeling_policy_version_id) REFERENCES ai.modeling_policy_version(organization_id, id);


--
-- Name: training_job training_job_requested_by_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.training_job
    ADD CONSTRAINT training_job_requested_by_fkey FOREIGN KEY (requested_by) REFERENCES iam.app_user(id);


--
-- Name: training_job training_job_target_fk; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.training_job
    ADD CONSTRAINT training_job_target_fk FOREIGN KEY (organization_id, target_id) REFERENCES ai.prediction_target(organization_id, id);


--
-- Name: training_job training_job_target_version_fk; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.training_job
    ADD CONSTRAINT training_job_target_version_fk FOREIGN KEY (organization_id, target_id, target_version_id) REFERENCES ai.target_version(organization_id, target_id, id);


--
-- Name: training_sample training_sample_current_revision_fk; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.training_sample
    ADD CONSTRAINT training_sample_current_revision_fk FOREIGN KEY (organization_id, current_sample_revision_id) REFERENCES ai.sample_revision(organization_id, id);


--
-- Name: training_sample training_sample_organization_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.training_sample
    ADD CONSTRAINT training_sample_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: training_sample training_sample_organization_id_takeover_experiment_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.training_sample
    ADD CONSTRAINT training_sample_organization_id_takeover_experiment_id_fkey FOREIGN KEY (organization_id, takeover_experiment_id) REFERENCES rnd.experiment(organization_id, id);


--
-- Name: training_sample training_sample_organization_id_takeover_experiment_versio_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.training_sample
    ADD CONSTRAINT training_sample_organization_id_takeover_experiment_versio_fkey FOREIGN KEY (organization_id, takeover_experiment_version_id) REFERENCES rnd.experiment_version(organization_id, id);


--
-- Name: training_schedule_intent training_schedule_intent_organization_id_eligibility_run_i_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.training_schedule_intent
    ADD CONSTRAINT training_schedule_intent_organization_id_eligibility_run_i_fkey FOREIGN KEY (organization_id, eligibility_run_id) REFERENCES ai.eligibility_evaluation_run(organization_id, id);


--
-- Name: training_schedule_intent training_schedule_intent_organization_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.training_schedule_intent
    ADD CONSTRAINT training_schedule_intent_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: training_schedule_intent training_schedule_intent_organization_id_target_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.training_schedule_intent
    ADD CONSTRAINT training_schedule_intent_organization_id_target_id_fkey FOREIGN KEY (organization_id, target_id) REFERENCES ai.prediction_target(organization_id, id);


--
-- Name: training_schedule_intent training_schedule_intent_organization_id_target_id_input_s_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.training_schedule_intent
    ADD CONSTRAINT training_schedule_intent_organization_id_target_id_input_s_fkey FOREIGN KEY (organization_id, target_id, input_scheme_id) REFERENCES ai.input_scheme(organization_id, target_id, id);


--
-- Name: training_schedule_intent training_schedule_intent_organization_id_target_id_target__fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.training_schedule_intent
    ADD CONSTRAINT training_schedule_intent_organization_id_target_id_target__fkey FOREIGN KEY (organization_id, target_id, target_version_id) REFERENCES ai.target_version(organization_id, target_id, id);


--
-- Name: training_scheduler_setting training_scheduler_setting_organization_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.training_scheduler_setting
    ADD CONSTRAINT training_scheduler_setting_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: training_scheduler_setting training_scheduler_setting_updated_by_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.training_scheduler_setting
    ADD CONSTRAINT training_scheduler_setting_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES iam.app_user(id);


--
-- Name: training_snapshot training_snapshot_eligibility_run_fk; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.training_snapshot
    ADD CONSTRAINT training_snapshot_eligibility_run_fk FOREIGN KEY (organization_id, eligibility_run_id) REFERENCES ai.eligibility_evaluation_run(organization_id, id);


--
-- Name: training_snapshot training_snapshot_frozen_by_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.training_snapshot
    ADD CONSTRAINT training_snapshot_frozen_by_fkey FOREIGN KEY (frozen_by) REFERENCES iam.app_user(id);


--
-- Name: training_snapshot_item training_snapshot_item_organization_id_eligibility_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.training_snapshot_item
    ADD CONSTRAINT training_snapshot_item_organization_id_eligibility_id_fkey FOREIGN KEY (organization_id, eligibility_id) REFERENCES ai.training_eligibility(organization_id, id);


--
-- Name: training_snapshot_item training_snapshot_item_organization_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.training_snapshot_item
    ADD CONSTRAINT training_snapshot_item_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: training_snapshot_item training_snapshot_item_organization_id_sample_revision_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.training_snapshot_item
    ADD CONSTRAINT training_snapshot_item_organization_id_sample_revision_id_fkey FOREIGN KEY (organization_id, sample_revision_id) REFERENCES ai.sample_revision(organization_id, id);


--
-- Name: training_snapshot_item training_snapshot_item_organization_id_training_snapshot_i_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.training_snapshot_item
    ADD CONSTRAINT training_snapshot_item_organization_id_training_snapshot_i_fkey FOREIGN KEY (organization_id, training_snapshot_id) REFERENCES ai.training_snapshot(organization_id, id);


--
-- Name: training_snapshot_item training_snapshot_item_sample_fk; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.training_snapshot_item
    ADD CONSTRAINT training_snapshot_item_sample_fk FOREIGN KEY (organization_id, training_sample_id) REFERENCES ai.training_sample(organization_id, id);


--
-- Name: training_snapshot training_snapshot_organization_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.training_snapshot
    ADD CONSTRAINT training_snapshot_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: training_snapshot training_snapshot_organization_id_input_scheme_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.training_snapshot
    ADD CONSTRAINT training_snapshot_organization_id_input_scheme_id_fkey FOREIGN KEY (organization_id, input_scheme_id) REFERENCES ai.input_scheme(organization_id, id);


--
-- Name: training_snapshot training_snapshot_organization_id_material_dictionary_vers_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.training_snapshot
    ADD CONSTRAINT training_snapshot_organization_id_material_dictionary_vers_fkey FOREIGN KEY (organization_id, material_dictionary_version_id) REFERENCES ai.material_dictionary_version(organization_id, id);


--
-- Name: training_snapshot training_snapshot_organization_id_modeling_policy_version__fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.training_snapshot
    ADD CONSTRAINT training_snapshot_organization_id_modeling_policy_version__fkey FOREIGN KEY (organization_id, modeling_policy_version_id) REFERENCES ai.modeling_policy_version(organization_id, id);


--
-- Name: training_snapshot training_snapshot_organization_id_target_version_id_fkey; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.training_snapshot
    ADD CONSTRAINT training_snapshot_organization_id_target_version_id_fkey FOREIGN KEY (organization_id, target_version_id) REFERENCES ai.target_version(organization_id, id);


--
-- Name: training_snapshot training_snapshot_target_fk; Type: FK CONSTRAINT; Schema: ai; Owner: -
--

ALTER TABLE ONLY ai.training_snapshot
    ADD CONSTRAINT training_snapshot_target_fk FOREIGN KEY (organization_id, target_id) REFERENCES ai.prediction_target(organization_id, id);


--
-- Name: project_reference project_reference_added_by_fkey; Type: FK CONSTRAINT; Schema: core; Owner: -
--

ALTER TABLE ONLY core.project_reference
    ADD CONSTRAINT project_reference_added_by_fkey FOREIGN KEY (added_by) REFERENCES iam.app_user(id);


--
-- Name: project_reference project_reference_organization_id_fkey; Type: FK CONSTRAINT; Schema: core; Owner: -
--

ALTER TABLE ONLY core.project_reference
    ADD CONSTRAINT project_reference_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: project_reference project_reference_removed_by_fkey; Type: FK CONSTRAINT; Schema: core; Owner: -
--

ALTER TABLE ONLY core.project_reference
    ADD CONSTRAINT project_reference_removed_by_fkey FOREIGN KEY (removed_by) REFERENCES iam.app_user(id);


--
-- Name: project_resource_link project_resource_link_created_by_fkey; Type: FK CONSTRAINT; Schema: core; Owner: -
--

ALTER TABLE ONLY core.project_resource_link
    ADD CONSTRAINT project_resource_link_created_by_fkey FOREIGN KEY (created_by) REFERENCES iam.app_user(id);


--
-- Name: project_resource_link project_resource_link_organization_id_fkey; Type: FK CONSTRAINT; Schema: core; Owner: -
--

ALTER TABLE ONLY core.project_resource_link
    ADD CONSTRAINT project_resource_link_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: confirmed_submission confirmed_submission_confirmed_by_fkey; Type: FK CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.confirmed_submission
    ADD CONSTRAINT confirmed_submission_confirmed_by_fkey FOREIGN KEY (confirmed_by) REFERENCES iam.app_user(id);


--
-- Name: confirmed_submission_head confirmed_submission_head_organization_id_fkey; Type: FK CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.confirmed_submission_head
    ADD CONSTRAINT confirmed_submission_head_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: confirmed_submission_head confirmed_submission_head_organization_id_import_job_id_co_fkey; Type: FK CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.confirmed_submission_head
    ADD CONSTRAINT confirmed_submission_head_organization_id_import_job_id_co_fkey FOREIGN KEY (organization_id, import_job_id, confirmed_submission_id) REFERENCES data.confirmed_submission(organization_id, import_job_id, id);


--
-- Name: confirmed_submission_head confirmed_submission_head_organization_id_import_job_id_fkey; Type: FK CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.confirmed_submission_head
    ADD CONSTRAINT confirmed_submission_head_organization_id_import_job_id_fkey FOREIGN KEY (organization_id, import_job_id) REFERENCES data.import_job(organization_id, id);


--
-- Name: confirmed_submission_item confirmed_submission_item_organization_id_confirmed_submis_fkey; Type: FK CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.confirmed_submission_item
    ADD CONSTRAINT confirmed_submission_item_organization_id_confirmed_submis_fkey FOREIGN KEY (organization_id, confirmed_submission_id) REFERENCES data.confirmed_submission(organization_id, id);


--
-- Name: confirmed_submission_item confirmed_submission_item_organization_id_fkey; Type: FK CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.confirmed_submission_item
    ADD CONSTRAINT confirmed_submission_item_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: confirmed_submission_item confirmed_submission_item_organization_id_mapping_version__fkey; Type: FK CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.confirmed_submission_item
    ADD CONSTRAINT confirmed_submission_item_organization_id_mapping_version__fkey FOREIGN KEY (organization_id, mapping_version_id) REFERENCES ai.source_mapping_version(organization_id, id);


--
-- Name: confirmed_submission_item confirmed_submission_item_source_file_id_fkey; Type: FK CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.confirmed_submission_item
    ADD CONSTRAINT confirmed_submission_item_source_file_id_fkey FOREIGN KEY (source_file_id) REFERENCES ops.file_object(id);


--
-- Name: confirmed_submission confirmed_submission_organization_id_fkey; Type: FK CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.confirmed_submission
    ADD CONSTRAINT confirmed_submission_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: confirmed_submission confirmed_submission_organization_id_import_job_id_fkey; Type: FK CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.confirmed_submission
    ADD CONSTRAINT confirmed_submission_organization_id_import_job_id_fkey FOREIGN KEY (organization_id, import_job_id) REFERENCES data.import_job(organization_id, id);


--
-- Name: confirmed_submission confirmed_submission_organization_id_supersedes_submission_fkey; Type: FK CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.confirmed_submission
    ADD CONSTRAINT confirmed_submission_organization_id_supersedes_submission_fkey FOREIGN KEY (organization_id, supersedes_submission_id) REFERENCES data.confirmed_submission(organization_id, id);


--
-- Name: data_category data_category_created_by_fkey; Type: FK CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.data_category
    ADD CONSTRAINT data_category_created_by_fkey FOREIGN KEY (created_by) REFERENCES iam.app_user(id);


--
-- Name: data_category data_category_organization_id_fkey; Type: FK CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.data_category
    ADD CONSTRAINT data_category_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: data_record data_record_import_job_id_fkey; Type: FK CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.data_record
    ADD CONSTRAINT data_record_import_job_id_fkey FOREIGN KEY (import_job_id) REFERENCES data.import_job(id) ON DELETE CASCADE;


--
-- Name: data_record data_record_organization_id_fkey; Type: FK CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.data_record
    ADD CONSTRAINT data_record_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: data_value data_value_organization_id_fkey; Type: FK CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.data_value
    ADD CONSTRAINT data_value_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: data_value data_value_record_id_fkey; Type: FK CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.data_value
    ADD CONSTRAINT data_value_record_id_fkey FOREIGN KEY (record_id) REFERENCES data.data_record(id) ON DELETE CASCADE;


--
-- Name: data_value data_value_source_anchor_id_fkey; Type: FK CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.data_value
    ADD CONSTRAINT data_value_source_anchor_id_fkey FOREIGN KEY (source_anchor_id) REFERENCES data.source_anchor(id) ON DELETE SET NULL;


--
-- Name: source_anchor fk_data_source_anchor_record; Type: FK CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.source_anchor
    ADD CONSTRAINT fk_data_source_anchor_record FOREIGN KEY (record_id) REFERENCES data.data_record(id) ON DELETE CASCADE;


--
-- Name: import_component_override import_component_override_created_by_fkey; Type: FK CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.import_component_override
    ADD CONSTRAINT import_component_override_created_by_fkey FOREIGN KEY (created_by) REFERENCES iam.app_user(id);


--
-- Name: import_component_override import_component_override_import_job_id_fkey; Type: FK CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.import_component_override
    ADD CONSTRAINT import_component_override_import_job_id_fkey FOREIGN KEY (import_job_id) REFERENCES data.import_job(id) ON DELETE CASCADE;


--
-- Name: import_component_override import_component_override_organization_id_fkey; Type: FK CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.import_component_override
    ADD CONSTRAINT import_component_override_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: import_experiment_link import_experiment_link_created_by_fkey; Type: FK CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.import_experiment_link
    ADD CONSTRAINT import_experiment_link_created_by_fkey FOREIGN KEY (created_by) REFERENCES iam.app_user(id);


--
-- Name: import_experiment_link import_experiment_link_import_job_id_fkey; Type: FK CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.import_experiment_link
    ADD CONSTRAINT import_experiment_link_import_job_id_fkey FOREIGN KEY (import_job_id) REFERENCES data.import_job(id) ON DELETE CASCADE;


--
-- Name: import_experiment_link import_experiment_link_organization_id_fkey; Type: FK CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.import_experiment_link
    ADD CONSTRAINT import_experiment_link_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: import_experiment_link import_experiment_link_recognition_job_fk; Type: FK CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.import_experiment_link
    ADD CONSTRAINT import_experiment_link_recognition_job_fk FOREIGN KEY (organization_id, recognition_job_id) REFERENCES data.import_job(organization_id, id);


--
-- Name: import_experiment_link import_experiment_link_resolved_by_fkey; Type: FK CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.import_experiment_link
    ADD CONSTRAINT import_experiment_link_resolved_by_fkey FOREIGN KEY (resolved_by) REFERENCES iam.app_user(id);


--
-- Name: import_experiment_link import_experiment_link_submission_fk; Type: FK CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.import_experiment_link
    ADD CONSTRAINT import_experiment_link_submission_fk FOREIGN KEY (organization_id, confirmed_submission_id) REFERENCES data.confirmed_submission(organization_id, id);


--
-- Name: import_issue import_issue_import_job_id_fkey; Type: FK CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.import_issue
    ADD CONSTRAINT import_issue_import_job_id_fkey FOREIGN KEY (import_job_id) REFERENCES data.import_job(id) ON DELETE CASCADE;


--
-- Name: import_issue import_issue_import_sheet_id_fkey; Type: FK CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.import_issue
    ADD CONSTRAINT import_issue_import_sheet_id_fkey FOREIGN KEY (import_sheet_id) REFERENCES data.import_sheet(id) ON DELETE SET NULL;


--
-- Name: import_issue import_issue_resolved_by_fkey; Type: FK CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.import_issue
    ADD CONSTRAINT import_issue_resolved_by_fkey FOREIGN KEY (resolved_by) REFERENCES iam.app_user(id);


--
-- Name: import_issue import_issue_staging_row_id_fkey; Type: FK CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.import_issue
    ADD CONSTRAINT import_issue_staging_row_id_fkey FOREIGN KEY (staging_row_id) REFERENCES data.staging_row(id) ON DELETE CASCADE;


--
-- Name: import_job_attempt import_job_attempt_created_by_fkey; Type: FK CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.import_job_attempt
    ADD CONSTRAINT import_job_attempt_created_by_fkey FOREIGN KEY (created_by) REFERENCES iam.app_user(id);


--
-- Name: import_job_attempt import_job_attempt_import_job_id_fkey; Type: FK CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.import_job_attempt
    ADD CONSTRAINT import_job_attempt_import_job_id_fkey FOREIGN KEY (import_job_id) REFERENCES data.import_job(id) ON DELETE CASCADE;


--
-- Name: import_job_attempt import_job_attempt_organization_id_fkey; Type: FK CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.import_job_attempt
    ADD CONSTRAINT import_job_attempt_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: import_job import_job_category_id_fkey; Type: FK CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.import_job
    ADD CONSTRAINT import_job_category_id_fkey FOREIGN KEY (category_id) REFERENCES data.data_category(id);


--
-- Name: import_job import_job_created_by_fkey; Type: FK CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.import_job
    ADD CONSTRAINT import_job_created_by_fkey FOREIGN KEY (created_by) REFERENCES iam.app_user(id);


--
-- Name: import_job import_job_organization_id_fkey; Type: FK CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.import_job
    ADD CONSTRAINT import_job_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: import_job import_job_recognition_profile_fk; Type: FK CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.import_job
    ADD CONSTRAINT import_job_recognition_profile_fk FOREIGN KEY (organization_id, recognition_profile_id) REFERENCES data.recognition_profile(organization_id, id);


--
-- Name: import_mapping import_mapping_import_job_id_fkey; Type: FK CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.import_mapping
    ADD CONSTRAINT import_mapping_import_job_id_fkey FOREIGN KEY (import_job_id) REFERENCES data.import_job(id) ON DELETE CASCADE;


--
-- Name: import_mapping import_mapping_import_sheet_id_fkey; Type: FK CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.import_mapping
    ADD CONSTRAINT import_mapping_import_sheet_id_fkey FOREIGN KEY (import_sheet_id) REFERENCES data.import_sheet(id) ON DELETE CASCADE;


--
-- Name: import_mapping_profile import_mapping_profile_approved_by_fkey; Type: FK CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.import_mapping_profile
    ADD CONSTRAINT import_mapping_profile_approved_by_fkey FOREIGN KEY (approved_by) REFERENCES iam.app_user(id);


--
-- Name: import_mapping_profile import_mapping_profile_organization_id_fkey; Type: FK CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.import_mapping_profile
    ADD CONSTRAINT import_mapping_profile_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: import_sheet import_sheet_import_job_id_fkey; Type: FK CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.import_sheet
    ADD CONSTRAINT import_sheet_import_job_id_fkey FOREIGN KEY (import_job_id) REFERENCES data.import_job(id) ON DELETE CASCADE;


--
-- Name: import_workbook_draft import_workbook_draft_organization_id_import_job_id_fkey; Type: FK CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.import_workbook_draft
    ADD CONSTRAINT import_workbook_draft_organization_id_import_job_id_fkey FOREIGN KEY (organization_id, import_job_id) REFERENCES data.import_job(organization_id, id) ON DELETE CASCADE;


--
-- Name: import_workbook_draft import_workbook_draft_updated_by_fkey; Type: FK CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.import_workbook_draft
    ADD CONSTRAINT import_workbook_draft_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES iam.app_user(id);


--
-- Name: publication publication_confirmed_snapshot_id_fkey; Type: FK CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.publication
    ADD CONSTRAINT publication_confirmed_snapshot_id_fkey FOREIGN KEY (confirmed_snapshot_id) REFERENCES data.publication_snapshot(id);


--
-- Name: publication_event publication_event_actor_id_fkey; Type: FK CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.publication_event
    ADD CONSTRAINT publication_event_actor_id_fkey FOREIGN KEY (actor_id) REFERENCES iam.app_user(id);


--
-- Name: publication_event publication_event_import_job_id_fkey; Type: FK CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.publication_event
    ADD CONSTRAINT publication_event_import_job_id_fkey FOREIGN KEY (import_job_id) REFERENCES data.import_job(id);


--
-- Name: publication_event publication_event_organization_id_fkey; Type: FK CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.publication_event
    ADD CONSTRAINT publication_event_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: publication_event publication_event_snapshot_id_fkey; Type: FK CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.publication_event
    ADD CONSTRAINT publication_event_snapshot_id_fkey FOREIGN KEY (snapshot_id) REFERENCES data.publication_snapshot(id);


--
-- Name: publication publication_import_job_id_fkey; Type: FK CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.publication
    ADD CONSTRAINT publication_import_job_id_fkey FOREIGN KEY (import_job_id) REFERENCES data.import_job(id);


--
-- Name: publication publication_organization_id_fkey; Type: FK CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.publication
    ADD CONSTRAINT publication_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: publication publication_published_snapshot_id_fkey; Type: FK CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.publication
    ADD CONSTRAINT publication_published_snapshot_id_fkey FOREIGN KEY (published_snapshot_id) REFERENCES data.publication_snapshot(id);


--
-- Name: publication_release publication_release_confirmed_submission_id_fkey; Type: FK CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.publication_release
    ADD CONSTRAINT publication_release_confirmed_submission_id_fkey FOREIGN KEY (confirmed_submission_id) REFERENCES data.confirmed_submission(id);


--
-- Name: publication_release publication_release_published_by_fkey; Type: FK CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.publication_release
    ADD CONSTRAINT publication_release_published_by_fkey FOREIGN KEY (published_by) REFERENCES iam.app_user(id);


--
-- Name: publication_release publication_release_snapshot_id_fkey; Type: FK CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.publication_release
    ADD CONSTRAINT publication_release_snapshot_id_fkey FOREIGN KEY (snapshot_id) REFERENCES data.publication_snapshot(id);


--
-- Name: publication publication_reviewer_id_fkey; Type: FK CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.publication
    ADD CONSTRAINT publication_reviewer_id_fkey FOREIGN KEY (reviewer_id) REFERENCES iam.app_user(id);


--
-- Name: publication_snapshot publication_snapshot_confirmed_by_fkey; Type: FK CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.publication_snapshot
    ADD CONSTRAINT publication_snapshot_confirmed_by_fkey FOREIGN KEY (confirmed_by) REFERENCES iam.app_user(id);


--
-- Name: publication_snapshot publication_snapshot_confirmed_submission_id_fkey; Type: FK CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.publication_snapshot
    ADD CONSTRAINT publication_snapshot_confirmed_submission_id_fkey FOREIGN KEY (confirmed_submission_id) REFERENCES data.confirmed_submission(id);


--
-- Name: publication_snapshot publication_snapshot_import_job_id_fkey; Type: FK CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.publication_snapshot
    ADD CONSTRAINT publication_snapshot_import_job_id_fkey FOREIGN KEY (import_job_id) REFERENCES data.import_job(id);


--
-- Name: publication_snapshot publication_snapshot_organization_id_fkey; Type: FK CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.publication_snapshot
    ADD CONSTRAINT publication_snapshot_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: recognition_profile recognition_profile_created_by_fkey; Type: FK CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.recognition_profile
    ADD CONSTRAINT recognition_profile_created_by_fkey FOREIGN KEY (created_by) REFERENCES iam.app_user(id);


--
-- Name: recognition_profile recognition_profile_organization_id_fkey; Type: FK CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.recognition_profile
    ADD CONSTRAINT recognition_profile_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: recognition_profile recognition_profile_updated_by_fkey; Type: FK CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.recognition_profile
    ADD CONSTRAINT recognition_profile_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES iam.app_user(id);


--
-- Name: record_governance record_governance_import_job_id_fkey; Type: FK CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.record_governance
    ADD CONSTRAINT record_governance_import_job_id_fkey FOREIGN KEY (import_job_id) REFERENCES data.import_job(id);


--
-- Name: record_governance record_governance_organization_id_fkey; Type: FK CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.record_governance
    ADD CONSTRAINT record_governance_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: record_governance record_governance_updated_by_fkey; Type: FK CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.record_governance
    ADD CONSTRAINT record_governance_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES iam.app_user(id);


--
-- Name: source_anchor source_anchor_import_job_id_fkey; Type: FK CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.source_anchor
    ADD CONSTRAINT source_anchor_import_job_id_fkey FOREIGN KEY (import_job_id) REFERENCES data.import_job(id);


--
-- Name: staging_row staging_row_excluded_by_fkey; Type: FK CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.staging_row
    ADD CONSTRAINT staging_row_excluded_by_fkey FOREIGN KEY (excluded_by) REFERENCES iam.app_user(id);


--
-- Name: staging_row staging_row_import_job_id_fkey; Type: FK CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.staging_row
    ADD CONSTRAINT staging_row_import_job_id_fkey FOREIGN KEY (import_job_id) REFERENCES data.import_job(id) ON DELETE CASCADE;


--
-- Name: staging_row staging_row_import_sheet_id_fkey; Type: FK CONSTRAINT; Schema: data; Owner: -
--

ALTER TABLE ONLY data.staging_row
    ADD CONSTRAINT staging_row_import_sheet_id_fkey FOREIGN KEY (import_sheet_id) REFERENCES data.import_sheet(id) ON DELETE CASCADE;


--
-- Name: app_user app_user_organization_id_fkey; Type: FK CONSTRAINT; Schema: iam; Owner: -
--

ALTER TABLE ONLY iam.app_user
    ADD CONSTRAINT app_user_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: app_user app_user_role_id_fkey; Type: FK CONSTRAINT; Schema: iam; Owner: -
--

ALTER TABLE ONLY iam.app_user
    ADD CONSTRAINT app_user_role_id_fkey FOREIGN KEY (role_id) REFERENCES iam.role(id);


--
-- Name: login_attempt login_attempt_organization_id_fkey; Type: FK CONSTRAINT; Schema: iam; Owner: -
--

ALTER TABLE ONLY iam.login_attempt
    ADD CONSTRAINT login_attempt_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: login_session login_session_organization_id_fkey; Type: FK CONSTRAINT; Schema: iam; Owner: -
--

ALTER TABLE ONLY iam.login_session
    ADD CONSTRAINT login_session_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: login_session login_session_user_id_fkey; Type: FK CONSTRAINT; Schema: iam; Owner: -
--

ALTER TABLE ONLY iam.login_session
    ADD CONSTRAINT login_session_user_id_fkey FOREIGN KEY (user_id) REFERENCES iam.app_user(id) ON DELETE CASCADE;


--
-- Name: permission_level_binding permission_level_binding_organization_id_fkey; Type: FK CONSTRAINT; Schema: iam; Owner: -
--

ALTER TABLE ONLY iam.permission_level_binding
    ADD CONSTRAINT permission_level_binding_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: permission_level_binding permission_level_binding_permission_code_fkey; Type: FK CONSTRAINT; Schema: iam; Owner: -
--

ALTER TABLE ONLY iam.permission_level_binding
    ADD CONSTRAINT permission_level_binding_permission_code_fkey FOREIGN KEY (permission_code) REFERENCES iam.permission_definition(code);


--
-- Name: permission_level_binding permission_level_binding_permission_level_id_fkey; Type: FK CONSTRAINT; Schema: iam; Owner: -
--

ALTER TABLE ONLY iam.permission_level_binding
    ADD CONSTRAINT permission_level_binding_permission_level_id_fkey FOREIGN KEY (permission_level_id) REFERENCES iam.permission_level(id) ON DELETE CASCADE;


--
-- Name: permission_level permission_level_organization_id_fkey; Type: FK CONSTRAINT; Schema: iam; Owner: -
--

ALTER TABLE ONLY iam.permission_level
    ADD CONSTRAINT permission_level_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: permission_migration_issue permission_migration_issue_organization_id_fkey; Type: FK CONSTRAINT; Schema: iam; Owner: -
--

ALTER TABLE ONLY iam.permission_migration_issue
    ADD CONSTRAINT permission_migration_issue_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: permission_migration_issue permission_migration_issue_user_id_fkey; Type: FK CONSTRAINT; Schema: iam; Owner: -
--

ALTER TABLE ONLY iam.permission_migration_issue
    ADD CONSTRAINT permission_migration_issue_user_id_fkey FOREIGN KEY (user_id) REFERENCES iam.app_user(id) ON DELETE CASCADE;


--
-- Name: permission_scope_target permission_scope_target_organization_id_fkey; Type: FK CONSTRAINT; Schema: iam; Owner: -
--

ALTER TABLE ONLY iam.permission_scope_target
    ADD CONSTRAINT permission_scope_target_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: permission_scope_target permission_scope_target_permission_code_fkey; Type: FK CONSTRAINT; Schema: iam; Owner: -
--

ALTER TABLE ONLY iam.permission_scope_target
    ADD CONSTRAINT permission_scope_target_permission_code_fkey FOREIGN KEY (permission_code) REFERENCES iam.permission_definition(code);


--
-- Name: project_member project_member_organization_id_fkey; Type: FK CONSTRAINT; Schema: iam; Owner: -
--

ALTER TABLE ONLY iam.project_member
    ADD CONSTRAINT project_member_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: project_member project_member_user_id_fkey; Type: FK CONSTRAINT; Schema: iam; Owner: -
--

ALTER TABLE ONLY iam.project_member
    ADD CONSTRAINT project_member_user_id_fkey FOREIGN KEY (user_id) REFERENCES iam.app_user(id) ON DELETE CASCADE;


--
-- Name: resource_owner_identity resource_owner_identity_organization_id_fkey; Type: FK CONSTRAINT; Schema: iam; Owner: -
--

ALTER TABLE ONLY iam.resource_owner_identity
    ADD CONSTRAINT resource_owner_identity_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: resource_owner_identity resource_owner_identity_user_id_fkey; Type: FK CONSTRAINT; Schema: iam; Owner: -
--

ALTER TABLE ONLY iam.resource_owner_identity
    ADD CONSTRAINT resource_owner_identity_user_id_fkey FOREIGN KEY (user_id) REFERENCES iam.app_user(id) ON DELETE CASCADE;


--
-- Name: role_menu_binding role_menu_binding_organization_id_fkey; Type: FK CONSTRAINT; Schema: iam; Owner: -
--

ALTER TABLE ONLY iam.role_menu_binding
    ADD CONSTRAINT role_menu_binding_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: role_menu_binding role_menu_binding_role_id_fkey; Type: FK CONSTRAINT; Schema: iam; Owner: -
--

ALTER TABLE ONLY iam.role_menu_binding
    ADD CONSTRAINT role_menu_binding_role_id_fkey FOREIGN KEY (role_id) REFERENCES iam.role(id) ON DELETE CASCADE;


--
-- Name: role role_organization_id_fkey; Type: FK CONSTRAINT; Schema: iam; Owner: -
--

ALTER TABLE ONLY iam.role
    ADD CONSTRAINT role_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: role_permission_binding role_permission_binding_organization_id_fkey; Type: FK CONSTRAINT; Schema: iam; Owner: -
--

ALTER TABLE ONLY iam.role_permission_binding
    ADD CONSTRAINT role_permission_binding_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: role_permission_binding role_permission_binding_permission_code_fkey; Type: FK CONSTRAINT; Schema: iam; Owner: -
--

ALTER TABLE ONLY iam.role_permission_binding
    ADD CONSTRAINT role_permission_binding_permission_code_fkey FOREIGN KEY (permission_code) REFERENCES iam.permission_definition(code);


--
-- Name: role_permission_binding role_permission_binding_role_id_fkey; Type: FK CONSTRAINT; Schema: iam; Owner: -
--

ALTER TABLE ONLY iam.role_permission_binding
    ADD CONSTRAINT role_permission_binding_role_id_fkey FOREIGN KEY (role_id) REFERENCES iam.role(id) ON DELETE CASCADE;


--
-- Name: user_menu_override user_menu_override_organization_id_fkey; Type: FK CONSTRAINT; Schema: iam; Owner: -
--

ALTER TABLE ONLY iam.user_menu_override
    ADD CONSTRAINT user_menu_override_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: user_menu_override user_menu_override_user_id_fkey; Type: FK CONSTRAINT; Schema: iam; Owner: -
--

ALTER TABLE ONLY iam.user_menu_override
    ADD CONSTRAINT user_menu_override_user_id_fkey FOREIGN KEY (user_id) REFERENCES iam.app_user(id) ON DELETE CASCADE;


--
-- Name: user_operation_override user_operation_override_organization_id_fkey; Type: FK CONSTRAINT; Schema: iam; Owner: -
--

ALTER TABLE ONLY iam.user_operation_override
    ADD CONSTRAINT user_operation_override_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: user_operation_override user_operation_override_permission_code_fkey; Type: FK CONSTRAINT; Schema: iam; Owner: -
--

ALTER TABLE ONLY iam.user_operation_override
    ADD CONSTRAINT user_operation_override_permission_code_fkey FOREIGN KEY (permission_code) REFERENCES iam.permission_definition(code);


--
-- Name: user_operation_override user_operation_override_user_id_fkey; Type: FK CONSTRAINT; Schema: iam; Owner: -
--

ALTER TABLE ONLY iam.user_operation_override
    ADD CONSTRAINT user_operation_override_user_id_fkey FOREIGN KEY (user_id) REFERENCES iam.app_user(id) ON DELETE CASCADE;


--
-- Name: user_permission_override user_permission_override_organization_id_fkey; Type: FK CONSTRAINT; Schema: iam; Owner: -
--

ALTER TABLE ONLY iam.user_permission_override
    ADD CONSTRAINT user_permission_override_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: user_permission_override user_permission_override_permission_code_fkey; Type: FK CONSTRAINT; Schema: iam; Owner: -
--

ALTER TABLE ONLY iam.user_permission_override
    ADD CONSTRAINT user_permission_override_permission_code_fkey FOREIGN KEY (permission_code) REFERENCES iam.permission_definition(code);


--
-- Name: user_permission_override user_permission_override_user_id_fkey; Type: FK CONSTRAINT; Schema: iam; Owner: -
--

ALTER TABLE ONLY iam.user_permission_override
    ADD CONSTRAINT user_permission_override_user_id_fkey FOREIGN KEY (user_id) REFERENCES iam.app_user(id) ON DELETE CASCADE;


--
-- Name: user_permission_profile user_permission_profile_organization_id_fkey; Type: FK CONSTRAINT; Schema: iam; Owner: -
--

ALTER TABLE ONLY iam.user_permission_profile
    ADD CONSTRAINT user_permission_profile_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: user_permission_profile user_permission_profile_permission_level_id_fkey; Type: FK CONSTRAINT; Schema: iam; Owner: -
--

ALTER TABLE ONLY iam.user_permission_profile
    ADD CONSTRAINT user_permission_profile_permission_level_id_fkey FOREIGN KEY (permission_level_id) REFERENCES iam.permission_level(id) ON DELETE SET NULL;


--
-- Name: user_permission_profile user_permission_profile_user_id_fkey; Type: FK CONSTRAINT; Schema: iam; Owner: -
--

ALTER TABLE ONLY iam.user_permission_profile
    ADD CONSTRAINT user_permission_profile_user_id_fkey FOREIGN KEY (user_id) REFERENCES iam.app_user(id) ON DELETE CASCADE;


--
-- Name: user_role user_role_organization_id_fkey; Type: FK CONSTRAINT; Schema: iam; Owner: -
--

ALTER TABLE ONLY iam.user_role
    ADD CONSTRAINT user_role_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: user_role user_role_role_id_fkey; Type: FK CONSTRAINT; Schema: iam; Owner: -
--

ALTER TABLE ONLY iam.user_role
    ADD CONSTRAINT user_role_role_id_fkey FOREIGN KEY (role_id) REFERENCES iam.role(id) ON DELETE CASCADE;


--
-- Name: user_role user_role_user_id_fkey; Type: FK CONSTRAINT; Schema: iam; Owner: -
--

ALTER TABLE ONLY iam.user_role
    ADD CONSTRAINT user_role_user_id_fkey FOREIGN KEY (user_id) REFERENCES iam.app_user(id) ON DELETE CASCADE;


--
-- Name: user_scope_override user_scope_override_organization_id_fkey; Type: FK CONSTRAINT; Schema: iam; Owner: -
--

ALTER TABLE ONLY iam.user_scope_override
    ADD CONSTRAINT user_scope_override_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: user_scope_override user_scope_override_permission_code_fkey; Type: FK CONSTRAINT; Schema: iam; Owner: -
--

ALTER TABLE ONLY iam.user_scope_override
    ADD CONSTRAINT user_scope_override_permission_code_fkey FOREIGN KEY (permission_code) REFERENCES iam.permission_definition(code);


--
-- Name: user_scope_override user_scope_override_user_id_fkey; Type: FK CONSTRAINT; Schema: iam; Owner: -
--

ALTER TABLE ONLY iam.user_scope_override
    ADD CONSTRAINT user_scope_override_user_id_fkey FOREIGN KEY (user_id) REFERENCES iam.app_user(id) ON DELETE CASCADE;


--
-- Name: chunk_term chunk_term_chunk_id_fkey; Type: FK CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.chunk_term
    ADD CONSTRAINT chunk_term_chunk_id_fkey FOREIGN KEY (chunk_id) REFERENCES kb.document_chunk(id) ON DELETE CASCADE;


--
-- Name: document_ai_grant document_ai_grant_document_id_fkey; Type: FK CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.document_ai_grant
    ADD CONSTRAINT document_ai_grant_document_id_fkey FOREIGN KEY (document_id) REFERENCES kb.document(id) ON DELETE CASCADE;


--
-- Name: document_ai_grant document_ai_grant_organization_id_fkey; Type: FK CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.document_ai_grant
    ADD CONSTRAINT document_ai_grant_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: document_ai_grant document_ai_grant_updated_by_fkey; Type: FK CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.document_ai_grant
    ADD CONSTRAINT document_ai_grant_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES iam.app_user(id);


--
-- Name: document_category document_category_created_by_fkey; Type: FK CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.document_category
    ADD CONSTRAINT document_category_created_by_fkey FOREIGN KEY (created_by) REFERENCES iam.app_user(id);


--
-- Name: document document_category_id_fkey; Type: FK CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.document
    ADD CONSTRAINT document_category_id_fkey FOREIGN KEY (category_id) REFERENCES kb.document_category(id);


--
-- Name: document_category document_category_organization_id_fkey; Type: FK CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.document_category
    ADD CONSTRAINT document_category_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: document_chunk document_chunk_document_id_fkey; Type: FK CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.document_chunk
    ADD CONSTRAINT document_chunk_document_id_fkey FOREIGN KEY (document_id) REFERENCES kb.document(id) ON DELETE CASCADE;


--
-- Name: document_chunk document_chunk_document_version_id_fkey; Type: FK CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.document_chunk
    ADD CONSTRAINT document_chunk_document_version_id_fkey FOREIGN KEY (document_version_id) REFERENCES kb.document_version(id) ON DELETE CASCADE;


--
-- Name: document_chunk document_chunk_parent_chunk_id_fkey; Type: FK CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.document_chunk
    ADD CONSTRAINT document_chunk_parent_chunk_id_fkey FOREIGN KEY (parent_chunk_id) REFERENCES kb.document_chunk(id) ON DELETE SET NULL;


--
-- Name: document_chunk document_chunk_parse_run_id_fkey; Type: FK CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.document_chunk
    ADD CONSTRAINT document_chunk_parse_run_id_fkey FOREIGN KEY (parse_run_id) REFERENCES kb.document_parse_run(id) ON DELETE CASCADE;


--
-- Name: document document_created_by_fkey; Type: FK CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.document
    ADD CONSTRAINT document_created_by_fkey FOREIGN KEY (created_by) REFERENCES iam.app_user(id);


--
-- Name: document document_disabled_by_fkey; Type: FK CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.document
    ADD CONSTRAINT document_disabled_by_fkey FOREIGN KEY (disabled_by) REFERENCES iam.app_user(id);


--
-- Name: document document_organization_id_fkey; Type: FK CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.document
    ADD CONSTRAINT document_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: document_parse_issue document_parse_issue_parse_run_id_fkey; Type: FK CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.document_parse_issue
    ADD CONSTRAINT document_parse_issue_parse_run_id_fkey FOREIGN KEY (parse_run_id) REFERENCES kb.document_parse_run(id) ON DELETE CASCADE;


--
-- Name: document_parse_issue_source_node document_parse_issue_source_node_parse_issue_id_fkey; Type: FK CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.document_parse_issue_source_node
    ADD CONSTRAINT document_parse_issue_source_node_parse_issue_id_fkey FOREIGN KEY (parse_issue_id) REFERENCES kb.document_parse_issue(id) ON DELETE CASCADE;


--
-- Name: document_parse_issue_source_node document_parse_issue_source_node_source_node_key_fkey; Type: FK CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.document_parse_issue_source_node
    ADD CONSTRAINT document_parse_issue_source_node_source_node_key_fkey FOREIGN KEY (source_node_key) REFERENCES kb.document_source_node(source_node_key) ON DELETE CASCADE;


--
-- Name: document_parse_run document_parse_run_document_id_fkey; Type: FK CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.document_parse_run
    ADD CONSTRAINT document_parse_run_document_id_fkey FOREIGN KEY (document_id) REFERENCES kb.document(id) ON DELETE CASCADE;


--
-- Name: document_parse_run document_parse_run_document_version_id_fkey; Type: FK CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.document_parse_run
    ADD CONSTRAINT document_parse_run_document_version_id_fkey FOREIGN KEY (document_version_id) REFERENCES kb.document_version(id) ON DELETE CASCADE;


--
-- Name: document_parse_run document_parse_run_organization_id_fkey; Type: FK CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.document_parse_run
    ADD CONSTRAINT document_parse_run_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: document_processing_step document_processing_step_document_id_fkey; Type: FK CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.document_processing_step
    ADD CONSTRAINT document_processing_step_document_id_fkey FOREIGN KEY (document_id) REFERENCES kb.document(id) ON DELETE CASCADE;


--
-- Name: document_processing_step document_processing_step_document_version_id_fkey; Type: FK CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.document_processing_step
    ADD CONSTRAINT document_processing_step_document_version_id_fkey FOREIGN KEY (document_version_id) REFERENCES kb.document_version(id) ON DELETE CASCADE;


--
-- Name: document_processing_step document_processing_step_organization_id_fkey; Type: FK CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.document_processing_step
    ADD CONSTRAINT document_processing_step_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: document_processing_step document_processing_step_parse_run_id_fkey; Type: FK CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.document_processing_step
    ADD CONSTRAINT document_processing_step_parse_run_id_fkey FOREIGN KEY (parse_run_id) REFERENCES kb.document_parse_run(id) ON DELETE CASCADE;


--
-- Name: document_result_asset document_result_asset_asset_file_id_fkey; Type: FK CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.document_result_asset
    ADD CONSTRAINT document_result_asset_asset_file_id_fkey FOREIGN KEY (asset_file_id) REFERENCES ops.file_object(id);


--
-- Name: document_result_asset document_result_asset_document_id_fkey; Type: FK CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.document_result_asset
    ADD CONSTRAINT document_result_asset_document_id_fkey FOREIGN KEY (document_id) REFERENCES kb.document(id) ON DELETE CASCADE;


--
-- Name: document_result_asset document_result_asset_document_version_id_fkey; Type: FK CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.document_result_asset
    ADD CONSTRAINT document_result_asset_document_version_id_fkey FOREIGN KEY (document_version_id) REFERENCES kb.document_version(id) ON DELETE CASCADE;


--
-- Name: document_result_asset document_result_asset_organization_id_fkey; Type: FK CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.document_result_asset
    ADD CONSTRAINT document_result_asset_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: document_result_asset document_result_asset_result_file_id_fkey; Type: FK CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.document_result_asset
    ADD CONSTRAINT document_result_asset_result_file_id_fkey FOREIGN KEY (result_file_id) REFERENCES ops.file_object(id);


--
-- Name: document_review_issue_state document_review_issue_state_parse_issue_id_fkey; Type: FK CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.document_review_issue_state
    ADD CONSTRAINT document_review_issue_state_parse_issue_id_fkey FOREIGN KEY (parse_issue_id) REFERENCES kb.document_parse_issue(id) ON DELETE CASCADE;


--
-- Name: document_review_issue_state document_review_issue_state_review_revision_id_fkey; Type: FK CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.document_review_issue_state
    ADD CONSTRAINT document_review_issue_state_review_revision_id_fkey FOREIGN KEY (review_revision_id) REFERENCES kb.document_review_revision(id) ON DELETE CASCADE;


--
-- Name: document_review_issue_state document_review_issue_state_updated_by_fkey; Type: FK CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.document_review_issue_state
    ADD CONSTRAINT document_review_issue_state_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES iam.app_user(id);


--
-- Name: document_review_revision document_review_revision_base_publication_id_fkey; Type: FK CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.document_review_revision
    ADD CONSTRAINT document_review_revision_base_publication_id_fkey FOREIGN KEY (base_publication_id) REFERENCES kb.publication(id);


--
-- Name: document_review_revision document_review_revision_created_by_fkey; Type: FK CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.document_review_revision
    ADD CONSTRAINT document_review_revision_created_by_fkey FOREIGN KEY (created_by) REFERENCES iam.app_user(id);


--
-- Name: document_review_revision document_review_revision_document_id_fkey; Type: FK CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.document_review_revision
    ADD CONSTRAINT document_review_revision_document_id_fkey FOREIGN KEY (document_id) REFERENCES kb.document(id) ON DELETE CASCADE;


--
-- Name: document_review_revision document_review_revision_document_version_id_fkey; Type: FK CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.document_review_revision
    ADD CONSTRAINT document_review_revision_document_version_id_fkey FOREIGN KEY (document_version_id) REFERENCES kb.document_version(id) ON DELETE CASCADE;


--
-- Name: document_review_revision document_review_revision_organization_id_fkey; Type: FK CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.document_review_revision
    ADD CONSTRAINT document_review_revision_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: document_review_revision document_review_revision_parse_run_id_fkey; Type: FK CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.document_review_revision
    ADD CONSTRAINT document_review_revision_parse_run_id_fkey FOREIGN KEY (parse_run_id) REFERENCES kb.document_parse_run(id);


--
-- Name: document_review_revision document_review_revision_updated_by_fkey; Type: FK CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.document_review_revision
    ADD CONSTRAINT document_review_revision_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES iam.app_user(id);


--
-- Name: document_review_table_cell_patch document_review_table_cell_patch_review_revision_id_fkey; Type: FK CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.document_review_table_cell_patch
    ADD CONSTRAINT document_review_table_cell_patch_review_revision_id_fkey FOREIGN KEY (review_revision_id) REFERENCES kb.document_review_revision(id) ON DELETE CASCADE;


--
-- Name: document_review_table_cell_patch document_review_table_cell_patch_source_table_id_fkey; Type: FK CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.document_review_table_cell_patch
    ADD CONSTRAINT document_review_table_cell_patch_source_table_id_fkey FOREIGN KEY (source_table_id) REFERENCES kb.document_source_table(id) ON DELETE CASCADE;


--
-- Name: document_review_table_cell_patch document_review_table_cell_patch_updated_by_fkey; Type: FK CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.document_review_table_cell_patch
    ADD CONSTRAINT document_review_table_cell_patch_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES iam.app_user(id);


--
-- Name: document_review_table_row_state document_review_table_row_state_review_revision_id_fkey; Type: FK CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.document_review_table_row_state
    ADD CONSTRAINT document_review_table_row_state_review_revision_id_fkey FOREIGN KEY (review_revision_id) REFERENCES kb.document_review_revision(id) ON DELETE CASCADE;


--
-- Name: document_review_table_row_state document_review_table_row_state_source_table_id_fkey; Type: FK CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.document_review_table_row_state
    ADD CONSTRAINT document_review_table_row_state_source_table_id_fkey FOREIGN KEY (source_table_id) REFERENCES kb.document_source_table(id) ON DELETE CASCADE;


--
-- Name: document_review_table_row_state document_review_table_row_state_updated_by_fkey; Type: FK CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.document_review_table_row_state
    ADD CONSTRAINT document_review_table_row_state_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES iam.app_user(id);


--
-- Name: document_source_node document_source_node_organization_id_fkey; Type: FK CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.document_source_node
    ADD CONSTRAINT document_source_node_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: document_source_node document_source_node_parse_run_id_fkey; Type: FK CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.document_source_node
    ADD CONSTRAINT document_source_node_parse_run_id_fkey FOREIGN KEY (parse_run_id) REFERENCES kb.document_parse_run(id) ON DELETE CASCADE;


--
-- Name: document_source_table_cell document_source_table_cell_source_table_id_fkey; Type: FK CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.document_source_table_cell
    ADD CONSTRAINT document_source_table_cell_source_table_id_fkey FOREIGN KEY (source_table_id) REFERENCES kb.document_source_table(id) ON DELETE CASCADE;


--
-- Name: document_source_table document_source_table_organization_id_fkey; Type: FK CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.document_source_table
    ADD CONSTRAINT document_source_table_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: document_source_table document_source_table_parse_run_id_fkey; Type: FK CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.document_source_table
    ADD CONSTRAINT document_source_table_parse_run_id_fkey FOREIGN KEY (parse_run_id) REFERENCES kb.document_parse_run(id) ON DELETE CASCADE;


--
-- Name: document_source_table document_source_table_source_node_key_fkey; Type: FK CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.document_source_table
    ADD CONSTRAINT document_source_table_source_node_key_fkey FOREIGN KEY (source_node_key) REFERENCES kb.document_source_node(source_node_key) ON DELETE CASCADE;


--
-- Name: document_tag document_tag_document_id_fkey; Type: FK CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.document_tag
    ADD CONSTRAINT document_tag_document_id_fkey FOREIGN KEY (document_id) REFERENCES kb.document(id) ON DELETE CASCADE;


--
-- Name: document_tag document_tag_tag_id_fkey; Type: FK CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.document_tag
    ADD CONSTRAINT document_tag_tag_id_fkey FOREIGN KEY (tag_id) REFERENCES kb.tag(id) ON DELETE CASCADE;


--
-- Name: document_version document_version_document_id_fkey; Type: FK CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.document_version
    ADD CONSTRAINT document_version_document_id_fkey FOREIGN KEY (document_id) REFERENCES kb.document(id) ON DELETE CASCADE;


--
-- Name: document_version document_version_file_object_id_fkey; Type: FK CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.document_version
    ADD CONSTRAINT document_version_file_object_id_fkey FOREIGN KEY (file_object_id) REFERENCES ops.file_object(id);


--
-- Name: document_version document_version_reviewed_by_fkey; Type: FK CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.document_version
    ADD CONSTRAINT document_version_reviewed_by_fkey FOREIGN KEY (reviewed_by) REFERENCES iam.app_user(id);


--
-- Name: document_chunk fk_kb_chunk_review_revision; Type: FK CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.document_chunk
    ADD CONSTRAINT fk_kb_chunk_review_revision FOREIGN KEY (review_revision_id) REFERENCES kb.document_review_revision(id) ON DELETE CASCADE;


--
-- Name: document fk_kb_document_current_publication; Type: FK CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.document
    ADD CONSTRAINT fk_kb_document_current_publication FOREIGN KEY (current_publication_id) REFERENCES kb.publication(id);


--
-- Name: document_processing_step fk_kb_processing_step_review_revision; Type: FK CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.document_processing_step
    ADD CONSTRAINT fk_kb_processing_step_review_revision FOREIGN KEY (review_revision_id) REFERENCES kb.document_review_revision(id) ON DELETE CASCADE;


--
-- Name: publication fk_kb_publication_review_revision; Type: FK CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.publication
    ADD CONSTRAINT fk_kb_publication_review_revision FOREIGN KEY (review_revision_id) REFERENCES kb.document_review_revision(id);


--
-- Name: publication publication_document_id_fkey; Type: FK CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.publication
    ADD CONSTRAINT publication_document_id_fkey FOREIGN KEY (document_id) REFERENCES kb.document(id) ON DELETE CASCADE;


--
-- Name: publication publication_document_version_id_fkey; Type: FK CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.publication
    ADD CONSTRAINT publication_document_version_id_fkey FOREIGN KEY (document_version_id) REFERENCES kb.document_version(id);


--
-- Name: publication publication_organization_id_fkey; Type: FK CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.publication
    ADD CONSTRAINT publication_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: publication publication_parse_run_id_fkey; Type: FK CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.publication
    ADD CONSTRAINT publication_parse_run_id_fkey FOREIGN KEY (parse_run_id) REFERENCES kb.document_parse_run(id);


--
-- Name: publication publication_published_by_fkey; Type: FK CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.publication
    ADD CONSTRAINT publication_published_by_fkey FOREIGN KEY (published_by) REFERENCES iam.app_user(id);


--
-- Name: tag tag_created_by_fkey; Type: FK CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.tag
    ADD CONSTRAINT tag_created_by_fkey FOREIGN KEY (created_by) REFERENCES iam.app_user(id);


--
-- Name: tag tag_organization_id_fkey; Type: FK CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.tag
    ADD CONSTRAINT tag_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: term_stat term_stat_organization_id_fkey; Type: FK CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.term_stat
    ADD CONSTRAINT term_stat_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: upload_task upload_task_actor_id_fkey; Type: FK CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.upload_task
    ADD CONSTRAINT upload_task_actor_id_fkey FOREIGN KEY (actor_id) REFERENCES iam.app_user(id);


--
-- Name: upload_task upload_task_document_id_fkey; Type: FK CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.upload_task
    ADD CONSTRAINT upload_task_document_id_fkey FOREIGN KEY (document_id) REFERENCES kb.document(id) ON DELETE SET NULL;


--
-- Name: upload_task upload_task_document_version_id_fkey; Type: FK CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.upload_task
    ADD CONSTRAINT upload_task_document_version_id_fkey FOREIGN KEY (document_version_id) REFERENCES kb.document_version(id) ON DELETE SET NULL;


--
-- Name: upload_task upload_task_organization_id_fkey; Type: FK CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.upload_task
    ADD CONSTRAINT upload_task_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: upload_task upload_task_source_file_id_fkey; Type: FK CONSTRAINT; Schema: kb; Owner: -
--

ALTER TABLE ONLY kb.upload_task
    ADD CONSTRAINT upload_task_source_file_id_fkey FOREIGN KEY (source_file_id) REFERENCES ops.file_object(id) ON DELETE SET NULL;


--
-- Name: communication_record communication_record_partner_id_fkey; Type: FK CONSTRAINT; Schema: mdm; Owner: -
--

ALTER TABLE ONLY mdm.communication_record
    ADD CONSTRAINT communication_record_partner_id_fkey FOREIGN KEY (partner_id) REFERENCES mdm.business_partner(id);


--
-- Name: customer_requirement customer_requirement_partner_id_fkey; Type: FK CONSTRAINT; Schema: mdm; Owner: -
--

ALTER TABLE ONLY mdm.customer_requirement
    ADD CONSTRAINT customer_requirement_partner_id_fkey FOREIGN KEY (partner_id) REFERENCES mdm.business_partner(id);


--
-- Name: project fk_mdm_project_organization; Type: FK CONSTRAINT; Schema: mdm; Owner: -
--

ALTER TABLE ONLY mdm.project
    ADD CONSTRAINT fk_mdm_project_organization FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: meeting_minutes fk_meeting_minutes_project; Type: FK CONSTRAINT; Schema: mdm; Owner: -
--

ALTER TABLE ONLY mdm.meeting_minutes
    ADD CONSTRAINT fk_meeting_minutes_project FOREIGN KEY (project_id) REFERENCES mdm.project(id) ON DELETE CASCADE;


--
-- Name: project_document_audit fk_pda_document; Type: FK CONSTRAINT; Schema: mdm; Owner: -
--

ALTER TABLE ONLY mdm.project_document_audit
    ADD CONSTRAINT fk_pda_document FOREIGN KEY (document_id) REFERENCES mdm.project_document(id);


--
-- Name: project_document_review fk_pdr_version; Type: FK CONSTRAINT; Schema: mdm; Owner: -
--

ALTER TABLE ONLY mdm.project_document_review
    ADD CONSTRAINT fk_pdr_version FOREIGN KEY (document_version_id) REFERENCES mdm.project_document_version(id);


--
-- Name: material_alias material_alias_created_by_fkey; Type: FK CONSTRAINT; Schema: mdm; Owner: -
--

ALTER TABLE ONLY mdm.material_alias
    ADD CONSTRAINT material_alias_created_by_fkey FOREIGN KEY (created_by) REFERENCES iam.app_user(id);


--
-- Name: material_alias material_alias_material_id_fkey; Type: FK CONSTRAINT; Schema: mdm; Owner: -
--

ALTER TABLE ONLY mdm.material_alias
    ADD CONSTRAINT material_alias_material_id_fkey FOREIGN KEY (material_id) REFERENCES mdm.material(id);


--
-- Name: material_alias material_alias_organization_id_fkey; Type: FK CONSTRAINT; Schema: mdm; Owner: -
--

ALTER TABLE ONLY mdm.material_alias
    ADD CONSTRAINT material_alias_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: material_alias material_alias_updated_by_fkey; Type: FK CONSTRAINT; Schema: mdm; Owner: -
--

ALTER TABLE ONLY mdm.material_alias
    ADD CONSTRAINT material_alias_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES iam.app_user(id);


--
-- Name: partner_contact partner_contact_partner_id_fkey; Type: FK CONSTRAINT; Schema: mdm; Owner: -
--

ALTER TABLE ONLY mdm.partner_contact
    ADD CONSTRAINT partner_contact_partner_id_fkey FOREIGN KEY (partner_id) REFERENCES mdm.business_partner(id);


--
-- Name: project_document project_document_file_object_id_fkey; Type: FK CONSTRAINT; Schema: mdm; Owner: -
--

ALTER TABLE ONLY mdm.project_document
    ADD CONSTRAINT project_document_file_object_id_fkey FOREIGN KEY (file_object_id) REFERENCES ops.file_object(id);


--
-- Name: project_document project_document_project_id_fkey; Type: FK CONSTRAINT; Schema: mdm; Owner: -
--

ALTER TABLE ONLY mdm.project_document
    ADD CONSTRAINT project_document_project_id_fkey FOREIGN KEY (project_id) REFERENCES mdm.project(id) ON DELETE CASCADE;


--
-- Name: project_document_review project_document_review_document_id_fkey; Type: FK CONSTRAINT; Schema: mdm; Owner: -
--

ALTER TABLE ONLY mdm.project_document_review
    ADD CONSTRAINT project_document_review_document_id_fkey FOREIGN KEY (document_id) REFERENCES mdm.project_document(id) ON DELETE CASCADE;


--
-- Name: project_document project_document_template_id_fkey; Type: FK CONSTRAINT; Schema: mdm; Owner: -
--

ALTER TABLE ONLY mdm.project_document
    ADD CONSTRAINT project_document_template_id_fkey FOREIGN KEY (template_id) REFERENCES tpl.template(id);


--
-- Name: project_document project_document_template_version_id_fkey; Type: FK CONSTRAINT; Schema: mdm; Owner: -
--

ALTER TABLE ONLY mdm.project_document
    ADD CONSTRAINT project_document_template_version_id_fkey FOREIGN KEY (template_version_id) REFERENCES tpl.template_version(id);


--
-- Name: project_document_version project_document_version_document_id_fkey; Type: FK CONSTRAINT; Schema: mdm; Owner: -
--

ALTER TABLE ONLY mdm.project_document_version
    ADD CONSTRAINT project_document_version_document_id_fkey FOREIGN KEY (document_id) REFERENCES mdm.project_document(id) ON DELETE CASCADE;


--
-- Name: project project_partner_id_fkey; Type: FK CONSTRAINT; Schema: mdm; Owner: -
--

ALTER TABLE ONLY mdm.project
    ADD CONSTRAINT project_partner_id_fkey FOREIGN KEY (partner_id) REFERENCES mdm.business_partner(id);


--
-- Name: project_stage project_stage_project_id_fkey; Type: FK CONSTRAINT; Schema: mdm; Owner: -
--

ALTER TABLE ONLY mdm.project_stage
    ADD CONSTRAINT project_stage_project_id_fkey FOREIGN KEY (project_id) REFERENCES mdm.project(id);


--
-- Name: project_task project_task_project_id_fkey; Type: FK CONSTRAINT; Schema: mdm; Owner: -
--

ALTER TABLE ONLY mdm.project_task
    ADD CONSTRAINT project_task_project_id_fkey FOREIGN KEY (project_id) REFERENCES mdm.project(id);


--
-- Name: project_task project_task_stage_id_fkey; Type: FK CONSTRAINT; Schema: mdm; Owner: -
--

ALTER TABLE ONLY mdm.project_task
    ADD CONSTRAINT project_task_stage_id_fkey FOREIGN KEY (stage_id) REFERENCES mdm.project_stage(id);


--
-- Name: inventory_stock_import initial_stock_import_actor_id_fkey; Type: FK CONSTRAINT; Schema: mfg; Owner: -
--

ALTER TABLE ONLY mfg.inventory_stock_import
    ADD CONSTRAINT initial_stock_import_actor_id_fkey FOREIGN KEY (actor_id) REFERENCES iam.app_user(id);


--
-- Name: inventory_stock_import initial_stock_import_organization_id_fkey; Type: FK CONSTRAINT; Schema: mfg; Owner: -
--

ALTER TABLE ONLY mfg.inventory_stock_import
    ADD CONSTRAINT initial_stock_import_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: inventory_policy inventory_policy_created_by_fkey; Type: FK CONSTRAINT; Schema: mfg; Owner: -
--

ALTER TABLE ONLY mfg.inventory_policy
    ADD CONSTRAINT inventory_policy_created_by_fkey FOREIGN KEY (created_by) REFERENCES iam.app_user(id);


--
-- Name: inventory_policy inventory_policy_organization_id_fkey; Type: FK CONSTRAINT; Schema: mfg; Owner: -
--

ALTER TABLE ONLY mfg.inventory_policy
    ADD CONSTRAINT inventory_policy_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: inventory_policy inventory_policy_product_fk; Type: FK CONSTRAINT; Schema: mfg; Owner: -
--

ALTER TABLE ONLY mfg.inventory_policy
    ADD CONSTRAINT inventory_policy_product_fk FOREIGN KEY (organization_id, product_id) REFERENCES mfg.inventory_product(organization_id, id);


--
-- Name: inventory_policy inventory_policy_updated_by_fkey; Type: FK CONSTRAINT; Schema: mfg; Owner: -
--

ALTER TABLE ONLY mfg.inventory_policy
    ADD CONSTRAINT inventory_policy_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES iam.app_user(id);


--
-- Name: inventory_product inventory_product_created_by_fkey; Type: FK CONSTRAINT; Schema: mfg; Owner: -
--

ALTER TABLE ONLY mfg.inventory_product
    ADD CONSTRAINT inventory_product_created_by_fkey FOREIGN KEY (created_by) REFERENCES iam.app_user(id);


--
-- Name: inventory_product inventory_product_organization_id_fkey; Type: FK CONSTRAINT; Schema: mfg; Owner: -
--

ALTER TABLE ONLY mfg.inventory_product
    ADD CONSTRAINT inventory_product_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: inventory_sample_dispatch inventory_sample_customer_fk; Type: FK CONSTRAINT; Schema: mfg; Owner: -
--

ALTER TABLE ONLY mfg.inventory_sample_dispatch
    ADD CONSTRAINT inventory_sample_customer_fk FOREIGN KEY (customer_id) REFERENCES mdm.business_partner(id);


--
-- Name: inventory_shipment inventory_shipment_customer_fk; Type: FK CONSTRAINT; Schema: mfg; Owner: -
--

ALTER TABLE ONLY mfg.inventory_shipment
    ADD CONSTRAINT inventory_shipment_customer_fk FOREIGN KEY (customer_id) REFERENCES mdm.business_partner(id);


--
-- Name: inventory_shipment inventory_shipment_header_fk; Type: FK CONSTRAINT; Schema: mfg; Owner: -
--

ALTER TABLE ONLY mfg.inventory_shipment
    ADD CONSTRAINT inventory_shipment_header_fk FOREIGN KEY (organization_id, shipment_no) REFERENCES mfg.inventory_shipment_header(organization_id, shipment_no);


--
-- Name: inventory_transaction inventory_transaction_actor_id_fkey; Type: FK CONSTRAINT; Schema: mfg; Owner: -
--

ALTER TABLE ONLY mfg.inventory_transaction
    ADD CONSTRAINT inventory_transaction_actor_id_fkey FOREIGN KEY (actor_id) REFERENCES iam.app_user(id);


--
-- Name: inventory_transaction inventory_transaction_organization_id_fkey; Type: FK CONSTRAINT; Schema: mfg; Owner: -
--

ALTER TABLE ONLY mfg.inventory_transaction
    ADD CONSTRAINT inventory_transaction_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: inventory_transaction inventory_transaction_product_fk; Type: FK CONSTRAINT; Schema: mfg; Owner: -
--

ALTER TABLE ONLY mfg.inventory_transaction
    ADD CONSTRAINT inventory_transaction_product_fk FOREIGN KEY (organization_id, product_id) REFERENCES mfg.inventory_product(organization_id, id);


--
-- Name: inventory_transaction inventory_transaction_reversal_of_fkey; Type: FK CONSTRAINT; Schema: mfg; Owner: -
--

ALTER TABLE ONLY mfg.inventory_transaction
    ADD CONSTRAINT inventory_transaction_reversal_of_fkey FOREIGN KEY (reversal_of) REFERENCES mfg.inventory_transaction(id);


--
-- Name: production_ingest_item production_ingest_item_ingest_job_id_fkey; Type: FK CONSTRAINT; Schema: mfg; Owner: -
--

ALTER TABLE ONLY mfg.production_ingest_item
    ADD CONSTRAINT production_ingest_item_ingest_job_id_fkey FOREIGN KEY (ingest_job_id) REFERENCES mfg.production_ingest_job(id) ON DELETE CASCADE;


--
-- Name: production_ingest_job production_ingest_job_created_by_fkey; Type: FK CONSTRAINT; Schema: mfg; Owner: -
--

ALTER TABLE ONLY mfg.production_ingest_job
    ADD CONSTRAINT production_ingest_job_created_by_fkey FOREIGN KEY (created_by) REFERENCES iam.app_user(id);


--
-- Name: production_ingest_job production_ingest_job_organization_id_fkey; Type: FK CONSTRAINT; Schema: mfg; Owner: -
--

ALTER TABLE ONLY mfg.production_ingest_job
    ADD CONSTRAINT production_ingest_job_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: production_ingest_job production_ingest_job_production_order_id_fkey; Type: FK CONSTRAINT; Schema: mfg; Owner: -
--

ALTER TABLE ONLY mfg.production_ingest_job
    ADD CONSTRAINT production_ingest_job_production_order_id_fkey FOREIGN KEY (production_order_id) REFERENCES mfg.production_order(id) ON DELETE CASCADE;


--
-- Name: production_ingest_job production_ingest_job_requested_template_version_id_fkey; Type: FK CONSTRAINT; Schema: mfg; Owner: -
--

ALTER TABLE ONLY mfg.production_ingest_job
    ADD CONSTRAINT production_ingest_job_requested_template_version_id_fkey FOREIGN KEY (requested_template_version_id) REFERENCES tpl.template_version(id);


--
-- Name: production_ingest_job production_ingest_job_selected_template_version_id_fkey; Type: FK CONSTRAINT; Schema: mfg; Owner: -
--

ALTER TABLE ONLY mfg.production_ingest_job
    ADD CONSTRAINT production_ingest_job_selected_template_version_id_fkey FOREIGN KEY (selected_template_version_id) REFERENCES tpl.template_version(id);


--
-- Name: production_ingest_source production_ingest_source_file_object_id_fkey; Type: FK CONSTRAINT; Schema: mfg; Owner: -
--

ALTER TABLE ONLY mfg.production_ingest_source
    ADD CONSTRAINT production_ingest_source_file_object_id_fkey FOREIGN KEY (file_object_id) REFERENCES ops.file_object(id);


--
-- Name: production_ingest_source production_ingest_source_ingest_job_id_fkey; Type: FK CONSTRAINT; Schema: mfg; Owner: -
--

ALTER TABLE ONLY mfg.production_ingest_source
    ADD CONSTRAINT production_ingest_source_ingest_job_id_fkey FOREIGN KEY (ingest_job_id) REFERENCES mfg.production_ingest_job(id) ON DELETE CASCADE;


--
-- Name: production_order production_order_created_by_fkey; Type: FK CONSTRAINT; Schema: mfg; Owner: -
--

ALTER TABLE ONLY mfg.production_order
    ADD CONSTRAINT production_order_created_by_fkey FOREIGN KEY (created_by) REFERENCES iam.app_user(id);


--
-- Name: production_order production_order_draft_editor_snapshot_file_id_fkey; Type: FK CONSTRAINT; Schema: mfg; Owner: -
--

ALTER TABLE ONLY mfg.production_order
    ADD CONSTRAINT production_order_draft_editor_snapshot_file_id_fkey FOREIGN KEY (draft_editor_snapshot_file_id) REFERENCES ops.file_object(id);


--
-- Name: production_order production_order_organization_id_fkey; Type: FK CONSTRAINT; Schema: mfg; Owner: -
--

ALTER TABLE ONLY mfg.production_order
    ADD CONSTRAINT production_order_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: production_order production_order_owner_id_fkey; Type: FK CONSTRAINT; Schema: mfg; Owner: -
--

ALTER TABLE ONLY mfg.production_order
    ADD CONSTRAINT production_order_owner_id_fkey FOREIGN KEY (owner_id) REFERENCES iam.app_user(id);


--
-- Name: production_order production_order_template_version_id_fkey; Type: FK CONSTRAINT; Schema: mfg; Owner: -
--

ALTER TABLE ONLY mfg.production_order
    ADD CONSTRAINT production_order_template_version_id_fkey FOREIGN KEY (template_version_id) REFERENCES tpl.template_version(id);


--
-- Name: production_order production_order_updated_by_fkey; Type: FK CONSTRAINT; Schema: mfg; Owner: -
--

ALTER TABLE ONLY mfg.production_order
    ADD CONSTRAINT production_order_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES iam.app_user(id);


--
-- Name: production_upload production_upload_async_job_id_fkey; Type: FK CONSTRAINT; Schema: mfg; Owner: -
--

ALTER TABLE ONLY mfg.production_upload
    ADD CONSTRAINT production_upload_async_job_id_fkey FOREIGN KEY (async_job_id) REFERENCES ops.async_job(id);


--
-- Name: production_upload production_upload_created_by_fkey; Type: FK CONSTRAINT; Schema: mfg; Owner: -
--

ALTER TABLE ONLY mfg.production_upload
    ADD CONSTRAINT production_upload_created_by_fkey FOREIGN KEY (created_by) REFERENCES iam.app_user(id);


--
-- Name: production_upload_field production_upload_field_production_upload_id_fkey; Type: FK CONSTRAINT; Schema: mfg; Owner: -
--

ALTER TABLE ONLY mfg.production_upload_field
    ADD CONSTRAINT production_upload_field_production_upload_id_fkey FOREIGN KEY (production_upload_id) REFERENCES mfg.production_upload(id) ON DELETE CASCADE;


--
-- Name: production_upload production_upload_file_id_fkey; Type: FK CONSTRAINT; Schema: mfg; Owner: -
--

ALTER TABLE ONLY mfg.production_upload
    ADD CONSTRAINT production_upload_file_id_fkey FOREIGN KEY (file_id) REFERENCES ops.file_object(id);


--
-- Name: production_upload production_upload_organization_id_fkey; Type: FK CONSTRAINT; Schema: mfg; Owner: -
--

ALTER TABLE ONLY mfg.production_upload
    ADD CONSTRAINT production_upload_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: production_upload production_upload_selected_template_version_id_fkey; Type: FK CONSTRAINT; Schema: mfg; Owner: -
--

ALTER TABLE ONLY mfg.production_upload
    ADD CONSTRAINT production_upload_selected_template_version_id_fkey FOREIGN KEY (selected_template_version_id) REFERENCES tpl.template_version(id);


--
-- Name: production_upload production_upload_updated_by_fkey; Type: FK CONSTRAINT; Schema: mfg; Owner: -
--

ALTER TABLE ONLY mfg.production_upload
    ADD CONSTRAINT production_upload_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES iam.app_user(id);


--
-- Name: production_upload_version production_upload_version_created_by_fkey; Type: FK CONSTRAINT; Schema: mfg; Owner: -
--

ALTER TABLE ONLY mfg.production_upload_version
    ADD CONSTRAINT production_upload_version_created_by_fkey FOREIGN KEY (created_by) REFERENCES iam.app_user(id);


--
-- Name: production_upload_version production_upload_version_organization_id_fkey; Type: FK CONSTRAINT; Schema: mfg; Owner: -
--

ALTER TABLE ONLY mfg.production_upload_version
    ADD CONSTRAINT production_upload_version_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: production_upload_version production_upload_version_production_upload_id_fkey; Type: FK CONSTRAINT; Schema: mfg; Owner: -
--

ALTER TABLE ONLY mfg.production_upload_version
    ADD CONSTRAINT production_upload_version_production_upload_id_fkey FOREIGN KEY (production_upload_id) REFERENCES mfg.production_upload(id);


--
-- Name: inventory_sample_dispatch sample_dispatch_actor_id_fkey; Type: FK CONSTRAINT; Schema: mfg; Owner: -
--

ALTER TABLE ONLY mfg.inventory_sample_dispatch
    ADD CONSTRAINT sample_dispatch_actor_id_fkey FOREIGN KEY (actor_id) REFERENCES iam.app_user(id);


--
-- Name: inventory_sample_dispatch sample_dispatch_inventory_transaction_id_fkey; Type: FK CONSTRAINT; Schema: mfg; Owner: -
--

ALTER TABLE ONLY mfg.inventory_sample_dispatch
    ADD CONSTRAINT sample_dispatch_inventory_transaction_id_fkey FOREIGN KEY (inventory_transaction_id) REFERENCES mfg.inventory_transaction(id);


--
-- Name: inventory_sample_dispatch sample_dispatch_organization_id_fkey; Type: FK CONSTRAINT; Schema: mfg; Owner: -
--

ALTER TABLE ONLY mfg.inventory_sample_dispatch
    ADD CONSTRAINT sample_dispatch_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: inventory_sample_dispatch sample_dispatch_product_fk; Type: FK CONSTRAINT; Schema: mfg; Owner: -
--

ALTER TABLE ONLY mfg.inventory_sample_dispatch
    ADD CONSTRAINT sample_dispatch_product_fk FOREIGN KEY (organization_id, product_id) REFERENCES mfg.inventory_product(organization_id, id);


--
-- Name: sample_recipient_preference sample_recipient_preference_customer_id_fkey; Type: FK CONSTRAINT; Schema: mfg; Owner: -
--

ALTER TABLE ONLY mfg.sample_recipient_preference
    ADD CONSTRAINT sample_recipient_preference_customer_id_fkey FOREIGN KEY (customer_id) REFERENCES mdm.business_partner(id) ON DELETE CASCADE;


--
-- Name: inventory_shipment shipment_actor_id_fkey; Type: FK CONSTRAINT; Schema: mfg; Owner: -
--

ALTER TABLE ONLY mfg.inventory_shipment
    ADD CONSTRAINT shipment_actor_id_fkey FOREIGN KEY (actor_id) REFERENCES iam.app_user(id);


--
-- Name: inventory_shipment shipment_inventory_transaction_id_fkey; Type: FK CONSTRAINT; Schema: mfg; Owner: -
--

ALTER TABLE ONLY mfg.inventory_shipment
    ADD CONSTRAINT shipment_inventory_transaction_id_fkey FOREIGN KEY (inventory_transaction_id) REFERENCES mfg.inventory_transaction(id);


--
-- Name: inventory_shipment shipment_organization_id_fkey; Type: FK CONSTRAINT; Schema: mfg; Owner: -
--

ALTER TABLE ONLY mfg.inventory_shipment
    ADD CONSTRAINT shipment_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: inventory_shipment shipment_product_fk; Type: FK CONSTRAINT; Schema: mfg; Owner: -
--

ALTER TABLE ONLY mfg.inventory_shipment
    ADD CONSTRAINT shipment_product_fk FOREIGN KEY (organization_id, product_id) REFERENCES mfg.inventory_product(organization_id, id);


--
-- Name: inventory_stock_balance stock_balance_organization_id_fkey; Type: FK CONSTRAINT; Schema: mfg; Owner: -
--

ALTER TABLE ONLY mfg.inventory_stock_balance
    ADD CONSTRAINT stock_balance_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: inventory_stock_balance stock_balance_product_fk; Type: FK CONSTRAINT; Schema: mfg; Owner: -
--

ALTER TABLE ONLY mfg.inventory_stock_balance
    ADD CONSTRAINT stock_balance_product_fk FOREIGN KEY (organization_id, product_id) REFERENCES mfg.inventory_product(organization_id, id);


--
-- Name: async_job async_job_organization_id_fkey; Type: FK CONSTRAINT; Schema: ops; Owner: -
--

ALTER TABLE ONLY ops.async_job
    ADD CONSTRAINT async_job_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: audit_log audit_log_actor_id_fkey; Type: FK CONSTRAINT; Schema: ops; Owner: -
--

ALTER TABLE ONLY ops.audit_log
    ADD CONSTRAINT audit_log_actor_id_fkey FOREIGN KEY (actor_id) REFERENCES iam.app_user(id);


--
-- Name: audit_log audit_log_organization_id_fkey; Type: FK CONSTRAINT; Schema: ops; Owner: -
--

ALTER TABLE ONLY ops.audit_log
    ADD CONSTRAINT audit_log_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: file_object file_object_created_by_fkey; Type: FK CONSTRAINT; Schema: ops; Owner: -
--

ALTER TABLE ONLY ops.file_object
    ADD CONSTRAINT file_object_created_by_fkey FOREIGN KEY (created_by) REFERENCES iam.app_user(id);


--
-- Name: file_object file_object_organization_id_fkey; Type: FK CONSTRAINT; Schema: ops; Owner: -
--

ALTER TABLE ONLY ops.file_object
    ADD CONSTRAINT file_object_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: outbox_event outbox_event_organization_id_fkey; Type: FK CONSTRAINT; Schema: ops; Owner: -
--

ALTER TABLE ONLY ops.outbox_event
    ADD CONSTRAINT outbox_event_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: data_category data_category_created_by_fkey; Type: FK CONSTRAINT; Schema: quality; Owner: -
--

ALTER TABLE ONLY quality.data_category
    ADD CONSTRAINT data_category_created_by_fkey FOREIGN KEY (created_by) REFERENCES iam.app_user(id);


--
-- Name: data_category data_category_organization_id_fkey; Type: FK CONSTRAINT; Schema: quality; Owner: -
--

ALTER TABLE ONLY quality.data_category
    ADD CONSTRAINT data_category_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: data_record data_record_category_id_fkey; Type: FK CONSTRAINT; Schema: quality; Owner: -
--

ALTER TABLE ONLY quality.data_record
    ADD CONSTRAINT data_record_category_id_fkey FOREIGN KEY (category_id) REFERENCES quality.data_category(id);


--
-- Name: data_record data_record_created_by_fkey; Type: FK CONSTRAINT; Schema: quality; Owner: -
--

ALTER TABLE ONLY quality.data_record
    ADD CONSTRAINT data_record_created_by_fkey FOREIGN KEY (created_by) REFERENCES iam.app_user(id);


--
-- Name: data_record data_record_organization_id_fkey; Type: FK CONSTRAINT; Schema: quality; Owner: -
--

ALTER TABLE ONLY quality.data_record
    ADD CONSTRAINT data_record_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: data_record data_record_source_file_id_fkey; Type: FK CONSTRAINT; Schema: quality; Owner: -
--

ALTER TABLE ONLY quality.data_record
    ADD CONSTRAINT data_record_source_file_id_fkey FOREIGN KEY (source_file_id) REFERENCES ops.file_object(id);


--
-- Name: data_record data_record_updated_by_fkey; Type: FK CONSTRAINT; Schema: quality; Owner: -
--

ALTER TABLE ONLY quality.data_record
    ADD CONSTRAINT data_record_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES iam.app_user(id);


--
-- Name: data_record_version data_record_version_created_by_fkey; Type: FK CONSTRAINT; Schema: quality; Owner: -
--

ALTER TABLE ONLY quality.data_record_version
    ADD CONSTRAINT data_record_version_created_by_fkey FOREIGN KEY (created_by) REFERENCES iam.app_user(id);


--
-- Name: data_record_version data_record_version_organization_id_fkey; Type: FK CONSTRAINT; Schema: quality; Owner: -
--

ALTER TABLE ONLY quality.data_record_version
    ADD CONSTRAINT data_record_version_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: data_record_version data_record_version_record_id_fkey; Type: FK CONSTRAINT; Schema: quality; Owner: -
--

ALTER TABLE ONLY quality.data_record_version
    ADD CONSTRAINT data_record_version_record_id_fkey FOREIGN KEY (record_id) REFERENCES quality.data_record(id) ON DELETE CASCADE;


--
-- Name: upload_file upload_file_category_id_fkey; Type: FK CONSTRAINT; Schema: quality; Owner: -
--

ALTER TABLE ONLY quality.upload_file
    ADD CONSTRAINT upload_file_category_id_fkey FOREIGN KEY (category_id) REFERENCES quality.data_category(id);


--
-- Name: upload_file upload_file_created_by_fkey; Type: FK CONSTRAINT; Schema: quality; Owner: -
--

ALTER TABLE ONLY quality.upload_file
    ADD CONSTRAINT upload_file_created_by_fkey FOREIGN KEY (created_by) REFERENCES iam.app_user(id);


--
-- Name: upload_file upload_file_file_id_fkey; Type: FK CONSTRAINT; Schema: quality; Owner: -
--

ALTER TABLE ONLY quality.upload_file
    ADD CONSTRAINT upload_file_file_id_fkey FOREIGN KEY (file_id) REFERENCES ops.file_object(id);


--
-- Name: upload_file upload_file_generated_record_id_fkey; Type: FK CONSTRAINT; Schema: quality; Owner: -
--

ALTER TABLE ONLY quality.upload_file
    ADD CONSTRAINT upload_file_generated_record_id_fkey FOREIGN KEY (generated_record_id) REFERENCES quality.data_record(id);


--
-- Name: upload_file upload_file_organization_id_fkey; Type: FK CONSTRAINT; Schema: quality; Owner: -
--

ALTER TABLE ONLY quality.upload_file
    ADD CONSTRAINT upload_file_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: experiment_ai_source_evidence experiment_ai_source_evidence_captured_by_fkey; Type: FK CONSTRAINT; Schema: rnd; Owner: -
--

ALTER TABLE ONLY rnd.experiment_ai_source_evidence
    ADD CONSTRAINT experiment_ai_source_evidence_captured_by_fkey FOREIGN KEY (captured_by) REFERENCES iam.app_user(id);


--
-- Name: experiment_ai_source_evidence experiment_ai_source_evidence_organization_id_experiment_i_fkey; Type: FK CONSTRAINT; Schema: rnd; Owner: -
--

ALTER TABLE ONLY rnd.experiment_ai_source_evidence
    ADD CONSTRAINT experiment_ai_source_evidence_organization_id_experiment_i_fkey FOREIGN KEY (organization_id, experiment_id) REFERENCES rnd.experiment(organization_id, id);


--
-- Name: experiment_ai_source_evidence experiment_ai_source_evidence_organization_id_experiment_v_fkey; Type: FK CONSTRAINT; Schema: rnd; Owner: -
--

ALTER TABLE ONLY rnd.experiment_ai_source_evidence
    ADD CONSTRAINT experiment_ai_source_evidence_organization_id_experiment_v_fkey FOREIGN KEY (organization_id, experiment_version_id) REFERENCES rnd.experiment_version(organization_id, id);


--
-- Name: experiment_ai_source_evidence experiment_ai_source_evidence_organization_id_fkey; Type: FK CONSTRAINT; Schema: rnd; Owner: -
--

ALTER TABLE ONLY rnd.experiment_ai_source_evidence
    ADD CONSTRAINT experiment_ai_source_evidence_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: experiment_attachment experiment_attachment_experiment_id_fkey; Type: FK CONSTRAINT; Schema: rnd; Owner: -
--

ALTER TABLE ONLY rnd.experiment_attachment
    ADD CONSTRAINT experiment_attachment_experiment_id_fkey FOREIGN KEY (experiment_id) REFERENCES rnd.experiment(id);


--
-- Name: experiment_attachment experiment_attachment_experiment_version_id_fkey; Type: FK CONSTRAINT; Schema: rnd; Owner: -
--

ALTER TABLE ONLY rnd.experiment_attachment
    ADD CONSTRAINT experiment_attachment_experiment_version_id_fkey FOREIGN KEY (experiment_version_id) REFERENCES rnd.experiment_version(id);


--
-- Name: experiment_attachment experiment_attachment_organization_id_fkey; Type: FK CONSTRAINT; Schema: rnd; Owner: -
--

ALTER TABLE ONLY rnd.experiment_attachment
    ADD CONSTRAINT experiment_attachment_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: experiment_audit experiment_audit_experiment_id_fkey; Type: FK CONSTRAINT; Schema: rnd; Owner: -
--

ALTER TABLE ONLY rnd.experiment_audit
    ADD CONSTRAINT experiment_audit_experiment_id_fkey FOREIGN KEY (experiment_id) REFERENCES rnd.experiment(id);


--
-- Name: experiment_audit experiment_audit_organization_id_fkey; Type: FK CONSTRAINT; Schema: rnd; Owner: -
--

ALTER TABLE ONLY rnd.experiment_audit
    ADD CONSTRAINT experiment_audit_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: experiment experiment_category_id_fkey; Type: FK CONSTRAINT; Schema: rnd; Owner: -
--

ALTER TABLE ONLY rnd.experiment
    ADD CONSTRAINT experiment_category_id_fkey FOREIGN KEY (category_id) REFERENCES rnd.experiment_category(id);


--
-- Name: experiment_category experiment_category_organization_id_fkey; Type: FK CONSTRAINT; Schema: rnd; Owner: -
--

ALTER TABLE ONLY rnd.experiment_category
    ADD CONSTRAINT experiment_category_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: experiment_import_job experiment_import_job_experiment_id_fkey; Type: FK CONSTRAINT; Schema: rnd; Owner: -
--

ALTER TABLE ONLY rnd.experiment_import_job
    ADD CONSTRAINT experiment_import_job_experiment_id_fkey FOREIGN KEY (experiment_id) REFERENCES rnd.experiment(id);


--
-- Name: experiment_import_job experiment_import_job_organization_id_fkey; Type: FK CONSTRAINT; Schema: rnd; Owner: -
--

ALTER TABLE ONLY rnd.experiment_import_job
    ADD CONSTRAINT experiment_import_job_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: experiment experiment_organization_id_fkey; Type: FK CONSTRAINT; Schema: rnd; Owner: -
--

ALTER TABLE ONLY rnd.experiment
    ADD CONSTRAINT experiment_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: experiment_outbox experiment_outbox_organization_id_fkey; Type: FK CONSTRAINT; Schema: rnd; Owner: -
--

ALTER TABLE ONLY rnd.experiment_outbox
    ADD CONSTRAINT experiment_outbox_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: experiment_report experiment_report_experiment_id_fkey; Type: FK CONSTRAINT; Schema: rnd; Owner: -
--

ALTER TABLE ONLY rnd.experiment_report
    ADD CONSTRAINT experiment_report_experiment_id_fkey FOREIGN KEY (experiment_id) REFERENCES rnd.experiment(id);


--
-- Name: experiment_report experiment_report_experiment_version_id_fkey; Type: FK CONSTRAINT; Schema: rnd; Owner: -
--

ALTER TABLE ONLY rnd.experiment_report
    ADD CONSTRAINT experiment_report_experiment_version_id_fkey FOREIGN KEY (experiment_version_id) REFERENCES rnd.experiment_version(id);


--
-- Name: experiment_report experiment_report_organization_id_fkey; Type: FK CONSTRAINT; Schema: rnd; Owner: -
--

ALTER TABLE ONLY rnd.experiment_report
    ADD CONSTRAINT experiment_report_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: experiment_review experiment_review_experiment_id_fkey; Type: FK CONSTRAINT; Schema: rnd; Owner: -
--

ALTER TABLE ONLY rnd.experiment_review
    ADD CONSTRAINT experiment_review_experiment_id_fkey FOREIGN KEY (experiment_id) REFERENCES rnd.experiment(id);


--
-- Name: experiment_review experiment_review_experiment_version_id_fkey; Type: FK CONSTRAINT; Schema: rnd; Owner: -
--

ALTER TABLE ONLY rnd.experiment_review
    ADD CONSTRAINT experiment_review_experiment_version_id_fkey FOREIGN KEY (experiment_version_id) REFERENCES rnd.experiment_version(id);


--
-- Name: experiment_review experiment_review_organization_id_fkey; Type: FK CONSTRAINT; Schema: rnd; Owner: -
--

ALTER TABLE ONLY rnd.experiment_review
    ADD CONSTRAINT experiment_review_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: experiment experiment_source_file_id_fkey; Type: FK CONSTRAINT; Schema: rnd; Owner: -
--

ALTER TABLE ONLY rnd.experiment
    ADD CONSTRAINT experiment_source_file_id_fkey FOREIGN KEY (source_file_id) REFERENCES ops.file_object(id);


--
-- Name: experiment_source_reference experiment_source_reference_created_by_fkey; Type: FK CONSTRAINT; Schema: rnd; Owner: -
--

ALTER TABLE ONLY rnd.experiment_source_reference
    ADD CONSTRAINT experiment_source_reference_created_by_fkey FOREIGN KEY (created_by) REFERENCES iam.app_user(id);


--
-- Name: experiment_source_reference experiment_source_reference_organization_id_confirmed_subm_fkey; Type: FK CONSTRAINT; Schema: rnd; Owner: -
--

ALTER TABLE ONLY rnd.experiment_source_reference
    ADD CONSTRAINT experiment_source_reference_organization_id_confirmed_subm_fkey FOREIGN KEY (organization_id, confirmed_submission_id) REFERENCES data.confirmed_submission(organization_id, id);


--
-- Name: experiment_source_reference experiment_source_reference_organization_id_experiment_id_fkey; Type: FK CONSTRAINT; Schema: rnd; Owner: -
--

ALTER TABLE ONLY rnd.experiment_source_reference
    ADD CONSTRAINT experiment_source_reference_organization_id_experiment_id_fkey FOREIGN KEY (organization_id, experiment_id) REFERENCES rnd.experiment(organization_id, id);


--
-- Name: experiment_source_reference experiment_source_reference_organization_id_experiment_imp_fkey; Type: FK CONSTRAINT; Schema: rnd; Owner: -
--

ALTER TABLE ONLY rnd.experiment_source_reference
    ADD CONSTRAINT experiment_source_reference_organization_id_experiment_imp_fkey FOREIGN KEY (organization_id, experiment_import_job_id) REFERENCES rnd.experiment_import_job(organization_id, id);


--
-- Name: experiment_source_reference experiment_source_reference_organization_id_experiment_ver_fkey; Type: FK CONSTRAINT; Schema: rnd; Owner: -
--

ALTER TABLE ONLY rnd.experiment_source_reference
    ADD CONSTRAINT experiment_source_reference_organization_id_experiment_ver_fkey FOREIGN KEY (organization_id, experiment_version_id) REFERENCES rnd.experiment_version(organization_id, id);


--
-- Name: experiment_source_reference experiment_source_reference_organization_id_fkey; Type: FK CONSTRAINT; Schema: rnd; Owner: -
--

ALTER TABLE ONLY rnd.experiment_source_reference
    ADD CONSTRAINT experiment_source_reference_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: experiment_source_reference experiment_source_reference_organization_id_recognition_jo_fkey; Type: FK CONSTRAINT; Schema: rnd; Owner: -
--

ALTER TABLE ONLY rnd.experiment_source_reference
    ADD CONSTRAINT experiment_source_reference_organization_id_recognition_jo_fkey FOREIGN KEY (organization_id, recognition_job_id) REFERENCES data.import_job(organization_id, id);


--
-- Name: experiment_source_reference experiment_source_reference_source_file_id_fkey; Type: FK CONSTRAINT; Schema: rnd; Owner: -
--

ALTER TABLE ONLY rnd.experiment_source_reference
    ADD CONSTRAINT experiment_source_reference_source_file_id_fkey FOREIGN KEY (source_file_id) REFERENCES ops.file_object(id);


--
-- Name: experiment_version experiment_version_experiment_id_fkey; Type: FK CONSTRAINT; Schema: rnd; Owner: -
--

ALTER TABLE ONLY rnd.experiment_version
    ADD CONSTRAINT experiment_version_experiment_id_fkey FOREIGN KEY (experiment_id) REFERENCES rnd.experiment(id) ON DELETE CASCADE;


--
-- Name: experiment_version experiment_version_organization_id_fkey; Type: FK CONSTRAINT; Schema: rnd; Owner: -
--

ALTER TABLE ONLY rnd.experiment_version
    ADD CONSTRAINT experiment_version_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: experiment fk_experiment_current_version; Type: FK CONSTRAINT; Schema: rnd; Owner: -
--

ALTER TABLE ONLY rnd.experiment
    ADD CONSTRAINT fk_experiment_current_version FOREIGN KEY (current_version_id) REFERENCES rnd.experiment_version(id);


--
-- Name: research_test_record fk_research_test_current_version; Type: FK CONSTRAINT; Schema: rnd; Owner: -
--

ALTER TABLE ONLY rnd.research_test_record
    ADD CONSTRAINT fk_research_test_current_version FOREIGN KEY (current_version_id) REFERENCES rnd.research_test_version(id);


--
-- Name: research_test_audit research_test_audit_operator_id_fkey; Type: FK CONSTRAINT; Schema: rnd; Owner: -
--

ALTER TABLE ONLY rnd.research_test_audit
    ADD CONSTRAINT research_test_audit_operator_id_fkey FOREIGN KEY (operator_id) REFERENCES iam.app_user(id);


--
-- Name: research_test_audit research_test_audit_organization_id_fkey; Type: FK CONSTRAINT; Schema: rnd; Owner: -
--

ALTER TABLE ONLY rnd.research_test_audit
    ADD CONSTRAINT research_test_audit_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: research_test_audit research_test_audit_record_id_fkey; Type: FK CONSTRAINT; Schema: rnd; Owner: -
--

ALTER TABLE ONLY rnd.research_test_audit
    ADD CONSTRAINT research_test_audit_record_id_fkey FOREIGN KEY (record_id) REFERENCES rnd.research_test_record(id) ON DELETE CASCADE;


--
-- Name: research_test_audit research_test_audit_version_id_fkey; Type: FK CONSTRAINT; Schema: rnd; Owner: -
--

ALTER TABLE ONLY rnd.research_test_audit
    ADD CONSTRAINT research_test_audit_version_id_fkey FOREIGN KEY (version_id) REFERENCES rnd.research_test_version(id);


--
-- Name: research_test_record research_test_record_created_by_fkey; Type: FK CONSTRAINT; Schema: rnd; Owner: -
--

ALTER TABLE ONLY rnd.research_test_record
    ADD CONSTRAINT research_test_record_created_by_fkey FOREIGN KEY (created_by) REFERENCES iam.app_user(id);


--
-- Name: research_test_record research_test_record_organization_id_fkey; Type: FK CONSTRAINT; Schema: rnd; Owner: -
--

ALTER TABLE ONLY rnd.research_test_record
    ADD CONSTRAINT research_test_record_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: research_test_record research_test_record_owner_id_fkey; Type: FK CONSTRAINT; Schema: rnd; Owner: -
--

ALTER TABLE ONLY rnd.research_test_record
    ADD CONSTRAINT research_test_record_owner_id_fkey FOREIGN KEY (owner_id) REFERENCES iam.app_user(id);


--
-- Name: research_test_record research_test_record_source_file_id_fkey; Type: FK CONSTRAINT; Schema: rnd; Owner: -
--

ALTER TABLE ONLY rnd.research_test_record
    ADD CONSTRAINT research_test_record_source_file_id_fkey FOREIGN KEY (source_file_id) REFERENCES ops.file_object(id);


--
-- Name: research_test_record research_test_record_updated_by_fkey; Type: FK CONSTRAINT; Schema: rnd; Owner: -
--

ALTER TABLE ONLY rnd.research_test_record
    ADD CONSTRAINT research_test_record_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES iam.app_user(id);


--
-- Name: research_test_review research_test_review_operator_id_fkey; Type: FK CONSTRAINT; Schema: rnd; Owner: -
--

ALTER TABLE ONLY rnd.research_test_review
    ADD CONSTRAINT research_test_review_operator_id_fkey FOREIGN KEY (operator_id) REFERENCES iam.app_user(id);


--
-- Name: research_test_review research_test_review_organization_id_fkey; Type: FK CONSTRAINT; Schema: rnd; Owner: -
--

ALTER TABLE ONLY rnd.research_test_review
    ADD CONSTRAINT research_test_review_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: research_test_review research_test_review_record_id_fkey; Type: FK CONSTRAINT; Schema: rnd; Owner: -
--

ALTER TABLE ONLY rnd.research_test_review
    ADD CONSTRAINT research_test_review_record_id_fkey FOREIGN KEY (record_id) REFERENCES rnd.research_test_record(id) ON DELETE CASCADE;


--
-- Name: research_test_review research_test_review_version_id_fkey; Type: FK CONSTRAINT; Schema: rnd; Owner: -
--

ALTER TABLE ONLY rnd.research_test_review
    ADD CONSTRAINT research_test_review_version_id_fkey FOREIGN KEY (version_id) REFERENCES rnd.research_test_version(id) ON DELETE CASCADE;


--
-- Name: research_test_upload research_test_upload_created_by_fkey; Type: FK CONSTRAINT; Schema: rnd; Owner: -
--

ALTER TABLE ONLY rnd.research_test_upload
    ADD CONSTRAINT research_test_upload_created_by_fkey FOREIGN KEY (created_by) REFERENCES iam.app_user(id);


--
-- Name: research_test_upload research_test_upload_file_id_fkey; Type: FK CONSTRAINT; Schema: rnd; Owner: -
--

ALTER TABLE ONLY rnd.research_test_upload
    ADD CONSTRAINT research_test_upload_file_id_fkey FOREIGN KEY (file_id) REFERENCES ops.file_object(id);


--
-- Name: research_test_upload research_test_upload_organization_id_fkey; Type: FK CONSTRAINT; Schema: rnd; Owner: -
--

ALTER TABLE ONLY rnd.research_test_upload
    ADD CONSTRAINT research_test_upload_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: research_test_upload research_test_upload_record_id_fkey; Type: FK CONSTRAINT; Schema: rnd; Owner: -
--

ALTER TABLE ONLY rnd.research_test_upload
    ADD CONSTRAINT research_test_upload_record_id_fkey FOREIGN KEY (record_id) REFERENCES rnd.research_test_record(id);


--
-- Name: research_test_version research_test_version_created_by_fkey; Type: FK CONSTRAINT; Schema: rnd; Owner: -
--

ALTER TABLE ONLY rnd.research_test_version
    ADD CONSTRAINT research_test_version_created_by_fkey FOREIGN KEY (created_by) REFERENCES iam.app_user(id);


--
-- Name: research_test_version research_test_version_organization_id_fkey; Type: FK CONSTRAINT; Schema: rnd; Owner: -
--

ALTER TABLE ONLY rnd.research_test_version
    ADD CONSTRAINT research_test_version_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: research_test_version research_test_version_record_id_fkey; Type: FK CONSTRAINT; Schema: rnd; Owner: -
--

ALTER TABLE ONLY rnd.research_test_version
    ADD CONSTRAINT research_test_version_record_id_fkey FOREIGN KEY (record_id) REFERENCES rnd.research_test_record(id) ON DELETE CASCADE;


--
-- Name: analysis_event analysis_event_analysis_run_id_fkey; Type: FK CONSTRAINT; Schema: spc; Owner: -
--

ALTER TABLE ONLY spc.analysis_event
    ADD CONSTRAINT analysis_event_analysis_run_id_fkey FOREIGN KEY (analysis_run_id) REFERENCES spc.analysis_run(id) ON DELETE CASCADE;


--
-- Name: analysis_event analysis_event_organization_id_fkey; Type: FK CONSTRAINT; Schema: spc; Owner: -
--

ALTER TABLE ONLY spc.analysis_event
    ADD CONSTRAINT analysis_event_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: analysis_run analysis_run_created_by_fkey; Type: FK CONSTRAINT; Schema: spc; Owner: -
--

ALTER TABLE ONLY spc.analysis_run
    ADD CONSTRAINT analysis_run_created_by_fkey FOREIGN KEY (created_by) REFERENCES iam.app_user(id);


--
-- Name: analysis_run analysis_run_organization_id_fkey; Type: FK CONSTRAINT; Schema: spc; Owner: -
--

ALTER TABLE ONLY spc.analysis_run
    ADD CONSTRAINT analysis_run_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: analysis_run analysis_run_session_id_fkey; Type: FK CONSTRAINT; Schema: spc; Owner: -
--

ALTER TABLE ONLY spc.analysis_run
    ADD CONSTRAINT analysis_run_session_id_fkey FOREIGN KEY (session_id) REFERENCES spc.chat_session(id) ON DELETE CASCADE;


--
-- Name: chart_asset chart_asset_category_id_fkey; Type: FK CONSTRAINT; Schema: spc; Owner: -
--

ALTER TABLE ONLY spc.chart_asset
    ADD CONSTRAINT chart_asset_category_id_fkey FOREIGN KEY (category_id) REFERENCES spc.chart_category(id);


--
-- Name: chart_asset chart_asset_created_by_fkey; Type: FK CONSTRAINT; Schema: spc; Owner: -
--

ALTER TABLE ONLY spc.chart_asset
    ADD CONSTRAINT chart_asset_created_by_fkey FOREIGN KEY (created_by) REFERENCES iam.app_user(id);


--
-- Name: chart_asset chart_asset_organization_id_fkey; Type: FK CONSTRAINT; Schema: spc; Owner: -
--

ALTER TABLE ONLY spc.chart_asset
    ADD CONSTRAINT chart_asset_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: chart_category chart_category_created_by_fkey; Type: FK CONSTRAINT; Schema: spc; Owner: -
--

ALTER TABLE ONLY spc.chart_category
    ADD CONSTRAINT chart_category_created_by_fkey FOREIGN KEY (created_by) REFERENCES iam.app_user(id);


--
-- Name: chart_category chart_category_organization_id_fkey; Type: FK CONSTRAINT; Schema: spc; Owner: -
--

ALTER TABLE ONLY spc.chart_category
    ADD CONSTRAINT chart_category_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: chart_upload_attempt chart_upload_attempt_chart_id_fkey; Type: FK CONSTRAINT; Schema: spc; Owner: -
--

ALTER TABLE ONLY spc.chart_upload_attempt
    ADD CONSTRAINT chart_upload_attempt_chart_id_fkey FOREIGN KEY (chart_id) REFERENCES spc.chart_asset(id);


--
-- Name: chart_upload_attempt chart_upload_attempt_created_by_fkey; Type: FK CONSTRAINT; Schema: spc; Owner: -
--

ALTER TABLE ONLY spc.chart_upload_attempt
    ADD CONSTRAINT chart_upload_attempt_created_by_fkey FOREIGN KEY (created_by) REFERENCES iam.app_user(id);


--
-- Name: chart_upload_attempt chart_upload_attempt_organization_id_fkey; Type: FK CONSTRAINT; Schema: spc; Owner: -
--

ALTER TABLE ONLY spc.chart_upload_attempt
    ADD CONSTRAINT chart_upload_attempt_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: chat_message chat_message_analysis_run_id_fkey; Type: FK CONSTRAINT; Schema: spc; Owner: -
--

ALTER TABLE ONLY spc.chat_message
    ADD CONSTRAINT chat_message_analysis_run_id_fkey FOREIGN KEY (analysis_run_id) REFERENCES spc.analysis_run(id) ON DELETE SET NULL;


--
-- Name: chat_message chat_message_organization_id_fkey; Type: FK CONSTRAINT; Schema: spc; Owner: -
--

ALTER TABLE ONLY spc.chat_message
    ADD CONSTRAINT chat_message_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: chat_message chat_message_session_id_fkey; Type: FK CONSTRAINT; Schema: spc; Owner: -
--

ALTER TABLE ONLY spc.chat_message
    ADD CONSTRAINT chat_message_session_id_fkey FOREIGN KEY (session_id) REFERENCES spc.chat_session(id) ON DELETE CASCADE;


--
-- Name: chat_session chat_session_created_by_fkey; Type: FK CONSTRAINT; Schema: spc; Owner: -
--

ALTER TABLE ONLY spc.chat_session
    ADD CONSTRAINT chat_session_created_by_fkey FOREIGN KEY (created_by) REFERENCES iam.app_user(id);


--
-- Name: chat_session chat_session_organization_id_fkey; Type: FK CONSTRAINT; Schema: spc; Owner: -
--

ALTER TABLE ONLY spc.chat_session
    ADD CONSTRAINT chat_session_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: analysis_run fk_spc_analysis_user_message; Type: FK CONSTRAINT; Schema: spc; Owner: -
--

ALTER TABLE ONLY spc.analysis_run
    ADD CONSTRAINT fk_spc_analysis_user_message FOREIGN KEY (user_message_id) REFERENCES spc.chat_message(id) ON DELETE SET NULL;


--
-- Name: template fk_template_current_published; Type: FK CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.template
    ADD CONSTRAINT fk_template_current_published FOREIGN KEY (current_published_version_id) REFERENCES tpl.template_version(id);


--
-- Name: recognition_call recognition_call_parent_call_id_fkey; Type: FK CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.recognition_call
    ADD CONSTRAINT recognition_call_parent_call_id_fkey FOREIGN KEY (parent_call_id) REFERENCES tpl.recognition_call(id) ON DELETE SET NULL;


--
-- Name: recognition_call recognition_call_recognition_run_id_fkey; Type: FK CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.recognition_call
    ADD CONSTRAINT recognition_call_recognition_run_id_fkey FOREIGN KEY (recognition_run_id) REFERENCES tpl.recognition_run(id) ON DELETE CASCADE;


--
-- Name: recognition_run recognition_run_import_job_id_fkey; Type: FK CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.recognition_run
    ADD CONSTRAINT recognition_run_import_job_id_fkey FOREIGN KEY (import_job_id) REFERENCES tpl.template_import_job(id) ON DELETE CASCADE;


--
-- Name: recognition_run recognition_run_parent_run_id_fkey; Type: FK CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.recognition_run
    ADD CONSTRAINT recognition_run_parent_run_id_fkey FOREIGN KEY (parent_run_id) REFERENCES tpl.recognition_run(id) ON DELETE SET NULL;


--
-- Name: recognition_suggestion recognition_suggestion_decided_by_fkey; Type: FK CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.recognition_suggestion
    ADD CONSTRAINT recognition_suggestion_decided_by_fkey FOREIGN KEY (decided_by) REFERENCES iam.app_user(id);


--
-- Name: recognition_suggestion recognition_suggestion_import_job_id_fkey; Type: FK CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.recognition_suggestion
    ADD CONSTRAINT recognition_suggestion_import_job_id_fkey FOREIGN KEY (import_job_id) REFERENCES tpl.template_import_job(id) ON DELETE CASCADE;


--
-- Name: recognition_suggestion recognition_suggestion_parent_suggestion_id_fkey; Type: FK CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.recognition_suggestion
    ADD CONSTRAINT recognition_suggestion_parent_suggestion_id_fkey FOREIGN KEY (parent_suggestion_id) REFERENCES tpl.recognition_suggestion(id) ON DELETE SET NULL;


--
-- Name: recognition_suggestion recognition_suggestion_recognition_call_id_fkey; Type: FK CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.recognition_suggestion
    ADD CONSTRAINT recognition_suggestion_recognition_call_id_fkey FOREIGN KEY (recognition_call_id) REFERENCES tpl.recognition_call(id) ON DELETE SET NULL;


--
-- Name: recognition_suggestion recognition_suggestion_recognition_run_id_fkey; Type: FK CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.recognition_suggestion
    ADD CONSTRAINT recognition_suggestion_recognition_run_id_fkey FOREIGN KEY (recognition_run_id) REFERENCES tpl.recognition_run(id) ON DELETE SET NULL;


--
-- Name: recognition_trace recognition_trace_recognition_run_id_fkey; Type: FK CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.recognition_trace
    ADD CONSTRAINT recognition_trace_recognition_run_id_fkey FOREIGN KEY (recognition_run_id) REFERENCES tpl.recognition_run(id) ON DELETE CASCADE;


--
-- Name: recognition_trace recognition_trace_recognition_suggestion_id_fkey; Type: FK CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.recognition_trace
    ADD CONSTRAINT recognition_trace_recognition_suggestion_id_fkey FOREIGN KEY (recognition_suggestion_id) REFERENCES tpl.recognition_suggestion(id) ON DELETE SET NULL;


--
-- Name: record_attachment record_attachment_created_by_fkey; Type: FK CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.record_attachment
    ADD CONSTRAINT record_attachment_created_by_fkey FOREIGN KEY (created_by) REFERENCES iam.app_user(id);


--
-- Name: record_attachment record_attachment_file_object_id_fkey; Type: FK CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.record_attachment
    ADD CONSTRAINT record_attachment_file_object_id_fkey FOREIGN KEY (file_object_id) REFERENCES ops.file_object(id);


--
-- Name: record_attachment record_attachment_production_order_id_fkey; Type: FK CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.record_attachment
    ADD CONSTRAINT record_attachment_production_order_id_fkey FOREIGN KEY (production_order_id) REFERENCES mfg.production_order(id);


--
-- Name: record_attachment record_attachment_revision_id_fkey; Type: FK CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.record_attachment
    ADD CONSTRAINT record_attachment_revision_id_fkey FOREIGN KEY (revision_id) REFERENCES tpl.record_revision(id);


--
-- Name: record_collection_item record_collection_item_production_order_id_fkey; Type: FK CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.record_collection_item
    ADD CONSTRAINT record_collection_item_production_order_id_fkey FOREIGN KEY (production_order_id) REFERENCES mfg.production_order(id);


--
-- Name: record_collection_item record_collection_item_revision_id_fkey; Type: FK CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.record_collection_item
    ADD CONSTRAINT record_collection_item_revision_id_fkey FOREIGN KEY (revision_id) REFERENCES tpl.record_revision(id) ON DELETE CASCADE;


--
-- Name: record_revision record_revision_created_by_fkey; Type: FK CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.record_revision
    ADD CONSTRAINT record_revision_created_by_fkey FOREIGN KEY (created_by) REFERENCES iam.app_user(id);


--
-- Name: record_revision record_revision_editor_snapshot_file_id_fkey; Type: FK CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.record_revision
    ADD CONSTRAINT record_revision_editor_snapshot_file_id_fkey FOREIGN KEY (editor_snapshot_file_id) REFERENCES ops.file_object(id);


--
-- Name: record_revision record_revision_export_office_file_id_fkey; Type: FK CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.record_revision
    ADD CONSTRAINT record_revision_export_office_file_id_fkey FOREIGN KEY (export_office_file_id) REFERENCES ops.file_object(id);


--
-- Name: record_revision record_revision_production_order_id_fkey; Type: FK CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.record_revision
    ADD CONSTRAINT record_revision_production_order_id_fkey FOREIGN KEY (production_order_id) REFERENCES mfg.production_order(id);


--
-- Name: record_value_index record_value_index_collection_item_id_fkey; Type: FK CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.record_value_index
    ADD CONSTRAINT record_value_index_collection_item_id_fkey FOREIGN KEY (collection_item_id) REFERENCES tpl.record_collection_item(id) ON DELETE CASCADE;


--
-- Name: record_value_index record_value_index_production_order_id_fkey; Type: FK CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.record_value_index
    ADD CONSTRAINT record_value_index_production_order_id_fkey FOREIGN KEY (production_order_id) REFERENCES mfg.production_order(id);


--
-- Name: record_value_index record_value_index_revision_id_fkey; Type: FK CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.record_value_index
    ADD CONSTRAINT record_value_index_revision_id_fkey FOREIGN KEY (revision_id) REFERENCES tpl.record_revision(id) ON DELETE CASCADE;


--
-- Name: standard_field_alias standard_field_alias_dictionary_id_fkey; Type: FK CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.standard_field_alias
    ADD CONSTRAINT standard_field_alias_dictionary_id_fkey FOREIGN KEY (dictionary_id) REFERENCES tpl.standard_field_dictionary(id) ON DELETE CASCADE;


--
-- Name: standard_field_catalog_import standard_field_catalog_import_created_by_fkey; Type: FK CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.standard_field_catalog_import
    ADD CONSTRAINT standard_field_catalog_import_created_by_fkey FOREIGN KEY (created_by) REFERENCES iam.app_user(id);


--
-- Name: standard_field_catalog_import standard_field_catalog_import_organization_id_fkey; Type: FK CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.standard_field_catalog_import
    ADD CONSTRAINT standard_field_catalog_import_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: standard_field_catalog_import standard_field_catalog_import_source_file_id_fkey; Type: FK CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.standard_field_catalog_import
    ADD CONSTRAINT standard_field_catalog_import_source_file_id_fkey FOREIGN KEY (source_file_id) REFERENCES ops.file_object(id);


--
-- Name: standard_field_catalog_proposal standard_field_catalog_proposal_import_id_fkey; Type: FK CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.standard_field_catalog_proposal
    ADD CONSTRAINT standard_field_catalog_proposal_import_id_fkey FOREIGN KEY (import_id) REFERENCES tpl.standard_field_catalog_import(id) ON DELETE CASCADE;


--
-- Name: standard_field_catalog_proposal standard_field_catalog_proposal_previous_proposal_id_fkey; Type: FK CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.standard_field_catalog_proposal
    ADD CONSTRAINT standard_field_catalog_proposal_previous_proposal_id_fkey FOREIGN KEY (previous_proposal_id) REFERENCES tpl.standard_field_catalog_proposal(id);


--
-- Name: standard_field_catalog_proposal standard_field_catalog_proposal_standard_field_id_fkey; Type: FK CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.standard_field_catalog_proposal
    ADD CONSTRAINT standard_field_catalog_proposal_standard_field_id_fkey FOREIGN KEY (standard_field_id) REFERENCES tpl.standard_field_dictionary(id);


--
-- Name: standard_field_dictionary standard_field_dictionary_supersedes_id_fkey; Type: FK CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.standard_field_dictionary
    ADD CONSTRAINT standard_field_dictionary_supersedes_id_fkey FOREIGN KEY (supersedes_id) REFERENCES tpl.standard_field_dictionary(id);


--
-- Name: standard_field_request standard_field_request_approved_dictionary_id_fkey; Type: FK CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.standard_field_request
    ADD CONSTRAINT standard_field_request_approved_dictionary_id_fkey FOREIGN KEY (approved_dictionary_id) REFERENCES tpl.standard_field_dictionary(id);


--
-- Name: standard_field_request standard_field_request_catalog_proposal_id_fkey; Type: FK CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.standard_field_request
    ADD CONSTRAINT standard_field_request_catalog_proposal_id_fkey FOREIGN KEY (catalog_proposal_id) REFERENCES tpl.standard_field_catalog_proposal(id) ON DELETE SET NULL;


--
-- Name: standard_field_request standard_field_request_created_by_fkey; Type: FK CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.standard_field_request
    ADD CONSTRAINT standard_field_request_created_by_fkey FOREIGN KEY (created_by) REFERENCES iam.app_user(id);


--
-- Name: standard_field_request standard_field_request_organization_id_fkey; Type: FK CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.standard_field_request
    ADD CONSTRAINT standard_field_request_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: standard_field_request standard_field_request_reviewed_by_fkey; Type: FK CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.standard_field_request
    ADD CONSTRAINT standard_field_request_reviewed_by_fkey FOREIGN KEY (reviewed_by) REFERENCES iam.app_user(id);


--
-- Name: standard_field_request standard_field_request_template_version_id_fkey; Type: FK CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.standard_field_request
    ADD CONSTRAINT standard_field_request_template_version_id_fkey FOREIGN KEY (template_version_id) REFERENCES tpl.template_version(id) ON DELETE SET NULL;


--
-- Name: template_category template_category_created_by_fkey; Type: FK CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.template_category
    ADD CONSTRAINT template_category_created_by_fkey FOREIGN KEY (created_by) REFERENCES iam.app_user(id);


--
-- Name: template template_category_id_fkey; Type: FK CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.template
    ADD CONSTRAINT template_category_id_fkey FOREIGN KEY (category_id) REFERENCES tpl.template_category(id);


--
-- Name: template_category template_category_organization_id_fkey; Type: FK CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.template_category
    ADD CONSTRAINT template_category_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: template template_created_by_fkey; Type: FK CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.template
    ADD CONSTRAINT template_created_by_fkey FOREIGN KEY (created_by) REFERENCES iam.app_user(id);


--
-- Name: template_import_contract template_import_contract_created_by_fkey; Type: FK CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.template_import_contract
    ADD CONSTRAINT template_import_contract_created_by_fkey FOREIGN KEY (created_by) REFERENCES iam.app_user(id);


--
-- Name: template_import_contract template_import_contract_template_version_id_fkey; Type: FK CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.template_import_contract
    ADD CONSTRAINT template_import_contract_template_version_id_fkey FOREIGN KEY (template_version_id) REFERENCES tpl.template_version(id) ON DELETE CASCADE;


--
-- Name: template_import_issue template_import_issue_import_job_id_fkey; Type: FK CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.template_import_issue
    ADD CONSTRAINT template_import_issue_import_job_id_fkey FOREIGN KEY (import_job_id) REFERENCES tpl.template_import_job(id) ON DELETE CASCADE;


--
-- Name: template_import_job template_import_job_async_job_id_fkey; Type: FK CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.template_import_job
    ADD CONSTRAINT template_import_job_async_job_id_fkey FOREIGN KEY (async_job_id) REFERENCES ops.async_job(id);


--
-- Name: template_import_job template_import_job_category_id_fkey; Type: FK CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.template_import_job
    ADD CONSTRAINT template_import_job_category_id_fkey FOREIGN KEY (category_id) REFERENCES tpl.template_category(id) ON DELETE SET NULL;


--
-- Name: template_import_job template_import_job_created_by_fkey; Type: FK CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.template_import_job
    ADD CONSTRAINT template_import_job_created_by_fkey FOREIGN KEY (created_by) REFERENCES iam.app_user(id);


--
-- Name: template_import_job template_import_job_duplicate_source_job_id_fkey; Type: FK CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.template_import_job
    ADD CONSTRAINT template_import_job_duplicate_source_job_id_fkey FOREIGN KEY (duplicate_source_job_id) REFERENCES tpl.template_import_job(id);


--
-- Name: template_import_job template_import_job_generated_template_version_id_fkey; Type: FK CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.template_import_job
    ADD CONSTRAINT template_import_job_generated_template_version_id_fkey FOREIGN KEY (generated_template_version_id) REFERENCES tpl.template_version(id);


--
-- Name: template_import_job template_import_job_normalized_source_file_id_fkey; Type: FK CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.template_import_job
    ADD CONSTRAINT template_import_job_normalized_source_file_id_fkey FOREIGN KEY (normalized_source_file_id) REFERENCES ops.file_object(id);


--
-- Name: template_import_job template_import_job_organization_id_fkey; Type: FK CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.template_import_job
    ADD CONSTRAINT template_import_job_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: template_import_job template_import_job_original_source_file_id_fkey; Type: FK CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.template_import_job
    ADD CONSTRAINT template_import_job_original_source_file_id_fkey FOREIGN KEY (original_source_file_id) REFERENCES ops.file_object(id);


--
-- Name: template_import_job template_import_job_source_file_id_fkey; Type: FK CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.template_import_job
    ADD CONSTRAINT template_import_job_source_file_id_fkey FOREIGN KEY (source_file_id) REFERENCES ops.file_object(id);


--
-- Name: template_mapping template_mapping_template_version_id_fkey; Type: FK CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.template_mapping
    ADD CONSTRAINT template_mapping_template_version_id_fkey FOREIGN KEY (template_version_id) REFERENCES tpl.template_version(id) ON DELETE CASCADE;


--
-- Name: template template_organization_id_fkey; Type: FK CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.template
    ADD CONSTRAINT template_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: template_quality_issue template_quality_issue_decided_by_fkey; Type: FK CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.template_quality_issue
    ADD CONSTRAINT template_quality_issue_decided_by_fkey FOREIGN KEY (decided_by) REFERENCES iam.app_user(id);


--
-- Name: template_quality_issue template_quality_issue_import_job_id_fkey; Type: FK CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.template_quality_issue
    ADD CONSTRAINT template_quality_issue_import_job_id_fkey FOREIGN KEY (import_job_id) REFERENCES tpl.template_import_job(id) ON DELETE CASCADE;


--
-- Name: template_quality_issue template_quality_issue_recognition_call_id_fkey; Type: FK CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.template_quality_issue
    ADD CONSTRAINT template_quality_issue_recognition_call_id_fkey FOREIGN KEY (recognition_call_id) REFERENCES tpl.recognition_call(id) ON DELETE SET NULL;


--
-- Name: template_quality_issue template_quality_issue_recognition_run_id_fkey; Type: FK CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.template_quality_issue
    ADD CONSTRAINT template_quality_issue_recognition_run_id_fkey FOREIGN KEY (recognition_run_id) REFERENCES tpl.recognition_run(id) ON DELETE CASCADE;


--
-- Name: template_static_region template_static_region_template_version_id_fkey; Type: FK CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.template_static_region
    ADD CONSTRAINT template_static_region_template_version_id_fkey FOREIGN KEY (template_version_id) REFERENCES tpl.template_version(id) ON DELETE CASCADE;


--
-- Name: template_structure_change template_structure_change_created_by_fkey; Type: FK CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.template_structure_change
    ADD CONSTRAINT template_structure_change_created_by_fkey FOREIGN KEY (created_by) REFERENCES iam.app_user(id);


--
-- Name: template_structure_change template_structure_change_template_version_id_fkey; Type: FK CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.template_structure_change
    ADD CONSTRAINT template_structure_change_template_version_id_fkey FOREIGN KEY (template_version_id) REFERENCES tpl.template_version(id) ON DELETE CASCADE;


--
-- Name: template_version template_version_created_by_fkey; Type: FK CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.template_version
    ADD CONSTRAINT template_version_created_by_fkey FOREIGN KEY (created_by) REFERENCES iam.app_user(id);


--
-- Name: template_version template_version_derived_from_version_id_fkey; Type: FK CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.template_version
    ADD CONSTRAINT template_version_derived_from_version_id_fkey FOREIGN KEY (derived_from_version_id) REFERENCES tpl.template_version(id);


--
-- Name: template_version template_version_editor_snapshot_file_id_fkey; Type: FK CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.template_version
    ADD CONSTRAINT template_version_editor_snapshot_file_id_fkey FOREIGN KEY (editor_snapshot_file_id) REFERENCES ops.file_object(id);


--
-- Name: template_version template_version_export_office_file_id_fkey; Type: FK CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.template_version
    ADD CONSTRAINT template_version_export_office_file_id_fkey FOREIGN KEY (export_office_file_id) REFERENCES ops.file_object(id);


--
-- Name: template_version_review_event template_version_review_event_actor_id_fkey; Type: FK CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.template_version_review_event
    ADD CONSTRAINT template_version_review_event_actor_id_fkey FOREIGN KEY (actor_id) REFERENCES iam.app_user(id);


--
-- Name: template_version_review_event template_version_review_event_organization_id_fkey; Type: FK CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.template_version_review_event
    ADD CONSTRAINT template_version_review_event_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: template_version_review_event template_version_review_event_version_id_fkey; Type: FK CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.template_version_review_event
    ADD CONSTRAINT template_version_review_event_version_id_fkey FOREIGN KEY (version_id) REFERENCES tpl.template_version(id) ON DELETE CASCADE;


--
-- Name: template_version_review template_version_review_organization_id_fkey; Type: FK CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.template_version_review
    ADD CONSTRAINT template_version_review_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES iam.organization(id);


--
-- Name: template_version_review template_version_review_reviewed_by_fkey; Type: FK CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.template_version_review
    ADD CONSTRAINT template_version_review_reviewed_by_fkey FOREIGN KEY (reviewed_by) REFERENCES iam.app_user(id);


--
-- Name: template_version_review template_version_review_submitted_by_fkey; Type: FK CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.template_version_review
    ADD CONSTRAINT template_version_review_submitted_by_fkey FOREIGN KEY (submitted_by) REFERENCES iam.app_user(id);


--
-- Name: template_version_review template_version_review_version_id_fkey; Type: FK CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.template_version_review
    ADD CONSTRAINT template_version_review_version_id_fkey FOREIGN KEY (version_id) REFERENCES tpl.template_version(id) ON DELETE CASCADE;


--
-- Name: template_version template_version_source_file_id_fkey; Type: FK CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.template_version
    ADD CONSTRAINT template_version_source_file_id_fkey FOREIGN KEY (source_file_id) REFERENCES ops.file_object(id);


--
-- Name: template_version template_version_template_id_fkey; Type: FK CONSTRAINT; Schema: tpl; Owner: -
--

ALTER TABLE ONLY tpl.template_version
    ADD CONSTRAINT template_version_template_id_fkey FOREIGN KEY (template_id) REFERENCES tpl.template(id);


--
-- PostgreSQL database dump complete
--

\unrestrict WYQxcCbPBwL2bLyMPZCzWRKefuk3xNCC13UQjqh3nMvLYP9656PtXN1ZMWjhFzn

