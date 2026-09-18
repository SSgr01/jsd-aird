from __future__ import annotations

import json
import sys
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[1]
REPOSITORY_ROOT = PROJECT_ROOT.parent
sys.path.insert(0, str(PROJECT_ROOT / "src"))

from jsd_aird_ai.contracts import TaskProfile  # noqa: E402
from jsd_aird_ai.devtools import DEFAULT_MODEL_INDEX, DEFAULT_PROFILE_PATH  # noqa: E402
from jsd_aird_ai.digests import canonical_sha256, sha256_bytes  # noqa: E402
from jsd_aird_ai.modeling import load_bundle, rebind_bundle_schema  # noqa: E402


def main() -> None:
    index_path = DEFAULT_MODEL_INDEX
    index = json.loads(index_path.read_bytes())
    old_path = REPOSITORY_ROOT / index["modelBundlePath"]
    old_data = old_path.read_bytes()
    profile = TaskProfile.model_validate_json(DEFAULT_PROFILE_PATH.read_bytes())
    new_data = rebind_bundle_schema(old_data, index["modelBundleHash"], profile)
    new_hash = sha256_bytes(new_data)
    destination = old_path.parent / f"{profile.code}-{profile.version}-{new_hash[:16]}.zip"
    if destination.exists() and destination.read_bytes() != new_data:
        raise RuntimeError("content-addressed destination contains different bytes")
    if not destination.exists():
        destination.write_bytes(new_data)
    rebound = load_bundle(new_data, new_hash)
    expected_profile_hash = canonical_sha256(profile)
    if rebound.payload["task_profile_hash"] != expected_profile_hash:
        raise RuntimeError("rebound model bundle profile binding is invalid")
    index.update(
        {
            "taskProfileHash": expected_profile_hash,
            "schemaHash": profile.schema_hash,
            "modelBundlePath": destination.relative_to(REPOSITORY_ROOT).as_posix(),
            "modelBundleHash": new_hash,
            "modelBundleSize": len(new_data),
        }
    )
    index_path.write_text(
        json.dumps(index, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(
        json.dumps(
            {
                "previousBundleHash": sha256_bytes(old_data),
                "modelBundleHash": new_hash,
                "modelBundleSize": len(new_data),
                "taskProfileHash": expected_profile_hash,
                "schemaHash": profile.schema_hash,
            },
            indent=2,
        )
    )


if __name__ == "__main__":
    main()
