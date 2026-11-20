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

async function harness(t,{initialError='ARTIFACT_EXECUTION_HASH_MISMATCH: regenerate',checkpointError,finalError,
  filename='PET基材厚度更正说明.md',checkpointExitCode=0,checkpointStderr='',mutateDuringCheckpoint=false,
  checkpointStatus='SUCCEEDED',reconciliation={outcome:'STILL_UNKNOWN'}}={}) {
  const root=await mkdtemp(join(tmpdir(),'aird-delivery-')),previous=process.env.JSD_DSH_EXECUTION_ROOT;
  process.env.JSD_DSH_EXECUTION_ROOT=root;
  const owner=new Context(),fs=new EnterpriseFileSystem(owner),calls=[],hooks=[];
  t.after(async()=>{await owner.fiber.dispose();
    if(previous===undefined)delete process.env.JSD_DSH_EXECUTION_ROOT;else process.env.JSD_DSH_EXECUTION_ROOT=previous;
    await rm(root,{recursive:true,force:true});});
  const target=await fs.resolve(filename);
  await fs.writeText(target,'按用户指定更正为 18.8 μm。',{kind:'createIfAbsent'});
  const ctx={fs,workingDirectory:{ensure:async()=>worldRoot},web:{registerSearchProvider:()=>{}},
    on:(event,hook)=>{if(event==='tools/execute')hooks.push(hook);},plugin:()=>{},logger:{warn:()=>{}}};
  const business=async(action,body)=>{
    calls.push({action,body});
    if(action==='operation-reconcile')return reconciliation;
    if(action==='native-output') {
      const bytes=Buffer.from(checkpointStderr),offset=body.stderr_offset;
      return {status:checkpointStatus,streams:{stderr:{base64:bytes.subarray(offset).toString('base64'),
        from_offset:offset,next_offset:bytes.length,total_bytes:bytes.length}}};
    }
    if(action==='native-cancel')return {status:'CANCELLED'};
    if(action!=='native-tool')return {};
    if(body.name==='dsh_execute') {
      if(checkpointError)return {error:checkpointError};
      if(mutateDuringCheckpoint)await fs.publish(String(target.targetKey),Buffer.from('concurrent edit'));
      return {exit_code:checkpointExitCode,files:body.arguments.files,receipt:{status:checkpointStatus}};
    }
    const error=body.call_id.endsWith('-checkpointed')?finalError:initialError;
    return error?{error}:{id:'registered',name:'PET基材厚度更正说明.md'};
  };
  const runs=new Map([['session',{id:'run',pack_ids:['files'],dsh_turn:1}]]);
  const native=installNativeRuntime(ctx,{business,runs,profileOf:()=> 'agent'});
  let presented=false;
  const present=(files=[{path:filename}])=>hooks[0]({name:'present',callId:'present-1',agent:{id:'session'},
    arguments:{files},signal:new AbortController().signal},
    async()=>{presented=true;return 'native delivery card';});
  return {present,calls,fs,target,native,wasPresented:()=>presented};
}

test('invalid delivery collections fail before any registration or sandbox operation',async t=>{
  const {present,calls,wasPresented}=await harness(t);
  for(const files of ['[]',[],[{}],[{path:'  '}],Array(9).fill({path:'a.md'})])
    await assert.rejects(present(files),/INVALID_ARGUMENT: files/);
  assert.equal(calls.length,0);assert.equal(wasPresented(),false);
});

test('record export uses the broker and returns no workbook on a failed source conversion',async t=>{
  const {native,calls}=await harness(t,{checkpointExitCode:1,checkpointStderr:'source incomplete'});
  const execution={callId:'export-1',agent:{id:'session'},signal:new AbortController().signal};
  const dataset={format:'business-records',path:'.business-data/business-'+'a'.repeat(20)+'.json',export_script:'.business-data/export-records.py'};
  await assert.rejects(native.exportRecords(execution,{...dataset,path:'../outside.json'},'业务记录.xlsx'),/EXPORT_SOURCE_INCOMPLETE/);
  assert.equal(calls.length,0);
  await assert.rejects(native.exportRecords(execution,dataset,'业务记录.xlsx'),/BUSINESS_EXPORT_FAILED: source incomplete/);
  const operation=calls.find(call=>call.body.name==='dsh_execute').body;
  assert.equal(operation.call_id,'export-1/export');
  assert.match(operation.arguments.command,/python3 -I -B \.business-data\/export-records.py --input \.business-data\/business-a{20}\.json --output 业务记录-[a-f0-9]{12}\.xlsx$/);
  assert.equal(calls.some(call=>call.body.name==='artifact_save'),false);
});

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

