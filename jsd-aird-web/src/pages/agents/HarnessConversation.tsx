import { Alert, Button, Space } from 'antd';
import { DownloadOutlined, FileTextOutlined } from '@ant-design/icons';
import type { TextAreaRef } from 'antd/es/input/TextArea';
import { QueuedMessages } from './QueuedMessages';
import {
  AiConversationWorkspace,
  type ConversationItem,
  type ConversationMessage,
} from '@/components/ai-conversation-workspace';
import { ConversationBody } from '@/components/ai-conversation-workspace/ConversationBody';
import {
  ConversationIconButton,
  CopyAnswerButton,
} from '@/components/ai-conversation-workspace/ConversationIconButton';
import {
  agentApi,
  citedMarkdown,
  type AgentRun,
  type Conversation,
  type ConversationEntry,
  type LiteratureReport,
  type QueueChange,
} from '@/services/agents/agent-api';
import { useEffect, useRef, useState, type MouseEvent } from 'react';
import { ResearchPlan } from './ResearchPlan';
import { ResearchRunSummary } from './ResearchRunSummary';
import { ResearchOperations } from './ResearchOperations';
import {
  deliveryProblems,
  operationUnknown,
  reportAssessment,
  reportOutputs,
  researchText,
} from './research-presentation';

interface Props {
  run?: AgentRun;
  topics?: ConversationItem[];
  activeTopicId?: string;
  scope?: string;
  onNewTask?: () => void;
  onSelectTask?: (id: string) => void;
  conversation?: Conversation;
  draft: string;
  draftId?: string;
  draftAfterMessageSeq?: number;
  request: string;
  busy: boolean;
  ready: boolean;
  readinessMessage?: string;
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
  onUpload?: () => void;
  onChoose?: () => void;
  onCitation: (event: MouseEvent<HTMLDivElement>, runId?: string) => void;
}

const examples = [
  '比较文献中水凝胶的自愈效果，并整理成报告。',
  '阅读我上传的论文，提取关键数据，生成计算表。',
];

