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

    def test_compact_preview_preserves_cell_evidence_and_cache(self):
        result = extract(self.root, self.plan['source'], limit=1)
        preview_path = self.root / result['preview']
        original_path = self.root / result['artifact']
        preview = json.loads(preview_path.read_text(encoding='utf-8'))
        original = json.loads(original_path.read_text(encoding='utf-8'))
        self.assertLess(len(result['preview']), 70)
        self.assertLess(preview_path.stat().st_size, original_path.stat().st_size)
        self.assertLess(max(map(len, preview_path.read_text(encoding='utf-8').splitlines())), 2000)
        for sheet, compact in zip(original['sheets'], preview['sheets']):
            for cell in sheet['cells']:
                actual = compact['cells'][cell['cell']]
                self.assertEqual(actual['value'], cell['value'])
                self.assertEqual(actual.get('formula'), cell.get('formula'))
                self.assertEqual(actual.get('format', 'General'), cell.get('format'))
        self.assertEqual(extract(self.root, self.plan['source'])['preview'], result['preview'])

    def test_invalid_plan_reports_contract_instead_of_a_key_error(self):
        write_json(self.plan_path, {'version': 1, 'source': self.plan['source'], 'meta': {}})
        with self.assertRaisesRegex(ValueError, 'INVALID_MAPPING_PLAN.*kind.*execution.md'):
            execute(self.root, self.plan_path)

    def test_all_protected_targets_are_reported_together(self):
        self.plan['sheets'][0]['writes'].extend([{'src': 'E2', 'dst': 'A1'}, {'src': 'G2', 'dst': 'A2'}])
        write_json(self.plan_path, self.plan)
        with self.assertRaisesRegex(ValueError, '拒绝覆盖模板标签.*A1.*A2'):
            execute(self.root, self.plan_path)

    def test_customer_no_charge_is_explicit_and_measurements_stay_strict(self):
        for raw in (None, '', '/', '-', '－'):
            self.assertEqual(convert_value(raw, 'customer_charge_g'), 0)
        self.assertEqual(convert_value('39.760', 'customer_charge_g'), 39.760)
        with self.assertRaises(ValueError):
            convert_value('1/2', 'customer_charge_g')
        with self.assertRaises(ValueError):
            convert_value('-1', 'customer_charge_g')
        self.assertIsNone(convert_value(None, None))

    def test_separate_observation_sources_preserve_same_sample_and_condition(self):
        source=self.root/'原始实验/测试实验/observations.xlsx'
        wb=Workbook();ws=wb.active
        for row in (1,2):
            ws.cell(row,1,'sample-A');ws.cell(row,2,'1%');ws.cell(row,3,110)
        wb.save(source);wb.close()
        spec=dict(source_sheet='Sheet',groups=['A1','A2'],group_identity_role='PLAN',
            group_condition_refs=[['B1'],['B2']],recipe=dict(names=[],amounts=[]),
            writes=[dict(src='B1',dst='C49'),dict(src='B2',dst='D49'),
                    dict(src='C1',dst='C104'),dict(src='C2',dst='D104')])
        plan=dict(version=1,source=source.relative_to(self.root).as_posix(),kind='test',
            source_sha256=digest(source),template_sha256=digest(self.root/'标准模板/test.xlsx'),
            sheets=[spec],skipped=[])
        write_json(self.plan_path,plan)
        with self.assertRaisesRegex(ValueError,'逐次观测'):
            execute(self.root,self.plan_path)
        spec['group_observation_refs']=[['C1'],['C2']]
        write_json(self.plan_path,plan)
        result=execute(self.root,self.plan_path)
        out=load_workbook(self.root/result['output'],data_only=True)
        try:
            self.assertEqual([out.active[c].value for c in ('C7','D7')],['sample-A','sample-A'])
            self.assertEqual([out.active[c].value for c in ('C49','D49')],['1%','1%'])
            self.assertEqual([out.active[c].value for c in ('C104','D104')],[110,110])
        finally:out.close()
        for refs in ([['C1'],['C1']],[['C1'],['B2']],[['C1'],['D3']]):
            spec['group_observation_refs']=refs;write_json(self.plan_path,plan)
            with self.assertRaisesRegex(ValueError,'检测来源'):
                execute(self.root,self.plan_path,self.root/'_work/rejected.xlsx')
        spec['group_observation_refs']=[['C1'],['C2']]
        spec['writes'][-1]['dst']='C105';write_json(self.plan_path,plan)
        with self.assertRaisesRegex(ValueError,'本组标准检测结果'):
            execute(self.root,self.plan_path,self.root/'_work/rejected.xlsx')

    def test_multiple_recipe_items_in_one_source_cell_keep_separate_extractions(self):
        source = self.root/'原始实验/测试实验/inline-recipe.xlsx'
        wb = Workbook();ws = wb.active
        ws['A1'],ws['B1'] = 'sample-A','sample-B'
        ws['A2'] = 'Resin: 9.20\nF279: 0.80\nBP/10% 2.25'
        wb.save(source);wb.close()
        entries = [{'name':'A2','amount':'A2',
                    'name_transform':{'pattern':r'('+label+r')'},
                    'amount_transform':{'pattern':label+r'[: ]+([0-9.]+)','numeric':True}}
                   for label in ['Resin','F279','BP/10%']]
        spec = dict(source_sheet='Sheet',groups=['A1','B1'],group_identity_role='SAMPLE',
                    recipe={'entries':[entries,[dict(entry) for entry in entries]]},writes=[])
        plan = dict(version=1,source=source.relative_to(self.root).as_posix(),kind='test',
                    source_sha256=digest(source),template_sha256=digest(self.root/'标准模板/test.xlsx'),
                    sheets=[spec],skipped=[])
        write_json(self.plan_path,plan)
        result = execute(self.root,self.plan_path)
        out = load_workbook(self.root/result['output'],data_only=True)
        try:
            self.assertEqual([out.active[f'B{r}'].value for r in range(52,55)],['Resin','F279','BP/10%'])
            for col in 'CD':
                self.assertEqual([out.active[f'{col}{r}'].value for r in range(52,55)],[9.2,.8,2.25])
                self.assertEqual(out.active[f'{col}60'].value,12.25)
        finally:out.close()
        entries[0]['amount_transform'] = {'pattern':r'([0-9]+\.[0-9]+)','numeric':True}
        write_json(self.plan_path,plan)
        with self.assertRaisesRegex(ValueError,'不唯一'):
            execute(self.root,self.plan_path,self.root/'_work/rejected.xlsx')
        entries[0]['amount_value'] = 9.2
        write_json(self.plan_path,plan)
        with self.assertRaisesRegex(ValueError,'不接受直接填值'):
            execute(self.root,self.plan_path,self.root/'_work/rejected.xlsx')

    def test_percentage_composition_keeps_source_basis_without_gram_total(self):
        source=self.root/'原始实验/测试实验/composition.xlsx'
        wb=Workbook();ws=wb.active
        ws['A1']='sample-A';ws['A2']='stock 95/5';ws['B2']=97.5
        ws['A3']=2959;ws['B3']=.02;ws['B3'].number_format='0%'
        wb.save(source);wb.close()
        binding={'dst':'C20','composition_parts':[{'name':'A2','amount':'B2'},{'name':'A3','amount':'B3'}]}
        spec={'source_sheet':'Sheet','groups':['A1'],'group_identity_role':'SAMPLE',
              'recipe':{'names':[],'amounts':[]},'writes':[binding]}
        plan={'version':1,'source':source.relative_to(self.root).as_posix(),'kind':'test',
              'source_sha256':digest(source),'template_sha256':digest(self.root/'标准模板/test.xlsx'),
              'sheets':[spec],'skipped':[]}
        write_json(self.plan_path,plan);result=execute(self.root,self.plan_path)
        out=load_workbook(self.root/result['output'],data_only=True)
        try:
            self.assertEqual(out.active['C20'].value,'stock 95/5：97.5\n2959：2%')
            self.assertIsNone(out.active['C52'].value)
            self.assertIsNone(out.active['C60'].value)
        finally:out.close()
        binding['dst']='C104';write_json(self.plan_path,plan)
        with self.assertRaisesRegex(ValueError,'只能写本组组成说明'):
            execute(self.root,self.plan_path,self.root/'_work/rejected.xlsx')

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
