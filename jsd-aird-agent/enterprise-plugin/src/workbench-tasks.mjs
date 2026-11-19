/** Task entries reuse existing expert identities and never install schedules. */
export const workbenchTasks = Object.freeze([
  {id:'experiment-records',title:'整理资料',description:'把实验记录与表格整理成清楚、可核对的数据。',expertId:'experiment-assistant',expertName:'数据整理助理',prompt:'请整理我提供的资料。实验记录提取配方、工艺、样品和测试结果，普通表格核对字段、编号和单位；保留原始值与来源，列出缺失、重复和冲突。按资料类型选择整理方式，不覆盖原件，不自动审核或发布。'},
  {id:'materials',title:'分析实验',description:'比较配方与测试结果，梳理差异和验证方向。',expertId:'materials-advisor',expertName:'材料研发助理',prompt:'请结合我提供的实验、配方或工艺分析当前问题。明确目标和比较基准，核对样品、版本及测试条件是否可比，区分事实、原因假设与建议，按需要提出有判断标准的验证方案。不编造实测，不自动创建或发布实验。'},
  {id:'literature',title:'研读文献',description:'提炼论文与专利的技术路线、关键条件和依据。',expertId:'materials-advisor',expertName:'材料研发助理',prompt:'请研读我提供的论文、专利或技术资料，整理材料体系、技术路线、关键条件及原文支持的结论；多份资料先核对可比性，保留出处并说明局限。不把摘要当作已读全文，不将文献结论当作当前样品的实测。'},
  {id:'project',title:'推进项目',description:'梳理项目进展、关键卡点和下一步行动。',expertId:'project-assistant',expertName:'项目助理',prompt:'请梳理我指定项目的进展，结合客户要求、任务与关联实验，列出已核实成果、关键卡点、验证缺口及优先行动。说明判断依据，责任人和期限只引用已有记录，不修改项目状态。'},
].map(task=>Object.freeze(task)));

export function startWorkbenchTask(id,summon) {
  const task=workbenchTasks.find(item=>item.id===id);
  if(!task)throw new Error('未知任务，请刷新工作台。');
  return summon({id:task.expertId},task.prompt);
}

/** Expert suggestions come from the authorized expert catalog, not a shared list. */
export function tasksForExpert(expert) {
  if (!expert) return workbenchTasks;
  const examples = (expert.examples || []).filter(text => typeof text === 'string' && text.trim());
  const configured=(expert.starter_tasks || []).filter(task=>task?.title?.trim() && task?.prompt?.trim());
  const fallback=examples.map(prompt=>({title:prompt,description:expert.summary || '',prompt}));
  for(const capability of expert.capabilities || []) fallback.push({title:capability,description:expert.summary || '',
    prompt:`请围绕“${capability}”处理当前任务，依据已有资料说明结果、缺项和下一步。`});
  for(const title of ['梳理当前问题','核对资料依据','分析重点差异','整理下一步行动'])
    fallback.push({title,description:expert.summary || '',prompt:`请结合${expert.name}的专业职责，${title}，保留事实依据和待确认事项。`});
  return [...configured,...fallback].slice(0,4).map((task,index)=>({
    ...task,id:`expert-${expert.id}-${index}`,expertId:expert.id,expertName:expert.name,
  }));
}
