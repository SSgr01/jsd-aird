import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp, mkdir, rm, writeFile} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {createRequire} from 'node:module';
import {pathToFileURL, fileURLToPath} from 'node:url';
import {mountAgentLoopTestDependencies, mountAgentLoopTestHarness} from '../../../vendor/deepseek-harness/packages/test-support/agent-loop-testkit/lib/index.js';
import {executeToolFixture} from '../../../vendor/deepseek-harness/packages/test-support/tool-testkit/lib/index.js';
import * as DirectoryTool from '../../../vendor/deepseek-harness/packages/session/tool-working-directory/lib/index.js';
import EnterpriseFileSystem from '../../enterprise-plugin/src/filesystem.mjs';
import {SandboxProcess} from '../../enterprise-plugin/src/sandbox-process.mjs';
import {enterprisePatch, standardPlugins} from '../src/config.mjs';
import {composeEntries} from '../../../vendor/deepseek-harness/packages/boot/app-boot/lib/index.js';
import * as Instructions from '../../enterprise-plugin/src/instruction-runtime.mjs';
import {installTaskDirectories,ensureTaskDirectory} from '../../enterprise-plugin/src/task-directories.mjs';
import EnterpriseWorkingDirectory from '../../enterprise-plugin/src/working-directory.mjs';

const require=createRequire(new URL('../../../vendor/deepseek-harness/packages/session/working-directory/package.json',import.meta.url));
const {Context}=await import(pathToFileURL(require.resolve('@deepseek-ai/cordis')).href);

