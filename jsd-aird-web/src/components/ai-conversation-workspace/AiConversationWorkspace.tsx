import { DeleteOutlined, EditOutlined, MessageOutlined, PlusOutlined, SendOutlined } from '@ant-design/icons';
import { Avatar, Button, Empty, Input, List, Space, Spin, Typography } from 'antd';
import type { ReactNode } from 'react';

export interface ConversationItem {
  id: string;
  title: string;
  updatedAt?: string;
}

export interface ConversationMessage {
  id: string;
  role: 'USER' | 'ASSISTANT';
  content: ReactNode;
}

interface AiConversationWorkspaceProps {
  conversations: ConversationItem[];
  activeConversationId?: string;
  messages: ConversationMessage[];
  scopeContent: ReactNode;
  question: string;
  loading?: boolean;
  streaming?: boolean;
  scopeSummary?: ReactNode;
  onNewConversation: () => void;
  onSelectConversation: (id: string) => void;
  onRenameConversation?: (item: ConversationItem) => void;
  onDeleteConversation?: (item: ConversationItem) => void;
  onQuestionChange: (value: string) => void;
  onSubmit: () => void;
}

export function AiConversationWorkspace({ conversations, activeConversationId, messages, scopeContent, question, loading, streaming, scopeSummary, onNewConversation, onSelectConversation, onRenameConversation, onDeleteConversation, onQuestionChange, onSubmit }: AiConversationWorkspaceProps) {
  return (
    <div className="ai-conversation-workspace">
      <aside className="ai-conversation-side">
        <Button type="primary" size="large" icon={<PlusOutlined />} block onClick={onNewConversation}>新建对话</Button>
        <Typography.Text strong className="ai-conversation-section-title">最近对话</Typography.Text>
        {conversations.length ? <List
          className="ai-conversation-list"
          dataSource={conversations}
          renderItem={(item) => <List.Item className={item.id === activeConversationId ? 'is-active' : ''} onClick={() => onSelectConversation(item.id)}>
            <MessageOutlined /><span className="ai-conversation-name" title={item.title}>{item.title}</span>
            {item.updatedAt && <Typography.Text type="secondary">{new Date(item.updatedAt).toLocaleDateString('zh-CN')}</Typography.Text>}
            <Space size={0} className="ai-conversation-actions">
              {onRenameConversation && <Button type="text" size="small" icon={<EditOutlined />} aria-label={`重命名对话${item.title}`} onClick={(event) => { event.stopPropagation(); onRenameConversation(item); }} />}
              {onDeleteConversation && <Button type="text" danger size="small" icon={<DeleteOutlined />} aria-label={`删除对话${item.title}`} onClick={(event) => { event.stopPropagation(); onDeleteConversation(item); }} />}
            </Space>
          </List.Item>}
        /> : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无历史对话" />}
        <Typography.Text strong className="ai-conversation-section-title">选择同步数据范围</Typography.Text>
        <div className="ai-conversation-scope">{scopeContent}</div>
      </aside>
      <main className="ai-conversation-main">
        <div className="ai-conversation-messages" aria-live="polite">
          {!messages.length && !loading && <div className="ai-conversation-welcome"><Avatar size={64} className="ai-conversation-welcome-avatar" icon={<MessageOutlined />} /><Typography.Title level={3}>从研发资料开始提问</Typography.Title><Typography.Paragraph type="secondary">选择左侧资料范围，输入问题后开始受控检索和问答。</Typography.Paragraph>{scopeSummary}</div>}
          {loading && !messages.length && <div className="ai-conversation-state"><Spin /><Typography.Text type="secondary">正在加载会话…</Typography.Text></div>}
          <List split={false} dataSource={messages} renderItem={(item) => <List.Item className={`ai-message ai-message-${item.role.toLowerCase()}`}>
            <Avatar className="ai-message-avatar" icon={item.role === 'USER' ? undefined : <MessageOutlined />}>{item.role === 'USER' ? '我' : undefined}</Avatar>
            <div className="ai-message-content"><Typography.Text strong>{item.role === 'USER' ? '你' : 'AI研发助手'}</Typography.Text><div className="ai-message-body">{item.content || (streaming ? <Spin size="small" /> : null)}</div></div>
          </List.Item>} />
        </div>
        <div className="ai-conversation-composer">
          <Input.TextArea aria-label="输入问题" autoSize={{ minRows: 2, maxRows: 6 }} placeholder="输入你的问题…" value={question} onChange={(event) => onQuestionChange(event.target.value)} onPressEnter={(event) => { if (!event.shiftKey) { event.preventDefault(); onSubmit(); } }} />
          <Button type="primary" size="large" icon={<SendOutlined />} aria-label="发送问题" loading={streaming} disabled={!question.trim() || loading} onClick={onSubmit} />
        </div>
      </main>
    </div>
  );
}
