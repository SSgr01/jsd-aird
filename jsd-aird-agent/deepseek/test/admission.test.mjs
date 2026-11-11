import test from 'node:test';
import assert from 'node:assert/strict';
import { setTimeout as delay } from 'node:timers/promises';
import { installAdmission } from '../../enterprise-plugin/src/admission.mjs';

function harness(t, respond) {
  const hooks=new Map(), cleanups=[], calls=[], controller=new AbortController();
  const message={id:'original',source:{kind:'user'},content:[{type:'text',text:'retained input'}]};
  const agent={id:'session',inbox:{nextStep:[],nextTurn:[message]},cancel:(reason,options)=>{
    agent.cancelled={reason,options}; controller.abort(reason);
  }};
  const ctx={on:(name,fn)=>hooks.set(name,fn),effect:fn=>cleanups.push(fn()),logger:{warn:()=>{}}};
  const admission=installAdmission(ctx,{ready:Promise.resolve(),prepare:async(_agent,input)=>({session_id:agent.id,...input}),
    business:async(action,body)=>{calls.push({action,body});return respond(action,body,calls);}});
  t.after(()=>cleanups.forEach(fn=>fn()));
  const run=next=>hooks.get('agent/pre-turn')({agent,messages:[message],turn:1,signal:controller.signal},next);
  return {agent,controller,calls,admission,run,end:()=>hooks.get('session/event')({id:agent.id},{type:'turn/end',data:{turn:1}})};
}
const state=(admission='QUEUED')=>({run_id:'run',epoch:admission==='GRANTED'?1:0,status:'QUEUED',admission,heartbeat_seconds:10,lease_seconds:60});

test('queued native input waits, automatically enters once, and releases the same epoch',async t=>{
  let current=state(),entered=0;
  const h=harness(t,async action=>action.startsWith('admission-')&&action!=='admission-state'?{accepted:true}:current);
  const operation=h.run(async()=>{entered++;});
  await delay(20);
  assert.equal(entered,0);assert.equal(h.agent.inbox.nextTurn.length,1);
  current=state('GRANTED'); await operation;
  assert.equal(entered,1);assert.equal(h.admission.grants.get('session').epoch,1);
  h.end();await delay(0);
  assert.equal(h.admission.grants.size,0);
  assert.deepEqual(h.calls.at(-1),{action:'admission-release',body:{session_id:'session',run_id:'run',epoch:1,stopped:true}});
});

for (const kind of ['user','disposed']) test(`waiting ${kind} cancellation preserves the intended ticket lifecycle`,async t=>{
  const h=harness(t,async()=>state());
  const operation=h.run(()=>assert.fail('waiting work entered the model loop'));
  const rejected=assert.rejects(operation);
  await delay(20);h.controller.abort({kind});await rejected;
  const released=h.calls.find(call=>call.action==='admission-release');
  if(kind==='disposed') assert.equal(released,undefined);
  else {assert.equal(released.body.cancelled,true);assert.equal(released.body.stopped,true);}
  assert.equal(h.calls.filter(call=>call.action==='failure').length,0);
});

test('editing queued input cancels its ticket before the core re-proposes',async t=>{
  let entered=0;
  const h=harness(t,async()=>state());
  const operation=h.run(async()=>{entered++;});
  await delay(20);h.agent.inbox.nextTurn[0]={id:'edited',content:[{type:'text',text:'new input'}]};
  await operation;
  assert.equal(entered,1);assert.equal(h.admission.grants.size,0);
  assert.equal(h.calls.at(-1).action,'admission-release');assert.equal(h.calls.at(-1).body.cancelled,true);
});

test('a lost admission reply retries the same input signature without failing the turn',async t=>{
  let attempts=0;
  const h=harness(t,async action=>{
    if(action==='admit' && ++attempts===1) throw Object.assign(new Error('lost reply'),{status:503});
    return state('GRANTED');
  });
  await h.run(async()=>{});
  const requests=h.calls.filter(call=>call.action==='admit');
  assert.equal(requests.length,2);assert.equal(requests[0].body.input_signature,requests[1].body.input_signature);
  assert.equal(h.calls.filter(call=>call.action==='failure').length,0);
});

test('a fenced heartbeat stops the old agent while preserving its pending Inbox',async t=>{
  const h=harness(t,async action=>{
    if(action==='admission-renew') throw Object.assign(new Error('fenced'),{status:409});
    return {...state('GRANTED'),heartbeat_seconds:0.005};
  });
  await h.run(async()=>{});await delay(25);
  assert.deepEqual(h.agent.cancelled,{reason:{kind:'user'},options:{keepInbox:true}});
});

test('cancelling after a lost initial reply persists cancellation with the same input identity',async t=>{
  let h;
  h=harness(t,async(action,body)=>{
    if(action==='admit' && !body.cancelled) {
      h.controller.abort({kind:'user'});
      throw Object.assign(new Error('reply lost'),{status:503});
    }
    if(action==='admit') return {...state(),status:'CANCELLED',admission:'RELEASED'};
    return {accepted:true};
  });
  await assert.rejects(h.run(()=>assert.fail('cancelled work entered')));
  const admits=h.calls.filter(call=>call.action==='admit');
  assert.equal(admits.length,2);
  assert.equal(admits[1].body.cancelled,true);
  assert.equal(admits[0].body.input_signature,admits[1].body.input_signature);
  assert.equal(h.calls.filter(call=>call.action==='failure').length,0);
});

test('transient polling errors keep the original request queued until admission returns',async t=>{
  let polls=0,entered=0;
  const h=harness(t,async action=>{
    if(action==='admit') return state();
    if(action==='admission-state' && ++polls===1) throw Object.assign(new Error('temporary outage'),{status:503});
    return state('GRANTED');
  });
  await h.run(async()=>{entered++;});
  assert.equal(entered,1);assert.equal(polls,2);
  assert.equal(h.calls.filter(call=>call.action==='failure').length,0);
});
