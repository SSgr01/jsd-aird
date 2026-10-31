import {
  App,
  Alert,
  Button,
  Drawer,
  Input,
  List,
  Modal,
  Space,
  Spin,
  Table,
  Tabs,
  Tag,
  Typography,
} from 'antd';
import { useCallback, useEffect, useRef, useState, type MouseEvent } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { isAxiosError } from 'axios';
import { Link } from 'react-router-dom';
import { OriginalDocumentViewer } from '@/components/knowledge-document/OriginalDocumentViewer';
import { StructuredDocumentRenderer } from '@/components/knowledge-document/StructuredDocumentRenderer';
import {
  agentApi,
  type AgentEvent,
  type AgentRun,
  type Candidate,
  type Conversation,
  type EvidenceCard,
  type SourceRef,
  type QueueChange,
} from '@/services/agents/agent-api';
import {
  knowledgeApi,
  type KnowledgeDocument,
  type PublishedContent,
} from '@/services/knowledge/knowledge-api';
import { useAuthStore } from '@/stores/auth-store';
import { HarnessConversation } from './HarnessConversation';
import { ResearchQualityPanel } from './ResearchQualityPanel';
import { ResearchReportReader } from './ResearchReportReader';
import { reportAssessment, reportOutputs } from './research-presentation';
import { generateUUID } from '@/utils/uuid';
import './research-workspace.css';

const activeStatuses = ['QUEUED', 'RUNNING'];
const observedStatuses = [...activeStatuses, 'WAITING_APPROVAL'];

