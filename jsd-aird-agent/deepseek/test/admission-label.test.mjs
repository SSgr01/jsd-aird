import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { runInNewContext } from 'node:vm';

const client=readFileSync(new URL('../../enterprise-plugin/src/client.js',import.meta.url),'utf8');

function fixture(fetch) {
  let plugin,provider,component,registration;
  const React={createElement:(type,props,...children)=>({type,props,children}),createContext:()=>({})};
  runInNewContext(client,{URL,fetch,setTimeout,clearTimeout,window:{__ModuleLoader__:{load:definition=>{
    plugin=definition.factory(name=>name==='react'?React:{});
  }}}});
  const scope={effect:fn=>fn(),resources:{register:value=>{provider=value;return ()=>{};}},
    slots:{inject:(_name,fn)=>fn(),register:(spec,entry)=>{registration=spec;component=entry;}}};
  plugin.apply({effect:()=>{},slots:{inject:()=>{}},inject:(_deps,fn)=>{
    if(fn.name==='registerAdmissionLabel')fn(scope);
  }});
  return {provider,registration,render:run=>component({sessionId:'session-one',label:'深度求索中',
    useResource:address=>{assert.equal(address,'dsh-resource://enterprise-run-state/session-one');return {value:{run}};}})};
}

test('admission replaces only the native running label with no extra controls or diagnostics',()=>{
  const f=fixture();
  assert.equal(f.registration.name,'conversation.chat.running-label');
  for(const [run,text] of [
    [{status:'QUEUED',admission:'QUEUED'},'当前任务较多，正在排队'],
    [{status:'QUEUED',admission:'GRANTED'},'正在准备任务…'],
    [{status:'RUNNING'},'深度求索中'],[undefined,'深度求索中'],
  ]) {
    const tree=f.render(run);
    assert.equal(tree.type,'span');assert.deepEqual([...tree.children],[text]);
  }
});

test('the resource owns polling and stops with the native running indicator',async()=>{
  const requests=[];
  const f=fixture(async(url,options)=>{
    requests.push({url,options});return {ok:true,json:async()=>({run:{status:'QUEUED'}})};
  });
  const controller=new AbortController();
  const stream=f.provider.open('dsh-resource://enterprise-run-state/session-one',{signal:controller.signal});
  const first=await stream.next();
  assert.equal(first.value.ok,true);assert.equal(first.value.value.run.status,'QUEUED');
  assert.equal(requests[0].url,'/agent/business/run-state');
  assert.deepEqual(JSON.parse(requests[0].options.body),{session_id:'session-one'});
  controller.abort();
  assert.equal((await stream.next()).done,true);
  assert.equal(requests.length,1);
});

test('a temporary state read failure preserves the submitted request without exposing internal errors',async()=>{
  const f=fixture(async()=>{throw new Error('private transport details');});
  const controller=new AbortController();
  const stream=f.provider.open('dsh-resource://enterprise-run-state/session-one',{signal:controller.signal});
  const first=await stream.next();
  assert.equal(first.value.ok,false);
  assert.equal(first.value.error.code,'RUN_STATE_UNAVAILABLE');
  assert.equal(first.value.error.message.includes('private'),false);
  controller.abort();await stream.next();
});
