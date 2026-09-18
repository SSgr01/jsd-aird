from __future__ import annotations

import json
import math
import time
from pathlib import Path
from random import Random

import httpx
import os
from openpyxl import load_workbook
from openpyxl.cell.cell import MergedCell

ROOT = Path(__file__).resolve().parents[2]
WORK = ROOT / ".runtime/demo/input/bulk"
SOURCE = ROOT / "docs/AI实验优化、配方预测/干净模板表_整理完成/原表优化/应用测试报告模板.xlsx"
BASE_URL = os.environ.get("JSD_AIRD_DEMO_API_BASE_URL", "http://127.0.0.1:8080")
def n(v: float, digits: int = 2) -> float:
    return round(v, digits)


def set_value(ws, row: int, col: int, value) -> None:
    cell = ws.cell(row=row, column=col)
    if isinstance(cell, MergedCell):
        return
    cell.value = value


def fill_sheet(ws, sample_base: str, rng: Random, is_experiment: bool, start_index: int) -> None:
    # Data rows are deliberately uneven: some result cells are left blank to
    # exercise real coverage and eligibility gates later in the workflow.
    for offset, col in enumerate(range(4, 10)):
        idx = start_index + offset
        total = 98.8 if idx % 4 == 0 else 100.0
        resin = n(67.0 + rng.uniform(-5.0, 5.0), 1)
        additive = n(20.0 + rng.uniform(-2.5, 2.5), 1)
        crosslinker = n(total - resin - additive, 1)
        if crosslinker < 4:
            crosslinker = 4.0
            additive = n(total - resin - crosslinker, 1)
        substrate = ["PET", "PC", "PMMA/PC"][idx % 3]
        method = ["线棒涂布", "刮涂", "旋涂"][idx % 3]
        temp = 22 + (idx % 7)
        rh = 45 + ((idx * 3) % 31)
        uv = 650 + ((idx * 47) % 500)
        solid = n(38.0 + (idx % 8) * 0.75 + rng.uniform(-0.25, 0.25), 1)
        gloss = n(76 + (idx % 9) * 2.1 + rng.uniform(-1.5, 1.5), 1)
        dry = n(0.75 + (idx % 6) * 0.2 + rng.uniform(-0.08, 0.08), 2)
        curl = n(0.8 + (idx % 8) * 0.22 + rng.uniform(-0.12, 0.12), 2)
        thickness = n(10.5 + (idx % 6) * 1.7 + rng.uniform(-0.5, 0.5), 1)
        hard = ["F", "H", "2H", "3H"][idx % 4]
        wool500 = 720 + (idx * 19) % 280
        wool1000 = 420 + (idx * 23) % 260
        adhesion = ["5B", "4B", "3B", "5B"][idx % 4]

        # Resin and process properties.
        set_value(ws, 8, col, f"RESIN-{['A', 'B', 'C'][idx % 3]}")
        set_value(ws, 9, col, f"RESIN-{['A', 'B', 'C'][idx % 3]} + ADDITIVE")
        set_value(ws, 10, col, "浅黄色透明液体")
        set_value(ws, 11, col, f"{solid:.1f} %")
        set_value(ws, 12, col, "通过")
        set_value(ws, 13, col, f"{n(1180 + (idx % 10) * 28 + rng.uniform(-18, 18), 0):.0f} mPa·s")
        set_value(ws, 14, col, f"{n(0.25 + rng.random() * 0.45, 2):.2f} %")
        set_value(ws, 15, col, f"{n(5100 + rng.uniform(-220, 220), 0):.0f} g/mol")
        set_value(ws, 16, col, f"{sample_base}-{idx:04d}")
        set_value(ws, 17, col, resin)
        set_value(ws, 18, col, additive)
        set_value(ws, 19, col, crosslinker)
        for row in range(20, 26):
            set_value(ws, row, col, None)
        set_value(ws, 26, col, total)
        set_value(ws, 27, col, f"{solid:.1f} %")
        set_value(ws, 28, col, ["轻微缩孔", "平整透明", "轻微颗粒"][idx % 3])
        set_value(ws, 29, col, "通过")
        set_value(ws, 30, col, ["平整透明", "轻微橘皮", "平整透明"][idx % 3])
        set_value(ws, 31, col, thickness)
        set_value(ws, 32, col, f"{dry:.2f} mm")
        set_value(ws, 33, col, f"{n(dry * 0.62, 2):.2f} mm")
        set_value(ws, 34, col, hard)
        set_value(ws, 35, col, f"{wool500} 次")
        set_value(ws, 36, col, f"{wool1000} 次")

        # Sheet2 has customer-method fields such as adhesion, water-boil and
        # pencil hardness. Keep the same source layout and populate them.
        if ws.title.lower().startswith("sheet2"):
            set_value(ws, 34, col, adhesion)
            set_value(ws, 35, col, "无异常" if idx % 5 else "轻微发白")
            set_value(ws, 36, col, f"{n(91 + rng.uniform(-2, 2), 1):.1f} %")
            set_value(ws, 37, col, ["H", "2H", "3H"][idx % 3])
            set_value(ws, 38, col, f"{500 + (idx * 17) % 420} 次")

    # Preserve the original row count and use the existing note row for traceability.
    set_value(ws, 6, 2, ["线棒涂布", "刮涂", "旋涂"][start_index % 3])
    set_value(ws, 7, 2, f"UV {650 + (start_index * 47) % 500} mW/cm²；{22 + start_index % 7}°C；{45 + (start_index * 3) % 31}%RH")
    note_row = 40 if ws.title.lower().startswith("sheet2") else 39
    set_value(ws, note_row, 1,
              f"备注：SYNTHETIC_R10_ACCEPTANCE；来源={'EXPERIMENT' if is_experiment else 'DATA_CENTER'}；"
              "应用测试模板原生布局；比例含100%与98.8%；客户测试字段：附着力/铅笔硬度/钢丝绒耐磨/UV表干。")


