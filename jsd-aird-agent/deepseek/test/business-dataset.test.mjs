import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { materializeBusinessDataset } from '../../enterprise-plugin/src/business-dataset.mjs';
import { modelResult } from '../../enterprise-plugin/src/model-view.mjs';

function fixture() {
  const bytes = Buffer.from(JSON.stringify({schema:{columns:[{field:'kg',types:['NUMBER']}]},rows:[{kg:0,missing:null}]}));
  const hash = createHash('sha256').update(bytes).digest('hex');
  const dataset = {name:`business-${hash.slice(0,20)}.json`,sha256:hash,bytes:bytes.length,content_base64:bytes.toString('base64')};
  const writes = [];
  const ctx = {workingDirectory:{ensure:async()=>'/native/session'},fs:{
    resolve:async(path,{cwd})=>({targetKey:cwd+'/'+path}),publish:async(path,content)=>writes.push({path,content})}};
  return {ctx,writes,dataset};
}

test('authorized record bodies become immutable native datasets without translating null or zero',async()=>{
  const {ctx,writes}=fixture();
  const text=JSON.stringify({section:'record',current_registration:{experiment_no:'ELN-001'},sections:{
    testResults:[{records:[{testItem:'黏度',unit:'mPa·s',values:[{sample_key:'A',value:0},{sample_key:'B',value:null}]}]}]}});
  const source={kind:'BUSINESS_RECORD',resource_type:'EXPERIMENT',resource_id:'experiment',version_id:'v1',
    content_sha256:createHash('sha256').update(text).digest('hex')};
  const input={candidates:[{source,text,evidence_id:'e1',model_text:'缺失'}]};
  const result=await materializeBusinessDataset(ctx,{agent:{}},input);
  const snapshot=JSON.parse(writes[0].content);
  assert.deepEqual(snapshot.rows.map(row=>row.value),[0,null]);
  assert.equal(snapshot.rows[1].registration.experiment_no,'ELN-001');
  assert.deepEqual(snapshot.records[0].content,JSON.parse(text));
  assert.equal(result.dataset.format,'business-records');
  assert.match(result.analysis_hint,/null必须写成空白/);
  assert.equal(input.dataset,undefined);
  const count=writes.length;
  await assert.rejects(materializeBusinessDataset(ctx,{agent:{}},{candidates:[{...input.candidates[0],text:text+' '}]}),/BUSINESS_RECORD_HASH_MISMATCH/);
  assert.equal(writes.length,count);
});
test('Word originals retain their exact bytes in the native task directory',async()=>{
  const {ctx,writes}=fixture(),bytes=Buffer.from('PK\u0003\u0004word-original');
  const result=await materializeBusinessDataset(ctx,{agent:{}},{business_file:{name:'交付说明.docx',content_type:'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
    bytes:bytes.length,sha256:createHash('sha256').update(bytes).digest('hex'),content_base64:bytes.toString('base64')}});
  assert.match(result.business_file.path,/\.docx$/);assert.deepEqual(writes[0].content,bytes);
  assert.equal(result.business_file.content_base64,undefined);
});

test('original text views stay hash-bound and require a separate native read',async()=>{
  const {ctx,writes}=fixture(),bytes=Buffer.from('PK-word'),text=Buffer.from('原文件正文：编号000123，数量0');
  const digest=value=>createHash('sha256').update(value).digest('hex');
  const file={name:'技术要求.docx',content_type:'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
    bytes:bytes.length,sha256:digest(bytes),content_base64:bytes.toString('base64'),text_projection:{source_sha256:digest(bytes),
      bytes:text.length,sha256:digest(text),content_base64:text.toString('base64'),parser:'office-docx-v2'}};
  const result=await materializeBusinessDataset(ctx,{agent:{}},{business_file:file});
  assert.equal(writes.length,2);assert.deepEqual(writes[0].content,bytes);assert.deepEqual(writes[1].content,text);
  assert.match(result.business_file.reading_path,/\.txt$/);assert.match(result.file_analysis_hint,/read\(file_path=business_file.reading_path\)/);
  assert.equal(result.business_file.text_projection.content_base64,undefined);
  assert.equal(result.business_file.text_projection.source_sha256,digest(bytes));
  await assert.rejects(materializeBusinessDataset(ctx,{agent:{}},{business_file:{...file,text_projection:{...file.text_projection,source_sha256:'0'.repeat(64)}}}),/BUSINESS_PROJECTION_SOURCE_MISMATCH/);
});

