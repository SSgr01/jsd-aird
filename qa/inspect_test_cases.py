import json
import sys
from pathlib import Path

import openpyxl


path = Path(sys.argv[1])
wb = openpyxl.load_workbook(path, data_only=False, read_only=True)
out = {}
for ws in wb.worksheets:
    rows = []
    for row_idx, cells in enumerate(ws.iter_rows(values_only=True), start=1):
        vals = [None if v is None else str(v) for v in cells]
        if any(v not in (None, "") for v in vals):
            rows.append({"row": row_idx, "values": vals})
    out[ws.title] = rows
if len(sys.argv) > 2:
    Path(sys.argv[2]).write_text(json.dumps(out, ensure_ascii=False, indent=2), encoding="utf-8")
print(json.dumps({k: len(v) for k, v in out.items()}, ensure_ascii=False))
