import {
  CheckCircleOutlined,
  ClockCircleOutlined,
  ExclamationCircleOutlined,
  LoadingOutlined,
  MinusCircleOutlined,
  PauseCircleOutlined,
} from '@ant-design/icons';
import type { AgentRun } from '@/services/agents/agent-api';
import { planStepLabel, researchStatus } from './research-presentation';

type Tone = 'idle' | 'running' | 'done' | 'waiting' | 'stopped' | 'error';
const icons = {
  idle: ClockCircleOutlined,
  running: LoadingOutlined,
  done: CheckCircleOutlined,
  waiting: PauseCircleOutlined,
  stopped: MinusCircleOutlined,
  error: ExclamationCircleOutlined,
};

function StatusMark({ tone, label }: { tone: Tone; label: string }) {
  const Icon = icons[tone];
  return (
    <span className={`research-status-mark is-${tone}`}>
      <Icon aria-hidden="true" spin={tone === 'running'} />
      <span>{label}</span>
    </span>
  );
}

export function ResearchRunStatus({ run, prefix = '' }: { run: AgentRun; prefix?: string }) {
  const unknown = /SIDE_EFFECT_RECONCILIATION_REQUIRED|TOOL_RESULT_UNKNOWN/.test(run.error || '');
  const tone: Tone =
    unknown || run.error || run.status === 'FAILED'
      ? 'error'
      : run.status === 'CANCELLED'
        ? 'stopped'
        : run.status === 'RUNNING'
          ? 'running'
          : ['WAITING_APPROVAL', 'WAITING_CLARIFICATION'].includes(run.status) ||
              run.completion?.outcome === 'NEEDS_INPUT'
            ? 'waiting'
            : run.status === 'QUEUED' || run.completion?.outcome === 'PARTIAL'
              ? 'idle'
              : run.status === 'SUCCEEDED'
                ? 'done'
                : 'idle';
  return (
    <StatusMark tone={tone} label={prefix + (unknown ? '操作结果待核实' : researchStatus(run))} />
  );
}

export function ResearchStepStatus({ run, status }: { run: AgentRun; status: string }) {
  const tone: Tone =
    status === 'completed'
      ? 'done'
      : status !== 'in_progress'
        ? 'idle'
        : run.status === 'CANCELLED'
          ? 'stopped'
          : run.status === 'FAILED'
            ? 'error'
            : ['WAITING_APPROVAL', 'WAITING_CLARIFICATION'].includes(run.status)
              ? 'waiting'
              : run.status === 'RUNNING'
                ? 'running'
                : 'idle';
  return <StatusMark tone={tone} label={planStepLabel(run, status)} />;
}
