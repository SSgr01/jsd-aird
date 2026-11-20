import test from 'node:test';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {pathToFileURL} from 'node:url';
import {mountAgentLoopTestDependencies,mountAgentLoopTestHarness} from '../../../vendor/deepseek-harness/packages/test-support/agent-loop-testkit/lib/index.js';
import {BasicCompactionEngine} from '../../../vendor/deepseek-harness/packages/compaction/compaction-basic/lib/index.js';
import TokenMeter from '../../../vendor/deepseek-harness/packages/llm/token-meter/lib/index.js';
import {LlmAdapter,createUserMessage} from '../../../vendor/deepseek-harness/packages/llm/llm/lib/index.js';
import {standardPlugins} from '../src/config.mjs';
import {beginBriefingPeriod,installBriefingPeriods} from '../../enterprise-plugin/src/briefings.mjs';

const require=createRequire(new URL('../../../vendor/deepseek-harness/packages/fs/fs-local/package.json',import.meta.url));
const {Context}=await import(pathToFileURL(require.resolve('@deepseek-ai/cordis')).href);

test('new background periods archive the old surface before native compaction prices it',async t=>{
  const ctx=new Context();t.after(()=>ctx.fiber.dispose());
  await mountAgentLoopTestDependencies(ctx,{systemPrompt:{includeRuntimeContext:false}});
  await ctx.plugin(TokenMeter);
  const requests=[],oldBody='上一周期实验配方及结果。'.repeat(25000);
  class PeriodModel extends LlmAdapter {
    async resolveModel(provider,model){return {provider,id:model,name:model,context:{contextWindow:1000000}};}
    async *stream(options) {
      requests.push(options);
      const text=requests.length===1?oldBody:'本期依据尚需重新读取。';
      yield {type:'block-start',index:0,blockType:'text'};
      yield {type:'block-end',index:0,block:{type:'text',text}};
      yield {type:'finish',reason:{kind:'stop'}};
    }
  }
  class PeriodCompaction extends BasicCompactionEngine {
    async summarize(){assert.fail('old-period bodies must be archived before any summarization');}
  }
  ctx.llm.registerAdapter(['period'],new PeriodModel());
  const turns=new Map();
  ctx.on('agent/inbox/claimed',({agent,turn})=>turns.set(agent.id,turn));
  // The production binder runs here: tool conditions resolve before the
  // pre-step compaction middleware, regardless of middleware registration order.
  ctx.tools.register({name:'business_read_many',description:'读取本期固定正文',parameters:{type:'object',properties:{}},
    output:{schema:{type:'object'},render:()=>[{type:'text',text:'正文'}]},
    execute:async()=>({})},{when:({agent,signal})=>{
      beginBriefingPeriod({agent,turn:turns.get(agent.id),signal},{kind:'enter',messages:[]});
      return true;
    }});
  installBriefingPeriods(ctx,{binding:()=>({automation:true})});
  await ctx.plugin(PeriodCompaction,{thresholdRatio:0.06,retainTokens:1000,maxTokens:4096});
  const harness=await mountAgentLoopTestHarness(ctx);
  const agent=await harness.create('period-compaction',{provider:'period',model:'period'});
  for(const text of ['上期研发周报','本期研发周报']) {
    agent.followup(createUserMessage({source:{kind:'schedule'},content:[{type:'text',text}]}));
    await agent.whenIdle();
    assert.equal(agent.session.snapshotEvents().findLast(event=>event.type==='turn/end')?.data.reason.kind,'completed');
  }
  assert.equal(requests.length,2);
  assert.doesNotMatch(JSON.stringify(requests[1].messages),/上一周期实验配方及结果|上期研发周报/);
  assert.match(JSON.stringify(requests[1].messages),/本期研发周报|enterprise-briefing-period/);
  assert.ok(agent.session.snapshotEvents().some(event=>event.type==='assistant/message' && JSON.stringify(event).includes(oldBody)));
  assert.equal(agent.session.snapshotEvents().filter(event=>event.type==='compaction/start').length,0);
});

test('business pages converge through native compaction without losing the fresh body before its first model request',async t=>{
  const ctx=new Context();t.after(()=>ctx.fiber.dispose());
  await mountAgentLoopTestDependencies(ctx,{systemPrompt:{includeRuntimeContext:false}});
  await ctx.plugin(TokenMeter);
  ctx.effect(()=>ctx.systemPrompt.section({name:'business-policy',order:0,text:'Fixed enterprise policy. '.repeat(4000)}));
  const bodies=Array.from({length:12},(_,index)=>JSON.stringify({index,rows:'原值 0；空白 null；单位 g；'.repeat(1500)}));
  const summaries=[];
  class BusinessCompaction extends BasicCompactionEngine {
    async summarize(input) {
      const consumed=input.messages.filter(message=>message.role==='tool');
      assert.ok(consumed.length,'do not repeatedly compact a summary alone');
      summaries.push(consumed.length);
      return {summary:[{type:'text',text:'Earlier fixed records were read; preserve original measurements and version links.'}],provider:'business',model:'business'};
    }
  }
  class BusinessModel extends LlmAdapter {
    calls=0;
    async resolveModel(provider,model){return {provider,id:model,name:model,context:{contextWindow:1000000}};}
    async *stream(options) {
      const step=this.calls++;
      if(step)assert.ok(options.messages.some(message=>message.role==='tool'
        && message.content.some(block=>block.type==='text' && block.text===bodies[step-1])),
      'the complete newest page must reach its first actual request');
      yield {type:'block-start',index:0,blockType:step<12?'tool-call':'text'};
      yield {type:'block-end',index:0,block:step<12
        ? {type:'tool-call',id:'page-'+step,name:'business_read_many',arguments:'{}'}
        : {type:'text',text:'已读完全部记录。'}};
      yield {type:'finish',reason:{kind:step<12?'tool-calls':'stop'}};
    }
  }
  const model=new BusinessModel();ctx.llm.registerAdapter(['business'],model);
  let read=0;
  ctx.tools.register({name:'business_read_many',description:'固定版本正文',parameters:{type:'object',properties:{}},
    output:{schema:{type:'string'},render:(_args,value)=>[{type:'text',text:value}]},execute:async()=>bodies[read++]});
  const configured=standardPlugins({plugin:'G:/enterprise',model:{provider:'business',id:'business'}})
    .find(row=>row.id==='compaction').config.find(row=>row.id==='compaction-basic').config;
  await ctx.plugin(BusinessCompaction,configured);
  const harness=await mountAgentLoopTestHarness(ctx);
  const agent=await harness.create('business-compaction',{provider:'business',model:'business'});
  agent.followup(createUserMessage({source:{kind:'user'},content:[{type:'text',text:'请汇总这些实验的配方、条件和结果。'}]}));
  await agent.whenIdle();
  assert.equal(agent.session.snapshotEvents().findLast(event=>event.type==='turn/end')?.data.reason.kind,'completed');
  assert.equal(read,12);assert.equal(model.calls,13);
  assert.ok(summaries.length>0 && summaries.length<read);
  assert.equal(agent.session.snapshotEvents().filter(event=>event.type==='compaction/end' && event.data.error).length,0);
});
