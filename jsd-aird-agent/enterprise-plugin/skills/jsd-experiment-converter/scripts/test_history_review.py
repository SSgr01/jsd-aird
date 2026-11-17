"""Review gaps preserve source evidence without inventing customer omissions."""
import unittest
import tempfile
from pathlib import Path
from history_review import review_rows, export_review, consolidate_rows
from workflow import digest, write_json


def doc():
    ref={'anchor':'Sheet1!B5','rawValue':1}
    return {'source':{'source':'original.xlsx','source_sha256':'hash'},'experiments':[
        {'recordKey':'r','sourceGroups':[{'sourceGroupKey':'g','sourceIdentity':'a','sourceRefs':[ref]}],
         'formulaItems':[],'testResults':[]}], 'issues':[], 'unplaced':[], 'unreadImages':[]}


class ReviewTests(unittest.TestCase):
    def test_unknown_recipe_unit_basis_identity_and_repeated_additions_remain_review_gaps(self):
        d=doc();r=d['experiments'][0];ref={'anchor':'Sheet1!B5','rawValue':1}
        for n in [1,2]:
            r['formulaItems'].append({'sourceGroupKey':'g','normalized':{'kind':'NUMBER'},'unit':'',
                'compositionBasis':'UNSPECIFIED','materialName':'S-48','materialId':None,
                'stageKey':str(n),'rawValue':n,'sourceRefs':[{**ref,'anchor':'Sheet1!B'+str(n)}]})
        rows=review_rows([d]);codes={x['code'] for x in rows}
        self.assertTrue({'FORMULA_UNIT_UNCONFIRMED','COMPOSITION_BASIS_UNCONFIRMED',
                         'MATERIAL_MASTER_UNMATCHED','REPEATED_ADDITION_REVIEW'}<=codes)
        repeated=next(x for x in rows if x['code']=='REPEATED_ADDITION_REVIEW')
        self.assertEqual([x['value'] for x in repeated['currentValues']],[1,2])
        self.assertTrue(all(x['confirmedValue']=='' and x['approval']=='NOT_GRANTED' for x in rows))

    def test_dimensionless_values_are_not_reported_as_missing_units(self):
        d=doc();r=d['experiments'][0]
        for key in ['pH','refractive_index','water_contact_angle']:
            r['testResults'].append({'sourceGroupKey':'g','normalized':{'kind':'NUMBER'},'semanticKey':key,
                'unit':'','conditions':{},'testItem':key,'rawValue':7,'sourceRefs':[{'anchor':'A1','rawValue':7}]})
        rows=review_rows([d],{'water_contact_angle':['substrate','bakeTime']})
        missing=next(x for x in rows if x['code']=='RESULT_UNIT_UNCONFIRMED')
        self.assertEqual([x['item'] for x in missing['currentValues']],['water_contact_angle'])
        self.assertEqual(sum(x['code'].startswith('TASK_CONDITION_MISSING:') for x in rows),2)

    def test_unplaced_and_images_are_kept_separate_from_customer_missing_information(self):
        d=doc();ref={'anchor':'table-1!B2','rawValue':'unclear','evidenceType':'IMAGE_TRANSCRIPTION'}
        d['experiments'][0]['sourceGroups'][0]['sourceRefs']=[ref]
        d['unplaced']=[{'rawValue':41,'sourceRefs':[{'anchor':'Sheet2!D2','rawValue':41}]}]
        d['unreadImages']=[{'part':'word/media/image2.jpeg','sha256':'image-hash'}]
        rows=review_rows([d]);by={x['code']:x for x in rows}
        self.assertEqual(by['UNPLACED_SOURCE']['route'],'提取适配')
        self.assertEqual(by['UNPLACED_SOURCE']['currentValues']['count'],1)
        self.assertEqual(by['IMAGE_TRANSCRIPTION_REVIEW']['sourceRefs'],[ref])
        self.assertIn('UNREAD_IMAGE',by)

    def test_explicit_units_and_conditions_do_not_get_spurious_missing_questions(self):
        d=doc();d['experiments'][0]['testResults']=[{'sourceGroupKey':'g','semanticKey':'water_contact_angle',
            'unit':'°','normalized':{'kind':'INTERVAL'},'testItem':'angle','rawValue':'110-115°',
            'sourceRefs':[{'anchor':'Sheet1!C5','rawValue':'110-115°'}],
            'conditions':{'substrate':{'normalized':{'kind':'TEXT'},'value':'glass'}}}]
        rows=review_rows([d],{'water_contact_angle':['substrate']})
        self.assertEqual([x['code'] for x in rows],['HUMAN_SOURCE_CHECK'])

    def test_changed_original_is_rejected_before_checklist_is_written(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);source=root/'original.xlsx';source.write_bytes(b'original')
            d=doc();d['source']['source_sha256']=digest(source)
            d['source']['original']={'path':'raw.xlsx','sha256':digest(source)}
            (root/'raw.xlsx').write_bytes(b'changed')
            write_json(root/'history.json',d)
            write_json(root/'verification.json',{'artifacts':{'history.json':digest(root/'history.json')}})
            with self.assertRaisesRegex(ValueError,'原件已变化'):
                export_review(root,['history.json'],'review')
            self.assertFalse((root/'review').exists())

    def test_existing_human_notes_are_never_overwritten(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);(root/'review').mkdir()
            notes=root/'review/review.csv';notes.write_text('human notes',encoding='utf-8')
            with self.assertRaisesRegex(ValueError,'不覆盖人工填写内容'):
                export_review(root,[],'review')
            self.assertEqual(notes.read_text(encoding='utf-8'),'human notes')

    def test_consolidation_merges_repeated_questions_without_merging_file_approvals(self):
        d=doc();r=d['experiments'][0]
        r['formulaItems']=[{'sourceGroupKey':'g','normalized':{'kind':'NUMBER'},'unit':'',
            'compositionBasis':'UNSPECIFIED','materialName':'X','materialId':None,
            'stageKey':'a','rawValue':1,'sourceRefs':[{'anchor':'Sheet1!B5','rawValue':1}]}]
        rows=review_rows([d]);other=[{**x,'sourcePath':'second.xlsx','sourceHash':'other'} for x in rows]
        questions=consolidate_rows(rows+other)
        self.assertEqual(sum(x['bucket']=='逐份确认' for x in questions),2)
        basis=[x for x in questions if x['code']=='FORMULA_MEASUREMENT_BASIS']
        self.assertEqual(len(basis),2)
        self.assertTrue(all(len(x['sourceRowIndices'])==2 for x in basis))
        self.assertTrue(all(x['approval']=='NOT_GRANTED' and x['confirmedValue']=='' for x in questions))
        self.assertTrue(all(x['bucket']=='内部处理' for x in questions if x['code']=='MATERIAL_MASTER_UNMATCHED'))

    def test_weekday_conflict_keeps_actual_date_and_evidence(self):
        d=doc();r=d['experiments'][0];r['metadata']={}
        f={'value':{'iso':'2026-09-03T00:00:00'},'rawValue':{'iso':'2026-09-03T00:00:00'},
           'sourceRefs':[{'anchor':'星期五!C5','rawValue':'2026-09-03'}]}
        r['sourceGroups'][0]['properties']={'date':f}
        issue=next(x for x in review_rows([d]) if x['code']=='SOURCE_DATE_WEEKDAY_CONFLICT')
        self.assertEqual(issue['sourceRefs'],f['sourceRefs'])
        self.assertEqual(f['value']['iso'],'2026-09-03T00:00:00')

    def test_summary_has_a_source_link_check_and_is_not_presented_as_new_experiment(self):
        d=doc();d['experiments'][0]['recordRole']='SUMMARY';d['experiments'][0]['processSteps']=[]
        rows=review_rows([d]);self.assertEqual([x['code'] for x in rows],['SOURCE_SUMMARY'])
        self.assertFalse(rows[0]['currentValues']['independentExperiment'])

    def test_new_questions_do_not_renumber_existing_source_bound_questions(self):
        rows=review_rows([doc()]);old=consolidate_rows(rows)
        new={**rows[0],'code':'INVALID_VALUE','question':'new ambiguity'}
        current=consolidate_rows([new,*rows],old)
        self.assertEqual(next(q for q in current if q['code']=='HUMAN_SOURCE_CHECK')['questionId'],old[0]['questionId'])
        self.assertEqual(next(q for q in current if q['code']=='INVALID_VALUE')['questionId'],'Q0002')
        changed=consolidate_rows([{**rows[0],'sourceHash':'changed'}],old)
        self.assertEqual(changed[0]['questionId'],'Q0002')
        self.assertEqual(changed[0]['confirmedValue'],'')

    def test_task_conditions_apply_only_to_the_declared_source(self):
        a=doc();b=doc();b['source']['source']='other.xlsx'
        for d in [a,b]:d['experiments'][0]['testResults']=[{'sourceGroupKey':'g','semanticKey':'water_contact_angle',
            'unit':'°','normalized':{'kind':'NUMBER'},'testItem':'angle','rawValue':110,'sourceRefs':[], 'conditions':{}}]
        rules={'rules':[{'sourcePath':'original.xlsx','semanticKey':'water_contact_angle','requiredConditions':['bakeTime']}]}
        gaps=[x for x in review_rows([a,b],rules) if x['code'].startswith('TASK_CONDITION_MISSING:')]
        self.assertEqual(len(gaps),1);self.assertEqual(gaps[0]['sourcePath'],'original.xlsx')

    def test_undefined_marker_evaluation_is_not_a_missing_physical_unit(self):
        d=doc();d['experiments'][0]['testResults']=[{'sourceGroupKey':'g','semanticKey':'marker_pen_source_score',
            'unit':'','normalized':{'kind':'NUMBER'},'testItem':'记号笔','rawValue':8.8,'sourceRefs':[], 'conditions':{}}]
        rows=review_rows([d]);codes={x['code'] for x in rows}
        self.assertIn('EVALUATION_CRITERIA_UNCONFIRMED',codes);self.assertNotIn('RESULT_UNIT_UNCONFIRMED',codes)
        self.assertEqual(next(q for q in consolidate_rows(rows) if q['code']=='EVALUATION_CRITERIA_UNCONFIRMED')['bucket'],'客户需确认')

    def test_invalid_task_scope_is_rejected_instead_of_silently_broadening(self):
        for rules in [{'rules':None},{'rules':[{'semanticKey':'wear'}]},{'rules':[],'wear':['load']}]:
            with self.subTest(rules=rules):
                with self.assertRaises(ValueError):review_rows([doc()],rules)


if __name__=='__main__':unittest.main()
