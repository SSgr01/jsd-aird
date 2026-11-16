import test from 'node:test';
import assert from 'node:assert/strict';
import {briefingDefaults,reconcileBriefings,NativeBriefings,beginBriefingPeriod,installBriefingPeriods} from '../../enterprise-plugin/src/briefings.mjs';
import {Session} from '../../../vendor/deepseek-harness/packages/core/session/lib/index.js';
import {sessionFormatCatalog} from '../../../vendor/deepseek-harness/packages/session/session-format-catalog/lib/index.js';
import {createUserMessage,createSystemMessage,LlmAdapter} from '../../../vendor/deepseek-harness/packages/llm/llm/lib/index.js';
import {createRequire} from 'node:module';
import {pathToFileURL} from 'node:url';
import {mountAgentLoopTestDependencies,mountAgentLoopTestHarness} from '../../../vendor/deepseek-harness/packages/test-support/agent-loop-testkit/lib/index.js';

function restore(session) {
  const reader=sessionFormatCatalog.createRestore(sessionFormatCatalog.encodeCurrentHeader({...session.header,delegationDepth:0},0),{recovery:'strict',validation:'current'});
  for(const event of session.snapshotEvents())reader.decodeRow(sessionFormatCatalog.encodeCurrentEvent(event));
  const artifact=reader.finish();
  return Session.create(session.id,artifact.events,artifact.header);
}

test('defaults use Beijing weekdays and readable business instructions',()=>{
  assert.deepEqual(briefingDefaults.map(task=>[task.time,task.weekdays]),[
    ['09:00:00',[1,2,3,4,5]],['16:00:00',[5]],['17:30:00',[1,2,3,4,5]],
  ]);
  for(const task of briefingDefaults)assert.doesNotMatch(task.title+task.prompt,/测试|验证|验收|模拟/);
});

test('each period retains the native transcript but cannot reuse stale evidence after restart',()=>{
  const session=Session.create('briefing-periods');
  session.append('turn/start',{turn:1});
  session.append('step/start',{turn:1,step:1});
  session.append('system/message',{turn:1,step:1,message:createSystemMessage('研发助手')},{surfaceOp:'append'});
  session.append('user/message',createUserMessage({source:{kind:'schedule'},content:[{type:'text',text:'前期正文与旧记录'}]}),{surfaceOp:'append'});
  session.append('step/end',{turn:1,step:1});
  session.append('turn/end',{turn:1,reason:{kind:'completed'}});
  session.append('turn/start',{turn:2});
  const original=session.snapshotEvents();
  const decision=beginBriefingPeriod({agent:{session},turn:2,signal:new AbortController().signal},
    {kind:'enter',messages:[createUserMessage({source:{kind:'schedule'},content:[{type:'text',text:'本期任务'}]})]});
  session.append('step/start',{turn:2,step:1});
  for(const message of decision.messages)session.append('user/message',message,{surfaceOp:'append'});
  const context=JSON.stringify(session.deriveMessages());
  assert.match(context,/研发助手/);assert.match(context,/本期任务/);
  assert.doesNotMatch(context,/前期正文与旧记录/);
  assert.deepEqual(session.snapshotEvents().slice(0,original.length),original);
  const restored=restore(session);
  const count=restored.snapshotEvents().length;
  beginBriefingPeriod({agent:{session:restored},turn:2},{kind:'enter',messages:[]});
  assert.equal(restored.snapshotEvents().length,count);
  assert.equal(JSON.stringify(restored.deriveMessages()),context);
});

