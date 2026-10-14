"""Build the template catalog required by the local model-data demo packages.

The model bundles themselves do not contain templates.  Their experiment/source
metadata points at template versions, so this command collects the immutable
Univer snapshots and the declared template identities into one catalog that can
be imported before the experiment facts.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import uuid
from pathlib import Path


ORG = "00000000-0000-0000-0000-000000000001"
NAMESPACE = uuid.UUID("6c866f68-1a28-5a2b-934d-c6f7ef05e0f3")


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def stable_uuid(value: str) -> str:
    return str(uuid.uuid5(NAMESPACE, value))


def load_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def build_catalog(repo_root: Path) -> dict:
    extended = repo_root / ".runtime/demo/customer-sql-fields-extended-metrics-development-20260921"
    cationic_detail = repo_root / ".runtime/demo/input/cationic-uvpu/experiment-detail.json"
    cationic_snapshot = repo_root / ".runtime/demo/template-catalog/阳离子树脂与应用测试模板-7a7-univer-snapshot.json"

    extended_manifest = load_json(extended / "dataset-import-manifest.json")
    detail = load_json(cationic_detail)
    embedded_snapshot = detail.get("templateSnapshot")
    if not isinstance(embedded_snapshot, dict):
        raise ValueError(f"缺少阳离子模板嵌入快照: {cationic_detail}")
    cationic_snapshot.parent.mkdir(parents=True, exist_ok=True)
    cationic_snapshot.write_text(
        json.dumps(embedded_snapshot, ensure_ascii=False, separators=(",", ":")),
        encoding="utf-8",
    )

    templates = [
        {
            "templateCode": "UVPU_APPLICATION_TEST",
            "name": "应用性能测试模板",
            "category": "应用性能测试",
            "usage": "客户SQL扩展指标模型实验数据",
            "templateId": stable_uuid("template:UVPU_APPLICATION_TEST"),
            "templateVersionId": extended_manifest["templateVersionId"],
            "snapshotHash": extended_manifest["templateSnapshotHash"],
            "snapshotFileId": extended_manifest["templateSnapshotFileId"],
            "snapshotObjectKey": extended_manifest["templateSnapshotObjectKey"],
            "snapshotFile": str((extended / "template-snapshot-from-test-db.json").resolve()),
            "sourcePackages": [str(extended.resolve())],
        },
        {
            "templateCode": "CATIONIC_RESIN_APPLICATION_TEST",
            "name": "阳离子树脂性能及应用测试模板",
            "category": "树脂及应用性能测试",
            "usage": "阳离子树脂/UVPU历史实验与SQL字段模型数据",
            "templateId": stable_uuid("template:CATIONIC_RESIN_APPLICATION_TEST"),
            "templateVersionId": detail["templateVersionId"],
            "snapshotHash": detail["templateSnapshotHash"],
            "snapshotFileId": stable_uuid("snapshot-file:" + detail["templateVersionId"]),
            "snapshotObjectKey": (
                ORG + "/staged/" + stable_uuid("snapshot-file:" + detail["templateVersionId"])
                + "/xlsx-univer-snapshot.json"
            ),
            "snapshotFile": str(cationic_snapshot.resolve()),
            "sourcePackages": [str(cationic_detail.resolve()), str((repo_root / ".runtime/demo/customer-sql-fields-20260919").resolve())],
        },
    ]
    for item in templates:
        path = Path(item["snapshotFile"])
        if not path.is_file():
            raise FileNotFoundError(path)
        snapshot = load_json(path)
        item["snapshotContentSha256"] = sha256(path)
        item["snapshotFormatVersion"] = int(snapshot.get("snapshotFormatVersion", 3))
        item["editorAppVersion"] = snapshot.get("appVersion", "univer-0.25.1")
        item["snapshotId"] = snapshot.get("id")
        item["sourcePackages"] = sorted(set(item["sourcePackages"]))
    return {
        "schema": "model-experiment-template-catalog.v1",
        "organizationId": ORG,
        "purpose": "TEST_ENVIRONMENT_MODEL_EXPERIMENT_DATA",
        "modelBundlesImported": False,
        "templates": templates,
        "notes": [
            "模型包不携带模板，模板必须先于实验事实导入。",
            "snapshotHash是模板版本契约哈希，snapshotContentSha256是对象存储文件哈希，二者不可混用。",
            "当前仓库扫描到两套模型实验模板；未把无关的旧预览文件当作模板依赖。",
        ],
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo-root", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    catalog = build_catalog(args.repo_root.resolve())
    output = args.output or (args.repo_root.resolve() / ".runtime/demo/template-catalog/model-experiment-template-catalog.json")
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(catalog, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"status": "READY", "catalog": str(output.resolve()), "templateCount": len(catalog["templates"])}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
