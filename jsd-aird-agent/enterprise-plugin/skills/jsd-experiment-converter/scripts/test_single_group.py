"""Source-bound single-result conversion and complete print-area regressions."""
import json
import shutil
import tempfile
import unittest
import zipfile
from unittest.mock import patch
from datetime import datetime
from pathlib import Path

from openpyxl import Workbook, load_workbook
from openpyxl.worksheet.cell_range import CellRange
from openpyxl.worksheet.print_settings import PrintArea

from execute_plan import execute, _docx_table_views
from workflow import digest, write_json


class SingleGroupTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / '标准模板').mkdir()
        self.template = self.root / '标准模板/test.xlsx'
        shutil.copy2(Path(__file__).parent.parent / 'templates/test.xlsx', self.template)
        self.source = self.root / '原始实验/测试实验/来样 记录.xlsx'
        self.source.parent.mkdir(parents=True)
        self.make_source([10])
        self.plan_path = self.root / 'plan.json'
        self.spec = {
            'source_sheet': '结果', 'single_group_test': {},
            'recipe': {'names': [], 'amounts': [], 'totals': []},
            'writes': [{'src': 'B1', 'dst': 'C24'}],
            'structural': [{'src': 'A1', 'reason': '项目标签'}], 'unplaced': [],
        }
        self.plan = {
            'version': 1, 'source': self.source.relative_to(self.root).as_posix(),
            'kind': 'test', 'source_sha256': digest(self.source),
            'template_sha256': digest(self.template), 'sheets': [self.spec], 'skipped': [],
        }

    def make_source(self, values):
        book = Workbook()
        sheet = book.active
        sheet.title = '结果'
        for row, value in enumerate(values, 1):
            sheet.cell(row, 1, '项目' + str(row))
            cell = sheet.cell(row, 2, value)
            if isinstance(value, str) and value.startswith('='):
                cell.data_type = 's'
        book.save(self.source)
        book.close()

    def run_plan(self):
        write_json(self.plan_path, self.plan)
        return execute(self.root, self.plan_path)

    def assert_no_output(self):
        self.assertFalse((self.root / '转换结果').exists())

    def test_old_literal_values_are_rejected(self):
        self.spec['single_group_test'] = {'values': {'C24': 999}}
        with self.assertRaisesRegex(ValueError, 'src/dst'):
            self.run_plan()
        self.assert_no_output()

    def test_write_cannot_override_source_with_a_literal(self):
        self.spec['writes'][0]['value'] = 999
        with self.assertRaisesRegex(ValueError, '不接受直接填写的值'):
            self.run_plan()
        self.assert_no_output()

    def test_source_values_and_empty_formulation_are_preserved(self):
        values = [10, 0, None, '/', '00123', datetime(2026, 11, 16), '=原文']
        self.make_source(values)
        self.plan['source_sha256'] = digest(self.source)
        self.spec['writes'] = [{'src': f'B{i}', 'dst': f'C{23+i}'} for i in range(1, 8)]
        self.spec['structural'] = [{'src': f'A{i}', 'reason': '项目标签'} for i in range(1, 8)]
        result = self.run_plan()
        book = load_workbook(self.root / result['output'])
        try:
            sheet = book.active
            self.assertEqual([sheet.cell(24+i, 3).value for i in range(7)], values)
            self.assertEqual(sheet['C30'].data_type, 's')
            self.assertIsNone(sheet['C52'].value)
            self.assertIsNone(sheet['C7'].value)
            self.assertIsNone(sheet['D24'].value)
        finally:
            book.close()
        report = json.loads((self.root / result['verification']).read_text(encoding='utf-8'))
        self.assertEqual(report['status'], 'validated')
        self.assertEqual(report['human_review'], 'pending')
        self.assertEqual(report['sheets'][0]['groups'], 1)
        self.assertEqual(report['sheets'][0]['materials'], 0)
        self.assertTrue(all(v['nonempty'] == v['accounted'] for v in report['coverage']))
        self.assertTrue(self.run_plan()['cache_hit'])

    def test_other_group_column_is_rejected(self):
        self.spec['writes'][0]['dst'] = 'E24'
        with self.assertRaisesRegex(ValueError, '其他组列不允许'):
            self.run_plan()
        self.assert_no_output()

    def test_headers_and_merged_remarks_are_inside_print_area(self):
        self.make_source([10, '2026-11-16', 25, 50, '保留原备注'])
        self.plan['source_sha256'] = digest(self.source)
        targets = ['C24', 'D2', 'F2', 'H2', 'C138']
        self.spec['writes'] = [{'src': f'B{i}', 'dst': target} for i, target in enumerate(targets, 1)]
        self.spec['structural'] = [{'src': f'A{i}', 'reason': '项目标签'} for i in range(1, 6)]
        result = self.run_plan()
        book = load_workbook(self.root / result['output'])
        try:
            sheet = book.active
            self.assertEqual(sheet['B138'].value, '保留原备注')
            area, = PrintArea.from_string(str(sheet.print_area)).ranges
            for target in ['C24', 'D2', 'F2', 'H2', 'B138']:
                self.assertIn(target, area)
            for merged in sheet.merged_cells.ranges:
                self.assertTrue(CellRange(str(merged)).issubset(area))
            self.assertEqual(area.max_col, 8)
        finally:
            book.close()

    def test_duplicate_target_aliases_are_rejected(self):
        for targets in [('C24', 'c24'), ('C138', 'B138')]:
            with self.subTest(targets=targets):
                self.spec['writes'] = [{'src': 'B1', 'dst': target} for target in targets]
                with self.assertRaisesRegex(ValueError, '重复写目标'):
                    self.run_plan()
                self.assert_no_output()

    def test_clipped_print_area_is_rejected_before_delivery(self):
        from template_ops import append_single_group_test

        def clipped(sheet, values):
            written = append_single_group_test(sheet, values)
            sheet.print_area = 'A1:C138'
            return written

        for header, message in [(True, '未覆盖全部写入'), (False, '截断模板合并')]:
            with self.subTest(header=header):
                self.spec['writes'] = [{'src': 'B1', 'dst': 'C24'}]
                if header:
                    self.spec['writes'].append({'src': 'B1', 'dst': 'H2'})
                with patch('template_ops.append_single_group_test', side_effect=clipped):
                    with self.assertRaisesRegex(ValueError, message):
                        self.run_plan()
                self.assertFalse(any((self.root / '转换结果').rglob('*.xlsx')))

    def test_unaccounted_source_and_mixed_groups_are_rejected(self):
        self.spec['structural'] = []
        with self.assertRaisesRegex(ValueError, '未处置'):
            self.run_plan()
        self.assert_no_output()
        self.spec['groups'] = ['B1']
        with self.assertRaisesRegex(ValueError, '分组'):
            self.run_plan()

    def test_explicit_transform_reads_original_text(self):
        self.make_source(['UVA能量：25'])
        self.plan['source_sha256'] = digest(self.source)
        self.spec['writes'][0]['transform'] = {'pattern': 'UVA能量：([0-9.]+)', 'numeric': True}
        result = self.run_plan()
        book = load_workbook(self.root / result['output'])
        try:
            self.assertEqual(book.active['C24'].value, 25)
        finally:
            book.close()

    def test_docx_values_use_the_same_source_mapping(self):
        self.source = self.source.with_suffix('.docx')
        xml = ('<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">'
               '<w:body><w:tbl><w:tr><w:tc><w:p><w:r><w:t>黏度</w:t></w:r></w:p></w:tc>'
               '<w:tc><w:p><w:r><w:t>10</w:t></w:r></w:p></w:tc></w:tr></w:tbl></w:body></w:document>')
        with zipfile.ZipFile(self.source, 'w') as archive:
            archive.writestr('word/document.xml', xml)
        # Remove the previous workbook so the inventory has only this source.
        self.source.with_suffix('.xlsx').unlink()
        self.plan['source'] = self.source.relative_to(self.root).as_posix()
        self.plan['source_sha256'] = digest(self.source)
        self.spec['source_sheet'] = 'table-1'
        _, paragraphs = _docx_table_views(self.source)
        self.plan['docx_paragraphs'] = [{'text': text, 'handling': '表格内容已映射'} for text in paragraphs]
        result = self.run_plan()
        book = load_workbook(self.root / result['output'])
        try:
            self.assertEqual(book.active['C24'].value, '10')
        finally:
            book.close()


if __name__ == '__main__':
    unittest.main(verbosity=2)
