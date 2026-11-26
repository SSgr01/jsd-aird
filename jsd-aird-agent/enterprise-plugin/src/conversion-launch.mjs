import { executionDirectory } from '../../deepseek/src/paths.mjs';
import { presetForProfile } from '../../deepseek/src/session-policy.mjs';

export const conversionSession = id => 'session-conversion-'+id;
export const conversionId = sessionId => /^session-conversion-([a-f0-9-]{36})$/i.exec(sessionId || '')?.[1];

export function conversionError(error) {
  const messageCode = typeof error?.message === 'string' && /^[A-Z][A-Z0-9_]{3,99}$/.test(error.message)
    ? error.message : undefined;
  const raw = error?.code || error?.cause?.code || messageCode || error?.name;
  const code = typeof raw === 'string' && /^[a-z0-9_/-]{1,100}$/i.test(raw) ? raw : 'CONVERSION_ERROR';
  const reason = {
    INTERACTIVE_AUTHORIZATION_REQUIRED: '登录授权已失效，请重新登录',
    ENTERPRISE_GATE_401: '登录授权已失效，请重新登录',
    ENTERPRISE_GATE_403: '当前账号没有执行此操作的权限',
    ENTERPRISE_GATE_404: '找不到所需的转换任务或专家',
    ENTERPRISE_GATE_429: '服务请求过多，请稍后重试',
    ENTERPRISE_GATE_502: '专家控制服务暂时不可用',
    ENTERPRISE_GATE_503: '专家控制服务暂时不可用',
    ECONNREFUSED: '无法连接专家控制服务',
    ECONNRESET: '与专家控制服务的连接中断',
    ETIMEDOUT: '连接专家控制服务超时',
    'session/not-found': '专家会话不存在',
    'session/agent-busy': '专家会话正在处理其他请求',
    CONVERSION_EXPERT_MISMATCH: '会话绑定的专家不是实验转换专家',
    INVALID_CONVERSION_BATCH: '上传批次参数无效',
    TimeoutError: '等待服务响应超时',
    AbortError: '请求已中止',
    TOKEN_BUDGET_EXCEEDED: '转换超出 Token 预算',
    BUDGET_EXCEEDED: '转换超出执行预算',
  }[code];
  return reason ? `${reason}（${code}）` : `服务错误（${code}）`;
}

export function reportConversion(business, sessionId, status, details = {}) {
  return business('conversion-status', {session_id:sessionId,conversion_id:conversionId(sessionId),status,...details});
}

/** Bound task protocol belongs to model context, never the customer's message. */
export function conversionContext(sessionId) {
  const id=conversionId(sessionId);
  if(!id)return '';
  return '当前会话绑定实验文件标准化转换任务，conversion_id='+id+'。'+
    '先通过 tool_search 发现 experiment_conversion，调用 action=source、conversion_id='+id+' 取得原件和工作区资源，然后读取 jsd-experiment-converter skill。'+
    '仅在首次运行执行 source.fast_path.command；eligible=true 时直接登记 plan/result/verification 并 stage。eligible=false 且返回 semantic 时，按其路径读取源预览、目标标签和映射约定后直接保存计划。'+
    '上下文压缩后先读取 .dsh/cache/jsd-experiment-converter/semantic-handoff.json（若已生成），接着完成当前计划。不要反复调用 source、重新清点或为了写计划通读执行器源码。'+
    '只处理此任务，复用已有资料和有效计划，保存字段映射计划并运行随包脚本，保留原值、未落位数据与冲突；不能生成虚构测量值。'+
    '将转换结果、映射计划、verification.json 用 artifact_save 登记，取返回的真实 id，再用 present 展示结果，并以真实 artifact id 调用 experiment_conversion(action=stage)。'+
    '输出有源冲突时仅作为待核对稿。不要确认导入或发布。若本文件阻塞，用 action=fail 保存具体原因，不盲目重复。最终用客户能理解的语言说明已送待人工核对或具体阻塞。';
}

export function conversionMessage(task) {
  const file=typeof task.sourceName==='string' && task.sourceName.trim()
    ? '《'+task.sourceName.trim()+'》' : '刚上传的文件';
  const retry = task.status==='FAILED' || task.launchRequestId?.startsWith('conversion-retry-');
  return (retry?'请继续帮我把':'帮我把')+file+
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
      const result=[];
      let expert;
      for(const task of tasks){
        if(!['QUEUED','START_FAILED'].includes(task.status) && !(body.retry_failed && task.status==='FAILED'))continue;
        const sessionId=conversionSession(task.id);
        const stamp={expected_updated_at:task.updatedAt};
        let phase='解析实验转换专家';
        try {
          expert ||= await business('experts',{action:'resolve',expert_id:'experiment-assistant'});
          phase='创建专家会话';
          let state;
          try {state=await ctx.sessionController.projections({sessionId},new AbortController().signal);}
          catch(error){if(error.code!=='session/not-found')throw error;}
          if(!state){
            const directory=await executionDirectory(executionRoot,'/workspaces/default');
            const workspace=await ctx.workspaceRegistry.create(directory);
            await ctx.sessionController.create({workspaceId:workspace.id,agentPreset:presetForProfile(),sessionId});
          }
          phase='绑定实验转换专家';
          const current=await business('experts',{action:'session',session_id:sessionId});
          if(!current.expert) await business('experts',{action:'bind',session_id:sessionId,expert_id:expert.id});
          if(current.expert && current.expert.id!=='experiment-assistant')throw new Error('CONVERSION_EXPERT_MISMATCH');
          phase='提交专家请求';
          const requestId=task.launchRequestId || (task.status==='FAILED'?'conversion-retry-'+task.id+'-'+task.updatedAt:'conversion-'+task.id);
          await ctx.sessionController.prompt({sessionId,requestId,mode:'queue',clientTimeZone:'Asia/Shanghai',content:[{type:'text',text:
            conversionMessage(task)}]},new AbortController().signal);
          try {
            await reportConversion(business,sessionId,'ACCEPTED',stamp);
            result.push({conversion_id:task.id,session_id:sessionId,accepted:true});
          } catch(error) {
            const warning='专家已受理，状态回写失败：'+conversionError(error);
            ctx.logger?.warn('Conversion acceptance projection pending task=%s',task.id);
            result.push({conversion_id:task.id,session_id:sessionId,accepted:true,warning});
          }
        } catch(error){
          ctx.logger?.warn('Conversion launch pending task=%s code=%s message=%s',task.id,error.code||error.name,error.message);
          const reason=phase+'失败：'+conversionError(error);
          const requestId=task.launchRequestId || (task.status==='FAILED'?'conversion-retry-'+task.id+'-'+task.updatedAt:'conversion-'+task.id);
          try {
            const state=await reportConversion(business,sessionId,'START_FAILED',{...stamp,reason,request_id:requestId});
            if(['ACCEPTED','RUNNING','WAITING_REVIEW'].includes(state?.status)) {
              result.push({conversion_id:task.id,session_id:sessionId,accepted:true});
              continue;
            }
          } catch(reportError) {
            ctx.logger?.warn('Conversion failure projection pending task=%s code=%s',task.id,reportError.code||reportError.name);
          }
          result.push({conversion_id:task.id,session_id:sessionId,accepted:false,error:reason});
        }
      }
      return {tasks:result};
    })().finally(()=>pending.delete(key));
    pending.set(key,start);return start;
  };
}