test('the mounted enterprise directory service unifies native context, tools, restored state and sandbox transfers', async t=>{
  const root=await mkdtemp(join(tmpdir(),'aird-directory-runtime-'));
  const previous=process.env.JSD_DSH_EXECUTION_ROOT;
  process.env.JSD_DSH_EXECUTION_ROOT=root;
  await mkdir(join(root,'default','乳液 工艺'),{recursive:true});
  await writeFile(join(root,'.dsh-workspace-root'),'');
  await writeFile(join(root,'default','AGENTS.md'),'保留所有原始记录。');
  const ctx=new Context();
  t.after(async()=>{
    await ctx.fiber.dispose();
    if(previous===undefined)delete process.env.JSD_DSH_EXECUTION_ROOT;else process.env.JSD_DSH_EXECUTION_ROOT=previous;
    await rm(root,{recursive:true,force:true});
  });
  await mountAgentLoopTestDependencies(ctx,{systemPrompt:{includeRuntimeContext:false}});
  await ctx.plugin(EnterpriseFileSystem);
  // Load the deployment's selected service rather than a hand-built object
  // calling the subclass prototype, which misses Cordis method interception.
  const patch=enterprisePatch({plugin:fileURLToPath(new URL('../../enterprise-plugin/',import.meta.url)),
    executionRoot:root,model:{id:'qwen-test'}});
  assert.match(standardPlugins({plugin:fileURLToPath(new URL('../../enterprise-plugin/',import.meta.url))})
    .find(plugin=>plugin.id==='agent-instructions').name,/instruction-runtime\.mjs$/);
  const entries=composeEntries([[{insert:[{id:'working-directory',name:'@deepseek-ai/dsh-working-directory'}]},...patch]]);
  assert.equal(entries.find(plugin=>plugin.id==='working-directory').disabled,true);
  const row=entries.find(plugin=>plugin.id==='enterprise-working-directory');
  assert.match(row.name,/working-directory\.mjs$/);
  const Directory=(await import(pathToFileURL(row.name).href)).default;
  await ctx.plugin(Directory,{defaultDirectory:root});
  await ctx.plugin(DirectoryTool);
  const harness=await mountAgentLoopTestHarness(ctx);
  const agent=await harness.create('directory-runtime',{}, {cwd:join(root,'default')});
  await ctx.plugin(Instructions,{maxBytes:16000,projectRootMarkers:['.dsh-workspace-root']});
  const execute=arguments_=>executeToolFixture(ctx,{name:'working_directory',callId:'directory-call',arguments:arguments_,
    signal:new AbortController().signal,agent});
  assert.equal(ctx.workingDirectory.get(agent.session),'/workspaces/default');
  const decision = await ctx.waterfall('agent/pre-step', {
    agent, modelSelection:{provider:'mock',model:'mock'}, messages:[],turn:1,step:1,
    signal:new AbortController().signal,
  }, async()=>({kind:'enter',messages:[]}));
  assert.equal(decision.kind,'enter');
  assert.match(JSON.stringify(agent.inbox.nextStep),/保留所有原始记录/);
  assert.deepEqual((await execute({})).value,{cwd:'/workspaces/default'});
  assert.deepEqual((await execute({cd:'乳液 工艺'})).value,{cwd:'/workspaces/default/乳液 工艺'});
  const assembly=await ctx.systemPrompt.assemble({agent,scope:agent});
  assert.equal(assembly.contexts.find(item=>item.name==='working-directory:current').text,
    'Current working directory: "/workspaces/default/乳液 工艺".');
  const cwd=await ctx.workingDirectory.ensure(agent);
  await ctx.fs.writeText(await ctx.fs.resolve('source.txt',{cwd}),'零值 0',{kind:'createIfAbsent'});
  assert.equal(await ctx.fs.readText(await ctx.fs.resolve('source.txt',{cwd})),'零值 0');
  await writeFile(join(root,'default','乳液 工艺','AGENTS.md'),'编号应保留前导零。');
  await ctx.parallel('tools/result', {
    name:'read', token:Symbol('instruction-touch'), agent,
    arguments:{file_path:'/workspaces/default/乳液 工艺/source.txt'},
    signal:new AbortController().signal,
  }, {isError:false});
  const nestedDecision=await ctx.waterfall('agent/pre-step', {
    agent, modelSelection:{provider:'mock',model:'mock'},messages:[],turn:1,step:2,
    signal:new AbortController().signal,
  },async()=>({kind:'enter',messages:[]}));
  assert.match(JSON.stringify(nestedDecision),/编号应保留前导零/);
  assert.equal(ctx.workingDirectory.get(agent.session),cwd);
  let transferred;
  const processHandle=new SandboxProcess(ctx,{argv:['bash','-c',':'],cwd,env:{},signal:new AbortController().signal,
    stdio:{stdout:{maxBytes:4096},stderr:{maxBytes:4096}}},{agent,payload:{run_id:'directory-run',call_id:'directory-shell'},
    business:async(action,body)=>{
      if(action==='native-output')return {status:'SUCCEEDED',streams:{}};
      transferred=body.arguments;
      return {exit_code:0,files:body.arguments.files,receipt:{status:'SUCCEEDED'}};
    }});
  assert.equal((await processHandle.done).exitCode,0);
  assert.equal(transferred.cwd,'default/乳液 工艺');
  assert.deepEqual(transferred.files.map(file=>file.path),['default/乳液 工艺/AGENTS.md','default/乳液 工艺/source.txt']);
  // A restored legacy projection may contain a host path; public directory
  // behavior still uses the execution-world path without rewriting history.
  agent.session.append('working-directory/change',{cwd:join(root,'default')});
  assert.deepEqual((await execute({})).value,{cwd:'/workspaces/default'});
  assert.equal(ctx.workingDirectory.get(agent.session),'/workspaces/default');
  const sibling=await harness.create('directory-sibling',{}, {cwd:join(root,'default')});
  assert.equal(ctx.workingDirectory.get(sibling.session),'/workspaces/default');
});

