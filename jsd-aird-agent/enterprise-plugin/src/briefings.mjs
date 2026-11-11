import { createDeveloperMessage } from '../../../vendor/deepseek-harness/packages/llm/llm/lib/index.js';

/** Default enterprise tasks use the native scheduler, native Inbox and stable Sessions. */
export const briefingDefaults = Object.freeze([
  {id:'project-morning', title:'项目晨报', time:'09:00:00', weekdays:[1,2,3,4,5], prompt:
    '请整理项目晨报。先用 get_research_briefing_context 获取 project-morning 的当前研发索引，读完各分区及分页，再用 business_read 读取固定记录。根据真实状态和计划日期列出在研项目、逾期及今日到期任务、阻塞阶段、资料缺口和优先行动。区分事实、判断与建议，给出可打开的项目、阶段和任务入口。没有记录时如实说明。只在本会话保留完整正文，不创建草稿、正式报告或文件。'},
  {id:'research-weekly', title:'研发周报', time:'16:00:00', weekdays:[5], prompt:
    '请整理研发周报。先用 get_research_briefing_context 获取 research-weekly 的本周活动索引，读完各分区及分页，再用 business_read 读取固定记录；实验优先用 section=record 一次完整读取，仅记录过大时按返回的非空分区和 next_offset 分页。以服务端确定的周一零点至当前时刻为窗口，梳理项目、阶段、任务与实验的实际进展、成果、风险和下周安排。区分本周更新与当前状态；没有前态依据时不推断具体变化。沿真实关联联动分析，注明不同单位、方法和条件的可比边界，给出记录及版本入口。只在本会话保留完整正文，不创建草稿、正式报告或文件。'},
  {id:'experiment-review', title:'实验复盘', time:'17:30:00', weekdays:[1,2,3,4,5], prompt:
    '请完成本期实验复盘。先用 get_research_briefing_context 获取 experiment-review 的活动索引，读完全部分页；以服务端确定的时间窗口为准，包含独立实验。用 business_read 的 section=record 一次读取每条实验的固定版本背景、方案、样品、配方、工艺、条件及结果；仅记录过大时按返回的非空分区和 next_offset 分页。必要时沿明确的实验与版本关联回读历史依据。比较配方、工艺、方法、条件和结果，区分历史依据、本次实测、缺失、未测与零值，不把不同单位或不同条件直接比较。给出有依据的结论、局限和下一步建议，附真实版本入口；覆盖不完整时明确说明，不声称完成。只在本会话保留完整正文，不创建草稿、正式报告或文件。'},
].map(task=>Object.freeze({...task,prompt:task.prompt+' 正文直接呈现业务事实、判断与建议，用记录名称和版本链接提供入口。覆盖情况、协议字段、解析状态与技术标识留在过程记录。'})));

export function reconcileBriefings(plan, catalog) {
  return Object.values(plan.tasks).map(saved => {
    const matches = saved.native_id ? catalog.filter(task=>task.id===saved.native_id)
      : catalog.filter(task=>task.target?.kind==='session' && task.target.sessionId===saved.session_id);
    if(matches.length>1) throw new Error('BUILTIN_TASK_RECONCILIATION_REQUIRED');
    const native=matches[0];
    return {...saved, native_id:native?.id || saved.native_id,
      status:native ? (native.target?.kind==='session' && native.target.sessionId===saved.session_id ? native.status : 'target_changed')
        : saved.native_id ? 'deleted' : saved.status, native};
  });
}

/** Archive completed periods in the native log without carrying their stale evidence forward. */
export function beginBriefingPeriod(agent, turn, step, signal) {
  signal?.throwIfAborted();
  const session=agent.session;
  const start=session.snapshotEvents().find(event=>event.type==='turn/start' && event.data.turn===turn)?.seq;
  if(start===undefined)return;
  const nodes=[...session.surface.nodes];
  const first=nodes.findIndex(seq=>seq<start && session.eventAt(seq)?.type!=='system/message');
  if(first<0)return;
  let end=first;
  while(end+1<nodes.length && nodes[end+1]<start)end++;
  const previous=nodes.slice(first,end+1);
  // Surface replacement is a native, durable context operation. It leaves all
  // original messages and tool receipts readable in the conversation timeline.
  // A coding-task checkpoint would incorrectly frame an earlier period's facts
  // and errors as unfinished work for this newly scheduled business period.
  session.append('developer/message',{turn,step,message:createDeveloperMessage({
    source:{kind:'enterprise-briefing-period',turn},content:[{type:'text',text:
      '本期是独立的研发简报周期。前期正文和过程仍保留在会话历史中；本期从服务端活动索引重新读取固定记录。历史工具结果不代表本期读取，记录更新时间不代表已知前后变化。需要比较历史时，沿真实版本关联重新读取。'}],
  })},{surfaceOp:{op:'replace',startSeq:previous[0],endSeq:previous.at(-1)},sourceEventSeqs:previous});
}

