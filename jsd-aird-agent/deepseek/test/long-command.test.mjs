import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';
import EnterpriseFileSystem, { worldRoot } from '../../enterprise-plugin/src/filesystem.mjs';
import { installNativeRuntime } from '../../enterprise-plugin/src/native-runtime.mjs';
import { enterprisePatch } from '../src/config.mjs';
import { nativeFileSession, checkRpc, checkStream } from '../src/rpc-policy.mjs';
import { SandboxProcess } from '../../enterprise-plugin/src/sandbox-process.mjs';

const require=createRequire(new URL('../../../vendor/deepseek-harness/packages/fs/fs-local/package.json',import.meta.url));
const {Context}=await import(pathToFileURL(require.resolve('@deepseek-ai/cordis')).href);

async function harness(t,{stdout='completed\n',stderr='',elapsed=50,exitCode=0,submissionError,hold=false,noExport=false,reconcileAfter=0,outputFaults=0,lostSubmission=false}={}) {
  const root=await mkdtemp(join(tmpdir(),'aird-native-process-')),previous=process.env.JSD_DSH_EXECUTION_ROOT;
  process.env.JSD_DSH_EXECUTION_ROOT=root;
  const owner=new Context(),fs=new EnterpriseFileSystem(owner),plugins=[],hooks=[],calls=[],operations=new Map();
  t.after(async()=>{await owner.fiber.dispose();
    if(previous===undefined)delete process.env.JSD_DSH_EXECUTION_ROOT;else process.env.JSD_DSH_EXECUTION_ROOT=previous;
    await rm(root,{recursive:true,force:true});});
  const ctx={fs,workingDirectory:{get:()=>worldRoot,ensure:async()=>worldRoot},
    web:{registerSearchProvider:()=>{}},on:(event,handler)=>{if(event==='tools/execute')hooks.push(handler);},
    plugin:plugin=>plugins.push(plugin),logger:{warn:()=>{}}};
  const runs=new Map([['session-a',{id:'run-a',pack_ids:['files'],dsh_turn:'turn-a'}]]);
  const released=Promise.withResolvers();
  const business=async(action,body)=>{
    calls.push({action,body});
    if(action==='native-cancel') {
      const operation=operations.get(body.call_id);
      if(operation)operation.status='CANCELLED';
      return {status:'CANCELLED',pending:false};
    }
    if(action==='native-output') {
      if(outputFaults-->0)throw new TypeError('fetch failed');
      const operation=operations.get(body.call_id);
      if(!operation)return {status:'QUEUED',streams:{}};
      if(operation.status==='UNKNOWN' && Date.now()>=operation.recoverAt)operation.status='SUCCEEDED';
      return {status:operation.status,streams:Object.fromEntries(['stdout','stderr'].map(name=>{
        const bytes=Buffer.from(name==='stdout'?stdout:stderr),offset=body[name+'_offset'];
        const chunk=bytes.subarray(offset,offset+65536);
        return [name,{base64:chunk.toString('base64'),from_offset:offset,next_offset:offset+chunk.length,total_bytes:bytes.length,lossy:false}];
      })),...(reconcileAfter && operation.status==='SUCCEEDED'?{reconciled_result:{exit_code:exitCode,files:operation.files,receipt:{status:'SUCCEEDED'}}}:{})};
    }
    if(action!=='native-tool')return {};
    if(submissionError)return {error:submissionError,retryable:false};
    const operation={status:'RUNNING'};operations.set(body.call_id,operation);
    if(hold)await released.promise;else await delay(elapsed);
    if(operation.status==='CANCELLED')return {error:'SANDBOX_CANCELLED',receipt:{status:'CANCELLED'}};
    if(reconcileAfter) {
      operation.status='UNKNOWN';operation.recoverAt=Date.now()+reconcileAfter;operation.files=body.arguments.files;
      if(lostSubmission)await new Promise(()=>{});
      return {error:'TOOL_RESULT_UNKNOWN',requires_reconciliation:true,retryable:false};
    }
    operation.status='SUCCEEDED';
    return {exit_code:exitCode,...(noExport?{error:'SANDBOX_SCRIPT_FAILED'}:{files:body.arguments.files}),receipt:{status:'SUCCEEDED'}};
  };
  installNativeRuntime(ctx,{business,runs,profileOf:()=> 'agent'});
  const Provider=plugins.find(plugin=>plugin.name==='EnterpriseSubprocess'),provider=Object.create(Provider.prototype);
  Object.defineProperty(provider,'ctx',{value:ctx});provider.remote=new Set();ctx.subprocess=provider;
  const Shell=plugins.find(plugin=>plugin.name==='EnterpriseShell'),shell=Object.create(Shell.prototype);
  Object.defineProperty(shell,'ctx',{value:ctx});
  shell.config=Object.fromEntries(Object.entries({timeoutMs:120000,maxTimeoutMs:600000,maxOutputBytes:64000,
    maxSpillBytes:67108864,graceMs:3000}).map(([key,value])=>[key,{get:()=>value}]));
  let count=0;
  const start=async(spec,signal)=>{
    const exec={name:'bash',callId:'command-'+(++count),agent:{id:'session-a',session:{}},arguments:{},signal:signal||new AbortController().signal};
    return hooks[0](exec,()=>shell.execute(shell.resolve({...spec,signal:exec.signal})));
  };
  return {ctx,fs,shell,start,calls,runs,finish:()=>released.resolve()};
}

