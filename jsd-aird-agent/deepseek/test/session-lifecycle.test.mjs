import test from 'node:test';
import assert from 'node:assert/strict';
import {installSessionLifecycle} from '../../enterprise-plugin/src/session-lifecycle.mjs';
import {checkPluginRequest} from '../src/plugin-policy.mjs';
import {checkRpc} from '../src/rpc-policy.mjs';
import {readFileSync} from 'node:fs';
import {runInNewContext} from 'node:vm';

test('delete drains the native handle and business writes before the plugin removes files', async () => {
  const events = new Map(), order = [], agent = {id:'session-a'};
  const receiver = {scope:'native-caller'};
  const agents = {async create() { assert.equal(this, receiver); return {agent,dispose:async()=>{order.push('dispose');live=undefined;}}; },get:()=>live};
  let live = agent;
  const ctx = {agents,schedule:{catalog:async()=>[]},effect:fn=>fn(),on:(name,fn)=>events.set(name,fn)};
  installSessionLifecycle(ctx,{business:async(action,body)=>order.push(action+':'+body.phase),
    drain:async()=>order.push('drain'),clear:()=>order.push('clear')});
  await Reflect.apply(agents.create,receiver,[{sessionId:'session-a'}]);
  await events.get('enterprise/session-before-delete')('session-a');
  assert.deepEqual(order,['session-delete:prepare','dispose','drain']);
  await assert.rejects(()=>Reflect.apply(agents.create,receiver,[{sessionId:'session-a'}]),/正在删除/);
  await events.get('enterprise/session-deleted')('session-a');
  assert.deepEqual(order.slice(-2),['session-delete:commit','clear']);
  await assert.rejects(()=>events.get('enterprise/session-deleted')('../outside'),/SESSION_REQUIRED/);
});

test('a removed conversation leaves through native navigation once; live conversations stay open', () => {
  let plugin, component, options, nextCalls=0;
  const effects=[], handled={current:false};
  const React={createElement:()=>null,createContext:()=>({}),useRef:()=>handled,
    useEffect:fn=>effects.push(fn)};
  runInNewContext(readFileSync(new URL('../../enterprise-plugin/src/client.js',import.meta.url),'utf8'),{
    window:{__ModuleLoader__:{load:definition=>{plugin=definition.factory(name=>name==='react'?React:{});}}},
  });
  const scope={slots:{inject:(_name,fn)=>fn(),register:(spec,view)=>{options=spec;component=view;}},
    uiWorkspace:{startSession:()=>{nextCalls++;}}};
  plugin.apply({effect:()=>{},slots:{inject:()=>{}},inject:(_deps,fn)=>{
    if(fn.name==='registerDeletedSessionNavigation')fn(scope);
  }});
  const render=removed=>{
    component({...options.inject('session-a'),useSession:select=>select({removed})});
    while(effects.length)effects.shift()();
  };
  render(false);assert.equal(nextCalls,0);
  render(true);assert.equal(nextCalls,1);
  render(true);assert.equal(nextCalls,1);
  render(false);render(true);assert.equal(nextCalls,2);
});

test('sessions bound to a native schedule cannot be deleted underneath it', async () => {
  const events = new Map(); let admitted = false;
  const ctx={agents:{get:()=>undefined},schedule:{catalog:async()=>[{target:{kind:'session',sessionId:'session-a'}}]},
    effect:fn=>fn(),on:(name,fn)=>events.set(name,fn)};
  installSessionLifecycle(ctx,{business:async()=>{admitted=true;},drain:async()=>{},clear:()=>{}});
  await assert.rejects(()=>events.get('enterprise/session-before-delete')('session-a'),/自动化任务/);
  assert.equal(admitted,false);
});

test('only required plugin routes and safe settings pass the enterprise gateway', async () => {
  assert.equal(checkPluginRequest('/api/dsh-rewind/preview','POST',{sessionId:'session-a',seq:4}),'session-a');
  for(const [path,method,body] of [
    ['/api/dsh-rewind/preview','POST',{sessionId:'../other',seq:4}],
    ['/api/michengai/dsh-archive-manager/update','POST',{}],
    ['/api/michengai/dsh-archive-manager/open-in-explorer','POST',{path:'C:/'}],
    ['/api/michengai/dsh-archive-manager/online-update','POST',{enabled:true}],
  ]) assert.throws(()=>checkPluginRequest(path,method,body),/DENIED/);
  const rpc=(method,args)=>checkRpc(method,{type:'client-request',method,payload:{args}},{executionRoot:'G:/owner'});
  await rpc('workspaceRegistry/deleteSession',{sessionId:'session-a'});
  await rpc('settings/update',{ns:'dsh-rewind-plugin',patch:{fileCheckpointingEnabled:false}});
  await assert.rejects(()=>rpc('settings/update',{ns:'dsh-rewind-plugin',patch:{snapshotDir:'G:/other'}}));
  await assert.rejects(()=>rpc('workspaceRegistry/moveSessionToWorkspace',{}));
});
