import { LlmError } from '../../../vendor/deepseek-harness/packages/llm/llm/lib/index.js';

const explanations = {
  TOKEN_BUDGET_EXCEEDED: '本轮 Token 预算已用尽，任务尚未完成。请缩小范围后继续。',
  BUDGET_EXCEEDED: '本轮执行预算已用尽，任务尚未完成。',
  OUTPUT_LIMIT_EXCEEDED: '回答被截断，本轮任务尚未完成，可在原会话继续。',
  CITATION_INVALID: '回答的原文引用缺失或无效，本轮未通过来源检查。',
  CITATION_REPAIR_BUDGET_EXCEEDED: '引用修复已达到请求上限，仍有引用未通过核对，本轮尚未完成。',
  CITATION_REPAIR_READ_BUDGET_EXCEEDED: '定向补读已达到上限，请用已有证据完成答复，并说明仍缺少的依据。',
  CITATION_REPAIR_FINAL_RESPONSE_REQUIRED: '这是最后一次引用修复请求，请直接完成答复并说明资料缺口。',
  CITATION_REPAIR_TARGETED_READ_ONLY: '引用修复只允许定向核对已有来源，不能扩展检索或新增业务操作。',
  CITATION_REPAIR_SOURCE_NOT_READ: '只能定向核对本轮已有证据或其固定版本文献。',
  SOURCE_CITATIONS_REQUIRED: '回答缺少可核对的原文引用，本轮未通过来源检查。',
  BRIEFING_COVERAGE_INCOMPLETE: '本期仍有业务记录或分页未完整读取，复盘位置已保留，可在任务中重新执行。',
  CURRENT_MATRIX_CHECKED_REPORT_REQUIRED: '研究报告尚未通过原文核查并保存。',
  USER_DELIVERABLES_MISSING: '本轮要求的报告或文件尚未完成保存。',
  SIDE_EFFECT_RECONCILIATION_REQUIRED: '有一项业务操作的结果尚未确认，需要先核对回执，不能重复执行。',
};

export function deliveryError(result) {
  const gap = result.completion?.gaps?.[0] || 'ENTERPRISE_DELIVERY_FAILED';
  const code = /^[A-Z][A-Z0-9_]+/.exec(gap)?.[0] || 'ENTERPRISE_DELIVERY_FAILED';
  // The native turn error surface retains this structured failure and its code.
  return new LlmError(explanations[code] || '本轮业务检查未通过，尚不能确认交付完成。', code);
}

/** Public validation instructions, without response bodies from server failures. */
export async function businessError(response) {
  const prefix = 'ENTERPRISE_GATE_' + response.status;
  const refused = message => Object.assign(new Error(message), {code:prefix,status:response.status});
  if ([401,403].includes(response.status)) return refused('当前账号无权执行这项业务操作。');
  if (response.status === 404) return refused('所需记录不存在，请重新查找。');
  if (![400, 409, 422, 429].includes(response.status)) return refused('业务服务暂时不可用。');
  let detail;
  try { detail = (await response.json()).detail; } catch { return new Error(prefix); }
  if (typeof detail === 'string') {
    if (explanations[detail]) return new LlmError(explanations[detail], detail, {status:response.status});
    return Object.assign(new Error(prefix + ': ' + detail.slice(0, 2000)), {code:detail,status:response.status});
  }
  if (detail?.code === 'INVALID_TOOL_ARGUMENTS') {
    const safe = {code: detail.code, path: detail.path, rule: detail.rule,
      expected: detail.expected, unexpected_fields: detail.unexpected_fields};
    return new Error(prefix + ': ' + JSON.stringify(safe).slice(0, 2000));
  }
  return new Error(prefix);
}
