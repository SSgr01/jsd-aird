import {
  ArrowLeftOutlined,
  DatabaseOutlined,
  DownloadOutlined,
  EyeOutlined,
  FileTextOutlined,
  FolderOpenOutlined,
  GlobalOutlined,
} from '@ant-design/icons';
import { App, Button, Checkbox, Collapse, Space, Tag, Typography } from 'antd';
import { useCallback, useEffect, useMemo, useRef, useState, useSyncExternalStore } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';

import {
  AiConversationWorkspace,
  type ConversationItem,
  type ConversationMessage,
} from '@/components/ai-conversation-workspace';
import {
  FilePreviewModal,
  downloadPreviewFile,
  type FilePreviewDescriptor,
} from '@/components/file-preview';
import { MarkdownContent } from '@/components/markdown/MarkdownContent';
import { StreamingMarkdownContent } from '@/components/markdown/StreamingMarkdownContent';
import {
  AssistantRequestError,
  assistantApi,
  type AssistantCitation,
  type AssistantMessageReplacement,
  type ConversationMeta,
} from '@/services/assistant';
import {
  claimAssistantAuthRedirect,
  getAssistantConversationSelection,
  getAssistantStream,
  getAssistantStreams,
  setAssistantConversationSelection,
  startAssistantStream,
  subscribeAssistantStream,
  subscribeAssistantStreams,
  type AssistantStreamTask,
} from '@/services/assistant/assistant-streams';
import { HttpError } from '@/services/http/errors';
import { dataApi, type DataCategory } from '@/services/data/data-api';
import { fileContentUrl, fetchFilePreviewBlob } from '@/services/files';
import { knowledgeApi, type KnowledgeCategory } from '@/services/knowledge';
import {
  citationEvidenceLabel,
  citationPagesLabel,
  dataFileSelectionQuestion,
  groupAssistantCitations,
  partitionAssistantCitations,
} from './citation-utils';

interface ChatMessage {
  id: string;
  role: 'USER' | 'ASSISTANT';
  content: string;
  citations?: AssistantCitation[];
}

const UNCATEGORIZED_DATA_SCOPE_ID = '00000000-0000-0000-0000-000000000000';

function displayAnswer(value: string) {
  const stripLegacyStatus = (answer: string) =>
    answer.replace(/(?:\r?\n|^)[ \t]*(?:ANSWERED|PARTIAL|NOT_FOUND)[ \t]*$/i, '').trim();
  const text = value?.trim() || '';
  const start = text.indexOf('{');
  const end = text.lastIndexOf('}');
  if (start >= 0 && end > start) {
    try {
      const parsed: unknown = JSON.parse(text.slice(start, end + 1));
      if (
        parsed &&
        typeof parsed === 'object' &&
        !Array.isArray(parsed) &&
        typeof (parsed as { answer?: unknown }).answer === 'string'
      ) {
        return stripLegacyStatus((parsed as { answer: string }).answer);
      }
    } catch {
      /* keep the original Markdown */
    }
  }
  return stripLegacyStatus(value);
}

function citationSourceKey(citation: AssistantCitation) {
  return `${citation.sourceType}-${citation.chunkId || citation.url || citation.title}`;
}

