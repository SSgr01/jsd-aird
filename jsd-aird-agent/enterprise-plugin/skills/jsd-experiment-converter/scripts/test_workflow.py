"""Behavioral regressions using synthetic sources and packaged empty templates."""
import argparse
import json
import shutil
import tempfile
import unittest
import zipfile
from pathlib import Path

from openpyxl import Workbook, load_workbook
from openpyxl.utils import get_column_letter

from execute_plan import execute, convert_value
from template_ops import pristine_sheet, expand_recipe, expand_groups
from workflow import write_json, extract_docx, extract, digest

ROOT = None


class WorkflowTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        (self.root / "标准模板").mkdir()
        templates = ROOT / "标准模板" if ROOT else Path(__file__).parent.parent / "templates"
        for kind in ("test", "synthesis"):
            shutil.copy2(templates / (kind+".xlsx"), self.root / "标准模板")
        for category in ("测试实验", "合成实验"):
            (self.root / "原始实验" / category).mkdir(parents=True)
        source = self.root / "原始实验/测试实验/fixture.xlsx"
        workbook = Workbook()
        workbook.remove(workbook.active)
        sheets = []
        for ordinal in (1, 2):
            ws = workbook.create_sheet(f"Sheet{ordinal}")
            ws['E2'], ws['G2'], ws['I2'] = '2024-01-01', 25, 50
            writes = [dict(src=src, dst=dst) for src, dst in (('E2', 'D2'), ('G2', 'F2'), ('I2', 'H2'))]
            for r in range(17, 26):
                ws[f'C{r}'] = f'synthetic-material-{r - 16}-sheet{ordinal}'
            for index, (sc, tc) in enumerate(zip('DEFGHI', 'CDEFGH')):
                ws[f'{sc}16'] = f'fixture-{ordinal}-{index + 1}'
                ws[f'{sc}10'] = 10 + index
                ws[f'{sc}11'] = 'UV光固化'
                for r in range(17, 26):
                    ws[f'{sc}{r}'] = 20 if r == 25 else 10
                ws[f'{sc}26'] = 100
                writes.extend([dict(src=f'{sc}10', dst=f'{tc}21'), dict(src=f'{sc}11', dst=f'{tc}10')])
            unplaced = []
            if ordinal == 1:
                for sc in 'DEFGHI':
                    ws[f'{sc}33'] = .5
                    unplaced.append(dict(src=f'{sc}33', reason='synthetic extra time point', suggested_field='warp after 12h'))
            sheets.append(dict(source_sheet=ws.title, groups=[f'{sc}16' for sc in 'DEFGHI'],
                recipe=dict(names=[f'C{r}' for r in range(17, 26)],
                    amounts=[[f'{sc}{r}' for sc in 'DEFGHI'] for r in range(17, 26)],
                    totals=[f'{sc}26' for sc in 'DEFGHI']), writes=writes, structural=[], unplaced=unplaced))
        workbook.save(source)
        workbook.close()
        self.plan = dict(version=1, source='原始实验/测试实验/fixture.xlsx', kind='test',
            source_sha256=digest(source), template_sha256=digest(self.root / '标准模板/test.xlsx'),
            sheets=sheets, skipped=[])
        self.plan_path = self.root / "plan.json"
        write_json(self.plan_path, self.plan)

    def tearDown(self):
        self.tmp.cleanup()

    def test_customer_no_charge_is_explicit_and_measurements_stay_strict(self):
        for raw in (None, '', '/', '-', '－'):
            self.assertEqual(convert_value(raw, 'customer_charge_g'), 0)
        self.assertEqual(convert_value('39.760', 'customer_charge_g'), 39.760)
        with self.assertRaises(ValueError):
            convert_value('1/2', 'customer_charge_g')
        with self.assertRaises(ValueError):
            convert_value('-1', 'customer_charge_g')
        self.assertIsNone(convert_value(None, None))

    def test_repeated_charges_keep_original_names_order_and_path_inference(self):
        source=self.root/'原始实验/合成实验/林志华实验记录/fixture.xlsx'
        source.parent.mkdir()
        wb=Workbook();ws=wb.active
        ws['B1']='sample-1'
        for r,name,qty in [(2,'S-8',10),(3,'S-8',2),(4,'P-O',0.5)]:
            ws.cell(r,1,name);ws.cell(r,2,qty)
        wb.save(source);wb.close()
        recipe=dict(names=['A2','A3','A4'],amounts=[['B2'],['B3'],['B4']],preserve_repeated_lines=True)
        plan=dict(version=1,source=source.relative_to(self.root).as_posix(),kind='synthesis',
            source_sha256=digest(source),template_sha256=digest(self.root/'标准模板/synthesis.xlsx'),
            sheets=[dict(source_sheet='Sheet',groups=['B1'],recipe=recipe,
                writes=[dict(src='@source.parent',dst='B2',transform={'pattern':'(.+)实验记录'})])],skipped=[])
        write_json(self.plan_path,plan)
        result=execute(self.root,self.plan_path)
        out=load_workbook(self.root/result['output'],data_only=True)
        try:
            self.assertEqual([(out.active.cell(r,2).value,out.active.cell(r,3).value) for r in range(10,13)], [('S-8',10),('S-8',2),('P-O',.5)])
            self.assertEqual(out.active['C24'].value,12.5)
            self.assertEqual(out.active['B2'].value,'林志华')
        finally:out.close()
        recipe['preserve_repeated_lines']=False
        write_json(self.plan_path,plan)
        with self.assertRaisesRegex(ValueError,'物料名称重复'):
            execute(self.root,self.plan_path,self.root/'_work/rejected.xlsx')

    def test_published_metadata_stays_hidden_and_total_cache_stays_on_business_sheet(self):
        template = self.root / '标准模板/test.xlsx'
        wb = load_workbook(template)
        expand_recipe(wb.active, 9, 'test')
        for name in ('_JSD_LAB_META', '_JSD_EXPORT_META'):
            meta = wb.create_sheet(name)
            meta['A1'], meta['B1'] = 'templateVersionId', 'published-fixture'
            meta.sheet_state = 'hidden'
        wb.save(template); wb.close()
        self.plan['template_sha256'] = digest(template)
        self.plan['sheets'] = self.plan['sheets'][:1]
        self.plan['skipped'] = [dict(source_sheet='Sheet2', reason='not selected')]
        write_json(self.plan_path, self.plan)
        result = execute(self.root, self.plan_path)
        out = load_workbook(self.root / result['output'], data_only=True)
        try:
            self.assertEqual(out.sheetnames, ['测试实验', '_JSD_LAB_META', '_JSD_EXPORT_META'])
            self.assertEqual(out.active.title, '测试实验')
            self.assertEqual(out['测试实验']['C61'].value, 100)
            for name in ('_JSD_LAB_META', '_JSD_EXPORT_META'):
                self.assertEqual(out[name].sheet_state, 'hidden')
                self.assertEqual(out[name]['B1'].value, 'published-fixture')
        finally:
            out.close()

    def test_exported_synthesis_validation_restored_only_for_known_layout(self):
        wb = load_workbook(self.root / '标准模板/synthesis.xlsx')
        ws = wb.active
        ws.data_validations.dataValidation.clear()
        for name in ('_JSD_LAB_META', '_JSD_EXPORT_META'):
            wb.create_sheet(name).sheet_state = 'hidden'
        info, _ = expand_recipe(ws, 4, 'synthesis')
        self.assertEqual((info['first'], info['last'], info['total']), (10, 23, 24))
        self.assertTrue(any('C10' in dv.sqref and 'H23' in dv.sqref for dv in ws.data_validations.dataValidation))
        ws.data_validations.dataValidation.clear()
        ws['B47'] = '不同指标'
        with self.assertRaisesRegex(ValueError, '验证区域'):
            expand_recipe(ws, 4, 'synthesis')
        wb.close()

    def test_synthetic_two_sheet_data_and_restart(self):
        first = execute(self.root, self.plan_path)
        self.assertFalse(first["cache_hit"])
        self.assertTrue(execute(self.root, self.plan_path)["cache_hit"])
        out = load_workbook(self.root / first["output"], data_only=True)
        src = load_workbook(self.root / self.plan["source"], data_only=True)
        try:
            self.assertEqual(out.sheetnames, ["测试实验", "测试实验-2"])
            for sn, tn in zip(("Sheet1", "Sheet2"), out.sheetnames):
                target = out[tn]
                for sc, tc in zip("DEFGHI", "CDEFGH"):
                    self.assertEqual(target[f"{tc}7"].value, src[sn][f"{sc}16"].value)
                    self.assertEqual(target[f"{tc}51"].value, src[sn][f"{sc}16"].value)
                    for sr in range(17, 26):
                        self.assertEqual(target[f"{tc}{sr+35}"].value, src[sn][f"{sc}{sr}"].value)
                    self.assertEqual(target[f"{tc}61"].value, 100)
                self.assertEqual(target["B49"].value, "具体方案")
                self.assertEqual(target["B24"].value, "黏度\ncP（mPa·s）/ KU / s")
                for sc, tc in (("E2", "D2"), ("G2", "F2"), ("I2", "H2")):
                    self.assertEqual(target[tc].value, src[sn][sc].value)
                self.assertIsNone(target["C119"].value)  # 12h warp must not land in acid-rain row.
            self.assertEqual(out.worksheets[0]["B58"].value, "synthetic-material-7-sheet1")
            self.assertEqual(out.worksheets[1]["B58"].value, "synthetic-material-7-sheet2")
            self.assertEqual(out.worksheets[0]["C58"].value, 10)
            self.assertEqual(out.worksheets[1]["C58"].value, 10)
            report = json.loads((self.root / first["verification"]).read_text(encoding="utf-8"))
            self.assertEqual(len(report["unplaced"]), 6)
            self.assertTrue(all(c["nonempty"] == c["accounted"] for c in report["coverage"]))
        finally:
            out.close()
            src.close()

    def test_reject_missing_measurement(self):
        # Drop the only placement of a real value while leaving labels accounted for.
        self.plan["sheets"][0]["writes"] = [w for w in self.plan["sheets"][0]["writes"] if w["src"] != "D10"]
        write_json(self.plan_path, self.plan)
        with self.assertRaisesRegex(ValueError, "未处置"):
            execute(self.root, self.plan_path)
        self.assertFalse((self.root / "转换结果").exists())

    def test_reject_stale_fingerprint(self):
        self.plan["source_sha256"] = "0" * 64
        write_json(self.plan_path, self.plan)
        with self.assertRaisesRegex(ValueError, "指纹已变"):
            execute(self.root, self.plan_path)

    def test_reject_invalid_dropdown_value(self):
        item = next(w for w in self.plan["sheets"][0]["writes"] if w["dst"] == "C10")
        item["transform"] = {"pattern": "(UV)光固化"}
        write_json(self.plan_path, self.plan)
        with self.assertRaisesRegex(ValueError, "下拉值不在模板选项中"):
            execute(self.root, self.plan_path)
        self.assertFalse(list((self.root / "转换结果").rglob("*.xlsx")))

    def test_both_axes_and_blank_donor(self):
        for kind, count, groups, total, endrow in (("test", 15, 12, 67, 145), ("synthesis", 17, 8, 27, 57)):
            with self.subTest(kind=kind):
                wb = load_workbook(self.root / "标准模板" / (kind+".xlsx"))
                try:
                    blank = wb.active
                    first = pristine_sheet(wb, blank, "first")
                    info, rowmap = expand_recipe(first, count, kind)
                    expand_groups(first, info, groups)
                    self.assertEqual(info["total"], total)
                    self.assertEqual(first.max_row, endrow)
                    self.assertEqual(rowmap(138 if kind == "test" else 52), 145 if kind == "test" else 55)
                    for col in range(3, groups+3):
                        for row in range(info["first"], info["last"]+1):
                            addr = f"{get_column_letter(col)}{row}"
                            self.assertTrue(any(addr in d.sqref for d in first.data_validations.dataValidation))
                        if kind == "test":
                            self.assertTrue(any(f"{get_column_letter(col)}10" in d.sqref and d.type == "list"
                                                for d in first.data_validations.dataValidation))
                    self.assertIn(f"{get_column_letter(groups+2)}${endrow}", str(first.print_area))
                    first["C9"] = "must-not-leak"
                    second = pristine_sheet(wb, blank, "second")
                    self.assertIsNone(second["C9"].value)
                    self.assertEqual(second.freeze_panes, blank.freeze_panes)
                    self.assertEqual(len(second.data_validations.dataValidation), len(blank.data_validations.dataValidation))
                finally:
                    wb.close()

    def test_nested_docx_tables_without_optional_library(self):
        xml = '''<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:tbl><w:tr><w:tc><w:p><w:r><w:t>实验配方</w:t></w:r></w:p><w:tbl><w:tr><w:tc><w:p><w:r><w:t>配方A</w:t></w:r></w:p></w:tc></w:tr></w:tbl></w:tc></w:tr></w:tbl></w:body></w:document>'''
        path = self.root / "nested.docx"
        with zipfile.ZipFile(path, "w") as z:
            z.writestr("word/document.xml", xml)
        result = extract_docx(path)
        self.assertEqual(len(result["tables"]), 2)
        self.assertEqual(result["tables"][0]["rows"], [["实验配方"]])
        self.assertEqual(result["tables"][1]["rows"], [["配方A"]])

    def test_extraction_cache(self):
        source = self.plan["source"]
        self.assertFalse(extract(self.root, source, limit=2)["cache_hit"])
        self.assertTrue(extract(self.root, source, limit=2)["cache_hit"])


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, help="Optional workspace providing 标准模板; defaults to the packaged templates")
    args, rest = parser.parse_known_args()
    ROOT = args.root.resolve() if args.root else None
    unittest.main(argv=[__file__, *rest], verbosity=2)
