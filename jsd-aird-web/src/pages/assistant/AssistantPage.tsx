import { ClearOutlined, SendOutlined, RobotOutlined } from '@ant-design/icons';
import { Alert, Avatar, Button, Card, Divider, Input, List, Select, Space, Spin, Tag, Typography } from 'antd';
import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';

import { assistantApi, type AssistantCitation, type AssistantResponse, type ConversationMeta } from '@/services/assistant';

interface ChatMessage {
  role: 'USER' | 'ASSISTANT';
  content: string;
  citations?: AssistantCitation[];
  warnings?: string[];
}

export function AssistantPage() {
  const navigate = useNavigate();
  const [question, setQuestion] = useState('');
  const [conversationId, setConversationId] = useState<string>();
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [loading, setLoading] = useState(false);
  const [streaming, setStreaming] = useState(false);
  const [scopes, setScopes] = useState<Array<{ id: string; scopeType: string; name: string }>>([]);
  const [selectedScopes, setSelectedScopes] = useState<string[]>([]);
  const [stage, setStage] = useState('等待提问');
  const [conversations, setConversations] = useState<ConversationMeta[]>([]);

  useEffect(() => {
    void assistantApi.scopes().then(setScopes).catch(() => setScopes([]));
    void assistantApi.conversations().then(setConversations).catch(() => setConversations([]));
  }, []);

  const send = async () => {
    const value = question.trim();
    if (!value || loading || streaming) return;
    setQuestion('');
    setLoading(true);
    const index = messages.length + 1;
    setMessages((current) => [...current, { role: 'USER', content: value }, { role: 'ASSISTANT', content: '' }]);
    try {
      setStreaming(true);
      let answer = '';
      await assistantApi.stream(value, conversationId, selectedScopes,
        scopes.filter((scope) => selectedScopes.includes(scope.id)).map((scope) => scope.scopeType), (token) => {
        answer += token;
        setMessages((current) => current.map((item, itemIndex) => itemIndex === index ? { ...item, content: answer } : item));
      }, (response: AssistantResponse) => {
        setConversationId(response.conversationId);
        void assistantApi.conversations().then(setConversations).catch(() => undefined);
        setMessages((current) => current.map((item, itemIndex) => itemIndex === index ? { ...item, content: response.answer || answer, citations: response.citations, warnings: response.warnings } : item));
      }, (event) => {
        setStage(event === 'rewrite' ? '已完成查询改写' : event === 'retrieval' ? '已完成混合检索与重排' : event === 'citation' ? '引用来源已生成' : event);
      });
    } catch (error) {
      setMessages((current) => current.map((item, itemIndex) => itemIndex === index ? { ...item, content: error instanceof Error ? error.message : 'AI 问答失败', warnings: ['请检查模型网关配置或稍后重试'] } : item));
    } finally { setLoading(false); setStreaming(false); }
  };

  const reset = () => { setConversationId(undefined); setMessages([]); setQuestion(''); setStage('等待提问'); };
  const openConversation = async (id: string) => {
    const conversation = await assistantApi.conversation(id);
    setConversationId(id);
    setMessages(conversation.messages.map((message) => ({ role: message.role as ChatMessage['role'], content: message.content, citations: message.citations, warnings: message.warnings })));
  };
  return <div className="business-page">
    <div className="page-heading"><div><Typography.Title level={2}>AI 研发助手</Typography.Title><Typography.Text type="secondary">回答仅基于已授权知识库内容；需要最新公开信息时可通过 Tavily 受控检索。</Typography.Text></div><Button icon={<ClearOutlined />} onClick={reset}>新建会话</Button></div>
    <Card className="content-card" bodyStyle={{ paddingBottom: 8 }}>
      <div style={{ display: 'flex', gap: 12, alignItems: 'center', marginBottom: 12 }}>
        <Select allowClear placeholder="历史会话" style={{ width: 220 }} value={conversationId} onChange={(id) => { if (id) void openConversation(id); }} options={conversations.map((item) => ({ value: item.id, label: item.title || '未命名会话' }))} />
        <Typography.Text strong>检索范围</Typography.Text>
        <Select mode="multiple" allowClear placeholder="选择项目、产品、知识库或数据资产" style={{ flex: 1 }} value={selectedScopes} onChange={setSelectedScopes} options={scopes.map((scope) => ({ value: scope.id, label: `${scope.name} · ${scope.scopeType}` }))} />
        <Tag color={streaming ? 'processing' : 'default'}>{stage}</Tag>
      </div>
      {!messages.length && <div style={{ padding: '48px 12px', textAlign: 'center' }}><Avatar size={64} icon={<RobotOutlined />} style={{ background: '#dbeafe', color: '#2563eb' }} /><Typography.Title level={3}>从研发资料开始提问</Typography.Title><Typography.Text type="secondary">例如：某材料的推荐测试条件是什么？引用会显示在答案下方。</Typography.Text></div>}
      <List dataSource={messages} split={false} renderItem={(item) => <List.Item style={{ alignItems: 'flex-start', padding: '18px 4px' }}><Space align="start" style={{ width: '100%' }}><Avatar icon={item.role === 'USER' ? '我' : <RobotOutlined />} style={{ background: item.role === 'USER' ? '#0f766e' : '#2563eb' }} /><div style={{ flex: 1, minWidth: 0 }}><Typography.Text strong>{item.role === 'USER' ? '你' : 'AI 研发助手'}</Typography.Text><Typography.Paragraph style={{ whiteSpace: 'pre-wrap', marginTop: 8 }}>{item.content || (streaming ? <Spin size="small" /> : '')}</Typography.Paragraph>{item.warnings?.map((warning) => <Alert key={warning} type="warning" showIcon message={warning} style={{ marginBottom: 8 }} />)}{item.citations?.length ? <div><Typography.Text type="secondary">参考来源</Typography.Text><Space wrap style={{ marginTop: 6 }}>{item.citations.map((citation) => <Tag key={`${citation.sourceType}-${citation.chunkId}`} color="blue" style={{ cursor: citation.documentId ? 'pointer' : 'default' }} onClick={() => { if (citation.documentId) navigate(`/knowledge/documents/${citation.documentId}`); }}>{citation.title || citation.dataAssetId || '数据资产来源'}{citation.pageNo ? ` · 第${citation.pageNo}页` : ''}</Tag>)}</Space></div> : null}</div></Space></List.Item>} />
      <Divider />
      <Input.TextArea value={question} onChange={(event) => setQuestion(event.target.value)} onPressEnter={(event) => { if (!event.shiftKey) { event.preventDefault(); void send(); } }} placeholder="输入研发问题，Enter 发送，Shift+Enter 换行" autoSize={{ minRows: 2, maxRows: 6 }} disabled={loading || streaming} />
      <div style={{ display: 'flex', justifyContent: 'space-between', marginTop: 10 }}><Typography.Text type="secondary">未授权文件不会进入 AI 上下文。</Typography.Text><Button type="primary" icon={<SendOutlined />} loading={loading || streaming} disabled={!question.trim()} onClick={() => void send()}>发送问题</Button></div>
    </Card>
  </div>;
}