function assistantFailure(error: unknown): string {
  const code =
    error instanceof AssistantRequestError || error instanceof HttpError ? error.code : '';
  const status =
    error instanceof AssistantRequestError || error instanceof HttpError ? error.status : undefined;
  if (code === 'AI_PERMISSION_DENIED' || code === 'PERMISSION_DENIED') {
    return '当前账号没有使用 AI 问答的权限，请联系系统管理员开通。';
  }
  if (code === 'AI_MODEL_NOT_CONFIGURED' || code === 'AI_NOT_CONFIGURED') {
    return 'AI 模型网关尚未配置，暂时无法生成回答。';
  }
  if (code === 'AI_MODEL_AUTH_FAILED') {
    return 'AI 模型网关认证失败，暂时无法生成回答。';
  }
  if (code === 'AI_MODEL_RATE_LIMITED') {
    return 'AI 模型服务请求过于频繁，请稍后重试。';
  }
  if (code === 'AI_MODEL_TIMEOUT') {
    return 'AI 模型服务响应超时，请稍后重试。';
  }
  if (code === 'AI_MODEL_EMPTY_RESPONSE') {
    return 'AI 模型没有返回有效回答，请稍后重试。';
  }
  if (code === 'AI_MODEL_RESPONSE_INVALID') {
    return 'AI 模型返回格式无效，未保存本次回答，请稍后重试。';
  }
  if (code === 'AI_PROVIDER_UNAVAILABLE') {
    return 'AI 模型服务暂时不可用，请稍后重试。';
  }
  if (status === 403) {
    return '请求被安全策略拒绝，请刷新页面后重试；如仍失败请联系管理员。';
  }
  return error instanceof Error ? error.message : 'AI 问答失败，请稍后重试。';
}

function isAuthenticationFailure(error: unknown) {
  if (!(error instanceof AssistantRequestError || error instanceof HttpError)) return false;
  return error.status === 401 || error.code === 'AUTH_REQUIRED';
}

function syncAssistantTaskMessages(messages: ChatMessage[], task: AssistantStreamTask) {
  const content =
    task.status === 'RUNNING'
      ? undefined
      : task.status === 'FAILED'
      ? task.answer
        ? `${task.answer}\n\n回答未完成：${assistantFailure(task.failure)}`
        : assistantFailure(task.failure)
      : task.answer;
  let next = messages.map((item) => {
    if (
      item.id === task.localUserMessageId ||
      (task.userMessageId && item.id === task.userMessageId)
    ) {
      return { ...item, id: task.userMessageId || item.id, content: task.question };
    }
    if (
      item.id === task.localAssistantMessageId ||
      (task.assistantMessageId && item.id === task.assistantMessageId)
    ) {
      const next = {
        ...item,
        id: task.assistantMessageId || item.id,
        citations: task.response?.citations || task.citations,
      };
      return content === undefined ? next : { ...next, content };
    }
    return item;
  });

  const hasUser = next.some(
    (item) =>
      item.id === task.localUserMessageId ||
      Boolean(task.userMessageId && item.id === task.userMessageId),
  );
  if (!hasUser) {
    next = [
      ...next,
      {
        id: task.userMessageId || task.localUserMessageId,
        role: 'USER',
        content: task.question,
      },
    ];
  }

  const hasAssistant = next.some(
    (item) =>
      item.id === task.localAssistantMessageId ||
      Boolean(task.assistantMessageId && item.id === task.assistantMessageId),
  );
  if (!hasAssistant) {
    next = [
      ...next,
      {
        id: task.assistantMessageId || task.localAssistantMessageId,
        role: 'ASSISTANT',
        content: content || '',
        citations: task.response?.citations || task.citations,
      },
    ];
  }
  return next;
}

function LiveAssistantAnswer({ runId }: { runId: string }) {
  const subscribe = useCallback(
    (listener: () => void) => subscribeAssistantStream(runId, listener),
    [runId],
  );
  const getSnapshot = useCallback(() => getAssistantStream(runId)?.answer || '', [runId]);
  const answer = useSyncExternalStore(subscribe, getSnapshot, getSnapshot);
  return answer ? (
    <StreamingMarkdownContent value={answer} />
  ) : (
    <Typography.Text type="secondary">正在生成回答…</Typography.Text>
  );
}

