import { mkdir, unlink } from 'node:fs/promises';
import { relative, sep, isAbsolute } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
import { OutputCollector } from '../../../vendor/deepseek-harness/packages/subprocess/subprocess-local/lib/output.js';
import { worldRoot, sha256 } from './filesystem.mjs';

const terminal = new Set(['SUCCEEDED', 'FAILED', 'CANCELLED']);
const processLogs = path => path.split('/').includes('.dsh-process-output');
const visibleFiles = files => files.filter(file => !processLogs(file.path));

/** Broker transport implementing the native subprocess handle; deadlines and jobs belong to dsh. */
export class SandboxProcess {
  constructor(ctx, spec, { business, payload, agent }) {
    this.ctx=ctx; this.spec=spec; this.business=business; this.payload=Object.freeze({...payload}); this.agent=agent;
    this.quiescent=false; this.stopping=false; this.streams={};
    for(const name of ['stdout','stderr']) {
      const mode=spec.stdio[name];
      if(typeof mode!=='object')throw new Error('SANDBOX_COLLECTED_OUTPUT_REQUIRED');
      this.streams[name]={mode,collector:new OutputCollector(mode.maxBytes,name,undefined),offset:0,base:0,incomplete:false};
    }
    this.collected=Object.fromEntries(Object.entries(this.streams).map(([name,state])=>[name,{readFrom:offset=>{
      const read=state.collector.readFrom(Math.max(0,offset-state.base));
      const spill=read.spillPath && !state.incomplete ? ctx.fs.processPathFromHostPath(read.spillPath) : undefined;
      return {...read,nextOffset:read.nextOffset+state.base,lossy:read.lossy || offset<state.base,
        spillPath:spill};
    }}]));
    this.abort=()=>this.terminate();
    spec.signal?.addEventListener('abort',this.abort,{once:true});
    if(spec.signal?.aborted)this.terminate();
    this.done=this.run().finally(()=>{
      spec.signal?.removeEventListener('abort',this.abort);
      for(const state of Object.values(this.streams))state.collector.seal();
    });
    // The native executor observes the same promise; transport rejection must not be unhandled meanwhile.
    void this.done.catch(()=>{});
  }

  consume(name, value) {
    const state=this.streams[name], bytes=Buffer.from(value.base64,'base64');
    if(!Number.isSafeInteger(value.from_offset) || !Number.isSafeInteger(value.next_offset)
      || !Number.isSafeInteger(value.total_bytes) || value.from_offset<state.offset
      || value.next_offset!==value.from_offset+bytes.length || value.total_bytes<value.next_offset
      || bytes.length>65536)throw new Error('INVALID_SANDBOX_OUTPUT_CURSOR');
    if(value.from_offset!==state.offset) {
      state.collector.seal();
      state.collector=new OutputCollector(state.mode.maxBytes,name,undefined);
      state.base=value.from_offset;state.incomplete=true;
    }
    if(bytes.length)state.collector.push(bytes);
    state.offset=value.next_offset;
    return value.next_offset<value.total_bytes;
  }