test('existing schedules receive current research guidance on their first period without changing the schedule',()=>{
  const session=Session.create('existing-briefing');
  session.append('turn/start',{turn:1});
  const decision=beginBriefingPeriod({agent:{session},turn:1,signal:new AbortController().signal},
    {kind:'enter',messages:[createUserMessage({source:{kind:'schedule'},content:[{type:'text',text:'原有日报内容'}]})]});
  assert.equal(session.snapshotEvents().length,1);
  session.append('step/start',{turn:1,step:1});
  session.append('system/message',{turn:1,step:1,message:createSystemMessage('研发助手')},{surfaceOp:'append'});
  for(const message of decision.messages)session.append('user/message',message,{surfaceOp:'append'});
  const context=JSON.stringify(session.deriveMessages());
  assert.match(context,/原有日报内容/);assert.match(context,/get_experiment_loop/);assert.match(context,/get_compute_result/);
  const restored=restore(session);
  const count=restored.snapshotEvents().length;
  beginBriefingPeriod({agent:{session:restored},turn:1},{kind:'enter',messages:[]});
  assert.equal(restored.snapshotEvents().length,count);
});

test('the mounted native loop admits each period after its system prompt and restores the exact request surface',async t=>{
  const require=createRequire(new URL('../../../vendor/deepseek-harness/packages/core/tools/package.json',import.meta.url));
  const {Context}=await import(pathToFileURL(require.resolve('@deepseek-ai/cordis')).href);
  const ctx=new Context();t.after(()=>ctx.fiber.dispose());
  await mountAgentLoopTestDependencies(ctx,{systemPrompt:{includeRuntimeContext:false}});
  const requests=[];
  class ReportAdapter extends LlmAdapter {
    async resolveModel(provider,model){return {provider,id:model,name:model};}
    async *stream(options){
      requests.push(options);
      yield {type:'block-start',index:0,blockType:'text'};
      yield {type:'text-delta',index:0,text:'本期内容'+requests.length};
      yield {type:'block-end',index:0,block:{type:'text',text:'本期内容'+requests.length}};
      yield {type:'finish',reason:{kind:'stop'}};
    }
  }
  ctx.llm.registerAdapter(['report'],new ReportAdapter());
  let background=true;
  installBriefingPeriods(ctx,{binding:()=>({automation:background})});
  const harness=await mountAgentLoopTestHarness(ctx);
  const agent=await harness.create('mounted-briefing',{provider:'report',model:'report'});
  for(const text of ['前期资料','当期资料']){
    agent.followup(createUserMessage({source:{kind:'schedule'},content:[{type:'text',text}]}));
    await agent.whenIdle();
    assert.equal(agent.session.snapshotEvents().at(-1).data.reason.kind,'completed');
    assert.deepEqual(restore(agent.session).deriveMessages(),agent.session.deriveMessages());
  }
  assert.equal(requests.length,2);
  assert.equal(requests[0].messages[0].role,'system');
  assert.match(JSON.stringify(requests[0].messages),/enterprise-briefing-period/);
  assert.match(JSON.stringify(requests[1].messages),/当期资料/);
  assert.doesNotMatch(JSON.stringify(requests[1].messages),/前期资料|本期内容1/);
  assert.match(JSON.stringify(agent.session.snapshotEvents()),/前期资料|本期内容1/);
  background=false;
  agent.followup(createUserMessage({source:{kind:'user'},content:[{type:'text',text:'解释上期建议'}]}));
  await agent.whenIdle();
  assert.match(JSON.stringify(requests[2].messages),/本期内容2/);
  assert.equal(agent.session.snapshotEvents().filter(event=>event.type==='user/message' && event.data.source.kind==='enterprise-briefing-period').length,2);
  assert.deepEqual(restore(agent.session).deriveMessages(),agent.session.deriveMessages());
});

test('reconciliation keeps native edits, pauses and deletions without matching titles',()=>{
  const plan={tasks:{morning:{builtin_id:'project-morning',native_id:'native-morning',session_id:'session-morning'},
    weekly:{builtin_id:'research-weekly',native_id:'native-weekly',session_id:'session-weekly'},
    review:{builtin_id:'experiment-review',native_id:null,session_id:'session-review',status:'INSTALLING'}}};
  const rows=reconcileBriefings(plan,[{id:'native-morning',title:'自定标题',prompt:'自定内容',status:'paused',target:{kind:'session',sessionId:'session-morning'}},
    {id:'same-title',title:'研发周报',status:'active',target:{kind:'session',sessionId:'unrelated'}},
    {id:'committed-review',title:'复盘',status:'active',target:{kind:'session',sessionId:'session-review'}}]);
  assert.deepEqual(rows.map(row=>[row.native_id,row.status]),[['native-morning','paused'],['native-weekly','deleted'],['committed-review','active']]);
  assert.equal(rows[0].native.prompt,'自定内容');
  assert.equal(reconcileBriefings(plan,[{id:'native-morning',status:'active',target:{kind:'session',sessionId:'other'}}])[0].status,'target_changed');
});

