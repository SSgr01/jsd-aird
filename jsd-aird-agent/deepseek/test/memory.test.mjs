import test from 'node:test';
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {Session} from '../../../vendor/deepseek-harness/packages/core/session/lib/index.js';
import {createUserMessage,createSystemMessage,createAssistantMessage,createDeveloperMessage,createToolResultMessage} from '../../../vendor/deepseek-harness/packages/llm/llm/lib/index.js';
import {NativeMemory} from '../../enterprise-plugin/src/memory.mjs';
import {registerMemoryUI} from '../../enterprise-plugin/src/memory-client.mjs';
import {assertReleasedV4Relationships} from '../../../vendor/deepseek-harness/packages/session/session-format-v3-to-v4/lib/index.js';

function fixture() {
  const session=Session.create('memory-session');
  session.append('system/message',{turn:0,step:0,message:createSystemMessage({content:[{type:'text',text:'企业规则'}]})},{surfaceOp:'append'});
  const agent={id:session.id,session},calls=[];
  let valid=true,revision=1;
  const ctx={agents:{get:()=>agent},logger:{warn:()=>{}}};
  const runs=new Map([[agent.id,{id:'run',dsh_turn:1}]]);
  const entries=()=>[{id:'entry-one',content_revision:revision,text:'以后请用中文回答。',source:{kind:'manual'}}];
  const business=async(action,body)=>{
    calls.push({action,body});
    if(body.action==='initialize')return {read_enabled:true};
    if(body.action==='validate')return {valid,generation:1};
    if(body.action==='search')return {entries:entries(),generation:1};
    return {};
  };
  const memory=new NativeMemory(ctx,{business,runs,ready:Promise.resolve()});
  const prepare=memory.prepare.bind(memory);
  // The native loop admits returned messages only after opening its step.
  memory.prepare=async(...args)=>{
    const decision=await prepare(...args);
    for(const message of decision?.messages || [])if(message.source?.kind==='enterprise-personal-memory')
      session.append('user/message',message,{surfaceOp:'append'});
    return decision;
  };
  const payload={agent,turn:1,step:1,messages:[createUserMessage({source:{kind:'user'},content:[{type:'text',text:'你好'}]})]};
  return {memory,agent,calls,payload,runs,entries,setValid:v=>{valid=v;},setRevision:v=>{revision=v;}};
}

test('new sessions receive one sourced bounded snapshot; tool continuations and model switches deduplicate',async()=>{
  const f=fixture(),decision={kind:'continue',messages:f.payload.messages};
  await f.memory.prepare(f.payload,decision,{});
  const message=f.agent.session.deriveMessages().find(m=>m.source?.kind==='enterprise-personal-memory');
  assert.match(message.content[0].text,/中文/);
  assert.ok(Buffer.byteLength(message.content[0].text)<=800);
  assert.deepEqual(message.source.entries,[{id:'entry-one',content_revision:1}]);
  const count=f.calls.filter(c=>c.body.action==='search').length;
  await f.memory.prepare({...f.payload,step:2,messages:[]},{kind:'continue',messages:[]},{});
  assert.equal(f.calls.filter(c=>c.body.action==='search').length,count);
  await f.memory.prepare({...f.payload,turn:2},decision,{});
  assert.equal(f.agent.session.deriveMessages().filter(m=>m.source?.kind==='enterprise-personal-memory').length,1);
});

test('revocation blocks a frozen stale provider request, rebuilds the native surface, and survives replay',async()=>{
  const f=fixture();
  await f.memory.prepare(f.payload,{kind:'continue',messages:f.payload.messages},{});
  f.agent.session.append('assistant/message',{turn:1,step:1,stream:[],message:createAssistantMessage({source:{provider:'test',model:'one'},content:[{type:'text',text:'衍生旧记忆'}]})},{surfaceOp:'append'});
  f.agent.session.append('user/message',createUserMessage({source:{kind:'user'},content:[{type:'text',text:'继续本轮请求'}]}),{surfaceOp:'append'});
  const options=Object.freeze({sessionId:f.agent.id,messages:Object.freeze(f.agent.session.deriveMessages())});
  await f.memory.guardRequest(options);
  f.setValid(false);
  await assert.rejects(f.memory.guardRequest(options),{code:'MEMORY_CONTEXT_CHANGED'});
  const fresh=Object.freeze({sessionId:f.agent.id,messages:Object.freeze(f.agent.session.deriveMessages())});
  await f.memory.guardRequest(fresh);
  assert.doesNotMatch(JSON.stringify(fresh.messages),/中文|衍生旧记忆/);
  assert.match(JSON.stringify(fresh.messages),/继续本轮请求|企业规则/);
  const restored=Session.create(f.agent.id,f.agent.session.snapshotEvents());
  assert.doesNotMatch(JSON.stringify(restored.deriveMessages()),/衍生旧记忆|以后请用中文/);
  assert.equal(f.memory.references(restored).length,0);
});

test('a memory tool result and compacted descendants remain subject to validation after restart',async()=>{
  const f=fixture();
  f.agent.session.append('tool/result',{turn:1,step:1,message:createToolResultMessage({callId:'memory-call',isError:false,content:[{type:'text',text:JSON.stringify({entries:f.entries(),memory_context:true})}]})},{surfaceOp:'append'});
  const seq=f.agent.session.surface.nodes.at(-1);
  f.agent.session.append('developer/message',{turn:1,step:2,message:createDeveloperMessage({source:{kind:'compaction'},content:[{type:'text',text:'压缩了旧记忆'}]})},
    {surfaceOp:{op:'replace',startSeq:seq,endSeq:seq},sourceEventSeqs:[seq]});
  f.setValid(false);
  assert.equal(await f.memory.validate(f.agent),true);
  assert.doesNotMatch(JSON.stringify(f.agent.session.deriveMessages()),/压缩了旧记忆/);
});

