import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp,mkdir,rm,readFile,writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createHash } from 'node:crypto';
import { conversionLauncher,conversionSession } from '../../enterprise-plugin/src/conversion-launch.mjs';
import { prepareConversionResources,conversionFiles } from '../../enterprise-plugin/src/conversion-resources.mjs';

const id='11111111-1111-4111-8111-111111111111', other='22222222-2222-4222-8222-222222222222';
test('batch uses one native session per original and stable prompt identities; one failure does not stop the next',async t=>{
  const root=await mkdtemp(join(tmpdir(),'conversion-'));t.after(()=>rm(root,{recursive:true,force:true}));await mkdir(join(root,'default'));
  const created=[],prompted=[],calls=[];
  const tasks=[{id,status:'QUEUED'},{id:other,status:'QUEUED'},{id:'done',status:'WAITING_REVIEW'}];
  const launch=conversionLauncher({workspaceRegistry:{async create(){return {id:'workspace'};}},sessionController:{
    async projections(){return undefined;},async create(body){created.push(body);},
    async prompt(body,signal){signal.throwIfAborted();prompted.push(body);if(body.sessionId===conversionSession(id))throw new Error('model unavailable');},
  }},{executionRoot:root,business:async(action,body)=>{
    calls.push([action,body]);if(action==='conversion-queue')return {tasks};
    if(body.action==='resolve')return {id:'experiment-assistant'};
    return {expert:null};
  }});
  const result=await launch({batch_id:id});
  assert.equal(created.length,2);assert.equal(prompted.length,2);
  assert.equal(result.tasks[0].accepted,false);assert.equal(result.tasks[1].accepted,true);
  assert.equal(prompted[0].requestId,'conversion-'+id);
  assert.match(prompted[0].content[0].text,/不要确认导入或发布/);
  assert.ok(created.every(row=>row.workspaceId==='workspace'&&row.agentPreset==='enterprise'));
  assert.equal(calls.filter(([action,body])=>action==='experts'&&body.action==='bind').length,2);
  await assert.rejects(()=>launch({batch_id:id,instructions:'arbitrary'}),/INVALID_CONVERSION_BATCH/);
});

test('resource preparation copies reviewed scripts, exact source bytes and empty templates into the current task',async t=>{
  assert.ok(conversionFiles.every(file=>!file.startsWith('recipes/')),'source-bound business plans must not be distributed');
  const root=await mkdtemp(join(tmpdir(),'conversion-files-'));t.after(()=>rm(root,{recursive:true,force:true}));
  const bytes=Buffer.from('original bytes'),hash=createHash('sha256').update(bytes).digest('hex');
  await writeFile(join(root,'input.docx'),bytes);
  const ctx={workingDirectory:{async ensure(){return root;}},fs:{
    async resolve(path){return {targetKey:join(root,path)};},async readBytes(target){return readFile(target.targetKey);},
    async publish(path,data){await mkdir(join(path,'..'),{recursive:true});await writeFile(path,data);},
  }};
  const result=await prepareConversionResources(ctx,{agent:{}},{conversion_resources:true,task:{id,kind:'test',sourceSha256:hash},business_file:{path:'input.docx',name:'原始记录.docx'}});
  assert.deepEqual(await readFile(join(root,result.source_path)),bytes);
  assert.ok((await readFile(join(root,'标准模板/test.xlsx'))).length>1000);
  for(const file of conversionFiles)assert.ok((await readFile(join(root,result.skill_resources,file))).length>0);
  await assert.rejects(()=>prepareConversionResources(ctx,{agent:{}},{conversion_resources:true,task:{id,kind:'test',sourceSha256:'wrong'},business_file:{path:'input.docx',name:'原始记录.docx'}}),/SOURCE_MISMATCH/);
});

test('explicit retry keeps the original session and uses a stable identity for the same failure',async()=>{
  const prompts=[];
  const launch=conversionLauncher({sessionController:{async projections(){return {values:{}};},async prompt(body,signal){signal.throwIfAborted();prompts.push(body);}}},
    {business:async(action,body)=>action==='conversion-queue'?{tasks:[{id,status:'FAILED',updatedAt:'fixed-failure'}]}:
      body.action==='resolve'?{id:'experiment-assistant'}:{expert:{id:'experiment-assistant'}}});
  assert.deepEqual((await launch({batch_id:id})).tasks,[]);
  await launch({batch_id:id,retry_failed:true});await launch({batch_id:id,retry_failed:true});
  assert.equal(prompts.length,2);assert.equal(prompts[0].sessionId,conversionSession(id));
  assert.equal(prompts[0].requestId,prompts[1].requestId);
  assert.match(prompts[0].content[0].text,/复用已有资料和有效计划/);
});
