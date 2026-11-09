import { currentProgress, deliveryProblems, reportAssessment } from './research-presentation';
import type { AgentRun, LiteratureReport, ReportQualityReview } from '@/services/agents/agent-api';

const run = {
  status: 'CANCELLED',
  request: '请生成 JSON、CSV 和报告',
  registered_outputs: [{ id: 'json', kind: 'artifact', title: 'result.json' }],
  plan: { deliverables: ['report'], steps: [{ title: '计算', status: 'completed' }] },
  report_checks: {
    current: {
      quality_check_version: 3,
      passed: false,
      units: [{ verdict: 'CONTRADICTED' }],
      questions: [],
    },
  },
} as unknown as AgentRun;

it('keeps missing CSV and an unfinished report visible even when all plan steps completed', () => {
  expect(deliveryProblems(run)).toEqual([
    '报告还没完成：部分内容与原文不一致。',
    'CSV 文件尚未生成。',
  ]);
});

it('checks each declared file rather than treating an unrelated CSV as delivered', () => {
  expect(
    deliveryProblems({
      ...run,
      plan: { ...run.plan!, deliverables: ['analysis_file'] },
      report_checks: {},
      required_artifacts: ['result.json', 'result.csv'],
      registered_outputs: [
        { id: 'json', kind: 'artifact', title: 'JSON', path: 'output/result.json' },
        { id: 'csv', kind: 'artifact', title: 'unrelated.csv' },
      ],
    }),
  ).toEqual(['result.csv 尚未生成。']);
});

it('does not report missing files when the turn only discusses previously delivered files', () => {
  expect(
    deliveryProblems({
      ...run,
      request: 'JSON 和 CSV 已交付，本轮仅补发最终摘要，不生成新文件。',
      plan: { ...run.plan!, task_type: 'GENERAL', deliverables: ['answer'] },
      report_checks: {},
      registered_outputs: [],
      required_artifacts: [],
    }),
  ).toEqual([]);
});

it('derives one ordinary progress sentence without exposing activity payloads', () => {
  expect(
    currentProgress({ ...run, status: 'RUNNING' }, '正在执行隔离数据分析：sandbox_execute'),
  ).toBe('正在计算结果');
  expect(currentProgress({ ...run, status: 'RUNNING' }, '正在逐项核查报告事实与原文')).toBe(
    '正在检查报告',
  );
});

it('requires a matching current AI review before calling a report checked', () => {
  const report = {
    evidence_matrix: { revision: 3 },
    quality_review: {
      quality_check_version: 3,
      matrix_revision: 3,
      passed: true,
      verification: 'AI_REVIEW_NOT_HUMAN_VERIFICATION',
      units: [],
      questions: [],
    },
  } as unknown as LiteratureReport;
  expect(reportAssessment(report).passed).toBe(true);
  report.quality_review!.matrix_revision = 2;
  expect(reportAssessment(report).passed).toBe(false);
  report.quality_review!.matrix_revision = 3;
  report.quality_review!.verification = 'DETERMINISTIC_PRESCREEN_NOT_AI_OR_HUMAN_VERIFICATION';
  expect(reportAssessment(report).passed).toBe(false);
  report.quality_review = {
    ...report.quality_review,
    passed: false,
    units: [{ verdict: 'INSUFFICIENT' }],
  } as ReportQualityReview;
  expect(reportAssessment(report).detail).toContain('有些结论缺少原文依据');
});
