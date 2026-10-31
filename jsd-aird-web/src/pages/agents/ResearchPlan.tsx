import { useState } from 'react';
import type { AgentRun } from '@/services/agents/agent-api';
import { researchText } from './research-presentation';
import { ResearchStepStatus } from './ResearchStatus';

export function ResearchPlan({ run }: { run: AgentRun }) {
  const [disclosure, setDisclosure] = useState<{ runId: string; open: boolean }>();
  const plan = run.plan;
  if (plan?.presentation !== 'research_plan_v1' || plan.task_type !== 'RESEARCH') return null;
  const active = ['QUEUED', 'RUNNING'].includes(run.status);
  const open = disclosure?.runId === run.id ? disclosure.open : active;
  return (
    <section className="research-plan" aria-label="研究计划">
      <details className="research-process" open={open}>
        <summary
          onClick={(event) => {
            event.preventDefault();
            setDisclosure({ runId: run.id, open: !open });
          }}
        >
          研究计划
        </summary>
        <div>
          <h4>{researchText(plan.goal)}</h4>
          <p>{researchText(plan.summary)}</p>
          {plan.scope && (
            <p className="research-plan-scope">资料范围：{researchText(plan.scope)}</p>
          )}
          <ol>
            {plan.steps.map((step) => (
              <li
                key={step.id}
                aria-current={
                  run.status === 'RUNNING' && step.status === 'in_progress' ? 'step' : undefined
                }
              >
                <div className="research-plan-step-heading">
                  <strong>{researchText(step.title)}</strong>
                  <ResearchStepStatus run={run} status={step.status} />
                </div>
                {step.description && <p>{researchText(step.description)}</p>}
              </li>
            ))}
          </ol>
          <p className="research-plan-note">
            可在下方输入框调整研究方向；步骤完成不代表结论已核实。
          </p>
        </div>
      </details>
    </section>
  );
}