test('an unknown artifact save reads its exact subcall receipt and never replays the save',async t=>{
  const {present,calls,wasPresented}=await harness(t,{initialError:'TOOL_RESULT_UNKNOWN',
    reconciliation:{outcome:'CONFIRMED_SUCCEEDED',result:{id:'registered'}}});
  assert.equal(await present(),'native delivery card');
  assert.equal(wasPresented(),true);
  assert.equal(calls.filter(call=>call.body.name==='artifact_save').length,1);
  assert.equal(calls.find(call=>call.action==='operation-reconcile').body.call_id,'present-1/file-0');
});

test('an unresolved artifact never creates a delivery card or replays a save',async t=>{
  const {present,calls,wasPresented}=await harness(t,{initialError:'TOOL_RESULT_UNKNOWN'});
  await assert.rejects(present(),/TOOL_RESULT_UNKNOWN/);
  assert.equal(wasPresented(),false);
  assert.equal(calls.filter(call=>call.body.name==='artifact_save').length,1);
});

test('export permission refusal never creates a delivery card or replays execution',async t=>{
  const {present,calls,wasPresented}=await harness(t,{initialError:'当前账号没有该业务资料的导出权限。'});
  await assert.rejects(present(),/导出权限/);
  assert.equal(wasPresented(),false);
  assert.equal(calls.filter(call=>call.body.name==='artifact_save').length,1);
  assert.equal(calls.filter(call=>call.body.name==='dsh_execute').length,0);
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

test('xlsx presentation opens and freezes its exact bytes before the first registration',async t=>{
  const {present,calls}=await harness(t,{filename:"澄禾 电子's 台账.XLSX",initialError:null});
  await present();
  const tools=calls.filter(call=>call.action==='native-tool').map(call=>call.body);
  assert.deepEqual(tools.map(call=>call.name),['dsh_execute','artifact_save']);
  assert.match(tools[0].arguments.command,/python3 -I -B -c /);
  assert.match(tools[0].arguments.command,/load_workbook/);
  assert.ok(tools[0].arguments.command.includes(tools[1].arguments.sha256));
  assert.equal(tools[0].arguments.files.find(file=>file.path.endsWith('.XLSX')).base64,tools[1].arguments.base64);
});

test('an unreadable xlsx keeps the file and returns the inspection error without registering it',async t=>{
  const {present,calls,fs,target,wasPresented}=await harness(t,{filename:'台账.xlsx',initialError:null,
    checkpointExitCode:1,checkpointStderr:'EXPORT_FORMAT_INVALID: missing worksheet part\n'});
  await assert.rejects(present(),/missing worksheet part/);
  assert.equal(wasPresented(),false);
  assert.equal(calls.filter(call=>call.body.name==='artifact_save').length,0);
  assert.equal(await fs.readText(target),'按用户指定更正为 18.8 μm。');
});

test('unknown xlsx inspections and concurrent edits never register or replay a file',async t=>{
  for(const options of [{checkpointError:'TOOL_RESULT_UNKNOWN'},{mutateDuringCheckpoint:true}]) {
    const {present,calls,wasPresented}=await harness(t,{filename:'台账.xlsx',initialError:null,...options});
    await assert.rejects(present(),/TOOL_RESULT_UNKNOWN|WORKSPACE_CHANGED_DURING_EXECUTION/);
    assert.equal(wasPresented(),false);
    assert.equal(calls.filter(call=>call.body.name==='dsh_execute').length,1);
    assert.equal(calls.filter(call=>call.body.name==='artifact_save').length,0);
  }
});

test('an xlsx registration mismatch after inspection cannot cause a second inspection or checkpoint',async t=>{
  const {present,calls}=await harness(t,{filename:'台账.xlsx'});
  await assert.rejects(present(),/ARTIFACT_EXECUTION_HASH_MISMATCH/);
  assert.equal(calls.filter(call=>call.body.name==='dsh_execute').length,1);
  assert.equal(calls.filter(call=>call.body.name==='artifact_save').length,1);
});

test('a cancelled xlsx inspection retains the workbook and stops before registration',async t=>{
  const {present,calls,fs,target,wasPresented}=await harness(t,{
    filename:'台账.xlsx',initialError:null,checkpointStatus:'CANCELLED',
  });
  await assert.rejects(present(),/ARTIFACT_CHECKPOINT_CANCELLED/);
  assert.equal(wasPresented(),false);
  assert.equal(calls.filter(call=>call.body.name==='dsh_execute').length,1);
  assert.equal(calls.filter(call=>call.body.name==='artifact_save').length,0);
  assert.equal(await fs.readText(target),'按用户指定更正为 18.8 μm。');
});
