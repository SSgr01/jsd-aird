import { DatabaseOutlined, DownloadOutlined, EyeOutlined, FolderOpenOutlined } from '@ant-design/icons';
import { Alert, App, Button, Checkbox, Collapse, Space, Tag, Typography } from 'antd';
import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';

import { AiConversationWorkspace, type ConversationItem, type ConversationMessage } from '@/components/ai-conversation-workspace';
import { FilePreviewModal, downloadPreviewFile, type FilePreviewDescriptor } from '@/components/file-preview';
import { assistantApi, type AiScope, type AssistantCitation, type AssistantResponse, type ConversationMeta } from '@/services/assistant';
import { dataApi, type DataCategory } from '@/services/data/data-api';
import { knowledgeApi, type KnowledgeCategory } from '@/services/knowledge';

interface ChatMessage {
  id: string;
  role: 'USER' | 'ASSISTANT';
  content: string;
  citations?: AssistantCitation[];
  warnings?: string[];
}

function citationSourceKey(citation: AssistantCitation) {
  return `${citation.sourceType}-${citation.chunkId}`;
}

function renderAssistantContent(
  message: ChatMessage,
  navigate: ReturnType<typeof useNavigate>,
  streaming: boolean,
  onPreview: (citation: AssistantCitation) => void,
  onDownload: (citation: AssistantCitation) => void,
  hasOriginalFile: (citation: AssistantCitation) => boolean,
) {
  return (
    <div>
      <Typography.Paragraph className="ai-message-text">{message.content || (streaming ? '正在生成回答…' : '')}</Typography.Paragraph>
      {message.warnings?.map((warning) => <Alert key={warning} type="warning" showIcon message={warning} className="ai-message-alert" />)}
      {message.citations?.length ? (
        <div className="ai-message-citations">
          <Typography.Text type="secondary">参考来源</Typography.Text>
          <Space wrap>
            {message.citations.map((citation) => (
              <Space key={`${citation.sourceType}-${citation.chunkId}`} size={4} wrap>
                {(() => {
                  const originalFileAvailable = hasOriginalFile(citation);
                  return <>
                <Tag
                  color="blue"
                  className={citation.documentId ? 'is-clickable' : undefined}
                  onClick={() => { if (citation.documentId) navigate(`/knowledge/documents/${citation.documentId}`); else if (citation.dataAssetId) navigate(`/data/assets/${citation.dataAssetId}`); }}
                >
                  {citation.title || citation.dataAssetId || '数据资产来源'}{citation.pageNo ? ` · 第${citation.pageNo}页` : ''}
                </Tag>
                {originalFileAvailable && <Button size="small" type="link" icon={<EyeOutlined />} onClick={() => onPreview(citation)}>预览</Button>}
                {originalFileAvailable && <Button size="small" type="link" icon={<DownloadOutlined />} onClick={() => onDownload(citation)}>下载</Button>}
                  </>;
                })()}
              </Space>
            ))}
          </Space>
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
  const [stage, setStage] = useState('等待提问');
  const [scopes, setScopes] = useState<AiScope[]>([]);
  const [conversations, setConversations] = useState<ConversationMeta[]>([]);
  const [knowledgeCategories, setKnowledgeCategories] = useState<KnowledgeCategory[]>([]);
  const [dataCategories, setDataCategories] = useState<DataCategory[]>([]);
  const [selectedScopes, setSelectedScopes] = useState<string[]>([]);
  const [selectedKnowledge, setSelectedKnowledge] = useState<string[]>([]);
  const [selectedData, setSelectedData] = useState<string[]>([]);
  const [previewFile, setPreviewFile] = useState<FilePreviewDescriptor>();
  const [citationSources, setCitationSources] = useState<Record<string, boolean>>({});

  useEffect(() => {
    void Promise.all([
      assistantApi.scopes(),
      assistantApi.conversations(),
      knowledgeApi.categories(),
      dataApi.listCategories(),
    ]).then(([scopeList, conversationList, knowledgeList, dataList]) => {
      setScopes(scopeList);
      setConversations(conversationList);
      setKnowledgeCategories(knowledgeList);
      setDataCategories(dataList);
    }).catch(() => toast.error('AI 问答范围加载失败'));
  }, [toast]);

  useEffect(() => {
    let active = true;
    const citations = messages.flatMap((item) => item.citations || []).filter((citation) => citation.dataAssetId);
    const unique = Array.from(new Map(citations.map((citation) => [citationSourceKey(citation), citation])).values());
    if (!unique.length) {
      setCitationSources({});
      return () => { active = false; };
    }
    void Promise.all(unique.map(async (citation) => {
      try { return [citationSourceKey(citation), Boolean(await dataApi.resolveSourceFile(citation.dataAssetId as string, citation.revisionId))] as const; }
      catch { return [citationSourceKey(citation), false] as const; }
    })).then((entries) => { if (active) setCitationSources(Object.fromEntries(entries)); });
    return () => { active = false; };
  }, [messages]);

  const conversationItems: ConversationItem[] = useMemo(() => conversations.map((item) => ({ id: item.id, title: item.title || '未命名会话' })), [conversations]);

  const setChatMessages = (items: ChatMessage[]) => setMessages(items);

  const send = async () => {
    const value = question.trim();
    if (!value || loading || streaming) return;
    setQuestion('');
    setLoading(true);
    const index = messages.length + 1;
    setMessages((current) => [...current, { id: `${Date.now()}-user`, role: 'USER', content: value }, { id: `${Date.now()}-assistant`, role: 'ASSISTANT', content: '' }]);
    try {
      setStreaming(true);
      let answer = '';
      await assistantApi.stream(
        value,
        conversationId,
        selectedScopes,
        scopes.filter((scope) => selectedScopes.includes(scope.id)).map((scope) => scope.scopeType),
        selectedKnowledge,
        selectedData,
        (token) => {
          answer += token;
          setMessages((current) => current.map((item, itemIndex) => itemIndex === index ? { ...item, content: answer } : item));
        },
        (response: AssistantResponse) => {
          setConversationId(response.conversationId);
          setStage('回答已生成');
          setMessages((current) => current.map((item, itemIndex) => itemIndex === index ? { ...item, content: response.answer || answer, citations: response.citations, warnings: response.warnings } : item));
          void assistantApi.conversations().then(setConversations).catch(() => undefined);
        },
        (event) => setStage(event === 'rewrite' ? '已完成查询改写' : event === 'retrieval' ? '已完成混合检索与重排' : event === 'citation' ? '引用来源已生成' : event),
      );
    } catch (error) {
      setMessages((current) => current.map((item, itemIndex) => itemIndex === index ? { ...item, content: error instanceof Error ? error.message : 'AI 问答失败', warnings: ['请检查模型网关配置或稍后重试'] } : item));
    } finally {
      setLoading(false);
      setStreaming(false);
    }
  };

  const reset = () => { setConversationId(undefined); setChatMessages([]); setQuestion(''); setStage('等待提问'); };

  const openConversation = async (id: string) => {
    setLoading(true);
    try {
      const conversation = await assistantApi.conversation(id);
      setConversationId(id);
      setMessages(conversation.messages.map((item, index) => ({ id: `${id}-${index}`, role: item.role === 'USER' ? 'USER' : 'ASSISTANT', content: item.content, citations: item.citations, warnings: item.warnings })));
    } catch (error) {
      void toast.error(error instanceof Error ? error.message : '会话加载失败');
    } finally {
      setLoading(false);
    }
  };

  const resolveCitationFile = async (citation: AssistantCitation): Promise<FilePreviewDescriptor> => {
    if (citation.documentId) {
      return {
        fileName: citation.originalName || citation.title || 'knowledge-document',
        load: () => knowledgeApi.contentBlob(citation.documentId as string, citation.versionId),
      };
    }
    if (citation.dataAssetId) {
      const source = await dataApi.resolveSourceFile(citation.dataAssetId, citation.revisionId);
      if (!source) throw new Error('该数据资产没有可用的原始文件');
      return { fileName: source.fileName, load: () => dataApi.sourceBlob(source.fileId) };
    }
    throw new Error('当前引用没有可定位的原始文件');
  };

  const previewCitation = async (citation: AssistantCitation) => {
    try { setPreviewFile(await resolveCitationFile(citation)); }
    catch (error) { void toast.error(error instanceof Error ? error.message : '原始文件加载失败'); }
  };

  const downloadCitation = async (citation: AssistantCitation) => {
    try { await downloadPreviewFile(await resolveCitationFile(citation)); void toast.success('原文件下载已开始'); }
    catch (error) { void toast.error(error instanceof Error ? error.message : '原文件下载失败'); }
  };

  const viewMessages: ConversationMessage[] = messages.map((item) => ({
    id: item.id,
    role: item.role,
    content: item.role === 'ASSISTANT' ? renderAssistantContent(item, navigate, streaming, (citation) => void previewCitation(citation), (citation) => void downloadCitation(citation), (citation) => Boolean(citation.documentId || citationSources[citationSourceKey(citation)])) : <Typography.Paragraph className="ai-message-text">{item.content}</Typography.Paragraph>,
  }));

  const scopeContent = (
    <Space direction="vertical" size={12} className="ai-scope-content">
      <Collapse ghost items={[
        {
          key: 'knowledge',
          label: <span><FolderOpenOutlined /> 研发知识库</span>,
          children: <Space direction="vertical" className="ai-scope-options">
            {knowledgeCategories.map((item) => <Checkbox key={item.id} checked={selectedKnowledge.includes(item.id)} onChange={(event) => setSelectedKnowledge((current) => event.target.checked ? [...current, item.id] : current.filter((id) => id !== item.id))}>{item.name}<Typography.Text type="secondary">（{item.documentCount}）</Typography.Text></Checkbox>)}
          </Space>,
        },
        {
          key: 'data',
          label: <span><DatabaseOutlined /> 数据中心</span>,
          children: <Space direction="vertical" className="ai-scope-options">
            {dataCategories.map((item) => <Checkbox key={item.id} checked={selectedData.includes(item.id)} onChange={(event) => setSelectedData((current) => event.target.checked ? [...current, item.id] : current.filter((id) => id !== item.id))}>{item.name}<Typography.Text type="secondary">（{item.assetCount}）</Typography.Text></Checkbox>)}
          </Space>,
        },
        {
          key: 'business',
          label: '项目与业务范围',
          children: <Space direction="vertical" className="ai-scope-options">
            {scopes.map((item) => <Checkbox key={item.id} checked={selectedScopes.includes(item.id)} onChange={(event) => setSelectedScopes((current) => event.target.checked ? [...current, item.id] : current.filter((id) => id !== item.id))}>{item.name}</Checkbox>)}
          </Space>,
        },
      ]} />
    </Space>
  );

  return (
    <div className="business-page assistant-page">
      <div className="page-heading"><div><Typography.Title level={2}>AI问答</Typography.Title><Typography.Text type="secondary">回答仅基于已授权的研发资料和正式数据资产。</Typography.Text></div><Tag color={streaming ? 'processing' : 'default'}>{stage}</Tag></div>
      <AiConversationWorkspace
        conversations={conversationItems}
        activeConversationId={conversationId}
        messages={viewMessages}
        scopeContent={scopeContent}
        scopeSummary={<Typography.Text type="secondary">已选择 {selectedKnowledge.length + selectedData.length + selectedScopes.length} 个检索范围</Typography.Text>}
        question={question}
        loading={loading}
        streaming={streaming}
        onNewConversation={reset}
        onSelectConversation={(id) => void openConversation(id)}
        onQuestionChange={setQuestion}
        onSubmit={() => void send()}
      />
      <FilePreviewModal open={Boolean(previewFile)} file={previewFile} onClose={() => setPreviewFile(undefined)} />
    </div>
  );
}
