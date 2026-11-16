import test from 'node:test';
import assert from 'node:assert/strict';
import {resolve,join} from 'node:path';
import {mkdtemp,mkdir,rm,writeFile,symlink,readFile} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {createRequire} from 'node:module';
import {pathToFileURL} from 'node:url';
import {mountAgentLoopTestDependencies,mountAgentLoopTestHarness} from '../../../vendor/deepseek-harness/packages/test-support/agent-loop-testkit/lib/index.js';
import {executeToolFixture} from '../../../vendor/deepseek-harness/packages/test-support/tool-testkit/lib/index.js';
import * as FileTools from '../../../vendor/deepseek-harness/packages/fs/tool-fs/lib/index.js';
import * as SearchTools from '../../../vendor/deepseek-harness/packages/fs/tool-fs-search/lib/index.js';
import * as BashTool from '../../../vendor/deepseek-harness/packages/shell/tool-bash/lib/index.js';
import * as ShellEnv from '../../../vendor/deepseek-harness/packages/shell/shell-env/lib/index.js';
import * as PresentTool from '../../../vendor/deepseek-harness/packages/deliverables/tool-present/lib/index.js';
import LocalAttachmentStore from '../../../vendor/deepseek-harness/packages/attachment/attachment-local/lib/index.js';
import SandboxPolicy from '../../../vendor/deepseek-harness/packages/sandbox/sandbox-policy/lib/index.js';
import {LlmAdapter} from '../../../vendor/deepseek-harness/packages/llm/llm/lib/index.js';
import EnterpriseFileSystem from '../../enterprise-plugin/src/filesystem.mjs';
import EnterpriseWorkingDirectory from '../../enterprise-plugin/src/working-directory.mjs';
import {ensureTaskDirectory} from '../../enterprise-plugin/src/task-directories.mjs';
import {installNativeRuntime,installNativeToolGuard} from '../../enterprise-plugin/src/native-runtime.mjs';
import {backgroundNativeTools,confineBackgroundRead} from '../../enterprise-plugin/src/background-reads.mjs';

const require=createRequire(new URL('../../../vendor/deepseek-harness/packages/fs/fs-local/package.json',import.meta.url));
const {Context}=await import(pathToFileURL(require.resolve('@deepseek-ai/cordis')).href);

test('background native readers are limited to their task root even after changing subdirectory',async()=>{
  const root=resolve('owner/tasks/current');
  const ctx={workingDirectory:{ensure:async()=>join(root,'nested'),taskRoot:()=>root},
    fs:{hostPath:p=>p,resolve:async(p,{cwd})=>({targetKey:resolve(cwd,p)})}};
  for(const name of ['read','read_image','glob','grep']){
    assert.ok(backgroundNativeTools.has(name));
    const field=['read','read_image'].includes(name)?'file_path':'path';
    await confineBackgroundRead(ctx,{name,agent:{},arguments:{[field]:'.business-data/source.docx'}});
    await confineBackgroundRead(ctx,{name,agent:{},arguments:{[field]:'../source.txt'}});
    await assert.rejects(confineBackgroundRead(ctx,{name,agent:{},arguments:{[field]:'../../other/private.txt'}}),/BACKGROUND_FILE_OUTSIDE_TASK/);
  }
  for(const name of ['bash','write','edit','apply_patch','present','job_kill'])assert.equal(backgroundNativeTools.has(name),false);
  ctx.workingDirectory.taskRoot=()=>undefined;
  await assert.rejects(confineBackgroundRead(ctx,{name:'read',agent:{},arguments:{file_path:'source.txt'}}),/BACKGROUND_TASK_DIRECTORY_REQUIRED/);
});

