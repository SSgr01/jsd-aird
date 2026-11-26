import shutil
import tempfile
import unittest
from pathlib import Path

from openpyxl import load_workbook
from exact_template import prepare


class ExactTemplateTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        (self.root / '标准模板').mkdir()
        self.source = self.root / '原始实验/测试实验/fixture.xlsx'
        self.source.parent.mkdir(parents=True)
        template = Path(__file__).parent.parent / 'templates/test.xlsx'
        shutil.copyfile(template, self.root / '标准模板/test.xlsx')
        shutil.copyfile(template, self.source)
        self.edit({'B1': 'synthetic title', 'C7': 'sample-a', 'D7': 'sample-b',
            'C51': 'experiment-a', 'D51': 'experiment-b', 'B52': 'material',
            'C52': 2, 'D52': 3, 'C60': 2, 'D60': 3, 'C104': '80–82°', 'D104': '90–92°'})

    def tearDown(self):
        self.tmp.cleanup()

    def edit(self, cells):
        workbook = load_workbook(self.source)
        for coord, value in cells.items():
            workbook.worksheets[0][coord] = value
        workbook.save(self.source)
        workbook.close()

    def test_exact_layout_uses_executor_and_keeps_values_pending_review(self):
        result = prepare(self.root)
        self.assertTrue(result['eligible'])
        self.assertEqual(result['result']['stage'], 'validated')
        self.assertEqual(result['human_review'], 'pending')
        workbook = load_workbook(self.root / result['result']['output'], data_only=True)
        self.assertEqual(workbook.worksheets[0]['C104'].value, '80–82°')
        self.assertEqual(workbook.worksheets[0]['C60'].value, 2)
        workbook.close()
        self.assertTrue(prepare(self.root)['result']['cache_hit'])

    def test_changed_label_or_unlabelled_data_falls_back_without_output(self):
        for change in ({'B104': 'different metric'}, {'C48': 99}):
            with self.subTest(change=change):
                original = self.source.read_bytes()
                self.edit(change)
                self.assertFalse(prepare(self.root)['eligible'])
                self.assertFalse((self.root / '转换结果').exists())
                self.source.write_bytes(original)

    def test_missing_cache_in_active_group_still_blocks(self):
        self.edit({'C60': '=IF(COUNTA(C52:C59)=0,"",SUM(C52:C59))'})
        with self.assertRaisesRegex(ValueError, '源公式缺缓存'):
            prepare(self.root)

    def test_wrong_total_still_blocks(self):
        self.edit({'C60': 999})
        with self.assertRaises(ValueError):
            prepare(self.root)


if __name__ == '__main__':
    unittest.main()
