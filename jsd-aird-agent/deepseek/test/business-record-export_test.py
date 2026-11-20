"""Run with a Python containing the deployment's openpyxl dependency."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

from openpyxl import load_workbook


class RecordExportTest(unittest.TestCase):
    def test_preserves_null_zero_formula_text_dates_methods_and_clickable_sources(self):
        script = Path(__file__).parents[2] / "enterprise-plugin/src/business-record-export.py"
        spec = importlib.util.spec_from_file_location("record_export", script)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        record = {
            "source": {"content_sha256": "a" * 64, "version_id": "fixed-version"},
            "url": "https://example.invalid/experiments/record?version_id=fixed-version",
            "content": {
                "section": "record", "current_registration": {"experiment_no": "000123", "experiment_date": "2026-11-18"},
                "overview": {"version_no": 1, "status": "DRAFT", "fields": {"title": "=1+1"}},
                "sections": {"testResults": [{"records": [
                    {"name": "黏度", "unit": "mPa·s", "testMethod": "旋转黏度计", "testCondition": "25℃",
                     "values": [{"sample_key": "A", "value": 0, "rawValue": "0.0"}, {"sample_key": "B", "value": None}]},
                ]}]},
            },
        }
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder)
            source = path / "source.json"
            source.write_text(json.dumps({"schema_version": 1, "records": [record]}), encoding="utf-8")
            output = path / "records.xlsx"
            module.export([source, source], output)
            workbook = load_workbook(output, data_only=False)
            try:
                overview = workbook["记录概览"]
                self.assertEqual(overview.max_row, 2)
                self.assertEqual([overview.cell(2, i).value for i in range(1, 5)], ["000123", "2026-11-18", "DRAFT", 1])
                sheet = workbook["指标明细"]
                self.assertEqual(sheet["H2"].value, 0)
                self.assertEqual(sheet["I2"].value, "0.0")
                self.assertIsNone(sheet["H3"].value)
                self.assertIsNone(sheet["I3"].value)
                self.assertEqual(sheet["J2"].value, "mPa·s")
                self.assertEqual(sheet["K2"].value, "旋转黏度计")
                self.assertEqual(sheet["L2"].value, "25℃")
                self.assertEqual(sheet["N2"].hyperlink.target, record["url"])
                raw = workbook["原始字段"]
                title = next(row[3] for row in raw if row[2].value == "/overview/fields/title")
                self.assertEqual(title.value, "=1+1")
                self.assertEqual(title.data_type, "s")
            finally:
                workbook.close()


if __name__ == "__main__":
    unittest.main()
