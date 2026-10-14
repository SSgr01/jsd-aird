"""Register the 15 locally trained R07 synthetic targets as non-production Candidates.

This is a local fixture publisher for the isolated r0506_browser database. It never
activates a model and never writes the user's configured database.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import subprocess
import uuid
import zipfile
from pathlib import Path
from typing import Any

import sys
sys.path.insert(0, str(Path(__file__).resolve().parent))
from r07_synthetic_15y_train_and_predict import TARGETS, base_context, context_for  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
ORG = "00000000-0000-0000-0000-000000000001"
ACTOR = "fb7f93f2-95b8-484f-9332-57be544ad828"
FIELD_VERSION = "05060000-0000-0000-0000-000000000011"
NS = uuid.NAMESPACE_URL


def sha_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def uid(kind: str, code: str) -> str:
    return str(uuid.uuid5(NS, f"r07-15y-{kind}:{code}"))


def sql_text(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def sql_json(value: Any, tag: str = "r07json") -> str:
    text = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    if f"${tag}$" in text:
        tag = "r07jsonx"
    return f"${tag}${text}${tag}$::jsonb"


def psql_command(psql: str, db: str, *args: str) -> list[str]:
    return [psql, db, "-v", "ON_ERROR_STOP=1", *args]


def read_bundle(path: Path) -> tuple[dict[str, Any], str, int]:
    raw = path.read_bytes()
    with zipfile.ZipFile(path) as archive:
        card = json.loads(archive.read("model-card.json").decode("utf-8"))
    return card, sha_bytes(raw), len(raw)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--database-url", default="postgresql://postgres:postgres@127.0.0.1:55434/r0506_browser")
    parser.add_argument("--psql", default=r"D:\PostgreSQL\18\bin\psql.exe")
    parser.add_argument("--output-sql", default=str(ROOT / "tmp" / "r07-synthetic-15y" / "register-candidates.sql"))
    parser.add_argument("--apply", action="store_true", default=True)
    args = parser.parse_args()

    out_dir = ROOT / "tmp" / "r07-synthetic-15y"
    summary = {row["code"]: row for row in json.loads((out_dir / "training-summary.json").read_text(encoding="utf-8"))}
    base = base_context(args.psql, args.database_url)
    materials = base["materialDictionary"]
    statements: list[str] = ["BEGIN;"]
    for target in TARGETS:
        code = target["code"]
        row = summary[code]
        context = context_for(base, target)
        tid = uid("target", code)
        vid = tid
        sid = uid("scheme", code)
        mid = uid("mapping", code)
        train_policy_id = uid("policy-training", code)
        quality_policy_id = uid("policy-quality", code)
        domain_policy_id = uid("policy-domain", code)
        snapshot_id = uid("snapshot", code)
        job_id = uid("job", code)
        artifact_id = uid("artifact", code)
        model_id = uid("model", code)
        # Keep the V2 target definition strict.  Display-only fixture metadata belongs
        # in the model card, not in the immutable target contract sent to Python.
        target_def = dict(context["targetDefinition"])
        target_def_hash = target_def["sha256"]
        scheme = dict(context["inputScheme"])
        scheme["id"] = sid
        scheme["targetVersionId"] = vid
        scheme_hash = sha_bytes(json.dumps(scheme, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode())
        preprocess = context["preprocessing"]
        policy_config = context["trainingPolicy"]["config"]
        training_hash = context["trainingPolicy"]["sha256"]
        qualification = {"minimumTrainableSamples": 80, "dataNature": "SYNTHETIC", "syntheticMarker": "R07_SYNTHETIC"}
        validation = {"foldCount": 5, "groupFields": ["formula_lineage", "source_context"], "primaryMetric": target["metric"], "dataNature": "SYNTHETIC"}
        quality = {"rules": [{"when": {"warningCodesAny": ["FORMULA_TOTAL_WARNING"]}, "priority": 10, "trustLevel": "MEDIUM", "explanation": "合成演练保留配方实际总量，可信等级由版本化 QUALITY 策略决定。"}, {"when": {"domainStatuses": ["IN_DOMAIN"]}, "priority": 30, "trustLevel": "HIGH", "explanation": "输入处于当前合成模型适用域。"}], "defaultTrustLevel": "MEDIUM", "defaultExplanation": "合成数据仅用于功能演练。", "syntheticMarker": "R07_SYNTHETIC"}
        domain = {"source": "model-card", "dataNature": "SYNTHETIC", "applicabilityDomain": {}}
        bundle_path = out_dir / "models" / f"{hashlib.sha256(code.encode()).hexdigest()[:12]}.zip"
        card, bundle_sha, bundle_size = read_bundle(bundle_path)
        storage_key = f"ai/r07-synthetic-15y/{bundle_path.name}"
        storage_path = ROOT / "tmp" / "r07-storage" / storage_key
        storage_path.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(bundle_path, storage_path)
        card_domain = dict(card.get("applicabilityDomain", {}))
        card_domain["nearBoundaryRatio"] = 0.1
        domain["applicabilityDomain"] = card_domain
        metric_data = card.get("metrics", {})
        manifest_path = out_dir / "snapshots" / hashlib.sha256(code.encode()).hexdigest()[:12] / "manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        snapshot_hash = manifest["snapshotHash"]
        snapshot_manifest = {**manifest, "manifestFile": str(manifest_path.resolve()), "bundleSha256": bundle_sha}
        mapping = {"sourceType": "DATA_CENTER", "template": "综合测试报告模板.xlsx", "synthetic": True, "targetCode": code, "coverage": target["coverage"]}
        mapping_hash = sha_bytes(json.dumps(mapping, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode())
        business_hash = sha_bytes(f"R07|{code}|{snapshot_hash}|{bundle_sha}".encode())
        policy_hash = sha_bytes(json.dumps(policy_config, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode())
        quality_hash = sha_bytes(json.dumps(quality, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode())
        domain_hash = sha_bytes(json.dumps(domain, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode())
        model_card = {**card, "synthetic": True, "syntheticMarker": "SYNTHETIC_MULTICLASS_ACCEPTANCE" if code == "SYNTHETIC_MULTICLASS_ACCEPTANCE" else "R07_SYNTHETIC", "targetCode": code, "targetId": tid, "registeredSnapshotId": snapshot_id}
        metadata = {"synthetic": True, "marker": "SYNTHETIC_MULTICLASS_ACCEPTANCE" if code == "SYNTHETIC_MULTICLASS_ACCEPTANCE" else "R07_SYNTHETIC", "targetCode": code, "source": "r07_synthetic_15y_train_and_predict.py"}
        # Target and immutable configuration versions.
        statements.append(f"INSERT INTO ai.prediction_target (id,organization_id,target_code,name,performance_project,value_type,status,revision,created_by,updated_by) VALUES ({sql_text(tid)}::uuid,{sql_text(ORG)}::uuid,{sql_text(code)},{sql_text(target['name'])},{sql_text('R07 合成训练演练')},{sql_text(target['type'])},'ACTIVE',0,{sql_text(ACTOR)}::uuid,{sql_text(ACTOR)}::uuid) ON CONFLICT (organization_id,target_code) DO NOTHING;")
        statements.append(f"INSERT INTO ai.target_version (id,organization_id,target_id,version_no,status,value_type,unit,classes_jsonb,definition_jsonb,observation_semantics_jsonb,config_hash,published_at,created_by) VALUES ({sql_text(vid)}::uuid,{sql_text(ORG)}::uuid,{sql_text(tid)}::uuid,1,'PUBLISHED',{sql_text(target['type'])},{sql_text(target.get('unit') or '')},{sql_json(target.get('classes', []))},{sql_json(target_def)},{sql_json(target_def.get('observationSemantics', {}))},{sql_text(target_def_hash)},now(),{sql_text(ACTOR)}::uuid) ON CONFLICT (organization_id,target_id,version_no) DO NOTHING;")
        statements.append(f"INSERT INTO ai.input_scheme (id,organization_id,target_id,target_version_id,material_dictionary_version_id,scheme_code,name,version_no,status,preprocessing_jsonb,config_hash,revision,frozen_at,created_by,updated_by) VALUES ({sql_text(sid)}::uuid,{sql_text(ORG)}::uuid,{sql_text(tid)}::uuid,{sql_text(vid)}::uuid,{sql_text(materials['id'])}::uuid,{sql_text('R07_'+hashlib.sha256(code.encode()).hexdigest()[:10])},{sql_text('R07合成输入方案·'+target['name'])},1,'DRAFT',{sql_json(preprocess)},{sql_text(scheme_hash)},0,now(),{sql_text(ACTOR)}::uuid,{sql_text(ACTOR)}::uuid) ON CONFLICT (organization_id,target_id,scheme_code,version_no) DO NOTHING;")
        statements.append(f"INSERT INTO ai.input_scheme_field (organization_id,input_scheme_id,input_field_version_id,required,ordinal,override_jsonb) SELECT {sql_text(ORG)}::uuid,{sql_text(sid)}::uuid,{sql_text(FIELD_VERSION)}::uuid,true,0,'{{}}'::jsonb WHERE EXISTS (SELECT 1 FROM ai.input_scheme s WHERE s.organization_id={sql_text(ORG)}::uuid AND s.id={sql_text(sid)}::uuid AND s.status <> 'FROZEN') AND NOT EXISTS (SELECT 1 FROM ai.input_scheme_field f WHERE f.input_scheme_id={sql_text(sid)}::uuid AND f.input_field_version_id={sql_text(FIELD_VERSION)}::uuid) ON CONFLICT (input_scheme_id,input_field_version_id) DO NOTHING;")
        statements.append(f"UPDATE ai.input_scheme SET status='FROZEN',frozen_at=now(),updated_at=now(),updated_by={sql_text(ACTOR)}::uuid WHERE organization_id={sql_text(ORG)}::uuid AND id={sql_text(sid)}::uuid AND status <> 'FROZEN';")
        statements.append(f"INSERT INTO ai.source_mapping_version (id,organization_id,target_id,target_version_id,source_type,version_no,status,mapping_jsonb,mapping_hash,published_at,created_by) VALUES ({sql_text(mid)}::uuid,{sql_text(ORG)}::uuid,{sql_text(tid)}::uuid,{sql_text(vid)}::uuid,'DATA_CENTER',1,'PUBLISHED',{sql_json(mapping)},{sql_text(mapping_hash)},now(),{sql_text(ACTOR)}::uuid) ON CONFLICT (organization_id,target_version_id,source_type,version_no) DO NOTHING;")
        for pid, kind, config, phash in [(train_policy_id, 'TRAINING', policy_config, policy_hash), (quality_policy_id, 'QUALITY', quality, quality_hash), (domain_policy_id, 'DOMAIN', domain, domain_hash)]:
            cfg = config if kind != 'TRAINING' else policy_config
            statements.append(f"INSERT INTO ai.modeling_policy_version (id,organization_id,target_id,version_no,kind,status,qualification_jsonb,validation_jsonb,training_jsonb,configuration_jsonb,policy_hash,published_at,created_by,published_by) VALUES ({sql_text(pid)}::uuid,{sql_text(ORG)}::uuid,{sql_text(tid)}::uuid,1,{sql_text(kind)},'PUBLISHED',{sql_json(qualification)},{sql_json(validation)},{sql_json(policy_config)},{sql_json(cfg)},{sql_text(phash)},now(),{sql_text(ACTOR)}::uuid,{sql_text(ACTOR)}::uuid) ON CONFLICT (organization_id,target_id,kind,version_no) DO NOTHING;")
        statements.append(f"UPDATE ai.prediction_target SET current_version_id={sql_text(vid)}::uuid,current_input_scheme_id={sql_text(sid)}::uuid,current_quality_policy_version_id={sql_text(quality_policy_id)}::uuid,current_domain_policy_version_id={sql_text(domain_policy_id)}::uuid,updated_at=now(),updated_by={sql_text(ACTOR)}::uuid WHERE organization_id={sql_text(ORG)}::uuid AND id={sql_text(tid)}::uuid;")
        statements.append(f"INSERT INTO ai.training_snapshot (id,organization_id,target_version_id,input_scheme_id,material_dictionary_version_id,modeling_policy_version_id,status,sample_count,validation_groups_jsonb,manifest_jsonb,snapshot_hash,object_prefix,frozen_by,frozen_at,target_id,source_mapping_versions_jsonb,target_definition_jsonb,input_scheme_jsonb,material_dictionary_jsonb,preprocessing_jsonb,training_policy_jsonb,authorization_scope_jsonb,fact_high_watermark,data_nature,business_fingerprint) VALUES ({sql_text(snapshot_id)}::uuid,{sql_text(ORG)}::uuid,{sql_text(vid)}::uuid,{sql_text(sid)}::uuid,{sql_text(materials['id'])}::uuid,{sql_text(train_policy_id)}::uuid,'FROZEN',{int(target['coverage'])},{sql_json({'formulaLineage':'R07 synthetic grouped folds','sourceContext':'R07 synthetic source groups'})},{sql_json(snapshot_manifest)},{sql_text(snapshot_hash)},{sql_text('r07-synthetic-15y/'+hashlib.sha256(code.encode()).hexdigest()[:12])},{sql_text(ACTOR)}::uuid,now(),{sql_text(tid)}::uuid,'[]'::jsonb,{sql_json(target_def)},{sql_json(scheme)},{sql_json(materials)},{sql_json(preprocess)},{sql_json(policy_config)},{sql_json({'organizationId':ORG,'dataNature':'SYNTHETIC','synthetic':True})},0,'SYNTHETIC',{sql_text(business_hash)}) ON CONFLICT (organization_id,id) DO NOTHING;")
        statements.append(f"INSERT INTO ai.training_job (id,organization_id,training_snapshot_id,status,idempotency_key,request_hash,priority,max_attempts,attempt_count,next_attempt_at,revision,requested_by,created_at,started_at,finished_at,target_id,target_version_id,modeling_policy_version_id,seed,current_stage,progress,fencing_generation,error_jsonb,business_key) VALUES ({sql_text(job_id)}::uuid,{sql_text(ORG)}::uuid,{sql_text(snapshot_id)}::uuid,'SUCCEEDED',{sql_text('r07-synthetic-15y:'+code)},{sql_text(business_hash)},100,3,1,now(),0,{sql_text(ACTOR)}::uuid,now(),now(),now(),{sql_text(tid)}::uuid,{sql_text(vid)}::uuid,{sql_text(train_policy_id)}::uuid,2026,'SUCCEEDED',100,0,'{{}}'::jsonb,{sql_text(business_hash)}) ON CONFLICT (organization_id,idempotency_key) DO NOTHING;")
        statements.append(f"INSERT INTO ai.artifact (id,organization_id,artifact_type,training_snapshot_id,object_key,sha256,size_bytes,metadata_jsonb,media_type,content_addressed) VALUES ({sql_text(artifact_id)}::uuid,{sql_text(ORG)}::uuid,'MODEL_BUNDLE',{sql_text(snapshot_id)}::uuid,{sql_text(storage_key)},{sql_text(bundle_sha)},{bundle_size},{sql_json(metadata)},'application/zip',true) ON CONFLICT (organization_id,object_key) DO NOTHING;")
        statements.append(f"INSERT INTO ai.model_version (id,organization_id,target_id,target_version_id,input_scheme_id,training_snapshot_id,training_job_id,model_artifact_id,model_type,status,metrics_jsonb,applicability_domain_jsonb,model_card_jsonb,contract_version,model_hash,revision,created_by,version_no,data_nature,production_eligible,comparison_status,rejection_reasons_jsonb,domain_policy_version_id) VALUES ({sql_text(model_id)}::uuid,{sql_text(ORG)}::uuid,{sql_text(tid)}::uuid,{sql_text(vid)}::uuid,{sql_text(sid)}::uuid,{sql_text(snapshot_id)}::uuid,{sql_text(job_id)}::uuid,{sql_text(artifact_id)}::uuid,{sql_text(row['selectedAlgorithm'])},'CANDIDATE',{sql_json(metric_data)},{sql_json(domain['applicabilityDomain'])},{sql_json(model_card)},'formula-model.v2',{sql_text(bundle_sha)},0,{sql_text(ACTOR)}::uuid,1,'SYNTHETIC',false,'NOT_COMPARABLE','[]'::jsonb,{sql_text(domain_policy_id)}::uuid) ON CONFLICT (organization_id,model_hash) DO NOTHING;")
    statements.append("COMMIT;")
    sql_path = Path(args.output_sql)
    sql_path.parent.mkdir(parents=True, exist_ok=True)
    sql_path.write_text("\n".join(statements) + "\n", encoding="utf-8")
    subprocess.run(psql_command(args.psql, args.database_url, "-f", str(sql_path)), cwd=ROOT, check=True)
    print(f"registered {len(TARGETS)} synthetic Candidates in {args.database_url}")
    print(f"sql: {sql_path}")


if __name__ == "__main__":
    main()
