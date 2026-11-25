"""Preserve labelled process parameters; never import unknown review series."""
import argparse,json,shutil,tempfile,unittest
from pathlib import Path
from openpyxl import Workbook,load_workbook
from execute_plan import execute
from workflow import digest,write_json
ROOT=None
class ProcessConditionsTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory(dir=ROOT/'_tmp');self.root=Path(self.tmp.name)
        (self.root/'标准模板').mkdir();shutil.copy2(ROOT/'标准模板/synthesis.xlsx',self.root/'标准模板/synthesis.xlsx')
        (self.root/'原始实验/合成实验').mkdir(parents=True)
        self.src=self.root/'原始实验/合成实验/fixture.xlsx';self.pp=self.root/'plan.json'
        w=Workbook();s=w.active;s.title='data'
        for a,v in {'A1':'condition A','D1':'condition B','A2':2,'B2':27.34,'G1':'LED','G2':'光强/mw/cm2','H2':1100,'G3':'能量/mj/cm2','H3':205}.items():s[a]=v
        w.save(self.src);w.close()
        self.spec={'source_sheet':'data','groups':['A1','D1'],'group_identity_role':'PLAN','recipe':{'names':[],'amounts':[]},
            'writes':[{'dst':'C36','process_conditions':[{'operation':'G1','parameters':[{'label':'G2','value':'H2'},{'label':'G3','value':'H3'}]}]}],
            'unplaced':[{'src':a,'reason':'meaning not recorded; customer review attachment only'} for a in ['A2','B2']]}
        self.plan={'version':1,'kind':'synthesis','source':'原始实验/合成实验/fixture.xlsx','source_sha256':digest(self.src),'template_sha256':digest(self.root/'标准模板/synthesis.xlsx'),'sheets':[self.spec],'skipped':[]}
    def tearDown(self):self.tmp.cleanup()
    def run_plan(self):write_json(self.pp,self.plan);return execute(self.root,self.pp)
    def test_labelled_process_stages_preserved_unknown_values_stay_outside(self):
        result=self.run_plan();report=json.loads((self.root/result['verification']).read_text('utf8'))
        self.assertEqual({v['src'] for v in report['unplaced']},{'A2','B2'})
        w=load_workbook(self.root/result['output']);s=w.active
        self.assertEqual(s['C36'].value,'LED；光强/mw/cm2：1100；能量/mj/cm2：205')
        self.assertNotIn(27.34,[c.value for row in s for c in row]);self.assertEqual(s.max_row,54);w.close()
    def test_unlabelled_series_cannot_become_experiment_fields(self):
        self.spec['unlabelled_numeric_series']=[[['A2','B2']],[['A2','B2']]]
        with self.assertRaisesRegex(ValueError,'只能放客户核对附件'):self.run_plan()
    def test_measurement_cannot_be_written_as_process_elsewhere(self):
        self.spec['writes'][0]['dst']='C46'
        with self.assertRaisesRegex(ValueError,'本组方案/工艺简介'):self.run_plan()
    def test_constant_parameter_is_not_a_source_reference(self):
        self.spec['writes'][0]['process_conditions'][0]['parameters'][0]['value']=1100
        with self.assertRaisesRegex(ValueError,'来源坐标'):self.run_plan()
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--root',type=Path,required=True);args,rest=p.parse_known_args();ROOT=args.root.resolve()
    (ROOT/'_tmp').mkdir(exist_ok=True);unittest.main(argv=[__file__,*rest],verbosity=2)
