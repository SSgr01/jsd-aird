import type { AgentRun, LiteratureReport, ReportQualityReview } from '@/services/agents/agent-api';

export function operationUnknown(run: AgentRun) {
  return /SIDE_EFFECT_RECONCILIATION_REQUIRED|TOOL_RESULT_UNKNOWN/.test(run.error || '');
}

export function currentProgress(run: AgentRun, activity?: string) {
  if (run.status === 'QUEUED') return '正在等待开始';
  const text =
    activity || run.plan?.steps.find((step) => step.status === 'in_progress')?.title || '';
  if (/计算|分析|sandbox|隔离/.test(text)) return '正在计算结果';
  if (/核查|核对|检查报告/.test(text)) return '正在检查报告';
  if (/报告|交付|整理结果/.test(text)) return '正在整理报告';
  if (/读|原文|文献/.test(text)) return '正在阅读文献';
  if (/检索|查找|搜索/.test(text)) return '正在查找资料';
  return '正在处理你的任务';
}

export function reviewProblem(review?: ReportQualityReview) {
  if (!review) return '暂时无法检查报告';
  if (review.units?.some((unit) => unit.verdict === 'CONTRADICTED')) return '部分内容与原文不一致';
  if (
    review.units?.some((unit) => unit.verdict === 'INSUFFICIENT') ||
    review.blocking_findings?.length ||
    review.questions?.some((question) => question.verdict === 'MISSING')
  )
    return '有些结论缺少原文依据';
  return '暂时无法检查报告';
}

export function deliveryProblems(run: AgentRun) {
  const problems: string[] = [];
  const checks = Object.values(run.report_checks || {});
  const review = checks.at(-1);
  const reportExpected = run.plan?.deliverables.includes('report') || checks.length > 0;
  if (reportExpected && !reportOutputs(run).length)
    problems.push(
      review
        ? `报告还没完成：${reviewProblem(review)}。`
        : '报告还没完成，已生成的内容和文件已保留。',
    );
  const files = (run.registered_outputs || []).filter((item) => item.kind === 'artifact');
  if (!['QUEUED', 'RUNNING', 'WAITING_APPROVAL'].includes(run.status)) {
    const required = run.required_artifacts ?? run.plan?.file_names;
    if (required) {
      const saved = new Set(files.map((file) => (file.path || file.title).split('/').at(-1)));
      for (const name of new Set(required)) {
        if (!saved.has(name)) problems.push(`${name} 尚未生成。`);
      }
    } else {
      // Older runs did not record file names. An explicit empty list means this
      // turn has no new files, even if it discusses previously delivered CSV/JSON.
      for (const format of ['csv', 'json']) {
        if (
          new RegExp(`\\b${format}\\b`, 'i').test(run.request || '') &&
          !files.some((file) => file.title.toLowerCase().endsWith('.' + format))
        )
          problems.push(`${format.toUpperCase()} 文件尚未生成。`);
      }
    }
  }
  return problems;
}

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
    .replace(/在\s*offset\s*=\s*\d+\s*的节点中/g, '在原文中')
    .replace(/\bRCS\b/g, '文献证据评估')
    .replace(/\bfinish_task\b/g, '交付检查')
    .replace(/\breport_save\b/g, '保存报告')
    .replace(/\bplan_update\b/g, '更新计划')
    .replace(/\bevidence_save\b/g, '保存文献依据')
    .replace(/\bCOMPLETE\b/g, '已完成')
    .replace(/\bPARTIAL\b/g, '阶段性结果')
    .replace(/\bNEEDS_INPUT\b/g, '需要补充');
}

export function reportAssessment(report?: LiteratureReport) {
  const review = report?.quality_review;
  if (!report) return { label: '正在加载', detail: '正在读取报告的检查结果。', passed: false };
  if (!review)
    return {
      label: '待核查',
      detail: '这份报告还没有检查，请先核对原文。',
      passed: false,
    };
  if (review.quality_check_version !== 3)
    return {
      label: '待重新核查',
      detail: '这份报告需要重新检查。',
      passed: false,
    };
  if (review.matrix_revision !== report.evidence_matrix?.revision)
    return {
      label: '待重新核查',
      detail: '报告的参考内容已变化，需要重新检查。',
      passed: false,
    };
  if (!review.passed || review.blocking_findings?.length)
    return {
      label: '待修订',
      detail: reviewProblem(review) + '，请查看对应内容。',
      passed: false,
    };
  if (review.verification !== 'AI_REVIEW_NOT_HUMAN_VERIFICATION')
    return {
      label: '待核查',
      detail: '报告还没有完成原文检查，请先核对原文。',
      passed: false,
    };
  return {
    label: 'AI 核查通过',
    detail: '已完成 AI 辅助检查，使用前仍需人工复核。',
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
