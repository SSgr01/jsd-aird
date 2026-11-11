import {
  DeleteOutlined,
  DownOutlined,
  EditOutlined,
  CloseOutlined,
  MenuOutlined,
  MessageOutlined,
  PlusOutlined,
  RobotOutlined,
  SearchOutlined,
  SendOutlined,
} from '@ant-design/icons';
import { Button, Drawer, Empty, Input, List, Modal, Popconfirm, Space, Typography } from 'antd';
import { ConversationFeedback } from './ConversationFeedback';
import {
  ConversationIcon,
  ConversationIconButton,
  CopyAnswerButton,
} from './ConversationIconButton';
import type { TextAreaRef } from 'antd/es/input/TextArea';
import './conversation-workspace.css';
import {
  useCallback,
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
  type ReactNode,
  type Ref,
} from 'react';

export interface ConversationItem {
  id: string;
  title: string;
  summary?: string;
  updatedAt?: string;
}

export interface ConversationMessage {
  id: string;
  role: 'USER' | 'ASSISTANT';
  content: ReactNode;
  editableText?: string;
  pending?: boolean;
  copyText?: string;
}

interface AiConversationWorkspaceProps {
  conversations: ConversationItem[];
  activeConversationId?: string;
  messages: ConversationMessage[];
  /** Opt-in reading position for a newly published long plan; normal chat still follows latest. */
  initialScrollMessageId?: string;
  scopeContent?: ReactNode;
  scopePlacement?: 'sidebar' | 'composer';
  question: string;
  loading?: boolean;
  streaming?: boolean;
  scopeSummary?: ReactNode;
  composerTopContent?: ReactNode;
  composerActions?: ReactNode;
  compactComposer?: boolean;
  submitAction?: ReactNode;
  inputDisabled?: boolean;
  inputRef?: Ref<TextAreaRef>;
  assistantLabel?: string;
  submitDisabled?: boolean;
  canCreate?: boolean;
  pendingLabel?: string;
  scopeTitle?: string;
  welcomeTitle?: string;
  welcomeDescription?: string;
  welcomeContent?: ReactNode;
  welcomeMark?: ReactNode;
  searchableHistory?: boolean;
  historyTitle?: string;
  scopeButtonText?: string;
  inputLabel?: string;
  inputPlaceholder?: string;
  inputMaxLength?: number;
  submitLabel?: string;
  navigationDisabled?: boolean;
  hideEmptyList?: boolean;
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
  initialScrollMessageId,
  scopeContent,
  scopePlacement = 'sidebar',
  question,
  loading,
  streaming,
  scopeSummary,
  composerTopContent,
  composerActions,
  compactComposer = false,
  submitAction,
  inputDisabled = false,
  inputRef,
  assistantLabel = 'AI研发助手',
  submitDisabled = false,
  canCreate = true,
  pendingLabel = '正在阅读资料并整理依据',
  scopeTitle = '选择检索范围',
  welcomeTitle = '从研发资料开始提问',
  welcomeDescription = '提出问题，我会结合所选资料帮你解答。',
  welcomeContent,
  welcomeMark,
  searchableHistory = false,
  historyTitle = '最近对话',
  scopeButtonText,
  inputLabel = '输入问题',
  inputPlaceholder = '输入你的问题…',
  inputMaxLength = 2000,
  submitLabel = '发送问题',
  navigationDisabled = false,
  hideEmptyList = false,
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
  const [deletingConversation, setDeletingConversation] = useState<ConversationItem>();
  const [deleteLoading, setDeleteLoading] = useState(false);
  const [sideOpen, setSideOpen] = useState(false);
  const [scopeOpen, setScopeOpen] = useState(false);
  const [editingMessage, setEditingMessage] = useState<ConversationMessage>();
  const [editingValue, setEditingValue] = useState('');
  const [confirmEditOpen, setConfirmEditOpen] = useState(false);
  const [showJumpToLatest, setShowJumpToLatest] = useState(false);
  const [historyQuery, setHistoryQuery] = useState('');
  const historySearch = historyQuery.trim().toLocaleLowerCase();
  const visibleConversations =
    searchableHistory && historySearch
      ? conversations.filter((item) => item.title.toLocaleLowerCase().includes(historySearch))
      : conversations;
  const messagesRef = useRef<HTMLDivElement>(null);
  const composerRef = useRef<HTMLDivElement>(null);
  const customSubmit = Boolean(submitAction);
  const followLatestRef = useRef(true);
  const followingFrameRef = useRef<number>();
  const activeConversationRef = useRef<string>();
  const readingAnchorRef = useRef<string>();

