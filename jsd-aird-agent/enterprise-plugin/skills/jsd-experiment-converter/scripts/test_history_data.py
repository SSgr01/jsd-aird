"""Evidence, dimensional isolation and training-candidate regressions."""
import json
from pathlib import Path
import shutil
import tempfile
import unittest
import zipfile

from openpyxl import Workbook, load_workbook
from history_data import extract_history, batch, typed_value, Compiler, compile_document_facts
from history_sources import HistorySource
from history_training import candidate_rows, export_candidates
from execute_plan import execute, convert_value
from workflow import digest, write_json


class HistoryTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name);self.path=self.root/'原始实验/测试实验/fixture.xlsx';self.path.parent.mkdir(parents=True)
        book=Workbook();s=book.active;s.title='raw'
        for row in [('编号','001','002'),('物料','X','X'),('首次投料 g',2,3),('再次投料 g',1,2),
                    ('耐磨 次',100,200),('负重 kg',1.5,1.5),('温度 ℃',25,120),('未解释观测',0,None)]:
            s.append(row)
        s['D1']='日期';s['E1']='2026-01-01';book.save(self.path);book.close()
        self.plan={'source':self.path.relative_to(self.root).as_posix(),'source_sha256':digest(self.path),
            'records':[{'key':'run','metadata':{'date':'raw!E1'},
                'groups':[{'key':'a','identity':'raw!B1'},{'key':'b','identity':'raw!C1'}],
                'formulaItems':[], 'testResults':[]}], 'structural':[]}
        record=self.plan['records'][0]
        for group,col in [('a','B'),('b','C')]:
            for n in [3,4]:record['formulaItems'].append({'group':group,'material':col+'2',
                'amount':{'ref':'raw!'+col+str(n),'parse':'number','unit':'g','unitRef':'raw!A'+str(n)},
                'stageKey':'addition-'+str(n),'compositionBasis':'MASS'})
            record['testResults'].append({'group':group,'item':'raw!A5',
                'value':{'ref':'raw!'+col+'5','parse':'number','unit':'次','unitRef':'raw!A5'},
                'semanticKey':'wear','conditions':{
                    'load':{'ref':'raw!'+col+'6','parse':'number','unit':'kg','unitRef':'raw!A6'},
                    'temperature':{'ref':'raw!'+col+'7','parse':'number','unit':'℃','unitRef':'raw!A7'}}})
        for item in record['formulaItems']:
            item['material']='raw!'+item['material']
        self.pp=self.root/'plan.json'

    def run_plan(self):
        write_json(self.pp,self.plan);return extract_history(self.root,self.pp)

    def document(self,result):
        return json.loads((self.root/result['output']/'history.json').read_text(encoding='utf-8'))

    def test_multiple_groups_staged_materials_and_conditions_do_not_mix(self):
        result=self.run_plan();doc=self.document(result);r=doc['experiments'][0]
        self.assertEqual(len(r['formulaItems']),4)
        self.assertEqual([v['value'] for v in r['testResults']],[100,200])
        self.assertEqual([v['conditions']['temperature']['value'] for v in r['testResults']],[25,120])
        self.assertNotEqual(r['testResults'][0]['sourceContextKey'],r['testResults'][1]['sourceContextKey'])
        self.assertEqual([g['sourceIdentity'] for g in r['sourceGroups']],['001','002'])
        self.assertTrue(any(x['rawValue']==0 for x in doc['unplaced']))
        self.assertEqual(result['status'],'PARTIAL')
        self.assertTrue(extract_history(self.root,self.pp)['cache_hit'])

    def test_cross_scope_metadata_and_no_fabricated_source_values(self):
        self.plan['records'][0]['metadata']['author']={'literal':'made up'}
        with self.assertRaisesRegex(ValueError,'引用来源'):self.run_plan()
        self.assertFalse((self.root/'结构化结果').exists())

    def test_no_fake_blank_amount_coordinates(self):
        self.plan['records'][0]['formulaItems'][0]['amount']['ref']='raw!Z9999'
        with self.assertRaisesRegex(ValueError,'真实非空来源'):self.run_plan()

    def test_source_fingerprint_changes_and_tampered_output_are_rejected(self):
        result=self.run_plan();out=self.root/result['output']/'history.json';out.write_text('{}',encoding='utf-8')
        with self.assertRaisesRegex(ValueError,'不覆盖'):extract_history(self.root,self.pp)
        self.plan['source_sha256']='0'*64;write_json(self.pp,self.plan)
        with self.assertRaisesRegex(ValueError,'指纹已变'):extract_history(self.root,self.pp)

    def test_missing_formula_cache_kept_as_issue_not_number(self):
        b=load_workbook(self.path);b.active['B5']='=2+2';b.save(self.path);b.close()
        self.plan['source_sha256']=digest(self.path);result=self.run_plan();doc=self.document(result)
        self.assertEqual(doc['experiments'][0]['testResults'][0]['normalized']['kind'],'INVALID')
        self.assertTrue(any('缓存' in i['reason'] for i in doc['issues']))

    def test_bounds_intervals_slashes_and_percentages_are_distinct(self):
        self.assertEqual(typed_value('0',{'parse':'number'})['value'],0)
        self.assertEqual(typed_value('/',{'parse':'number'})['kind'],'NOT_USED')
        self.assertEqual(typed_value(None,{'parse':'number'})['kind'],'MISSING')
        self.assertEqual(typed_value('1-2',{'parse':'interval'})['upper'],2)
        self.assertEqual(typed_value('≥42%',{'parse':'bound','unit':'%'})['limit'],42)
        self.assertEqual(typed_value(.2,{'parse':'percent_points'},'0.0%')['value'],20)
        with self.assertRaises(ValueError):typed_value(.2,{'parse':'percent_points'})
        with self.assertRaises(ValueError):typed_value('0..1',{'parse':'number'})

    def test_numeric_observations_cannot_be_hidden_as_labels(self):
        self.plan['structural']=[{'ref':'raw!B8','reason':'hide measurement'}]
        with self.assertRaisesRegex(ValueError,'结构标签'):self.run_plan()

    def template_diagnostic(self,change=None):
        from template_ops import cache_totals
        b=load_workbook(self.path);s=b.active;s['X20']='=SUM(X6:X19)'
        if change:change(s)
        b.save(self.path);b.close()
        cache_totals(self.path,{'xl/worksheets/sheet1.xml':{'X20':0}})
        self.plan['source_sha256']=digest(self.path)
        self.plan['documentFacts']=[{'role':'TEMPLATE_CALCULATION','reason':'unused template column',
            'field':'raw!X20','proof':{'identityRef':'raw!X4','inputRange':'X6:X19','observationRange':'X23:X39'}}]

    def test_empty_template_zero_formula_is_preserved_outside_experiment_facts(self):
        self.template_diagnostic();d=self.document(self.run_plan())
        self.assertEqual(d['documentFacts'][0]['field']['rawValue'],0)
        self.assertEqual(d['documentFacts'][0]['field']['sourceRefs'][0]['formula'],'=SUM(X6:X19)')
        self.assertFalse(any(x['sourceRefs'][0]['anchor']=='raw!X20' for x in d['unplaced']))
        self.assertEqual(sum(len(r['testResults']) for r in d['experiments']),2)

    def test_real_input_or_observation_cannot_be_hidden_as_unused_template(self):
        for cell,val in [('X4',7),('X6',7),('X23',7),('X6',0),('X23',0)]:
            with self.subTest(cell=cell,value=val):
                self.template_diagnostic(lambda s:s.__setitem__(cell,val))
                with self.assertRaises(ValueError):self.run_plan()
                b=load_workbook(self.path);b.active[cell]=None;b.save(self.path);b.close()

    def test_real_zero_constant_cannot_be_hidden_as_template_calculation(self):
        self.template_diagnostic()
        b=load_workbook(self.path);b.active['X20']=0;b.save(self.path);b.close()
        self.plan['source_sha256']=digest(self.path)
        with self.assertRaisesRegex(ValueError,'公式'):self.run_plan()

    def test_summary_record_is_not_an_independent_training_candidate(self):
        self.plan['records'][0]['recordRole']='SUMMARY'
        d=self.document(self.run_plan())
        task={'targetSemanticKey':'wear','targetUnit':'次','includeFormulas':False,'requiredConditions':['load']}
        rows=candidate_rows([d],task)
        self.assertEqual(rows['candidateRows'],[])
        self.assertTrue(all(any('汇总' in s for s in r['reasons']) for r in rows['excludedRows']))

    def test_document_notes_keep_text_but_reject_numeric_measurements(self):
        self.plan['documentFacts']=[{'role':'SOURCE_NOTE','reason':'source note','field':'raw!B5'}]
        with self.assertRaisesRegex(ValueError,'数字或公式'):self.run_plan()

    def test_unquantified_recipe_label_blocks_formula_candidates_but_does_not_invent_zero(self):
        b=load_workbook(self.path);b.active['X6']='unquantified material';b.save(self.path);b.close()
        self.plan['source_sha256']=digest(self.path)
        self.plan['documentFacts']=[{'role':'UNQUANTIFIED_RECIPE_LABEL','reason':'amount not recorded','field':'raw!X6'}]
        d=self.document(self.run_plan())
        self.assertEqual(d['documentFacts'][0]['field']['value'],'unquantified material')
        task={'targetSemanticKey':'wear','targetUnit':'次','requiredConditions':['load'],
              'materialVocabulary':{'X':'X'},'formulaUnit':'g','compositionBasis':'MASS'}
        rows=candidate_rows([d],task)
        self.assertFalse(rows['candidateRows'])
        self.assertTrue(all(any('无用量' in s for s in row['reasons']) for row in rows['excludedRows']))
        task['includeFormulas']=False
        self.assertEqual(len(candidate_rows([d],task)['candidateRows']),2)

    def test_ok_ng_test_points_do_not_become_numeric_lifetime_targets(self):
        d=self.document(self.run_plan())
        for x in d['experiments'][0]['testResults']:x['semanticKey']='wear_observation_cycles'
        task={'targetSemanticKey':'wear_observation_cycles','targetUnit':'次','includeFormulas':False,'requiredConditions':['load']}
        rows=candidate_rows([d],task)
        self.assertFalse(rows['candidateRows'])
        self.assertEqual(len(rows['excludedRows']),2)
        self.assertTrue(all(any('寿命标量' in s for s in row['reasons']) for row in rows['excludedRows']))

    def test_unused_template_error_formula_keeps_its_original_error_and_formula(self):
        import io
        from xml.etree import ElementTree as ET
        self.template_diagnostic()
        payload=io.BytesIO()
        with zipfile.ZipFile(self.path) as original,zipfile.ZipFile(payload,'w',zipfile.ZIP_DEFLATED) as target:
            for entry in original.infolist():
                data=original.read(entry.filename)
                if entry.filename=='xl/worksheets/sheet1.xml':
                    xml=ET.fromstring(data);ns={'s':'http://schemas.openxmlformats.org/spreadsheetml/2006/main'}
                    c=xml.find(".//s:c[@r='X20']",ns);c.set('t','e');c.find('s:v',ns).text='#DIV/0!'
                    data=ET.tostring(xml,encoding='utf-8')
                target.writestr(entry,data)
        self.path.write_bytes(payload.getvalue());self.plan['source_sha256']=digest(self.path)
        d=self.document(self.run_plan())
        f=d['documentFacts'][0]['field']
        self.assertEqual(f['rawValue'],'#DIV/0!')
        self.assertEqual(f['sourceRefs'][0]['formula'],'=SUM(X6:X19)')

    def test_unit_cannot_be_invented(self):
        self.plan['records'][0]['testResults'][0]['value']['unit']='mN/m'
        with self.assertRaisesRegex(ValueError,'单位未见'):self.run_plan()

    def test_batch_failure_does_not_stop_next_item(self):
        write_json(self.pp,self.plan)
        write_json(self.root/'batch.json',{'items':[{'plan':'missing.json'},{'plan':'plan.json'}]})
        r=batch(self.root,self.root/'batch.json')
        self.assertEqual(r['counts']['BLOCKED'],1);self.assertEqual(r['counts']['PARTIAL'],1)

    def test_archive_only_is_not_an_experiment(self):
        self.plan['records']=[];self.plan['archiveReason']='仅保留方法资料来源'
        result=self.run_plan();self.assertEqual(result['status'],'ARCHIVE_ONLY')
        self.assertEqual(self.document(result)['experiments'],[])

    def test_sparse_far_away_cell_does_not_expand_empty_rectangle(self):
        book=load_workbook(self.path);book.active['XFD1048576']='尾部原始注记';book.save(self.path);book.close()
        self.plan['source_sha256']=digest(self.path)
        result=self.run_plan();self.assertTrue(any(x['rawValue']=='尾部原始注记' for x in self.document(result)['unplaced']))

    def test_docx_merged_alias_keeps_one_physical_fact_and_cross_table_scope(self):
        p=self.root/'merged.docx'
        with zipfile.ZipFile(p,'w') as z:
            z.writestr('word/document.xml','''<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>
              <w:tbl><w:tr><w:tc><w:tcPr><w:gridSpan w:val="2"/></w:tcPr><w:p><w:r><w:t>原实验题目</w:t></w:r></w:p></w:tc></w:tr></w:tbl>
              <w:tbl><w:tr><w:tc><w:p><w:r><w:t>39.760</w:t></w:r></w:p></w:tc></w:tr></w:tbl>
              </w:body></w:document>''')
        src=HistorySource(self.root,'merged.docx')
        try:
            self.assertTrue(src.has('table-1!B1'));self.assertEqual(len(src.cells),2)
            self.assertEqual(src.read('table-1!B1')[1]['anchor'],'table-1!A1')
            self.assertEqual(src.read('table-2!A1')[0],'39.760')
        finally:src.close()

    def test_image_evidence_requires_hash_bbox_and_review(self):
        # The image payload is not interpreted by the deterministic reader.
        p=self.root/'image.docx';image=b'fixture-only-image-payload'
        with zipfile.ZipFile(p,'w') as z:
            z.writestr('word/document.xml','<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p/></w:body></w:document>')
            z.writestr('word/media/p.png',image)
        import hashlib
        ref={'image':'word/media/p.png','sha256':hashlib.sha256(image).hexdigest(),'bbox':[0,0,1,1],'text':'100'}
        src=HistorySource(self.root,'image.docx')
        try:
            raw,evidence=src.read(ref);self.assertEqual(raw,'100');self.assertTrue(evidence['requiresSourceReview'])
            with self.assertRaisesRegex(ValueError,'bbox'):src.read({**ref,'bbox':[1,0,0,1]})
            with self.assertRaisesRegex(ValueError,'指纹'):src.read({**ref,'sha256':'0'*64})
        finally:src.close()

    def test_task_candidates_respect_conditions_and_retain_split_group(self):
        result=self.run_plan();doc=self.document(result)
        task={'targetSemanticKey':'wear','targetUnit':'次','requiredConditions':['load','temperature'],
            'conditionEquals':{'temperature':{'value':25,'unit':'℃'}},
            'materialVocabulary':{'X':'X'},'formulaUnit':'g','compositionBasis':'MASS'}
        selected=candidate_rows([doc],task)
        self.assertEqual(len(selected['candidateRows']),1)
        self.assertEqual(len(selected['excludedRows']),1)
        self.assertEqual(selected['approval'],'NOT_GRANTED')
        self.assertEqual(len(selected['candidateRows'][0]['features']),4)
        write_json(self.root/'task.json',task)
        export_candidates(self.root,self.root/'task.json',[result['output']+'/history.json'],'候选集')
        b=load_workbook(self.path);b.active['B5']=999;b.save(self.path);b.close()
        with self.assertRaisesRegex(ValueError,'来源已变化'):
            export_candidates(self.root,self.root/'task.json',[result['output']+'/history.json'],'候选集2')

    def test_interval_target_not_averaged_and_missing_condition_excluded(self):
        doc=self.document(self.run_plan());observation=doc['experiments'][0]['testResults'][0]
        observation['normalized']={'kind':'INTERVAL','value':None,'lower':100,'upper':200};observation['value']=None
        r=candidate_rows([doc],{'targetSemanticKey':'wear','targetUnit':'次','includeFormulas':False,'requiredConditions':['filmThickness']})
        self.assertEqual(len(r['candidateRows']),0)
        self.assertTrue(any('标量' in s for s in r['excludedRows'][0]['reasons']))

    def test_unknown_formula_units_stay_out_even_if_task_also_has_empty_unit(self):
        doc=self.document(self.run_plan())
        for item in doc['experiments'][0]['formulaItems']:item['unit']=''
        task={'targetSemanticKey':'wear','targetUnit':'次','materialVocabulary':{'X':'X'},
              'formulaUnit':'','compositionBasis':'MASS'}
        result=candidate_rows([doc],task)
        self.assertFalse(result['candidateRows'])
        self.assertTrue(any('单位缺失' in s for s in result['excludedRows'][0]['reasons']))

    def test_total_conflict_excludes_only_the_affected_group(self):
        book=load_workbook(self.path);book.active['D4']=99;book.save(self.path);book.close()
        self.plan['source_sha256']=digest(self.path)
        self.plan['records'][0]['totals']=[{'group':'a','value':{'ref':'raw!D4','parse':'number','unit':'g','unitRef':'raw!A3'}}]
        doc=self.document(self.run_plan());self.assertEqual(doc['issues'][0]['code'],'SOURCE_TOTAL_CONFLICT')
        result=candidate_rows([doc],{'targetSemanticKey':'wear','targetUnit':'次','requiredConditions':['temperature'],
            'materialVocabulary':{'X':'X'},'formulaUnit':'g','compositionBasis':'MASS'})
        self.assertEqual(len(result['candidateRows']),1)
        self.assertEqual(result['candidateRows'][0]['target'],200)

    def test_condition_units_do_not_mix_in_one_candidate_feature(self):
        doc=self.document(self.run_plan())
        doc['experiments'][0]['testResults'][1]['conditions']['load']['unit']='g'
        result=candidate_rows([doc],{'targetSemanticKey':'wear','targetUnit':'次','requiredConditions':['load'],
            'includeFormulas':False})
        self.assertFalse(result['candidateRows'])
        self.assertTrue(all(any('单位混用' in reason for reason in row['reasons']) for row in result['excludedRows']))


