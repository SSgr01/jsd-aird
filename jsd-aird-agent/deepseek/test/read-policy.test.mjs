import test from 'node:test';
import assert from 'node:assert/strict';
import {readExecutionPolicy} from '../../enterprise-plugin/src/read-policy.mjs';
import {executeToolBatch, ToolExecutionPipeline} from '../../../vendor/deepseek-harness/packages/core/tools/lib/pipeline.js';

test('the native scheduler recognizes only approved independent readers as parallel', () => {
  const definitions = new Map(['document_read','document_outline','evidence_read','private_document_read',
    'artifact_save','report_save','literature_acquire','unknown'].map(name => [name, {name, ...readExecutionPolicy(name)}]));
  const pipeline = new ToolExecutionPipeline({tools:{executable:name=>definitions.get(name)}});
  for (const name of ['document_read','document_outline','evidence_read','private_document_read'])
    assert.equal(pipeline.executionMode({name,arguments:{}}).kind,'parallel');
  for (const name of ['artifact_save','report_save','literature_acquire','unknown','unregistered'])
    assert.equal(pipeline.executionMode({name,arguments:{}}).kind,'exclusive');
});

test('independent reads overlap while per-call admission, ordered results and write barriers remain', {timeout:5000}, async () => {
  const names=['document_read','evidence_read','artifact_save','document_outline'];
  const definitions=new Map(names.map(name=>[name,{name,...readExecutionPolicy(name)}]));
  const classifier=new ToolExecutionPipeline({tools:{executable:name=>definitions.get(name)}});
  const first=Promise.withResolvers(),second=Promise.withResolvers(),both=Promise.withResolvers();
  const admitted=[],started=[],committed=[];
  let active=0,maxActive=0;
  const pipeline={
    executionMode:exec=>classifier.executionMode(exec),
    async prepare(exec) { admitted.push(exec.callId); return {kind:'dispatch',exec}; },
    async dispatch(exec) {
      started.push(exec.callId);
      if(exec.name==='artifact_save') {
        assert.equal(active,0);
        assert.deepEqual(committed,['r1','r2']);
      } else {
        active++; maxActive=Math.max(maxActive,active);
        if(active===2)both.resolve();
        if(exec.callId==='r1')await first.promise;
        if(exec.callId==='r2')await second.promise;
        active--;
      }
      return {kind:'post-result',result:{content:[{type:'text',text:exec.callId}]}};
    },
    async complete(exec,slot) { committed.push(exec.callId); return slot.result; },
  };
  const ids=['r1','r2','write','r3'];
  const pending=executeToolBatch({calls:names.map((name,i)=>({id:ids[i],name,arguments:'{}'})),
    signal:new AbortController().signal},pipeline,()=>2);
  await both.promise;
  assert.deepEqual(started,['r1','r2']);
  second.resolve();
  await new Promise(resolve=>setImmediate(resolve));
  assert.deepEqual(committed,[]);
  first.resolve();
  const result=await pending;
  assert.equal(maxActive,2);
  assert.deepEqual(admitted,ids);
  assert.deepEqual(committed,ids);
  assert.deepEqual(result.results.map(item=>item.callId),ids);
});