  useLayoutEffect(() => {
    if (!customSubmit) return;
    const composer = composerRef.current;
    if (!composer) return;
    const update = () =>
      composer.parentElement?.style.setProperty(
        '--ai-composer-height',
        `${composer.getBoundingClientRect().height}px`,
      );
    update();
    if (typeof ResizeObserver === 'undefined') return;
    const observer = new ResizeObserver(update);
    observer.observe(composer);
    return () => observer.disconnect();
  }, [customSubmit]);

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

  const confirmDelete = async () => {
    if (!deletingConversation || !onDeleteConversation || deleteLoading) return;
    setDeleteLoading(true);
    try {
      await onDeleteConversation(deletingConversation);
      setDeletingConversation(undefined);
    } catch {
      // The caller reports the request error; keep this confirmation available for retry.
    } finally {
      setDeleteLoading(false);
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
    const latestTop = element.scrollHeight;
    if (latestTop - element.scrollTop - element.clientHeight <= 1) {
      setShowJumpToLatest(false);
      return;
    }
    if (typeof element.scrollTo === 'function') {
      element.scrollTo({ top: latestTop, behavior });
    } else {
      element.scrollTop = latestTop;
    }
    setShowJumpToLatest(false);
  }, []);

  useLayoutEffect(() => {
    if (hideEmptyList && !messages.length) {
      if (messagesRef.current) messagesRef.current.scrollTop = 0;
      followLatestRef.current = true;
      setShowJumpToLatest(false);
      return;
    }
    if (
      initialScrollMessageId &&
      (readingAnchorRef.current !== initialScrollMessageId ||
        activeConversationRef.current !== activeConversationId)
    ) {
      const container = messagesRef.current;
      const anchor = Array.from(
        container?.querySelectorAll<HTMLElement>('[data-message-id]') || [],
      ).find((element) => element.dataset.messageId === initialScrollMessageId);
      if (container && anchor) {
        readingAnchorRef.current = initialScrollMessageId;
        activeConversationRef.current = activeConversationId;
        followLatestRef.current = false;
        container.scrollTop +=
          anchor.getBoundingClientRect().top - container.getBoundingClientRect().top;
        setShowJumpToLatest(!isNearBottom());
        return;
      }
    }
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
  }, [
    activeConversationId,
    hideEmptyList,
    initialScrollMessageId,
    isNearBottom,
    messages.length,
    scrollToLatest,
  ]);