export function ResearchWorkspacePage() {
  const { message } = App.useApp();
  const owner = useAuthStore((state) => state.user?.userId);
  const queries = useQueryClient();
  const [topicId, setTopicId] = useState<string>();
  const [selectedTask, setSelectedTask] = useState<string>();
  const [run, setRun] = useState<AgentRun>();
  const [conversation, setConversation] = useState<Conversation>();
  const [request, setRequest] = useState('');
  const [draft, setDraft] = useState('');
  const [progress, setProgress] = useState<Array<{ key: number; text: string }>>([]);
  const [source, setSource] = useState<SourceRef>();
  const [resourcesOpen, setResourcesOpen] = useState(false);
  const [resourcesTab, setResourcesTab] = useState('documents');
  const [reportOpen, setReportOpen] = useState(false);
  const [selectedReportId, setSelectedReportId] = useState<string>();
  const openedReportRun = useRef<string>();
  const [restoring, setRestoring] = useState(false);
  const startKey = useRef(generateUUID());
  const [documentsOpen, setDocumentsOpen] = useState(false);
  const [selectedDocs, setSelectedDocs] = useState<string[]>([]);
  const selectedDocumentCache = useRef(new Map<string, KnowledgeDocument>());
  const [keyword, setKeyword] = useState('');
  const [busy, setBusy] = useState(false);
  const actionLock = useRef(false);
  const sendAttempt = useRef<{ signature: string; conversation?: Conversation; key: string }>();
  const queueAttempt = useRef<{ signature: string; conversation: Conversation; key: string }>();
  const [note, setNote] = useState('');
  useEffect(() => {
    if (!owner) return;
    setTopicId(sessionStorage.getItem('harness-topic:' + owner) || undefined);
    const savedConversation = sessionStorage.getItem('harness-conversation:' + owner);
    const savedTopic = sessionStorage.getItem('harness-topic:' + owner);
    setSelectedTask(savedConversation || (savedTopic ? 'topic:' + savedTopic : undefined));
    setRun(undefined);
    setConversation(undefined);
    setDraft('');
    setReportOpen(false);
    setSelectedReportId(undefined);
    openedReportRun.current = undefined;
  }, [owner]);
  const capability = useQuery({
    queryKey: ['agent-capabilities', owner],
    queryFn: agentApi.capabilities,
  });
  const topics = useQuery({
    queryKey: ['research-topics', owner],
    queryFn: agentApi.topics,
    enabled: capability.data?.enabled === true,
  });
  const conversationList = useQuery({
    queryKey: ['harness-conversations', owner],
    queryFn: agentApi.conversations,
    enabled: capability.data?.enabled === true,
  });
  const topicQuery = useQuery({
    queryKey: ['research-topic', owner, topicId],
    queryFn: () => agentApi.topic(topicId!),
    enabled: Boolean(topicId),
  });
  const topic = topicQuery.data;
  const latestRunReview = Object.values(run?.report_checks || {}).at(-1);
  const matrixReview =
    latestRunReview?.matrix_revision === run?.research_matrix?.revision
      ? latestRunReview
      : undefined;
  const cards = useQuery({
    queryKey: ['research-cards', owner, topicId],
    queryFn: () => agentApi.cards(topicId!),
    enabled: Boolean(topicId),
  });
  const reports = useQuery({
    queryKey: ['research-reports', owner, topicId],
    queryFn: () => agentApi.reports(topicId!),
    enabled: Boolean(topicId),
  });
  const artifacts = useQuery({
    queryKey: ['research-artifacts', owner, topicId],
    queryFn: () => agentApi.artifacts(topicId!),
    enabled: Boolean(topicId),
  });
  const annotations = useQuery({
    queryKey: ['research-notes', owner, topicId],
    queryFn: () => agentApi.annotations(topicId!),
    enabled: Boolean(topicId),
  });
  const documents = useQuery({
    queryKey: ['research-document-selection', owner, keyword],
    queryFn: () => knowledgeApi.list({ keyword, size: 100, lifecycleStatus: 'ACTIVE' }),
    enabled: documentsOpen,
  });
  const active = Boolean(run && activeStatuses.includes(run.status));

  useEffect(() => {
    const latest = reportOutputs(run)[0];
    if (
      !run ||
      observedStatuses.includes(run.status) ||
      !latest ||
      openedReportRun.current === run.id
    )
      return;
    openedReportRun.current = run.id;
    setSelectedReportId(latest.id);
    setReportOpen(true);
  }, [run]);

  useEffect(() => {
    if (
      !conversation?.id ||
      (!observedStatuses.includes(run?.status || '') &&
        !conversation.queued?.length &&
        (!conversation.active_run || conversation.active_run === run?.id))
    )
      return;
    const cid = conversation.id;
    let current = true;
    const timer = window.setInterval(() => {
      void agentApi
        .conversation(cid)
        .then(async (next) => {
          if (
            current &&
            run &&
            !observedStatuses.includes(run.status) &&
            next.active_run &&
            next.active_run !== run.id
          ) {
            const successor = await agentApi.run(next.active_run);
            if (!current) return;
            setRun(successor);
            setDraft('');
            setProgress([]);
          }
          if (current)
            setConversation((previous) =>
              previous?.id === cid && previous.revision > next.revision ? previous : next,
            );
        })
        .catch(() => {});
    }, 4000);
    return () => {
      current = false;
      window.clearInterval(timer);
    };
  }, [
    conversation?.id,
    conversation?.active_run,
    Boolean(conversation?.queued?.length),
    run?.id,
    run?.status,
  ]);

  const refreshResearch = useCallback(() => {
    void queries.invalidateQueries({ queryKey: ['harness-conversations', owner] });
    void queries.invalidateQueries({ queryKey: ['research-cards', owner, topicId] });
    void queries.invalidateQueries({ queryKey: ['research-reports', owner, topicId] });
    void queries.invalidateQueries({ queryKey: ['research-artifacts', owner, topicId] });
    void queries.invalidateQueries({ queryKey: ['research-notes', owner, topicId] });
  }, [queries, owner, topicId]);

  useEffect(() => {
    if (!selectedTask) return;
    if (conversation?.id === selectedTask) return;
    let current = true;
    setRestoring(true);
    void agentApi
      .conversations()
      .then(async (values) => {
        const selected = values.find((value) =>
          selectedTask.startsWith('topic:')
            ? value.topic_id === selectedTask.slice(6)
            : value.id === selectedTask,
        );
        if (!current || !selected) return;
        const detail = await agentApi.conversation(selected.id);
        if (!current) return;
        setConversation(detail);
        setTopicId(detail.topic_id || undefined);
        if (detail.active_run || detail.last_run) {
          const running = await agentApi.run((detail.active_run || detail.last_run)!);
          if (current) {
            setRun(running);
            setDraft('');
          }
        }
      })
      .catch(() => {
        if (current) void message.warning('历史任务暂时无法加载，可以刷新重试。');
      })
      .finally(() => {
        if (current) setRestoring(false);
      });
    return () => {
      current = false;
    };
  }, [selectedTask, message]);

  useEffect(() => {
    if (run?.topic_id) setTopicId(run.topic_id);
  }, [run?.topic_id]);

  useEffect(() => {
    if (!run?.id || !observedStatuses.includes(run.status)) return;
    const rid = run.id;
    let observing = true;
    const acceptRun = (value: AgentRun) => {
      if (observing)
        setRun((previous) => {
          // A slow in-flight snapshot must not undo a stop or overwrite its successor.
          if (previous && previous.id !== value.id) return previous;
          if (
            previous &&
            !observedStatuses.includes(previous.status) &&
            observedStatuses.includes(value.status)
          )
            return previous;
          return value;
        });
    };
    const unsubscribe = agentApi.subscribe(
      rid,
      (event: AgentEvent) => {
        if (!observing) return;
        if (event.type === 'message.started') setDraft('');
        if (
          ['conversation.queue', 'message.applied', 'run.cancelled', 'run.failed'].includes(
            event.type,
          ) &&
          conversation
        ) {
          void agentApi
            .conversation(conversation.id)
            .then((next) => {
              if (observing)
                setConversation((previous) =>
                  previous?.id === next.id && previous.revision > next.revision ? previous : next,
                );
            })
            .catch(() => {});
        }
        if (event.type === 'message.delta' && typeof event.payload.delta === 'string') {
          const delta = event.payload.delta;
          setDraft((value) => value + delta);
        }
        if (event.type === 'message.completed' && typeof event.payload.text === 'string') {
          const text = event.payload.text;
          const id = typeof event.payload.id === 'string' ? event.payload.id : 'event-' + event.seq;
          setRun((value) =>
            value && text.trim() && !(value.assistant_messages || []).some((item) => item.id === id)
              ? {
                  ...value,
                  assistant_messages: [...(value.assistant_messages || []), { id, text }],
                }
              : value,
          );
          setDraft('');
        }
        if (event.type === 'task.plan')
          setRun((value) =>
            value ? { ...value, plan: event.payload as unknown as AgentRun['plan'] } : value,
          );
        if (event.type === 'task.review')
          setRun((value) =>
            value
              ? { ...value, completion: event.payload as unknown as AgentRun['completion'] }
              : value,
          );
        if (event.type === 'research.activity') {
          const title = typeof event.payload.title === 'string' ? event.payload.title : '处理资料';
          const detail = typeof event.payload.detail === 'string' ? event.payload.detail : '';
          const phase =
            event.payload.status === 'running'
              ? '正在'
              : event.payload.status === 'failed'
                ? '未能'
                : '已';
          const count =
            typeof event.payload.candidates === 'number' && event.payload.candidates > 0
              ? ' · 返回 ' + event.payload.candidates + ' 条候选证据'
              : '';
          const text = phase + title + (detail ? '：' + detail : '') + count;
          setProgress((values) =>
            values.some((item) => item.key === event.seq)
              ? values
              : [...values.slice(-49), { key: event.seq, text }],
          );
          if (event.payload.status !== 'running')
            void agentApi
              .run(rid)
              .then(acceptRun)
              .catch(() => {});
          refreshResearch();
        }
        if (
          event.type.startsWith('approval.') ||
          ['research.matrix.updated', 'research.report.checked'].includes(event.type) ||
          (event.type.startsWith('run.') && event.type !== 'run.succeeded')
        ) {
          void agentApi
            .run(rid)
            .then(acceptRun)
            .catch(() => void message.error('任务状态读取失败'));
          refreshResearch();
        }
        if (event.type === 'run.succeeded' && conversation) {
          void agentApi
            .conversation(conversation.id)
            .then(async (detail) => {
              if (!observing) return;
              // Fetch the successor before publishing a terminal snapshot, which
              // would otherwise tear down this subscription mid-handoff.
              const next = await agentApi.run(detail.active_run || rid);
              if (!observing) return;
              setConversation((previous) =>
                previous?.id === detail.id && previous.revision > detail.revision
                  ? previous
                  : detail,
              );
              setRun(next);
              setDraft('');
              setProgress([]);
              refreshResearch();
            })
            .catch(() => void message.warning('后续任务状态加载失败，请刷新'));
        }
      },
      () => {
        // EventSource reconnects with Last-Event-ID. Snapshot is authoritative if a terminal event was missed.
        void agentApi
          .run(rid)
          .then(acceptRun)
          .catch(() => {});
      },
    );
    return () => {
      observing = false;
      unsubscribe();
    };
  }, [run?.id, run?.status, conversation?.id, refreshResearch, message]);

  const act = async (operation: () => Promise<void>) => {
    if (actionLock.current) return false;
    actionLock.current = true;
    setBusy(true);
    try {
      await operation();
      return true;
    } catch (error) {
      if (isAxiosError(error) && error.response?.status === 409) {
        sendAttempt.current = undefined;
        queueAttempt.current = undefined;
        startKey.current = generateUUID();
        if (conversation)
          void agentApi
            .conversation(conversation.id)
            .then(setConversation)
            .catch(() => {});
      }
      void message.error(error instanceof Error ? error.message : '操作失败，请刷新后重试');
      return false;
    } finally {
      actionLock.current = false;
      setBusy(false);
    }
  };

  const start = () =>
    act(async () => {
      if (!request.trim() || restoring) return;
      const signature = JSON.stringify([
        topicId,
        conversation?.id,
        request,
        active ? 'follow_up' : 'new',
      ]);
      if (sendAttempt.current?.signature !== signature)
        sendAttempt.current = {
          signature,
          conversation: conversation ? await agentApi.conversation(conversation.id) : undefined,
          key: sendAttempt.current ? generateUUID() : startKey.current,
        };
      const attempt = sendAttempt.current;
      if (active && conversation) {
        const current = attempt.conversation!;
        await agentApi.message(current, request, 'follow_up', attempt.key);
        await agentApi
          .conversation(current.id)
          .then(setConversation)
          .catch(() => void message.warning('消息已接收，队列状态稍后自动刷新。'));
        void message.success('已加入待发送队列');
      } else {
        const current = attempt.conversation;
        const continuing = /^继续[。！!\s]*$/.test(request.trim());
        const value = await agentApi.start(
          topicId,
          request,
          current,
          run && ['CANCELLED', 'FAILED'].includes(run.status) ? run.id : undefined,
          attempt.key,
          continuing,
          current?.profile || (!current ? 'agent' : undefined),
        );
        if (!topicId && value.topic_id) {
          setTopicId(value.topic_id);
          void topics.refetch();
        }
        setRun(value);
        setDraft('');
        setProgress([]);
        await agentApi
          .conversation(value.conversation_id)
          .then(setConversation)
          .catch(() => void message.warning('任务已开始，历史状态稍后自动刷新。'));
        setSelectedTask(value.conversation_id);
        if (owner) {
          sessionStorage.setItem('harness-conversation:' + owner, value.conversation_id);
          sessionStorage.removeItem('harness-topic:' + owner);
        }
        void conversationList.refetch();
      }
      setRequest('');
      startKey.current = generateUUID();
      sendAttempt.current = undefined;
    });

  const changeQueue = (change: QueueChange, restoreToComposer = false) =>
    act(async () => {
      if (!conversation) return;
      const signature = JSON.stringify([conversation.id, change]);
      if (queueAttempt.current?.signature !== signature)
        queueAttempt.current = {
          signature,
          conversation: await agentApi.conversation(conversation.id),
          key: generateUUID(),
        };
      const attempt = queueAttempt.current;
      const queuedItem = attempt.conversation.queued?.find((item) => item.id === change.message_id);
      if (restoreToComposer && !queuedItem)
        throw new Error('这条消息已开始处理或已移除，不能回退编辑。');
      await agentApi.queue(attempt.conversation, change, attempt.key);
      if (restoreToComposer && queuedItem) {
        // Do not overwrite any draft typed before the busy state reached the composer.
        setRequest((current) => (current ? queuedItem.text + '\n\n' + current : queuedItem.text));
        startKey.current = generateUUID();
      }
      queueAttempt.current = undefined;
      await agentApi
        .conversation(conversation.id)
        .then(setConversation)
        .catch(() => void message.warning('队列已更新，显示稍后自动刷新。'));
    });

  const clickCitation = (event: MouseEvent<HTMLElement>, evidence: Record<string, Candidate>) => {
    const anchor = (event.target as HTMLElement).closest('a');
    const href = anchor?.getAttribute('href');
    if (href?.startsWith('#research-evidence-')) {
      event.preventDefault();
      const item = evidence[href.slice('#research-evidence-'.length)];
      if (item) setSource(item.source);
    }
  };

  const selectTask = (id?: string) => {
    const legacyTopic = id?.startsWith('topic:') ? id.slice(6) : undefined;
    setSelectedTask(id);
    setTopicId(legacyTopic);
    if (owner) {
      sessionStorage.removeItem('harness-topic:' + owner);
      sessionStorage.removeItem('harness-conversation:' + owner);
      if (legacyTopic) sessionStorage.setItem('harness-topic:' + owner, legacyTopic);
      else if (id) sessionStorage.setItem('harness-conversation:' + owner, id);
    }
    setRestoring(false);
    setResourcesOpen(false);
    setReportOpen(false);
    setSelectedReportId(undefined);
    openedReportRun.current = undefined;
    setRequest('');
    startKey.current = generateUUID();
    setRun(undefined);
    setConversation(undefined);
    setDraft('');
    setProgress([]);
    sendAttempt.current = undefined;
    queueAttempt.current = undefined;
  };
  const verify = (card: EvidenceCard, name: string, status: 'EXTRACTION_VERIFIED' | 'REJECTED') =>
    act(async () => {
      await agentApi.verify(card, name, status);
      refreshResearch();
    });

  const fields = (cards.data || []).flatMap((card) =>
    card.fields.map((field, index) => ({ ...field, key: `${card.id}-${index}`, card })),
  );
  const comparisonFields = [
    ...new Set((cards.data || []).flatMap((card) => card.fields.map((field) => field.name))),
  ];
  const showMaterials = (tab: string) => {
    setResourcesTab(tab);
    setResourcesOpen(true);
  };
  const openReport = (id: string) => {
    setSelectedReportId(id);
    setReportOpen(true);
    setResourcesOpen(false);
  };
  const visibleDocuments = [
    ...new Map([
      ...(topic?.documents || []).map(
        (item) => [item.document_id + ':' + item.publication_id, item] as const,
      ),
      ...Object.values(run?.evidence || {})
        .filter((item) => item.source.kind === 'KB_PUBLICATION')
        .map(
          (item) =>
            [
              item.source.document_id + ':' + item.source.publication_id,
              {
                document_id: item.source.document_id,
                publication_id: item.source.publication_id!,
                title: item.source.title,
              },
            ] as const,
        ),
    ]).values(),
  ];
  const task = (
    <HarnessConversation
      topics={[
        ...(conversationList.data || [])
          .filter((item) => item.profile !== 'fast_qa')
          .map((item) => ({
            id: item.id,
            title:
              item.title ||
              (topics.data || []).find((t) => t.id === item.topic_id)?.title ||
              '历史任务',
            updatedAt: item.updated_at || '',
          })),
        ...(topics.data || [])
          .filter((item) => !(conversationList.data || []).some((c) => c.topic_id === item.id))
          .map((item) => ({
            id: 'topic:' + item.id,
            title: item.title,
            updatedAt: item.updated_at,
          })),
      ]}
      activeTopicId={selectedTask}
      onNewTask={() => selectTask(undefined)}
      onSelectTask={selectTask}
      scope={
        topic && topic.scope_mode !== 'AUTOMATIC' ? '仅指定文献' : '按权限使用知识库与只读业务工具'
      }
      run={run}
      conversation={conversation}
      draft={draft}
      request={request}
      busy={busy}
      ready={capability.data?.enabled === true && capability.data.available === true}
      restoring={restoring || Boolean(topicId && topicQuery.isFetching && !topic)}
      progress={progress}
      reports={reports.data}
      onReport={openReport}
      onRequest={(value) => {
        setRequest(value);
        startKey.current = generateUUID();
      }}
      onRestoreQueued={(item) => {
        if (!conversation || request.trim()) return Promise.resolve(false);
        return changeQueue({ action: 'delete', message_id: item.id }, true);
      }}
      onQueue={changeQueue}
      onSend={() => void start()}
      onMaterials={showMaterials}
      onCancel={() =>
        void act(async () => {
          if (run) {
            await agentApi.cancel(run.id);
            const current = await agentApi.conversation(run.conversation_id);
            setConversation(current);
            setRun(await agentApi.run(current.last_run || run.id));
          }
        })
      }
      onRetry={() =>
        void act(async () => {
          if (run) {
            await agentApi.resume(run.id, { retry: true });
            setRun(await agentApi.run(run.id));
          }
        })
      }
      onApproval={(decision) =>
        void act(async () => {
          if (run?.pending_approval) {
            await agentApi.resume(run.id, { approval_id: run.pending_approval.id, decision });
            setRun(await agentApi.run(run.id));
          }
        })
      }
      onCitation={(event, runId) => {
        const href = (event.target as HTMLElement).closest('a')?.getAttribute('href');
        if (!href?.startsWith('#research-evidence-')) return;
        event.preventDefault();
        const id = href.slice('#research-evidence-'.length);
        void act(async () => {
          const evidence =
            runId && runId !== run?.id
              ? (await agentApi.run(runId)).evidence
              : (await agentApi.run(run!.id)).evidence;
          if (!evidence[id]) throw new Error('该引用暂时不可用，请在资料区核对来源。');
          setSource(evidence[id].source);
        });
      }}
    />
  );

  return (
    <main className="research-workspace harness-workspace">
      {capability.isError && (
        <Alert
          type="error"
          message="工作区状态加载失败"
          action={<Button onClick={() => void capability.refetch()}>重试</Button>}
        />
      )}
      {capability.data && !capability.data.enabled && (
        <Alert
          type="info"
          message="研究工作区尚未启用"
          description="管理员启用后，可直接交代任务，由助手查找授权资料。"
        />
      )}
      {capability.data?.enabled && capability.data.available === false && (
        <Alert
          type="warning"
          message="执行服务暂不可用，仍可阅读已保存的研究资料"
          action={<Button onClick={() => void capability.refetch()}>重新检查</Button>}
        />
      )}
      <div className={`research-session-layout${reportOpen ? ' has-report' : ''}`}>
        <div className="research-session-chat">{task}</div>
        {reportOpen && (
          <ResearchReportReader
            report={(reports.data || []).find((item) => item.id === selectedReportId)}
            reports={reports.data || []}
            loading={reports.isFetching}
            error={reports.isError}
            onRetry={() => void reports.refetch()}
            onClose={() => setReportOpen(false)}
            onSelect={setSelectedReportId}
            onSource={setSource}
            onCitation={clickCitation}
          />
        )}
      </div>
      <Drawer
        title="资料与成果"
        className="research-materials"
        open={resourcesOpen}
        onClose={() => setResourcesOpen(false)}
        width="min(1060px, 96vw)"
      >
        {!topic ? (
          <>
            <ResearchQualityPanel
              matrix={run?.research_matrix}
              review={matrixReview}
              onQuote={(quote) => setSource(quote.source)}
            />
            <Typography.Paragraph>
              本次任务按需获取授权资料；保存文献证据和研究报告时才建立研究主题。
            </Typography.Paragraph>
            <List
              header="本次任务来源"
              dataSource={Object.values(run?.evidence || {})}
              locale={{ emptyText: '尚未读取来源' }}
              renderItem={(item) => (
                <List.Item
                  actions={[
                    <Button
                      key="read"
                      onClick={() => {
                        if (run)
                          void act(async () => {
                            const latest = await agentApi.run(run.id);
                            const evidence = latest.evidence[item.evidence_id];
                            if (evidence) setSource(evidence.source);
                          });
                      }}
                    >
                      查看来源
                    </Button>,
                  ]}
                >
                  <List.Item.Meta title={item.source.title} description={item.source.kind} />
                </List.Item>
              )}
            />
            <List
              header="已保存文件"
              dataSource={(run?.registered_outputs || []).filter(
                (item) => item.kind === 'artifact',
              )}
              locale={{ emptyText: '尚未保存文件' }}
              renderItem={(file) => (
                <List.Item
                  actions={[
                    <Button key="download" href={agentApi.artifactUrl(file.id)}>
                      下载
                    </Button>,
                  ]}
                >
                  {file.title}
                </List.Item>
              )}
            />
          </>
        ) : (
          <>
            <div className="research-topic-heading">
              <Typography.Title level={3}>{topic.title}</Typography.Title>
              <Typography.Paragraph>{topic.goal.slice(0, 240)}</Typography.Paragraph>
            </div>
            {Boolean(artifacts.data?.length) && (
              <List
                size="small"
                header="已登记分析文件 · AI 生成，使用前请核对"
                dataSource={artifacts.data}
                renderItem={(file) => (
                  <List.Item
                    actions={[
                      <Button key="download" href={agentApi.artifactUrl(file.id)}>
                        下载
                      </Button>,
                    ]}
                  >
                    <List.Item.Meta
                      title={file.name}
                      description={`${file.bytes.toLocaleString()} 字节`}
                    />
                  </List.Item>
                )}
              />
            )}
            <Tabs
              activeKey={resourcesTab}
              onChange={setResourcesTab}
              items={[
                {
                  key: 'matrix',
                  label: `证据矩阵 (${Object.keys(run?.research_matrix?.questions || {}).length})`,
                  children: (
                    <ResearchQualityPanel
                      matrix={run?.research_matrix}
                      review={matrixReview}
                      onQuote={(quote) => setSource(quote.source)}
                    />
                  ),
                },
                {
                  key: 'documents',
                  label: `文献与阅读 (${visibleDocuments.length})`,
                  children: (
                    <section>
                      <Space wrap>
                        <Typography.Text>
                          {topic.scope_mode === 'AUTOMATIC' ? '自动发现授权文献' : '仅使用指定文献'}
                        </Typography.Text>
                        {topic.scope_mode !== 'AUTOMATIC' && (
                          <Button
                            disabled={Boolean(run && observedStatuses.includes(run.status))}
                            onClick={() =>
                              void act(async () => {
                                await agentApi.updateTopic(topic, [], 'AUTOMATIC');
                                await topicQuery.refetch();
                                await topics.refetch();
                              })
                            }
                          >
                            恢复自动找资料
                          </Button>
                        )}
                        <Button
                          disabled={Boolean(run && observedStatuses.includes(run.status))}
                          onClick={() => {
                            setSelectedDocs(topic.documents.map((item) => item.document_id));
                            setDocumentsOpen(true);
                          }}
                        >
                          选择知识库文献
                        </Button>
                        <Link to="/knowledge/library">上传与解析文献</Link>
                      </Space>
                      <List
                        dataSource={visibleDocuments}
                        locale={{
                          emptyText:
                            topic.scope_mode === 'AUTOMATIC'
                              ? '开始任务后，助手找到并阅读的授权资料会出现在这里'
                              : '选择已发布并允许 AI 使用的文献',
                        }}
                        renderItem={(item) => (
                          <List.Item
                            actions={[
                              <Button
                                key="read"
                                type="link"
                                onClick={() =>
                                  setSource({
                                    kind: 'KB_PUBLICATION',
                                    document_id: item.document_id,
                                    publication_id: item.publication_id,
                                    version_id: '',
                                    source_node_key: '',
                                    content_sha256: '',
                                    title: item.title || '文献',
                                    anchor: {},
                                  })
                                }
                              >
                                阅读原文
                              </Button>,
                            ]}
                          >
                            <List.Item.Meta
                              title={item.title || item.document_id}
                              description={`固定发布版本：${item.publication_id}`}
                            />
                          </List.Item>
                        )}
                      />
                      <Typography.Title level={4}>研究笔记</Typography.Title>
                      <Input.TextArea
                        aria-label="研究笔记"
                        value={note}
                        onChange={(event) => setNote(event.target.value)}
                      />
                      <Button
                        disabled={!note.trim()}
                        onClick={() =>
                          void act(async () => {
                            await agentApi.annotate(topic.id, note);
                            setNote('');
                            refreshResearch();
                          })
                        }
                      >
                        保存笔记
                      </Button>
                      <List
                        dataSource={annotations.data || []}
                        renderItem={(item) => (
                          <List.Item
                            actions={
                              item.source
                                ? [
                                    <Button
                                      key="source"
                                      type="link"
                                      onClick={() => setSource(item.source)}
                                    >
                                      定位批注原文
                                    </Button>,
                                  ]
                                : []
                            }
                          >
                            {item.text}
                          </List.Item>
                        )}
                      />
                    </section>
                  ),
                },
                {
                  key: 'evidence',
                  label: `证据与比较 (${fields.length})`,
                  children: (
                    <section>
                      <div className="research-section-heading">
                        <Typography.Text>
                          核对表示抽取与原文一致；独立复现需另行判断。
                        </Typography.Text>
                        <Button href={agentApi.comparisonUrl(topic.id)} disabled={!fields.length}>
                          导出比较表
                        </Button>
                      </div>
                      <Typography.Title level={4}>按研究实例比较</Typography.Title>
                      <Typography.Paragraph type="secondary">
                        每行一个具体实例；指标口径、规模和条件不一致时不能直接排名。
                      </Typography.Paragraph>
                      <Table<EvidenceCard>
                        rowKey="id"
                        dataSource={cards.data || []}
                        pagination={false}
                        scroll={{ x: 340 + comparisonFields.length * 220 }}
                        columns={[
                          { title: '研究实例', dataIndex: 'instance', width: 180, fixed: 'left' },
                          { title: '目标', dataIndex: 'target', width: 160 },
                          ...comparisonFields.map((name) => ({
                            title: name,
                            key: name,
                            width: 220,
                            render: (_value: unknown, card: EvidenceCard) => {
                              const field = card.fields.find((item) => item.name === name);
                              if (!field)
                                return <Typography.Text type="secondary">未提取</Typography.Text>;
                              return (
                                <Space direction="vertical" size={4}>
                                  <Typography.Text>
                                    {field.value ?? field.original ?? '未报告'} {field.unit || ''}
                                  </Typography.Text>
                                  <Typography.Text type="secondary">
                                    {field.status === 'EXTRACTION_VERIFIED'
                                      ? '抽取已核对'
                                      : field.status === 'REJECTED'
                                        ? '已排除，不参与判断'
                                        : '待核对'}
                                  </Typography.Text>
                                  <Typography.Text type="secondary">
                                    {Object.entries(field.qualifiers || {})
                                      .map(([key, value]) => `${key}：${value}`)
                                      .join('；')}
                                  </Typography.Text>
                                  <Button
                                    type="link"
                                    size="small"
                                    onClick={() => setSource(field.source)}
                                  >
                                    定位原文
                                  </Button>
                                </Space>
                              );
                            },
                          })),
                        ]}
                      />
                      <Typography.Title level={4}>逐字段核对</Typography.Title>
                      <Table
                        rowKey="key"
                        dataSource={fields}
                        pagination={false}
                        scroll={{ x: 1000 }}
                        columns={[
                          { title: '研究实例', dataIndex: ['card', 'instance'], width: 160 },
                          { title: '目标', dataIndex: ['card', 'target'], width: 140 },
                          { title: '字段', dataIndex: 'name', width: 110 },
                          { title: '原文值', dataIndex: 'original', width: 140 },
                          {
                            title: '标准化值',
                            render: (_, field) => `${field.value || '未报告'} ${field.unit || ''}`,
                            width: 120,
                          },
                          {
                            title: '限定条件',
                            render: (_, field) =>
                              Object.entries(field.qualifiers || {})
                                .map(([key, value]) => `${key}：${value}`)
                                .join('；') || '—',
                            width: 180,
                          },
                          {
                            title: '核对状态',
                            render: (_, field) => (
                              <Space direction="vertical">
                                <Tag>
                                  {field.status === 'EXTRACTION_VERIFIED'
                                    ? '抽取已核对'
                                    : field.status === 'REJECTED'
                                      ? '已排除'
                                      : '待核对'}
                                </Tag>
                                <Button size="small" onClick={() => setSource(field.source)}>
                                  定位原文
                                </Button>
                                <Button
                                  size="small"
                                  loading={busy}
                                  onClick={() =>
                                    void verify(field.card, field.name, 'EXTRACTION_VERIFIED')
                                  }
                                >
                                  与原文一致
                                </Button>
                                <Button
                                  size="small"
                                  onClick={() => void verify(field.card, field.name, 'REJECTED')}
                                >
                                  排除此字段
                                </Button>
                              </Space>
                            ),
                            width: 140,
                          },
                        ]}
                      />
                    </section>
                  ),
                },
                {
                  key: 'reports',
                  label: `报告 (${reports.data?.length || 0})`,
                  children: (
                    <List
                      dataSource={reports.data || []}
                      locale={{ emptyText: '研究任务生成并登记报告后，将保存在这里' }}
                      renderItem={(report) => (
                        <List.Item>
                          <List.Item.Meta
                            title={report.title}
                            description={`报告草稿 · 保存修订 ${report.revision} · ${reportAssessment(report).label}`}
                          />
                          <Button onClick={() => openReport(report.id)}>打开报告</Button>
                        </List.Item>
                      )}
                    />
                  ),
                },
              ]}
            />
          </>
        )}
      </Drawer>
      <Modal
        title="选择文献"
        open={documentsOpen}
        width={760}
        confirmLoading={busy}
        onCancel={() => setDocumentsOpen(false)}
        onOk={() =>
          void act(async () => {
            if (!topic) return;
            const found = new Map([
              ...selectedDocumentCache.current,
              ...(documents.data?.items || []).map((item) => [item.id, item] as const),
            ]);
            const selected = selectedDocs.map((id) => {
              const existing = topic.documents.find((item) => item.document_id === id);
              if (existing) return existing;
              const document = found.get(id);
              if (!document?.currentPublicationId)
                throw new Error('所选文献状态已变化，请重新选择。');
              return {
                document_id: document.id,
                publication_id: document.currentPublicationId,
                title: document.title,
              };
            });
            await agentApi.updateTopic(topic, selected, 'SELECTED');
            setDocumentsOpen(false);
            await topicQuery.refetch();
            await topics.refetch();
          })
        }
      >
        <Input.Search
          aria-label="查找文献"
          placeholder="按标题或编号查找"
          onSearch={setKeyword}
          enterButton
        />
        <Table<KnowledgeDocument>
          rowKey="id"
          size="small"
          loading={documents.isFetching}
          dataSource={documents.data?.items || []}
          pagination={false}
          rowSelection={{
            selectedRowKeys: selectedDocs,
            onChange: (keys, rows) => {
              for (const row of rows) if (row) selectedDocumentCache.current.set(row.id, row);
              setSelectedDocs(keys.map(String));
            },
            preserveSelectedRowKeys: true,
            getCheckboxProps: (item) => ({
              disabled: item.aiStatus !== 'APPROVED' || !item.currentPublicationId,
            }),
          }}
          columns={[
            { title: '文献', dataIndex: 'title' },
            { title: 'AI 使用', dataIndex: 'aiStatus', width: 120 },
            { title: '发布版本', dataIndex: 'currentPublicationNo', width: 90 },
          ]}
        />
      </Modal>
      <SourceReader
        source={source}
        snapshot={
          [
            ...Object.values(run?.evidence || {}),
            ...(reports.data || []).flatMap((report) => Object.values(report.evidence)),
          ].find(
            (item) =>
              item.source.kind === source?.kind &&
              item.source.document_id === source?.document_id &&
              item.source.version_id === source?.version_id &&
              item.source.publication_id === source?.publication_id &&
              item.source.content_sha256 === source?.content_sha256 &&
              item.source.source_node_key === source?.source_node_key,
          )?.text
        }
        close={() => setSource(undefined)}
        onAnnotate={async (text, reference) => {
          if (!topicId) throw new Error('请先选择研究主题');
          await agentApi.annotate(topicId, text, reference);
          refreshResearch();
        }}
      />
    </main>
  );
}

