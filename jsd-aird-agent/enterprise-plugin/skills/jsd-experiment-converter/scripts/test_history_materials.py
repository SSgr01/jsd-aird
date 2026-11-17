import copy
from pathlib import Path
import tempfile
import unittest
from history_materials import material_index,summary_links,export_index
from workflow import digest,write_json

def document(names):
    return {'source':{'source':'raw.xlsx','source_sha256':'hash'},'experiments':[{'recordKey':'r','recordRole':'EXPERIMENT',
        'metadata':{},'sourceGroups':[{'sourceGroupKey':'g','sourceIdentity':'A','sourceRefs':[]}],
        'formulaItems':[{'materialName':name,'sourceGroupKey':'g','stageKey':str(n),'sequence':n,
            'rawValue':n,'unit':'g','sourceRefs':[{'anchor':'S!B'+str(n),'rawValue':n}]} for n,name in enumerate(names,1)]}]}

class MaterialTests(unittest.TestCase):
    def test_concentration_and_solvent_qualifiers_are_not_stripped(self):
        r=material_index([document(['184','184/10%(S-6)','184/20%(S-6)','184/10%(S-5)'])])
        self.assertEqual(r['summary']['rawNames'],4);self.assertEqual(r['aliasCandidates'],[])
        self.assertTrue(all(x['proposedMaterialId'] is None for x in r['materials']))

    def test_punctuation_is_only_an_alias_candidate_and_never_a_merge(self):
        r=material_index([document(['UA84B','UA-84B','UA-84B/10%'])])
        self.assertEqual(r['summary']['rawNames'],3)
        self.assertEqual(r['aliasCandidates'][0]['rawNames'],['UA-84B','UA84B'])
        self.assertFalse(r['aliasCandidates'][0]['merged'])

    def test_repeated_additions_keep_all_stages_and_source_values(self):
        r=material_index([document(['S-48','S-48'])]);x=r['materials'][0]
        self.assertEqual([o['rawAmount'] for o in x['occurrences']],[1,2])
        self.assertEqual([o['stageKey'] for o in x['occurrences']],['1','2'])

    def test_summary_labels_do_not_prove_independence_or_date(self):
        d=document([]);summary=copy.deepcopy(d['experiments'][0]);summary.update(recordRole='SUMMARY',recordKey='summary')
        d['experiments'].append(summary);links=summary_links([d])
        self.assertEqual(links[0]['status'],'EXACT_LABEL_CANDIDATE');self.assertFalse(links[0]['dateInherited'])
        second=copy.deepcopy(d['experiments'][0]);second['recordKey']='r2';second['sourceGroups'][0]['sourceGroupKey']='g2'
        d['experiments'].insert(0,second)
        self.assertEqual(summary_links([d])[0]['status'],'AMBIGUOUS_LABEL')
        other=document([]);other['source']['source']='other.xlsx';other['experiments'][0]['sourceGroups'][0]['sourceIdentity']='B'
        self.assertEqual(len(summary_links([other])),0)

    def test_missing_summary_match_does_not_borrow_from_another_file(self):
        d=document([]);d['experiments'][0]['recordRole']='SUMMARY';other=document([]);other['source']['source']='other.xlsx'
        self.assertEqual(summary_links([d,other])[0]['status'],'NO_EXACT_LABEL')

    def test_changed_source_and_existing_manual_work_are_protected(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);(root/'raw.xlsx').write_bytes(b'original');d=document([])
            d['source']['source_sha256']=digest(root/'raw.xlsx');write_json(root/'history.json',d)
            write_json(root/'verification.json',{'artifacts':{'history.json':digest(root/'history.json')}})
            (root/'raw.xlsx').write_bytes(b'changed')
            with self.assertRaisesRegex(ValueError,'工作副本已变化'):export_index(root,['history.json'],'out')
            (root/'out').mkdir();(root/'out/materials.csv').write_text('human work',encoding='utf-8')
            with self.assertRaisesRegex(ValueError,'不覆盖'):export_index(root,[],'out')
            self.assertEqual((root/'out/materials.csv').read_text(encoding='utf-8'),'human work')

if __name__=='__main__':unittest.main()
