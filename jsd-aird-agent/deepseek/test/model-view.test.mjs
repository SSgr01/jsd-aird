import test from 'node:test';
import assert from 'node:assert/strict';
import { presentation, modelResult, renderModelResult } from '../../enterprise-plugin/src/model-view.mjs';

test('optimization candidates retain identities, inputs and predictions on the model path', () => {
  const raw={candidates:[{id:'candidate-1',inputs:{temperature:83},results:{value:51.8}},
    {candidateId:'candidate-2',predictedResults:{value:52}}]};
  assert.deepEqual(modelResult(raw),raw);
});

test('personal memory history retains authorized native locations and content revisions in model projection',()=>{
  const value={memory_context:true,entries:[{id:'entry',content_revision:2,revision:4,text:'以后先注明单位。',
    source:{kind:'session',session_id:'original',turn:1,event_seq:9,hash:'digest',bank_id:'never-export'}}]};
  const shown=JSON.parse(renderModelResult(value)[0].text);
  assert.equal(shown.entries[0].source.session_id,'original');
  assert.equal(shown.entries[0].source.event_seq,9);
  assert.equal(shown.entries[0].content_revision,2);
  assert.equal(shown.entries[0].source.bank_id,undefined);
  assert.match(modelResult({error:'MEMORY_USER_STATEMENT_REQUIRED',access_denied:true}).error,/逐字取自用户/);
});

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
  assert.equal(value.document_sources[0].publication_id,'pub');
  assert.equal(value.document_sources[0].version_id,'v2');
  assert.equal(value.candidates[0].document_ref,value.document_sources[0].ref);
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

test('unavailable business modules are explained by name without exposing closed contracts or inviting probes', () => {
  const raw = {resource_types:[{type:'PROJECT',available:true,filters:['query']},
    {type:'SHIPMENT',available:false,filters:['private_field']},{type:'QUALITY_COA',available:false}]};
  const value=modelResult(raw);
  assert.deepEqual(value.resource_types,[{type:'PROJECT',name:'项目',available:true,filters:['query']}]);
  assert.deepEqual(value.unavailable_resources,['出货单','COA 证书']);
  assert.match(value.reading_notice,/能力目录.*type.*required_filters/);
  assert.doesNotMatch(JSON.stringify(value),/SHIPMENT|QUALITY_COA|private_field/);
  assert.equal(raw.resource_types[1].available,false);
});

test('business refusal and unknown write outcomes retain meaning without internal errors in model or card text', () => {
  const denied={error:'Business tool refused the request',access_denied:true,retryable:false,trace:{secret:'internal'}};
  assert.match(modelResult(denied).error,/当前账号无权读取/);
  assert.match(presentation('business_search',denied).summary,/当前账号无权读取/);
  assert.doesNotMatch(JSON.stringify(modelResult(denied)),/Business tool|internal|secret/);
  const unknown=modelResult({error:'TOOL_RESULT_UNKNOWN',requires_reconciliation:true,retryable:false});
  assert.equal(unknown.requires_reconciliation,true);
  assert.match(unknown.error,/不能重复提交/);
  assert.doesNotMatch(unknown.error,/TOOL_RESULT_UNKNOWN/);
});

test('search hits deliver each canonical body once and retain sample, condition and reading handles', () => {
  const value=modelResult({retrieval_status:'PARTIAL',retrieval_notice:'向量检索未完成',hits:[{
    evidence_id:'e1',source:{title:'Paper',document_id:'doc',publication_id:'fixed',anchor:{page:7}},
    source_text:'Sample A: 12.38 HZ after 2000 cycles; PET substrate.',
    quote:{text:'12.38 HZ',quote_id:'q1',start:10},context_quotes:[{text:'PET substrate.',start:40}],
    quote_offset:2,quote_count:8,table_context:{header_node_keys:['header']},requires_read:true,
  }]});
  assert.equal(value.hits[0].text,'Sample A: 12.38 HZ after 2000 cycles; PET substrate.');
  assert.equal(value.hits[0].quote_id,'q1');
  assert.equal(value.document_sources[0].publication_id,'fixed');
  assert.equal(value.hits[0].document_ref,value.document_sources[0].ref);
  assert.equal(value.hits[0].requires_read,true);
  assert.equal(JSON.stringify(value).match(/12\.38/g).length,1);
  assert.equal(value.retrieval_status,'PARTIAL');
  assert.equal(value.retrieval_notice,'向量检索未完成');
  assert.deepEqual(modelResult({hits:[{text:'other tool result',count:0}]}),{hits:[{text:'other tool result',count:0}]});
});

