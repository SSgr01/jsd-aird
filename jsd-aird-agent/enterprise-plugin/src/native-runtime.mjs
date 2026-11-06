import { AsyncLocalStorage } from 'node:async_hooks';
import { relative, sep, isAbsolute } from 'node:path';
import { unlink } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { ShellExecutor } from '../../../vendor/deepseek-harness/packages/shell/shell/lib/index.js';
import { LocalSubprocessRuntime } from '../../../vendor/deepseek-harness/packages/subprocess/subprocess-local/lib/index.js';
import { worldRoot, sha256 } from './filesystem.mjs';
import { scheduleTools } from './scheduling.mjs';

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
    const value=await business('native-tool',bodyFor(exec,{name:'artifact_save',call_id:exec.callId+'/file-'+index,
      arguments:{path:pathOf(target),base64:Buffer.from(content).toString('base64'),sha256:sha256(content),source_quotes}}),exec.signal);
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
    await business('gate',{run_id:bound.id,session_id:exec.agent.id,turn:bound.dsh_turn,kind:['bash','present','web_search'].includes(exec.name)?'tool':'native-tool',call_id:exec.callId},exec.signal);
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
    spawn(spec) {
      const exec = current.getStore();
      if (!exec) return super.spawn(spec);
      if (!['glob','grep'].includes(exec.name) || !/[\\/]rg(?:\.exe)?$/.test(spec.argv[0]))
        throw new Error('ENTERPRISE_HOST_PROCESS_DENIED');
      const argv = [...spec.argv], separator = argv.indexOf('--');
      if (separator >= 0) argv[separator+1] = ctx.fs.hostPath(argv[separator+1],spec.cwd);
      return super.spawn({...spec,argv,cwd:ctx.fs.hostPath(spec.cwd)});
    }
    async spawnTerminal() {throw new Error('ENTERPRISE_HOST_TERMINAL_DENIED');}
  }
  ctx.plugin(EnterpriseSubprocess);
  class EnterpriseShell extends ShellExecutor {
    constructor(scope) {super(scope);this.pending=new Set();scope.effect(()=>()=>{for(const stop of this.pending)stop();});}
    get sandboxMode(){return 'workspace-write';}
    resolve(request) {
      if(request.sandboxPolicy?.mode==='danger-full-access'||request.sandboxPolicy?.mode==='read-only')throw new Error('ENTERPRISE_SHELL_POLICY_DENIED');
      return {...request,workdir:request.workdir||ctx.fs.root,timeoutMs:Math.min(request.timeoutMs||120000,120000),onExpiry:'kill',stdoutMaxBytes:12000};
    }
    async execute(spec) {
      const exec=current.getStore();if(!exec)throw new Error('ENTERPRISE_SHELL_REQUIRES_TOOL');
      const payload=bodyFor(exec), stopSignal=new AbortController();let aborted=false,timedOut=false,cursor=0,failure;
      const handle={status:'running',exitCode:null,signal:null,sandbox:{mode:'workspace-write',denied:false,enforcement:'full'},
        readOutput(){const delta=(handle.stdout||'').slice(cursor);cursor=(handle.stdout||'').length;return {delta,lossy:false};},
        observed:{stdout:{readFrom(offset){const text=handle.stdout||'';return {text:text.slice(offset),nextOffset:text.length,lossy:false};}},stderr:{readFrom(){return {text:'',nextOffset:0,lossy:false};}}},
        kill(){if(handle.status!=='running'||stopSignal.signal.aborted)return false;aborted=true;stopSignal.abort();return true;}};
      const cancel=()=>{void business('native-cancel',payload).catch(error=>ctx.logger.warn('Sandbox cancellation pending: %s',error.message));};
      stopSignal.signal.addEventListener('abort',cancel,{once:true});
      const abort=()=>handle.kill();spec.signal?.addEventListener('abort',abort,{once:true});
      if(spec.signal?.aborted)abort();
      const timer=setTimeout(()=>{if(!aborted){timedOut=true;stopSignal.abort();}},spec.timeoutMs);
      this.pending.add(abort);
      const done=(async()=>{
        try {
          stopSignal.signal.throwIfAborted();
          const base=await ctx.workingDirectory.ensure(exec.agent,stopSignal.signal), prefix=pathOf(await ctx.fs.resolve(base));
          const work=await ctx.fs.resolve(spec.workdir), within=relative(base,String(work.targetKey));
          if(within==='..'||within.startsWith('..'+sep)||isAbsolute(within))throw new Error('SHELL_WORKDIR_OUTSIDE_SESSION');
          const before=(await ctx.fs.snapshot(base,stopSignal.signal)).map(file=>({...file,path:[prefix,file.path].filter(Boolean).join('/')}));
          stopSignal.signal.throwIfAborted();
          const response=await business('native-tool',{...payload,name:'dsh_execute',arguments:{command:spec.command,cwd:pathOf(work),files:before}},stopSignal.signal);
          if(response.error && !response.files)throw new Error(response.error);
          // Validate the entire transfer and the original snapshot before publishing any mutation.
          const incoming=new Map();let size=0;
          for(const file of response.files||[]) {
            if(prefix && !file.path.startsWith(prefix+'/'))throw new Error('SANDBOX_OUTPUT_OUTSIDE_SESSION');
            const target=await ctx.fs.resolve(worldRoot+'/'+file.path), bytes=Buffer.from(file.base64,'base64');size+=bytes.length;
            if(incoming.has(file.path)||incoming.size>=50||size>10*1024*1024||sha256(bytes)!==file.sha256)throw new Error('SANDBOX_OUTPUT_INVALID');
            incoming.set(file.path,{target,bytes});
          }
          const now=(await ctx.fs.snapshot(base,stopSignal.signal)).map(file=>({...file,path:[prefix,file.path].filter(Boolean).join('/')}));
          if(JSON.stringify(now)!==JSON.stringify(before))throw new Error('WORKSPACE_CHANGED_DURING_EXECUTION');
          stopSignal.signal.throwIfAborted();
          for(const {target,bytes} of incoming.values())await ctx.fs.publish(String(target.targetKey),bytes);
          for(const file of before)if(!incoming.has(file.path))await unlink(await ctx.fs.checked(worldRoot+'/'+file.path));
          handle.stdout=response.output||'';handle.exitCode=response.exit_code;handle.status='completed';
        } catch(error) {handle.status='killed';handle.signal=aborted||timedOut?'SIGTERM':null;failure=aborted||timedOut?undefined:error;handle.stdout=error.message;}
        finally {clearTimeout(timer);spec.signal?.removeEventListener('abort',abort);this.pending.delete(abort);}
      })();
      handle.done=done;
      handle.result=async()=>{await done;if(failure)throw failure;return {exitCode:handle.exitCode,signal:handle.signal,aborted,timedOut,timeoutMs:spec.timeoutMs,
        stdout:{text:handle.stdout||'',truncated:false},stderr:{text:'',truncated:false},sandbox:handle.sandbox};};
      return handle;
    }
  }
  ctx.plugin(EnterpriseShell);
  return {register};
}