  async run() {
    let submitted;
    try {
      this.spec.signal?.throwIfAborted();
      const fs=this.ctx.fs, base=await this.ctx.workingDirectory.ensure(this.agent,this.spec.signal);
      const baseTarget=await fs.resolve(base), prefix=relative(fs.root,String(baseTarget.targetKey)).split(sep).join('/');
      const work=await fs.resolve(this.spec.cwd,{cwd:base}), within=relative(String(baseTarget.targetKey),String(work.targetKey));
      if(within==='..'||within.startsWith('..'+sep)||isAbsolute(within))throw new Error('SHELL_WORKDIR_OUTSIDE_SESSION');
      const before=(await fs.snapshot(base,this.spec.signal)).map(file=>({...file,path:[prefix,file.path].filter(Boolean).join('/')}));
      const logTarget=await fs.resolve('.dsh-process-output/'+sha256(this.payload.run_id+'/'+this.payload.call_id).slice(0,24),{cwd:base});
      await mkdir(String(logTarget.targetKey),{recursive:true});
      await fs.checked(String(logTarget.targetKey));
      for(const [name,state] of Object.entries(this.streams)) {
        state.collector=new OutputCollector(state.mode.maxBytes,name,state.mode.spill ? {
          maxBytes:state.mode.spill.maxBytes,dir:String(logTarget.targetKey),
          onFailure:error=>this.ctx.logger.warn('Sandbox output spill unavailable: %s',error.message),
        }:undefined);
      }
      this.spec.signal?.throwIfAborted();
      let settled=false, response, failure;
      submitted=this.business('native-tool',{...this.payload,name:'dsh_execute',arguments:{
        command:this.spec.argv[2],cwd:relative(fs.root,String(work.targetKey)).split(sep).join('/'),files:before,
        timeout_ms:600000,env:Object.fromEntries(Object.entries(this.spec.env||{}).map(([key,value])=>[key,value??null])),
        ...(typeof this.spec.stdio.stdin==='object'?{stdin:this.spec.stdio.stdin.data}:{}),
      }}).then(value=>{response=value;settled=true;},error=>{failure=error;settled=true;});
      let backlog=true, outputTerminal=false;
      while(!settled || backlog || !outputTerminal) {
        const output=await this.business('native-output',{...this.payload,
          stdout_offset:this.streams.stdout.offset,stderr_offset:this.streams.stderr.offset});
        backlog=false;
        for(const [name,value] of Object.entries(output.streams||{})) {
          if(!this.streams[name])throw new Error('INVALID_SANDBOX_OUTPUT_STREAM');
          backlog=this.consume(name,value)||backlog;
        }
        outputTerminal=terminal.has(output.status);
        if(outputTerminal)this.quiescent=true;
        if(failure)throw failure;
        // A denied submission has no output receipt to wait for. Unknown accepted
        // writes are reconciled by cancellation/readback, never resubmitted.
        if(settled && response?.error && !response.files) {
          if(terminal.has(response.receipt?.status))this.quiescent=true;
          throw new Error(response.error);
        }
        if((!settled || !outputTerminal) && !backlog)await delay(200);
      }
      await submitted;
      if(failure)throw failure;
      if(terminal.has(response.receipt?.status))this.quiescent=true;
      if(response.receipt?.status==='CANCELLED')return {exitCode:null,signal:'SIGTERM'};
      if(response.error && !response.files)throw new Error(response.error);
      if(!this.quiescent)throw new Error('SANDBOX_RESULT_UNKNOWN');
      // Cancellation never publishes an uncommitted workspace transfer.
      if(this.stopping || this.spec.signal?.aborted)return {exitCode:null,signal:'SIGTERM'};
      const incoming=new Map();let size=0;
      for(const file of response.files||[]) {
        if(prefix && !file.path.startsWith(prefix+'/'))throw new Error('SANDBOX_OUTPUT_OUTSIDE_SESSION');
        const target=await fs.resolve(worldRoot+'/'+file.path), bytes=Buffer.from(file.base64,'base64');size+=bytes.length;
        if(incoming.has(file.path)||incoming.size>=50||size>10*1024*1024||sha256(bytes)!==file.sha256)throw new Error('SANDBOX_OUTPUT_INVALID');
        incoming.set(file.path,{target,bytes});
      }
      const now=(await fs.snapshot(base,this.spec.signal,{excludeProcessOutput:true})).map(file=>({...file,path:[prefix,file.path].filter(Boolean).join('/')}));
      if(JSON.stringify(now)!==JSON.stringify(visibleFiles(before)))throw new Error('WORKSPACE_CHANGED_DURING_EXECUTION');
      this.spec.signal?.throwIfAborted();
      for(const [path,{target,bytes}] of incoming)if(!processLogs(path))await fs.publish(String(target.targetKey),bytes);
      for(const file of visibleFiles(before))if(!incoming.has(file.path))await unlink(await fs.checked(worldRoot+'/'+file.path));
      return {exitCode:response.exit_code,signal:null};
    } catch(error) {
      this.terminate();
      if(submitted)await submitted;
      throw error;
    }
  }

  terminate() {
    if(this.quiescent || this.stopping)return;
    this.stopping=true;
    this.termination=this.business('native-cancel',this.payload).then(value=>{
      if(terminal.has(value.status))this.quiescent=true;
    });
    void this.termination.catch(error=>this.ctx.logger.warn('Sandbox cancellation requires reconciliation: %s',error.message));
  }

  async waitForExit(signal) {
    if(this.quiescent)return true;
    if(signal?.aborted)return false;
    const cancelled=Promise.withResolvers();
    const abort=()=>cancelled.resolve(false);
    signal?.addEventListener('abort',abort,{once:true});
    try {
      return await Promise.race([this.done.catch(()=>{}).then(async()=>{
        await this.termination;
        if(!this.quiescent)throw new Error('SANDBOX_CLEANUP_PENDING');
        return true;
      }),cancelled.promise]);
    } finally {signal?.removeEventListener('abort',abort);}
  }
}
