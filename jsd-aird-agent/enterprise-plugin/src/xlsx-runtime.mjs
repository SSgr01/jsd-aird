// This runs in the existing Linux broker, never in the gateway's host Python.
export const inspectWorkbook = String.raw`import hashlib, json, pathlib, sys, zipfile

try:
    path = pathlib.Path(sys.argv[1])
    if hashlib.sha256(path.read_bytes()).hexdigest() != sys.argv[2]:
        raise ValueError("workbook changed before inspection")
    with zipfile.ZipFile(path) as package:
        if sum(part.file_size for part in package.infolist()) > 100 * 1024 * 1024:
            raise ValueError("expanded document exceeds limits")
        if not {"[Content_Types].xml", "xl/workbook.xml"} <= set(package.namelist()):
            raise ValueError("missing workbook parts")
        if package.testzip() is not None:
            raise ValueError("damaged workbook part")
    from openpyxl import load_workbook
    workbook = load_workbook(path, read_only=True, data_only=False, keep_links=False)
    try:
        if not workbook.worksheets:
            raise ValueError("workbook has no worksheets")
        sheets = []
        for sheet in workbook.worksheets:
            # Read the actual XML, rather than trusting a saved dimension that
            # could be stale or claim a much larger rectangle than the data.
            sheet.reset_dimensions()
            rows = sum(1 for _ in sheet.iter_rows(values_only=True))
            sheets.append({"name": sheet.title, "rows": rows})
        print(json.dumps({"sheets": sheets, "sha256": sys.argv[2]}, ensure_ascii=False))
    finally:
        workbook.close()
except Exception as error:
    print("EXPORT_FORMAT_INVALID: cannot open Excel workbook: " + str(error), file=sys.stderr)
    sys.exit(1)
`;

const shellQuote = value => "'" + value.replaceAll("'", "'\"'\"'") + "'";

export function workbookInspectionCommand(path, hash) {
  if (!/^[a-f0-9]{64}$/.test(hash)) throw new Error('INVALID_WORKBOOK_HASH');
  return '/usr/local/bin/python3 -I -B -c ' + shellQuote(inspectWorkbook)
    + ' ' + shellQuote(path) + ' ' + shellQuote(hash);
}

