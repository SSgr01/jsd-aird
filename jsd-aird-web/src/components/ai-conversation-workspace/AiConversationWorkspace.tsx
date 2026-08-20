import {
  DeleteOutlined,
  EditOutlined,
  MessageOutlined,
  PlusOutlined,
  SendOutlined,
} from '@ant-design/icons';
import { Avatar, Button, Empty, Input, List, Modal, Popconfirm, Space, Spin, Typography } from 'antd';
import { useState, type ReactNode } from 'react';

export interface ConversationItem {
  id: string;
  title: string;
  updatedAt?: string;
}

export interface ConversationMessage {
  id: string;
  role: 'USER' | 'ASSISTANT';
  content: ReactNode;
  pending?: boolean;
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
  composerTopContent?: ReactNode;
  pendingLabel?: string;
  scopeTitle?: string;
  welcomeTitle?: string;
  welcomeDescription?: string;
  welcomeContent?: ReactNode;
  onNewConversation: () => void;
  onSelectConversation: (id: string) => void;
  onRenameConversation?: (item: ConversationItem, title: string) => void | Promise<void>;
  onDeleteConversation?: (item: ConversationItem) => void | Promise<void>;
  onQuestionChange: (value: string) => void;
  onSubmit: () => void;
}

function ThinkingIndicator({ label }: { label: string }) {
  return (
    <div className="ai-thinking" role="status" aria-label="AI 正在思考">
      <span className="ai-thinking-bars" aria-hidden="true">
        <i />
        <i />
        <i />
      </span>
      <span className="ai-thinking-label">正在思考</span>
      <span className="ai-thinking-detail">{label}</span>
    </div>
  );
}

