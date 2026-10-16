import {
  ArrowRightOutlined,
  BookOutlined,
  CloseOutlined,
  DeleteOutlined,
  HolderOutlined,
  LoadingOutlined,
  MessageOutlined,
  QuestionCircleOutlined,
  ReloadOutlined,
  SendOutlined,
} from '@ant-design/icons';
import { App, Button, Input, Tooltip, theme } from 'antd';
import {
  useEffect,
  useRef,
  useState,
  type CSSProperties,
  type KeyboardEvent as ReactKeyboardEvent,
  type PointerEvent as ReactPointerEvent,
} from 'react';
import type { TextAreaRef } from 'antd/es/input/TextArea';
import { useLocation, useNavigate } from 'react-router-dom';
import { uiLayers } from '@/app/config/layers';
import { MarkdownContent } from '@/components/markdown/MarkdownContent';
import { useAuthStore } from '@/stores/auth-store';
import {
  bindOperationAssistantIdentity,
  type OperationAssistantPosition,
  useOperationAssistantStore,
} from '@/stores/operation-assistant-store';
import {
  canUseOperationPage,
  currentOperationPage,
  operationAnswerText,
  operationManual,
  operationSuggestionLabel,
  operationSuggestions,
} from '@/services/operation-assistant/operation-manual';
import './operation-assistant.css';

type DragMode = 'launcher' | 'panel';
type ActiveDrag = {
  mode: DragMode;
  pointerId: number;
  startX: number;
  startY: number;
  origin: OperationAssistantPosition;
  moved: boolean;
};

function defaultPosition(mode: DragMode): OperationAssistantPosition {
  const margin = window.innerWidth <= 480 ? 12 : 24;
  const width =
    mode === 'panel' ? Math.min(420, Math.max(0, window.innerWidth - 24)) : window.innerWidth <= 480 ? 44 : 48;
  const height =
    mode === 'panel'
      ? Math.min(560, Math.max(0, window.innerHeight - (window.innerWidth <= 480 ? 80 : 96)))
      : window.innerWidth <= 480
        ? 44
        : 48;
  return {
    x: Math.max(margin, window.innerWidth - width - margin),
    y: Math.max(margin, window.innerHeight - height - margin),
  };
}

function clampPosition(position: OperationAssistantPosition, width: number, height: number) {
  const margin = 12;
  return {
    x: Math.min(Math.max(margin, position.x), Math.max(margin, window.innerWidth - width - margin)),
    y: Math.min(Math.max(margin, position.y), Math.max(margin, window.innerHeight - height - margin)),
  };
}

