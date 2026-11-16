import test from 'node:test';
import assert from 'node:assert/strict';
import { recoverControlRead } from '../../enterprise-plugin/src/control-recovery.mjs';
import { EventProjection } from '../../enterprise-plugin/src/event-projection.mjs';
import { businessError } from '../../enterprise-plugin/src/business-error.mjs';
import { LlmError } from '../../../vendor/deepseek-harness/packages/llm/llm/lib/index.js';
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';
import { mountAgentLoopTestDependencies, mountAgentLoopTestHarness } from '../../../vendor/deepseek-harness/packages/test-support/agent-loop-testkit/lib/index.js';

test('native Tools.resolve survives a transient availability read and fails closed after bounded retries',async t=>{
  const require=createRequire(new URL('../../../vendor/deepseek-harness/packages/core/tools/package.json',import.meta.url));
  const {Context}=await import(pathToFileURL(require.resolve('@deepseek-ai/cordis')).href);
  const ctx=new Context();
  t.after(()=>ctx.fiber.dispose());
  await mountAgentLoopTestDependencies(ctx,{systemPrompt:{includeRuntimeContext:false}});
  const harness=await mountAgentLoopTestHarness(ctx);
  const agent=await harness.create('availability-recovery');
  let attempts=0, unavailable=false;
  ctx.tools.register({name:'original_status',description:'Read the original execution',
    parameters:{type:'object',properties:{}},output:{schema:{type:'object'},render:()=>[]},execute:async()=>({})},
    {when:()=>recoverControlRead('ownership',{session_id:agent.id},async()=>{
      attempts++;
      if(unavailable || attempts===1)throw new TypeError('fetch failed',{cause:{code:'ECONNRESET'}});
      return true;
    },{pause:async()=>{}})});
  const activation={agent,signal:new AbortController().signal,modelSelection:{provider:'mock',model:'mock'}};
  const selected=await ctx.tools.resolve(activation);
  assert.ok(selected.view(agent).has('original_status'));
  assert.equal(attempts,2);
  selected.dispose();unavailable=true;attempts=0;
  await assert.rejects(ctx.tools.resolve(activation),error=>error.code==='TOOL_ACTIVATION_FAILED');
  assert.equal(attempts,3);
});

test('read recovery preserves the request and only retries transient transport faults',async()=>{
  const body={run_id:'original-run',call_id:'original-command',stdout_offset:7};let attempts=0;
  const result=await recoverControlRead('native-output',body,async()=>{
    if(++attempts<3)throw new TypeError('fetch failed',{cause:{code:'ECONNRESET'}});
    return {status:'RUNNING'};
  },{pause:async()=>{}});
  assert.equal(attempts,3);assert.equal(result.status,'RUNNING');assert.equal(body.call_id,'original-command');
  for(const action of ['native-tool','tool','gate','admit']) {
    attempts=0;
    await assert.rejects(recoverControlRead(action,body,async()=>{attempts++;throw new TypeError('fetch failed');},{pause:async()=>{}}));
    assert.equal(attempts,1,action+' must not replay');
  }
  for(const status of [401,403,409,422]) {
    attempts=0;
    await assert.rejects(recoverControlRead('restore',body,async()=>{attempts++;throw Object.assign(new Error('denied'),{status});},{pause:async()=>{}}));
    assert.equal(attempts,1);
  }
});

test('registration retries only an admitted immutable input identity; cancellation stops retries',async()=>{
  const body={run_id:'r',epoch:1,turn:1,input_signature:'same-input'};let attempts=0;
  await recoverControlRead('register',body,async()=>{if(++attempts===1)throw new TypeError('fetch failed');return {};},{pause:async()=>{}});
  assert.equal(attempts,2);
  attempts=0;
  await assert.rejects(recoverControlRead('register',{},async()=>{attempts++;throw new TypeError('fetch failed');},{pause:async()=>{}}));
  assert.equal(attempts,1);
  const abort=new AbortController();attempts=0;
  await assert.rejects(recoverControlRead('native-output',body,async()=>{attempts++;abort.abort();throw new TypeError('fetch failed');},{signal:abort.signal,pause:async()=>{}}),{name:'AbortError'});
  assert.equal(attempts,1);
});

test('background ticket renewal retries transient service faults but never authorization refusals',async()=>{
  let attempts=0;
  const result=await recoverControlRead('background-ticket',{owner_id:'owner'},async()=>{
    if(++attempts===1)throw Object.assign(new Error('service unavailable'),{status:503});
    return {ticket:'current'};
  },{pause:async()=>{}});
  assert.equal(attempts,2);assert.equal(result.ticket,'current');
  for(const status of [401,403]) {
    attempts=0;
    await assert.rejects(recoverControlRead('background-ticket',{owner_id:'owner'},async()=>{
      attempts++;throw Object.assign(new Error('refused'),{status});
    },{pause:async()=>{}}));
    assert.equal(attempts,1);
  }
});

test('activation drains a sequence-keyed projection after a lost acknowledgement without duplicating the event',async()=>{
  const committed=new Set(), sends=[], queue=[];
  const event={seq:17,type:'assistant/message',data:{turn:1,message:{content:[{type:'text',text:'客户台账'}]}}};
  const projection=new EventProjection({enqueue:(_sid,operation)=>{queue.push(operation);},
    send:(session_id,event)=>recoverControlRead('event',{session_id,event},async()=>{
      sends.push(event);
      committed.add(event.seq);
      if(sends.length===1)throw new TypeError('fetch failed',{cause:{code:'ECONNRESET'}});
      return {accepted:true};
    },{pause:async()=>{}})});
  projection.push('session',event);
  await queue.shift()();
  projection.check('session');
  assert.equal(sends.length,2);assert.equal(sends[0],sends[1]);
  assert.equal(committed.size,1);
});

test('only a sequence-keyed event and same-epoch renewal may retry, while authorization still fails closed',async()=>{
  for(const [action,body,count] of [
    ['event',{session_id:'s',event:{seq:0}},3],['event',{session_id:'s',event:{}},1],
    ['admission-renew',{session_id:'s',run_id:'r',epoch:1},3],['admission-renew',{run_id:'r'},1],
  ]) {
    let attempts=0;
    await assert.rejects(recoverControlRead(action,body,async()=>{attempts++;throw new TypeError('fetch failed');},{pause:async()=>{}}));
    assert.equal(attempts,count);
  }
  let attempts=0;
  await assert.rejects(recoverControlRead('event',{session_id:'s',event:{seq:1}},async()=>{
    attempts++;throw Object.assign(new Error('denied'),{status:403});
  },{pause:async()=>{}}));
  assert.equal(attempts,1);
});

test('an unknown write refusal is a tool error, while final delivery stays fenced',async()=>{
  const error=await businessError(new Response(JSON.stringify({detail:'SIDE_EFFECT_RECONCILIATION_REQUIRED'}),{status:409}));
  assert.equal(error.code,'SIDE_EFFECT_RECONCILIATION_REQUIRED');
  assert.equal(error instanceof LlmError,false);
});
