/** The service owns counters and allowed sources; this message makes the exact repair task visible in the native log. */
export function repairInstruction(repair) {
  const remainingModels = Math.max(0,repair.max_model_calls-repair.model_calls);
  const remainingReads = remainingModels > 1 ? Math.max(0,repair.max_tool_calls-repair.tool_calls) : 0;
  return '仅修复上一份答复的引用。下面给出了具体位置、无效句柄和可核对的已有证据。保留原任务的结论、适用边界及已完成的文件交付。'
    + '格式可按已知句柄直接修正；只有原文确实支持该事实时才使用该证据。缺少依据时明确说明缺口，不猜证据或把 DOI 当作原文证据。'
    + `本次修复剩余 ${remainingModels} 次模型请求、${remainingReads} 次定向补读。`
    + '优先直接用已有证据完成；确需补读时，仅可调用 evidence_read、evidence_context，或读取已取得文献的固定版本。'
    + '禁止全库检索、发现新来源或新增业务写入。最后一次模型请求必须直接输出修复后的答复和仍有的资料缺口，不再调用读取工具。'
    + '只输出修正后的完整答复正文及必要的资料缺口，不输出修复说明、修复清单或诊断历史；不得在正文、引号或代码中复述无效引用句柄。'
    + '\n关键事实使用实际已读的 [E:证据ID]。诊断中的候选仅帮助定位，不代表已验证其支持任何结论。\n'
    + JSON.stringify(repair.diagnostics);
}