export function HarnessConversation(props: Props) {
  const inputRef = useRef<TextAreaRef>(null);
  const [focusRestoredMessage, setFocusRestoredMessage] = useState(false);
  useEffect(() => {
    if (!focusRestoredMessage || props.busy || props.restoring) return;
    inputRef.current?.focus({ cursor: 'end', preventScroll: true });
    setFocusRestoredMessage(false);
  }, [focusRestoredMessage, props.busy, props.restoring]);
  const { run, conversation, draft, busy } = props;
  const active = Boolean(run && ['QUEUED', 'RUNNING'].includes(run.status));
  const waiting = run?.status === 'WAITING_APPROVAL';
  const unknown = Boolean(run && operationUnknown(run));
  const stopAction = Boolean(run && (waiting || (active && !props.request.trim())));
  const history = (conversation?.messages || []).filter(
    (item) =>
      !(conversation?.queued || []).some((queued) => queued.id === item.id) &&
      (!run || item.run_id !== run.id),
  );
  const primaryOutput = reportOutputs(run)[0];
  const primaryReport = props.reports?.find((item) => item.id === primaryOutput?.id);
  const files = [
    ...new Map(
      (run?.registered_outputs || [])
        .filter((item) => item.kind === 'artifact')
        .map((item) => [item.id, item]),
    ).values(),
  ];
  const markdown = (text: string, runId?: string, streaming = false) => (
    <div onClick={(event) => props.onCitation(event, runId)}>
      <ConversationBody text={citedMarkdown(researchText(text))} streaming={streaming} />
    </div>
  );
  const messages: ConversationMessage[] = history.map((item) => ({
    id: 'history-' + item.id,
    role: item.role === 'user' ? 'USER' : 'ASSISTANT',
    content: item.role === 'user' ? item.text : markdown(item.text, item.run_id),
    copyText: item.role === 'assistant' ? researchText(item.text) : undefined,
  }));
  if (run?.request) messages.push({ id: 'request-' + run.id, role: 'USER', content: run.request });
  const queuedIds = new Set((conversation?.queued || []).map((item) => item.id));
  const conversationSteering = (conversation?.messages || []).filter(
    (item) => item.run_id === run?.id && ['PENDING', 'DELIVERED'].includes(item.status || ''),
  );
  const steering = [
    ...new Map(
      [...conversationSteering, ...(run?.messages || [])].map((item) => [item.id, item]),
    ).values(),
  ]
    .filter((item) => !queuedIds.has(item.id))
    .map((item, index) => ({
      ...item,
      status:
        item.status === 'DELIVERED' ||
        conversationSteering.some((saved) => saved.id === item.id && saved.status === 'DELIVERED')
          ? 'DELIVERED'
          : item.status,
      seq: item.seq ?? index + 1,
    }))
    .sort((left, right) => left.seq - right.seq);
  const deliveredSeq = Math.max(
    0,
    ...steering.filter((item) => item.status === 'DELIVERED').map((item) => item.seq),
  );
  // Assistant speech stays in the conversation, including streamed updates. Tool activity
  // is represented separately by one status line, never by a stack of execution events.
  const replies: Array<{
    id: string;
    text: string;
    streaming?: boolean;
    after_message_seq?: number;
  }> = (run?.assistant_messages || []).filter(
    (reply, index, all) =>
      reply.text.trim() && (index === 0 || reply.text.trim() !== all[index - 1]?.text.trim()),
  );
  const finalText = !active && !waiting ? run?.result || run?.last_text : undefined;
  if (finalText && replies.at(-1)?.text.trim() !== finalText.trim())
    replies.push({ id: 'result', text: finalText, after_message_seq: deliveredSeq });
  if (
    draft.trim() &&
    !replies.some((reply) => reply.id === props.draftId) &&
    draft.trim() !== replies.at(-1)?.text.trim()
  )
    replies.push({
      id: props.draftId || 'draft-' + run?.id,
      text: draft,
      streaming: active,
      after_message_seq: props.draftAfterMessageSeq ?? deliveredSeq,
    });
  const replyBody = (reply: (typeof replies)[number]) => (
    <div
      className={`research-assistant-reply${reply.streaming ? ' is-streaming' : ''}`}
      key={reply.id}
    >
      {markdown(reply.text, run?.id, reply.streaming)}
    </div>
  );
  const groups = [
    ...new Set([
      0,
      ...steering.map((item) => item.seq),
      ...replies.map((reply) => reply.after_message_seq ?? deliveredSeq),
    ]),
  ].sort((left, right) => left - right);
  if (run)
    for (const seq of groups) {
      const supplement = steering.find((item) => item.seq === seq);
      if (supplement)
        messages.push({
          id: 'history-' + supplement.id,
          role: 'USER',
          content: (
            <>
              {supplement.text}
              {active && (
                <span className="research-message-delivery" role="status">
                  {supplement.status === 'DELIVERED' ? '已加入当前任务' : '等待当前步骤结束后接收'}
                </span>
              )}
            </>
          ),
        });
      const groupReplies = replies.filter(
        (reply) => (reply.after_message_seq ?? deliveredSeq) === seq,
      );
      const last = seq === groups.at(-1);
      messages.push({
        id: 'turn-' + run.id + (seq ? '-' + seq : ''),
        role: 'ASSISTANT',
        content: (
          <div className="research-current-turn">
            {groupReplies[0] && replyBody(groupReplies[0])}
            {seq === 0 && <ResearchPlan key="plan" run={run} />}
            {groupReplies.slice(1).map(replyBody)}
            {last && (
              <>
                <ResearchRunSummary
                  run={run}
                  progress={props.progress}
                  busy={busy}
                  onRetry={props.onRetry}
                />
                <ResearchOperations runId={run.id} active={active} showNotice={!unknown} />
                {waiting && run.pending_approval && (
                  <Alert
                    type="info"
                    message={
                      run.pending_approval.tool === 'literature_acquire'
                        ? '允许获取论文全文吗？'
                        : '要到互联网上查找文献吗？'
                    }
                    description={
                      <div>
                        <p>
                          {run.pending_approval.description ||
                            (run.pending_approval.arguments.query
                              ? `将搜索：${run.pending_approval.arguments.query}`
                              : '查找与已选论文相关的文献。')}
                        </p>
                        <Space wrap>
                          <Button loading={busy} onClick={() => props.onApproval('APPROVED')}>
                            {run.pending_approval.tool === 'literature_acquire'
                              ? '允许获取全文'
                              : '允许本次查询'}
                          </Button>
                          <Button disabled={busy} onClick={() => props.onApproval('REJECTED')}>
                            只用已有资料
                          </Button>
                        </Space>
                      </div>
                    }
                  />
                )}
                {!!files.length && (
                  <section className="research-delivery-files" aria-label="生成的文件">
                    <h3>生成的文件</h3>
                    {files.map((file) => (
                      <div className="harness-delivery conversation-file-card" key={file.id}>
                        <span>
                          {file.title}
                          <small>{file.title.split('.').at(-1)?.toUpperCase()}</small>
                        </span>
                        <Button
                          type="text"
                          icon={<DownloadOutlined />}
                          href={agentApi.artifactUrl(file.id)}
                        >
                          下载
                        </Button>
                      </div>
                    ))}
                  </section>
                )}
                {primaryOutput && (
                  <section className="research-report-entry" aria-label="当前报告">
                    <h3>{primaryOutput.title}</h3>
                    <p>{reportAssessment(primaryReport).detail}</p>
                    <Button
                      icon={<FileTextOutlined />}
                      onClick={() => props.onReport?.(primaryOutput.id)}
                    >
                      阅读报告
                    </Button>
                  </section>
                )}
                {!active &&
                  deliveryProblems(run).map((problem) => (
                    <p key={problem} className="research-delivery-problem">
                      {problem}
                    </p>
                  ))}
                {!active && Object.keys(run.report_checks || {}).length > 0 && !primaryOutput && (
                  <Button
                    type="link"
                    className="research-problem-link"
                    onClick={() => props.onMaterials('checks')}
                  >
                    查看需要补充的内容
                  </Button>
                )}
                {!active && replies.length > 0 && (
                  <CopyAnswerButton
                    text={replies.map((reply) => researchText(reply.text)).join('\n\n')}
                  />
                )}
              </>
            )}
          </div>
        ),
      });
    }
  return (
    <AiConversationWorkspace
      conversations={props.topics || []}
      activeConversationId={props.activeTopicId}
      messages={messages}
      compactComposer
      question={props.request}
      inputRef={inputRef}
      inputDisabled={busy || props.restoring}
      loading={props.restoring}
      navigationDisabled={busy || props.restoring}
      hideEmptyList
      assistantLabel="研究助手"
      inputLabel="交代任务或继续交流"
      inputMaxLength={12000}
      inputPlaceholder={active ? '补充要求，当前任务完成后依次处理…' : '描述你想研究的问题…'}
      submitLabel={active ? '排队发送' : '发送'}
      submitAction={
        <ConversationIconButton
          label={stopAction ? '停止当前任务' : active ? '排队发送' : '发送'}
          tooltip={
            stopAction ? '停止当前任务' : active ? '加入待发送队列，当前任务完成后处理' : '发送'
          }
          name={stopAction ? 'stop' : 'send'}
          type="primary"
          size="large"
          shape="circle"
          loading={busy}
          disabled={
            busy ||
            props.restoring ||
            (!stopAction && (!props.request.trim() || !props.ready || waiting || unknown))
          }
          onClick={stopAction ? props.onCancel : props.onSend}
        />
      }
      submitDisabled={busy || !props.ready || props.restoring || waiting || unknown}
      welcomeTitle="今天想研究什么？"
      welcomeDescription="提出问题，或上传文献，我会帮你分析并整理结果。"
      welcomeContent={
        <div className="harness-examples">
          {examples.map((text) => (
            <button key={text} onClick={() => props.onRequest(text)}>
              {text}
            </button>
          ))}
        </div>
      }
      composerActions={
        <Space wrap size={4}>
          <ConversationIconButton
            label="选择资料"
            name="sources"
            onClick={props.onChoose}
            disabled={busy || active || waiting}
          />
          <ConversationIconButton
            label="上传文件"
            name="attach"
            onClick={props.onUpload}
            disabled={busy || active || waiting}
          />
          <ConversationIconButton
            label="资料与文件"
            name="files"
            onClick={() => props.onMaterials('references')}
          />
          {active && !stopAction && (
            <ConversationIconButton
              label="停止当前任务"
              name="stop"
              disabled={busy || props.restoring}
              onClick={props.onCancel}
            />
          )}
        </Space>
      }
      composerTopContent={
        <>
          {props.scope && <span className="harness-muted">{props.scope}</span>}
          <QueuedMessages
            items={conversation?.queued || []}
            paused={conversation?.queue_paused}
            busy={busy || props.restoring}
            canSteer={active && props.ready}
            canRestore={!props.request.trim()}
            onRestore={async (item) => {
              if (await props.onRestoreQueued(item)) setFocusRestoredMessage(true);
            }}
            onChange={props.onQueue}
          />
          {!props.ready && (
            <p className="harness-muted">
              {props.readinessMessage || '服务暂不可用，请稍后再试。'}
            </p>
          )}
        </>
      }
      onNewConversation={() => props.onNewTask?.()}
      onSelectConversation={(id) => props.onSelectTask?.(id)}
      onQuestionChange={props.onRequest}
      onSubmit={props.onSend}
    />
  );
}
