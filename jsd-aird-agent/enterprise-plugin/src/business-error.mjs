import { LlmError } from '../../../vendor/deepseek-harness/packages/llm/llm/lib/index.js';

const explanations = {
  TOKEN_BUDGET_EXCEEDED: '本轮 Token 预算已用尽，任务尚未完成。请缩小范围后继续。',
  BUDGET_EXCEEDED: '本轮执行预算已用尽，任务尚未完成。',
  OUTPUT_LIMIT_EXCEEDED: '回答被截断，本轮任务尚未完成，可在原会话继续。',
  CITATION_INVALID: '回答的原文引用缺失或无效，本轮未通过来源检查。',
  SOURCE_CITATIONS_REQUIRED: '回答缺少可核对的原文引用，本轮未通过来源检查。',
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
  if (![400, 409, 422, 429].includes(response.status)) return new Error(prefix);
  let detail;
  try { detail = (await response.json()).detail; } catch { return new Error(prefix); }
  if (typeof detail === 'string') {
    if (explanations[detail]) return new LlmError(explanations[detail], detail, {status:response.status});
    return new Error(prefix + ': ' + detail.slice(0, 2000));
  }
  if (detail?.code === 'INVALID_TOOL_ARGUMENTS') {
    const safe = {code: detail.code, path: detail.path, rule: detail.rule,
      expected: detail.expected, unexpected_fields: detail.unexpected_fields};
    return new Error(prefix + ': ' + JSON.stringify(safe).slice(0, 2000));
  }
  return new Error(prefix);
}
