import test from 'node:test';
import assert from 'node:assert/strict';
import { presentation, modelResult } from '../../enterprise-plugin/src/model-view.mjs';

test('enterprise metadata is lossless JSON for discovery, empty results and actual sources', () => {
  for (const value of [{activate_tools:['get_experiment_record']},{candidates:[]},
    {candidates:[{text:'actual source'}],citation_links:{e1:{url:'/fixed-version',label:'原文·第3页'}}}]) {
    const meta=presentation('tool_search',value);
    assert.deepEqual(JSON.parse(JSON.stringify(meta)),meta);
  }
});

test('readable source projection keeps the handles needed to continue reading the fixed version', () => {
  const value=modelResult({candidates:[{evidence_id:'e1',text:'Sample B: 12 kPa at 25 °C',
    source:{title:'Paper',document_id:'doc',publication_id:'pub',version_id:'v2',content_sha256:'ledger-only',anchor:{page:3}}}]});
  assert.equal(value.candidates[0].publication_id,'pub');
  assert.equal(value.candidates[0].version_id,'v2');
  assert.equal(value.candidates[0].page,3);
  assert.equal(value.candidates[0].text,'Sample B: 12 kPa at 25 °C');
  assert.ok(!JSON.stringify(value).includes('ledger-only'));
});

test('tool cards link only the records returned by that call, with duplicate versions collapsed', () => {
  const card=presentation('get_experiment_record',{candidates:[
    {evidence_id:'current',source:{title:'目标实验'}},{evidence_id:'same-version',source:{title:'目标实验'}}],
    citation_links:{old:{url:'/unrelated',label:'实验记录·V1'},
      current:{url:'/correct?version_id=fixed',label:'实验记录·V2'},
      'same-version':{url:'/correct?version_id=fixed',label:'实验记录·V2'}}});
  assert.deepEqual(card.sources,[{url:'/correct?version_id=fixed',label:'目标实验 · 实验记录·V2'}]);
});

test('model receives verified record entrances without unrelated ledger links', () => {
  const value=modelResult({candidates:[{evidence_id:'current',text:'Actual project fact',source:{title:'项目'}}],
    citation_links:{current:{url:'/projects/actual',label:'项目'},other:{url:'/unrelated',label:'无关'}}});
  assert.equal(value.candidates[0].url,'/projects/actual');
  assert.ok(!JSON.stringify(value).includes('/unrelated'));
});
