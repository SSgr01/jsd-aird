import test from 'node:test';
import assert from 'node:assert/strict';
import {prepareCompactionRequest} from '../../enterprise-plugin/src/compaction-request.mjs';
import {createRequire} from 'node:module';
import {pathToFileURL} from 'node:url';
import {mountAgentLoopTestDependencies,mountAgentLoopTestHarness} from '../../../vendor/deepseek-harness/packages/test-support/agent-loop-testkit/lib/index.js';
import {BasicCompactionEngine} from '../../../vendor/deepseek-harness/packages/compaction/compaction-basic/lib/index.js';
import TokenMeter from '../../../vendor/deepseek-harness/packages/llm/token-meter/lib/index.js';
import {LlmAdapter,createUserMessage} from '../../../vendor/deepseek-harness/packages/llm/llm/lib/index.js';

test('summary requests retain original evidence and images without executable history or conversation policies',()=>{
  const messages=[{role:'system',content:[{type:'text',text:'Large live tool instructions'}]},
    {role:'user',content:[{type:'text',text:'请整理批次，保留空白。'}]},
    {role:'assistant',content:[{type:'tool-call',id:'r',name:'business_read_many',arguments:'{}'}]},
    {role:'tool',toolCallId:'r',isError:false,content:[{type:'text',text:'实测 0；缺失 null；单位 g；固定版本 v1；[E:source]'},
      {type:'image',url:'https://example.invalid/page.png'}]},
    {role:'user',content:[{type:'text',text:'Native coding summary directive'}]}];
  const original=structuredClone(messages);
  const options={purpose:'compaction',provider:'qwen',model:'same',messages,tools:[{name:'business_read_many'}],toolHistory:{}};
  prepareCompactionRequest(options);
  assert.deepEqual(messages,original);
  assert.deepEqual(options.tools,[]);assert.equal(options.toolHistory,undefined);
  assert.deepEqual(options.messages.map(message=>message.role),['system','user']);
  const text=JSON.stringify(options.messages);
  assert.doesNotMatch(text,/Large live tool instructions|Native coding summary directive/);
  assert.match(text,/请整理批次，保留空白/);assert.match(text,/实测 0；缺失 null；单位 g；固定版本 v1；\[E:source\]/);
  const receipt=options.messages[1].content.filter(block=>block.type==='text').map(block=>{
    try{return JSON.parse(block.text);}catch{return {};}
  }).find(value=>value.role==='tool');
  assert.equal(receipt.toolCallId,'r');assert.equal(receipt.isError,false);
  assert.ok(options.messages[1].content.some(block=>block.type==='image'));
  const conversation={purpose:undefined,messages,tools:[{name:'business_read_many'}]};
  const before=structuredClone(conversation);prepareCompactionRequest(conversation);assert.deepEqual(conversation,before);
});

test('the real native summarizer uses the isolated transcript and lands its checkpoint',async t=>{
  const require=createRequire(new URL('../../../vendor/deepseek-harness/packages/fs/fs-local/package.json',import.meta.url));
  const {Context}=await import(pathToFileURL(require.resolve('@deepseek-ai/cordis')).href);
  const ctx=new Context();t.after(()=>ctx.fiber.dispose());
  await mountAgentLoopTestDependencies(ctx,{systemPrompt:{includeRuntimeContext:false}});
  await ctx.plugin(TokenMeter);
  ctx.on('llm/stream',(options,next)=>{prepareCompactionRequest(options);return next();});
  let summaries=0,reads=0,steps=0;
  const body='实测 0；空白 null；单位 g；固定版本 v1；[E:source]。'.repeat(2000);
  class Model extends LlmAdapter {
    async resolveModel(provider,model){return {provider,id:model,name:model,context:{contextWindow:1000000}};}
    async *stream(options) {
      let block;
      if(options.purpose==='compaction') {
        summaries++;
        assert.deepEqual(options.tools,[]);
        assert.deepEqual(options.messages.map(message=>message.role),['system','user']);
        assert.match(JSON.stringify(options.messages),/固定版本 v1/);
        block={type:'text',text:'已读固定版本 v1，实测 0，单位 g，缺失 null。[E:source] 后续继续下一批。'};
      } else {
        const step=steps++;
        if(step)assert.ok(options.messages.some(message=>message.role==='tool' && message.content.some(b=>b.text===body)));
        block=step<4?{type:'tool-call',id:'batch-'+step,name:'business_read_many',arguments:'{}'}:{type:'text',text:'整理完成。'};
      }
      yield {type:'block-start',index:0,blockType:block.type};
      yield {type:'block-end',index:0,block};
      yield {type:'finish',reason:{kind:block.type==='tool-call'?'tool-calls':'stop'}};
    }
  }
  ctx.llm.registerAdapter(['same'],new Model());
  ctx.tools.register({name:'business_read_many',description:'读取固定正文',parameters:{type:'object',properties:{}},
    output:{schema:{type:'string'},render:(_args,value)=>[{type:'text',text:value}]},execute:async()=>{reads++;return body;}});
  await ctx.plugin(BasicCompactionEngine,{thresholdRatio:0.06,retainTokens:1000,maxTokens:4096,compactionRetries:0});
  const harness=await mountAgentLoopTestHarness(ctx);const agent=await harness.create('isolated-summary',{provider:'same',model:'same'});
  agent.followup(createUserMessage({source:{kind:'user'},content:[{type:'text',text:'整理实验。'}]}));
  await agent.whenIdle();
  assert.equal(reads,4);assert.equal(steps,5);assert.ok(summaries>0);
  const events=agent.session.snapshotEvents();
  assert.equal(events.findLast(event=>event.type==='turn/end').data.reason.kind,'completed');
  assert.ok(events.some(event=>event.type==='compaction/summary'));
  assert.equal(events.filter(event=>event.type==='compaction/end' && event.data.error).length,0);
});
