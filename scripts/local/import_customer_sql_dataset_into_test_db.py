"""Import the customer SQL dataset through the test database's existing template
and Univer snapshot.

This importer intentionally does not register or copy model bundles.  It creates
the source file/mapping/records/sample facts in the test DB, reusing the already
published template's Univer snapshot.  The normal test-environment training
pipeline can then build fresh snapshots and models from those facts.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import mimetypes
import os
import shutil
import subprocess
import uuid
from pathlib import Path
from urllib.parse import unquote, urlparse

from import_customer_model_package_assets import (
    ACTOR,
    BUCKET,
    CSV_INPUTS,
    CSV_TARGETS,
    NS,
    ORG,
    PREFIX,
    TARGET_META,
    build_prediction_fixtures,
    load_csv,
    sql_json,
    sql_text,
    uid,
    write_field_mapping,
)


# The snapshot used for this DB-driven import was exported from the published
# template version already present in the test database.  Keep the version,
# hash, and object id together; mixing these values would make the imported
# experiment appear to use a different template than its Univer document.
TEMPLATE_VERSION_ID = "5da97fb6-d103-47be-9600-8f365bc4ca67"
TEMPLATE_SNAPSHOT_HASH = "43157d2f07e5c37b42d99d0eaeb31c7cef965c38588eb5b48ddd9ee1f449406a"
TEMPLATE_SNAPSHOT_FILE_ID = "7dc98594-4c51-4517-8ee0-ed4433bf2801"


def sha_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha_json(value: object) -> str:
    return sha_bytes(json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8"))


def content_type(path: Path) -> str:
    known = {".json": "application/json", ".csv": "text/csv"}
    return known.get(path.suffix.lower()) or mimetypes.guess_type(path.name)[0] or "application/octet-stream"


def build_sql(
    package: Path,
    storage_root: Path,
    csv_rows: list[dict[str, object]],
    headers: list[str],
    mapping: dict[str, object],
    snapshot: dict[str, object],
) -> tuple[str, list[tuple[Path, Path]], dict[str, object]]:
    csv_path = package / "客户测试结果_扩展目标_模拟数据.csv"
    mapping_path = package / "字段结构映射_客户应用性能测试.json"
    fixture_paths = build_prediction_fixtures(package, csv_rows, {})
    experiment_id = uid("dataset-experiment", "20260921")
    version_id = uid("dataset-experiment-version", "20260921")
    rnd_import_id = uid("dataset-rnd-import", "20260921")
    data_import_id = uid("dataset-data-import", "20260921")
    sheet_id = uid("dataset-sheet", "客户应用性能数据")
    csv_rel = "客户测试结果_扩展目标_模拟数据.csv"
    mapping_rel = "字段结构映射_客户应用性能测试.json"
    object_keys = {
        csv_rel: f"{PREFIX}/source/{csv_rel}",
        mapping_rel: f"{PREFIX}/source/{mapping_rel}",
    }
    for name, path in fixture_paths.items():
        object_keys[name] = f"{PREFIX}/research/{name}"
    copies: list[tuple[Path, Path]] = []
    statements: list[str] = ["BEGIN;", "CREATE EXTENSION IF NOT EXISTS pgcrypto;"]
    # The experiment facts must reference a template that has already been
    # imported into the test database.  Failing here is clearer than allowing
    # a later foreign-key error after hundreds of generated statements.
    statements.append(
        "DO $dataset_template_check$ BEGIN "
        f"IF NOT EXISTS (SELECT 1 FROM tpl.template_version WHERE id={sql_text(TEMPLATE_VERSION_ID)}::uuid "
        f"AND coalesce(editor_snapshot_hash,'')={sql_text(TEMPLATE_SNAPSHOT_HASH)}) "
        "THEN RAISE EXCEPTION 'required template version is missing or hash differs; import model-experiment templates first'; END IF; "
        "END $dataset_template_check$;"
    )
    file_ids: dict[str, str] = {}
    for rel, key in object_keys.items():
        path = csv_path if rel == csv_rel else mapping_path if rel == mapping_rel else fixture_paths[rel]
        file_id = uid("dataset-file", rel)
        file_ids[rel] = file_id
        copies.append((path, storage_root / key))
        digest = sha_bytes(path.read_bytes())
        statements.append(
            "INSERT INTO ops.file_object(id,organization_id,bucket,object_key,original_name,content_type,size_bytes,sha256,status,created_by,activated_at) "
            f"VALUES({sql_text(file_id)}::uuid,{sql_text(ORG)}::uuid,{sql_text(BUCKET)},{sql_text(key)},{sql_text(path.name)},{sql_text(content_type(path))},{path.stat().st_size},{sql_text(digest)},'ACTIVE',{sql_text(ACTOR)}::uuid,now()) ON CONFLICT(bucket,object_key) DO NOTHING;"
        )

    csv_file_id = file_ids[csv_rel]
    csv_hash = sha_bytes(csv_path.read_bytes())
    template_snapshot_key = f"{ORG}/staged/{TEMPLATE_SNAPSHOT_FILE_ID}/xlsx-univer-snapshot.json"
    mapping_payload = {**mapping, "templateVersionId": TEMPLATE_VERSION_ID, "templateSnapshotHash": TEMPLATE_SNAPSHOT_HASH, "templateSnapshotObjectKey": template_snapshot_key, "sourceObjectKey": object_keys[csv_rel]}
    mapping_path.write_text(json.dumps(mapping_payload, ensure_ascii=False, indent=2), encoding="utf-8")
    mapping_hash = sha_json(mapping_payload)

    edit_model = {
        "schemaVersion": "experiment-edit-model.v2",
        "dataNature": "SYNTHETIC",
        "snapshotPurpose": "DEVELOPMENT",
        "templateVersionId": TEMPLATE_VERSION_ID,
        "templateSnapshotHash": TEMPLATE_SNAPSHOT_HASH,
        "templateSnapshotObjectKey": template_snapshot_key,
        "sourceFileObjectKey": object_keys[csv_rel],
        "fieldMappingObjectKey": object_keys[mapping_rel],
        "recordCount": len(csv_rows),
        "sourceGroups": [],
        "formulaItems": [],
        "processSteps": [],
        "testResults": [],
    }
    statements.append(
        "INSERT INTO rnd.experiment(id,organization_id,experiment_no,title,category_name,source_type,status,classification_status,owner_id,owner_name,experiment_date,current_version_id,created_by,updated_by,source_file_id) "
        f"VALUES({sql_text(experiment_id)}::uuid,{sql_text(ORG)}::uuid,'CUST-APP-EXT-20260921','客户应用性能测试数据（模板快照导入）','应用性能测试','EXCEL_IMPORT','COMPLETED','CLASSIFIED',{sql_text(ACTOR)}::uuid,'本地开发账号','2026-09-21',NULL,{sql_text(ACTOR)}::uuid,{sql_text(ACTOR)}::uuid,{sql_text(csv_file_id)}::uuid) ON CONFLICT(organization_id,experiment_no) DO NOTHING;"
    )
    statements.append(
        "INSERT INTO rnd.experiment_version(id,organization_id,experiment_id,version_no,status,template_version_id,template_snapshot_hash,template_snapshot_jsonb,edit_model_jsonb,submitted_at,published_at,created_by) "
        f"VALUES({sql_text(version_id)}::uuid,{sql_text(ORG)}::uuid,{sql_text(experiment_id)}::uuid,1,'COMPLETED',{sql_text(TEMPLATE_VERSION_ID)}::uuid,{sql_text(TEMPLATE_SNAPSHOT_HASH)},{sql_json(snapshot,'univer_snapshot')},{sql_json(edit_model,'dataset_edit_model')},now(),now(),{sql_text(ACTOR)}::uuid) ON CONFLICT(experiment_id,version_no) DO NOTHING;"
    )
    statements.append(f"UPDATE rnd.experiment SET current_version_id={sql_text(version_id)}::uuid WHERE id={sql_text(experiment_id)}::uuid;")
    statements.append(
        "INSERT INTO rnd.experiment_import_job(id,organization_id,source_file_id,source_file_name,source_sha256,status,parse_result_jsonb,experiment_id,created_by,updated_at,source_format,visibility,category_name) "
        f"VALUES({sql_text(rnd_import_id)}::uuid,{sql_text(ORG)}::uuid,{sql_text(csv_file_id)}::uuid,{sql_text(csv_path.name)},{sql_text(csv_hash)},'COMPLETED',{sql_json({'templateVersionId':TEMPLATE_VERSION_ID,'templateSnapshotHash':TEMPLATE_SNAPSHOT_HASH,'fieldMappingHash':mapping_hash,'rowCount':len(csv_rows),'dataNature':'SYNTHETIC','snapshotPurpose':'DEVELOPMENT'}, 'rnd_dataset_parse')},{sql_text(experiment_id)}::uuid,{sql_text(ACTOR)}::uuid,now(),'CSV','ALL','应用性能测试') ON CONFLICT(id) DO NOTHING;"
    )
    statements.append(
        "INSERT INTO data.import_job(id,organization_id,source_file_id,source_sha256,source_file_name,source_format,template_version_id,status,progress,current_stage,parser_version,duplicate_override,created_by,completed_at,compatibility_status,compatibility_report_jsonb,import_purpose,source_owner,recognition_mode,recognition_workspace_jsonb,experiment_boundaries_jsonb,visibility,source_sequence) "
        f"VALUES({sql_text(data_import_id)}::uuid,{sql_text(ORG)}::uuid,{sql_text(csv_file_id)}::uuid,{sql_text(csv_hash)},{sql_text(csv_path.name)},'CSV',{sql_text(TEMPLATE_VERSION_ID)}::uuid,'COMPLETED',100,'COMPLETED','customer-sql-dataset-v1',false,{sql_text(ACTOR)}::uuid,now(),'EXACT',{sql_json({'templateVersionId':TEMPLATE_VERSION_ID,'templateSnapshotHash':TEMPLATE_SNAPSHOT_HASH,'mappingHash':mapping_hash,'dataNature':'SYNTHETIC'}, 'data_dataset_compat')},'EXPERIMENT_DRAFT','EXPERIMENT','TEMPLATE_GUIDED',{sql_json({'templateVersionId':TEMPLATE_VERSION_ID,'templateSnapshotHash':TEMPLATE_SNAPSHOT_HASH}, 'workspace')},'[]'::jsonb,'ALL',1) ON CONFLICT(id) DO NOTHING;"
    )
    statements.append(
        "INSERT INTO data.import_sheet(id,import_job_id,sheet_id,sheet_name,sheet_order,selected,header_rows_jsonb,data_start_row,data_end_row,structure_jsonb,confirmation_status) "
        f"VALUES({sql_text(sheet_id)}::uuid,{sql_text(data_import_id)}::uuid,'CUSTOMER_SQL_SHEET_01','客户应用性能数据',0,true,'[1]'::jsonb,2,{len(csv_rows)+1},{sql_json({'templateVersionId':TEMPLATE_VERSION_ID,'snapshotHash':TEMPLATE_SNAPSHOT_HASH,'recordCount':len(csv_rows)}, 'dataset_sheet')},'CONFIRMED') ON CONFLICT(import_job_id,sheet_id) DO NOTHING;"
    )
    # Store the template-guided field mapping as normal import mappings.
    for idx, header in enumerate(headers, 1):
        field_code = CSV_INPUTS.get(header, (CSV_TARGETS.get(header, "SOURCE_IDENTITY"), header, None, "TEST"))[0]
        field_name = CSV_INPUTS.get(header, (None, header, None, None))[1] if header in CSV_INPUTS else header
        unit = CSV_INPUTS.get(header, (None, None, None, None))[2] if header in CSV_INPUTS else ("%" if "（%）" in header else "MPa" if "MPa" in header else "N" if "N）" in header else None)
        mapping_id = uid("dataset-import-mapping", f"{idx}:{header}")
        statements.append(
            "INSERT INTO data.import_mapping(id,import_job_id,import_sheet_id,source_column,source_header,field_code,field_name,action,value_type,source_unit,standard_unit,mapping_jsonb,status) "
            f"VALUES({sql_text(mapping_id)}::uuid,{sql_text(data_import_id)}::uuid,{sql_text(sheet_id)}::uuid,{sql_text(chr(64+idx) if idx <= 26 else str(idx))},{sql_text(header)},{sql_text(field_code)},{sql_text(field_name)},'MAP',{sql_text('NUMBER' if header in CSV_INPUTS and field_code not in {'substrate','applicationMethod','curingSource'} else 'TEXT')},{sql_text(unit)},{sql_text(unit)},{sql_json({'mappingVersion':'customer-sql-dataset-v1','templateVersionId':TEMPLATE_VERSION_ID,'templateSnapshotHash':TEMPLATE_SNAPSHOT_HASH,'sourceColumn':header,'targetCode':CSV_TARGETS.get(header)}, 'dataset_mapping')},'CONFIRMED') ON CONFLICT(id) DO NOTHING;"
        )

    # Create completed experiment source references, data records, and current
    # sample revisions. These are the facts that the test environment training
    # pipeline will consume; no model bundle is imported here.
    rows_for_target: dict[str, list[dict[str, object]]] = {code: [] for code in TARGET_META}
    for ordinal, raw in enumerate(csv_rows, 1):
        row_key = str(raw["实验编号"])
        group_key = f"CUSTOMER-SQL-LINEAGE-{(ordinal - 1) % 25:02d}|CUSTOMER-SQL-SHEET-{(ordinal - 1) % 10:02d}"
        sample_boundary = f"CUSTOMER_SQL_DATASET:{row_key}"
        logical_key = f"CUSTOMER_SQL_DATASET:{row_key}"
        sample_id = uid("dataset-sample", row_key)
        source_id = uid("dataset-sample-source", row_key)
        revision_id = uid("dataset-sample-revision", row_key)
        ref_id = uid("dataset-source-reference", row_key)
        record_id = uid("dataset-record", row_key)
        formula = {"basis": "MASS_PERCENT", "compositionComplete": True, "components": [{"materialCode": "SJ-230", "ratio": float(raw["主树脂配方比例（%）"] or 0)}, {"materialCode": "DSP-3315", "ratio": 1.6}, {"materialCode": "SS059/PC", "ratio": 1.2}, {"materialCode": "S-48", "ratio": max(0.0, 100.0 - float(raw["主树脂配方比例（%）"] or 0) - 2.8)}], "recordedTotal": 100.0, "normalizationRule": "SUM_TO_100_V1"}
        inputs = {"coatingSolidsPct": raw["涂料固含（%）"], "uvaIntensityMwCm2": raw["UVA光强（mW/cm²）"], "uvEnergyMjCm2": raw["UV能量（mJ/cm²）"], "temperatureC": raw["温度（℃）"], "humidityRhPct": raw["湿度（%RH）"], "substrate": "PET_100UM_OPTICAL", "applicationMethod": "WIRE_BAR_ROLL_COAT_40UM", "curingSource": "MERCURY_LAMP_UV", "applicatorSpecUm": 40}
        targets = {CSV_TARGETS[h]: raw.get(h) for h in CSV_TARGETS}
        fact = {"recordKey": row_key, "sourceGroupKey": group_key, "sourceIdentity": row_key, "formula": formula, "inputs": inputs, "targets": targets, "dataNature": "SYNTHETIC", "snapshotPurpose": "DEVELOPMENT"}
        coords = {"sourceFileObjectKey": object_keys[csv_rel], "templateSnapshotFileId": TEMPLATE_SNAPSHOT_FILE_ID, "templateSnapshotObjectKey": template_snapshot_key, "templateVersionId": TEMPLATE_VERSION_ID, "sheetName": "客户应用性能数据", "row": ordinal + 1, "cells": {header: f"{chr(64+i)}{ordinal+1}" for i, header in enumerate(headers, 1)}, "fieldMappingObjectKey": object_keys[mapping_rel]}
        fact_hash = sha_json(fact)
        statements.append(
            "INSERT INTO data.data_record(id,organization_id,import_job_id,record_key,record_index,sheet_id,sheet_name,source_row_number,raw_data_jsonb,normalized_data_jsonb,corrected_data_jsonb,effective_data_jsonb,quality_status,synthetic_key) "
            f"VALUES({sql_text(record_id)}::uuid,{sql_text(ORG)}::uuid,{sql_text(data_import_id)}::uuid,{sql_text(row_key)},{ordinal},'CUSTOMER_SQL_SHEET_01','客户应用性能数据',{ordinal+1},{sql_json(raw,'raw')},{sql_json(raw,'normalized')},{sql_json(raw,'corrected')},{sql_json(raw,'effective')},'VALID',true) ON CONFLICT(import_job_id,record_index) DO NOTHING;"
        )
        statements.append(
            "INSERT INTO rnd.experiment_source_reference(id,organization_id,experiment_id,experiment_version_id,experiment_import_job_id,experiment_boundary_id,sample_boundary_id,logical_sample_key,source_group_keys_jsonb,source_file_id,source_file_sha256,source_coordinates_jsonb,recognition_snapshot_jsonb,content_hash,created_by) "
            f"VALUES({sql_text(ref_id)}::uuid,{sql_text(ORG)}::uuid,{sql_text(experiment_id)}::uuid,{sql_text(version_id)}::uuid,{sql_text(rnd_import_id)}::uuid,'CUST-APP-EXT-20260921',{sql_text(sample_boundary)},{sql_text(logical_key)},{sql_json([group_key],'groups')},{sql_text(csv_file_id)}::uuid,{sql_text(csv_hash)},{sql_json(coords,'coords')},{sql_json({'templateVersionId':TEMPLATE_VERSION_ID,'templateSnapshotHash':TEMPLATE_SNAPSHOT_HASH,'dataNature':'SYNTHETIC'},'recognition')},{sql_text(fact_hash)},{sql_text(ACTOR)}::uuid) ON CONFLICT DO NOTHING;"
        )
        statements.append(
            "INSERT INTO ai.training_sample(id,organization_id,logical_sample_key,authority_source_type,status) "
            f"VALUES({sql_text(sample_id)}::uuid,{sql_text(ORG)}::uuid,{sql_text(logical_key)},'EXPERIMENT','ACTIVE') ON CONFLICT(organization_id,logical_sample_key) DO NOTHING;"
        )
        statements.append(
            "INSERT INTO ai.sample_source(id,organization_id,training_sample_id,source_type,experiment_version_id,source_business_key,sample_boundary_id,logical_sample_key,source_group_keys_jsonb,physical_identity_hash,source_version,source_sequence,content_hash,priority,status) "
            f"VALUES({sql_text(source_id)}::uuid,{sql_text(ORG)}::uuid,{sql_text(sample_id)}::uuid,'EXPERIMENT',{sql_text(version_id)}::uuid,{sql_text('EXPERIMENT:'+experiment_id+':'+sample_boundary)},{sql_text(sample_boundary)},{sql_text(logical_key)},{sql_json([group_key],'sample_groups')},{sql_text(sha_json([csv_hash,group_key,coords]))},'experiment-v1',{ordinal},{sql_text(fact_hash)},100,'CURRENT') ON CONFLICT(organization_id,source_type,source_business_key,source_version) DO NOTHING;"
        )
        statements.append(
            "INSERT INTO ai.sample_revision(id,organization_id,training_sample_id,sample_source_id,revision_no,composition_jsonb,process_jsonb,conditions_jsonb,observations_jsonb,facts_jsonb,permission_scope_jsonb,source_coordinates_jsonb,fact_hash,status) "
            f"VALUES({sql_text(revision_id)}::uuid,{sql_text(ORG)}::uuid,{sql_text(sample_id)}::uuid,{sql_text(source_id)}::uuid,1,{sql_json({'items':formula['components']},'sample_formula')},{sql_json({'items':[]},'sample_process')},{sql_json({'value':inputs},'sample_conditions')},{sql_json({'items':targets},'sample_observations')},{sql_json(fact,'sample_facts')},{sql_json({'sourceType':'EXPERIMENT','visibility':'ALL','experimentId':experiment_id},'sample_permission')},{sql_json(coords,'sample_coordinates')},{sql_text(fact_hash)},'CURRENT') ON CONFLICT(organization_id,training_sample_id,revision_no) DO NOTHING;"
        )
        statements.append(f"UPDATE ai.training_sample SET current_sample_revision_id={sql_text(revision_id)}::uuid,authority_source_type='EXPERIMENT',updated_at=now() WHERE organization_id={sql_text(ORG)}::uuid AND id={sql_text(sample_id)}::uuid AND current_sample_revision_id IS NULL;")
        for header, code in CSV_TARGETS.items():
            rows_for_target[code].append({"rowKey": row_key, "sampleId": sample_id, "revisionId": revision_id, "groupKey": group_key, "value": raw.get(header), "factHash": fact_hash})

    # Register an EXPERIMENT source mapping and a trainable eligibility fact for
    # every customer target. The training service will create new snapshots.
    for code, sample_rows in rows_for_target.items():
        target_id_expr = f"(SELECT id FROM ai.prediction_target WHERE organization_id={sql_text(ORG)}::uuid AND target_code={sql_text(code)})"
        target_version_expr = f"(SELECT id FROM ai.target_version WHERE organization_id={sql_text(ORG)}::uuid AND target_id={target_id_expr} AND version_no=1)"
        scheme_expr = f"(SELECT id FROM ai.input_scheme WHERE organization_id={sql_text(ORG)}::uuid AND target_id={target_id_expr} AND version_no=1 ORDER BY created_at DESC LIMIT 1)"
        policy_expr = f"(SELECT id FROM ai.modeling_policy_version WHERE organization_id={sql_text(ORG)}::uuid AND target_id={target_id_expr} AND kind='TRAINING' AND version_no=1)"
        mapping_id = uid("dataset-source-mapping", code)
        mapping_body = {"mappingVersion": "customer-sql-dataset-v1", "sourceType": "EXPERIMENT", "templateVersionId": TEMPLATE_VERSION_ID, "templateSnapshotHash": TEMPLATE_SNAPSHOT_HASH, "sourceObjectKey": object_keys[csv_rel], "fieldMappingObjectKey": object_keys[mapping_rel], "targetCode": code, "targetName": TARGET_META[code]["name"], "resultColumn": next(header for header, target_code in CSV_TARGETS.items() if target_code == code), "dataNature": "SYNTHETIC", "snapshotPurpose": "DEVELOPMENT"}
        mapping_hash = sha_json(mapping_body)
        statements.append(
            "INSERT INTO ai.source_mapping_version(id,organization_id,target_id,target_version_id,source_type,version_no,status,mapping_jsonb,mapping_hash,published_at,created_by) "
            f"VALUES({sql_text(mapping_id)}::uuid,{sql_text(ORG)}::uuid,{target_id_expr},{target_version_expr},'EXPERIMENT',1,'PUBLISHED',{sql_json(mapping_body,'target_mapping')},{sql_text(mapping_hash)},now(),{sql_text(ACTOR)}::uuid) ON CONFLICT DO NOTHING;"
        )
        for row in sample_rows:
            eligibility_id = uid("dataset-eligibility", f"{code}:{row['rowKey']}")
            evidence = {"targetCode": code, "rowKey": row["rowKey"], "templateVersionId": TEMPLATE_VERSION_ID, "templateSnapshotHash": TEMPLATE_SNAPSHOT_HASH, "mappingHash": mapping_hash, "sourceObjectKey": object_keys[csv_rel], "dataNature": "SYNTHETIC", "snapshotPurpose": "DEVELOPMENT"}
            statements.append(
                "INSERT INTO ai.training_eligibility(id,organization_id,sample_revision_id,target_version_id,input_scheme_id,state,reasons_jsonb,warnings_jsonb,evidence_jsonb,rule_fingerprint,revision,source_mapping_version_id,modeling_policy_version_id) "
                f"VALUES({sql_text(eligibility_id)}::uuid,{sql_text(ORG)}::uuid,{sql_text(row['revisionId'])}::uuid,{target_version_expr},{scheme_expr},'TRAINABLE','[]'::jsonb,'[]'::jsonb,{sql_json(evidence,'eligibility')},{sql_text(sha_json(evidence))},0,{sql_text(mapping_id)}::uuid,{policy_expr}) ON CONFLICT DO NOTHING;"
            )

    manifest = {"dataNature": "SYNTHETIC", "snapshotPurpose": "DEVELOPMENT", "productionEligible": False, "templateVersionId": TEMPLATE_VERSION_ID, "templateSnapshotHash": TEMPLATE_SNAPSHOT_HASH, "templateSnapshotFileId": TEMPLATE_SNAPSHOT_FILE_ID, "templateSnapshotObjectKey": template_snapshot_key, "sourceObjectKeys": object_keys, "experimentId": experiment_id, "experimentVersionId": version_id, "dataImportJobId": data_import_id, "rndImportJobId": rnd_import_id, "rowCount": len(csv_rows), "targetCodes": list(TARGET_META), "trainingSamples": len(csv_rows), "eligibilityRows": len(csv_rows) * len(TARGET_META)}
    manifest_path = package / "dataset-import-manifest.json"
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    manifest_rel = manifest_path.name
    manifest_key = f"{PREFIX}/source/{manifest_rel}"
    copies.append((manifest_path, storage_root / manifest_key))
    manifest_file_id = uid("dataset-file", manifest_rel)
    statements.append(
        "INSERT INTO ops.file_object(id,organization_id,bucket,object_key,original_name,content_type,size_bytes,sha256,status,created_by,activated_at) "
        f"VALUES({sql_text(manifest_file_id)}::uuid,{sql_text(ORG)}::uuid,{sql_text(BUCKET)},{sql_text(manifest_key)},{sql_text(manifest_path.name)},'application/json',{manifest_path.stat().st_size},{sql_text(sha_bytes(manifest_path.read_bytes()))},'ACTIVE',{sql_text(ACTOR)}::uuid,now()) ON CONFLICT(bucket,object_key) DO NOTHING;"
    )
    statements.append("COMMIT;")
    return "\n".join(statements) + "\n", copies, manifest


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--package-root", type=Path, required=True)
    parser.add_argument("--storage-root", type=Path, required=True)
    parser.add_argument("--template-snapshot-file", type=Path)
    parser.add_argument("--database-url", default=None,
                        help="PostgreSQL URL；未提供时读取 JSD_AIRD_DATASOURCE_URL/USERNAME/PASSWORD")
    parser.add_argument("--template-version-id", default=None)
    parser.add_argument("--template-snapshot-hash", default=None)
    parser.add_argument("--template-snapshot-file-id", default=None)
    parser.add_argument("--organization-id", default=None)
    parser.add_argument("--actor-id", default=None)
    parser.add_argument("--object-bucket", default=None)
    parser.add_argument("--object-prefix", default=None)
    parser.add_argument("--sql-out", type=Path)
    parser.add_argument("--psql", default="psql")
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    global TEMPLATE_VERSION_ID, TEMPLATE_SNAPSHOT_HASH, TEMPLATE_SNAPSHOT_FILE_ID
    global ORG, ACTOR, BUCKET, PREFIX
    if args.template_version_id:
        TEMPLATE_VERSION_ID = args.template_version_id
    if args.template_snapshot_hash:
        TEMPLATE_SNAPSHOT_HASH = args.template_snapshot_hash
    if args.template_snapshot_file_id:
        TEMPLATE_SNAPSHOT_FILE_ID = args.template_snapshot_file_id
    if args.organization_id:
        ORG = args.organization_id
    if args.actor_id:
        ACTOR = args.actor_id
    if args.object_bucket:
        BUCKET = args.object_bucket
    if args.object_prefix:
        PREFIX = args.object_prefix
    database_url = args.database_url or os.environ.get("JSD_AIRD_TEST_DATABASE_URL")
    if not database_url:
        jdbc_url = os.environ.get("JSD_AIRD_DATASOURCE_URL", "")
        if jdbc_url.startswith("jdbc:"):
            jdbc_url = jdbc_url[5:]
        if jdbc_url:
            database_url = jdbc_url
        else:
            user = os.environ.get("JSD_AIRD_DATASOURCE_USERNAME", "postgres")
            password = os.environ.get("JSD_AIRD_DATASOURCE_PASSWORD", "")
            host = os.environ.get("JSD_AIRD_DATASOURCE_HOST", "127.0.0.1")
            port = os.environ.get("JSD_AIRD_DATASOURCE_PORT", "5432")
            database = os.environ.get("JSD_AIRD_DATASOURCE_DATABASE", "jsd_ai")
            database_url = f"postgresql://{user}:{password}@{host}:{port}/{database}"
    package = args.package_root.resolve()
    csv_path = package / "客户测试结果_扩展目标_模拟数据.csv"
    if not csv_path.is_file():
        raise SystemExit(f"缺少客户数据文件: {csv_path}")
    snapshot_path = args.template_snapshot_file or (args.storage_root.resolve() / ORG / "staged" / TEMPLATE_SNAPSHOT_FILE_ID / "xlsx-univer-snapshot.json")
    if not snapshot_path.is_file():
        raise SystemExit(f"找不到测试库已有 Univer 快照: {snapshot_path}")
    headers, rows = load_csv(csv_path)
    mapping = write_field_mapping(package / "字段结构映射_客户应用性能测试.json", headers, csv_path.name)
    snapshot = json.loads(snapshot_path.read_text(encoding="utf-8"))
    sql_out = args.sql_out or package / "import-dataset.generated.sql"
    sql, copies, manifest = build_sql(package, args.storage_root.resolve(), rows, headers, mapping, snapshot)
    sql_out.write_text(sql, encoding="utf-8")
    for source, dest in copies:
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, dest)
    if args.apply:
        env = dict(os.environ)
        if not env.get("PGPASSWORD"):
            parsed = urlparse(database_url)
            if parsed.password:
                env["PGPASSWORD"] = unquote(parsed.password)
        env["PGCLIENTENCODING"] = "UTF8"
        result = subprocess.run([args.psql, database_url, "-v", "ON_ERROR_STOP=1", "-f", str(sql_out)], env=env, capture_output=True, text=True, encoding="utf-8", errors="replace")
        if result.returncode:
            print(result.stdout or "")
            print(result.stderr or "")
            raise SystemExit(result.returncode)
    print(json.dumps({"status": "IMPORTED" if args.apply else "GENERATED", "manifest": manifest, "sql": str(sql_out), "templateSnapshot": str(snapshot_path), "storageRoot": str(args.storage_root), "database": database_url, "modelBundlesImported": False}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
