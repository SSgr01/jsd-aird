import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';
import EnterpriseFileSystem, { worldRoot } from '../../enterprise-plugin/src/filesystem.mjs';
import { installNativeRuntime } from '../../enterprise-plugin/src/native-runtime.mjs';

const require=createRequire(new URL('../../../vendor/deepseek-harness/packages/fs/fs-local/package.json',import.meta.url));
const {Context}=await import(pathToFileURL(require.resolve('@deepseek-ai/cordis')).href);

async function harness(t,{initialError='ARTIFACT_EXECUTION_HASH_MISMATCH: regenerate',checkpointError,finalError}={}) {
  const root=await mkdtemp(join(tmpdir(),'aird-delivery-')),previous=process.env.JSD_DSH_EXECUTION_ROOT;
  process.env.JSD_DSH_EXECUTION_ROOT=root;
  const owner=new Context(),fs=new EnterpriseFileSystem(owner),calls=[],hooks=[];
  t.after(async()=>{await owner.fiber.dispose();
    if(previous===undefined)delete process.env.JSD_DSH_EXECUTION_ROOT;else process.env.JSD_DSH_EXECUTION_ROOT=previous;
    await rm(root,{recursive:true,force:true});});
  const target=await fs.resolve('PET基材厚度更正说明.md');
  await fs.writeText(target,'按用户指定更正为 18.8 μm。',{kind:'createIfAbsent'});
  const ctx={fs,workingDirectory:{ensure:async()=>worldRoot},web:{registerSearchProvider:()=>{}},
    on:(event,hook)=>{if(event==='tools/execute')hooks.push(hook);},plugin:()=>{},logger:{warn:()=>{}}};
  const business=async(action,body)=>{
    calls.push({action,body});
    if(action==='native-output')return {status:'SUCCEEDED',streams:{}};
    if(action==='native-cancel')return {status:'CANCELLED'};
    if(action!=='native-tool')return {};
    if(body.name==='dsh_execute') {
      if(checkpointError)return {error:checkpointError};
      return {exit_code:0,files:body.arguments.files,receipt:{status:'SUCCEEDED'}};
    }
    const error=body.call_id.endsWith('-checkpointed')?finalError:initialError;
    return error?{error}:{id:'registered',name:'PET基材厚度更正说明.md'};
  };
  const runs=new Map([['session',{id:'run',pack_ids:['files'],dsh_turn:1}]]);
  installNativeRuntime(ctx,{business,runs,profileOf:()=> 'agent'});
  let presented=false;
  const present=()=>hooks[0]({name:'present',callId:'present-1',agent:{id:'session'},
    arguments:{files:[{path:'PET基材厚度更正说明.md'}]},signal:new AbortController().signal},
    async()=>{presented=true;return 'native delivery card';});
  return {present,calls,wasPresented:()=>presented};
}

test('present checkpoints native edits once and registers the exact current Unicode bytes',async t=>{
  const {present,calls,wasPresented}=await harness(t);
  assert.equal(await present(),'native delivery card');assert.equal(wasPresented(),true);
  const tools=calls.filter(call=>call.action==='native-tool').map(call=>call.body);
  assert.deepEqual(tools.map(call=>call.name),['artifact_save','dsh_execute','artifact_save']);
  assert.equal(tools[1].arguments.command,':');
  assert.equal(tools[1].arguments.files.find(file=>file.path.endsWith('.md')).base64,tools[2].arguments.base64);
  assert.equal(tools[0].arguments.sha256,tools[2].arguments.sha256);
  assert.notEqual(tools[0].call_id,tools[2].call_id);
  assert.ok(calls.some(call=>call.action==='gate'&&call.body.native_name==='bash'));
});

test('already registered bytes do not start a sandbox checkpoint',async t=>{
  const {present,calls}=await harness(t,{initialError:null});
  await present();assert.equal(calls.filter(call=>call.body.name==='dsh_execute').length,0);
});

test('citation failures stay actionable and do not become successful delivery cards',async t=>{
  const message='ANALYSIS_SOURCE_QUOTES_REQUIRED: note.md:3 q000000000000000000000000';
  const {present,calls,wasPresented}=await harness(t,{finalError:message});
  await assert.rejects(present(),error=>error.message===message);
  assert.equal(wasPresented(),false);
  assert.equal(calls.filter(call=>call.body.name==='dsh_execute').length,1);
});

test('an unknown checkpoint outcome is not replayed or registered',async t=>{
  const {present,calls,wasPresented}=await harness(t,{checkpointError:'TOOL_RESULT_UNKNOWN'});
  await assert.rejects(present(),/TOOL_RESULT_UNKNOWN/);
  assert.equal(wasPresented(),false);
  assert.equal(calls.filter(call=>call.body.name==='dsh_execute').length,1);
  assert.equal(calls.filter(call=>call.body.name==='artifact_save').length,1);
});