test('a lost native create response reconciles the reserved session and never creates twice',async()=>{
  let creates=0, sessions=0;
  const native=[];
  let plan={tasks:{'project-morning':{builtin_id:'project-morning',session_id:'stable-session',native_id:null,status:'INSTALLING'}}};
  const ctx={sessionController:{projections:async()=>undefined,create:async()=>{sessions++;},rename:async request=>assert.equal(request.title,'项目晨报')},
    schedule:{catalog:async()=>native,create:async request=>{creates++;native.push({id:'committed',status:'active',target:request.target});throw new Error('response lost');}}};
  const adapter=new NativeBriefings(ctx,{executionRoot:'managed',asBackground:fn=>fn(),business:async(action,body)=>{
    if(action==='builtin-start')return {claimed:true};
    if(action==='builtin-sync')for(const task of body.tasks)plan.tasks[task.builtin_id]=task;
    return structuredClone(plan);
  }});
  await Promise.all([adapter.sync({install:true}),adapter.sync({install:true})]);
  await adapter.sync({install:true});
  assert.equal(creates,1);assert.equal(sessions,1);
  assert.equal(plan.tasks['project-morning'].native_id,'committed');
});

test('an uncertain creation stays pending when native readback finds no task',async()=>{
  let creates=0, claimed=false;
  const plan={tasks:{'project-morning':{builtin_id:'project-morning',session_id:'reserved',status:'INSTALLING'}}};
  const ctx={sessionController:{projections:async()=>({values:{}})},schedule:{catalog:async()=>[],create:async()=>{creates++;throw new Error('lost');}}};
  const adapter=new NativeBriefings(ctx,{business:async action=>{
    if(action==='builtin-start'){const first=!claimed;claimed=true;return {claimed:first};}
    return structuredClone(plan);
  }});
  await assert.rejects(adapter.sync({install:true}),/lost/);
  await assert.rejects(adapter.sync({install:true}),/RECONCILIATION_REQUIRED/);
  assert.equal(creates,1);
});

test('only actual native delivery receipts bind a background turn; a schedule notice never does',async()=>{
  const saved={builtin_id:'experiment-review',native_id:'native-review',session_id:'session-review',status:'active'};
  const receipts=[{messageId:'delivered-message',targetSessionId:'session-review',scheduledAt:'2026-11-06T09:30:00Z',deliveredAt:'2026-11-06T09:30:01Z'}];
  const adapter=new NativeBriefings({schedule:{history:async()=>({records:receipts})}},
    {business:async()=>{},asBackground:fn=>fn()});
  adapter.plan={tasks:{review:saved}};adapter.sync=async()=>{};
  const message=id=>({id,source:{kind:'schedule'}});
  const result=await adapter.delivery('session-review',{turn:2,messages:[message('delivered-message')]});
  assert.equal(result.message_id,'delivered-message');assert.equal(result.task_id,'native-review');
  assert.equal(await adapter.delivery('session-review',{turn:2,messages:[]}),result);
  assert.equal(await adapter.delivery('session-review',{turn:3,messages:[{id:'human',source:{kind:'user'}}]}),undefined);
  assert.equal(await adapter.delivery('session-review',{turn:4,messages:[message('change-notice')]}),undefined);
  saved.status='ended';
  assert.equal((await adapter.delivery('session-review',{turn:6,messages:[message('delivered-message')]})).message_id,'delivered-message');
  await assert.rejects(adapter.delivery('session-review',{turn:5,messages:[message('delivered-message'),{source:{kind:'user'}}]}),/MIXED_INPUT/);
});