test('retrieval timeout is actionable without exposing an internal error code', () => {
  const raw={error:'RETRIEVAL_TIMEOUT: internal',retryable:false};
  for (const text of [modelResult(raw).error,presentation('knowledge_search',raw).summary]) {
    assert.match(text,/覆盖不完整/);
    assert.doesNotMatch(text,/RETRIEVAL_TIMEOUT|internal/);
  }
});

test('bounded discovery coverage remains visible without exposing retrieval internals', () => {
  const trace={ranking_scope:'BOUNDED_CURRENT_INDEX_CANDIDATES',fulltext_coverage:false,
    coverage_note:'仅检索当前索引候选，不代表已检查全部原文。',sql:'internal',timings_ms:{bm25:44}};
  assert.deepEqual(modelResult({hits:[],trace}).trace,{
    ranking_scope:trace.ranking_scope,fulltext_coverage:false,coverage_note:trace.coverage_note});
});

test('outline model entries keep reading offsets and pages without source geometry', () => {
  const raw={entries:[{node_offset:58,end_offset_exclusive:63,source_node_key:'ledger-node',
    kind:'HEADING_CANDIDATE',title:'3.9. Evaluation of Haze',
    anchor:{page:7,polygon:[0,0,1,1],pageWidth:594,pageHeight:841,provider:'MINERU'}}],
    trace:{total_nodes:100,fulltext_coverage:false},next_offset:96};
  const value=modelResult(raw);
  assert.deepEqual(value.entries,[{node_offset:58,end_offset_exclusive:63,
    kind:'HEADING_CANDIDATE',title:'3.9. Evaluation of Haze',page:7}]);
  assert.equal(value.next_offset,96);
  assert.equal(raw.entries[0].source_node_key,'ledger-node');
  assert.ok(raw.entries[0].anchor.polygon);
  assert.doesNotMatch(JSON.stringify(value),/polygon|MINERU|ledger-node|pageWidth/);
  assert.deepEqual(modelResult({entries:[{data:'other result'}]}),{entries:[{data:'other result'}]});
});

test('search reuse keeps fixed-version handles and progress without reinjecting bodies', () => {
  const value=modelResult({hits:[{evidence_id:'existing',source:{document_id:'doc',publication_id:'fixed',
    content_sha256:'ledger-only',anchor:{page:7}},quote_offset:8,quote_count:15,already_retrieved:true}],
    trace:{new_evidence_count:0,new_source_versions:0,cache_hit:true,retrieval_ms:100}});
  assert.equal(value.hits[0].already_retrieved,true);
  assert.equal(value.document_sources[0].publication_id,'fixed');
  assert.equal(value.hits[0].quote_offset,8);
  assert.deepEqual(value.trace,{new_evidence_count:0,new_source_versions:0,cache_hit:true});
  assert.doesNotMatch(JSON.stringify(value),/ledger-only|retrieval_ms/);
});

test('business query repair and session expiry stay distinct from empty results', () => {
  const raw={error:'业务参数缺少必要字段：filters.project_id；请补齐后再查询。',
    error_code:'INVALID_TOOL_ARGUMENTS',retryable:false,attempts:1,
    repair:{path:['filters','project_id'],required_filters:['project_id'],allowed_filters:['project_id']}};
  assert.deepEqual(modelResult(raw),raw);
  assert.match(presentation('business_search',raw).summary,/filters.project_id/);
  assert.equal(presentation('business_search',raw).failed,true);
  const expired=modelResult({error:'private upstream error',error_code:'AUTH_REQUIRED',access_denied:true,retryable:false});
  assert.match(expired.error,/登录状态已失效/);
  assert.doesNotMatch(expired.error,/private/);
});