test('native executor owns deadlines, cancellation and non-expiring job waits through the broker provider',async t=>{
  const {shell,start,calls}=await harness(t);
  assert.equal(shell.resolve({command:'work'}).timeoutMs,120000);
  assert.equal(shell.resolve({command:'work',timeoutMs:300000}).timeoutMs,300000);
  assert.equal(shell.resolve({command:'work',timeoutMs:900000}).timeoutMs,600000);
  for(const timeoutMs of [0,-1,NaN,Infinity])assert.throws(()=>shell.resolve({command:'work',timeoutMs}));
  assert.equal((await (await start({command:'work',timeoutMs:300000})).result()).exitCode,0);
  const detached=await (await start({command:'work',timeoutMs:5,onExpiry:'none'})).result();
  assert.equal(detached.timedOut,false);assert.equal(detached.exitCode,0);
  const expired=await (await start({command:'work',timeoutMs:25})).result();
  assert.equal(expired.timedOut,true);assert.equal(expired.aborted,false);
  assert.equal(calls.filter(value=>value.action==='native-cancel').length,1);
});

test('rejected submission ends without waiting forever for an unregistered output receipt',{timeout:10000},async t=>{
  const {start,calls}=await harness(t,{submissionError:'CAPABILITY_NOT_AUTHORIZED'});
  const process=await start({command:'work'});
  await assert.rejects(process.result(),/CAPABILITY_NOT_AUTHORIZED/);
  assert.equal(calls.filter(value=>value.action==='native-tool').length,1);
  assert.equal(calls.filter(value=>value.action==='native-cancel').length,1);
});

test('known script failure keeps stderr and does not delete files without an export',async t=>{
  const {start,fs,calls}=await harness(t,{exitCode:1,stderr:'FileNotFoundError: missing input\n',noExport:true});
  await fs.publish('/workspaces/source.csv',Buffer.from('original rows'));
  const result=await (await start({command:'python3 missing.py'})).result();
  assert.equal(result.exitCode,1);
  assert.match(result.stderr.text,/FileNotFoundError: missing input/);
  assert.equal(await fs.readText(await fs.resolve('/workspaces/source.csv')),'original rows');
  assert.equal(calls.filter(value=>value.action==='native-cancel').length,0);
});

test('quiet commands back off output polling and still wait for their actual terminal receipt',async t=>{
  const {start,calls}=await harness(t,{elapsed:1250,stdout:'',stderr:''});
  const result=await (await start({command:'work'})).result();
  assert.equal(result.exitCode,0);
  assert.equal(calls.filter(c=>c.action==='native-tool').length,1);
  assert.ok(calls.filter(c=>c.action==='native-output').length<=5);
  assert.equal(calls.filter(c=>c.action==='native-cancel').length,0);
});

test('UNKNOWN execution recovers the original receipt and bytes without command replay',async t=>{
  const {start,fs,calls}=await harness(t,{reconcileAfter:25,exitCode:1,stderr:'ValueError: repair this cell\n'});
  await fs.publish('/workspaces/input.csv',Buffer.from('unaltered source'));
  const result=await (await start({command:'python3 workbook.py'})).result();
  assert.equal(result.exitCode,1);assert.match(result.stderr.text,/repair this cell/);
  assert.equal(await fs.readText(await fs.resolve('/workspaces/input.csv')),'unaltered source');
  assert.equal(calls.filter(c=>c.action==='native-tool').length,1);
  assert.equal(calls.filter(c=>c.action==='native-cancel').length,0);
  assert.ok(calls.filter(c=>c.action==='native-output').length>=2);
});

