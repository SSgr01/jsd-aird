"""Small, restartable inventory/extraction CLI. Never modifies experiment files."""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import os
import sys
import time
import zipfile
from datetime import date, datetime, time as daytime
from pathlib import Path
from xml.etree import ElementTree as ET

VERSION = 1
CATEGORIES = ("测试实验", "合成实验")


def digest(path):
    h = hashlib.sha256()
    with Path(path).open("rb") as f:
        for block in iter(lambda: f.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def write_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(path.name + ".tmp")
    tmp.write_text(json.dumps(value, ensure_ascii=False, indent=2, default=str), encoding="utf-8")
    os.replace(tmp, path)


def read_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8-sig"))


def workspace(value=None):
    if value:
        candidates = [Path(value).resolve()]
    else:
        here = Path.cwd().resolve()
        candidates = [here, *here.parents, Path(__file__).resolve().parents[4]]
    for p in candidates:
        if (p / "标准模板").is_dir() and (p / "原始实验").is_dir():
            return p
    raise ValueError("找不到工作区；传 --root，目录内须有 标准模板/ 和 原始实验/")


def source_path(root, value):
    p = (root / value).resolve()
    relposix(p, root / "原始实验")
    if not p.is_file() or p.name.startswith("~$"):
        raise ValueError("源文件不存在，或为锁文件：" + str(p))
    return p


def state_dir(root):
    return root / ".dsh" / "cache" / "jsd-experiment-converter"


def relposix(path, root):
    """Workspace-relative posix path; tolerates 8.3 short names in TEMP on Windows."""
    path, root = Path(path).resolve(), Path(root).resolve()
    try:
        return path.relative_to(root).as_posix()
    except ValueError:
        return Path(os.path.relpath(path, root)).as_posix()


def is_within(path, parent):
    """is_relative_to that tolerates 8.3 short names on Windows."""
    return os.path.normcase(os.path.realpath(path)).startswith(
        os.path.normcase(os.path.realpath(parent)).rstrip(os.sep) + os.sep)


def record_failure(root, source, stage, error):
    path = source_path(root, source)
    target = state_dir(root) / digest(path) / ("failure-" + stage + ".json")
    old = read_json(target) if target.exists() else {}
    write_json(target, {"stage": stage, "source": relposix(path, root),
                        "error": str(error), "attempts": old.get("attempts", 0)+1,
                        "action": "修正后最多重试一次；仍失败交代阻塞并继续独立文件"})


def scalar(value):
    if isinstance(value, (date, datetime, daytime)):
        return {"type": type(value).__name__, "iso": value.isoformat()}
    return value


def clear_failure(root, source, stage):
    """Drop a previously recorded failure once the same source succeeds again."""
    path = source_path(root, source)
    (state_dir(root) / digest(path) / ("failure-" + stage + ".json")).unlink(missing_ok=True)


def extract_xlsx(path, wanted):
    from openpyxl import load_workbook
    wf = load_workbook(path, data_only=False, read_only=True)
    wv = load_workbook(path, data_only=True, read_only=True)
    try:
        out = []
        for name in wanted:
            ws, cached = wf[name], wv[name]
            cells = []
            for row, vals in zip(ws.iter_rows(), cached.iter_rows()):
                for c, v in zip(row, vals):
                    if c.value is not None:
                        item = {"cell": c.coordinate, "value": scalar(v.value), "format": c.number_format}
                        if c.data_type == "f":
                            item["formula"] = c.value
                        cells.append(item)
            out.append({"sheet": name, "size": [ws.max_row, ws.max_column], "cells": cells})
        return out
    finally:
        wf.close()
        wv.close()


def extract_xls(path, wanted):
    try:
        import xlrd
    except ImportError as e:
        raise ValueError("缺 xlrd；用当前 DSH 的 LibreOffice Kit 转副本，见 references/legacy-formats.md；不要循环探测/安装") from e
    wb = xlrd.open_workbook(str(path))
    result = []
    for name in wanted:
        ws = wb.sheet_by_name(name)
        cells = []
        from openpyxl.utils import get_column_letter
        for r in range(ws.nrows):
            for c in range(ws.ncols):
                cell = ws.cell(r, c)
                if cell.ctype not in (xlrd.XL_CELL_EMPTY, xlrd.XL_CELL_BLANK):
                    value = cell.value
                    if cell.ctype == xlrd.XL_CELL_DATE:
                        value = scalar(xlrd.xldate_as_datetime(value, wb.datemode))
                    cells.append({"cell": f"{get_column_letter(c+1)}{r+1}", "value": value})
        result.append({"sheet": name, "size": [ws.nrows, ws.ncols], "cells": cells})
    return result


def sheet_names(path):
    if path.suffix.lower() == ".xlsx":
        from openpyxl import load_workbook
        wb = load_workbook(path, read_only=True)
        try:
            return wb.sheetnames
        finally:
            wb.close()
    if path.suffix.lower() == ".xls":
        import xlrd
        return xlrd.open_workbook(str(path), on_demand=True).sheet_names()
    return []


def extract_docx(path):
    ns = {"w": "http://schemas.openxmlformats.org/wordprocessingml/2006/main"}
    with zipfile.ZipFile(path) as z:
        xml = ET.fromstring(z.read("word/document.xml"))
    # Every table, including nested tables. Outer cells do not duplicate nested text.
    tables = []
    for i, table in enumerate(xml.findall(".//w:tbl", ns), 1):
        rows = []
        for row in table.findall("w:tr", ns):
            rows.append(["\n".join("".join(t.text or "" for t in p.findall(".//w:t", ns))
                                      for p in cell.findall("w:p", ns))
                        for cell in row.findall("w:tc", ns)])
        tables.append({"table": i, "rows": rows})
    body = xml.find("w:body", ns)
    paragraphs = ["".join(t.text or "" for t in p.findall(".//w:t", ns))
                  for p in body.findall("w:p", ns)]
    return {"tables": tables, "paragraphs": paragraphs}


def inventory(root):
    templates = []
    for kind in ("test", "synthesis"):
        p = root / "标准模板" / (kind + ".xlsx")
        templates.append({"kind": kind, "exists": p.is_file(), "sha256": digest(p) if p.is_file() else None})
    plans = []
    for path in (Path(__file__).parent.parent / "recipes").glob("*.json"):
        plan = read_json(path)
        plans.append((path, plan))
    records = []
    for category in CATEGORIES:
        folder = root / "原始实验" / category
        if not folder.is_dir():
            records.append({"category": category, "status": "missing_directory"})
            continue
        for p in sorted(folder.rglob("*")):
            if not p.is_file() or p.name.startswith("~$") or p.suffix.lower() not in (".xlsx", ".xls", ".docx", ".doc"):
                continue
            sha = digest(p)
            cached = state_dir(root) / sha
            status_path = cached / "status.json"
            status = read_json(status_path) if status_path.exists() else {"stage": "pending"}
            if status.get("source") not in (None, relposix(p, root)):
                status = {"stage": "pending"}
            # Completion is only reusable if the output still has its verified hash.
            if status.get("stage") in ("validated", "review_required"):
                out = root / status["output"]
                current_tpl = templates[CATEGORIES.index(category)]["sha256"]
                scripts = Path(__file__).parent
                engine = digest(scripts / "execute_plan.py") + digest(scripts / "template_ops.py")
                if (not out.is_file() or digest(out) != status.get("output_sha256")
                    or current_tpl != status.get("template_sha256") or engine != status.get("engine_sha256")):
                    status = {"stage": "stale"}
            records.append({"source": relposix(p, root), "category": category,
                            "sha256": sha, "status": status,
                            "matching_plans": [str(path.resolve()) for path, plan in plans
                                               if plan.get("source_sha256") == sha
                                               and Path(plan.get("source", "")).as_posix() == relposix(p, root)
                                               and plan.get("template_sha256") == templates[CATEGORIES.index(category)]["sha256"]],
                            "failures": [read_json(f) for f in cached.glob("failure-*.json")
                                         if read_json(f).get("source") == relposix(p, root)],
                            "extracted_batches": len(list(cached.glob("extract-*.json"))) if cached.exists() else 0})
    result = {"version": VERSION, "root": str(root), "templates": templates, "sources": records,
              "dependencies": {x: importlib.util.find_spec(x) is not None for x in ("openpyxl", "xlrd")}}
    write_json(state_dir(root) / "inventory.json", result)
    return result


def extract(root, source, sheet=None, start=0, limit=1):
    path = source_path(root, source)
    sha = digest(path)
    suffix = path.suffix.lower()
    if suffix == ".doc":
        raise ValueError(".doc 需要一次本地转换；转换并验证后读取 .docx；见 references/legacy-formats.md")
    if suffix == ".docx":
        wanted = []
    else:
        metadata = state_dir(root) / sha / "sheet-names.json"
        if metadata.exists():
            names = read_json(metadata)
        else:
            names = sheet_names(path)
            write_json(metadata, names)
        wanted = [sheet] if sheet else names[start:start+limit]
        if not wanted or any(n not in names for n in wanted):
            raise ValueError("工作表选择无效")
    key = hashlib.sha256(json.dumps([VERSION, relposix(path, root), wanted, suffix], ensure_ascii=False).encode()).hexdigest()[:16]
    target = state_dir(root) / sha / ("extract-" + key + ".json")
    hit = target.is_file()
    if not hit:
        payload = {"version": VERSION, "source": relposix(path, root), "sha256": sha}
        if suffix == ".docx":
            payload.update(extract_docx(path))
        else:
            payload["sheet_names"] = names
            payload["sheets"] = (extract_xlsx if suffix == ".xlsx" else extract_xls)(path, wanted)
        write_json(target, payload)
    (state_dir(root) / sha / "failure-extract.json").unlink(missing_ok=True)
    # Keep the complete extraction as evidence, but give the model a concise
    # coordinate-indexed view and a short relative path it can copy reliably.
    payload = read_json(target)
    view = dict(payload)
    if "sheets" in payload:
        view["sheets"] = [{**item, "cells": {
            cell["cell"]: {key: (value[:120] + "… [完整公式见 artifact]"
                                if key == "formula" and isinstance(value, str) and len(value) > 500 else value)
                           for key, value in cell.items()
                           if key != "cell" and not (key == "format" and value == "General")}
            for cell in item["cells"]}} for item in payload["sheets"]]
    preview = state_dir(root) / ("view-" + hashlib.sha256((sha + key).encode()).hexdigest()[:12] + ".json")
    # Native read truncates each line at 2000 characters. Keep JSON compact
    # across lines, rather than emitting one giant line that hides later cells.
    preview.write_text(json.dumps(view, ensure_ascii=False, separators=(',\n', ':')), encoding="utf-8")
    return {"cache_hit": hit, "artifact": relposix(target, root), "preview": relposix(preview, root), "sheets": wanted,
            "read_hint": "preview 保留本批所有非空坐标、原值和格式；长公式仅显示开头。完整公式保存在 artifact，仅按具体坐标查阅。",
            "next_start": start + len(wanted) if not sheet and suffix in (".xlsx", ".xls") else None}


def semantic_handoff(root, result):
    """Return the inputs for semantic mapping without rediscovering the workspace."""
    sources = [(kind, path) for kind, category in zip(("test", "synthesis"), CATEGORIES)
               for path in (root / "原始实验" / category).glob("*.xlsx")
               if not path.name.startswith("~$")]
    if len(sources) != 1:
        return result
    kind, source = sources[0]
    template = root / "标准模板" / (kind + ".xlsx")
    if not template.is_file():
        return result
    source_ref = relposix(source, root)
    source_hash, template_hash = digest(source), digest(template)
    target = state_dir(root) / f"target-{kind}-{template_hash[:16]}.json"
    if not target.is_file():
        sheets = extract_xlsx(template, sheet_names(template)[:1])
        write_json(target, {"template_sha256": template_hash, "sheet": sheets[0]["sheet"],
                           "labels": {cell["cell"]: cell["value"] for cell in sheets[0]["cells"]
                                      if "formula" not in cell}})
    batch = extract(root, source_ref)
    with zipfile.ZipFile(source) as archive:
        members = archive.namelist()
    plans = []
    for path in sorted((state_dir(root) / "plans").glob("*.json")):
        try:
            plan = read_json(path)
        except (OSError, ValueError):
            continue
        if (isinstance(plan, dict) and plan.get("source") == source_ref and plan.get("kind") == kind
                and plan.get("source_sha256") == source_hash and plan.get("template_sha256") == template_hash):
            plans.append(relposix(path, root))
    result["semantic"] = {
        "source": source_ref, "kind": kind, "source_sha256": source_hash, "template_sha256": template_hash,
        "source_preview": batch["preview"], "target_labels": relposix(target, root),
        "source_media": sum(name.startswith("xl/media/") and not name.endswith("/") for name in members),
        "source_charts": sum(name.startswith("xl/charts/chart") and name.endswith(".xml") for name in members),
        "existing_plans": plans, "next_start": batch["next_start"],
        "plan_contract": ".conversion-tools/jsd-experiment-converter/references/execution.md",
        "next_action": "有已保存计划则先检查并续跑。否则读取源预览、目标标签和计划约定，再保存坐标映射。"
                       "无需重新清点或阅读执行器源码。后续工作表按源预览中的 sheet_names 提取。"
    }
    write_json(state_dir(root) / "semantic-handoff.json", result)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("prepare", "inspect", "extract"))
    parser.add_argument("--root")
    parser.add_argument("--source")
    parser.add_argument("--sheet")
    parser.add_argument("--start", type=int, default=0)
    parser.add_argument("--limit", type=int, default=1)
    args = parser.parse_args()
    started = time.perf_counter()
    try:
        root = workspace(args.root)
        if args.start < 0 or not 1 <= args.limit <= 5:
            raise ValueError("start >= 0；limit 为 1..5，每批只处理少量工作表")
        if args.command == "prepare":
            from exact_template import prepare
            result = prepare(root)
            if not result["eligible"]:
                result = semantic_handoff(root, result)
        elif args.command == "inspect":
            result = inventory(root)
        else:
            if not args.source:
                raise ValueError("extract 需要 --source（相对工作区的精确路径）")
            result = extract(root, args.source, args.sheet, args.start, args.limit)
        result["elapsed_seconds"] = round(time.perf_counter() - started, 3)
        print(json.dumps(result, ensure_ascii=False, default=str))
        return 0
    except Exception as e:
        if args.source:
            try:
                record_failure(workspace(args.root), args.source, "extract", e)
            except Exception:
                pass
        print(json.dumps({"ok": False, "error": str(e), "action": "记录此文件阻塞并继续其他文件，不重复同一命令"}, ensure_ascii=False))
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
