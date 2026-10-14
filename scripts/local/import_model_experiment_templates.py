"""Generate/apply idempotent SQL for model-data templates and their snapshots."""

from __future__ import annotations

import argparse
import hashlib
import json
import mimetypes
import os
import shutil
import subprocess
from pathlib import Path
from urllib.parse import unquote, urlparse


def sql_text(value: object) -> str:
    if value is None:
        return "NULL"
    return "'" + str(value).replace("'", "''") + "'"


def sql_json(value: object, tag: str) -> str:
    payload = json.dumps(value, ensure_ascii=False, separators=(",", ":"))
    return f"${tag}${payload}${tag}$::jsonb"


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def database_url(args: argparse.Namespace) -> str:
    value = args.database_url or os.environ.get("JSD_AIRD_TEST_DATABASE_URL")
    if value:
        return value
    jdbc = os.environ.get("JSD_AIRD_DATASOURCE_URL", "")
    if jdbc.startswith("jdbc:"):
        jdbc = jdbc[5:]
    if jdbc:
        return jdbc
    user = os.environ.get("JSD_AIRD_DATASOURCE_USERNAME", "postgres")
    password = os.environ.get("JSD_AIRD_DATASOURCE_PASSWORD", "")
    host = os.environ.get("JSD_AIRD_DATASOURCE_HOST", "127.0.0.1")
    port = os.environ.get("JSD_AIRD_DATASOURCE_PORT", "5432")
    database = os.environ.get("JSD_AIRD_DATASOURCE_DATABASE", "jsd_ai")
    return f"postgresql://{user}:{password}@{host}:{port}/{database}"


