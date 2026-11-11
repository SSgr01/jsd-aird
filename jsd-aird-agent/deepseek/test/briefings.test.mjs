import test from 'node:test';
import assert from 'node:assert/strict';
import {briefingDefaults,reconcileBriefings,NativeBriefings,beginBriefingPeriod} from '../../enterprise-plugin/src/briefings.mjs';
import {Session} from '../../../vendor/deepseek-harness/packages/core/session/lib/index.js';
import {createUserMessage,createSystemMessage} from '../../../vendor/deepseek-harness/packages/llm/llm/lib/index.js';

test('defaults use Beijing weekdays and readable business instructions',()=>{
  assert.deepEqual(briefingDefaults.map(task=>[task.time,task.weekdays]),[
    ['09:00:00',[1,2,3,4,5]],['16:00:00',[5]],['17:30:00',[1,2,3,4,5]],
  ]);
  for(const task of briefingDefaults)assert.doesNotMatch(task.title+task.prompt,/测试|验证|验收|模拟/);
});

test('each period retains the native transcript but cannot reuse stale evidence after restart',()=>{
  const session=Session.create('briefing-periods');
  session.append('system/message',{turn:0,step:0,message:createSystemMessage({content:[{type:'text',text:'研发助手'}]})},{surfaceOp:'append'});
  session.append('user/message',createUserMessage({source:{kind:'schedule'},content:[{type:'text',text:'前期正文与旧记录'}]}),{surfaceOp:'append'});
  session.append('turn/start',{turn:1});
  session.append('user/message',createUserMessage({source:{kind:'schedule'},content:[{type:'text',text:'本期任务'}]}),{surfaceOp:'append'});
  const original=session.snapshotEvents();
  beginBriefingPeriod({session},1,0,new AbortController().signal);
  const context=JSON.stringify(session.deriveMessages());
  assert.match(context,/研发助手/);assert.match(context,/本期任务/);
  assert.doesNotMatch(context,/前期正文与旧记录/);
  assert.deepEqual(session.snapshotEvents().slice(0,original.length),original);
  const restored=Session.create(session.id,session.snapshotEvents());
  const count=restored.snapshotEvents().length;
  beginBriefingPeriod({session:restored},1,1,new AbortController().signal);
  assert.equal(restored.snapshotEvents().length,count);
  assert.equal(JSON.stringify(restored.deriveMessages()),context);
});

test('existing schedules receive current research guidance on their first period without changing the schedule',()=>{
  const session=Session.create('existing-briefing');
  session.append('turn/start',{turn:1});
  session.append('user/message',createUserMessage({source:{kind:'schedule'},content:[{type:'text',text:'原有日报内容'}]}),{surfaceOp:'append'});
  beginBriefingPeriod({session},1,0,new AbortController().signal);
  const context=JSON.stringify(session.deriveMessages());
  assert.match(context,/原有日报内容/);assert.match(context,/get_experiment_loop/);assert.match(context,/get_compute_result/);
  const restored=Session.create(session.id,session.snapshotEvents());
  const count=restored.snapshotEvents().length;
  beginBriefingPeriod({session:restored},1,1,new AbortController().signal);
  assert.equal(restored.snapshotEvents().length,count);
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
