import { useState } from 'react';
import { CheckOutlined, LoadingOutlined } from '@ant-design/icons';
import type { AgentRun } from '@/services/agents/agent-api';
import { researchText } from './research-presentation';

export function ResearchPlan({ run }: { run: AgentRun }) {
  const [disclosure, setDisclosure] = useState<{ runId: string; open: boolean }>();
  const plan = run.plan;
  if (!plan?.steps.length) return null;
  const active = ['QUEUED', 'RUNNING'].includes(run.status);
  const completed = plan.steps.filter((step) => step.status === 'completed').length;
  const current = plan.steps.find((step) => step.status === 'in_progress');
  const open = disclosure?.runId === run.id ? disclosure.open : completed < plan.steps.length;
  return (
    <details className="research-plan" aria-label="研究计划" open={open}>
      <summary
        onClick={(event) => {
          event.preventDefault();
          setDisclosure({ runId: run.id, open: !open });
        }}
      >
        <span>研究计划</span>
        <span className="research-plan-count">
          {completed} / {plan.steps.length} 步
        </span>
        {!open && active && current && (
          <span className="research-plan-current">{researchText(current.title)}</span>
        )}
      </summary>
      <ol>
        {plan.steps.map((step, index) => (
          <li
            key={step.id}
            className={
              step.status === 'completed'
                ? 'is-complete'
                : active && step.status === 'in_progress'
                  ? 'is-current'
                  : ''
            }
            aria-current={active && step.status === 'in_progress' ? 'step' : undefined}
          >
            <span className="research-plan-marker" aria-hidden="true">
              {step.status === 'completed' ? (
                <CheckOutlined />
              ) : active && step.status === 'in_progress' ? (
                <LoadingOutlined spin />
              ) : (
                index + 1
              )}
            </span>
            <div>
              <strong>{researchText(step.title)}</strong>
              {step.description && <p>{researchText(step.description)}</p>}
            </div>
          </li>
        ))}
      </ol>
    </details>
  );
}