export function AiConversationWorkspace({
  conversations,
  activeConversationId,
  messages,
  scopeContent,
  question,
  loading,
  streaming,
  scopeSummary,
  composerTopContent,
  pendingLabel = '正在阅读资料并整理依据',
  scopeTitle = '选择同步数据范围',
  welcomeTitle = '从研发资料开始提问',
  welcomeDescription = '选择左侧资料范围，输入问题后开始受控检索和问答。',
  welcomeContent,
  onNewConversation,
  onSelectConversation,
  onRenameConversation,
  onDeleteConversation,
  onQuestionChange,
  onSubmit,
}: AiConversationWorkspaceProps) {
  const [editingConversation, setEditingConversation] = useState<ConversationItem>();
  const [editingTitle, setEditingTitle] = useState('');
  const [renameLoading, setRenameLoading] = useState(false);

  const openRename = (item: ConversationItem) => {
    setEditingConversation(item);
    setEditingTitle(item.title);
  };

  const confirmRename = async () => {
    const item = editingConversation;
    const title = editingTitle.trim();
    if (!item || !title || !onRenameConversation) return;
    setRenameLoading(true);
    try {
      await onRenameConversation(item, title);
      setEditingConversation(undefined);
    } finally {
      setRenameLoading(false);
    }
  };

  return (
    <div className="ai-conversation-workspace">
      <aside className="ai-conversation-side">
        <Button
          type="primary"
          size="large"
          icon={<PlusOutlined />}
          block
          onClick={onNewConversation}
        >
          新建对话
        </Button>
        <Typography.Text strong className="ai-conversation-section-title">
          最近对话
        </Typography.Text>
        <div className="ai-conversation-history">
          {conversations.length ? (
            <List
              className="ai-conversation-list"
              dataSource={conversations}
              renderItem={(item) => (
                <List.Item
                  className={item.id === activeConversationId ? 'is-active' : ''}
                  onClick={() => onSelectConversation(item.id)}
                >
                  <MessageOutlined />
                  <span className="ai-conversation-name" title={item.title}>
                    {item.title}
                  </span>
                  {item.updatedAt && (
                    <Typography.Text type="secondary" className="ai-conversation-date">
                      {new Date(item.updatedAt).toLocaleDateString('zh-CN')}
                    </Typography.Text>
                  )}
                  <Space size={0} className="ai-conversation-actions">
                    {onRenameConversation && (
                      <Button
                        type="text"
                        size="small"
                        icon={<EditOutlined />}
                        aria-label={`重命名对话${item.title}`}
                        onClick={(event) => {
                          event.stopPropagation();
                          openRename(item);
                        }}
                      />
                    )}
                    {onDeleteConversation && (
                      <Popconfirm
                        title={`确认删除“${item.title}”？`}
                        description="删除后对话内容将一并移除。"
                        okText="删除"
                        cancelText="取消"
                        onConfirm={() => {
                          void Promise.resolve(onDeleteConversation(item)).catch(() => undefined);
                        }}
                      >
                        <Button
                          type="text"
                          danger
                          size="small"
                          icon={<DeleteOutlined />}
                          aria-label={`删除对话${item.title}`}
                          onClick={(event) => event.stopPropagation()}
                        />
                      </Popconfirm>
                    )}
                  </Space>
                </List.Item>
              )}
            />
          ) : (
            <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无历史对话" />
          )}
        </div>
        <Typography.Text strong className="ai-conversation-section-title">
          {scopeTitle}
        </Typography.Text>
        <div className="ai-conversation-scope">{scopeContent}</div>
      </aside>
      <main className="ai-conversation-main">
        <div className="ai-conversation-messages" aria-live="polite">
          {!messages.length && !loading && (
            <div className="ai-conversation-welcome">
              <Avatar
                size={64}
                className="ai-conversation-welcome-avatar"
                icon={<MessageOutlined />}
              />
              <Typography.Title level={3}>{welcomeTitle}</Typography.Title>
              <Typography.Paragraph type="secondary">{welcomeDescription}</Typography.Paragraph>
              {scopeSummary}
              {welcomeContent}
            </div>
          )}
          {loading && !messages.length && (
            <div className="ai-conversation-state">
              <Spin />
              <Typography.Text type="secondary">正在加载会话…</Typography.Text>
            </div>
          )}
          <List
            split={false}
            dataSource={messages}
            renderItem={(item) => (
              <List.Item className={`ai-message ai-message-${item.role.toLowerCase()}`}>
                <Avatar
                  className="ai-message-avatar"
                  icon={item.role === 'USER' ? undefined : <MessageOutlined />}
                >
                  {item.role === 'USER' ? '我' : undefined}
                </Avatar>
                <div className="ai-message-content">
                   <Typography.Text strong className="ai-message-meta">
                    {item.role === 'USER' ? '你' : 'AI研发助手'}
                  </Typography.Text>
                  <div className="ai-message-body">
                    {item.pending ? (
                      <ThinkingIndicator label={pendingLabel} />
                    ) : (
                      item.content ||
                      (streaming && item.role === 'ASSISTANT' ? (
                        <ThinkingIndicator label={pendingLabel} />
                      ) : null)
                    )}
                  </div>
                </div>
              </List.Item>
            )}
          />
        </div>
        <div className="ai-conversation-composer">
          {composerTopContent ? (
            <div className="ai-conversation-composer-top">{composerTopContent}</div>
          ) : null}
          <div className="ai-conversation-composer-row">
            <Input.TextArea
              aria-label="输入问题"
              autoSize={{ minRows: 2, maxRows: 6 }}
              placeholder="输入你的问题…"
              value={question}
              onChange={(event) => onQuestionChange(event.target.value)}
              onPressEnter={(event) => {
                if (!event.shiftKey) {
                  event.preventDefault();
                  onSubmit();
                }
              }}
            />
            <Button
              type="primary"
              size="large"
              icon={<SendOutlined />}
              aria-label="发送问题"
              loading={streaming}
              disabled={!question.trim() || loading || streaming}
              onClick={onSubmit}
            />
          </div>
        </div>
       </main>
       <Modal
         title="编辑会话标题"
         open={Boolean(editingConversation)}
         okText="保存"
         cancelText="取消"
         confirmLoading={renameLoading}
         okButtonProps={{ disabled: !editingTitle.trim() || editingTitle.trim().length > 80 }}
         onCancel={() => setEditingConversation(undefined)}
         onOk={() => void confirmRename()}
       >
         <Input
           autoFocus
           maxLength={80}
           showCount
           value={editingTitle}
           placeholder="输入会话标题"
           onChange={(event) => setEditingTitle(event.target.value)}
           onPressEnter={() => void confirmRename()}
         />
       </Modal>
     </div>
  );
}
