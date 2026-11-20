import test from 'node:test';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {pathToFileURL} from 'node:url';
import {mountAgentLoopTestDependencies,mountAgentLoopTestHarness} from '../../../vendor/deepseek-harness/packages/test-support/agent-loop-testkit/lib/index.js';
import {LlmAdapter,createUserMessage} from '../../../vendor/deepseek-harness/packages/llm/llm/lib/index.js';
import * as ProgressReminder from '../../enterprise-plugin/src/read-progress-reminder.mjs';
import {enterprisePatch} from '../src/config.mjs';

const require=createRequire(new URL('../../../vendor/deepseek-harness/packages/fs/fs-local/package.json',import.meta.url));
const {Context}=await import(pathToFileURL(require.resolve('@deepseek-ai/cordis')).href);

for(const failure of [false,true])test(`native repetition reminders follow committed page progress and retain ${failure?'denial':'stagnation'} detection`,async t=>{
  const ctx=new Context();t.after(()=>ctx.fiber.dispose());
  await mountAgentLoopTestDependencies(ctx,{systemPrompt:{includeRuntimeContext:false}});
  await ctx.plugin(ProgressReminder);
  let reads=0;
  ctx.tools.register({name:'business_read_many',description:'读取下一批固定记录',parameters:{type:'object',properties:{}},
    output:{schema:{type:'object'},render:(_args,value)=>[{type:'text',text:JSON.stringify(value)}]},
    execute:async()=>{
      reads++;
      if(failure && reads>12)throw new Error('ACCESS_DENIED');
      return {briefing_progress:{cursor:String(Math.min(reads,12)),complete:false},rows:[reads]};
    }});
  class Reader extends LlmAdapter {
    calls=0;
    async resolveModel(provider,model){return {provider,id:model,name:model};}
    async *stream() {
      const step=this.calls++;
      yield {type:'block-start',index:0,blockType:step<16?'tool-call':'text'};
      yield {type:'block-end',index:0,block:step<16
        ? {type:'tool-call',id:'batch-'+step,name:'business_read_many',arguments:'{}'}
        : {type:'text',text:'保留未完成状态。'}};
      yield {type:'finish',reason:{kind:step<16?'tool-calls':'stop'}};
    }
  }
  ctx.llm.registerAdapter(['reader'],new Reader());
  const harness=await mountAgentLoopTestHarness(ctx);
  const agent=await harness.create('page-progress',{provider:'reader',model:'reader'});
  agent.followup(createUserMessage({source:{kind:'user'},content:[{type:'text',text:'整理本期实验。'}]}));
  await agent.whenIdle();
  const events=agent.session.snapshotEvents();
  const reminders=events.filter(event=>event.type==='user/message' && event.data.source.kind==='repeat-tool-reminder');
  assert.equal(reads,16);
  assert.equal(reminders.length,failure?1:2);
  for(const reminder of reminders)assert.ok(events.filter(event=>event.type==='tool/result' && event.seq<reminder.seq).length>=14,
    'twelve genuinely advancing pages must never produce a loop warning');
  assert.equal(events.findLast(event=>event.type==='turn/end').data.reason.kind,'completed');
});

test('deployment replaces the argument-only detector with the native progress-aware adapter',()=>{
  const patch=enterprisePatch({plugin:'G:/enterprise',model:{provider:'reader',id:'reader'}});
  assert.equal(patch.find(row=>row.id==='repeat-tool-reminder').disabled,true);
  assert.ok(patch.flatMap(row=>row.insert||[]).some(row=>row.id==='enterprise-read-progress-reminder'));
});
