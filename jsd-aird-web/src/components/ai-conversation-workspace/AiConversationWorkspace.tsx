import {
  DeleteOutlined,
  DownOutlined,
  EditOutlined,
  CloseOutlined,
  MenuOutlined,
  MessageOutlined,
  PlusOutlined,
  RobotOutlined,
  SendOutlined,
} from '@ant-design/icons';
import { Button, Empty, Input, List, Modal, Popconfirm, Space, Spin, Typography } from 'antd';
import { useCallback, useEffect, useLayoutEffect, useRef, useState, type ReactNode } from 'react';

export interface ConversationItem {
  id: string;
  title: string;
  updatedAt?: string;
}

export interface ConversationMessage {
  id: string;
  role: 'USER' | 'ASSISTANT';
  content: ReactNode;
  editableText?: string;
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
  composerActions?: ReactNode;
  assistantLabel?: string;
  submitDisabled?: boolean;
  pendingLabel?: string;
  scopeTitle?: string;
  welcomeTitle?: string;
  welcomeDescription?: string;
  welcomeContent?: ReactNode;
  onNewConversation: () => void;
  onSelectConversation: (id: string) => void;
  onRenameConversation?: (item: ConversationItem, title: string) => void | Promise<void>;
  onDeleteConversation?: (item: ConversationItem) => void | Promise<void>;
  onEditMessage?: (item: ConversationMessage, value: string) => void;
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
  composerActions,
  assistantLabel = 'AI研发助手',
  submitDisabled = false,
  pendingLabel = '正在阅读资料并整理依据',
  scopeTitle = '选择检索范围',
  welcomeTitle = '从研发资料开始提问',
  welcomeDescription = '选择左侧资料范围，输入问题后开始受控检索和问答。',
  welcomeContent,
  onNewConversation,
  onSelectConversation,
  onRenameConversation,
  onDeleteConversation,
  onEditMessage,
  onQuestionChange,
  onSubmit,
}: AiConversationWorkspaceProps) {
  const [editingConversation, setEditingConversation] = useState<ConversationItem>();
  const [editingTitle, setEditingTitle] = useState('');
  const [renameLoading, setRenameLoading] = useState(false);
  const [sideOpen, setSideOpen] = useState(false);
  const [editingMessage, setEditingMessage] = useState<ConversationMessage>();
  const [editingValue, setEditingValue] = useState('');
  const [confirmEditOpen, setConfirmEditOpen] = useState(false);
  const [showJumpToLatest, setShowJumpToLatest] = useState(false);
  const messagesRef = useRef<HTMLDivElement>(null);
  const followLatestRef = useRef(true);
  const activeConversationRef = useRef<string>();

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

  const isNearBottom = useCallback(() => {
    const element = messagesRef.current;
    if (!element) return true;
    return element.scrollHeight - element.scrollTop - element.clientHeight <= 64;
  }, []);

  const scrollToLatest = useCallback((behavior: ScrollBehavior = 'smooth') => {
    const element = messagesRef.current;
    if (!element) return;
    followLatestRef.current = true;
    if (typeof element.scrollTo === 'function') {
      element.scrollTo({ top: element.scrollHeight, behavior });
    } else {
      element.scrollTop = element.scrollHeight;
    }
    setShowJumpToLatest(false);
  }, []);

  useLayoutEffect(() => {
    if (activeConversationRef.current !== activeConversationId) {
      activeConversationRef.current = activeConversationId;
      followLatestRef.current = true;
      scrollToLatest('auto');
      return;
    }
    if (followLatestRef.current) {
      scrollToLatest('auto');
    } else if (messages.length) {
      setShowJumpToLatest(!isNearBottom());
    }
  }, [activeConversationId, isNearBottom, messages, scrollToLatest]);

  useEffect(() => {
    if (!messages.length) {
      followLatestRef.current = true;
      setShowJumpToLatest(false);
    }
  }, [messages.length]);

  const startEditingMessage = (item: ConversationMessage) => {
    if (!item.editableText || loading || streaming || !onEditMessage) return;
    setEditingMessage(item);
    setEditingValue(item.editableText);
  };

  const cancelEditingMessage = () => {
    setEditingMessage(undefined);
    setEditingValue('');
    setConfirmEditOpen(false);
  };

  const confirmMessageReplacement = () => {
    const item = editingMessage;
    const value = editingValue.trim();
    if (!item || !value || !onEditMessage) return;
    setConfirmEditOpen(false);
    setEditingMessage(undefined);
    setEditingValue('');
    onEditMessage(item, value);
  };

  const editedMessageIndex = editingMessage
    ? messages.findIndex((item) => item.id === editingMessage.id)
    : -1;
  const followingMessageCount = editedMessageIndex < 0 ? 0 : messages.length - editedMessageIndex - 1;

  return (
    <div className={`ai-conversation-workspace${sideOpen ? ' is-side-open' : ''}`}>
      <aside className="ai-conversation-side">
        <div className="ai-conversation-side-head">
          <Button
            type="primary"
            size="large"
            icon={<PlusOutlined />}
            block
            onClick={() => {
              onNewConversation();
              setSideOpen(false);
            }}
          >
            新建对话
          </Button>
          <Button
            className="ai-conversation-side-close"
            type="text"
            icon={<CloseOutlined />}
            aria-label="关闭对话与范围面板"
            onClick={() => setSideOpen(false)}
          />
        </div>
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
                  onClick={() => {
                    onSelectConversation(item.id);
                    setSideOpen(false);
                  }}
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
        <div className="ai-conversation-scope">
          {scopeContent}
        </div>
      </aside>
      <button
        className="ai-conversation-scrim"
        type="button"
        aria-label="关闭对话与范围面板"
        onClick={() => setSideOpen(false)}
      />
      <main className="ai-conversation-main">
        <div className="ai-conversation-mobile-bar">
          <Button type="text" icon={<MenuOutlined />} onClick={() => setSideOpen(true)}>
            对话与范围
          </Button>
        </div>
        <div
          ref={messagesRef}
          className="ai-conversation-messages"
          aria-live="polite"
          onScroll={() => {
            const nearBottom = isNearBottom();
            followLatestRef.current = nearBottom;
            setShowJumpToLatest(!nearBottom && messages.length > 0);
          }}
        >
          {!messages.length && !loading && (
            <div className="ai-conversation-welcome">
              <span className="ai-conversation-welcome-mark" aria-hidden="true">
                <RobotOutlined />
              </span>
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
            renderItem={(item) => {
              const messageBody = item.pending ? (
                <ThinkingIndicator label={pendingLabel} />
              ) : (
                item.content ||
                (streaming && item.role === 'ASSISTANT' ? (
                  <ThinkingIndicator label={pendingLabel} />
                ) : null)
              );

              return (
                <List.Item className={`ai-message ai-message-${item.role.toLowerCase()}`}>
                  <div className="ai-message-content">
                    {item.role === 'ASSISTANT' ? (
                      <>
                        <div className="ai-message-assistant-heading">
                          <span className="ai-message-assistant-icon" aria-hidden="true">
                            ✦
                          </span>
                          <Typography.Text strong className="ai-message-meta">
                            {assistantLabel}
                          </Typography.Text>
                        </div>
                        <div className="ai-message-body ai-message-assistant-body">
                          {messageBody}
                        </div>
                      </>
                    ) : editingMessage?.id === item.id ? (
                      <div className="ai-message-editor">
                        <Input.TextArea
                          autoFocus
                          aria-label="编辑已发送的问题"
                          autoSize={{ minRows: 2, maxRows: 8 }}
                          maxLength={2000}
                          showCount
                          value={editingValue}
                          onChange={(event) => setEditingValue(event.target.value)}
                          onPressEnter={(event) => {
                            if (!event.shiftKey) {
                              event.preventDefault();
                              if (editingValue.trim()) setConfirmEditOpen(true);
                            }
                          }}
                        />
                        <Space size={8} className="ai-message-editor-actions">
                          <Button size="small" onClick={cancelEditingMessage}>
                            取消
                          </Button>
                          <Button
                            size="small"
                            type="primary"
                            disabled={!editingValue.trim()}
                            onClick={() => setConfirmEditOpen(true)}
                          >
                            保存并重新发送
                          </Button>
                        </Space>
                      </div>
                    ) : (
                      <>
                        <div className="ai-message-user-heading">
                          <Typography.Text strong className="ai-message-meta">
                            你
                          </Typography.Text>
                          {item.editableText && onEditMessage ? (
                            <Button
                              className="ai-message-edit-button"
                              type="text"
                              size="small"
                              icon={<EditOutlined />}
                              aria-label="编辑并重新发送这条问题"
                              disabled={Boolean(loading || streaming)}
                              onClick={() => startEditingMessage(item)}
                            >
                              编辑
                            </Button>
                          ) : null}
                        </div>
                        <div className="ai-message-body">{messageBody}</div>
                      </>
                    )}
                  </div>
                </List.Item>
              );
            }}
          />
        </div>
        {showJumpToLatest ? (
          <Button
            className="ai-conversation-jump-latest"
            shape="circle"
            icon={<DownOutlined />}
            aria-label="跳到最新消息"
            title="跳到最新消息"
            onClick={() => scrollToLatest('smooth')}
          />
        ) : null}
        <div className="ai-conversation-composer">
          {composerTopContent || composerActions ? (
            <div className="ai-conversation-composer-top">
              <div className="ai-conversation-composer-context">{composerTopContent}</div>
              {composerActions ? <div className="ai-conversation-composer-actions">{composerActions}</div> : null}
            </div>
          ) : null}
          <div className="ai-conversation-composer-row">
            <Input.TextArea
              aria-label="输入问题"
              autoSize={{ minRows: 3, maxRows: 6 }}
              maxLength={2000}
              showCount={{ formatter: ({ count, maxLength }) => `${count}/${maxLength || 2000}` }}
              placeholder="输入你的问题…"
              value={question}
              onChange={(event) => onQuestionChange(event.target.value)}
              onPressEnter={(event) => {
                if (!event.shiftKey) {
                  event.preventDefault();
                  if (question.trim() && !loading && !streaming && !submitDisabled) onSubmit();
                }
              }}
            />
            <Button
              type="primary"
              size="large"
              icon={<SendOutlined />}
              aria-label="发送问题"
              loading={streaming}
              disabled={!question.trim() || loading || streaming || submitDisabled}
              onClick={onSubmit}
            />
          </div>
        </div>
      </main>
      <Modal
        title="确认编辑并重新发送？"
        open={confirmEditOpen}
        okText="替换并重新发送"
        cancelText="继续编辑"
        okButtonProps={{ danger: true, disabled: !editingValue.trim() }}
        onCancel={() => setConfirmEditOpen(false)}
        onOk={confirmMessageReplacement}
      >
        <Typography.Paragraph>
          当前问题将被修改，它原来的回答以及后续 {followingMessageCount} 条消息将从本会话中删除。
        </Typography.Paragraph>
        <Typography.Text type="secondary">此操作不会删除已有的模型调用审计记录。</Typography.Text>
      </Modal>
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
