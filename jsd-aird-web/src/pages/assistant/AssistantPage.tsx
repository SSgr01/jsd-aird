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
import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';

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
import {
  AssistantRequestError,
  assistantApi,
  type AssistantCitation,
  type AssistantResponse,
  type ConversationMeta,
} from '@/services/assistant';
import { HttpError } from '@/services/http/errors';
import { dataApi, type DataCategory } from '@/services/data/data-api';
import { knowledgeApi, type KnowledgeCategory } from '@/services/knowledge';
import {
  citationEvidenceLabel,
  citationPagesLabel,
  groupAssistantCitations,
} from './citation-utils';

interface ChatMessage {
  id: string;
  role: 'USER' | 'ASSISTANT';
  content: string;
  citations?: AssistantCitation[];
}

function displayAnswer(value: string) {
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
        return (parsed as { answer: string }).answer;
      }
    } catch {
      /* keep the original Markdown */
    }
  }
  return value;
}

function citationSourceKey(citation: AssistantCitation) {
  return `${citation.sourceType}-${citation.chunkId || citation.url || citation.title}`;
}

function thinkingLabel(value: unknown) {
  return value === 'UNDERSTANDING'
    ? '正在理解问题…'
    : value === 'SEARCHING'
      ? '正在查找资料…'
      : value === 'COMPOSING'
        ? '正在组织回答…'
        : '正在思考…';
}