export function OperationAssistant() {
  const { token } = theme.useToken();
  const { message: notification } = App.useApp();
  const user = useAuthStore((state) => state.user);
  const open = useOperationAssistantStore((state) => state.open);
  const draft = useOperationAssistantStore((state) => state.draft);
  const messages = useOperationAssistantStore((state) => state.messages);
  const running = useOperationAssistantStore((state) => state.running);
  const setOpen = useOperationAssistantStore((state) => state.setOpen);
  const setDraft = useOperationAssistantStore((state) => state.setDraft);
  const setPosition = useOperationAssistantStore((state) => state.setPosition);
  const launcherPosition = useOperationAssistantStore((state) => state.launcherPosition);
  const panelPosition = useOperationAssistantStore((state) => state.panelPosition);
  const send = useOperationAssistantStore((state) => state.send);
  const retry = useOperationAssistantStore((state) => state.retry);
  const clear = useOperationAssistantStore((state) => state.clear);
  const location = useLocation();
  const navigate = useNavigate();
  const composer = useRef<TextAreaRef>(null);
  const launcher = useRef<HTMLButtonElement>(null);
  const panel = useRef<HTMLElement>(null);
  const root = useRef<HTMLElement>(null);
  const conversation = useRef<HTMLDivElement>(null);
  const nearBottom = useRef(true);
  const drag = useRef<ActiveDrag | null>(null);
  const suppressLauncherClick = useRef(false);
  const [dragging, setDragging] = useState(false);
  const currentPage = currentOperationPage(location.pathname, location.search);
  const suggestions = operationSuggestions(currentPage?.id, user);

  useEffect(bindOperationAssistantIdentity, []);
  useEffect(() => {
    if (open) composer.current?.focus();
  }, [open]);
  useEffect(() => {
    const element = conversation.current;
    if (element && nearBottom.current) element.scrollTop = element.scrollHeight;
  }, [messages, open]);

  useEffect(() => {
    const reposition = () => {
      const state = useOperationAssistantStore.getState();
      const mode = state.open ? 'panel' : 'launcher';
      const element = mode === 'panel' ? panel.current : launcher.current;
      if (!element) return;
      const rect = element.getBoundingClientRect();
      const position = mode === 'panel' ? state.panelPosition : state.launcherPosition;
      const next = clampPosition(position || defaultPosition(mode), rect.width, rect.height);
      setPosition(mode, next, true);
    };
    reposition();
    window.addEventListener('resize', reposition);
    return () => window.removeEventListener('resize', reposition);
  }, [open, user?.userId, user?.organizationId, setPosition]);

  if (!user) return null;
  const close = () => {
    setOpen(false);
    window.setTimeout(() => launcher.current?.focus(), 0);
  };
  const style = {
    zIndex: uiLayers.operationAssistant,
    left: `${(open ? panelPosition : launcherPosition)?.x ?? defaultPosition(open ? 'panel' : 'launcher').x}px`,
    top: `${(open ? panelPosition : launcherPosition)?.y ?? defaultPosition(open ? 'panel' : 'launcher').y}px`,
    '--oa-accent': token.colorPrimary,
    '--oa-surface': token.colorBgElevated,
    '--oa-background': token.colorBgLayout,
    '--oa-text': token.colorText,
    '--oa-muted': token.colorTextSecondary,
    '--oa-border': token.colorBorderSecondary,
    '--oa-error': token.colorError,
  } as CSSProperties;

  const beginDrag = (event: ReactPointerEvent<HTMLElement>, mode: DragMode) => {
    if (event.pointerType === 'mouse' && event.button !== 0) return;
    if (
      mode === 'panel' &&
      event.target instanceof Element &&
      event.target.closest('button, input, textarea, a')
    )
      return;
    const state = useOperationAssistantStore.getState();
    const saved = mode === 'panel' ? state.panelPosition : state.launcherPosition;
    const origin = saved || defaultPosition(mode);
    drag.current = {
      mode,
      pointerId: event.pointerId,
      startX: event.clientX,
      startY: event.clientY,
      origin,
      moved: false,
    };
    event.currentTarget.setPointerCapture?.(event.pointerId);
  };

  const moveDrag = (event: ReactPointerEvent<HTMLElement>) => {
    const active = drag.current;
    if (!active || active.pointerId !== event.pointerId) return;
    const dx = event.clientX - active.startX;
    const dy = event.clientY - active.startY;
    if (!active.moved && Math.hypot(dx, dy) < 4) return;
    active.moved = true;
    const element = active.mode === 'panel' ? panel.current : launcher.current;
    if (!element) return;
    const rect = element.getBoundingClientRect();
    const next = clampPosition({ x: active.origin.x + dx, y: active.origin.y + dy }, rect.width, rect.height);
    setPosition(active.mode, next);
    setDragging(true);
  };

  const endDrag = (event: ReactPointerEvent<HTMLElement>) => {
    const active = drag.current;
    if (!active || active.pointerId !== event.pointerId) return;
    drag.current = null;
    if (active.moved) {
      const state = useOperationAssistantStore.getState();
      const position = active.mode === 'panel' ? state.panelPosition : state.launcherPosition;
      if (position) state.setPosition(active.mode, position, true);
      if (active.mode === 'launcher') {
        suppressLauncherClick.current = true;
        window.setTimeout(() => {
          suppressLauncherClick.current = false;
        }, 0);
      }
    }
    setDragging(false);
  };

  const moveWithKeyboard = (event: ReactKeyboardEvent<HTMLElement>, mode: DragMode) => {
    const delta = 16;
    const directions: Record<string, OperationAssistantPosition> = {
      ArrowLeft: { x: -delta, y: 0 },
      ArrowRight: { x: delta, y: 0 },
      ArrowUp: { x: 0, y: -delta },
      ArrowDown: { x: 0, y: delta },
    };
    const shift = directions[event.key];
    if (!shift) return;
    event.preventDefault();
    const element = mode === 'panel' ? panel.current : launcher.current;
    if (!element) return;
    const state = useOperationAssistantStore.getState();
    const origin = (mode === 'panel' ? state.panelPosition : state.launcherPosition) || defaultPosition(mode);
    const rect = element.getBoundingClientRect();
    setPosition(
      mode,
      clampPosition({ x: origin.x + shift.x, y: origin.y + shift.y }, rect.width, rect.height),
      true,
    );
  };

  return (
    <aside
      ref={root}
      className={`operation-assistant-root${dragging ? ' is-dragging' : ''}`}
      style={style}
      aria-label="操作帮助"
    >
      {open ? (
        <section
          ref={panel}
          className="operation-assistant-panel"
          role="dialog"
          aria-modal="false"
          aria-labelledby="operation-assistant-title"
          onKeyDown={(event) => {
            if (event.target === event.currentTarget) moveWithKeyboard(event, 'panel');
            if (event.key === 'Escape') {
              event.stopPropagation();
              close();
            }
          }}
        >
          <header
            className="operation-assistant-header"
            tabIndex={0}
            aria-label="拖动面板；使用方向键移动"
            onPointerDown={(event) => beginDrag(event, 'panel')}
            onPointerMove={moveDrag}
            onPointerUp={endDrag}
            onPointerCancel={endDrag}
            onKeyDown={(event) => {
              if (event.target === event.currentTarget) moveWithKeyboard(event, 'panel');
            }}
          >
            <span className="operation-assistant-mark" aria-hidden="true">
              <QuestionCircleOutlined />
            </span>
            <div className="operation-assistant-heading">
              <h2 id="operation-assistant-title">AI 操作助手</h2>
            </div>
            <HolderOutlined className="operation-assistant-drag-mark" aria-hidden="true" />
            <Tooltip title="清空对话">
              <Button
                type="text"
                icon={<DeleteOutlined />}
                aria-label="清空对话"
                onClick={() => {
                  clear();
                  composer.current?.focus();
                }}
                disabled={!messages.length && !draft}
              />
            </Tooltip>
            <Button
              type="text"
              icon={<CloseOutlined />}
              aria-label="关闭操作助手"
              onClick={close}
            />
          </header>

          {currentPage && (
            <div className="operation-assistant-context">
              <BookOutlined aria-hidden="true" />
              <span>当前页面</span>
              <strong>{currentPage.title}</strong>
            </div>
          )}

          <div
            className="operation-assistant-conversation"
            ref={conversation}
            role="log"
            aria-label="操作问答记录"
            aria-live="polite"
            aria-relevant="additions"
            onScroll={() => {
              const element = conversation.current;
              if (element)
                nearBottom.current =
                  element.scrollHeight - element.scrollTop - element.clientHeight < 80;
            }}
          >
            {!messages.length ? (
              <div className="operation-assistant-welcome">
                <h3>你想完成什么操作？</h3>
                <p>直接说你要做什么。</p>
                <div className="operation-assistant-suggestions" aria-label="推荐问题">
                  {suggestions.map((question) => (
                    <button
                      key={question}
                      type="button"
                      onClick={() => {
                        nearBottom.current = true;
                        void send(question, currentPage?.id);
                      }}
                    >
                      <span>{operationSuggestionLabel(question)}</span>
                      <ArrowRightOutlined aria-hidden="true" />
                    </button>
                  ))}
                </div>
              </div>
            ) : (
              messages.map((item, index) => {
                const links = (item.response?.links || []).flatMap((link) => {
                  const page = operationManual.pages.find(
                    (candidate) => candidate.id === link.pageId,
                  );
                  return page && page.path === link.path && canUseOperationPage(page, user)
                    ? [page]
                    : [];
                });
                return (
                  <article
                    key={item.id}
                    className={`operation-assistant-message is-${item.role.toLowerCase()}`}
                    aria-label={item.role === 'USER' ? '你的问题' : '助手回答'}
                  >
                    {item.role === 'ASSISTANT' && (
                      <div className="operation-assistant-author">
                        <BookOutlined aria-hidden="true" />
                        操作指南
                      </div>
                    )}
                    {item.content &&
                      (item.role === 'USER' ? (
                        <p className="operation-assistant-user-text">{item.content}</p>
                      ) : (
                        <MarkdownContent
                          value={operationAnswerText(item.content)}
                          className="operation-assistant-answer"
                        />
                      ))}
                    {item.status === 'RUNNING' && (
                      <div className="operation-assistant-progress" role="status">
                        <LoadingOutlined spin aria-hidden="true" />
                        <span>{item.content ? '正在继续回答…' : '正在整理操作步骤…'}</span>
                      </div>
                    )}
                    {!!links.length && (
                      <div className="operation-assistant-links" aria-label="相关页面入口">
                        {links.map((page) => (
                          <Button
                            key={page.id}
                            type="default"
                            size="small"
                            aria-label={`打开${page.title}`}
                            onClick={() => {
                              if (!canUseOperationPage(page, useAuthStore.getState().user)) {
                                void notification.warning(
                                  '当前账号无法打开此页面，请重新检查权限。',
                                );
                                return;
                              }
                              navigate(page.path);
                            }}
                          >
                            {page.title}
                            <ArrowRightOutlined aria-hidden="true" />
                          </Button>
                        ))}
                      </div>
                    )}
                    {!!item.response?.sources.length && (
                      <p className="operation-assistant-sources">
                        依据：{item.response.sources.map((source) => source.title).join('、')}
                      </p>
                    )}
                    {item.error && (
                      <div className="operation-assistant-failure" role="alert">
                        <p>{item.error}</p>
                        {item.errorRequestId && (
                          <p className="operation-assistant-request-id">
                            请求编号：<code>{item.errorRequestId}</code>
                          </p>
                        )}
                        {index === messages.length - 1 && (
                          <Button
                            type="text"
                            size="small"
                            icon={<ReloadOutlined />}
                            disabled={running}
                            onClick={() => {
                              nearBottom.current = true;
                              void retry();
                            }}
                          >
                            重新回答
                          </Button>
                        )}
                      </div>
                    )}
                  </article>
                );
              })
            )}
          </div>

          <form
            className="operation-assistant-composer"
            onSubmit={(event) => {
              event.preventDefault();
              nearBottom.current = true;
              void send(draft, currentPage?.id);
            }}
          >
            <label className="operation-assistant-sr-only" htmlFor="operation-assistant-question">
              输入操作问题
            </label>
            <Input.TextArea
              ref={composer}
              id="operation-assistant-question"
              value={draft}
              maxLength={3000}
              autoSize={{ minRows: 2, maxRows: 4 }}
              placeholder="例如：导入实验记录"
              onChange={(event) => setDraft(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) {
                  event.preventDefault();
                  nearBottom.current = true;
                  void send(draft, currentPage?.id);
                }
              }}
            />
            <div className="operation-assistant-composer-footer">
              <span>Enter 发送，Shift + Enter 换行</span>
              <Button
                type="primary"
                htmlType="submit"
                aria-label="发送操作问题"
                icon={<SendOutlined />}
                disabled={!draft.trim() || running}
              >
                发送
              </Button>
            </div>
          </form>
        </section>
      ) : (
        <Button
          ref={launcher}
          type="primary"
          shape="circle"
          className="operation-assistant-launcher"
          icon={<MessageOutlined />}
          aria-label="打开 AI 操作助手"
          aria-expanded={false}
          onPointerDown={(event) => beginDrag(event, 'launcher')}
          onPointerMove={moveDrag}
          onPointerUp={endDrag}
          onPointerCancel={endDrag}
          onKeyDown={(event) => moveWithKeyboard(event, 'launcher')}
          onClick={() => {
            if (suppressLauncherClick.current) return;
            setOpen(true);
          }}
        />
      )}
    </aside>
  );
}