  useEffect(() => {
    if (hideEmptyList && !messages.length) return;
    const content = messagesRef.current?.querySelector('.ant-list-items');
    if (!content || typeof ResizeObserver === 'undefined') return;
    let previousTime: number | undefined;
    const follow = (time: number) => {
      followingFrameRef.current = undefined;
      const element = messagesRef.current;
      if (!element || !followLatestRef.current) return;
      const target = Math.max(0, element.scrollHeight - element.clientHeight);
      const distance = target - element.scrollTop;
      const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)')?.matches;
      if (Math.abs(distance) <= 1 || reduceMotion) {
        element.scrollTop = target;
        previousTime = undefined;
        return;
      }
      const elapsed = Math.min(32, time - (previousTime ?? time - 16));
      previousTime = time;
      element.scrollTop +=
        Math.sign(distance) *
        Math.min(
          Math.abs(distance),
          Math.max(1, Math.abs(distance) * (1 - Math.exp(-elapsed / 55))),
        );
      followingFrameRef.current = window.requestAnimationFrame(follow);
    };
    const observer = new ResizeObserver(() => {
      if (!followLatestRef.current) {
        setShowJumpToLatest(!isNearBottom());
        return;
      }
      if (followingFrameRef.current !== undefined) return;
      followingFrameRef.current = window.requestAnimationFrame(follow);
    });
    observer.observe(content);
    return () => {
      observer.disconnect();
      if (followingFrameRef.current !== undefined)
        window.cancelAnimationFrame(followingFrameRef.current);
      followingFrameRef.current = undefined;
    };
  }, [activeConversationId, hideEmptyList, isNearBottom, messages.length]);

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
  const followingMessageCount =
    editedMessageIndex < 0 ? 0 : messages.length - editedMessageIndex - 1;

  return (
    <div className={`ai-conversation-workspace${sideOpen ? ' is-side-open' : ''}`}>
      <aside
        className={`ai-conversation-side${searchableHistory ? ' has-history-search' : ''}`}
        aria-label={historyTitle}
      >
        <div className="ai-conversation-side-head">
          {canCreate && <Button
            type="primary"
            size="large"
            icon={<PlusOutlined />}
            block
            disabled={navigationDisabled}
            onClick={() => {
              onNewConversation();
              setHistoryQuery('');
              setSideOpen(false);
            }}
          >
            新建对话
          </Button>}
          <Button
            className="ai-conversation-side-close"
            type="text"
            icon={<CloseOutlined />}
            aria-label="关闭对话与范围面板"
            onClick={() => setSideOpen(false)}
          />
        </div>
        <Typography.Text strong className="ai-conversation-section-title">
          {historyTitle}
        </Typography.Text>
        {searchableHistory && (
          <Input
            className="ai-conversation-history-search"
            aria-label="搜索对话"
            placeholder="搜索对话…"
            prefix={<SearchOutlined />}
            allowClear
            value={historyQuery}
            onChange={(event) => setHistoryQuery(event.target.value)}
          />
        )}
        <div className="ai-conversation-history">
          {visibleConversations.length ? (
            <List
              className="ai-conversation-list"
              dataSource={visibleConversations}
              renderItem={(item) => (
                <List.Item className={item.id === activeConversationId ? 'is-active' : ''}>
                  <MessageOutlined />
                  <button
                    type="button"
                    className="ai-conversation-select"
                    aria-current={item.id === activeConversationId ? 'true' : undefined}
                    disabled={navigationDisabled}
                    onClick={() => {
                      onSelectConversation(item.id);
                      setSideOpen(false);
                    }}
                  >
                    <span className="ai-conversation-name" title={item.title}>
                      {item.title}
                    </span>
                    {searchableHistory && item.summary && (
                      <span className="ai-conversation-summary" title={item.summary}>
                        {item.summary}
                      </span>
                    )}
                    {item.updatedAt && (
                      <Typography.Text type="secondary" className="ai-conversation-date">
                        {new Date(item.updatedAt).toLocaleDateString('zh-CN')}
                      </Typography.Text>
                    )}
                  </button>
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
                    {onDeleteConversation &&
                      (searchableHistory ? (
                        <Button
                          type="text"
                          danger
                          size="small"
                          icon={<DeleteOutlined />}
                          aria-label={`删除对话${item.title}`}
                          onClick={(event) => {
                            event.stopPropagation();
                            setDeletingConversation(item);
                          }}
                        />
                      ) : (
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
                      ))}
                  </Space>
                </List.Item>
              )}
            />
          ) : (
            <Empty
              image={Empty.PRESENTED_IMAGE_SIMPLE}
              description={historySearch ? '未找到相关对话' : '暂无历史对话'}
            />
          )}
        </div>
        {scopeContent && scopePlacement === 'sidebar' && (
          <>
            <Typography.Text strong className="ai-conversation-section-title">
              {scopeTitle}
            </Typography.Text>
            <div className="ai-conversation-scope">{scopeContent}</div>
          </>
        )}
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
            对话列表
          </Button>
        </div>
        <div
          ref={messagesRef}
          className="ai-conversation-messages"
          aria-live="polite"
          onWheel={(event) => {
            if (event.deltaY < 0) {
              followLatestRef.current = false;
              setShowJumpToLatest(messages.length > 0);
            }
          }}
          onPointerDown={() => {
            followLatestRef.current = false;
          }}
          onScroll={() => {
            if (followLatestRef.current && followingFrameRef.current !== undefined) return;
            const nearBottom = isNearBottom();
            followLatestRef.current = nearBottom;
            setShowJumpToLatest(!nearBottom && messages.length > 0);
          }}
        >
          {!messages.length && !loading && (
            <div className="ai-conversation-welcome">
              <span className="ai-conversation-welcome-mark" aria-hidden="true">
                {welcomeMark ?? <RobotOutlined />}
              </span>
              <Typography.Title level={3}>{welcomeTitle}</Typography.Title>
              <Typography.Paragraph type="secondary">{welcomeDescription}</Typography.Paragraph>
              {scopeSummary}
              {welcomeContent}
            </div>
          )}
          {loading && !messages.length && (
            <div className="ai-conversation-state">
              <ConversationFeedback kind="restoring" message="正在加载会话…" />
            </div>
          )}
          {(messages.length > 0 || !hideEmptyList) && (
            <List
              locale={hideEmptyList ? { emptyText: null } : undefined}
              split={false}
              dataSource={messages}
              renderItem={(item) => {
                const isEditingMessage = editingMessage?.id === item.id;
                const messageBody = item.pending ? (
                  <ThinkingIndicator label={pendingLabel} />
                ) : (
                  item.content ||
                  (streaming && item.role === 'ASSISTANT' ? (
                    <ThinkingIndicator label={pendingLabel} />
                  ) : null)
                );

                return (
                  <List.Item
                    data-message-id={item.id}
                    className={`ai-message ai-message-${item.role.toLowerCase()}`}
                  >
                    <div className={`ai-message-content${isEditingMessage ? ' is-editing' : ''}`}>
                      {item.role === 'ASSISTANT' ? (
                        <>
                          <div className="ai-message-assistant-heading">
                            <span className="ai-message-assistant-icon" aria-hidden="true">
                              <RobotOutlined />
                            </span>
                            <Typography.Text strong className="ai-message-meta">
                              {assistantLabel}
                            </Typography.Text>
                          </div>
                          <div className="ai-message-body ai-message-assistant-body">
                            {messageBody}
                          </div>
                          {item.copyText && !item.pending && (
                            <CopyAnswerButton text={item.copyText} />
                          )}
                        </>
                      ) : isEditingMessage ? (
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
                          </div>
                          <div className="ai-message-body">{messageBody}</div>
                          {item.editableText && onEditMessage ? (
                            <div className="ai-message-actions">
                              <ConversationIconButton
                                label="编辑并重新发送这条问题"
                                name="edit"
                                className="ai-message-edit-button"
                                size="small"
                                disabled={Boolean(loading || streaming)}
                                onClick={() => startEditingMessage(item)}
                              />
                            </div>
                          ) : null}
                        </>
                      )}
                    </div>
                  </List.Item>
                );
              }}
            />
          )}
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
        {canCreate && <div
          ref={composerRef}
          className={`ai-conversation-composer${compactComposer ? ' is-compact' : ''}`}
        >
          {composerTopContent || (!compactComposer && (composerActions || scopeContent)) ? (
            <div className="ai-conversation-composer-top">
              <div className="ai-conversation-composer-context">{composerTopContent}</div>
              {!compactComposer && (composerActions || scopeContent) ? (
                <div className="ai-conversation-composer-actions">
                  {scopeContent && scopePlacement === 'composer' && (
                    <Button type="text" onClick={() => setScopeOpen(true)}>
                      选择资料
                    </Button>
                  )}
                  {composerActions}
                </div>
              ) : null}
            </div>
          ) : null}
          <div className="ai-conversation-composer-row">
            <Input.TextArea
              ref={inputRef}
              disabled={inputDisabled}
              aria-label={inputLabel}
              autoSize={{ minRows: compactComposer ? 2 : 3, maxRows: 6 }}
              maxLength={inputMaxLength}
              showCount={
                !compactComposer && {
                  formatter: ({ count, maxLength }) => `${count}/${maxLength || inputMaxLength}`,
                }
              }
              placeholder={inputPlaceholder}
              value={question}
              onChange={(event) => onQuestionChange(event.target.value)}
              onPressEnter={(event) => {
                if (!event.shiftKey && !event.nativeEvent.isComposing) {
                  event.preventDefault();
                  if (
                    question.trim() &&
                    !inputDisabled &&
                    !loading &&
                    !streaming &&
                    !submitDisabled
                  )
                    onSubmit();
                }
              }}
            />
            {compactComposer && (
              <div className="ai-conversation-composer-tools">
                {scopeContent &&
                  scopePlacement === 'composer' &&
                  (scopeButtonText ? (
                    <Button
                      className="ai-composer-scope-button"
                      type="text"
                      icon={<ConversationIcon name="sources" />}
                      onClick={() => setScopeOpen(true)}
                    >
                      {scopeButtonText}
                    </Button>
                  ) : (
                    <ConversationIconButton
                      label="选择资料"
                      name="sources"
                      onClick={() => setScopeOpen(true)}
                    />
                  ))}
                {composerActions}
              </div>
            )}
            {submitAction ?? (
              <Button
                type="primary"
                size="large"
                icon={compactComposer ? <ConversationIcon name="send" /> : <SendOutlined />}
                aria-label={submitLabel}
                title={submitLabel}
                loading={streaming}
                disabled={!question.trim() || loading || streaming || submitDisabled}
                onClick={onSubmit}
              />
            )}
          </div>
        </div>}
      </main>
      <Drawer
        title={scopeTitle === '选择检索范围' ? '选择资料' : scopeTitle}
        open={scopePlacement === 'composer' && scopeOpen}
        onClose={() => setScopeOpen(false)}
        width="min(480px, 100vw)"
      >
        {scopeContent}
      </Drawer>
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
        title="删除这条对话？"
        open={Boolean(deletingConversation)}
        okText="删除"
        cancelText="取消"
        confirmLoading={deleteLoading}
        okButtonProps={{ danger: true }}
        closable={!deleteLoading}
        maskClosable={!deleteLoading}
        keyboard={!deleteLoading}
        onCancel={() => {
          if (!deleteLoading) setDeletingConversation(undefined);
        }}
        onOk={() => void confirmDelete()}
      >
        <Typography.Paragraph>{deletingConversation?.title}</Typography.Paragraph>
        <Typography.Text type="secondary">删除后对话内容将一并移除。</Typography.Text>
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
