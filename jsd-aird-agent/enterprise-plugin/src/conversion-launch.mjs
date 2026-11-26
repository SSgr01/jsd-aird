import { executionDirectory } from '../../deepseek/src/paths.mjs';
import { presetForProfile } from '../../deepseek/src/session-policy.mjs';

export const conversionSession = id => 'session-conversion-'+id;

/** Bound task protocol belongs to model context, never the customer's message. */
export function conversionContext(sessionId) {
  const id=/^session-conversion-([a-f0-9-]{36})$/i.exec(sessionId || '')?.[1];
  if(!id)return '';
  return '当前会话绑定实验文件标准化转换任务，conversion_id='+id+'。'+
    '先通过 tool_search 发现 experiment_conversion，调用 action=source、conversion_id='+id+' 取得原件和工作区资源，然后读取 jsd-experiment-converter skill。'+
    '先执行 source.fast_path.command；eligible=true 时直接登记已有 plan/result/verification 并 stage，不重新清点、提取或编写映射。eligible=false 才进行语义映射。'+
    '只处理此任务，复用已有资料和有效计划，保存字段映射计划并运行随包脚本，保留原值、未落位数据与冲突；不能生成虚构测量值。'+
    '将转换结果、映射计划、verification.json 用 artifact_save 登记，取返回的真实 id，再用 present 展示结果，并以真实 artifact id 调用 experiment_conversion(action=stage)。'+
    '输出有源冲突时仅作为待核对稿。不要确认导入或发布。若本文件阻塞，用 action=fail 保存具体原因，不盲目重复。最终用客户能理解的语言说明已送待人工核对或具体阻塞。';
}

export function conversionMessage(task) {
  const file=typeof task.sourceName==='string' && task.sourceName.trim()
    ? '《'+task.sourceName.trim()+'》' : '刚上传的文件';
  return (task.status==='FAILED'?'请继续帮我把':'帮我把')+file+
    '按我选择的实验模板整理，保留原始数据。无法确定的地方标出来，完成后交给我核对。';
}

/** One native session per source keeps quotas and failures independent. */
export function conversionLauncher(ctx,{business,executionRoot}) {
  const pending=new Map();
  return async body => {
    if(typeof body.batch_id!=='string'||!/^[a-f0-9-]{36}$/.test(body.batch_id)
      || body.retry_failed!==undefined && typeof body.retry_failed!=='boolean'
      || Object.keys(body).some(key=>!['batch_id','retry_failed'].includes(key)))
      throw new Error('INVALID_CONVERSION_BATCH');
    const key=body.batch_id+':'+Boolean(body.retry_failed);
    if(pending.has(key))return pending.get(key);
    const start=(async()=>{
      const {tasks}=await business('conversion-queue',{batch_id:body.batch_id});
      const expert=await business('experts',{action:'resolve',expert_id:'experiment-assistant'});
      const result=[];
      for(const task of tasks){
        if(task.status!=='QUEUED' && !(body.retry_failed && task.status==='FAILED'))continue;
        const sessionId=conversionSession(task.id);
        try {
          let state;
          try {state=await ctx.sessionController.projections({sessionId},new AbortController().signal);}
          catch(error){if(error.code!=='session/not-found')throw error;}
          if(!state){
            const directory=await executionDirectory(executionRoot,'/workspaces/default');
            const workspace=await ctx.workspaceRegistry.create(directory);
            await ctx.sessionController.create({workspaceId:workspace.id,agentPreset:presetForProfile(),sessionId});
          }
          const current=await business('experts',{action:'session',session_id:sessionId});
          if(!current.expert) await business('experts',{action:'bind',session_id:sessionId,expert_id:expert.id});
          if(current.expert && current.expert.id!=='experiment-assistant')throw new Error('CONVERSION_EXPERT_MISMATCH');
          const requestId=task.status==='FAILED'?'conversion-retry-'+task.id+'-'+task.updatedAt:'conversion-'+task.id;
          await ctx.sessionController.prompt({sessionId,requestId,mode:'queue',clientTimeZone:'Asia/Shanghai',content:[{type:'text',text:
            conversionMessage(task)}]},new AbortController().signal);
          result.push({conversion_id:task.id,session_id:sessionId,accepted:true});
        } catch(error){
          ctx.logger?.warn('Conversion launch pending task=%s code=%s message=%s',task.id,error.code||error.name,error.message);
          result.push({conversion_id:task.id,session_id:sessionId,accepted:false,error:error.code||'CONVERSION_START_PENDING'});
        }
      }
      return {tasks:result};
    })().finally(()=>pending.delete(key));
    pending.set(key,start);return start;
  };
}
