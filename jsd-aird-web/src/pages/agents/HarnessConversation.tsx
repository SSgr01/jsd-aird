import { Alert, Button, Space, Typography } from 'antd';
import { ArrowUpOutlined } from '@ant-design/icons';
import type { TextAreaRef } from 'antd/es/input/TextArea';
import { QueuedMessages } from './QueuedMessages';
import {
  AiConversationWorkspace,
  type ConversationItem,
  type ConversationMessage,
} from '@/components/ai-conversation-workspace';
import { MarkdownContent } from '@/components/markdown/MarkdownContent';
import {
  agentApi,
  citedMarkdown,
  type AgentRun,
  type Conversation,
  type ConversationEntry,
  type LiteratureReport,
  type QueueChange,
} from '@/services/agents/agent-api';
import { useRef, type MouseEvent } from 'react';
import { ResearchRunSummary } from './ResearchRunSummary';
import { ResearchRunStatus } from './ResearchStatus';
import { reportAssessment, reportOutputs, researchText } from './research-presentation';

interface Props {
  run?: AgentRun;
  topics?: ConversationItem[];
  activeTopicId?: string;
  scope?: string;
  onNewTask?: () => void;
  onSelectTask?: (id: string) => void;
  conversation?: Conversation;
  draft: string;
  request: string;
  busy: boolean;
  ready: boolean;
  restoring: boolean;
  progress: Array<{ key: number; text: string }>;
  reports?: LiteratureReport[];
  onReport?: (id: string) => void;
  onRequest: (value: string) => void;
  onRestoreQueued: (item: ConversationEntry) => Promise<boolean>;
  onSend: () => void;
  onCancel: () => void;
  onRetry: () => void;
  onQueue: (change: QueueChange) => Promise<boolean>;
  onApproval: (decision: 'APPROVED' | 'REJECTED') => void;
  onMaterials: (tab: string) => void;
  onCitation: (event: MouseEvent<HTMLDivElement>, runId?: string) => void;
}

const examples = [
  '帮我找 PDMS 在有机溶剂中溶胀的研究，对比测试条件，形成带引用的报告。',
  '找出与高延展、快速自愈水凝胶相关的内部文献，比较机理、测试方法和证据缺口。',
];

