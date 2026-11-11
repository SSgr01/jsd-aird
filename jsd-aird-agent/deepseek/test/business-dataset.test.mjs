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
