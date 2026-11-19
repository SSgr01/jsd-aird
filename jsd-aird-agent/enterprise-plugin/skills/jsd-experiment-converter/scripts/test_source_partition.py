"""Single-group partitions preserve repeat additions and whole-source coverage."""
import json
import shutil
import tempfile
import unittest
from copy import deepcopy
from pathlib import Path

from openpyxl import Workbook, load_workbook
from execute_plan import execute
from workflow import digest, write_json


class SourcePartitionTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root/'标准模板').mkdir()
        self.template = self.root/'标准模板/synthesis.xlsx'
        shutil.copy2(Path(__file__).parent.parent/'templates/synthesis.xlsx', self.template)
        self.source = self.root/'原始实验/合成实验/repeated.xlsx'
        self.source.parent.mkdir(parents=True)
        w = Workbook(); s = w.active; s.title = 'source'
        for coord, value in {'A1':'title', 'C1':'E1', 'D1':'E2',
                             'B2':'S-5','B3':'S-5','C2':10,'C3':20,
                             'D2':30,'D3':40,'A4':'original observation'}.items():
            s[coord] = value
        w.save(self.source); w.close()
        specs = []
        for col in 'CD':
            cells = ['A1','B2','B3',f'{col}1',f'{col}2',f'{col}3']
            specs.append({'source_sheet':'source',
                          'source_partition':{'id':col,'cells':cells},
                          'groups':[f'{col}1'],
                          'recipe':{'names':['B2','B3'],'amounts':[[f'{col}2'],[f'{col}3']],
                                    'amount_transform':'customer_charge_g', 'preserve_repeated_lines':True},
                          'writes':[{'src':'A1','dst':'B1'}], 'unplaced':[]})
        specs[0]['source_partition']['cells'].append('A4')
        specs[0]['unplaced'].append({'src':'A4','reason':'preserved original observation'})
        self.plan = {'version':1,'source':self.source.relative_to(self.root).as_posix(),
                     'kind':'synthesis','source_sha256':digest(self.source),
                     'template_sha256':digest(self.template),'sheets':specs,'skipped':[]}

    def run_plan(self):
        p=self.root/'plan.json';write_json(p,self.plan);return execute(self.root,p)

    def test_repeat_lines_and_group_values_remain_separate(self):
        result=self.run_plan(); w=load_workbook(self.root/result['output'],data_only=True)
        self.assertEqual(len(w.worksheets),2)
        for s, amounts in zip(w,[(10,20,30),(30,40,70)]):
            self.assertEqual([s['B10'].value,s['B11'].value],['S-5','S-5'])
            self.assertEqual([s['C10'].value,s['C11'].value,s['C24'].value],list(amounts))
        w.close()
        report=json.loads((self.root/result['verification']).read_text('utf8'))
        self.assertEqual([s['source_partition'] for s in report['sheets']],['C','D'])
        self.assertEqual(len(report['unplaced']),1)

    def test_omitted_source_fact_fails_whole_source_union(self):
        spec=self.plan['sheets'][0];spec['source_partition']['cells'].remove('A4');spec['unplaced']=[]
        with self.assertRaisesRegex(ValueError,'所有分区合并后'):
            self.run_plan()

    def test_cell_amount_transform_keeps_raw_layer_and_recomputes_total(self):
        w=load_workbook(self.source);w.active['C2']='下层10';w.save(self.source);w.close()
        self.plan['source_sha256']=digest(self.source)
        self.plan['sheets'][0]['recipe']['amount_transforms']={'C2':{'pattern':'^下层([0-9]+)$','numeric':True}}
        self.plan['sheets'][0]['group_column_width']=38
        result=self.run_plan();w=load_workbook(self.root/result['output'],data_only=True)
        self.assertEqual(w.active['C10'].value,10)
        self.assertEqual(w.active['C24'].value,30)
        self.assertEqual(w.active.column_dimensions['C'].width,38);w.close()
        report=json.loads((self.root/result['verification']).read_text('utf8'))
        self.assertEqual(report['recipe_conversions'][0]['raw_value'],'下层10')

    def test_shared_repeat_rows_keep_normal_multi_group_layout(self):
        spec={'source_sheet':'source','groups':['C1','D1'],
              'recipe':{'names':['B2','B3'],'amounts':[['C2','D2'],['C3','D3']],
                        'preserve_repeated_lines':True},
              'writes':[{'src':'A1','dst':'B1'}],
              'unplaced':[{'src':'A4','reason':'source observation'}]}
        self.plan['sheets']=[spec]
        result=self.run_plan();w=load_workbook(self.root/result['output'],data_only=True)
        self.assertEqual(len(w.worksheets),1)
        self.assertEqual([w.active['B10'].value,w.active['B11'].value],['S-5','S-5'])
        self.assertEqual([w.active['C10'].value,w.active['C11'].value,w.active['C24'].value],[10,20,30])
        self.assertEqual([w.active['D10'].value,w.active['D11'].value,w.active['D24'].value],[30,40,70])
        w.close()

    def test_reading_other_group_outside_partition_fails(self):
        self.plan['sheets'][0]['writes'].append({'src':'D2','dst':'C46'})
        with self.assertRaisesRegex(ValueError,'引用超出显式源分区'):
            self.run_plan()

    def test_material_and_charge_in_one_source_cell_are_explicitly_extracted(self):
        w=load_workbook(self.source);s=w.active;s['B2']='XZ545    12';s['B3']='分散树脂'
        w.save(self.source);w.close();self.plan['source_sha256']=digest(self.source)
        self.plan['sheets']=[{'source_sheet':'source','groups':['C1'],
            'recipe':{'entries':[[{'name':'B2','amount':'B2'},{'name':'B3','amount':'C3'}]],
                'name_transforms':{'B2':{'pattern':r'^(XZ545)\s+12$'}},
                'amount_transforms':{'B2':{'pattern':r'^XZ545\s+(12)$','numeric':True}}},
            'writes':[{'src':'A1','dst':'B1'}],
            'unplaced':[{'src':c,'reason':'other original group/observation'} for c in ['C2','D1','D2','D3','A4']]}]
        result=self.run_plan();w=load_workbook(self.root/result['output'],data_only=True)
        self.assertEqual([w.active['B10'].value,w.active['C10'].value,w.active['C24'].value],['XZ545',12,32]);w.close()
        report=json.loads((self.root/result['verification']).read_text('utf8'))
        self.assertEqual(report['recipe_name_conversions'][0]['raw_value'],'XZ545    12')
        self.assertEqual(report['recipe_conversions'][0]['raw_value'],'XZ545    12')

    def test_unused_material_extraction_reference_is_rejected(self):
        self.plan['sheets'][0]['recipe']['name_transforms']={'A4':{'pattern':'(observation)'}}
        with self.assertRaisesRegex(ValueError,'真实物料来源坐标'):
            self.run_plan()

    def test_different_material_columns_preserve_numeric_codes(self):
        w=load_workbook(self.source);s=w.active
        s['B2']=383;s['B3']=137;s['E2']='S-5';s['E3']=383
        w.save(self.source);w.close();self.plan['source_sha256']=digest(self.source)
        self.plan['sheets']=[{'source_sheet':'source','groups':['C1','D1'],
            'recipe':{'entries':[[{'name':'B2','amount':'C2'},{'name':'B3','amount':'C3'}],
                                 [{'name':'E2','amount':'D2'},{'name':'E3','amount':'D3'}]]},
            'writes':[{'src':'A1','dst':'B1'}],
            'unplaced':[{'src':'A4','reason':'original observation'}]}]
        result=self.run_plan();w=load_workbook(self.root/result['output'],data_only=True)
        self.assertEqual(len(w.worksheets),1)
        self.assertEqual([w.active[f'B{r}'].value for r in [10,11,12]],[383,137,'S-5'])
        self.assertEqual([w.active['C10'].value,w.active['D10'].value,w.active['D12'].value],[10,40,30])
        self.assertIsNone(w.active['D11'].value)
        w.close()

    def test_plan_groups_do_not_create_experiment_ids(self):
        self.plan['sheets']=[{'source_sheet':'source','groups':['C1','D1'],'group_identity_role':'PLAN',
            'recipe':{'names':['B2','B3'],'amounts':[['C2','D2'],['C3','D3']],'preserve_repeated_lines':True},
            'writes':[{'srcs':['A1','A4'],'join':'\n','dst':'B53'}]}]
        result=self.run_plan();w=load_workbook(self.root/result['output'],data_only=True)
        self.assertEqual(w.active['C8'].value,'E1');self.assertEqual(w.active['D8'].value,'E2')
        self.assertIsNone(w.active['C9'].value)
        self.assertEqual(w.active['B53'].value,'title\noriginal observation');w.close()

    def test_sparse_entries_do_not_turn_absent_material_into_zero_charge(self):
        w=load_workbook(self.source);s=w.active;s['B3']='different material';s['C2']=None
        w.save(self.source);w.close();self.plan['source_sha256']=digest(self.source)
        self.plan['sheets']=[{'source_sheet':'source','groups':['C1','D1'],
            'recipe':{'entries':[[{'name':'B2','amount':'C2'}],[{'name':'B3','amount':'D3'}]],
                      'amount_transform':'customer_charge_g'},
            'writes':[{'src':'A1','dst':'B1'}],
            'unplaced':[{'src':c,'reason':'other source fact'} for c in ['C3','D2','A4']]}]
        result=self.run_plan();w=load_workbook(self.root/result['output'],data_only=True);s=w.active
        self.assertEqual(s['C10'].value,0)
        self.assertIsNone(s['D10'].value)
        self.assertIsNone(s['C11'].value)
        self.assertEqual(s['D11'].value,40);w.close()

    def test_undeclared_duplicate_and_duplicate_partition_id_fail(self):
        self.plan['sheets'][1]['source_partition']['id']='C'
        with self.assertRaisesRegex(ValueError,'标识重复'):
            self.run_plan()
        self.plan['sheets'][1].pop('source_partition')
        with self.assertRaisesRegex(ValueError,'必须逐组显式分区'):
            self.run_plan()

    def sample_condition_plan(self):
        self.template=self.root/'标准模板/test.xlsx'
        shutil.copy2(Path(__file__).parent.parent/'templates/test.xlsx',self.template)
        target=self.root/'原始实验/测试实验/repeated.xlsx';target.parent.mkdir(parents=True)
        self.source.replace(target);self.source=target
        self.plan.update(kind='test',source=target.relative_to(self.root).as_posix(),template_sha256=digest(self.template))
        w=load_workbook(self.source);s=w.active;s['D1']='E1';s['C5']='1%';s['D5']='2%'
        w.save(self.source);w.close();self.plan['source_sha256']=digest(self.source)
        self.plan['sheets']=[{'source_sheet':'source','groups':['C1','D1'],
            'group_identity_role':'SAMPLE','group_condition_refs':[['C5'],['D5']],
            'recipe':{'names':['B2','B3'],'amounts':[['C2','D2'],['C3','D3']],
                      'preserve_repeated_lines':True},
            'writes':[{'src':'A1','dst':'B1'},{'src':'C5','dst':'C49'},{'src':'D5','dst':'D49'}],
            'unplaced':[{'src':'A4','reason':'original observation'}]}]

    def test_same_sample_different_conditions_preserves_six_fields(self):
        self.sample_condition_plan();result=self.run_plan()
        w=load_workbook(self.root/result['output'],data_only=True);s=w.active
        self.assertEqual([s['C49'].value,s['D49'].value],['1%','2%'])
        self.assertEqual([s['C52'].value,s['D52'].value],[10,30])
        self.assertEqual([s['C53'].value,s['D53'].value],[20,40]);w.close()

    def test_same_sample_conditions_must_be_visible_in_output(self):
        self.sample_condition_plan();self.plan['sheets'][0]['writes'].pop()
        with self.assertRaisesRegex(ValueError,'条件必须同时写入标准页'):
            self.run_plan()

    def test_same_sample_same_condition_is_not_distinct(self):
        self.sample_condition_plan();self.plan['sheets'][0]['group_condition_refs']=[['C5'],['C5']]
        with self.assertRaisesRegex(ValueError,'相同样品和条件仍重复'):
            self.run_plan()

    def test_percentage_condition_retains_display_and_requires_source_format(self):
        self.sample_condition_plan()
        w=load_workbook(self.source);s=w.active
        for c,v in [('C5',.0001),('D5',.002)]:s[c]=v;s[c].number_format='0.00%'
        w.save(self.source);w.close();self.plan['source_sha256']=digest(self.source)
        spec=self.plan['sheets'][0]
        for write in spec['writes'][1:]:write['transform']='percent_to_text'
        result=self.run_plan();w=load_workbook(self.root/result['output'])
        self.assertEqual([w.active['C49'].value,w.active['D49'].value],['0.01%','0.2%']);w.close()
        w=load_workbook(self.source);w.active['D5'].number_format='General';w.save(self.source);w.close()
        self.plan['source_sha256']=digest(self.source)
        p=self.root/'negative-plan.json';write_json(p,self.plan)
        with self.assertRaisesRegex(ValueError,'源百分比格式证明'):execute(self.root,p,self.root/'_work/negative.xlsx')

    def test_concentration_plan_identity_does_not_become_experiment_number(self):
        self.sample_condition_plan();w=load_workbook(self.source);s=w.active
        for c,v in [('C5',0),('D5',.01)]:s[c]=v;s[c].number_format='0.00%'
        w.save(self.source);w.close();self.plan['source_sha256']=digest(self.source)
        spec=self.plan['sheets'][0];spec['groups']=['C5','D5'];spec['group_identity_role']='PLAN';spec['group_transform']='percent_to_text'
        spec.pop('group_condition_refs');spec['structural']=[{'src':c,'reason':'source sample name'} for c in ['C1','D1']]
        for write in spec['writes'][1:]:write['transform']='percent_to_text'
        result=self.run_plan();w=load_workbook(self.root/result['output']);s=w.active
        self.assertEqual([s['C7'].value,s['D7'].value],['0%','1%'])
        self.assertIsNone(s['C50'].value);w.close()

    def test_explicit_experiment_block_keeps_parallel_groups_together(self):
        self.plan['sheets']=[{'source_sheet':'source','groups':['C1','D1'],
            'source_partition':{'id':'block1','mode':'EXPERIMENT_BLOCK',
                'cells':['A1','B2','B3','C1','D1','C2','D2','C3','D3','A4']},
            'recipe':{'names':['B2','B3'],'amounts':[['C2','D2'],['C3','D3']],
                      'preserve_repeated_lines':True},'writes':[{'src':'A1','dst':'B1'}],
            'unplaced':[{'src':'A4','reason':'source observation'}]}]
        result=self.run_plan();w=load_workbook(self.root/result['output'],data_only=True)
        self.assertEqual(len(w.worksheets),1)
        self.assertEqual([w.active['C24'].value,w.active['D24'].value],[30,70]);w.close()

    def context_plan(self):
        w=load_workbook(self.source);s=w.create_sheet('metadata');s['A1']='actual title';s['A2']='unused original note'
        w.save(self.source);w.close();self.plan['source_sha256']=digest(self.source)
        self.plan['context_sheets']=[{'source_sheet':'metadata','unplaced':[{'src':'A2','reason':'retained source note'}]}]
        for spec in self.plan['sheets']:
            spec['writes']=[{'src':'metadata!A1','dst':'B1'}]
            spec['structural']=[{'src':'A1','reason':'original section title'}]
            spec['source_partition']['cells'].remove('A1')
            spec.pop('source_partition')
        self.plan['sheets']=self.plan['sheets'][:1]
        self.plan['sheets'][0]['groups']=['C1','D1']
        self.plan['sheets'][0]['recipe']['amounts']=[['C2','D2'],['C3','D3']]

    def test_context_metadata_is_read_from_original_and_fully_accounted(self):
        self.context_plan();result=self.run_plan();w=load_workbook(self.root/result['output'])
        self.assertEqual(w.active['B1'].value,'actual title');self.assertEqual(len(w.worksheets),1);w.close()
        report=json.loads((self.root/result['verification']).read_text('utf8'))
        self.assertEqual(report['coverage'][-1]['accounted'],2)
        self.assertEqual(report['unplaced'][-1]['value'],'unused original note')

    def test_context_missing_original_note_is_rejected(self):
        self.context_plan();self.plan['context_sheets'][0]['unplaced']=[]
        with self.assertRaisesRegex(ValueError,'非空单元格未处置'):
            self.run_plan()


if __name__=='__main__':unittest.main()
