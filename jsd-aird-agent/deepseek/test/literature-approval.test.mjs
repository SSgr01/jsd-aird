import test from 'node:test';
import assert from 'node:assert/strict';
import {approveLiterature} from '../../enterprise-plugin/src/literature-approval.mjs';

test('public literature decisions use the native surface and bind exact tool arguments and call identity',async()=>{
  const calls=[];const args={query:'acrylic film gloss'};
  const ctx={get:()=>({request:async request=>{calls.push(request);return 'allowed-once';}})};
  const business=async(action,body)=>calls.push({action,body});
  const execution={agent:{id:'session'},callId:'call-a'};
  await approveLiterature(ctx,business,{id:'run',epoch:3,dsh_turn:4},'literature_search',args,execution);
  assert.equal(calls[0].callId,'call-a');assert.equal(calls[1].action,'literature-approval');
  assert.equal(calls[1].body.arguments,args);assert.equal(calls[1].body.call_id,'call-a');
  assert.equal(calls[1].body.epoch,3);
});

test('missing, cancelled and rejected decisions cannot create a host grant',async()=>{
  let grants=0;const business=async()=>grants++;
  await approveLiterature({get:()=>undefined},business,{},'business_read',{},{});
  await assert.rejects(approveLiterature({get:()=>undefined},business,{},'literature_acquire',{},{}),/UNAVAILABLE/);
  for(const outcome of ['rejected','cancelled','unavailable'])
    await assert.rejects(approveLiterature({get:()=>({request:async()=>outcome})},business,{},'literature_search',{},{}),/EXTERNAL_APPROVAL/);
  assert.equal(grants,0);
});
