"""Expanded group columns must survive XLSX serialization at template width."""
import tempfile
import unittest
from pathlib import Path
from openpyxl import Workbook,load_workbook
from openpyxl.worksheet.dimensions import ColumnDimension
from template_ops import expand_groups,effective_column_dimension,fit_written_text

class TemplateLayoutTests(unittest.TestCase):
    def test_grouped_dimension_is_copied_to_each_actual_new_column(self):
        w=Workbook();s=w.active
        s.column_dimensions['C']=ColumnDimension(s,index='C',min=3,max=8,width=21)
        expand_groups(s,{'first_col':3,'last_col':8},8)
        for n in [9,10]:
            dim=effective_column_dimension(s,n)
            self.assertEqual((dim.min,dim.max,dim.width),(n,n,21))
        s['I8']='原始长说明'*16
        fit_written_text(s,['I8'])
        self.assertNotIn('H',s.column_dimensions)
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'result.xlsx';w.save(p);out=load_workbook(p)
            for letter,n in [('I',9),('J',10)]:
                dim=out.active.column_dimensions[letter]
                self.assertEqual((dim.min,dim.max,dim.width),(n,n,21))
            self.assertTrue(out.active['I8'].alignment.wrap_text)
            self.assertGreater(out.active.row_dimensions[8].height,50)
            out.close()
        w.close()

if __name__=='__main__':unittest.main()