class TemplateRecipeTests(unittest.TestCase):
    def test_numeric_text_is_explicit_and_rejects_units_or_typos(self):
        self.assertEqual(convert_value('39.760','numeric_text'),39.76)
        self.assertIsNone(convert_value('  ','numeric_text'))
        self.assertEqual(convert_value('/','numeric_text'),'/')
        for value in ['0..1','3%','10g',True,'nan']:
            with self.assertRaises(ValueError):convert_value(value,'numeric_text')

    def test_per_group_material_pairs_and_repeated_sample_identity(self):
        with tempfile.TemporaryDirectory() as td:
            root=Path(td);(root/'标准模板').mkdir();(root/'原始实验/测试实验').mkdir(parents=True)
            shutil.copy2(Path(__file__).parent.parent/'templates/test.xlsx',root/'标准模板/test.xlsx')
            b=Workbook();s=b.active;s.title='raw'
            for cell,value in {'A1':'E1','C1':'E2','A2':'M-A','B2':'39.760','C2':'M-B','D2':'2.0','A3':'sample','C3':'sample'}.items():s[cell]=value
            source=root/'原始实验/测试实验/f.xlsx';b.save(source);b.close()
            plan={'version':1,'source':source.relative_to(root).as_posix(),'kind':'test','source_sha256':digest(source),
                'template_sha256':digest(root/'标准模板/test.xlsx'),'sheets':[{'source_sheet':'raw',
                'groups':['A1','C1'],'sample_groups':['A3','C3'],
                'recipe':{'entries':[[{'name':'A2','amount':'B2'}],[{'name':'C2','amount':'D2'}]],'amount_transform':'numeric_text'},
                'writes':[],'structural':[],'unplaced':[]}],'skipped':[]}
            pp=root/'plan.json';write_json(pp,plan);result=execute(root,pp)
            output=load_workbook(root/result['output'],data_only=True)
            try:
                w=output.active;self.assertEqual(w['B52'].value,'M-A');self.assertEqual(w['B53'].value,'M-B')
                self.assertEqual(w['C52'].value,39.76);self.assertIsNone(w['D52'].value)
                self.assertEqual(w['D53'].value,2);self.assertEqual(w['C7'].value,w['D7'].value)
                self.assertEqual(w['C60'].value,39.76)
            finally:output.close()


if __name__=='__main__':unittest.main()