test('new native tasks have independent files, forks copy files, and restores keep their task directory', async t=>{
  const root=await mkdtemp(join(tmpdir(),'aird-task-directory-'));
  const previous=process.env.JSD_DSH_EXECUTION_ROOT;
  process.env.JSD_DSH_EXECUTION_ROOT=root;
  await mkdir(join(root,'default'),{recursive:true});
  await writeFile(join(root,'default','build_workbook.py'),'old shared script');
  const ctx=new Context();
  t.after(async()=>{
    await ctx.fiber.dispose();
    if(previous===undefined)delete process.env.JSD_DSH_EXECUTION_ROOT;else process.env.JSD_DSH_EXECUTION_ROOT=previous;
    await rm(root,{recursive:true,force:true});
  });
  await mountAgentLoopTestDependencies(ctx,{systemPrompt:{includeRuntimeContext:false}});
  await ctx.plugin(EnterpriseFileSystem);
  await ctx.plugin(EnterpriseWorkingDirectory,{defaultDirectory:root});
  await ctx.plugin(DirectoryTool);
  installTaskDirectories(ctx);
  const harness=await mountAgentLoopTestHarness(ctx);
  const first=await harness.create('task-one',{}, {cwd:join(root,'default')});
  const second=await harness.create('task-two',{}, {cwd:join(root,'default')});
  const firstRoot=await ctx.workingDirectory.ensure(first),secondRoot=await ctx.workingDirectory.ensure(second);
  assert.match(firstRoot,/^\/workspaces\/tasks\/task-one\/[0-9a-f-]{36}$/);
  assert.notEqual(firstRoot,secondRoot);
  assert.deepEqual(await ctx.fs.snapshot(firstRoot),[]);
  assert.equal(first.session.header.cwd,join(root,'default'));
  const own=await ctx.fs.resolve('build_workbook.py',{cwd:firstRoot});
  await ctx.fs.writeText(own,'new task script',{kind:'createIfAbsent'});
  assert.equal(await ctx.fs.stat(await ctx.fs.resolve('build_workbook.py',{cwd:secondRoot})),undefined);
  await mkdir(ctx.fs.hostPath(firstRoot+'/中文 资料'),{recursive:true});
  await ctx.workingDirectory.set(first,'中文 资料');
  const cwd=await ctx.workingDirectory.ensure(first);
  assert.equal(cwd,firstRoot+'/中文 资料');
  const bytes=Buffer.from('编号,数量\n001,0\n');
  const {sha256}=await import('../../enterprise-plugin/src/filesystem.mjs');
  await ctx.fs.exposeAttachment({attachmentId:'sha256:'+sha256(bytes),name:'原始 记录.csv',bytes:bytes.length},bytes,cwd,'host-attachment');
  const ref=ctx.fs.processPathFromHostPath('host-attachment');
  assert.ok(ref.startsWith(cwd+'/.dsh-attachments/'));
  await ctx.fs.exposeAttachment({attachmentId:'sha256:'+sha256(bytes),name:'原始 记录.csv',bytes:bytes.length},bytes,secondRoot,'host-attachment');
  assert.equal(ctx.fs.processPathFromHostPath('host-attachment'),undefined);
  async function* mapped() {
    await new Promise(resolve=>setTimeout(resolve,5));
    yield ctx.fs.processPathFromHostPath('host-attachment');
  }
  const collect=async directory=>{const values=[];for await(const value of ctx.fs.scopedAttachments(directory,mapped()))values.push(value);return values;};
  const [firstPaths,secondPaths]=await Promise.all([collect(cwd),collect(secondRoot)]);
  assert.equal(firstPaths[0],ref);
  assert.ok(secondPaths[0].startsWith(secondRoot+'/.dsh-attachments/'));
  let transferred;
  const processHandle=new SandboxProcess(ctx,{argv:['bash','-c',':'],cwd,env:{},signal:new AbortController().signal,
    stdio:{stdout:{maxBytes:4096},stderr:{maxBytes:4096}}},{agent:first,payload:{run_id:'task-run',call_id:'task-shell'},
    business:async(action,body)=>{
      if(action==='native-output')return {status:'SUCCEEDED',streams:{}};
      transferred=body.arguments;return {exit_code:0,files:body.arguments.files,receipt:{status:'SUCCEEDED'}};
    }});
  assert.equal((await processHandle.done).exitCode,0);
  assert.equal(transferred.cwd,cwd.slice('/workspaces/'.length));
  assert.equal(transferred.files.length,1);
  assert.ok(transferred.files.every(file=>file.path.startsWith(transferred.cwd+'/')));
  await assert.rejects(ctx.workingDirectory.set(first,secondRoot),/TASK_DIRECTORY_OUTSIDE_SESSION/);
  // The native fork takes an inherited event prefix and must copy the source
  // directory rather than sharing its writable files.
  const seed=first.session.snapshotEvents();
  const handle=await ctx.agents.create({sessionId:'task-fork',seed,inheritedEventCount:first.session.seq,
    meta:{cwd:first.session.header.cwd,isSeeded:true},agentOptions:{provider:'mock',model:'mock'}});
  const forkRoot=await ctx.workingDirectory.ensure(handle.agent);
  assert.match(forkRoot,/^\/workspaces\/tasks\/task-fork\//);
  assert.notEqual(forkRoot,firstRoot);
  assert.equal((await ctx.fs.snapshot(forkRoot)).length,1);
  assert.equal(await ctx.fs.readText(await ctx.fs.resolve(ref)),bytes.toString());
  // The same native directory projection is read by a restored service, and
  // the resume lifecycle does not allocate a second task directory.
  await ctx.serial('agent/created',{agent:first,source:'resume'});
  assert.equal(await ctx.workingDirectory.ensure(first),cwd);
  await rm(ctx.fs.hostPath(cwd),{recursive:true,force:true});
  assert.equal(await ctx.workingDirectory.ensure(first),firstRoot);
  await rm(ctx.fs.hostPath(firstRoot),{recursive:true,force:true});
  await assert.rejects(ctx.workingDirectory.ensure(first),/TASK_DIRECTORY_UNAVAILABLE/);
  assert.equal(await ctx.workingDirectory.ensure(second),secondRoot);
});

test('a legacy scheduled session receives an empty task directory once and leaves historical workspace files in place',async t=>{
  const root=await mkdtemp(join(tmpdir(),'aird-legacy-briefing-'));
  const previous=process.env.JSD_DSH_EXECUTION_ROOT;process.env.JSD_DSH_EXECUTION_ROOT=root;
  const ctx=new Context();
  t.after(async()=>{
    await ctx.fiber.dispose();
    if(previous===undefined)delete process.env.JSD_DSH_EXECUTION_ROOT;else process.env.JSD_DSH_EXECUTION_ROOT=previous;
    await rm(root,{recursive:true,force:true});
  });
  await mkdir(join(root,'default'),{recursive:true});
  await writeFile(join(root,'default','history.txt'),'原会话文件');
  await mountAgentLoopTestDependencies(ctx,{systemPrompt:{includeRuntimeContext:false}});
  await ctx.plugin(EnterpriseFileSystem);
  await ctx.plugin(EnterpriseWorkingDirectory,{defaultDirectory:root});
  const harness=await mountAgentLoopTestHarness(ctx);
  const agent=await harness.create('legacy-briefing',{}, {cwd:join(root,'default')});
  const directory=await ensureTaskDirectory(ctx,agent);
  assert.match(directory,/^\/workspaces\/tasks\/legacy-briefing\//);
  assert.deepEqual(await ctx.fs.snapshot(directory),[]);
  assert.equal(await ctx.fs.readText(await ctx.fs.resolve('/workspaces/default/history.txt')),'原会话文件');
  assert.equal(await ensureTaskDirectory(ctx,agent),directory);
  assert.equal(agent.session.snapshotEvents().filter(event=>event.type==='working-directory/change').length,1);
  await rm(ctx.fs.hostPath(directory),{recursive:true,force:true});
  await assert.rejects(ensureTaskDirectory(ctx,agent),/TASK_DIRECTORY_UNAVAILABLE/);
});
