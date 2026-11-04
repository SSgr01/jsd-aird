import {
  groupEvidence,
  researchEvidence,
  sourceLabel,
  type EvidenceView,
} from '@/services/evidence/adapters';
import { applyTextUpdate } from '@/services/evidence/identity';
import { EvidenceReader } from '@/components/ai-conversation-workspace/EvidenceDetails';
import { App, Alert, Button, Drawer, Input, List, Space, Table, Tabs, Tag, Typography } from 'antd';
import { useCallback, useEffect, useRef, useState, type MouseEvent } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { isAxiosError } from 'axios';
import { Link } from 'react-router-dom';
import {
  agentApi,
  type AgentEvent,
  type AgentRun,
  type Candidate,
  type Conversation,
  type EvidenceCard,
  type SourceRef,
  type SourceQuote,
  type PrivateSource,
  type QueueChange,
} from '@/services/agents/agent-api';
import { knowledgeApi, type KnowledgeDocument } from '@/services/knowledge/knowledge-api';
import { useAuthStore } from '@/stores/auth-store';
import { HarnessConversation } from './HarnessConversation';
import { ResearchQualityPanel } from './ResearchQualityPanel';
import { ResearchReportReader } from './ResearchReportReader';
import { ConversationSources } from './ConversationSources';
import { deliveryProblems, reportAssessment } from './research-presentation';
import { generateUUID } from '@/utils/uuid';
import { triggerNativeDownload } from '@/services/files';
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
  const [draftId, setDraftId] = useState<string>();
  const [draftAfterMessageSeq, setDraftAfterMessageSeq] = useState(0);
  const [progress, setProgress] = useState<Array<{ key: number; text: string }>>([]);
  const [sourceView, setSourceView] = useState<EvidenceView>();
  const uploadInput = useRef<HTMLInputElement>(null);
  const workspace = useRef<HTMLDivElement>(null);
  const [wide, setWide] = useState(false);
  useEffect(() => {
    const element = workspace.current;
    if (!element) return;
    const update = () => setWide(element.getBoundingClientRect().width >= 1200);
    update();
    const observer = new ResizeObserver(update);
    observer.observe(element);
    return () => observer.disconnect();
  }, []);
  const setSource = (value?: SourceRef, quote?: SourceQuote, excerpt?: string) => {
    const candidate = Object.values(run?.evidence || {}).find(
      (item) =>
        item.source.kind === value?.kind &&
        item.source.document_id === value?.document_id &&
        item.source.version_id === value?.version_id &&
        item.source.publication_id === value?.publication_id &&
        item.source.content_sha256 === value?.content_sha256 &&
        item.source.source_node_key === value?.source_node_key,
    );
    setSourceView(value ? researchEvidence(value, excerpt || candidate?.text, quote) : undefined);
    if (value) {
      setResourcesOpen(false);
      setReportOpen(false);
    }
  };
  const [resourcesOpen, setResourcesOpen] = useState(false);
  const [resourcesTab, setResourcesTab] = useState('references');
  const [reportOpen, setReportOpen] = useState(false);
  const [selectedReportId, setSelectedReportId] = useState<string>();
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
  const sourceAttempt = useRef<{ signature: string; key: string }>();
  const [note, setNote] = useState('');
  const [uploadingName, setUploadingName] = useState<string>();
  const [uploadFailed, setUploadFailed] = useState(false);
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
    sourceAttempt.current = undefined;
    setSource(undefined);
    setResourcesOpen(false);
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
  const privateSources = useQuery({
    queryKey: ['harness-private-sources', owner, conversation?.id],
    queryFn: () => agentApi.privateSources(conversation!.id),
    enabled: Boolean(conversation?.id),
    refetchInterval: (query) =>
      query.state.data?.some((item) => item.status === 'PARSING') ? 2000 : false,
  });

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
          const target = next.active_run || next.last_run;
          if (current && target) {
            const snapshot = await agentApi.run(target);
            if (!current) return;
            setRun((previous) => {
              if (previous?.id !== run?.id) return previous;
              if (previous?.id === snapshot.id) {
                if ((previous.revision ?? 0) > (snapshot.revision ?? 0)) return previous;
                return {
                  ...snapshot,
                  assistant_messages: [
                    ...new Map(
                      [
                        ...(previous.assistant_messages || []),
                        ...(snapshot.assistant_messages || []),
                      ].map((item) => [item.id, item]),
                    ).values(),
                  ].slice(-20),
                };
              }
              return snapshot;
            });
            if (target !== run?.id) {
              setDraft('');
              setProgress([]);
            }
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
    if (conversation?.id === selectedTask) {
      setRestoring(false);
      return;
    }
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
    const completedIds = new Set((run.assistant_messages || []).map((item) => item.id));
    const acceptRun = (value: AgentRun) => {
      if (observing) {
        for (const item of value.assistant_messages || []) completedIds.add(item.id);
        setRun((previous) => {
          // A slow in-flight snapshot must not undo a stop or overwrite its successor.
          if (previous && previous.id !== value.id) return previous;
          if (
            previous &&
            !observedStatuses.includes(previous.status) &&
            observedStatuses.includes(value.status)
          )
            return previous;
          if (previous && (previous.revision ?? 0) > (value.revision ?? 0)) return previous;
          return previous
            ? {
                ...value,
                assistant_messages: [
                  ...new Map(
                    [
                      ...(previous.assistant_messages || []),
                      ...(value.assistant_messages || []),
                    ].map((item) => [item.id, item]),
                  ).values(),
                ].slice(-20),
              }
            : value;
        });
      }
    };
    const unsubscribe = agentApi.subscribe(
      rid,
      (event: AgentEvent) => {
        if (!observing) return;
        const messageId = typeof event.payload.id === 'string' ? event.payload.id : undefined;
        if (event.type === 'message.started' && (!messageId || !completedIds.has(messageId))) {
          setDraftId(messageId || 'event-' + event.seq);
          setDraftAfterMessageSeq(Number(event.payload.after_message_seq) || 0);
          setDraft('');
        }
        if (event.type === 'message.applied')
          setRun((value) =>
            value?.id === rid
              ? {
                  ...value,
                  messages: (value.messages || []).map((item) =>
                    item.id === event.payload.message_id ? { ...item, status: 'DELIVERED' } : item,
                  ),
                }
              : value,
          );
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
          if (messageId && completedIds.has(messageId)) return;
          if (messageId) setDraftId(messageId);
          const delta = event.payload.delta;
          setDraft((value) => applyTextUpdate(value, delta));
        }
        if (event.type === 'message.completed' && typeof event.payload.text === 'string') {
          const text = event.payload.text;
          const id = typeof event.payload.id === 'string' ? event.payload.id : 'event-' + event.seq;
          completedIds.add(id);
          setRun((value) =>
            value?.id === rid &&
            text.trim() &&
            !(value.assistant_messages || []).some((item) => item.id === id)
              ? {
                  ...value,
                  assistant_messages: [
                    ...(value.assistant_messages || []),
                    {
                      id,
                      text,
                      after_message_seq: Number(event.payload.after_message_seq) || 0,
                    },
                  ],
                }
              : value,
          );
          setDraft('');
        }
        if (event.type === 'task.plan')
          setRun((value) =>
            value?.id === rid
              ? { ...value, plan: event.payload as unknown as AgentRun['plan'] }
              : value,
          );
        if (event.type === 'task.review')
          setRun((value) =>
            value?.id === rid
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
              setRun((previous) => (previous?.id !== rid ? previous : next));
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
      let failureMessage = error instanceof Error ? error.message : '操作失败，请刷新后重试';
      if (isAxiosError<{ detail?: unknown }>(error) && error.response?.status === 409) {
        sendAttempt.current = undefined;
        queueAttempt.current = undefined;
        startKey.current = generateUUID();
        if (conversation)
          void agentApi
            .conversation(conversation.id)
            .then(setConversation)
            .catch(() => {});
        const detail = error.response.data?.detail;
        if (detail === 'MESSAGE_ALREADY_DISPATCHED_OR_REMOVED')
          failureMessage = '这条消息已开始处理或已移除，正在刷新队列。';
        else if (detail === 'Conversation revision changed')
          failureMessage = '消息状态刚刚有变化，正在刷新，请稍后再操作。';
        else if (detail === 'RUN_NOT_ACCEPTING_STEERING')
          failureMessage = '当前任务已结束，这条要求仍保留在待发送列表。';
      }
      void message.error(failureMessage);
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
      if (item) setSource(item.source, undefined, item.text);
    }
  };

  const selectTask = (id?: string) => {
    sourceAttempt.current = undefined;
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
    setSourceView(undefined);
    setDocumentsOpen(false);
    setReportOpen(false);
    setSelectedReportId(undefined);
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
    setResourcesTab(['reports', 'files', 'checks'].includes(tab) ? 'files' : 'references');
    setSourceView(undefined);
    setReportOpen(false);
    setResourcesOpen(true);
  };
  const ensureSourceConversation = async () => {
    if (conversation) return conversation;
    const created = await agentApi.createConversation();
    setConversation(created);
    setSelectedTask(created.id);
    setTopicId(undefined);
    if (owner) sessionStorage.setItem('harness-conversation:' + owner, created.id);
    return created;
  };
  const attachSource = async (file?: File, pmcid?: string) => {
    setUploadingName(file?.name || pmcid);
    setUploadFailed(false);
    const success = await act(async () => {
      if (file && file.size > 10 * 1024 * 1024) throw new Error('单份私有资料不能超过 10 MiB。');
      const signature = file
        ? JSON.stringify([file.name, file.size, file.lastModified])
        : 'oa:' + pmcid;
      if (sourceAttempt.current?.signature !== signature)
        sourceAttempt.current = { signature, key: generateUUID() };
      const current = await ensureSourceConversation();
      if (file) {
        const bytes = new Uint8Array(await file.arrayBuffer());
        let binary = '';
        for (let offset = 0; offset < bytes.length; offset += 8192)
          binary += String.fromCharCode(...bytes.subarray(offset, offset + 8192));
        await agentApi.uploadPrivateSource(
          current.id,
          {
            name: file.name,
            content_type: file.type || 'application/octet-stream',
            base64: btoa(binary),
          },
          sourceAttempt.current.key,
        );
      } else if (pmcid)
        await agentApi.acquireOpenAccess(current.id, pmcid, sourceAttempt.current.key);
      sourceAttempt.current = undefined;
      setConversation(await agentApi.conversation(current.id));
      await queries.invalidateQueries({ queryKey: ['harness-private-sources', owner, current.id] });
      void conversationList.refetch();
      void message.success('资料已接收，解析就绪后可开始研究。');
    });
    setUploadingName(undefined);
    setUploadFailed(!success);
  };
  const openReport = (id: string) => {
    setSourceView(undefined);
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
        topic && topic.scope_mode !== 'AUTOMATIC'
          ? `已选 ${topic.documents.length} 篇文献`
          : undefined
      }
      run={run}
      conversation={conversation}
      draft={draft}
      draftAfterMessageSeq={draftAfterMessageSeq}
      draftId={draftId}
      request={request}
      busy={busy}
      ready={
        capability.data?.enabled === true &&
        capability.data.available === true &&
        !privateSources.data?.some((item) => item.status !== 'READY') &&
        !(Boolean(conversation?.private_source_ids?.length) && !privateSources.data)
      }
      readinessMessage={
        privateSources.data?.some((item) => item.status !== 'READY')
          ? '文件仍在解析或解析失败，请在“资料与文件”中查看。'
          : undefined
      }
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
      onUpload={() => uploadInput.current?.click()}
      onChoose={() => {
        setSelectedDocs(topic?.documents.map((item) => item.document_id) || []);
        setDocumentsOpen(true);
        showMaterials('references');
      }}
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
          setSource(evidence[id].source, undefined, evidence[id].text);
        });
      }}
    />
  );

  const readPrivate = (file: PrivateSource) => {
    const passages = Object.values(run?.evidence || {}).filter(
      (item) => item.source.document_id === file.id && item.source.version_id === file.version_id,
    );
    const candidate = passages[0];
    setSourceView({
      ...(candidate ? researchEvidence(candidate.source, candidate.text) : { title: file.name }),
      passages: passages.map((item) => researchEvidence(item.source, item.text)),
      original: {
        fileName: file.name,
        contentType: file.content_type,
        downloadUrl: agentApi.privateSourceUrl(file.id),
        load: async () => {
          const response = await fetch(agentApi.privateSourceUrl(file.id), {
            credentials: 'include',
          });
          if (!response.ok) throw new Error('文件暂不可用');
          return response.blob();
        },
      },
    });
    setResourcesOpen(false);
    setReportOpen(false);
  };
  const references = groupEvidence(Object.values(run?.evidence || {})).filter(
    (group) =>
      !(privateSources.data || []).some(
        (file) =>
          file.id === group.source.document_id && file.version_id === group.source.version_id,
      ),
  );
  const previewReference = (group: (typeof references)[number]) => {
    setSourceView({
      ...researchEvidence(group.source, group.passages[0]?.text),
      passages: group.passages.map((item) => researchEvidence(item.source, item.text)),
    });
    setResourcesOpen(false);
  };
  const panelOpen = resourcesOpen || reportOpen || Boolean(sourceView);
  const closePanel = () => {
    setResourcesOpen(false);
    setReportOpen(false);
    setSourceView(undefined);
  };
  const saveSelection = () =>
    void act(async () => {
      const found = new Map([
        ...selectedDocumentCache.current,
        ...(documents.data?.items || []).map((item) => [item.id, item] as const),
      ]);
      const selected = selectedDocs.map((id) => {
        const existing = topic?.documents.find((item) => item.document_id === id);
        if (existing) return existing;
        const document = found.get(id);
        if (!document?.currentPublicationId) throw new Error('所选文献状态已变化，请重新选择。');
        return {
          document_id: document.id,
          publication_id: document.currentPublicationId,
          title: document.title,
        };
      });
      const currentTopic =
        topic || (await agentApi.createTopic('文献研究', request.trim() || '研究所选文献'));
      await agentApi.updateTopic(currentTopic, selected, 'SELECTED');
      setTopicId(currentTopic.id);
      setDocumentsOpen(false);
      if (!conversation) {
        setSelectedTask('topic:' + currentTopic.id);
        if (owner) sessionStorage.setItem('harness-topic:' + owner, currentTopic.id);
      }
      await topics.refetch();
      void queries.invalidateQueries({ queryKey: ['research-topic', owner, currentTopic.id] });
    });
  const materials = (
    <section className="research-materials-panel" aria-label="资料与文件">
      <header className="conversation-reader-toolbar">
        <strong>资料与文件</strong>
        <Button type="text" onClick={closePanel}>
          返回对话
        </Button>
      </header>
      <div className="research-materials-content">
        <Tabs
          activeKey={resourcesTab}
          onChange={setResourcesTab}
          items={[
            {
              key: 'references',
              label: '参考资料',
              children: documentsOpen ? (
                <section>
                  <div className="research-section-heading">
                    <h3>选择文献</h3>
                    <Button type="text" onClick={() => setDocumentsOpen(false)}>
                      返回资料
                    </Button>
                  </div>
                  <Input.Search
                    aria-label="查找文献"
                    placeholder="搜索文献标题"
                    onSearch={setKeyword}
                    enterButton
                  />
                  {documents.isError && (
                    <Alert
                      type="error"
                      message="文献加载失败"
                      action={<Button onClick={() => void documents.refetch()}>重试</Button>}
                    />
                  )}
                  <Table<KnowledgeDocument>
                    rowKey="id"
                    size="small"
                    loading={documents.isFetching}
                    dataSource={documents.data?.items || []}
                    pagination={false}
                    rowSelection={{
                      selectedRowKeys: selectedDocs,
                      preserveSelectedRowKeys: true,
                      onChange: (keys, rows) => {
                        for (const row of rows)
                          if (row) selectedDocumentCache.current.set(row.id, row);
                        setSelectedDocs(keys.map(String));
                      },
                      getCheckboxProps: (item) => ({
                        disabled: item.aiStatus !== 'APPROVED' || !item.currentPublicationId,
                      }),
                    }}
                    columns={[
                      { title: '文献', dataIndex: 'title' },
                      { title: '版本', dataIndex: 'currentPublicationNo', width: 60 },
                    ]}
                  />
                  <div className="research-selection-actions">
                    <span>已选 {selectedDocs.length} 篇</span>
                    <Button
                      type="primary"
                      disabled={!selectedDocs.length}
                      loading={busy}
                      onClick={saveSelection}
                    >
                      使用所选资料
                    </Button>
                  </div>
                </section>
              ) : (
                <>
                  <Space wrap>
                    <Button
                      disabled={busy || active}
                      onClick={() => {
                        setSelectedDocs(topic?.documents.map((item) => item.document_id) || []);
                        setDocumentsOpen(true);
                      }}
                    >
                      选择知识库资料
                    </Button>
                    {topic?.scope_mode === 'SELECTED' && (
                      <Button
                        disabled={busy || active}
                        onClick={() =>
                          void act(async () => {
                            await agentApi.updateTopic(topic, [], 'AUTOMATIC');
                            await topicQuery.refetch();
                          })
                        }
                      >
                        使用全部资料
                      </Button>
                    )}
                    <Link to="/knowledge/library">管理知识库</Link>
                  </Space>
                  {privateSources.isError && (
                    <Alert
                      type="error"
                      message="上传文件列表加载失败"
                      action={<Button onClick={() => void privateSources.refetch()}>重试</Button>}
                    />
                  )}
                  {uploadingName && <p role="status">正在上传 {uploadingName}…</p>}
                  {uploadFailed && (
                    <Alert type="error" message="文件未能上传，请重新选择文件再试。" />
                  )}
                  <ConversationSources
                    sources={privateSources.data || []}
                    loading={privateSources.isFetching && !privateSources.data}
                    failed={privateSources.isError}
                    busy={busy}
                    active={Boolean(run && observedStatuses.includes(run.status))}
                    onUpload={(file) => attachSource(file)}
                    onAcquire={(pmcid) => attachSource(undefined, pmcid)}
                    onRead={readPrivate}
                    onDetach={async (id) => {
                      if (conversation)
                        await act(async () => {
                          await agentApi.detachPrivateSource(conversation.id, id);
                          setConversation(await agentApi.conversation(conversation.id));
                          await privateSources.refetch();
                        });
                    }}
                  />
                  {references.length > 0 && (
                    <List
                      dataSource={references}
                      locale={{
                        emptyText:
                          references.length ||
                          privateSources.data?.length ||
                          visibleDocuments.length
                            ? null
                            : '助手阅读的资料会显示在这里',
                      }}
                      renderItem={(group) => (
                        <List.Item
                          actions={[
                            <Button
                              key="preview"
                              type="link"
                              onClick={() => previewReference(group)}
                            >
                              预览
                            </Button>,
                            ...(['RUN_UPLOAD', 'EXTERNAL_FULLTEXT'].includes(group.source.kind) ||
                            (group.source.kind === 'KB_PUBLICATION' && group.source.version_id)
                              ? [
                                  <Button
                                    key="download"
                                    type="link"
                                    href={
                                      group.source.kind === 'KB_PUBLICATION'
                                        ? knowledgeApi.contentUrl(
                                            group.source.document_id,
                                            group.source.version_id,
                                          )
                                        : agentApi.privateSourceUrl(group.source.document_id)
                                    }
                                  >
                                    下载
                                  </Button>,
                                ]
                              : []),
                          ]}
                        >
                          <List.Item.Meta
                            title={
                              <button
                                className="research-file-name"
                                onClick={() => previewReference(group)}
                              >
                                {group.source.title}
                              </button>
                            }
                            description={sourceLabel(group.source)}
                          />
                        </List.Item>
                      )}
                    />
                  )}
                  {visibleDocuments.some(
                    (doc) =>
                      !references.some(
                        (group) =>
                          group.source.document_id === doc.document_id &&
                          group.source.publication_id === doc.publication_id,
                      ),
                  ) && (
                    <List
                      dataSource={visibleDocuments.filter(
                        (doc) =>
                          !references.some(
                            (group) =>
                              group.source.document_id === doc.document_id &&
                              group.source.publication_id === doc.publication_id,
                          ),
                      )}
                      locale={{ emptyText: null }}
                      renderItem={(doc) => (
                        <List.Item
                          actions={[
                            <Button
                              key="preview"
                              type="link"
                              onClick={() =>
                                setSource({
                                  kind: 'KB_PUBLICATION',
                                  document_id: doc.document_id,
                                  publication_id: doc.publication_id,
                                  version_id: '',
                                  source_node_key: '',
                                  content_sha256: '',
                                  title: doc.title || '文献',
                                  anchor: {},
                                })
                              }
                            >
                              预览
                            </Button>,
                            <Button
                              key="download"
                              type="link"
                              onClick={() =>
                                void act(async () => {
                                  const published = await knowledgeApi.publishedContent(
                                    doc.document_id,
                                    doc.publication_id,
                                  );
                                  triggerNativeDownload(
                                    knowledgeApi.contentUrl(
                                      doc.document_id,
                                      published.publication.versionId,
                                    ),
                                    published.originalName,
                                  );
                                })
                              }
                            >
                              下载
                            </Button>,
                          ]}
                        >
                          <List.Item.Meta title={doc.title || '文献'} description="知识库资料" />
                        </List.Item>
                      )}
                    />
                  )}
                  {topic && (
                    <details className="research-extra">
                      <summary>笔记与批注</summary>
                      <Input.TextArea
                        aria-label="研究笔记"
                        placeholder="记录你的想法…"
                        value={note}
                        onChange={(event) => setNote(event.target.value)}
                      />
                      <Button
                        disabled={!note.trim()}
                        loading={busy}
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
                      {annotations.isError && (
                        <Alert
                          type="error"
                          message="笔记加载失败"
                          action={<Button onClick={() => void annotations.refetch()}>重试</Button>}
                        />
                      )}
                      <List
                        dataSource={annotations.data || []}
                        locale={{ emptyText: '暂无笔记' }}
                        renderItem={(item) => (
                          <List.Item
                            actions={
                              item.source
                                ? [
                                    <Button
                                      key="read"
                                      type="link"
                                      onClick={() => setSource(item.source)}
                                    >
                                      预览
                                    </Button>,
                                  ]
                                : []
                            }
                          >
                            {item.text}
                          </List.Item>
                        )}
                      />
                    </details>
                  )}
                  {topic && fields.length > 0 && (
                    <details className="research-extra">
                      <summary>数据比较与人工核对</summary>
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
                                        ? '已核对'
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
                                      预览
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
                              render: (_, field) =>
                                `${field.value ?? '未报告'} ${field.unit || ''}`,
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
                                      ? '已核对'
                                      : field.status === 'REJECTED'
                                        ? '已排除'
                                        : '待核对'}
                                  </Tag>
                                  <Button size="small" onClick={() => setSource(field.source)}>
                                    预览
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
                    </details>
                  )}
                </>
              ),
            },
            {
              key: 'files',
              label: '文件和报告',
              children: (
                <>
                  {(artifacts.isError || reports.isError) && (
                    <Alert
                      type="error"
                      message="部分文件或报告加载失败"
                      action={
                        <Button
                          onClick={() => {
                            void artifacts.refetch();
                            void reports.refetch();
                          }}
                        >
                          重试
                        </Button>
                      }
                    />
                  )}
                  <List
                    loading={artifacts.isFetching && !artifacts.data}
                    locale={{ emptyText: artifacts.isError ? null : '尚未生成文件' }}
                    dataSource={[
                      ...new Map([
                        ...(conversation?.registered_outputs || [])
                          .filter((file) => file.kind === 'artifact')
                          .map((file) => [file.id, { id: file.id, name: file.title }] as const),
                        ...(run?.registered_outputs || [])
                          .filter((file) => file.kind === 'artifact')
                          .map((file) => [file.id, { id: file.id, name: file.title }] as const),
                        ...(artifacts.data || []).map((file) => [file.id, file] as const),
                      ]).values(),
                    ]}
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
                          description={file.name.split('.').at(-1)?.toUpperCase()}
                        />
                      </List.Item>
                    )}
                  />
                  <List
                    loading={reports.isFetching && !reports.data}
                    locale={{ emptyText: reports.isError ? null : '尚未生成报告' }}
                    dataSource={reports.data || []}
                    renderItem={(report) => (
                      <List.Item
                        actions={[
                          <Button key="read" onClick={() => openReport(report.id)}>
                            阅读报告
                          </Button>,
                        ]}
                      >
                        <List.Item.Meta
                          title={report.title}
                          description={reportAssessment(report).label}
                        />
                      </List.Item>
                    )}
                  />
                  {run &&
                    deliveryProblems(run).map((problem) => (
                      <p key={problem} className="research-delivery-problem">
                        {problem}
                      </p>
                    ))}
                  {(matrixReview || run?.research_matrix) && (
                    <ResearchQualityPanel
                      matrix={run?.research_matrix}
                      review={matrixReview}
                      reportExpected={Boolean(
                        matrixReview || run?.plan?.deliverables.includes('report'),
                      )}
                      onQuote={(quote) => setSource(quote.source, quote)}
                    />
                  )}
                </>
              ),
            },
          ]}
        />
      </div>
    </section>
  );
  const panel = sourceView ? (
    <EvidenceReader
      key={JSON.stringify([sourceView.source, sourceView.title])}
      evidence={sourceView}
      onClose={closePanel}
      onAnnotate={
        topicId
          ? async (text, reference) => {
              await agentApi.annotate(topicId, text, reference);
              refreshResearch();
            }
          : undefined
      }
    />
  ) : reportOpen ? (
    <ResearchReportReader
      report={(reports.data || []).find((item) => item.id === selectedReportId)}
      reports={reports.data || []}
      loading={reports.isFetching}
      error={reports.isError}
      onRetry={() => void reports.refetch()}
      onClose={closePanel}
      onSelect={setSelectedReportId}
      onSource={setSource}
      onCitation={clickCitation}
    />
  ) : (
    materials
  );
  return (
    <main ref={workspace} className="research-workspace harness-workspace">
      <input
        ref={uploadInput}
        type="file"
        hidden
        accept=".pdf,.docx,.xlsx,.csv,.md,.txt"
        aria-label="上传研究文件"
        onChange={(event) => {
          const file = event.target.files?.[0];
          event.target.value = '';
          if (file) {
            showMaterials('references');
            void attachSource(file);
          }
        }}
      />
      {capability.isError && (
        <Alert
          type="error"
          message="工作区加载失败"
          action={<Button onClick={() => void capability.refetch()}>重试</Button>}
        />
      )}
      {capability.data && !capability.data.enabled && (
        <Alert type="info" message="研究功能尚未启用" />
      )}
      {capability.data?.enabled && capability.data.available === false && (
        <Alert
          type="warning"
          message="服务暂不可用，仍可阅读已保存资料"
          action={<Button onClick={() => void capability.refetch()}>重新检查</Button>}
        />
      )}
      <div className={`research-session-layout${wide && panelOpen ? ' has-panel' : ''}`}>
        <div className="research-session-chat">{task}</div>
        {wide && panelOpen && <aside className="research-workspace-panel">{panel}</aside>}
      </div>
      <Drawer
        className="research-panel-drawer"
        closable={false}
        width="min(820px, 100vw)"
        open={!wide && panelOpen}
        onClose={closePanel}
        destroyOnClose
      >
        {!wide && panelOpen && panel}
      </Drawer>
    </main>
  );
}