test('reused nodes and quote handles stay visible without returning their bodies again', () => {
  const value=modelResult({candidates:[{evidence_id:'e1',source:{document_id:'doc',publication_id:'fixed'},
    already_retrieved:true,quote_count:6}],hits:[{evidence_id:'e1',source:{document_id:'doc',publication_id:'fixed'},
    already_retrieved:true,quote_offset:3,quote_count:6,quote_id:'q4'}],
    trace:{reused_evidence_count:1,omitted_characters:1200,internal:'private'}});
  assert.equal(value.candidates[0].already_retrieved,true);
  assert.equal(value.candidates[0].quote_count,6);
  assert.equal(value.hits[0].quote_id,'q4');
  assert.equal(value.document_sources[0].publication_id,'fixed');
  assert.deepEqual(value.trace,{reused_evidence_count:1,omitted_characters:1200});
  assert.doesNotMatch(JSON.stringify(value),/private|internal/);
});

test('document fragments share one declaration while versions, locations and table conditions stay distinct',()=>{
  const source={kind:'KB_PUBLICATION',title:'A long paper title',document_id:'doc',publication_id:'pub',version_id:'v1'};
  const raw={candidates:[
    {evidence_id:'e1',source:{...source,anchor:{page:3,section:'Results'}},text:'Sample A: 0 mg at 25 °C',condition:'25 °C',unit:'mg'},
    {evidence_id:'e2',source:{...source,anchor:{page:4}},text:'Sample B: 12 mg at 80 °C'},
    {evidence_id:'e3',source:{...source,version_id:'v2',anchor:{page:4}},text:'Corrected value: 13 mg'},
  ],document_metadata:[{...source,abstract:'A recorded abstract'}],
  citation_links:{e1:{url:'https://original.test/v1#page=3',label:'原文'}}};
  const before=structuredClone(raw), value=modelResult(raw);
  assert.equal(value.document_sources.length,2);
  assert.equal(value.candidates[0].document_ref,value.candidates[1].document_ref);
  assert.notEqual(value.candidates[1].document_ref,value.candidates[2].document_ref);
  assert.deepEqual(value.candidates.map(c=>[c.evidence_id,c.page,c.text]),raw.candidates.map(c=>[c.evidence_id,c.source.anchor.page,c.text]));
  assert.equal(value.candidates[0].condition,'25 °C');
  assert.equal(value.candidates[0].unit,'mg');
  assert.equal(value.document_sources[0].abstract,'A recorded abstract');
  assert.equal(value.candidates[0].url,undefined);
  assert.equal(presentation('document_read',raw).sources[0].url,raw.citation_links.e1.url);
  assert.deepEqual(raw,before);
  const text=JSON.stringify(value);
  assert.deepEqual(renderModelResult(raw,Array.from(text).length),[{type:'text',text}]);
  assert.throws(()=>renderModelResult(raw,Array.from(text).length-1),/预算/);
  assert.deepEqual(modelResult(raw),value); // no hidden cross-call source cache
});

test('null model text preserves the original body at the final budget boundary',()=>{
  const raw={candidates:[{evidence_id:'e1',model_text:null,text:'12 mg at 25 °C 🧪',
    source:{document_id:'doc',publication_id:'fixed'}}]};
  const value=modelResult(raw), text=JSON.stringify(value);
  assert.equal(value.candidates[0].text,raw.candidates[0].text);
  assert.equal(renderModelResult(raw,Array.from(text).length)[0].text,text);
  assert.throws(()=>renderModelResult(raw,Array.from(text).length-1),/预算/);
});