def generate() -> tuple[list[Path], list[Path]]:
    WORK.mkdir(parents=True, exist_ok=True)
    for old in WORK.glob("*.xlsx"):
        old.unlink()
    data_files: list[Path] = []
    exp_files: list[Path] = []
    for kind, count, target in [("DATA", 20, data_files), ("EXPERIMENT", 14, exp_files)]:
        for batch in range(1, count + 1):
            out = WORK / f"r10_{kind.lower()}_{batch:03d}_SYNTHETIC.xlsx"
            wb = load_workbook(SOURCE)
            rng = Random(20260917 + batch + (1000 if kind == "EXPERIMENT" else 0))
            for ws in wb.worksheets:
                fill_sheet(ws, f"{kind}-{batch:03d}", rng, kind == "EXPERIMENT", (batch - 1) * 12 + 1)
            wb.save(out)
            target.append(out)
    return data_files, exp_files


def csrf(session: httpx.Client) -> str:
    payload = session.get(f"{BASE_URL}/api/v1/auth/csrf", timeout=30).json()
    return payload["data"]["token"]


def login(session: httpx.Client, token: str) -> None:
    username = os.environ.get("JSD_AIRD_IAM_ADMIN_USERNAME", "r0506admin")
    password = os.environ.get("JSD_AIRD_IAM_ADMIN_PASSWORD")
    if not password:
        raise RuntimeError("JSD_AIRD_IAM_ADMIN_PASSWORD is required for demo import")
    response = session.post(f"{BASE_URL}/api/v1/auth/login",
                            json={"username": username, "password": password, "rememberMe": True},
                            headers={"X-XSRF-TOKEN": token}, timeout=30)
    response.raise_for_status()


def template_and_category(session: httpx.Client) -> tuple[str, str]:
    templates = session.get(f"{BASE_URL}/api/v1/data/templates", timeout=30).json()["data"]
    selected = next((item for item in templates if item.get("experimentImportReady") and item.get("templateUsage") == "EXPERIMENT_DATA"), None)
    if not selected:
        raise RuntimeError("没有已发布且允许生成实验草稿的客户模板")
    categories = session.get(f"{BASE_URL}/api/v1/experiment-categories", timeout=30).json()["data"]
    category = next((item for item in categories if item.get("code") == "R10_DEMO"), None)
    if not category:
        response = session.post(f"{BASE_URL}/api/v1/experiment-categories",
                                json={"code": "R10_DEMO", "name": "客户演示实验", "description": "SYNTHETIC_DEMO 隔离演示"},
                                headers={"X-XSRF-TOKEN": csrf(session)}, timeout=30)
        response.raise_for_status()
        category = response.json()["data"]
    return selected["versionId"], category["id"]