export class NativeBriefings {
  constructor(ctx,{business,executionRoot,asBackground,reconcileSession}) {
    Object.assign(this,{ctx,business,executionRoot,asBackground,reconcileSession});
    this.turns=new Map();
  }
  async sync({install=false}={}) {
    if(this.syncing) return this.syncing;
    this.syncing=this.performSync(install).finally(()=>{this.syncing=undefined;});
    return this.syncing;
  }
  async performSync(install) {
    this.plan=await this.business(install?'builtin-plan':'builtin-state',{});
    let catalog=await this.ctx.schedule.catalog();
    const tasks=reconcileBriefings(this.plan,catalog);
    for(const task of tasks) {
      if(task.native_id || task.status!=='INSTALLING') continue;
      let state;
      try {state=await this.ctx.sessionController.projections({sessionId:task.session_id},new AbortController().signal);}
      catch(error) {if(error.code!=='session/not-found')throw error;}
      const definition=briefingDefaults.find(item=>item.id===task.builtin_id);
      if(!state) {
        await this.ctx.sessionController.create({sessionId:task.session_id,cwd:this.executionRoot,agentPreset:'enterprise'});
        await this.ctx.sessionController.rename({sessionId:task.session_id,title:definition.title});
      }
      const attempt=await this.business('builtin-start',{builtin_id:task.builtin_id,session_id:task.session_id});
      if(!attempt.claimed) throw new Error('BUILTIN_TASK_RECONCILIATION_REQUIRED');
      try {
        task.native=await this.ctx.schedule.create({title:definition.title,prompt:definition.prompt,
          timing:{kind:'weekly',weekly:{time:definition.time,time_zone:'Asia/Shanghai',weekdays:definition.weekdays}},
          target:{kind:'session',sessionId:task.session_id}});
      } catch(error) {
        // Native creation can commit before its response is observed. Read the
        // durable catalog by the reserved target, never by a mutable title.
        catalog=await this.ctx.schedule.catalog();
        task.native=reconcileBriefings({tasks:{[task.builtin_id]:task}},catalog)[0].native;
        if(!task.native) throw error;
      }
      task.native_id=task.native.id; task.status=task.native.status;
    }
    this.plan=await this.business('builtin-sync',{tasks:tasks.map(({native,...task})=>task)});
    if(this.reconcileSession) {
      const pending=await this.business('builtin-pending',{});
      for(const sessionId of pending.sessions) await this.reconcileSession(sessionId);
    }
    return this.plan;
  }
  owns(sessionId) {
    return Object.values(this.plan?.tasks || {}).some(task=>task.session_id===sessionId && ['active','paused','ended'].includes(task.status));
  }
  async delivery(sessionId,input) {
    const key=sessionId+':'+input.turn;
    if(this.turns.has(key))return this.turns.get(key);
    const scheduled=input.messages.filter(message=>message.source?.kind==='schedule');
    if(!scheduled.length)return undefined;
    if(input.messages.some(message=>message.source?.kind==='user')) throw new Error('BACKGROUND_MIXED_INPUT_DENIED');
    await this.asBackground(()=>this.sync());
    const task=Object.values(this.plan.tasks).find(task=>task.session_id===sessionId && ['active','paused','ended'].includes(task.status));
    if(!task)return undefined;
    // The native Inbox can claim a message before the scheduler has appended
    // its acknowledged delivery receipt. Wait briefly for that exact identity.
    for(let attempt=0;attempt<20;attempt++) {
      const history=await this.ctx.schedule.history({id:task.native_id,limit:100});
      const receipt=history.records?.find(record=>record.targetSessionId===sessionId && scheduled.some(message=>message.id===record.messageId));
      if(receipt) {
        const binding={builtin_id:task.builtin_id,task_id:task.native_id,message_id:receipt.messageId,
          scheduled_at:receipt.scheduledAt,delivered_at:receipt.deliveredAt};
        this.turns.set(key,binding);
        return binding;
      }
      await new Promise(resolve=>setTimeout(resolve,50));
    }
    // Change notices have the same native source kind as deliveries. They
    // never receive a background grant just because they mention a task.
    return undefined;
  }
}
