"""Focused regressions for independent IDs, client-added rows and source conflicts.

All fixtures and outputs are synthetic and confined to this isolated test root.
No additional original experiment is loaded.
"""
import argparse
from pathlib import Path
import tempfile
import shutil
import unittest
import json
from copy import deepcopy
from openpyxl import Workbook, load_workbook

from execute_plan import execute
from workflow import digest, write_json

ROOT=None

class ClientTemplateTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory(dir=ROOT/'_tmp')
        self.root=Path(self.tmp.name)
        (self.root/'标准模板').mkdir()
        shutil.copy2(ROOT/'标准模板/test.xlsx',self.root/'标准模板/test.xlsx')
        (self.root/'原始实验/测试实验').mkdir(parents=True)
        self.source=self.root/'原始实验/测试实验/fixture.xlsx'
        self.plan_path=self.root/'plan.json'

    def tearDown(self):
        self.tmp.cleanup()

    def fixture(self,distinct=True,addition=True,mismatch=False):
        wb=Workbook()
        ws=wb.active
        ws.title='source'
        ws['A1']='synthetic fixture'
        ws['A16']='source conclusion'
        for i,c in enumerate('CDEFGHIJ'):
            if distinct:ws[f'{c}2']=f'S{i+1}'
            ws[f'{c}3']=f'E{i+1}'
            for r in range(4,14):ws[f'{c}{r}']=1
            ws[f'{c}14']=99 if mismatch and i==0 else 10
            if addition:ws[f'{c}15']=f'observation {i+1}'
        for r in range(4,14):ws[f'B{r}']=f'material {r-3}'
        if addition:ws['A15']='客户新增体系接触角（°）'
        wb.save(self.source)
        wb.close()
        spec=dict(source_sheet='source',groups=[f'{c}3' for c in 'CDEFGHIJ'],
            recipe=dict(names=[f'B{r}' for r in range(4,14)],
                amounts=[[f'{c}{r}' for c in 'CDEFGHIJ'] for r in range(4,14)],
                totals=[f'{c}14' for c in 'CDEFGHIJ']),
            writes=[dict(src='A1',dst='B1'),dict(src='A16',dst='B136')])
        if distinct:spec['sample_groups']=[f'{c}2' for c in 'CDEFGHIJ']
        if addition:spec['client_template_additions']=[dict(label_src='A15',after_template='B131',after_label='动摩擦系数',
            values=[f'{c}15' for c in 'CDEFGHIJ'],reason='explicit synthetic client template addition')]
        plan=dict(version=1,source='原始实验/测试实验/fixture.xlsx',kind='test',source_sha256=digest(self.source),
            template_sha256=digest(self.root/'标准模板/test.xlsx'),sheets=[spec],skipped=[])
        write_json(self.plan_path,plan)
        return plan

    def test_distinct_ids_added_row_both_axes_and_structures(self):
        self.fixture()
        result=execute(self.root,self.plan_path)
        self.assertEqual(result['stage'],'validated')
        wb=load_workbook(self.root/result['output'])
        values=load_workbook(self.root/result['output'],data_only=True)
        template=load_workbook(self.root/'标准模板/test.xlsx')
        try:
            ws=wb.active
            self.assertEqual(ws['C7'].value,'S1')
            self.assertEqual(ws['C51'].value,'E1')
            self.assertEqual(ws['J7'].value,'S8')
            self.assertEqual(ws['B134'].value,'客户新增体系接触角（°）')
            self.assertEqual(ws['J134'].value,'observation 8')
            self.assertEqual(ws['B133'].value,'动摩擦系数')
            self.assertEqual(ws['B135'].value,'抗刮伤：指甲刮')
            self.assertEqual(ws['B139'].value,'source conclusion')
            self.assertEqual(ws.max_row,141)
            self.assertIn('A66:A138',[str(x) for x in ws.merged_cells])
            self.assertIn('$J$141',str(ws.print_area))
            # Two additional recipe rows move the original frozen boundary by two.
            self.assertEqual(ws.freeze_panes,'A'+str(int(template.active.freeze_panes[1:])+2))
            self.assertEqual(ws['B134']._style,ws['B133']._style)
            self.assertEqual(ws['J62'].value,'=IF(COUNTA(J52:J61)=0,"",SUM(J52:J61))')
            self.assertEqual(values.active['J62'].value,10)
            for c in 'CDEFGHIJ':
                for r in range(52,62):
                    self.assertTrue(any(f'{c}{r}' in dv.sqref and dv.type=='custom' for dv in ws.data_validations.dataValidation))
                self.assertTrue(any(f'{c}10' in dv.sqref and dv.type=='list' for dv in ws.data_validations.dataValidation))
            merges=list(ws.merged_cells)
            for i,a in enumerate(merges):
                for b in merges[i+1:]:
                    overlap=not(a.max_row<b.min_row or b.max_row<a.min_row or a.max_col<b.min_col or b.max_col<a.min_col)
                    self.assertFalse(overlap)
        finally:
            wb.close(); values.close(); template.close()

    def test_legacy_shared_ids_and_no_added_row(self):
        self.fixture(distinct=False,addition=False)
        result=execute(self.root,self.plan_path)
        wb=load_workbook(self.root/result['output'])
        try:
            self.assertEqual(wb.active['C7'].value,'E1')
            self.assertEqual(wb.active['C51'].value,'E1')
            self.assertEqual(wb.active['B133'].value,'动摩擦系数')
            self.assertEqual(wb.active['B134'].value,'抗刮伤：指甲刮')
            self.assertEqual(wb.active.max_row,140)
            self.assertEqual(result['stage'],'validated')
        finally:wb.close()

    def test_strict_total_rejection_is_preserved(self):
        self.fixture(mismatch=True)
        with self.assertRaisesRegex(ValueError,'源合计与配方重算不一致'):
            execute(self.root,self.plan_path)
        self.assertFalse((self.root/'转换结果').exists())

    def test_explicit_review_preserves_conflict_and_actual_total(self):
        plan=self.fixture(mismatch=True)
        plan['sheets'][0]['recipe']['source_total_policy']='report_conflicts'
        write_json(self.plan_path,plan)
        result=execute(self.root,self.plan_path,self.root/'_work/review.xlsx')
        self.assertEqual(result['stage'],'review_required')
        resumed=execute(self.root,self.plan_path,self.root/'_work/review.xlsx')
        self.assertTrue(resumed['cache_hit'])
        self.assertEqual(resumed['stage'],'review_required')
        report=json.loads((self.root/result['verification']).read_text(encoding='utf-8'))
        self.assertEqual(report['source_conflicts'][0]['source_total'],99)
        self.assertEqual(report['source_conflicts'][0]['computed_total'],10)
        self.assertEqual(report['unplaced'][0]['value'],99)
        wb=load_workbook(self.root/result['output'],data_only=True)
        try:self.assertEqual(wb.active['C62'].value,10)
        finally:wb.close()

    def test_review_policy_cannot_produce_production_result(self):
        plan=self.fixture(mismatch=True)
        plan['sheets'][0]['recipe']['source_total_policy']='report_conflicts'
        write_json(self.plan_path,plan)
        with self.assertRaisesRegex(ValueError,'只能输出到工作区_work'):
            execute(self.root,self.plan_path)

    def test_missing_source_measurement_still_rejected(self):
        plan=self.fixture()
        plan['sheets'][0]['writes']=plan['sheets'][0]['writes'][1:]
        write_json(self.plan_path,plan)
        with self.assertRaisesRegex(ValueError,'未处置'):
            execute(self.root,self.plan_path)

    def test_unmatched_predecessor_is_rejected(self):
        plan=self.fixture()
        plan['sheets'][0]['client_template_additions'][0]['after_label']='wrong predecessor'
        write_json(self.plan_path,plan)
        with self.assertRaisesRegex(ValueError,'已核对的模板标签'):
            execute(self.root,self.plan_path)

    def test_stale_fingerprint_still_rejected(self):
        plan=self.fixture()
        plan['source_sha256']='0'*64
        write_json(self.plan_path,plan)
        with self.assertRaisesRegex(ValueError,'指纹已变'):
            execute(self.root,self.plan_path)

if __name__=='__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('--root',type=Path,required=True)
    args,rest=parser.parse_known_args()
    ROOT=args.root.resolve()
    (ROOT/'_tmp').mkdir(exist_ok=True)
    unittest.main(argv=[__file__,*rest],verbosity=2)