test('knowledge images reach the session filesystem without base64 entering model text',async()=>{
  const {ctx,writes}=fixture();
  const bytes=Buffer.from([137,80,78,71,13,10,26,10]);
  const file={name:'figure.png',content_type:'image/png',bytes:bytes.length,
    sha256:createHash('sha256').update(bytes).digest('hex'),content_base64:bytes.toString('base64')};
  const value=await materializeBusinessDataset(ctx,{agent:{id:'knowledge-session'}},
    {business_file:file,evidence_id:'e'+'1'.repeat(24),image:{view:'figure',page:3}});
  assert.equal(writes.length,1);
  assert.deepEqual(writes[0].content,bytes);
  assert.match(value.business_file.path,/^\.business-data\/source-[a-f0-9]{20}\.png$/);
  assert.match(value.file_analysis_hint,/read_image/);
  assert.equal(value.image.page,3);
  assert.equal(value.business_file.content_base64,undefined);
  assert.equal(modelResult(value).evidence_id,'e'+'1'.repeat(24));
});
test('business snapshots use the native filesystem and preserve numeric zero and missing values',async()=>{
  const {ctx,writes,dataset}=fixture();
  const result=await materializeBusinessDataset(ctx,{agent:{id:'isolated-session'}},{dataset});
  assert.equal(writes.length,1);
  assert.match(result.dataset.path,/^\.business-data\/business-[a-f0-9]{20}\.json$/);
  assert.deepEqual(JSON.parse(writes[0].content).rows,[{kg:0,missing:null}]);
  assert.equal(result.dataset.content_base64,undefined);
  assert.equal(modelResult({dataset}).dataset.content_base64,undefined);
});
test('modified bytes and forged paths are rejected before any publication',async()=>{
  for(const change of [{content_base64:Buffer.from('modified').toString('base64')},{name:'../../outside.json'},{sha256:'0'.repeat(64)}]){
    const {ctx,writes,dataset}=fixture();
    await assert.rejects(materializeBusinessDataset(ctx,{agent:{}},{dataset:{...dataset,...change}}));
    assert.equal(writes.length,0);
  }
});
test('stopped turns cannot publish a dataset',async()=>{
  const {ctx,writes,dataset}=fixture(), stop=new AbortController();stop.abort();
  await assert.rejects(materializeBusinessDataset(ctx,{agent:{},signal:stop.signal},{dataset}));
  assert.equal(writes.length,0);
});

test('authorized spectrum originals use native files without exposing binary protocol',async()=>{
  const {ctx,writes}=fixture(), bytes=Buffer.from('%PDF-1.7\nIsolated spectrum fixture');
  const sha256=createHash('sha256').update(bytes).digest('hex');
  const business_file={name:'验收谱图.pdf',content_type:'application/pdf',bytes:bytes.length,
    sha256,content_base64:bytes.toString('base64'),ref:{type:'SPECTRUM',id:'isolated',revision:'v1'}};
  const result=await materializeBusinessDataset(ctx,{agent:{}},{business_file});
  assert.match(result.business_file.path,/^\.business-data\/source-[a-f0-9]{20}\.pdf$/);
  assert.deepEqual(writes[0].content,bytes);
  assert.equal(result.business_file.content_base64,undefined);
  assert.equal(result.business_file.name,'验收谱图.pdf');
  for(const change of [{name:'../spectrum.pdf'},{content_type:'text/html'},{sha256:'0'.repeat(64)}]){
    await assert.rejects(materializeBusinessDataset(ctx,{agent:{}},{business_file:{...business_file,...change}}));
  }
  assert.equal(writes.length,1);
});

test('cancellation while native working directory resolves prevents publication',async()=>{
  const {ctx,writes,dataset}=fixture(), stop=new AbortController();
  ctx.workingDirectory.ensure=async()=>{stop.abort();return '/native/session';};
  await assert.rejects(materializeBusinessDataset(ctx,{agent:{},signal:stop.signal},{dataset}));
  assert.equal(writes.length,0);
});
