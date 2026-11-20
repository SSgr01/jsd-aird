"""Export authorized business snapshots without retyping or interpreting values."""
import argparse
import json
import os
from pathlib import Path
import tempfile

from openpyxl import Workbook, load_workbook
from openpyxl.styles import Alignment, Font, PatternFill


def scalar(value):
    return json.dumps(value, ensure_ascii=False) if isinstance(value, (dict, list)) else value


def leaves(value, path=""):
    if isinstance(value, dict) and value:
        for key, item in value.items():
            yield from leaves(item, path + "/" + str(key).replace("~", "~0").replace("/", "~1"))
    elif isinstance(value, list) and value:
        for index, item in enumerate(value):
            yield from leaves(item, path + "/" + str(index))
    else:
        yield path, scalar(value)


def export(inputs, output):
    records, seen = [], set()
    for path in inputs:
        data = json.loads(Path(path).read_text(encoding="utf-8"))
        if data.get("schema_version") != 1 or not isinstance(data.get("records"), list):
            raise ValueError("Expected a business-records snapshot")
        for record in data["records"]:
            identity = json.dumps([record.get("ref"), record["source"]["content_sha256"]], sort_keys=True)
            if identity not in seen:
                records.append(record)
                seen.add(identity)
    if not records:
        raise ValueError("No source records")
    workbook = Workbook()
    workbook.remove(workbook.active)
    tables = {
        "记录概览": [["业务编号", "登记日期", "状态", "版本", "标题", "正文读取范围", "出处"]],
        "指标明细": [["业务编号", "登记日期", "状态", "版本", "样品", "分区", "字段", "数值", "原始值", "单位", "方法", "条件", "说明", "出处"]],
        "原始字段": [["业务编号", "版本", "字段路径", "原值", "出处"]],
    }
    for record in records:
        content, source = record["content"], record["source"]
        registration = content.get("current_registration", {})
        number = registration.get("experiment_no") or source.get("title") or source.get("resource_id")
        date = registration.get("experiment_date")
        overview = content.get("overview", content)
        version = overview.get("version_no", overview.get("versionNo", source.get("version_id")))
        status = overview.get("status")
        url = record.get("url")
        tables["记录概览"].append([number, date, status, version, registration.get("title") or source.get("title"), content.get("section"), url])
        for path, value in leaves(content):
            tables["原始字段"].append([number, version, path, value, url])
        sections = content.get("sections") if content.get("section") == "record" else {content.get("section", "content"): [content]}
        for section, pages in (sections or {}).items():
            if not isinstance(pages, list):
                continue
            for page in pages:
                for row in page.get("records", []):
                    values = row.get("values")
                    if not isinstance(values, list):
                        continue
                    for value in values:
                        raw = value.get("rawValue") if "rawValue" in value else value.get("value")
                        field = row.get("testItem") or row.get("name") or row.get("label") or row.get("field_code")
                        unit = value.get("rawUnit", value.get("unit", row.get("unit")))
                        tables["指标明细"].append([number, date, status, version,
                            value.get("sample_key", value.get("sampleKey")), section, field, scalar(value.get("value")), scalar(raw), unit,
                            row.get("testMethod", row.get("method")), row.get("testCondition", row.get("condition", row.get("conditions"))),
                            row.get("description"), url])
    expected = {}
    for name, rows in tables.items():
        sheet = workbook.create_sheet(name)
        normalized = [[scalar(v) for v in row] for row in rows]
        expected[name] = normalized
        for row in normalized:
            sheet.append(row)
        for cells in sheet:
            for cell in cells:
                if isinstance(cell.value, str):
                    if len(cell.value) > 32767:
                        raise ValueError("Source cell exceeds the Excel text limit; retain the JSON snapshot")
                    cell.data_type = "s"
                cell.alignment = Alignment(vertical="top", wrap_text=True)
            link = cells[-1]
            if isinstance(link.value, str) and link.value.startswith(("http://", "https://")):
                link.hyperlink = link.value
                link.style = "Hyperlink"
        for cell in sheet[1]:
            cell.font = Font(bold=True)
            cell.fill = PatternFill("solid", fgColor="E6EEF5")
        sheet.freeze_panes = "A2"
        sheet.auto_filter.ref = sheet.dimensions
        for column in sheet.columns:
            sheet.column_dimensions[column[0].column_letter].width = 24 if column[0].column < 6 else 42
    output = Path(output)
    descriptor, temporary = tempfile.mkstemp(prefix=".records-", suffix=".xlsx", dir=output.parent)
    os.close(descriptor)
    try:
        workbook.save(temporary)
        reopened = load_workbook(temporary, data_only=False)
        try:
            for name, rows in expected.items():
                sheet = reopened[name]
                assert sheet.max_row == len(rows) and sheet.max_column == len(rows[0])
                for row_number, row in enumerate(rows, 1):
                    assert [sheet.cell(row_number, col).value for col in range(1, len(row) + 1)] == [None if v == "" else v for v in row]
                    url = row[-1]
                    if isinstance(url, str) and url.startswith(("http://", "https://")):
                        assert sheet.cell(row_number, len(row)).hyperlink.target == url
        finally:
            reopened.close()
        os.replace(temporary, output)
    finally:
        Path(temporary).unlink(missing_ok=True)
        workbook.close()
    print(json.dumps({"path": str(output), "records": len(records), "sheets": {name: len(rows) - 1 for name, rows in tables.items()}}, ensure_ascii=False))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", nargs="+", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    export(args.input, args.output)
