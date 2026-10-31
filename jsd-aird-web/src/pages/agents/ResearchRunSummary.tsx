import type { AgentRun } from '@/services/agents/agent-api';
import { useId, useState, type ReactNode } from 'react';
import { Button } from 'antd';
import { researchText } from './research-presentation';
import { ResearchPlan } from './ResearchPlan';
import { ResearchRunStatus, ResearchStepStatus } from './ResearchStatus';

export function ResearchRunSummary({
  run,
  progress,
  children,
  busy = false,
  onRetry,
}: {
  run: AgentRun;
  progress: Array<{ key: number; text: string }>;
  children?: ReactNode;
  busy?: boolean;
  onRetry?: () => void;
}) {
  const active = ['QUEUED', 'RUNNING'].includes(run.status);
  const researchPlan =
    run.plan?.presentation === 'research_plan_v1' && run.plan.task_type === 'RESEARCH';
  const currentStep = researchPlan
    ? run.plan?.steps.find((step) => step.status === 'in_progress')
    : undefined;
  const errorId = useId();
  const [expandedError, setExpandedError] = useState<string>();
  const showError = Boolean(run.error && expandedError === run.error);
  const reconciliation = /SIDE_EFFECT_RECONCILIATION_REQUIRED|TOOL_RESULT_UNKNOWN/.test(
    run.error || '',
  );
  return (
    <section className="research-run-summary" aria-label="研究进度">
      <div
        className={`research-run-heading${run.error ? ' has-error' : ''}`}
        role={active || run.error ? 'status' : undefined}
      >
        <ResearchRunStatus run={run} />
        {reconciliation && <span className="research-error-caution">请勿重复提交</span>}
        {run.error && (
          <>
            {run.retryable === true && !reconciliation && onRetry && (
              <Button type="text" size="small" loading={busy} onClick={onRetry}>
                重试
              </Button>
            )}
            <button
              className="research-error-toggle"
              aria-expanded={showError}
              aria-controls={errorId}
              onClick={() => setExpandedError(showError ? undefined : run.error)}
            >
              {showError ? '收起原因' : '查看原因'}
            </button>
          </>
        )}
      </div>
      {showError && (
        <div id={errorId} className="research-error-reason">
          <p>{run.error}</p>
          {reconciliation ? (
            <p>部分操作的结果尚未确认，需先核实，不能直接重试。</p>
          ) : (
            run.retryable !== true && <p>请调整要求或联系管理员排查。</p>
          )}
        </div>
      )}
      {run.status !== 'FAILED' && (
        <p className="research-run-caption">
          {active
            ? currentStep
              ? `当前：${researchText(currentStep.title)}`
              : researchText(progress.at(-1)?.text || '正在整理任务与资料，研究过程会在这里更新。')
            : run.status === 'CANCELLED'
              ? '已保存的资料与草稿保留。输入“继续”或调整要求即可接着处理。'
              : run.status === 'WAITING_APPROVAL'
                ? '确认后继续；也可以拒绝外部检索，仅使用内部资料。'
                : run.completion?.outcome === 'NEEDS_INPUT' || run.completion?.outcome === 'PARTIAL'
                  ? researchText(run.completion.summary)
                  : '执行结束不代表事实已经核实，请查看报告的核查状态。'}
        </p>
      )}
      {!active &&
        !['CANCELLED', 'FAILED', 'WAITING_APPROVAL'].includes(run.status) &&
        run.completion?.outcome === 'NEEDS_INPUT' &&
        !!run.completion.gaps.length && (
          <ul>
            {run.completion.gaps.map((gap) => (
              <li key={gap}>{researchText(gap)}</li>
            ))}
          </ul>
        )}
      <ResearchPlan run={run} />
      <details className="research-process">
        <summary>查看研究过程</summary>
        {run.plan && !researchPlan && (
          <section className="research-process-plan" aria-label="助手工作计划">
            <p>{researchText(run.plan.summary)}</p>
            <ol>
              {run.plan.steps.map((step) => (
                <li key={step.id}>
                  <span className="research-step-label">
                    <ResearchStepStatus run={run} status={step.status} />
                  </span>
                  <span>{researchText(step.title)}</span>
                </li>
              ))}
            </ol>
            <p className="harness-muted">以上为助手维护的计划，不是原文核对或事实核查记录。</p>
          </section>
        )}
        {children}
        {run.completion && (
          <section aria-label="助手交付自检">
            <h4>助手交付自检</h4>
            <p>{researchText(run.completion.summary)}</p>
            {!!run.completion.gaps.length && (
              <ul>
                {run.completion.gaps.map((gap) => (
                  <li key={gap}>{researchText(gap)}</li>
                ))}
              </ul>
            )}
            {!!run.completion.checks.length && (
              <ul>
                {run.completion.checks.map((check) => (
                  <li key={check.requirement}>
                    {check.status === 'covered'
                      ? '助手标记已覆盖'
                      : check.status === 'missing'
                        ? '有缺口'
                        : '不适用'}{' '}
                    · {researchText(check.requirement)}
                  </li>
                ))}
              </ul>
            )}
            <p className="harness-muted">助手自检不等于人工原文核对或实验复现。</p>
          </section>
        )}
        {!!progress.length && (
          <details className="harness-debug">
            <summary>查看活动记录</summary>
            {progress.map((item) => (
              <div key={item.key}>{researchText(item.text)}</div>
            ))}
          </details>
        )}
        <p className="harness-muted">
          工具调用 {run.tool_calls} 次 · 模型请求 {run.model_calls} 次
        </p>
      </details>
    </section>
  );
}
