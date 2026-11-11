import { AsyncLocalStorage } from 'node:async_hooks';
import { relative, sep } from 'node:path';
import { createHash } from 'node:crypto';
import { LocalBashExecutor } from '../../../vendor/deepseek-harness/packages/shell/bash-local/lib/index.js';
import { LocalSubprocessRuntime } from '../../../vendor/deepseek-harness/packages/subprocess/subprocess-local/lib/index.js';
import { sha256 } from './filesystem.mjs';
import { scheduleTools } from './scheduling.mjs';
import { SandboxProcess } from './sandbox-process.mjs';

export const nativeTools = new Set(['read','read_image','write','edit','apply_patch','bash','present','todo_write',
  'glob','grep','skill','ask_user_question','web_search','web_fetch','working_directory',
  'enter_plan_mode','exit_plan_mode','get_goal','create_goal','update_goal','job_output','job_list','job_kill', ...scheduleTools]);

/** Native tools retain their schemas, editing semantics, terminal cards and delivery cards. */
export function installNativeRuntime(ctx, {business,runs,profileOf,searchAvailable=false}) {
  const current = new AsyncLocalStorage();
  const bodyFor = (exec, more={}) => {
    const bound=runs.get(exec.agent?.id);
    if(!bound || profileOf(exec.agent)!=='agent' || !bound.pack_ids.includes('files')) throw new Error('CAPABILITY_NOT_AUTHORIZED');
    return {run_id:bound.id,session_id:exec.agent.id,turn:bound.dsh_turn,call_id:exec.callId,...more};
  };
  const pathOf = target => relative(ctx.fs.root,String(target.targetKey)).split(sep).join('/');
  const register = async(exec,path,index=0,source_quotes=[])=> {
    const cwd=await ctx.workingDirectory.ensure(exec.agent,exec.signal), target=await ctx.fs.resolve(path,{cwd,signal:exec.signal});
    const content=await ctx.fs.readBytes(target,exec.signal,10*1024*1024);
    const save = suffix => business('native-tool',bodyFor(exec,{name:'artifact_save',call_id:exec.callId+'/file-'+index+suffix,
      arguments:{path:pathOf(target),base64:Buffer.from(content).toString('base64'),sha256:sha256(content),source_quotes}}),exec.signal);
    let value=await save('');
    if(value.error?.startsWith('ARTIFACT_EXECUTION_HASH_MISMATCH:')) {
      // Native write/edit can follow bash. Freeze the current workspace through
      // the same broker, rather than requiring the model to invent a shell retry.
      // This creates a real receipt; unknown outcomes are never replayed here.
      const bound=bodyFor(exec,{call_id:exec.callId+'/checkpoint-'+index});
      await business('gate',{...bound,kind:'tool',native_name:'bash'},exec.signal);
      const checkpoint=new SandboxProcess(ctx,{
        argv:['bash','-c',':'],cwd:ctx.fs.processPath(await ctx.fs.resolve(cwd)),env:{},signal:exec.signal,
        stdio:{stdout:{maxBytes:4096},stderr:{maxBytes:4096}},
      },{business,payload:bound,agent:exec.agent});
      const result=await checkpoint.done;
      if(result.exitCode!==0)throw new Error('ARTIFACT_CHECKPOINT_FAILED: file was not registered');
      exec.signal?.throwIfAborted();
      const currentContent=await ctx.fs.readBytes(target,exec.signal,10*1024*1024);
      if(sha256(currentContent)!==sha256(content))throw new Error('WORKSPACE_CHANGED_DURING_REGISTRATION');
      value=await save('-checkpointed');
    }
    if(value.error)throw new Error(value.error);
    return value;
  };
  ctx.on('tools/execute',async(exec,next)=>{
    if(!nativeTools.has(exec.name)) return next();
    const bound=runs.get(exec.agent?.id);
    if(!bound)throw new Error('ENTERPRISE_TOOL_DENIED');
    if (['glob','grep'].includes(exec.name)) {
      await ctx.fs.resolve(exec.arguments.path || '.', {cwd:await ctx.workingDirectory.ensure(exec.agent,exec.signal),signal:exec.signal});
    }
    await business('gate',{run_id:bound.id,session_id:exec.agent.id,turn:bound.dsh_turn,kind:['bash','present','web_search'].includes(exec.name)?'tool':'native-tool',native_name:exec.name,call_id:exec.callId},exec.signal);
    return current.run(exec,async()=>{
      if(exec.name==='present')for(const [index,file] of exec.arguments.files.entries())await register(exec,file.path,index);
      return next();
    });
  });
  ctx.web.registerSearchProvider({
    id:'enterprise-search', title:'企业联网搜索',
    available:()=>searchAvailable, ready:async()=>searchAvailable,
    async search(request,signal) {
      const exec=current.getStore();
      if(!exec || exec.name!=='web_search')throw new Error('ENTERPRISE_SEARCH_REQUIRES_TOOL');
      const query={query:request.query,max_results:request.maxResults ?? 10};
      const key=createHash('sha256').update(JSON.stringify(query)).digest('hex').slice(0,16);
      const result=await business('native-tool',bodyFor(exec,{name:'dsh_web_search',call_id:exec.callId+'/search-'+key,
        arguments:query}),signal);
      if(result.error)throw new Error(result.error);
      return result;
    },
  });
  // Native glob/grep own their argv and parsers. Only map their verified file
  // target into the managed filesystem; never expose a generic host spawn.
  class EnterpriseSubprocess extends LocalSubprocessRuntime {
    constructor(scope) {
      super(scope);
      this.remote=new Set();
      scope.effect(()=>async()=>{
        const handles=[...this.remote];
        for(const handle of handles)handle.terminate();
        const results=await Promise.allSettled(handles.map(async handle=>{
          if(!await handle.waitForExit(AbortSignal.timeout(12000)))throw new Error('SANDBOX_CLEANUP_PENDING');
        }));
        const failures=results.filter(value=>value.status==='rejected').map(value=>value.reason);
        if(failures.length)throw new AggregateError(failures,'Sandbox disposal requires receipt reconciliation');
      });
    }
    spawn(spec) {
      const exec = current.getStore();
      if (!exec) return super.spawn(spec);
      if(exec.name==='bash' && spec.argv.length===3 && spec.argv[0]==='bash' && spec.argv[1]==='-c') {
        const handle=new SandboxProcess(this.ctx,spec,{business,payload:bodyFor(exec),agent:exec.agent});
        this.remote.add(handle);
        void handle.done.finally(()=>{if(handle.quiescent)this.remote.delete(handle);}).catch(()=>{});
        return handle;
      }
      if (!['glob','grep'].includes(exec.name) || !/[\\/]rg(?:\.exe)?$/.test(spec.argv[0]))
        throw new Error('ENTERPRISE_HOST_PROCESS_DENIED');
      const argv = [...spec.argv], separator = argv.indexOf('--');
      if (separator >= 0) argv[separator+1] = ctx.fs.hostPath(argv[separator+1],spec.cwd);
      return super.spawn({...spec,argv,cwd:ctx.fs.hostPath(spec.cwd)});
    }
    async spawnTerminal() {throw new Error('ENTERPRISE_HOST_TERMINAL_DENIED');}
  }
  ctx.plugin(EnterpriseSubprocess);
  class EnterpriseShell extends LocalBashExecutor {
    get sandboxMode(){return 'workspace-write';}
    resolve(request) {
      if(request.sandboxPolicy?.mode==='danger-full-access'||request.sandboxPolicy?.mode==='read-only')throw new Error('ENTERPRISE_SHELL_POLICY_DENIED');
      const exec=current.getStore();
      const spec=super.resolve({...request,workdir:request.workdir||(exec?ctx.workingDirectory.get(exec.agent.session):ctx.fs.processPath({targetKey:ctx.fs.root}))});
      return {...spec,timeoutMs:Math.min(spec.timeoutMs,600000)};
    }
    async execute(spec) {
      if(!current.getStore())throw new Error('ENTERPRISE_SHELL_REQUIRES_TOOL');
      const handle=await super.execute(spec), result=handle.result.bind(handle);
      handle.result=async()=>({...await result(),sandbox:{mode:'workspace-write',denied:false,enforcement:'full'}});
      return handle;
    }
  }
  ctx.plugin(EnterpriseShell);
  return {register};
}
