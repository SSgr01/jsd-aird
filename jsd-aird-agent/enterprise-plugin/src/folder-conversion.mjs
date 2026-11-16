import {readFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import {conversionFiles} from './conversion-resources.mjs';
import {executionDirectory} from '../../deepseek/src/paths.mjs';
import {presetForProfile} from '../../deepseek/src/session-policy.mjs';

const hash = bytes => createHash('sha256').update(bytes).digest('hex');
export const isFolderSession = id => typeof id==='string' && /^folder-[a-f0-9-]{36}$/.test(id);
export const folderNativeTools = new Set(['read','read_image','write','edit','apply_patch','bash','glob','grep','skill',
  'todo_write','working_directory','job_output','job_list','job_kill']);

/** Only dispatches native Inbox requests. The native main agent owns every model step. */
export class FolderConversionRuntime {
  constructor(ctx,{business,executionRoot,withBatch}) {Object.assign(this,{ctx,business,executionRoot,withBatch});this.pending=new Map();}
  sync(batchId) {
    if(this.pending.has(batchId))return this.pending.get(batchId);
    const operation=this.withBatch(batchId,async()=>{
      const root=new URL('../skills/jsd-experiment-converter/',import.meta.url);
      const engineHash=hash(Buffer.concat(await Promise.all(conversionFiles.filter(file=>!file.startsWith('recipes/')).map(file=>readFile(new URL(file,root))))));
      const plan=await this.business('folder-dispatch',{engine_hash:engineHash});
      for(const item of plan.items) {
        try {
        const signal=new AbortController().signal;
        let state;
        try {state=await this.ctx.sessionController.projections({sessionId:item.session_id},signal);}
        catch(error){if(error.code!=='session/not-found')throw error;}
        if(!state) {
          const directory=await executionDirectory(this.executionRoot,'/workspaces/default');
          const workspace=await this.ctx.workspaceRegistry.create(directory);
          await this.ctx.sessionController.create({sessionId:item.session_id,workspaceId:workspace.id,agentPreset:presetForProfile()});
        }
        const current=await this.business('experts',{action:'session',session_id:item.session_id});
        if(!current.expert)await this.business('experts',{action:'bind',session_id:item.session_id,expert_id:'experiment-assistant'});
        // Recover a completed native turn whose completion notification was lost.
        if(state && this.ctx.sessionController.events) {
          const events=await this.ctx.sessionController.events({address:{kind:'session',sessionId:item.session_id},maxMessages:10},signal);
          if(events.records.some(event=>event.type==='turn/end')) {
            await this.business('folder-ended',{session_id:item.session_id});continue;
          }
        }
        await this.ctx.sessionController.prompt({sessionId:item.session_id,requestId:'folder-'+item.id,mode:'queue',clientTimeZone:'Asia/Shanghai',
          content:[{type:'text',text:'服务器文件夹转换模式，批次 '+plan.batch_id+'。仅处理当前会话绑定的一份文件。通过 tool_search 发现 folder_conversion，调用 action=source 取得工作副本、标准模板和脚本。读取 jsd-experiment-converter skill。独立检查本文件并确定测试或合成类型，保存自己的字段映射计划，不套用其他文件的映射。保持源值、空值、零值、单位、分组和未落位数据；类型不明、公式缺缓存或资料不足时记录阻塞。用随包Python脚本运行和验证，所有命令在现有Linux沙箱执行。文件夹模式交付指令优先于skill中的上传入库指引：完成后只调用 folder_conversion(action=export,output_path=实际XLSX路径,plan_path=实际映射JSON路径,report_path=实际verification.json路径)。严禁 artifact_save、present、experiment_conversion 或其他业务写入。不记录人工审核状态、不确认、不发布。无法完成时调用 folder_conversion(action=fail,reason=具体原因)。只根据真实工具回执说明是否保存。'}]},signal);
        } catch(error) {this.ctx.logger?.warn('Folder source dispatch pending: %s',error.code || error.message);}
      }
      return plan;
    }).finally(()=>this.pending.delete(batchId));
    this.pending.set(batchId,operation);return operation;
  }
  async materialize(execution,result) {
    if(!result?.folder_source)return result;
    const cwd=await this.ctx.workingDirectory.ensure(execution.agent,execution.signal);
    const publish=async(path,bytes)=>{
      const target=await this.ctx.fs.resolve(path,{cwd,signal:execution.signal});
      await this.ctx.fs.publish(String(target.targetKey),bytes);
    };
    const bytes=Buffer.from(result.base64,'base64');
    if(hash(bytes)!==result.working_sha256)throw new Error('FOLDER_SOURCE_HASH_MISMATCH');
    // Prefix is stable across source calls; file name cannot introduce a path.
    if(/[\\/\x00-\x1f]/.test(result.name))throw new Error('INVALID_FOLDER_SOURCE_NAME');
    const path='原始实验/待分类/'+result.name;
    await publish(path,bytes);
    for(const kind of ['test','synthesis'])await publish('标准模板/'+kind+'.xlsx',Buffer.from(result.templates[kind],'base64'));
    const root=new URL('../skills/jsd-experiment-converter/',import.meta.url);
    // Examples describe other files and are deliberately excluded in folder mode.
    for(const file of conversionFiles.filter(file=>!file.startsWith('recipes/')))
      await publish('.conversion-tools/jsd-experiment-converter/'+file,await readFile(new URL(file,root)));
    const {base64,templates,...safe}=result;
    return {...safe,source_path:path,skill_resources:'.conversion-tools/jsd-experiment-converter',
      instruction:'先读取当前文件判断类型；把工作副本复制到原始实验/测试实验或原始实验/合成实验，再运行workflow.py inspect。不覆盖原件和模板。逐份独立映射；只用folder_conversion export保存文件夹结果。'};
  }
  async export(execution,args,bound) {
    const cwd=await this.ctx.workingDirectory.ensure(execution.agent,execution.signal),files=[],paths={};
    for(const key of ['output','plan','report']) {
      const target=await this.ctx.fs.resolve(args[key+'_path'],{cwd,signal:execution.signal});
      const path=this.ctx.fs.processPath(target).replace(/^\/workspaces\//,'');
      const bytes=await this.ctx.fs.readBytes(target,execution.signal,8*1024*1024);
      files.push({path,base64:Buffer.from(bytes).toString('base64'),sha256:hash(bytes)});paths[key]=path;
    }
    return this.business('folder-export',{session_id:execution.agent.id,run_id:bound.id,epoch:bound.epoch,
      turn:bound.dsh_turn,call_id:execution.callId,files,paths},execution.signal);
  }
}
