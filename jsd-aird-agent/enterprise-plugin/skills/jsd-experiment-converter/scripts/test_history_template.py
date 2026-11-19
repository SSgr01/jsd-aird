"""Optional Excel export must retain facts and refuse lossy projections."""
import json
from pathlib import Path
import shutil
import tempfile
import unittest

from openpyxl import Workbook,load_workbook
from openpyxl.utils import get_column_letter
from history_data import extract_history
from history_template import project,project_batch
from workflow import digest,write_json


class ProjectionTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup)
        self.root=Path(self.tmp.name);(self.root/'标准模板').mkdir()
        for kind in ['test','synthesis']:
            shutil.copy2(Path(__file__).parent.parent/'templates'/(kind+'.xlsx'),self.root/'标准模板'/(kind+'.xlsx'))

    def fixture(self,groups=2,materials=2,kind='test'):
        self.source=self.root/'原始实验'/('测试实验' if kind=='test' else '合成实验')/'fixture.xlsx'
        self.source.parent.mkdir(parents=True,exist_ok=True)
        book=Workbook();s=book.active;s.title='raw'
        for row,label in [(1,'实验题目'),(2,'实验人'),(3,'日期'),(4,'组号'),(7,'实测固含'),(8,'用量g')]:s.cell(row,1,label)
        s['B1']='synthetic acceptance fixture';s['B2']='fixture operator';s['B3']='2026-09-20'
        s['A5']='水接触角（°）' if kind=='test' else '折光率'
        if kind=='test':s['A6']='基材'
        record={'key':'study','experimentType':'TEST' if kind=='test' else 'SYNTHESIS',
            'metadata':{'title':'raw!B1','operator':'raw!B2','date':'raw!B3'},
            'groups':[],'formulaItems':[],'processSteps':[],'testResults':[]}
        for n in range(materials):s.cell(10+n,1,'M'+str(n+1))
        for i in range(groups):
            c=get_column_letter(i+2);key='g'+str(i)
            s[c+'4']='G'+str(i+1);s[c+'5']=100+i if kind=='test' else 1.4+i*.01
            s[c+'7']=.2+i*.01;s[c+'7'].number_format='0.00%'
            if kind=='test':s[c+'6']='PET'
            record['groups'].append({'key':key,'identity':'raw!'+c+'4','identityType':'EXPERIMENT'})
            for n in range(materials):
                s.cell(10+n,i+2,n+1+i)
                record['formulaItems'].append({'group':key,'material':'raw!A'+str(10+n),
                    'amount':{'ref':'raw!'+c+str(10+n),'parse':'number','unit':'g','unitRef':'raw!A8'},'compositionBasis':'MASS'})
            conditions={'substrate':'raw!'+c+'6'} if kind=='test' else {}
            record['testResults'].extend([
                {'group':key,'item':'raw!A5','semanticKey':'water_contact_angle' if kind=='test' else 'refractive_index',
                 'value':{'ref':'raw!'+c+'5','parse':'number',**({'unit':'°','unitRef':'raw!A5'} if kind=='test' else {})},'conditions':conditions},
                {'group':key,'item':'raw!A7','semanticKey':'measured_solids','value':{'ref':'raw!'+c+'7','parse':'percent_points','unit':'%'},'conditions':conditions}])
        book.save(self.source);book.close()
        self.plan={'source':self.source.relative_to(self.root).as_posix(),'source_sha256':digest(self.source),
            'records':[record],'structural':[{'ref':'raw!A'+str(n),'reason':'字段标签'} for n in [1,2,3,4]+([6] if kind=='test' else [])],
            'templateAssessment':{'decision':'POSSIBLE','reason':'synthetic fixture has complete representable observations and explicit units'}}
        self.pp=self.root/'plan.json'
        return record

    def extract(self):
        self.plan['source_sha256']=digest(self.source);write_json(self.pp,self.plan)
        result=extract_history(self.root,self.pp)
        return result['output']+'/history.json'

    def edit_source(self,values):
        b=load_workbook(self.source)
        for cell,value in values.items():b.active[cell]=value
        b.save(self.source);b.close()

    def test_projects_from_current_source_preserving_template_units_and_ids(self):
        self.fixture();history=self.extract();result=project(self.root,history)
        self.assertEqual(result['status'],'PROJECTED');self.assertEqual(result['approval'],'NOT_GRANTED')
        out=load_workbook(self.root/result['output'],data_only=True)
        try:
            s=out.active;self.assertEqual(s['C104'].value,100);self.assertEqual(s['D104'].value,101)
            self.assertEqual(s['C22'].value,.2);self.assertEqual(s['C60'].value,3);self.assertEqual(s['D60'].value,5)
            self.assertEqual(s['C8'].value,'PET');self.assertEqual(s['C51'].value,'G1')
            self.assertEqual(s['B104'].value,'水接触角（°）')
        finally:out.close()
        self.assertTrue(project(self.root,history)['cache_hit'])

    def test_two_axis_expansion_uses_blank_template_and_moves_result_rows(self):
        self.fixture(groups=8,materials=10);result=project(self.root,self.extract())
        self.assertEqual(result['status'],'PROJECTED')
        out=load_workbook(self.root/result['output'],data_only=True)
        try:
            s=out.active;self.assertEqual(s['J106'].value,107);self.assertEqual(s['C62'].value,55)
            self.assertEqual(s['B61'].value,'M10');self.assertIn('$J$',str(s.print_area))
        finally:out.close()

    def test_synthesis_projection_uses_synthesis_fields(self):
        self.fixture(kind='synthesis');result=project(self.root,self.extract())
        self.assertEqual(result['status'],'PROJECTED')
        b=load_workbook(self.root/result['output'],data_only=True)
        try:
            s=b.active;self.assertEqual(s['C24'].value,3);self.assertEqual(s['C48'].value,1.4)
            self.assertEqual(s['C47'].value,.2 if '%' in s['C47'].number_format else 20)
        finally:b.close()

    def test_source_change_is_rejected_before_any_workbook(self):
        self.fixture();history=self.extract();self.edit_source({'B5':999})
        with self.assertRaisesRegex(ValueError,'指纹'):project(self.root,history)
        self.assertFalse((self.root/'转换结果').exists())

    def test_copied_json_values_cannot_bypass_source_recompilation(self):
        self.fixture();history=self.extract();path=self.root/history;data=json.loads(path.read_text(encoding='utf-8'))
        data['experiments'][0]['testResults'][0]['value']=999;write_json(path,data)
        report=path.parent/'verification.json';r=json.loads(report.read_text(encoding='utf-8'));r['artifacts']['history.json']=digest(path);write_json(report,r)
        with self.assertRaisesRegex(ValueError,'重提取'):project(self.root,history)

    def test_multiple_timepoints_are_skipped_instead_of_collapsed(self):
        record=self.fixture();self.edit_source({'A30':'时间h','B30':.5,'A31':'时间h','B31':1})
        record['testResults'][0]['conditions']['time']={'ref':'raw!B30','parse':'number','unit':'h','unitRef':'raw!A30'}
        extra=json.loads(json.dumps(record['testResults'][0]));extra['conditions']['time']={'ref':'raw!B31','parse':'number','unit':'h','unitRef':'raw!A31'};record['testResults'].append(extra)
        result=project(self.root,self.extract());self.assertEqual(result['status'],'SKIPPED')
        self.assertTrue(any('time' in reason for reason in result['reasons']))
        self.assertFalse((self.root/result['output']).exists())

    def test_staged_repeated_material_is_not_summed(self):
        record=self.fixture();self.edit_source({'A30':'M1','B30':3})
        record['formulaItems'].append({'group':'g0','material':'raw!A30','amount':{'ref':'raw!B30','parse':'number','unit':'g','unitRef':'raw!A8'},'stageKey':'second-addition'})
        result=project(self.root,self.extract());self.assertEqual(result['status'],'SKIPPED')
        self.assertIn('重复投料',result['reasons'][0])

    def test_unplaced_data_and_unknown_unit_are_not_hidden(self):
        record=self.fixture();record['formulaItems'][0]['amount'].pop('unit')
        result=project(self.root,self.extract());self.assertEqual(result['status'],'SKIPPED')
        self.assertTrue(any('单位' in reason for reason in result['reasons']))

    def test_unsuitable_assessment_generates_reason_only_and_no_empty_excel(self):
        self.fixture();self.plan['templateAssessment']={'decision':'NOT_SUITABLE','reason':'explicit unsupported source layout'}
        result=project(self.root,self.extract());self.assertEqual(result['status'],'SKIPPED')
        self.assertFalse((self.root/result['output']).exists());self.assertTrue((self.root/result['output']).with_suffix('.projection.json').exists())

    def test_parallel_observations_are_not_replaced_even_when_equal(self):
        record=self.fixture();record['testResults'].append(json.loads(json.dumps(record['testResults'][0])))
        result=project(self.root,self.extract());self.assertEqual(result['status'],'SKIPPED')
        self.assertTrue(any('多项观测' in r for r in result['reasons']))

    def test_modified_output_is_not_overwritten(self):
        self.fixture();history=self.extract();result=project(self.root,history)
        b=load_workbook(self.root/result['output']);b.active['C104']=999;b.save(self.root/result['output']);b.close()
        with self.assertRaisesRegex(ValueError,'指纹|不覆盖'):project(self.root,history)

    def test_projection_batch_continues_after_an_invalid_item(self):
        self.fixture();history=self.extract()
        path=self.root/'batch.json';write_json(path,{'items':[{'history':'missing/history.json'},{'history':history}]})
        result=project_batch(self.root,path)
        self.assertEqual(result['counts'],{'PROJECTED':1,'SKIPPED':0,'BLOCKED':1})

    def test_explicit_partial_keeps_sample_identity_and_no_invented_recipe(self):
        record=self.fixture(materials=0)
        for g in record['groups']:g['identityType']='SAMPLE'
        self.edit_source({'A30':'extra aging condition','B30':'20 cycles'})
        self.plan['unplaced']=[{'ref':'raw!A30','reason':'source-only condition'},
                               {'ref':'raw!B30','reason':'source-only condition'}]
        result=project(self.root,self.extract(),allow_partial=True)
        self.assertEqual(result['status'],'PARTIAL');self.assertTrue(result['reasons'])
        self.assertIn('history',result)
        w=load_workbook(self.root/result['output'],data_only=True)
        self.assertEqual(w.active['C7'].value,'G1')
        self.assertIsNone(w.active['C51'].value);self.assertIsNone(w.active['C60'].value)
        self.assertEqual(w.active['C104'].value,100);w.close()

    def test_partial_flag_does_not_allow_condition_loss(self):
        record=self.fixture();self.edit_source({'A30':'time h','B30':20})
        record['testResults'][0]['conditions']['time']={'ref':'raw!B30','parse':'number','unit':'h','unitRef':'raw!A30'}
        result=project(self.root,self.extract(),allow_partial=True)
        self.assertEqual(result['status'],'SKIPPED')
        self.assertFalse((self.root/result['output']).exists())

    def test_viscosity_verbatim_preserves_method_notation_without_inventing_unit(self):
        record=self.fixture(materials=0)
        self.plan['structural'].append({'ref':'raw!A8','reason':'empty recipe section label'})
        self.edit_source({'A5':'粘度','B5':'8S/2#','C5':'3040cps'})
        for observation in record['testResults'][::2]:
            observation['semanticKey']='resin_viscosity_verbatim'
            observation['value']={'ref':observation['value']['ref']}
        result=project(self.root,self.extract());self.assertEqual(result['status'],'PROJECTED')
        w=load_workbook(self.root/result['output']);self.assertEqual(w.active['C24'].value,'8S/2#')
        self.assertEqual(w.active['D24'].value,'3040cps');w.close()


if __name__=='__main__':unittest.main()
