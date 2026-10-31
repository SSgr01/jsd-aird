import type { AgentRun, LiteratureReport } from '@/services/agents/agent-api';

/** Presentation only: never writes a model's plan or upgrades its verification. */
export function researchStatus(run: AgentRun) {
  if (run.status === 'CANCELLED') return '本轮已停止';
  if (run.status === 'FAILED') return '本轮中断';
  if (run.status === 'WAITING_APPROVAL') return '等待你的确认';
  if (run.status === 'WAITING_CLARIFICATION') return '等待你补充';
  if (run.status === 'QUEUED') return '任务已排队';
  if (run.status === 'RUNNING') return '正在研究';
  if (run.completion?.outcome === 'NEEDS_INPUT') return '需要你补充';
  if (run.completion?.outcome === 'PARTIAL') return '阶段性结果';
  return '本轮执行已结束';
}

export function planStepLabel(run: AgentRun, status: string) {
  if (status === 'completed') return '已完成';
  if (run.status === 'CANCELLED') return status === 'in_progress' ? '已停止' : '未执行';
  if (run.status === 'FAILED') return status === 'in_progress' ? '已中断' : '未执行';
  if (run.status === 'WAITING_APPROVAL') return status === 'in_progress' ? '等待确认' : '待处理';
  if (run.status === 'WAITING_CLARIFICATION')
    return status === 'in_progress' ? '等待补充' : '待处理';
  if (run.status === 'QUEUED') return '待处理';
  if (!['RUNNING', 'QUEUED'].includes(run.status)) return '未完成';
  return status === 'in_progress' ? '进行中' : '待处理';
}

// Translate orchestration vocabulary, not scientific content. Full originals remain on the server.
export function researchText(value: string) {
  return value
    .replace(/\bfinish_task\b/g, '交付检查')
    .replace(/\breport_save\b/g, '保存报告')
    .replace(/\bplan_update\b/g, '更新计划')
    .replace(/\bevidence_save\b/g, '保存实例证据')
    .replace(/\bCOMPLETE\b/g, '已完成')
    .replace(/\bPARTIAL\b/g, '阶段性结果')
    .replace(/\bNEEDS_INPUT\b/g, '需要补充');
}

export function reportAssessment(report?: LiteratureReport) {
  const review = report?.quality_review;
  if (!report)
    return { label: '正在加载核查状态', detail: '尚不能判断报告是否通过核查。', passed: false };
  if (!review)
    return {
      label: '待核查',
      detail: '没有对应这份报告的事实核查记录；使用前请核对原文。',
      passed: false,
    };
  if (review.quality_check_version !== 2)
    return {
      label: '待重新核查',
      detail: '这份报告只有旧版核查记录，不能视为当前协议核查通过。',
      passed: false,
    };
  if (review.matrix_revision !== report.evidence_matrix?.revision)
    return {
      label: '核查版本不匹配',
      detail: '核查记录与报告所固定的证据版本不一致，需要重新核查。',
      passed: false,
    };
  if (!review.passed || review.blocking_findings?.length)
    return {
      label: '待修订',
      detail: '报告核查未通过；草稿保留，不能作为已确认的研究结论。',
      passed: false,
    };
  if (review.verification !== 'AI_REVIEW_NOT_HUMAN_VERIFICATION')
    return {
      label: '待核查',
      detail: '没有可确认的 AI 原文核查记录；规则预检或助手自检不能替代事实核查。',
      passed: false,
    };
  return {
    label: 'AI 核查通过',
    detail: '仅表示 AI 辅助核查通过，仍待人工复核，不代表独立实验验证。',
    passed: true,
  };
}

export function reportOutputs(run?: AgentRun) {
  // Registration order is authoritative; deduplicate retries without deleting history.
  return [
    ...new Map(
      (run?.registered_outputs || [])
        .filter((item) => item.kind === 'report')
        .map((item) => [item.id, item]),
    ).values(),
  ].reverse();
}