test('a transient output read failure does not cancel an accepted command',async t=>{
  const {start,calls}=await harness(t,{outputFaults:1});
  const result=await (await start({command:'work'})).result();
  assert.equal(result.exitCode,0);
  assert.equal(calls.filter(c=>c.action==='native-tool').length,1);
  assert.equal(calls.filter(c=>c.action==='native-cancel').length,0);
});

test('the original receipt can finish while the submission HTTP response is still missing',{timeout:10000},async t=>{
  const {start,calls}=await harness(t,{reconcileAfter:25,lostSubmission:true});
  const result=await (await start({command:'work'})).result();
  assert.equal(result.exitCode,0);
  assert.equal(calls.filter(c=>c.action==='native-tool').length,1);
  assert.equal(calls.filter(c=>c.action==='native-cancel').length,0);
});

test('an unknown receipt ends its recovery window without awaiting or cancelling a lost submission',{timeout:20000},async t=>{
  const {ctx}=await harness(t);
  const actions=[];let now=0;
  t.mock.method(Date,'now',()=>now+=31000);
  const handle=new SandboxProcess(ctx,{argv:['bash','-c','original command'],cwd:worldRoot,env:{},
    signal:new AbortController().signal,stdio:{stdout:{maxBytes:4096},stderr:{maxBytes:4096}}},
    {agent:{id:'session-a',session:{}},payload:{run_id:'run-a',call_id:'original-command'},business:async action=>{
      actions.push(action);
      if(action==='native-tool')return new Promise(()=>{});
      return {status:'UNKNOWN',streams:{}};
    }});
  await assert.rejects(handle.done,/SANDBOX_RESULT_UNKNOWN/);
  assert.equal(actions.filter(action=>action==='native-tool').length,1);
  assert.equal(actions.filter(action=>action==='native-cancel').length,0);
  assert.equal(handle.quiescent,false);
});

test('long Unicode stdout and stderr use native byte collectors and recoverable session spill files',async t=>{
  const stdout='配方 0 g；25 °C；β\n'.repeat(10000),stderr='条件缺失，不作比较\n';
  const {start,fs,finish}=await harness(t,{stdout,stderr,hold:true});
  const process=await start({command:'research'});
  try {
    const deadline=Date.now()+5000;
    while(process.observed.stdout.readFrom(0).nextOffset===0 && Date.now()<deadline)await delay(10);
    const preview=process.observed.stdout.readFrom(0);
    assert.ok(preview.nextOffset>0);assert.equal(process.status,'running');
    assert.deepEqual(process.observed.stdout.readFrom(0),preview);
  } finally {finish();}
  const result=await process.result();
  assert.equal(result.exitCode,0);assert.equal(result.stdout.truncated,true);
  assert.ok(result.stdout.spillPath.startsWith(worldRoot+'/.dsh-process-output/'));
  assert.equal(await fs.readText(await fs.resolve(result.stdout.spillPath)),stdout);
  assert.equal(result.stderr.text,stderr);assert.equal(result.stderr.truncated,false);
  assert.equal(process.observed.stdout.readFrom(0).nextOffset,Buffer.byteLength(stdout));
});

test('native background commands retain their original turn binding after a new turn starts',async t=>{
  const {start,runs,calls}=await harness(t,{elapsed:100,exitCode:7});
  const process=await start({command:'work',onExpiry:'none'});
  await delay(20);
  runs.set('session-a',{id:'run-b',pack_ids:['files'],dsh_turn:'turn-b'});
  const result=await process.result();
  assert.equal(result.exitCode,7);
  for(const {action,body} of calls.filter(value=>value.action.startsWith('native-'))) {
    assert.equal(body.run_id,'run-a',action);assert.equal(body.turn,'turn-a',action);
  }
});

test('native background tools, roster and controls are available with an owned session',async()=>{
  const patch=enterprisePatch({plugin:'G:/enterprise',executionRoot:'G:/execution',model:{id:'deepseek-flash'}});
  for(const id of ['job-controller','ui-jobs'])assert.notEqual(patch.find(row=>row.id===id)?.disabled,true);
  assert.ok(JSON.stringify(patch).includes('tool-jobs'));
  for(const method of ['list','follow','kill']) {
    const endpoint='job/'+method,envelope={type:'client-request',method:endpoint,payload:{args:{request:{sessionId:'owned-session'}}}};
    await checkRpc(endpoint,envelope,{});
    assert.equal(nativeFileSession(endpoint,envelope),'owned-session');
    envelope.payload.args.request={};
    await assert.rejects(checkRpc(endpoint,envelope,{}),/SESSION_REQUIRED/);
  }
  for(const endpoint of ['job/list','job/follow'])assert.doesNotThrow(()=>checkStream({type:'open',endpoint}));
});