function SourceReader({
  source,
  snapshot,
  close,
  onAnnotate,
}: {
  source?: SourceRef;
  snapshot?: string;
  close: () => void;
  onAnnotate: (text: string, source: SourceRef) => Promise<void>;
}) {
  const { message } = App.useApp();
  const [annotation, setAnnotation] = useState('');
  const [saving, setSaving] = useState(false);
  const [published, setPublished] = useState<PublishedContent>();
  const [error, setError] = useState<string>();
  useEffect(() => {
    if (!source?.publication_id) return;
    setAnnotation('');
    let current = true;
    setPublished(undefined);
    setError(undefined);
    void knowledgeApi
      .publishedContent(source.document_id, source.publication_id)
      .then((value) => {
        if (current) setPublished(value);
      })
      .catch(() => {
        if (current) setError('原文加载失败或访问权限已变化');
      });
    return () => {
      current = false;
    };
  }, [source]);
  const load = useCallback(
    () => knowledgeApi.contentBlob(source!.document_id, published!.publication.versionId),
    [source, published],
  );
  const anchorKeys = source?.anchor.sourceNodeKeys;
  const selectedKey =
    Array.isArray(anchorKeys) && typeof anchorKeys[0] === 'string'
      ? anchorKeys[0]
      : source?.source_node_key;
  return (
    <Drawer
      title={source?.title || '原文与证据'}
      width="90vw"
      open={Boolean(source)}
      onClose={close}
      destroyOnClose
    >
      {source && source.kind !== 'KB_PUBLICATION' ? (
        <>
          <Alert
            type="info"
            message={
              source.kind === 'BUSINESS_RECORD'
                ? '这是读取时的业务记录快照，不是实时状态。'
                : '该来源不使用知识库 PDF 阅读器。'
            }
          />
          <Typography.Paragraph>
            版本：{source.version_id} · 来源位置：{source.source_node_key}
          </Typography.Paragraph>
          <Typography.Paragraph copyable>{JSON.stringify(source.anchor)}</Typography.Paragraph>
          {snapshot && (
            <Typography.Paragraph style={{ whiteSpace: 'pre-wrap' }}>
              {snapshot}
            </Typography.Paragraph>
          )}
          {source.kind === 'WEB_RESULT' && source.url && /^https?:\/\//.test(source.url) && (
            <a href={source.url} target="_blank" rel="noreferrer">
              打开原始网页
            </a>
          )}
        </>
      ) : error ? (
        <Alert type="error" message={error} />
      ) : !source || !published ? (
        <Spin />
      ) : (
        <>
          <Typography.Paragraph type="secondary">
            发布 #{published.publication.publicationNo} · 文件版本固定；文件页序与印刷页码可能不同。
          </Typography.Paragraph>
          {source?.source_node_key && source.content_sha256 && (
            <Space.Compact style={{ width: '100%', marginBottom: 16 }}>
              <Input
                aria-label="原文位置批注"
                placeholder="为当前来源位置添加批注"
                value={annotation}
                onChange={(event) => setAnnotation(event.target.value)}
                maxLength={6000}
              />
              <Button
                loading={saving}
                disabled={!annotation.trim()}
                onClick={() => {
                  setSaving(true);
                  void onAnnotate(annotation, source)
                    .then(() => {
                      setAnnotation('');
                      void message.success('原文批注已保存');
                    })
                    .catch(() => void message.error('批注保存失败，原文版本或权限可能已变化'))
                    .finally(() => setSaving(false));
                }}
              >
                保存批注
              </Button>
            </Space.Compact>
          )}
          <div className="research-reader">
            <OriginalDocumentViewer
              fileName={published.originalName}
              contentType={published.contentType}
              sourceNodes={published.sourceNodes}
              selectedSourceNodeKey={selectedKey}
              load={load}
              contentUrl={knowledgeApi.contentUrl(
                source.document_id,
                published.publication.versionId,
              )}
            />
            <StructuredDocumentRenderer
              value={published.confirmedDocument}
              selectedSourceNodeKey={selectedKey}
            />
          </div>
        </>
      )}
    </Drawer>
  );
}