def import_one(session: httpx.Client, token: str, path: Path, purpose: str, template_id: str, category_id: str) -> str:
    with path.open("rb") as handle:
        staged = session.post(
            f"{BASE_URL}/api/v1/files/staged?kind={'EXPERIMENT_SOURCE' if purpose == 'EXPERIMENT_DRAFT' else 'DATA_SOURCE'}",
            files={"file": (path.name, handle, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")},
            headers={"X-XSRF-TOKEN": token}, timeout=120).json()
    file_id = staged["data"]["fileId"]
    body = {"sourceFileId": file_id, "templateVersionId": template_id,
            "importPurpose": purpose, "duplicateOverride": True}
    if purpose == "EXPERIMENT_DRAFT":
        body["targetExperimentCategoryId"] = category_id
    endpoint = "/api/v1/data/experiment-import-jobs" if purpose == "EXPERIMENT_DRAFT" else "/api/v1/data/import-jobs"
    created = session.post(f"{BASE_URL}{endpoint}", json=body,
                           headers={"X-XSRF-TOKEN": token}, timeout=60).json()
    return created["data"]["id"]


def wait_status(session: httpx.Client, job_id: str, wanted: set[str], timeout_s: int = 180) -> dict:
    deadline = time.time() + timeout_s
    last: dict = {}
    while time.time() < deadline:
        last = session.get(f"{BASE_URL}/api/v1/data/import-jobs/{job_id}", timeout=30).json()["data"]
        if last["status"] in wanted:
            return last
        time.sleep(1.5)
    raise RuntimeError(f"job {job_id} timeout at {last.get('status')}")


def confirm_and_commit(session: httpx.Client, token: str, job_id: str, purpose: str, category_id: str) -> dict:
    preview = session.get(f"{BASE_URL}/api/v1/data/import-jobs/{job_id}/preview", timeout=60).json()["data"]
    items = []
    for mapping in preview["mappings"]:
        item = {k: mapping.get(k) for k in ["sheetId", "sourceColumn", "sourceHeader", "fieldCode", "fieldName",
                                             "action", "valueType", "sourceUnit", "standardUnit", "detail"]}
        if not item.get("fieldCode") or item.get("action") != "MAP":
            item.update(action="IGNORE", fieldCode=None, fieldName=None, detail={})
        items.append(item)
    session.put(f"{BASE_URL}/api/v1/data/import-jobs/{job_id}/mappings", json={"items": items},
                headers={"X-XSRF-TOKEN": token}, timeout=120).raise_for_status()
    job = wait_status(session, job_id, {"WAITING_CONFIRM", "FAILED"})
    if job["status"] == "FAILED":
        return job
    if job.get("compatibilityStatus") == "REVIEW_REQUIRED":
        # Keep the result for the report; this is a real template-compatibility
        # finding and should never be hidden by forcing a commit.
        return job
    response = session.post(f"{BASE_URL}/api/v1/data/import-jobs/{job_id}/commit",
                             headers={"X-XSRF-TOKEN": token}, timeout=180)
    result = response.json().get("data", job)
    if purpose == "EXPERIMENT_DRAFT" and result.get("status") == "COMPLETED":
        sync = session.post(f"{BASE_URL}/api/v1/data/import-jobs/{job_id}/experiment-sync",
                            json={"categoryId": category_id},
                            headers={"X-XSRF-TOKEN": token}, timeout=180)
        result["experimentSync"] = sync.json().get("data", sync.json())
    return result


def main() -> None:
    data_files, exp_files = generate()
    session = httpx.Client(follow_redirects=True)
    token = csrf(session)
    login(session, token)
    template_id, category_id = template_and_category(session)
    results = []
    for kind, files, purpose in [("DATA_CENTER", data_files, "DATA_ONLY"),
                                  ("EXPERIMENT", exp_files, "EXPERIMENT_DRAFT")]:
        for path in files:
            job_id = import_one(session, token, path, purpose, template_id, category_id)
            wait_status(session, job_id, {"WAITING_MAPPING", "FAILED"})
            final = confirm_and_commit(session, token, job_id, purpose, category_id)
            results.append({"kind": kind, "file": path.name, "jobId": job_id,
                            "status": final.get("status"), "compatibilityStatus": final.get("compatibilityStatus"),
                            "purpose": purpose})
            print(kind, path.name, job_id, final.get("status"), final.get("compatibilityStatus"), flush=True)
    (WORK / "import-results.json").write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"dataFiles": len(data_files), "experimentFiles": len(exp_files),
                      "dataJobs": sum(1 for x in results if x["kind"] == "DATA_CENTER"),
                      "experimentJobs": sum(1 for x in results if x["kind"] == "EXPERIMENT"),
                      "completed": sum(1 for x in results if x["status"] == "COMPLETED")}, ensure_ascii=False))


if __name__ == "__main__":
    main()