def build_sql(catalog: dict, storage_root: Path, actor_id: str, bucket: str, organization_id: str) -> tuple[str, list[tuple[Path, Path]]]:
    statements = ["BEGIN;", "CREATE EXTENSION IF NOT EXISTS pgcrypto;"]
    copies: list[tuple[Path, Path]] = []
    for item in catalog["templates"]:
        snapshot_path = Path(item["snapshotFile"]).resolve()
        if not snapshot_path.is_file():
            raise FileNotFoundError(snapshot_path)
        catalog_org = catalog["organizationId"]
        suffix = item["snapshotObjectKey"][len(catalog_org):] if item["snapshotObjectKey"].startswith(catalog_org) else "/staged/" + item["snapshotFileId"] + "/xlsx-univer-snapshot.json"
        object_key = organization_id + suffix
        file_id = item["snapshotFileId"]
        file_hash = sha256(snapshot_path)
        content_type = mimetypes.guess_type(snapshot_path.name)[0] or "application/json"
        # StorageRoot is the mounted root of the object store.  It already
        # represents the bucket configured by the importer, so keep the same
        # layout as the dataset importer: <StorageRoot>/<objectKey>.
        copies.append((snapshot_path, storage_root / object_key))
        # Refuse an existing object with a different hash; never overwrite a
        # template snapshot silently in a shared test database.
        statements.append(
            "DO $template_file_check$ BEGIN "
            f"IF EXISTS (SELECT 1 FROM ops.file_object WHERE bucket={sql_text(bucket)} AND object_key={sql_text(object_key)} AND id <> {sql_text(file_id)}::uuid) "
            "THEN RAISE EXCEPTION 'template snapshot object key belongs to another file id: " + object_key + "'; END IF; "
            f"IF EXISTS (SELECT 1 FROM ops.file_object WHERE id={sql_text(file_id)}::uuid AND sha256 <> {sql_text(file_hash)}) "
            "THEN RAISE EXCEPTION 'template snapshot file hash mismatch: " + file_id + "'; END IF; "
            "END $template_file_check$;"
        )
        statements.append(
            "INSERT INTO ops.file_object(id,organization_id,bucket,object_key,original_name,content_type,size_bytes,sha256,status,created_by,activated_at) "
            f"VALUES({sql_text(file_id)}::uuid,{sql_text(organization_id)}::uuid,{sql_text(bucket)},{sql_text(object_key)},"
            f"{sql_text(snapshot_path.name)},{sql_text(content_type)},{snapshot_path.stat().st_size},{sql_text(file_hash)},'ACTIVE',{sql_text(actor_id)}::uuid,now()) "
            "ON CONFLICT(id) DO NOTHING;"
        )
        schema = {"schemaVersion": "template-import.v1", "templateCode": item["templateCode"], "name": item["name"]}
        layout = {"initialSnapshot": {"snapshotFormatVersion": item["snapshotFormatVersion"]}}
        plugin_hash = hashlib.sha256(b"{}").hexdigest()
        schema_hash = hashlib.sha256(json.dumps(schema, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
        mapping_hash = hashlib.sha256(b"template-mapping-empty-v1").hexdigest()
        data_hash = hashlib.sha256(snapshot_path.read_bytes()).hexdigest()
        workspace_hash = hashlib.sha256((item["snapshotHash"] + ":" + item["snapshotContentSha256"]).encode()).hexdigest()
        # Resolve by template_code so an existing template with the same code
        # is reused instead of creating a second logical template.
        statements.append(
            "INSERT INTO tpl.template(id,organization_id,template_code,name,purpose,category,format,created_by) "
            f"VALUES({sql_text(item['templateId'])}::uuid,{sql_text(organization_id)}::uuid,{sql_text(item['templateCode'])},{sql_text(item['name'])},"
            f"{sql_text(item['usage'])},{sql_text(item['category'])},'XLSX',{sql_text(actor_id)}::uuid) "
            "ON CONFLICT(organization_id,template_code) DO NOTHING;"
        )
        statements.append(
            "DO $template_version_check$ BEGIN "
            f"IF EXISTS (SELECT 1 FROM tpl.template_version tv JOIN tpl.template t ON t.id=tv.template_id "
            f"WHERE tv.id={sql_text(item['templateVersionId'])}::uuid AND (t.organization_id <> {sql_text(organization_id)}::uuid OR t.template_code <> {sql_text(item['templateCode'])})) "
            "THEN RAISE EXCEPTION 'template version belongs to another template: " + item["templateVersionId"] + "'; END IF; "
            f"IF EXISTS (SELECT 1 FROM tpl.template_version tv JOIN tpl.template t ON t.id=tv.template_id "
            f"WHERE t.organization_id={sql_text(organization_id)}::uuid AND t.template_code={sql_text(item['templateCode'])} "
            f"AND tv.version_no=1 AND tv.id <> {sql_text(item['templateVersionId'])}::uuid) "
            "THEN RAISE EXCEPTION 'template code already has a different version-1 id: " + item["templateCode"] + "'; END IF; "
            f"IF EXISTS (SELECT 1 FROM tpl.template_version WHERE id={sql_text(item['templateVersionId'])}::uuid "
            f"AND coalesce(editor_snapshot_hash,'') <> {sql_text(item['snapshotHash'])}) "
            "THEN RAISE EXCEPTION 'template version snapshot hash mismatch: " + item["templateVersionId"] + "'; END IF; "
            "END $template_version_check$;"
        )
        statements.append(
            "INSERT INTO tpl.template_version(id,template_id,version_no,status,schema_jsonb,layout_summary_jsonb,editor_snapshot_file_id,editor_snapshot_hash,snapshot_kind,editor_app_version,plugin_manifest_hash,snapshot_format_version,schema_hash,mapping_hash,data_hash,workspace_hash,created_by,published_at) "
            f"SELECT {sql_text(item['templateVersionId'])}::uuid,t.id,1,'PUBLISHED',{sql_json(schema,'template_schema')},{sql_json(layout,'template_layout')},{sql_text(file_id)}::uuid,{sql_text(item['snapshotHash'])},'UNIVER_WORKBOOK',"
            f"{sql_text(item['editorAppVersion'])},{sql_text(plugin_hash)},{int(item['snapshotFormatVersion'])},{sql_text(schema_hash)},{sql_text(mapping_hash)},{sql_text(data_hash)},{sql_text(workspace_hash)},{sql_text(actor_id)}::uuid,now() "
            f"FROM tpl.template t WHERE t.organization_id={sql_text(organization_id)}::uuid AND t.template_code={sql_text(item['templateCode'])} "
            "ON CONFLICT DO NOTHING;"
        )
        statements.append(
            "UPDATE tpl.template t SET current_published_version_id=tv.id,updated_at=now() "
            f"FROM tpl.template_version tv WHERE t.organization_id={sql_text(organization_id)}::uuid AND t.template_code={sql_text(item['templateCode'])} AND tv.id={sql_text(item['templateVersionId'])}::uuid;"
        )
    statements.append("COMMIT;")
    return "\n".join(statements) + "\n", copies


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--catalog-file", type=Path, required=True)
    parser.add_argument("--storage-root", type=Path, required=True)
    parser.add_argument("--database-url")
    parser.add_argument("--actor-id", default="00000000-0000-0000-0000-000000000099")
    parser.add_argument("--organization-id", default=None)
    parser.add_argument("--object-bucket", default="jsd-aird")
    parser.add_argument("--sql-out", type=Path)
    parser.add_argument("--psql", default="psql")
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    catalog = json.loads(args.catalog_file.resolve().read_text(encoding="utf-8"))
    if catalog.get("schema") != "model-experiment-template-catalog.v1":
        raise SystemExit("模板目录版本不受支持")
    organization_id = args.organization_id or catalog["organizationId"]
    sql, copies = build_sql(catalog, args.storage_root.resolve(), args.actor_id, args.object_bucket, organization_id)
    sql_out = args.sql_out or args.catalog_file.with_name("import-model-experiment-templates.generated.sql")
    sql_out.write_text(sql, encoding="utf-8")
    for source, destination in copies:
        destination.parent.mkdir(parents=True, exist_ok=True)
        if destination.exists() and sha256(destination) != sha256(source):
            raise SystemExit(f"对象存储中已有不同内容，拒绝覆盖: {destination}")
        if not destination.exists():
            shutil.copy2(source, destination)
    db = database_url(args)
    if args.apply:
        env = dict(os.environ)
        if not env.get("PGPASSWORD"):
            parsed = urlparse(db)
            if parsed.password:
                env["PGPASSWORD"] = unquote(parsed.password)
        env["PGCLIENTENCODING"] = "UTF8"
        result = subprocess.run([args.psql, db, "-v", "ON_ERROR_STOP=1", "-f", str(sql_out)], env=env, capture_output=True, text=True, encoding="utf-8", errors="replace")
        if result.returncode:
            print(result.stdout or "")
            print(result.stderr or "")
            raise SystemExit(result.returncode)
    print(json.dumps({"status": "IMPORTED" if args.apply else "GENERATED", "templateCount": len(catalog["templates"]), "organizationId": organization_id, "sql": str(sql_out.resolve()), "storageRoot": str(args.storage_root.resolve()), "database": db}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
