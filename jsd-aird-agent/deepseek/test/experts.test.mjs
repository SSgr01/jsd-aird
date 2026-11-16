import test, {after} from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync,mkdirSync,rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {installExperts} from '../../enterprise-plugin/src/experts.mjs';

const roots=[];
after(()=>{for(const root of roots)rmSync(root,{recursive:true,force:true});});
function setup({deny=false,expert={id:'project-assistant',name:'项目助理'}}={}) {
  const calls=[], effects=[];
  const executionRoot=mkdtempSync(join(tmpdir(),'jsd-experts-'));roots.push(executionRoot);
  mkdirSync(join(executionRoot,'default'));
  const workspaceRegistry={async create(path){calls.push(['workspace',path]);return {id:'workspace-default'};}};
  const controller={
    async create(request) {calls.push(['native-create',request]);return {sessionId:request.sessionId};},
    async fork(request) {calls.push(['native-fork',request]);return {sessionId:'session-child'};},
    prompt() {throw new Error('Expert selection must not send a message');},
  };
  const originalFork=controller.fork;
  const business=async(action,body)=>{
    calls.push([body.action,body]);
    if(body.action==='resolve') {if(deny)throw new Error('EXPERT_DISABLED');return expert;}
    if(body.action==='session')return {expert};
    return {expert};
  };
  const handle=installExperts({sessionController:controller,workspaceRegistry,effect:fn=>effects.push(fn())},{business,executionRoot});
  return {handle,calls,controller,effects,originalFork};
}

test('summon validates a company expert, creates a native main session and durably binds before returning',async()=>{
  const {handle,calls}=setup();
  const result=await handle({action:'summon',expert_id:'project-assistant',instructions:'untrusted',agentPreset:'other'});
  assert.deepEqual(calls.map(([name])=>name),['resolve','workspace','native-create','bind']);
  assert.match(calls[1][1],/[\\/]default$/);
  assert.equal(calls[2][1].workspaceId,'workspace-default');
  assert.equal(calls[2][1].cwd,undefined);
  assert.equal(calls[2][1].agentPreset,'enterprise');
  assert.match(result.session_id,/^session-/);
  assert.equal(calls[3][1].session_id,result.session_id);
  assert.equal(calls[3][1].revision,undefined);
  assert.equal(calls[3][1].expert_id,'project-assistant');
  assert.equal(calls[3][1].instructions,undefined);
});

test('disabled expert cannot create a session; configuration and binding operations stay unavailable to the browser',async()=>{
  const {handle,calls}=setup({deny:true});
  await assert.rejects(()=>handle({action:'summon',expert_id:'project-assistant'}),/EXPERT_DISABLED/);
  assert.deepEqual(calls.map(([name])=>name),['resolve']);
  for(const action of ['bind','fork','create','edit','share','clone','team'])
    await assert.rejects(()=>handle({action}),/EXPERT_ACTION_DENIED/);
});

test('native forks inherit the expert identity before the child is released and teardown restores the service',async()=>{
  const {controller,calls,effects,originalFork}=setup();
  const result=await controller.fork({sessionId:'session-parent',atSeq:12});
  assert.equal(result.sessionId,'session-child');
  assert.deepEqual(calls.map(([name])=>name),['session','resolve','native-fork','fork']);
  assert.equal(calls[1][1].revision,undefined);
  assert.equal(calls[1][1].expert_id,'project-assistant');
  assert.equal(calls[3][1].source_session_id,'session-parent');
  for(const cleanup of effects)cleanup();
  assert.equal(controller.fork,originalFork);
});

test('general assistant forks remain general',async()=>{
  const {controller,calls}=setup({expert:null});
  await controller.fork({sessionId:'session-parent'});
  assert.deepEqual(calls.map(([name])=>name),['session','native-fork']);
});
