import {
  assistantApi,
  type AssistantCitation,
  type AssistantMessageReplacement,
  type AssistantResponse,
} from './assistant-api';

export type AssistantStreamStatus = 'RUNNING' | 'SUCCEEDED' | 'FAILED';

export interface AssistantStreamTask {
  id: string;
  conversationId?: string;
  userMessageId?: string;
  assistantMessageId?: string;
  localUserMessageId: string;
  localAssistantMessageId: string;
  question: string;
  answer: string;
  citations?: AssistantCitation[];
  response?: AssistantResponse;
  failure?: unknown;
  status: AssistantStreamStatus;
  stage: string;
  createdAt: number;
}

export interface StartAssistantStreamInput {
  question: string;
  conversationId?: string;
  knowledgeCategoryIds: string[];
  dataCategoryIds: string[];
  webSearchEnabled: boolean;
  replacement?: AssistantMessageReplacement;
  localUserMessageId: string;
  localAssistantMessageId: string;
}

const selectionStorageKey = 'assistant.qa.selectedConversation';
const STREAM_UI_REFRESH_MS = 16;
const listeners = new Set<() => void>();
const streamListeners = new Map<string, Set<() => void>>();
const streamRefreshTimers = new Map<string, ReturnType<typeof setTimeout>>();
const connections = new Map<string, Promise<void>>();
const authRedirects = new Set<string>();
let snapshot = new Map<string, AssistantStreamTask>();

export function getAssistantStreams() {
  return snapshot;
}

export function subscribeAssistantStreams(listener: () => void) {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

export function subscribeAssistantStream(runId: string, listener: () => void) {
  const subscribers = streamListeners.get(runId) || new Set<() => void>();
  subscribers.add(listener);
  streamListeners.set(runId, subscribers);
  return () => {
    subscribers.delete(listener);
    if (!subscribers.size) streamListeners.delete(runId);
  };
}

export function getAssistantStream(runId: string) {
  return snapshot.get(runId);
}

export function claimAssistantAuthRedirect(runId: string) {
  if (authRedirects.has(runId)) return false;
  authRedirects.add(runId);
  return true;
}

export function getAssistantConversationSelection() {
  if (typeof window === 'undefined') return undefined;
  try {
    return window.sessionStorage.getItem(selectionStorageKey) || undefined;
  } catch {
    return undefined;
  }
}

export function setAssistantConversationSelection(value?: string) {
  if (typeof window === 'undefined') return;
  try {
    if (value) window.sessionStorage.setItem(selectionStorageKey, value);
    else window.sessionStorage.removeItem(selectionStorageKey);
  } catch {
    // The in-memory stream still works when browser storage is unavailable.
  }
}

export function startAssistantStream(input: StartAssistantStreamInput) {
  const id = createRunId();
  const task: AssistantStreamTask = {
    id,
    conversationId: input.conversationId,
    localUserMessageId: input.localUserMessageId,
    localAssistantMessageId: input.localAssistantMessageId,
    question: input.question,
    answer: '',
    status: 'RUNNING',
    stage: '正在理解问题…',
    createdAt: Date.now(),
  };
  update(id, task);
  setAssistantConversationSelection(input.conversationId || `pending:${id}`);

  const connection = assistantApi
    .stream(
      input.question,
      input.conversationId,
      input.knowledgeCategoryIds,
      input.dataCategoryIds,
      input.webSearchEnabled,
      (token, replace) => {
        const current = snapshot.get(id);
        if (current)
          update(id, { ...current, answer: replace ? token : current.answer + token }, true);
      },
      (response) => acceptResponse(id, response),
      (event, data) => acceptEvent(id, event, data),
      input.replacement,
    )
    .catch((error: unknown) => {
      const current = snapshot.get(id);
      if (current) update(id, { ...current, status: 'FAILED', failure: error });
    })
    .finally(() => connections.delete(id));
  // Keep the fetch consumer rooted outside the page component so route unmounts
  // cannot release the stream or its callbacks.
  connections.set(id, connection);
  return id;
}

function acceptEvent(id: string, event: string, data: unknown) {
  const current = snapshot.get(id);
  if (!current) return;

  if (event === 'thinking') {
    update(id, { ...current, stage: thinkingLabel(data) });
    return;
  }
  if (event === 'context' && data && typeof data === 'object') {
    const context = data as { conversationId?: unknown; userMessageId?: unknown };
    const conversationId =
      typeof context.conversationId === 'string' ? context.conversationId : undefined;
    const userMessageId =
      typeof context.userMessageId === 'string' ? context.userMessageId : undefined;
    if (conversationId) {
      const selection = getAssistantConversationSelection();
      if (selection === `pending:${id}`) setAssistantConversationSelection(conversationId);
    }
    update(id, { ...current, conversationId, userMessageId, stage: '正在查找资料…' });
    return;
  }
}

function acceptResponse(id: string, response: AssistantResponse) {
  const current = snapshot.get(id);
  if (!current) return;
  const selection = getAssistantConversationSelection();
  if (selection === `pending:${id}`) setAssistantConversationSelection(response.conversationId);
  update(id, {
    ...current,
    conversationId: response.conversationId,
    userMessageId: response.userMessageId,
    assistantMessageId: response.assistantMessageId,
    answer: response.answer || current.answer,
    citations: response.citations,
    response,
    status: 'SUCCEEDED',
    stage: '回答完成',
  });
}

function update(id: string, task: AssistantStreamTask, contentOnly = false) {
  if (contentOnly) {
    // The global map is metadata state for the page. Keep its identity stable
    // while only answer text changes; the per-run subscriber reads this entry.
    snapshot.set(id, task);
    scheduleStreamNotification(id);
    return;
  }

  const next = new Map(snapshot);
  next.set(id, task);
  if (next.size > 30) {
    for (const [oldId, oldTask] of next) {
      if (oldId !== id && oldTask.status !== 'RUNNING') {
        next.delete(oldId);
        authRedirects.delete(oldId);
      }
      if (next.size <= 24) break;
    }
  }
  snapshot = next;
  flushStreamNotification(id);
  listeners.forEach((listener) => listener());
}

function scheduleStreamNotification(id: string) {
  if (streamRefreshTimers.has(id)) return;
  streamRefreshTimers.set(
    id,
    setTimeout(() => {
      streamRefreshTimers.delete(id);
      notifyStreamListeners(id);
    }, STREAM_UI_REFRESH_MS),
  );
}

function flushStreamNotification(id: string) {
  const timer = streamRefreshTimers.get(id);
  if (timer !== undefined) {
    clearTimeout(timer);
    streamRefreshTimers.delete(id);
  }
  notifyStreamListeners(id);
}

function notifyStreamListeners(id: string) {
  streamListeners.get(id)?.forEach((listener) => listener());
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

function createRunId() {
  return `${Date.now()}-${Math.random().toString(36).slice(2)}`;
}
