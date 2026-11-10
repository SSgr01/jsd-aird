import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { runInNewContext } from 'node:vm';

const client=readFileSync(new URL('../../enterprise-plugin/src/client.js',import.meta.url),'utf8');

function fixture(reason={kind:'aborted',reason:{kind:'user'}}) {
  const states=[], calls=[], notices=[];
  let hook=0, entry, spec;
  const React={createElement:(type,props,...children)=>({type,props:props || {},children}),
    createContext:()=>({}),useState:initial=>{
      const index=hook++;
      if (!(index in states)) states[index]=initial;
      return [states[index],value=>{states[index]=value;}];
    }};
  let plugin;
  runInNewContext(client,{window:{__ModuleLoader__:{load:definition=>{
    plugin=definition.factory(name=>name==='react'?React:{});
  }}}});
  const session={openState:'open',running:false,removed:false,blank:false,subagent:null,pendingSubmissions:[]};
  const input={phase:'plain',draft:'',attachmentIds:[]};
  const inbox={'next-turn':[],'next-step':[]};
  const events={entries:[{type:'event',event:{type:'turn/end',seq:19,data:{turn:1,reason}}}]};
  const source={getSnapshot:()=>events,subscribe:()=>()=>{}};
  let block, current;
  const binding={eventSource:source,session:{getSnapshot:()=>session,
    prompt:async(...args)=>{calls.push(args);return {ok:true,value:{accepted:true}};},
    projections:{faceOf:key=>({getSnapshot:()=>key==='inbox'?inbox:undefined})}}};
  current=binding;
  const scope={slots:{inject:(_name,fn)=>fn(),register:(options,component)=>{spec=options;entry=component;}},
    sessions:{binding:()=>current,scope:()=>({})},conversation:{
      input:{for:()=>({state:{getSnapshot:()=>input},notify:(level,text)=>notices.push({level,text})})},
      blocks:{storeFor:()=>({getSnapshot:()=>block,subscribe:()=>()=>{}})}}};
  plugin.apply({effect:()=>{},slots:{inject:()=>{}},
    inject:(_deps,fn)=>{if(fn.name==='registerContinuation')fn(scope);}});
  const injected=spec.inject('task-one');
  let allowed=true;
  const render=()=>{hook=0;return entry({...injected,
    useSession:select=>select(session),useInput:select=>select(input),
    useEvents:select=>select(injected.hooks.events.getSnapshot()),
    useComposerBlock:select=>select(injected.hooks.composerBlock.getSnapshot()),useAccess:()=>allowed});};
  return {session,input,events,inbox,binding,calls,notices,render,
    setBlock:value=>{block=value;},setAllowed:value=>{allowed=value;},setBinding:value=>{current=value;}};
}

const action=tree=>tree;

test('offers continuation for stopped, interrupted and truncated tasks, leaving normal completion and policy stops alone',()=>{
  for (const reason of [
    {kind:'aborted',reason:{kind:'user'}},{kind:'aborted',reason:{kind:'disposed'}},
    {kind:'aborted',reason:{kind:'legacy'}},{kind:'interrupted'},{kind:'max-tokens'},
  ]) assert.equal(action(fixture(reason).render()).props['aria-label'],'继续任务');
  for (const reason of [
    {kind:'completed'},{kind:'blocked'},{kind:'forked'},{kind:'error',error:{code:'SERVER'}},
    {kind:'aborted',reason:{kind:'hook'}},{kind:'aborted',reason:{kind:'parent'}},
  ]) assert.equal(fixture(reason).render(),null);
  const f=fixture();
  f.events.entries.push({type:'event',event:{type:'turn/start',seq:20,data:{turn:2}}});
  assert.equal(f.render(),null);
});

test('uses the primary action slot and continues once, preserving a draft added during admission',async()=>{
  const f=fixture();
  let release;
  f.binding.session.prompt=(...args)=>{f.calls.push(args);return new Promise(resolve=>{release=resolve;});};
  const click=action(f.render()).props.onClick;
  const first=click(), second=click();
  assert.equal(f.calls.length,1);
  assert.equal(action(f.render()).props.disabled,true);
  f.input.draft='等待续接时写下的补充说明';
  f.input.attachmentIds.push('new-draft-file');
  const before=structuredClone(f.input);
  assert.equal(f.render(),null);
  release({ok:true,value:{accepted:true}});
  await Promise.all([first,second]);
  assert.equal(JSON.stringify(f.calls[0]),JSON.stringify([
    [{type:'text',text:'继续'}],'queue',undefined,'enterprise-continue-task-one-19',
  ]));
  assert.deepEqual(f.input,before);
  f.input.draft='';f.input.attachmentIds=[];
  assert.equal(action(f.render()).props.disabled,true);
  f.session.running=true;
  assert.equal(f.render(),null);
  f.session.running=false;
  f.events.entries.push({type:'event',event:{type:'turn/end',seq:32,data:{turn:2,reason:{kind:'aborted',reason:{kind:'user'}}}}});
  assert.equal(action(f.render()).props.disabled,false);
});

test('rejects stale clicks after another turn, binding replacement or concurrent submission',async()=>{
  for (const change of [
    f=>{f.session.running=true;},f=>{f.session.pendingSubmissions.push({requestId:'other'});},
    f=>{f.input.phase='sending';},f=>f.setBlock({reason:'请选择模型'}),f=>f.setBinding({}),
    f=>{f.input.draft='新的任务';},f=>{f.input.attachmentIds.push('draft-file');},
    f=>f.events.entries.push({type:'event',event:{type:'turn/start',seq:20,data:{turn:2}}}),
  ]) {
    const f=fixture(), click=action(f.render()).props.onClick;
    change(f);
    await click();
    assert.equal(f.calls.length,0);
  }
});

test('respects native lifecycle, access and input locks',()=>{
  for (const patch of [{running:true},{removed:true},{blank:true},{openState:'loading'},{subagent:{}}]) {
    const f=fixture();Object.assign(f.session,patch);assert.equal(f.render(),null);
  }
  const f=fixture();f.setAllowed(false);assert.equal(f.render(),null);
  f.setAllowed(true);f.input.phase='claimed';assert.equal(action(f.render()).props.disabled,true);
  f.input.phase='plain';f.setBlock({reason:'请选择模型'});assert.equal(action(f.render()).props.disabled,true);
});

test('leaves the native send arrow in place when a paused session has text or attachments',()=>{
  const f=fixture();
  f.input.draft='发送补充说明';assert.equal(f.render(),null);
  f.input.draft='';f.input.attachmentIds=['draft-file'];assert.equal(f.render(),null);
  f.input.attachmentIds=[];f.input.draft='  \n ';
  assert.equal(action(f.render()).props['aria-label'],'继续任务');
});

test('failed or lost admissions leave input intact and retry with the same native request identity',async()=>{
  const f=fixture(), before=structuredClone(f.input);
  f.binding.session.prompt=async(...args)=>{f.calls.push(args);throw new Error('connection lost');};
  await action(f.render()).props.onClick();
  assert.equal(f.notices.length,1);
  assert.equal(action(f.render()).props.disabled,false);
  f.binding.session.prompt=async(...args)=>{f.calls.push(args);return {ok:false,error:{code:'session/writer-held'}};};
  await action(f.render()).props.onClick();
  assert.equal(action(f.render()).props.disabled,false);
  assert.equal(f.calls[0][3],f.calls[1][3]);
  assert.deepEqual(f.input,before);
});