test('mounted native file and search tools read background task materials while mutation, delivery and cross-task access are rejected',async t=>{
  const base=await mkdtemp(join(tmpdir(),'aird-background-reading-')),root=join(base,'owner');
  await mkdir(join(root,'default'),{recursive:true});
  const previous=process.env.JSD_DSH_EXECUTION_ROOT;process.env.JSD_DSH_EXECUTION_ROOT=root;
  const ctx=new Context();
  t.after(async()=>{
    await ctx.fiber.dispose();
    if(previous===undefined)delete process.env.JSD_DSH_EXECUTION_ROOT;else process.env.JSD_DSH_EXECUTION_ROOT=previous;
    await rm(base,{recursive:true,force:true});
  });
  await mountAgentLoopTestDependencies(ctx,{systemPrompt:{includeRuntimeContext:false}});
  await ctx.plugin(EnterpriseFileSystem);
  await ctx.plugin(EnterpriseWorkingDirectory,{defaultDirectory:root});
  await ctx.plugin(SandboxPolicy,{mode:'workspace-write',workspaceRoot:root});
  await ctx.plugin(LocalAttachmentStore,{dshHome:join(base,'home')});
  ctx.provide('web',{registerSearchProvider:()=>{}});
  const calls=[],runs=new Map();
  installNativeRuntime(ctx,{runs,profileOf:()=> 'agent',business:async(action,body)=>{
    calls.push({action,body});
    assert.equal(action,'gate','A background read must never execute or register a file');
    return {allowed:true};
  }});
  installNativeToolGuard(ctx,{runs});
  await ctx.plugin(FileTools,{applyPatch:true});
  await ctx.plugin(SearchTools,{sampleOverCapGlobResults:false});
  await ctx.plugin(ShellEnv,{dshHome:join(base,'home')});
  await ctx.plugin(BashTool);
  await ctx.plugin(PresentTool);
  class ImageRoute extends LlmAdapter {
    async resolveModel(provider,model){return {provider,id:model,name:model,inputModalities:['text','image']};}
  }
  ctx.llm.registerAdapter(['image-fixture'],new ImageRoute());
  const harness=await mountAgentLoopTestHarness(ctx);
  const agent=await harness.create('background-materials',{provider:'image-fixture',model:'image-fixture'}, {cwd:join(root,'default')});
  const directory=await ensureTaskDirectory(ctx,agent);
  runs.set(agent.id,{id:'background-run',dsh_turn:1,pack_ids:['files'],tools:[],automation:{builtin_id:'project-morning'}});
  const original=Buffer.from('编号 000123\n原值 0\n缺值为空\n');
  const image=Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGP4z8AAAAMBAQDJ/pLvAAAAAElFTkSuQmCC','base64');
  await ctx.fs.publish(directory+'/.business-data/会议 正文.txt',original);
  await ctx.fs.publish(directory+'/.business-data/检测 图.png',image);
  await mkdir(ctx.fs.hostPath(directory+'/中文 子目录'),{recursive:true});
  await ctx.workingDirectory.set(agent,'中文 子目录');
  await writeFile(join(root,'default','private.txt'),'不属于本次报告');
  const sibling=join(root,'tasks','other','task');await mkdir(sibling,{recursive:true});
  await writeFile(join(sibling,'private.txt'),'另一任务文件');
  await symlink(sibling,ctx.fs.hostPath(directory+'/越界入口'),process.platform==='win32'?'junction':'dir');
  let serial=0;
  const execute=(name,args)=>executeToolFixture(ctx,{name,arguments:args,agent,callId:'read-'+(++serial),signal:new AbortController().signal});
  const read=await execute('read',{file_path:'../.business-data/会议 正文.txt'});
  assert.equal(read.isError,false,JSON.stringify(read));
  assert.match(JSON.stringify(read.content),/编号 000123/);
  assert.match(JSON.stringify(read.content),/原值 0/);
  const pixels=await execute('read_image',{file_path:directory+'/.business-data/检测 图.png'});
  assert.equal(pixels.isError,false,JSON.stringify(pixels));
  assert.equal(pixels.value.image.width,1);
  assert.equal(pixels.value.image.height,1);
  const glob=await execute('glob',{path:'../.business-data',pattern:'**/*.txt'});
  assert.equal(glob.isError,false,JSON.stringify(glob));
  assert.equal(glob.value.paths.length,1);
  assert.match(glob.value.paths[0],/会议 正文\.txt$/);
  const grep=await execute('grep',{path:'../.business-data',pattern:'原值 0',include:'*.txt'});
  assert.equal(grep.isError,false,JSON.stringify(grep));
  assert.equal(grep.value.matches.length,1);
  assert.equal(grep.value.matches[0].lineNumber,2);
  assert.equal(grep.value.matches[0].line,'原值 0');
  assert.equal(calls.length,4);
  for(const name of ['read','read_image','glob','grep']) {
    const field=['read','read_image'].includes(name)?'file_path':'path';
    for(const path of ['/workspaces/default/private.txt','/workspaces/tasks/other/task/private.txt',directory+'/越界入口/private.txt']) {
      const result=await execute(name,{[field]:path,...(['glob','grep'].includes(name)?{pattern:'*'}:{})});
      assert.equal(result.isError,true,`${name} must reject ${path}`);
      assert.match(JSON.stringify(result),/BACKGROUND_FILE_OUTSIDE_TASK|FS_SANDBOX_DENIED/);
    }
  }
  for(const [name,args] of [
    ['write',{file_path:'../.business-data/会议 正文.txt',content:'覆盖'}],
    ['edit',{file_path:'../.business-data/会议 正文.txt',old_string:'原值 0',new_string:'原值 1'}],
    ['apply_patch',{patch:'*** Begin Patch\n*** Add File: overwrite.txt\n+覆盖\n*** End Patch'}],
    ['bash',{command:'touch overwrite.txt',description:'写入文件'}],
    ['present',{files:[{path:'../.business-data/会议 正文.txt'}]}],
  ]) {
    const result=await execute(name,args);
    assert.equal(result.isError,true,`${name} must be rejected`);
    assert.match(JSON.stringify(result),/BACKGROUND_READ_ONLY/);
  }
  assert.equal(calls.length,4,'Denied tools must not reach Control, execution or registration');
  assert.deepEqual(await readFile(ctx.fs.hostPath(directory+'/.business-data/会议 正文.txt')),original);
});