// Appended only to office-xlsx. The source data is supplied by the task; the
// example deliberately contains no customer's schema or fabricated rows.
export const workbookWorkflow = String.raw`

## Reliable workbook generation in this deployment

系统内已有实验记录导出优先用business_export_records(refs=搜索返回的固定版本ref)，收到workbook.path后present。已有business-records数据文件时也可直接用随文件返回的export_script。以下手工source_rows示例只适用于没有平台原值数据文件的其他表格任务；不得用它转抄已经存在的业务正文。

本环境的 Excel 执行要求：先调用 working_directory；脚本使用当前目录下的相对路径。
生成时完整复用下方示例的 imports、临时保存、重新打开和逐行原值比对代码，
再增加业务分析和样式。不要只摘取保存代码而遗漏 import tempfile；
临时文件检查通过后必须执行 os.replace(temporary_path, output_path)，然后确认
目标文件确实存在，才能报告生成成功；只打印文件名并不等于已经保存目标文件。
不要以手写单元格行号断言替换通用逐行比对。所有诊断代码也必须写成依赖完整的
独立脚本。先检查脚本语法再执行；错误按 stderr 定点修正，不重写源数据。
每个要交付的 Excel 都必须调用原生 present 登记，并收到成功回执后才能告知
客户文件可下载。普通 Markdown 文件链接、打印文件名和成功的生成命令不能
代替 present；补做业务核验后也要确认本次交付仍有成功的登记回执。

Use working_directory to obtain the exact current /workspaces/... directory.
Use relative paths for BOTH native write/edit and bash; /workspaces itself is
not the current workspace. Only the current workspace is transferred to bash.
Do not copy a Windows host path or invent /app/default, /tmp or a parent folder.

Keep the source values in one data file and the Python script in another. Read
the source once instead of retyping it during a repair. Preserve text IDs,
leading zeros, zero values, empty cells and date meaning. Keep source columns
separate from derived columns. Use descriptive function and variable names;
never reuse a function name for a header, row, column or cell variable.
Keep numeric measurements as numbers with units in a separate column or number
format; do not concatenate units into numeric cells. Preserve a missing source
value as null/None (a genuinely empty Excel cell), with any explanation in a
separate notes column. Keep full business record numbers. Registration dates
are available in current_registration and must be labelled as registration
dates; never infer a measurement timestamp from them. Do not query invented
date/created_at field codes when the full record already contains registration.
Reuse the complete generic save/reopen/comparison block below for raw source
rows. Do not replace it with individually hardcoded Excel row assertions:
the header occupies row 1, and source record numbers are not Excel row numbers.
Extend task-specific analysis separately, after the raw data is correct.

The following is executable Python. Prepare source_rows.json from the actual
source as {"headers": [...], "rows": [[...], ...]}; this is a local intermediate
file, not a platform API. Replace the output filename and extend the workbook
for the user's task. Do not invent example business values.

~~~python
import json
import os
import tempfile
from pathlib import Path
from openpyxl import Workbook, load_workbook
from openpyxl.styles import Font

source = json.loads(Path("source_rows.json").read_text(encoding="utf-8"))
headers = source["headers"]
source_rows = source["rows"]
if not headers or any(len(row_values) != len(headers) for row_values in source_rows):
    raise ValueError("Source headers and row widths do not match")

workbook = Workbook()
raw_sheet = workbook.active
raw_sheet.title = "原始记录"
raw_sheet.append(headers)
for row_values in source_rows:
    raw_sheet.append(row_values)
for header_cell in raw_sheet[1]:
    header_cell.font = Font(bold=True)
raw_sheet.freeze_panes = "A2"

output_path = Path("整理结果.xlsx")
descriptor, temporary_name = tempfile.mkstemp(
    prefix=".xlsx-", suffix=".xlsx", dir=output_path.parent
)
os.close(descriptor)
temporary_path = Path(temporary_name)
try:
    workbook.save(temporary_path)
    reopened = load_workbook(temporary_path, data_only=False)
    try:
        saved_sheet = reopened["原始记录"]
        assert saved_sheet.max_row == len(source_rows) + 1
        assert saved_sheet.max_column == len(headers)
        expected_rows = [headers, *source_rows]
        for row_number, expected_values in enumerate(expected_rows, 1):
            actual_values = [saved_sheet.cell(row_number, column_number).value
                             for column_number in range(1, len(headers) + 1)]
            # openpyxl reloads an empty string as None; neither is numeric zero.
            assert actual_values == [None if value == "" else value
                                     for value in expected_values], row_number
    finally:
        reopened.close()
    os.replace(temporary_path, output_path)
    assert output_path.is_file(), "Final workbook was not saved"
finally:
    temporary_path.unlink(missing_ok=True)
    workbook.close()
print(output_path)
~~~

Before running the script, check its syntax without generating __pycache__:
~~~bash
/usr/local/bin/python3 -c 'import ast; from pathlib import Path; ast.parse(Path("build_workbook.py").read_text(encoding="utf-8"))'
/usr/local/bin/python3 build_workbook.py
~~~
Use native write/edit for scripts so shell quoting cannot change their contents.
This also applies to diagnostic scripts: put complete imports at the top of a
file and run that file, rather than improvising multiline python -c snippets.
For a failure, read the actual stderr and repair that cause; do not search for
another Python, reinstall dependencies, or rewrite the source data. For an
existing workbook use load_workbook(data_only=False), preserve other sheets,
formulas and formatting, and save to a new filename by default. Check dates
according to their actual source type rather than assuming Excel serial values
are literal strings. Call present only after the saved workbook passes checks.
The runtime also opens .xlsx files before registration; that is a structural
check, not proof of data accuracy or business correctness.
`;
