/** Task entries reuse existing expert identities and never install schedules. */
export const workbenchTasks = Object.freeze([
  {id:'experiment-records',title:'整理实验资料',description:'整理配方、工艺和结果，对照原件检查缺项。',expertId:'experiment-assistant',expertName:'数据整理助理',prompt:'请整理我提供的实验资料，按实验和样品保留配方、工艺、检测结果及来源。标准模板按现有映射读取，复杂文本只提出有依据的解释；缺失和歧义单独列出，不补造数据。先确认资料和交付方式，结果仍待逐份人工核对，不自动确认、审核或发布。'},
  {id:'tables',title:'整理普通表格',description:'梳理字段、编号和单位，定位重复与冲突。',expertId:'experiment-assistant',expertName:'数据整理助理',prompt:'请整理我提供的普通表格，先确认字段含义、编号、单位、主键和目标格式，保留原始值与来源，列明重复、缺失和冲突。不要静默合并冲突或覆盖原件；需要保存时沿用已有授权能力与真实回执。'},
  {id:'materials',title:'分析实验与设计验证',description:'诊断材料问题，比较证据，按需设计验证实验。',expertId:'materials-advisor',expertName:'材料研发助理',prompt:'请结合我提供的实验、配方、工艺或材料问题开展分析。先明确目标和比较基准，核对样品、版本及制备和测试条件是否可比，区分事实、原因假设与建议；按问题需要提出有判据的验证方案，不强制每次都设计实验。未知条件留空，不编造实测，不自动创建或发布实验。'},
  {id:'experiment-review',title:'实验复盘',description:'复盘指定实验的变化、结论、局限与下一步。',expertId:'materials-advisor',expertName:'材料研发助理',prompt:'请对我指定的实验做一次复盘分析，不创建或修改定时任务。先明确实验范围和固定版本，再读取配方、工艺、检测条件与结果，核对可比性，说明哪些结论有证据支持、哪些仍受缺项或混杂因素影响，提出下一步建议。没有指定实验时先询问范围；不把预测或建议当实测，不修改、审核或发布原实验。'},
].map(task=>Object.freeze(task)));

export function startWorkbenchTask(id,summon) {
  const task=workbenchTasks.find(item=>item.id===id);
  if(!task)throw new Error('未知任务，请刷新工作台。');
  return summon({id:task.expertId},task.prompt);
}