function assistantFailure(error: unknown): string {
  const code =
    error instanceof AssistantRequestError || error instanceof HttpError ? error.code : '';
  const status =
    error instanceof AssistantRequestError || error instanceof HttpError ? error.status : undefined;
  if (code === 'AI_PERMISSION_DENIED' || code === 'PERMISSION_DENIED') {
    return '当前账号没有使用 AI 问答的权限，请联系系统管理员开通。';
  }
  if (code === 'CSRF_TOKEN_INVALID') {
    return '页面安全令牌已失效，请刷新页面后重试。';
  }
  if (code === 'AUTH_REQUIRED') {
    return '登录状态已失效，请重新登录后再试。';
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
  if (code === 'AI_PROVIDER_UNAVAILABLE') {
    return 'AI 模型服务暂时不可用，请稍后重试。';
  }
  if (status === 403) {
    return '请求被安全策略拒绝，请刷新页面后重试；如仍失败请联系管理员。';
  }
  return error instanceof Error ? error.message : 'AI 问答失败，请稍后重试。';
}

function renderAssistantContent(
  message: ChatMessage,
  navigate: ReturnType<typeof useNavigate>,
  onPreview: (citation: AssistantCitation) => void,
  onDownload: (citation: AssistantCitation) => void,
  hasOriginalFile: (citation: AssistantCitation) => boolean,
) {
  const citationGroups = groupAssistantCitations(message.citations);
  return (
    <div>
      <MarkdownContent value={displayAnswer(message.content)} />
      {citationGroups.length ? (
        <div className="ai-message-citations">
          <Typography.Text type="secondary">参考来源</Typography.Text>
          <div className="ai-message-citation-list">
            {citationGroups.map((group) => {
              const citation = group.citation;
              const external = citation.sourceType === 'EXTERNAL_REFERENCE';
              return (
              <div
                className="ai-message-citation-row"
                key={group.key}
              >
                {external ? <GlobalOutlined aria-hidden="true" /> : <FileTextOutlined aria-hidden="true" />}
                <button
                  type="button"
                  className="ai-message-citation-title"
                  disabled={!citation.documentId && !citation.fileObjectId && !citation.url}
                  onClick={() => {
                    if (citation.documentId)
                      navigate(`/knowledge/documents/${citation.documentId}`);
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
  const [question, setQuestion] = useState('');
  const [conversationId, setConversationId] = useState<string>();
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [loading, setLoading] = useState(false);
  const [streaming, setStreaming] = useState(false);
  const [streamStage, setStreamStage] = useState('正在思考…');
  const [conversations, setConversations] = useState<ConversationMeta[]>([]);
  const [knowledgeCategories, setKnowledgeCategories] = useState<KnowledgeCategory[]>([]);
  const [dataCategories, setDataCategories] = useState<DataCategory[]>([]);
  const [selectedKnowledge, setSelectedKnowledge] = useState<string[]>([]);
  const [selectedData, setSelectedData] = useState<string[]>([]);
  const [previewFile, setPreviewFile] = useState<FilePreviewDescriptor>();
  const [citationSources, setCitationSources] = useState<Record<string, boolean>>({});
  const [webSearchAvailable, setWebSearchAvailable] = useState(false);
  const [webSearchEnabled, setWebSearchEnabled] = useState(false);

  useEffect(() => {
    void Promise.all([
      assistantApi.conversations(),
      knowledgeApi.categories(),
      dataApi.listCategories(),
      assistantApi.capabilities().catch(() => ({ webSearchAvailable: false })),
    ])
      .then(([conversationList, knowledgeList, dataList, capabilities]) => {
        setConversations(conversationList);
        setKnowledgeCategories(knowledgeList);
        setDataCategories(dataList);
        setSelectedKnowledge(knowledgeList.map((item) => item.id));
        setSelectedData(dataList.map((item) => item.id));
        setWebSearchAvailable(Boolean(capabilities.webSearchAvailable));
      })
      .catch(() => toast.error('AI 问答范围加载失败'));
  }, [toast]);

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
          citation.sourceType === 'DATA_SOURCE_FILE' && Boolean(citation.fileObjectId),
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

  const send = async () => {
    const value = question.trim();
    if (!value || loading || streaming || !hasSelectedScope) return;
    setQuestion('');
    setLoading(true);
    setStreamStage('正在思考…');
    const index = messages.length + 1;
    setMessages((current) => [
      ...current,
      { id: `${Date.now()}-user`, role: 'USER', content: value },
      { id: `${Date.now()}-assistant`, role: 'ASSISTANT', content: '' },
    ]);
    try {
      setStreaming(true);
      let answer = '';
      await assistantApi.stream(
        value,
        conversationId,
        selectedKnowledge,
        selectedData,
        webSearchEnabled,
        (token) => {
          answer += token;
          setMessages((current) =>
            current.map((item, itemIndex) =>
              itemIndex === index ? { ...item, content: answer } : item,
            ),
          );
        },
        (response: AssistantResponse) => {
          setConversationId(response.conversationId);
          setMessages((current) =>
            current.map((item, itemIndex) =>
              itemIndex === index
                ? {
                    ...item,
                    content: response.answer || answer,
                    citations: response.citations,
                  }
                : item,
            ),
          );
          void assistantApi
            .conversations()
            .then(setConversations)
            .catch(() => undefined);
          window.setTimeout(() => {
            void assistantApi
              .conversations()
              .then(setConversations)
              .catch(() => undefined);
          }, 1200);
        },
        (event, data) => {
          if (event === 'thinking') setStreamStage(thinkingLabel(data));
        },
      );
    } catch (error) {
      const failure = assistantFailure(error);
      setMessages((current) =>
        current.map((item, itemIndex) =>
          itemIndex === index
            ? {
                ...item,
                content: failure,
              }
            : item,
        ),
      );
    } finally {
      setLoading(false);
      setStreaming(false);
      setStreamStage('正在思考…');
    }
  };

  const reset = () => {
    setConversationId(undefined);
    setChatMessages([]);
    setQuestion('');
    setWebSearchEnabled(false);
  };

  const openConversation = async (id: string) => {
    setWebSearchEnabled(false);
    setLoading(true);
    try {
      const conversation = await assistantApi.conversation(id);
      setConversationId(id);
      setMessages(
        conversation.messages.map((item, index) => ({
          id: `${id}-${index}`,
          role: item.role === 'USER' ? 'USER' : 'ASSISTANT',
          content: item.content,
          citations: item.citations,
        })),
      );
    } catch (error) {
      void toast.error(error instanceof Error ? error.message : '会话加载失败');
    } finally {
      setLoading(false);
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
      return {
        fileName: citation.originalName || citation.title || 'knowledge-document',
        load: () => knowledgeApi.contentBlob(citation.documentId as string, citation.versionId),
      };
    }
    if (citation.sourceType === 'DATA_SOURCE_FILE' && citation.fileObjectId) {
      return {
        fileName: citation.originalName || citation.title || 'source-file',
        load: () => dataApi.sourceBlob(citation.fileObjectId as string),
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

  const viewMessages: ConversationMessage[] = messages.map((item) => ({
    id: item.id,
    role: item.role,
    pending: item.role === 'ASSISTANT' && streaming && !item.content,
    content:
      item.role === 'ASSISTANT' ? (
        item.content ? (
          renderAssistantContent(
            item,
            navigate,
            (citation) => void previewCitation(citation),
            (citation) => void downloadCitation(citation),
            (citation) =>
              Boolean(
                citation.documentId ||
                citation.sourceType === 'DATA_SOURCE_FILE' ||
                citationSources[citationSourceKey(citation)],
              ),
          )
        ) : null
      ) : (
        <Typography.Paragraph className="ai-message-text">{item.content}</Typography.Paragraph>
      ),
  }));

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
        composerActions={(
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
        )}
        scopeSummary={
          <Typography.Text type="secondary">
            {allAuthorizedSelected
              ? '全部已授权资料'
              : hasSelectedScope
                ? `已选择 ${selectedKnowledge.length + selectedData.length} 个检索范围`
                : '未选择资料范围'}
          </Typography.Text>
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
