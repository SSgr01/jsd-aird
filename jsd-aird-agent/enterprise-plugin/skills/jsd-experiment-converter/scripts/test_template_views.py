import unittest
from openpyxl import Workbook
from openpyxl.worksheet.views import Pane
from template_ops import pristine_sheet, shift_view_rows


class TemplateViewTests(unittest.TestCase):
    def test_copy_preserves_two_frozen_rows_when_bottom_pane_is_scrolled_far_down(self):
        book = Workbook()
        blank = book.active
        blank.sheet_view.pane = Pane(ySplit=2, topLeftCell='A63', activePane='bottomLeft', state='frozen')
        result = pristine_sheet(book, blank, '测试实验')
        self.assertEqual(result.sheet_view.pane.ySplit, 2)
        self.assertEqual(result.sheet_view.pane.topLeftCell, 'A63')
        self.assertEqual(blank.sheet_view.pane.ySplit, 2)

    def test_recipe_insertion_moves_scroll_origin_without_freezing_more_rows(self):
        sheet = Workbook().active
        sheet.sheet_view.pane = Pane(ySplit=2, topLeftCell='A63', activePane='bottomLeft', state='frozen')
        shift_view_rows(sheet, 60, 5)
        self.assertEqual(sheet.sheet_view.pane.ySplit, 2)
        self.assertEqual(sheet.sheet_view.pane.topLeftCell, 'A68')

    def test_insertion_inside_frozen_rows_expands_only_the_frozen_boundary(self):
        sheet = Workbook().active
        sheet.sheet_view.pane = Pane(ySplit=4, topLeftCell='A40', activePane='bottomLeft', state='frozen')
        shift_view_rows(sheet, 3, 2)
        self.assertEqual(sheet.sheet_view.pane.ySplit, 6)
        self.assertEqual(sheet.sheet_view.pane.topLeftCell, 'A42')


if __name__ == '__main__':
    unittest.main()