test('capture submits only this fork own genuine turn events',async()=>{
  const f=fixture();
  f.agent.session.append('user/message',createUserMessage({source:{kind:'user'},content:[{type:'text',text:'继承的偏好'}]}),{surfaceOp:'append'});
  f.agent.session.append('turn/start',{turn:1});
  f.agent.session.append('user/message',createUserMessage({source:{kind:'enterprise-briefing'},content:[{type:'text',text:'自动简报'}]}),{surfaceOp:'append'});
  const own=f.agent.session.append('user/message',f.payload.messages[0],{surfaceOp:'append'});
  await f.memory.capture(f.agent,{id:'run',dsh_turn:1});
  const captured=f.calls.find(c=>c.body.action==='capture').body.events;
  assert.deepEqual(captured.map(e=>e.seq),[own.seq]);
});

test('history uses sessionQuery exact event and rejects assistant claims or changed hashes',async()=>{
  const f=fixture(),text='以后请用中文回答。';
  const event={seq:5,type:'user/message',data:createUserMessage({source:{kind:'user'},content:[{type:'text',text:'请记住：'+text}]})};
  f.memory.ctx.sessionQuery={readEvent:async request=>{assert.deepEqual(request,{sessionId:'source',seq:5,before:0,after:0});return {target:event};}};
  const source={entry_id:'one',session_id:'source',event_seq:5,hash:createHash('sha256').update(JSON.stringify(text)).digest('hex'),expected_text:text};
  assert.equal((await f.memory.history({sources:[source]})).entries[0].text,text);
  assert.equal((await f.memory.history({sources:[{...source,hash:'changed'}]})).entries.length,0);
  event.type='assistant/message';
  assert.equal((await f.memory.history({sources:[source]})).entries.length,0);
});

test('unavailable memory does not stop ordinary work, while unavailable validation removes private context',async()=>{
  const f=fixture();
  await f.memory.prepare(f.payload,{kind:'continue',messages:f.payload.messages},{});
  f.memory.business=async()=>{throw new Error('offline');};
  await f.memory.prepare(f.payload,{kind:'continue',messages:f.payload.messages},{});
  assert.doesNotMatch(JSON.stringify(f.agent.session.deriveMessages()),/以后请用中文/);
});

test('memory settings stay in settings and session controls open only from native menus',()=>{
  const slots=[],menus=[];
  registerMemoryUI({uiWorkspace:{},effect:fn=>fn(),contextMenus:{register:menu=>menus.push(menu)},
    slots:{inject:(_slot,fn)=>fn(),register:(spec,component)=>slots.push({spec,component})}},
    {React:{createElement:(type,props,...children)=>({type,props,children}),useSyncExternalStore:(_subscribe,snapshot)=>snapshot()},
      api:()=>assert.fail('Opening the menu must not require a memory request')});
  assert.deepEqual(slots.map(({spec})=>spec.name),['settings.section','shell.overlay','contextMenu.icon','settings.section.icon']);
  assert.equal(slots[3].spec.id,slots[0].spec.id);
  assert.equal(slots[3].component,slots[2].component);
  assert.equal(slots[0].spec.label(),'记忆');
  const overlay=slots[1].component;
  assert.equal(overlay().children[1],null);
  for(const target of ['sessionHeader','session']) {
    const [entry]=menus.find(menu=>menu.target===target).resolve({sessionId:target+'-selected'});
    assert.equal(entry.label,'本会话记忆');
    assert.equal(entry.icon.key,slots[2].spec.key);
    entry.run();
    assert.equal(overlay().children[1].props.sessionId,target+'-selected');
  }
});

test('memory admission and invalidation satisfy durable V4 lifecycle relationships',async()=>{
  const f=fixture(),session=Session.create('persisted-memory');
  f.agent.session=session;
  session.append('turn/start',{turn:1});
  // Exercise prepare before step/start, exactly as the native loop does.
  const decision=await NativeMemory.prototype.prepare.call(f.memory,{...f.payload,agent:f.agent},
    {kind:'enter',messages:f.payload.messages},{});
  assert.equal(session.snapshotEvents().some(e=>e.type==='developer/message'),false);
  session.append('step/start',{turn:1,step:1});
  session.append('system/message',{turn:1,step:1,message:createSystemMessage({content:[{type:'text',text:'企业规则'}]})},{surfaceOp:'append'});
  for(const message of decision.messages)session.append('user/message',message,{surfaceOp:'append'});
  session.append('step/end',{turn:1,step:1});
  session.append('turn/end',{turn:1,reason:{kind:'completed'}});
  f.setValid(false);
  await f.memory.validate(f.agent);
  const events=session.snapshotEvents();
  const artifact={header:{formatVersion:4},events,inheritedEventCount:0};
  assertReleasedV4Relationships(artifact,new Set(events.map(e=>e.type)));
  assert.doesNotMatch(JSON.stringify(Session.create(session.id,events).deriveMessages()),/以后请用中文/);
});
