import type { AgentRun } from '@/services/agents/agent-api';
import { Button } from 'antd';
import { LoadingOutlined } from '@ant-design/icons';
import { currentProgress, operationUnknown, researchStatus } from './research-presentation';

export function ResearchRunSummary({
  run,
  progress,
  busy = false,
  onRetry,
}: {
  run: AgentRun;
  progress: Array<{ key: number; text: string }>;
  busy?: boolean;
  onRetry?: () => void;
}) {
  const active = ['QUEUED', 'RUNNING'].includes(run.status);
  const unknown = operationUnknown(run);
  if (run.status === 'SUCCEEDED' && !run.error && run.completion?.outcome === 'COMPLETE')
    return null;
  const label = unknown
    ? '结果还在确认，请先不要重复提交'
    : active
      ? currentProgress(run, progress.at(-1)?.text)
      : run.status === 'CANCELLED'
        ? '已停止。已生成的文件仍可下载，补充要求后可继续。'
        : run.status === 'FAILED' || run.error
          ? '任务暂时中断，已生成的内容已保留。'
          : researchStatus(run);
  return (
    <section className="research-run-summary" aria-label="当前进度" role="status">
      {active && <LoadingOutlined spin aria-hidden="true" />}
      <span>{label}</span>
      {run.error && run.retryable === true && !unknown && onRetry && (
        <Button type="link" loading={busy} onClick={onRetry}>
          继续处理
        </Button>
      )}
    </section>
  );
}
