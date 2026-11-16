import test from 'node:test';
import assert from 'node:assert/strict';
import {BackgroundHosts} from '../src/background-hosts.mjs';
import {Hosts} from '../src/hosts.mjs';

test('background recovery restores native hosts, stops refused grants and retains transient leases', async t=>{
  const restored=[],stopped=[],synced=[];let eligible;
  t.mock.method(globalThis,'fetch',async (url,request)=>{
    const body=JSON.parse(request.body);
    assert.equal(request.headers.authorization,'Bearer runtime-secret');
    if(url.endsWith('/owners'))return Response.json({owners:[{id:'active'},{id:'revoked'},{id:'transient'}]});
    if(body.owner_id==='revoked')return new Response('',{status:403});
    if(body.owner_id==='transient')throw new Error('connection interrupted');
    return Response.json({automation_grant_id:body.owner_id});
  });
  const hosts={ensureBackground:async identity=>{restored.push(identity.automation_grant_id);return identity;},
    call:async(host,action)=>synced.push(action), suspendBackground:async id=>stopped.push(id),
    expireBackground:async ids=>{eligible=[...ids];}};
  await new BackgroundHosts({controlUrl:'http://control',secret:'runtime-secret'},hosts).refresh();
  assert.deepEqual(restored,['active']);assert.deepEqual(synced,['automations-sync']);
  assert.deepEqual(stopped,['revoked']);assert.deepEqual(eligible,['active','transient']);
});

test('first visit retries an interrupted installation without caching its failure',async t=>{
  let attempts=0;
  const host={};const hosts={call:async()=>{if(++attempts===1)throw new Error('response lost');return {id:'grant',grant_status:'ACTIVE'};},
    ensureBackground:async identity=>assert.equal(identity.automation_grant_id,'grant')};
  t.mock.method(globalThis,'fetch',async()=>Response.json({automation_grant_id:'grant'}));
  const background=new BackgroundHosts({controlUrl:'http://control',secret:'runtime-secret'},hosts);
  await assert.rejects(background.install(host));await background.install(host);await background.install(host);
  assert.equal(attempts,2);
});

test('owner pagination finishes before a rejected ticket can shrink the active set',async t=>{
  let refused=false;const restored=[];
  t.mock.method(globalThis,'fetch',async(url,request)=>{
    const body=JSON.parse(request.body);
    if(url.endsWith('/owners')) {
      assert.equal(refused,false,'active owners must be collected before ticket exchange');
      return Response.json(body.offset===0?{owners:[{id:'revoked'}],next_offset:1}:{owners:[{id:'next'}]});
    }
    if(body.owner_id==='revoked') {refused=true;return new Response('',{status:403});}
    return Response.json({automation_grant_id:body.owner_id});
  });
  const hosts={ensureBackground:async identity=>{restored.push(identity.automation_grant_id);return identity;},
    call:async()=>{},suspendBackground:async()=>{},expireBackground:async()=>{}};
  await new BackgroundHosts({controlUrl:'http://control',secret:'runtime-secret'},hosts).refresh();
  assert.deepEqual(restored,['next']);
});

test('suspending background authorization lets a live interactive host install the current grant again',async()=>{
  const hosts=new Hosts({});const host={identity:{},backgroundIdentity:{automation_grant_id:'expired'},installing:Promise.resolve({})};
  hosts.instances.set('owner',Promise.resolve(host));
  const actions=[];hosts.call=async(_host,action)=>actions.push(action);
  await hosts.suspendBackground('expired');
  assert.equal(host.installing,undefined);assert.equal(host.backgroundIdentity,undefined);
  assert.deepEqual(actions,['background-revoke']);
});
