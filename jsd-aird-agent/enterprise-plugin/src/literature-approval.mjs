const externalTools=new Set(['bibliography_lookup','literature_search','literature_expand','literature_acquire']);

export async function approveLiterature(ctx,business,bound,name,args,execution) {
  if(!externalTools.has(name))return;
  const approval=ctx.get('approval');
  if(!approval)throw new Error('EXTERNAL_APPROVAL_UNAVAILABLE');
  const detail=name==='literature_acquire'?`获取开放文献 ${args.work_id}`:
    name==='literature_expand'?`查找 ${args.work_id} 的公开文献关系`:`检索公开文献：${args.query}`;
  const outcome=await approval.request({agent:execution.agent,toolName:name,callId:execution.callId,
    reason:detail,displayReason:{en:detail,zh:detail},signal:execution.signal});
  if(outcome!=='allowed-once')throw new Error(`EXTERNAL_APPROVAL_${String(outcome).toUpperCase()}`);
  await business('literature-approval',{run_id:bound.id,epoch:bound.epoch,session_id:execution.agent.id,
    turn:bound.dsh_turn,call_id:execution.callId,name,arguments:args,outcome},execution.signal);
}