function renderAssistantContent(
  message: ChatMessage,
  navigate: ReturnType<typeof useNavigate>,
  onPreview: (citation: AssistantCitation) => void,
  onDownload: (citation: AssistantCitation) => void,
  hasOriginalFile: (citation: AssistantCitation) => boolean,
  onSelectDataFile: (citation: AssistantCitation) => void,
  candidateDisabled: boolean,
) {
  const { candidates, evidence } = partitionAssistantCitations(message.citations);
  const citationGroups = groupAssistantCitations(evidence);
  return (
    <div>
      <MarkdownContent value={displayAnswer(message.content)} />
      {candidates.length ? (
        <div className="ai-message-file-candidates">
          <Typography.Text type="secondary">请选择数据文件</Typography.Text>
          <div className="ai-message-file-candidate-list">
            {candidates.map((citation) => (
              <Button
                key={citation.originalName || citation.title}
                size="small"
                icon={<FileTextOutlined />}
                disabled={candidateDisabled}
                title={citation.originalName || citation.title}
                onClick={() => onSelectDataFile(citation)}
              >
                {citation.originalName || citation.title}
              </Button>
            ))}
          </div>
        </div>
      ) : null}
      {citationGroups.length ? (
        <div className="ai-message-citations">
          <Typography.Text type="secondary">参考来源</Typography.Text>
          <div className="ai-message-citation-list">
            {citationGroups.map((group) => {
              const citation = group.citation;
              const external = citation.sourceType === 'EXTERNAL_REFERENCE';
              return (
                <div className="ai-message-citation-row" key={group.key}>
                  {external ? (
                    <GlobalOutlined aria-hidden="true" />
                  ) : (
                    <FileTextOutlined aria-hidden="true" />
                  )}
                  <button
                    type="button"
                    className="ai-message-citation-title"
                    disabled={!citation.documentId && !citation.fileObjectId && !citation.url}
                    onClick={() => {
                      if (citation.documentId)
                        navigate(`/knowledge/documents/${citation.documentId}`);
                      else if (citation.experimentId)
                        navigate(`/experiments/${citation.experimentId}`);
                      else if (citation.fileObjectId) navigate('/data/view');
                      else if (citation.url) {
                        const opened = window.open(citation.url, '_blank', 'noopener,noreferrer');
                        if (opened) opened.opener = null;
                      }
                    }}
                  >
                    <span className="ai-message-citation-name">
                      {citation.title || citation.originalName || '来源文件'}
                    </span>
                    <span className="ai-message-citation-summary">
                      {external
                        ? ` · 互联网来源${citation.siteName ? ` · ${citation.siteName}` : ''}`
                        : `${citationPagesLabel(group.pages)}${citationEvidenceLabel(group.evidenceCount)}`}
                    </span>
                  </button>
                  {hasOriginalFile(citation) && (
                    <div className="ai-message-citation-actions">
                      <Button
                        size="small"
                        type="text"
                        icon={<EyeOutlined />}
                        onClick={() => onPreview(citation)}
                      >
                        预览
                      </Button>
                      <Button
                        size="small"
                        type="text"
                        icon={<DownloadOutlined />}
                        onClick={() => onDownload(citation)}
                      >
                        下载
                      </Button>
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        </div>
      ) : null}
    </div>
  );
}

export function AssistantPage() {
  const { message: toast } = App.useApp();
  const navigate = useNavigate();
  const location = useLocation();
  const [question, setQuestion] = useState('');
  const [conversationId, setConversationId] = useState<string>();
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [loading, setLoading] = useState(false);
  const [assistantStreams, setAssistantStreams] = useState(getAssistantStreams());
  const [selectionKey, setSelectionKey] = useState(getAssistantConversationSelection());
  const [conversations, setConversations] = useState<ConversationMeta[]>([]);
  const [knowledgeCategories, setKnowledgeCategories] = useState<KnowledgeCategory[]>([]);
  const [dataCategories, setDataCategories] = useState<DataCategory[]>([]);
  const [selectedKnowledge, setSelectedKnowledge] = useState<string[]>([]);
  const [selectedData, setSelectedData] = useState<string[]>([]);
  const [previewFile, setPreviewFile] = useState<FilePreviewDescriptor>();
  const [citationSources, setCitationSources] = useState<Record<string, boolean>>({});
  const [webSearchAvailable, setWebSearchAvailable] = useState(false);
  const [webSearchEnabled, setWebSearchEnabled] = useState(false);
  const knownConversationRuns = useRef(new Set<string>());

  const selectConversationState = (value?: string) => {
    setAssistantConversationSelection(value);
    setSelectionKey(value);
  };

  const currentRun = useMemo(() => {
    if (selectionKey?.startsWith('pending:')) {
      return assistantStreams.get(selectionKey.slice('pending:'.length));
    }
    if (selectionKey) {
      return [...assistantStreams.values()]
        .reverse()
        .find((task) => task.conversationId === selectionKey);
    }
    return undefined;
  }, [assistantStreams, selectionKey]);
  const streaming = currentRun?.status === 'RUNNING';
  const streamStage = currentRun?.stage || '正在思考…';

  useEffect(
    () =>
      subscribeAssistantStreams(() => {
        setAssistantStreams(getAssistantStreams());
        setSelectionKey(getAssistantConversationSelection());
      }),
    [],
  );

  useEffect(() => {
    if (!currentRun) return;
    if (currentRun.conversationId && selectionKey === currentRun.conversationId) {
      setConversationId(currentRun.conversationId);
    }
    setMessages((current) => syncAssistantTaskMessages(current, currentRun));
  }, [currentRun, selectionKey]);

  useEffect(() => {
    for (const task of assistantStreams.values()) {
      if (task.conversationId && !knownConversationRuns.current.has(task.id)) {
        knownConversationRuns.current.add(task.id);
        void assistantApi
          .conversations()
          .then(setConversations)
          .catch(() => undefined);
      }
      if (task.status === 'FAILED' && isAuthenticationFailure(task.failure)) {
        if (!claimAssistantAuthRedirect(task.id)) continue;
        navigate('/login', {
          replace: true,
          state: { from: `${location.pathname}${location.search}` },
        });
      }
    }
  }, [assistantStreams, location.pathname, location.search, navigate]);

  useEffect(() => {
    void Promise.all([
      assistantApi.conversations(),
      knowledgeApi.categories(),
      dataApi.listCategories(),
      dataApi.listSourceFiles({ page: 1, size: 1 }),
      assistantApi.capabilities().catch(() => ({ webSearchAvailable: false })),
    ])
      .then(([conversationList, knowledgeList, dataList, sourcePage, capabilities]) => {
        const categorizedSourceCount = dataList.reduce(
          (total, item) => total + item.sourceCount,
          0,
        );
        const uncategorizedSourceCount = Math.max(0, sourcePage.total - categorizedSourceCount);
        const assistantDataList =
          uncategorizedSourceCount > 0
            ? [
                ...dataList,
                {
                  id: UNCATEGORIZED_DATA_SCOPE_ID,
                  name: '未分类',
                  description: '尚未归入数据分类的来源文件',
                  sortOrder: Number.MAX_SAFE_INTEGER,
                  sourceCount: uncategorizedSourceCount,
                  revision: '',
                },
              ]
            : dataList;
        setConversations(conversationList);
        setKnowledgeCategories(knowledgeList);
        setDataCategories(assistantDataList);
        setSelectedKnowledge(knowledgeList.map((item) => item.id));
        setSelectedData(assistantDataList.map((item) => item.id));
        setWebSearchAvailable(Boolean(capabilities.webSearchAvailable));
      })
      .catch(() => toast.error('AI 问答范围加载失败'));
  }, [toast]);

  useEffect(() => {
    const selection = getAssistantConversationSelection();
    setSelectionKey(selection);
    if (!selection) return;
    if (selection.startsWith('pending:')) {
      const task = getAssistantStreams().get(selection.slice('pending:'.length));
      if (!task) {
        selectConversationState(undefined);
        return;
      }
      setConversationId(task.conversationId);
      setMessages(syncAssistantTaskMessages([], task));
      return;
    }
    setConversationId(selection);
    void assistantApi
      .conversation(selection)
      .then((conversation) => {
        if (getAssistantConversationSelection() !== selection) return;
        const restoredMessages: ChatMessage[] = conversation.messages.map((item) => ({
          id: item.id,
          role: item.role === 'USER' ? 'USER' : 'ASSISTANT',
          content: item.content,
          citations: item.citations,
        }));
        const run = [...getAssistantStreams().values()]
          .reverse()
          .find((task) => task.conversationId === selection);
        setMessages(run ? syncAssistantTaskMessages(restoredMessages, run) : restoredMessages);
      })
      .catch(() => undefined);
  }, []);

  useEffect(() => {
    let active = true;
    const citations = messages
      .flatMap((item) => item.citations || [])
      .filter((citation) => citation.fileObjectId);
    const unique = Array.from(
      new Map(citations.map((citation) => [citationSourceKey(citation), citation])).values(),
    );
    if (!unique.length) {
      setCitationSources({});
      return () => {
        active = false;
      };
    }
    const entries = unique.map((citation) => {
      try {
        return [
          citationSourceKey(citation),
          ['DATA_SOURCE_FILE', 'EXPERIMENT_TEMPLATE_SOURCE'].includes(citation.sourceType) &&
            Boolean(citation.fileObjectId),
        ] as const;
      } catch {
        return [citationSourceKey(citation), false] as const;
      }
    });
    if (active) setCitationSources(Object.fromEntries(entries));
    return () => {
      active = false;
    };
  }, [messages]);

  const conversationItems: ConversationItem[] = useMemo(
    () => conversations.map((item) => ({ id: item.id, title: item.title || '未命名会话' })),
    [conversations],
  );

  const setChatMessages = (items: ChatMessage[]) => setMessages(items);
  const hasSelectedScope = selectedKnowledge.length > 0 || selectedData.length > 0;

  const send = (questionOverride?: string, replacement?: AssistantMessageReplacement) => {
    const value = (questionOverride ?? question).trim();
    if (!value || loading || streaming || !hasSelectedScope) return;
    if (!replacement) setQuestion('');
    setLoading(true);
    const runId = `${Date.now()}-${Math.random().toString(36).slice(2)}`;
    const userPlaceholderId = `pending-user-${runId}`;
    const assistantPlaceholderId = `pending-assistant-${runId}`;
    setMessages((current) => {
      const nextMessages: ChatMessage[] = [
        { id: userPlaceholderId, role: 'USER', content: value },
        { id: assistantPlaceholderId, role: 'ASSISTANT', content: '' },
      ];
      if (!replacement) return [...current, ...nextMessages];
      const targetIndex = current.findIndex((item) => item.id === replacement.replaceFromMessageId);
      return targetIndex < 0 ? current : [...current.slice(0, targetIndex), ...nextMessages];
    });
    const streamId = startAssistantStream({
      question: value,
      conversationId,
      knowledgeCategoryIds: selectedKnowledge,
      dataCategoryIds: selectedData,
      webSearchEnabled,
      replacement,
      localUserMessageId: userPlaceholderId,
      localAssistantMessageId: assistantPlaceholderId,
    });
    setSelectionKey(conversationId || `pending:${streamId}`);
    setLoading(false);
  };

  const reset = () => {
    selectConversationState(undefined);
    setConversationId(undefined);
    setChatMessages([]);
    setQuestion('');
    setWebSearchEnabled(false);
  };

  const openConversation = async (id: string) => {
    selectConversationState(id);
    setConversationId(id);
    setMessages([]);
    setWebSearchEnabled(false);
    setLoading(true);
    try {
      const conversation = await assistantApi.conversation(id);
      if (getAssistantConversationSelection() !== id) return;
      const loadedMessages: ChatMessage[] = conversation.messages.map((item) => ({
        id: item.id,
        role: item.role === 'USER' ? 'USER' : 'ASSISTANT',
        content: item.content,
        citations: item.citations,
      }));
      const run = [...getAssistantStreams().values()]
        .reverse()
        .find((task) => task.conversationId === id);
      setMessages(run ? syncAssistantTaskMessages(loadedMessages, run) : loadedMessages);
    } catch (error) {
      void toast.error(error instanceof Error ? error.message : '会话加载失败');
    } finally {
      if (getAssistantConversationSelection() === id) setLoading(false);
    }
  };

  const renameConversation = async (item: ConversationItem, title: string) => {
    try {
      await assistantApi.renameConversation(item.id, title);
      setConversations(await assistantApi.conversations());
      toast.success('会话标题已更新');
    } catch (error) {
      toast.error(error instanceof Error ? error.message : '会话标题更新失败');
      throw error;
    }
  };

  const deleteConversation = async (item: ConversationItem) => {
    try {
      await assistantApi.deleteConversation(item.id);
      setConversations(await assistantApi.conversations());
      if (conversationId === item.id) reset();
      toast.success('会话已删除');
    } catch (error) {
      toast.error(error instanceof Error ? error.message : '会话删除失败');
      throw error;
    }
  };

  const resolveCitationFile = (citation: AssistantCitation): FilePreviewDescriptor => {
    if (citation.documentId) {
      const documentId = citation.documentId;
      const versionId = citation.versionId;
      const fileName = citation.originalName || citation.title || 'knowledge-document';
      return {
        fileName,
        load: async () => {
          if (/\.doc$/i.test(fileName)) {
            const resolvedVersionId = versionId || (await knowledgeApi.get(documentId)).currentVersionId;
            const versions = await knowledgeApi.versions(documentId);
            const version = versions.find((item) => item.id === resolvedVersionId);
            if (!version) throw new Error('找不到知识文档对应的文件版本');
            return fetchFilePreviewBlob(version.fileObjectId);
          }
          return knowledgeApi.contentBlob(documentId, versionId);
        },
        downloadUrl: versionId
          ? `/api/v1/knowledge/documents/${encodeURIComponent(documentId)}/versions/${encodeURIComponent(versionId)}/content`
          : `/api/v1/knowledge/documents/${encodeURIComponent(documentId)}/content`,
      };
    }
    const fileObjectId = citation.fileObjectId;
    if (
      ['DATA_SOURCE_FILE', 'EXPERIMENT_TEMPLATE_SOURCE'].includes(citation.sourceType) &&
      fileObjectId
    ) {
      return {
        fileName: citation.originalName || citation.title || 'source-file',
        load: () => fetchFilePreviewBlob(fileObjectId),
        downloadUrl: fileContentUrl(fileObjectId),
      };
    }
    throw new Error('当前引用没有可定位的原始文件');
  };

  const previewCitation = (citation: AssistantCitation) => {
    try {
      setPreviewFile(resolveCitationFile(citation));
    } catch (error) {
      void toast.error(error instanceof Error ? error.message : '原始文件加载失败');
    }
  };

  const downloadCitation = async (citation: AssistantCitation) => {
    try {
      await downloadPreviewFile(resolveCitationFile(citation));
      void toast.success('原文件下载已开始');
    } catch (error) {
      void toast.error(error instanceof Error ? error.message : '原文件下载失败');
    }
  };

  const viewMessages: ConversationMessage[] = messages.map((item, messageIndex) => {
    const streamTask = item.role === 'ASSISTANT'
      ? [...assistantStreams.values()].find(
          (task) => task.localAssistantMessageId === item.id || task.assistantMessageId === item.id,
        )
      : undefined;
    const live = streamTask?.status === 'RUNNING';
    return {
      id: item.id,
      role: item.role,
      editableText: item.role === 'USER' ? item.content : undefined,
      pending: item.role === 'ASSISTANT' && streaming && !item.content && !live,
      content:
        item.role === 'ASSISTANT' ? (
          live && streamTask ? (
            <LiveAssistantAnswer runId={streamTask.id} />
          ) : item.content ? (
            renderAssistantContent(
              item,
              navigate,
              (citation) => void previewCitation(citation),
              (citation) => void downloadCitation(citation),
              (citation) =>
                Boolean(
                  citation.documentId ||
                  ['DATA_SOURCE_FILE', 'EXPERIMENT_TEMPLATE_SOURCE'].includes(citation.sourceType) ||
                  citationSources[citationSourceKey(citation)],
                ),
              (citation) => {
                const fileName = citation.originalName || citation.title;
                const originalQuestion = messages
                  .slice(0, messageIndex)
                  .reverse()
                  .find((candidate) => candidate.role === 'USER')?.content;
                if (!fileName || !originalQuestion) return;
                void send(dataFileSelectionQuestion(fileName, originalQuestion));
              },
              loading || streaming,
            )
          ) : null
        ) : (
          <Typography.Paragraph className="ai-message-text">{item.content}</Typography.Paragraph>
        ),
    };
  });

  const knowledgeScopeGroups = [
    { scope: 'INTERNAL' as const, label: '内部资料' },
    { scope: 'EXTERNAL' as const, label: '外部资料' },
  ].map((group) => ({
    ...group,
    categories: knowledgeCategories.filter((item) => item.scope === group.scope),
  }));

  const scopeContent = (
    <Space direction="vertical" size={12} className="ai-scope-content">
      <Collapse
        ghost
        defaultActiveKey={['knowledge']}
        items={[
          {
            key: 'knowledge',
            label: (
              <span className="ai-scope-group-label">
                <span>
                  <FolderOpenOutlined /> 研发知识库
                </span>
                <b>{knowledgeCategories.reduce((total, item) => total + item.documentCount, 0)}</b>
              </span>
            ),
            children: (
              <Space direction="vertical" className="ai-scope-options">
                {knowledgeScopeGroups.map((group) => (
                  <div className="ai-scope-subgroup" key={group.scope}>
                    <Typography.Text className="ai-scope-subgroup-title" type="secondary">
                      {group.label}
                    </Typography.Text>
                    {group.categories.length ? (
                      group.categories.map((item) => (
                        <Checkbox
                          key={item.id}
                          checked={selectedKnowledge.includes(item.id)}
                          onChange={(event) =>
                            setSelectedKnowledge((current) =>
                              event.target.checked
                                ? Array.from(new Set([...current, item.id]))
                                : current.filter((id) => id !== item.id),
                            )
                          }
                        >
                          {item.name}
                          <Typography.Text type="secondary">
                            （{item.documentCount}）
                          </Typography.Text>
                        </Checkbox>
                      ))
                    ) : (
                      <Typography.Text type="secondary">暂无分类</Typography.Text>
                    )}
                  </div>
                ))}
              </Space>
            ),
          },
          {
            key: 'data',
            label: (
              <span className="ai-scope-group-label">
                <span>
                  <DatabaseOutlined /> 数据中心
                </span>
                <b>{dataCategories.reduce((total, item) => total + item.sourceCount, 0)}</b>
              </span>
            ),
            children: (
              <Space direction="vertical" className="ai-scope-options">
                {dataCategories.map((item) => (
                  <Checkbox
                    key={item.id}
                    checked={selectedData.includes(item.id)}
                    onChange={(event) =>
                      setSelectedData((current) =>
                        event.target.checked
                          ? Array.from(new Set([...current, item.id]))
                          : current.filter((id) => id !== item.id),
                      )
                    }
                  >
                    {item.name}
                    <Typography.Text type="secondary">（{item.sourceCount}）</Typography.Text>
                  </Checkbox>
                ))}
              </Space>
            ),
          },
        ]}
      />
    </Space>
  );

  const composerScopeGroups = [
    {
      key: 'knowledge',
      label: '研发知识库',
      count: knowledgeCategories
        .filter((item) => selectedKnowledge.includes(item.id))
        .reduce((total, item) => total + item.documentCount, 0),
      selected: selectedKnowledge.length,
    },
    {
      key: 'data',
      label: '数据中心',
      count: dataCategories
        .filter((item) => selectedData.includes(item.id))
        .reduce((total, item) => total + item.sourceCount, 0),
      selected: selectedData.length,
    },
  ].filter((item) => item.selected > 0);

  const allAuthorizedSelected =
    (knowledgeCategories.length > 0 || dataCategories.length > 0) &&
    knowledgeCategories.every((item) => selectedKnowledge.includes(item.id)) &&
    dataCategories.every((item) => selectedData.includes(item.id));

  const composerScopeContent = (
    <div className="ai-composer-scope-summary">
      <Typography.Text type="secondary">当前检索范围：</Typography.Text>
      <Space size={[6, 6]} wrap>
        {allAuthorizedSelected ? (
          <Tag className="ai-composer-scope-tag">全部已授权资料</Tag>
        ) : composerScopeGroups.length ? (
          composerScopeGroups.map((item) => (
            <Tag key={item.key} className="ai-composer-scope-tag">
              {item.label}
              <b>{item.count}</b>
            </Tag>
          ))
        ) : (
          <Typography.Text type="secondary">未选择资料范围</Typography.Text>
        )}
      </Space>
    </div>
  );

  return (
    <div className="business-page assistant-page">
      <div className="page-heading">
        <Button
          type="text"
          shape="circle"
          className="assistant-page-back"
          icon={<ArrowLeftOutlined />}
          aria-label="返回上一页"
          onClick={() => navigate(-1)}
        />
        <div className="assistant-page-heading-copy">
          <div className="assistant-page-title-row">
            <span className="assistant-page-sparkle" aria-hidden="true">
              ✦
            </span>
            <Typography.Title level={2}>AI问答</Typography.Title>
          </div>
          <Typography.Text type="secondary">
            基于已授权资料回答，也可按需补充公开互联网信息。
          </Typography.Text>
        </div>
      </div>
      <AiConversationWorkspace
        conversations={conversationItems}
        activeConversationId={conversationId}
        messages={viewMessages}
        scopeContent={scopeContent}
        composerTopContent={composerScopeContent}
        composerActions={
          <Button
            icon={<GlobalOutlined />}
            type={webSearchEnabled ? 'primary' : 'default'}
            className={`ai-composer-web-toggle${webSearchEnabled ? ' is-active' : ''}`}
            aria-pressed={webSearchEnabled}
            disabled={!webSearchAvailable || loading || streaming}
            title={webSearchAvailable ? '联网搜索公开资料' : '联网搜索尚未配置'}
            onClick={() => setWebSearchEnabled((current) => !current)}
          >
            联网搜索
          </Button>
        }
        pendingLabel={streamStage}
        question={question}
        loading={loading}
        streaming={streaming}
        submitDisabled={!hasSelectedScope}
        onNewConversation={reset}
        onSelectConversation={(id) => void openConversation(id)}
        onRenameConversation={renameConversation}
        onDeleteConversation={deleteConversation}
        onEditMessage={(item, value) => {
          const expectedLastMessageId = messages.at(-1)?.id;
          if (!conversationId || !expectedLastMessageId) {
            void toast.error('当前会话尚未保存完成，请稍后重试');
            return;
          }
          void send(value, {
            replaceFromMessageId: item.id,
            expectedLastMessageId,
          });
        }}
        onQuestionChange={setQuestion}
        onSubmit={() => void send()}
      />
      <FilePreviewModal
        open={Boolean(previewFile)}
        file={previewFile}
        onClose={() => setPreviewFile(undefined)}
      />
    </div>
  );
}