export function HarnessConversation(props: Props) {
  const inputRef = useRef<TextAreaRef>(null);
  const { run, conversation, draft, busy } = props;
  const active = Boolean(run && ['QUEUED', 'RUNNING'].includes(run.status));
  const waiting = run?.status === 'WAITING_APPROVAL';
  const stopAction = Boolean(run && (waiting || (active && !props.request.trim())));
  const submitLabel = active ? '排队发送' : run ? '发送' : '开始任务';
  const history = (conversation?.messages || []).filter(
    (item) =>
      !(conversation?.queued || []).some((queued) => queued.id === item.id) &&
      (item.role !== 'assistant' || item.run_id !== run?.id),
  );
  const segments = run?.assistant_messages?.length
    ? run.assistant_messages
    : !active && !draft && (run?.result || run?.last_text)
      ? [{ id: 'last', text: run.result || run.last_text || '' }]
      : [];
  const savedReports = reportOutputs(run);
  const primaryOutput = savedReports[0];
  const primaryReport = props.reports?.find((item) => item.id === primaryOutput?.id);
  const assessment = reportAssessment(primaryReport);
  const evidenceCount = new Set(
    (run?.registered_outputs || []).filter((item) => item.kind === 'card').map((item) => item.id),
  ).size;
  const files = (run?.registered_outputs || []).filter((item) => item.kind === 'artifact');
  const markdown = (text: string, runId?: string) => (
    <div onClick={(event) => props.onCitation(event, runId)}>
      <MarkdownContent value={citedMarkdown(researchText(text))} />
    </div>
  );
  const messages: ConversationMessage[] = history.map((item) => ({
    id: 'history-' + item.id,
    role: item.role === 'user' ? 'USER' : 'ASSISTANT',
    content:
      item.role === 'user' ? (
        <>
          {item.text}
          {item.status === 'PENDING' && (
            <p className="harness-muted">已接收 · 等待下一安全执行边界</p>
          )}
          {item.status === 'DELIVERED' && <p className="harness-muted">已交给助手</p>}
        </>
      ) : item.text.length > 400 ||
        /\b(?:finish_task|report_save|plan_update)\b/.test(item.text) ? (
        <details className="research-process">
          <summary>查看此前回复</summary>
          {markdown(item.text, item.run_id)}
        </details>
      ) : (
        markdown(item.text, item.run_id)
      ),
  }));
  if (run?.request && !history.some((item) => item.role === 'user' && item.run_id === run.id))
    messages.push({ id: 'request-' + run.id, role: 'USER', content: run.request });
  const currentStart = messages.length;
  if (run)
    messages.push({
      id: 'progress-' + run.id,
      role: 'ASSISTANT',
      content: (
        <ResearchRunSummary run={run} progress={props.progress} busy={busy} onRetry={props.onRetry}>
          {segments.slice(0, -1).map((item) => (
            <div key={item.id} className="research-process-message">
              {markdown(item.text, run.id)}
            </div>
          ))}
        </ResearchRunSummary>
      ),
    });
  segments.slice(-1).forEach((item) =>
    messages.push({
      id: 'segment-' + item.id,
      role: 'ASSISTANT',
      content:
        active ||
        waiting ||
        run?.status === 'CANCELLED' ||
        run?.status === 'FAILED' ||
        (primaryOutput && item.text.length > 400) ? (
          <details className="research-process">
            <summary>查看助手说明</summary>
            {markdown(item.text, run?.id)}
          </details>
        ) : (
          markdown(item.text, run?.id)
        ),
    }),
  );
  if (draft)
    messages.push({
      id: 'draft',
      role: 'ASSISTANT',
      content: (
        <>
          {markdown(draft, run?.id)}
          <p className="harness-muted">引用与结论尚待交付检查。</p>
        </>
      ),
    });
  if (
    run &&
    (active ||
      waiting ||
      run.error ||
      run.status === 'CANCELLED' ||
      run.registered_outputs?.length ||
      props.progress.length)
  )
    messages.push({
      id: 'state-' + run.id,
      role: 'ASSISTANT',
      content: (
        <>
          {' '}
          {waiting && run?.pending_approval && (
            <Alert
              type="info"
              message="需要你确认外部检索"
              description={
                <div>
                  <p>只发送以下检索词；拒绝后助手可继续处理内部资料。</p>
                  <Typography.Paragraph copyable>
                    {run.pending_approval.arguments.query}
                  </Typography.Paragraph>
                  <Space wrap>
                    <Button loading={busy} onClick={() => props.onApproval('APPROVED')}>
                      允许此检索词
                    </Button>
                    <Button disabled={busy} onClick={() => props.onApproval('REJECTED')}>
                      仅用内部资料
                    </Button>
                  </Space>
                </div>
              }
            />
          )}
          {primaryOutput && (
            <section className="research-report-entry" aria-label="当前报告">
              <p className="research-report-entry-meta">
                本轮最新保存 · 报告草稿 · {assessment.label}
              </p>
              <h3 title={primaryOutput.title}>{primaryOutput.title}</h3>
              <p>{assessment.detail}</p>
              <Space wrap>
                <Button
                  type="primary"
                  onClick={() =>
                    props.onReport ? props.onReport(primaryOutput.id) : props.onMaterials('reports')
                  }
                >
                  打开报告
                </Button>
                <Button type="text" onClick={() => props.onMaterials('reports')}>
                  查看保存历史
                </Button>
              </Space>
            </section>
          )}
          {evidenceCount > 0 && (
            <div className="research-secondary-output">
              <span>{evidenceCount} 条实例证据 · 抽取核对状态见证据区</span>
              <Button type="link" onClick={() => props.onMaterials('evidence')}>
                查看证据与比较
              </Button>
            </div>
          )}
          {!!files.length && (
            <details className="research-process">
              <summary>分析文件（{files.length}）</summary>
              {files.map((item) => (
                <div className="harness-delivery" key={item.id}>
                  <span>{item.title}</span>
                  <Button href={agentApi.artifactUrl(item.id)}>下载文件</Button>
                </div>
              ))}
            </details>
          )}
        </>
      ),
    });
  if (run && messages.length > currentStart) {
    const currentMessages = messages.splice(currentStart);
    messages.push({
      id: 'turn-' + run.id,
      role: 'ASSISTANT',
      content: (
        <div className="research-current-turn">
          {currentMessages.map((item) => (
            <div key={item.id}>{item.content}</div>
          ))}
        </div>
      ),
    });
  }
  return (
    <AiConversationWorkspace
      conversations={props.topics || []}
      activeConversationId={props.activeTopicId}
      messages={messages}
      initialScrollMessageId={
        active && run?.plan?.presentation === 'research_plan_v1' ? 'turn-' + run.id : undefined
      }
      question={props.request}
      inputRef={inputRef}
      inputDisabled={busy || props.restoring}
      loading={props.restoring}
      navigationDisabled={busy || props.restoring}
      hideEmptyList
      assistantLabel="研究助手"
      inputLabel="交代任务或继续交流"
      inputMaxLength={12000}
      inputPlaceholder={
        active
          ? '补充要求，发送后加入队列…'
          : run?.status === 'CANCELLED'
            ? '输入“继续”，或补充调整后的要求…'
            : '直接交代研究目标，不用先建主题或找齐资料。'
      }
      submitLabel={submitLabel}
      submitAction={
        <Button
          type="primary"
          size="large"
          shape="circle"
          icon={
            stopAction ? (
              <span className="harness-stop-symbol" aria-hidden="true" />
            ) : (
              <ArrowUpOutlined />
            )
          }
          aria-label={stopAction ? '停止当前任务' : submitLabel}
          title={
            stopAction
              ? '停止当前执行并暂停待发送队列；已完成操作不会撤销'
              : active
                ? '排队发送：本轮完成后处理'
                : submitLabel
          }
          loading={busy}
          disabled={
            busy ||
            props.restoring ||
            (!stopAction && (!props.request.trim() || !props.ready || waiting))
          }
          onClick={stopAction ? props.onCancel : props.onSend}
        />
      }
      submitDisabled={busy || !props.ready || props.restoring || waiting}
      welcomeTitle="把研究任务交给我"
      welcomeDescription="我会查找授权资料、阅读证据，交付可追溯的结果。"
      welcomeContent={
        <div className="harness-examples">
          {examples.map((text) => (
            <button key={text} onClick={() => props.onRequest(text)}>
              {text}
            </button>
          ))}
        </div>
      }
      scopeTitle="资料与成果"
      scopeContent={
        <Space direction="vertical">
          <span>{props.scope || '自动查找授权知识库'}</span>
          <span className="harness-muted">外部检索需确认</span>
          <Button onClick={() => props.onMaterials('documents')}>文献与阅读</Button>
          <Button onClick={() => props.onMaterials('evidence')}>证据核对与比较</Button>
          <Button onClick={() => props.onMaterials('reports')}>研究报告</Button>
        </Space>
      }
      composerTopContent={
        <div>
          <div className="harness-composer-status">
            <span className="harness-muted">
              {run ? (
                <>
                  <ResearchRunStatus run={run} prefix="状态：" />
                  <span className="research-composer-hint">
                    {active
                      ? '发送后排队'
                      : run.status === 'CANCELLED'
                        ? '输入“继续”可接着处理'
                        : ''}
                  </span>
                </>
              ) : (
                props.scope || '自动查找授权知识库'
              )}
            </span>
          </div>
          <QueuedMessages
            items={conversation?.queued || []}
            paused={conversation?.queue_paused}
            busy={busy || props.restoring}
            canSteer={active && props.ready}
            canRestore={!props.request.trim()}
            onRestore={async (item) => {
              if (await props.onRestoreQueued(item)) inputRef.current?.focus({ cursor: 'end' });
            }}
            onChange={props.onQueue}
          />
          {!props.ready && (
            <p className="harness-muted">执行服务尚未就绪；可以先阅读已保存资料。</p>
          )}
          {waiting && <p className="harness-muted">请先确认上方外部检索请求，或停止本轮任务。</p>}
        </div>
      }
      onNewConversation={() => props.onNewTask?.()}
      onSelectConversation={(id) => props.onSelectTask?.(id)}
      onQuestionChange={props.onRequest}
      onSubmit={props.onSend}
    />
  );
}
