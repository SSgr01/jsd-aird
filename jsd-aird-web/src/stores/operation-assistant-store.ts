import { create } from 'zustand';
import { useAuthStore } from '@/stores/auth-store';
import type { AuthUser } from '@/services/auth/auth-api';
import {
  isOperationAnswer,
  operationAssistantApi,
  OperationRequestError,
  type OperationAnswer,
  type OperationQuestion,
} from '@/services/operation-assistant/operation-assistant-api';
import { operationAnswerText } from '@/services/operation-assistant/operation-manual';
import { generateUUID } from '@/utils/uuid';

export interface OperationMessage {
  id: string;
  role: 'USER' | 'ASSISTANT';
  content: string;
  status: 'DONE' | 'RUNNING' | 'FAILED';
  error?: string;
  errorRequestId?: string;
  response?: OperationAnswer;
  currentPageId?: string;
}

export interface OperationAssistantPosition {
  x: number;
  y: number;
}

type PositionMode = 'launcher' | 'panel';

interface OperationState {
  owner?: string;
  open: boolean;
  draft: string;
  messages: OperationMessage[];
  running: boolean;
  launcherPosition?: OperationAssistantPosition;
  panelPosition?: OperationAssistantPosition;
  setOpen: (open: boolean) => void;
  setDraft: (draft: string) => void;
  setPosition: (mode: PositionMode, position: OperationAssistantPosition, persistNow?: boolean) => void;
  clear: () => void;
  send: (question: string, pageId?: string) => Promise<void>;
  retry: () => Promise<void>;
}

const prefix = 'operation-assistant.v1.';
let controller: AbortController | undefined;
let generation = 0;
const key = (owner: string) => `${prefix}${owner}`;

function persist() {
  const state = useOperationAssistantStore.getState();
  if (!state.owner) return;
  try {
    sessionStorage.setItem(
      key(state.owner),
      JSON.stringify({
        version: 1,
        owner: state.owner,
        open: state.open,
        draft: state.draft,
        messages: state.messages,
        launcherPosition: state.launcherPosition,
        panelPosition: state.panelPosition,
      }),
    );
  } catch {
    /* In-memory help remains usable. */
  }
}

function cancel() {
  generation += 1;
  controller?.abort();
  controller = undefined;
}

function isStoredMessage(value: unknown): value is OperationMessage {
  return (
    !!value &&
    typeof value === 'object' &&
    'id' in value &&
    typeof value.id === 'string' &&
    'role' in value &&
    (value.role === 'USER' || value.role === 'ASSISTANT') &&
    'content' in value &&
    typeof value.content === 'string'
  );
}

function isStoredPosition(value: unknown): value is OperationAssistantPosition {
  return (
    !!value &&
    typeof value === 'object' &&
    'x' in value &&
    typeof value.x === 'number' &&
    Number.isFinite(value.x) &&
    'y' in value &&
    typeof value.y === 'number' &&
    Number.isFinite(value.y)
  );
}

export function initializeOperationIdentity(user: AuthUser | null) {
  const owner = user ? `${user.organizationId}:${user.userId}` : undefined;
  const previous = useOperationAssistantStore.getState().owner;
  if (owner === previous) return;
  cancel();
  if (previous) {
    try {
      sessionStorage.removeItem(key(previous));
    } catch {
      /* Storage can be disabled. */
    }
  }
  let restored: Partial<
    Pick<OperationState, 'open' | 'draft' | 'messages' | 'launcherPosition' | 'panelPosition'>
  > = {};
  if (owner) {
    try {
      const saved: unknown = JSON.parse(sessionStorage.getItem(key(owner)) || 'null');
      if (
        saved &&
        typeof saved === 'object' &&
        'version' in saved &&
        saved.version === 1 &&
        'owner' in saved &&
        saved.owner === owner &&
        'messages' in saved &&
        Array.isArray(saved.messages)
      ) {
        restored = {
          open: 'open' in saved && saved.open === true,
          draft:
            'draft' in saved && typeof saved.draft === 'string' ? saved.draft.slice(0, 3000) : '',
          messages: (saved.messages as unknown[])
            .slice(-60)
            .filter(isStoredMessage)
            .map((item) => ({
              ...item,
              content: item.content.slice(0, 16000),
              currentPageId:
                typeof item.currentPageId === 'string' ? item.currentPageId : undefined,
              response: isOperationAnswer(item.response) ? item.response : undefined,
              status:
                item.status === 'RUNNING' ? 'FAILED' : item.status === 'FAILED' ? 'FAILED' : 'DONE',
              error:
                item.status === 'RUNNING'
                  ? '页面刷新中断了回答，请重试。'
                  : typeof item.error === 'string'
                    ? item.error
                    : undefined,
            })),
          launcherPosition:
            'launcherPosition' in saved && isStoredPosition(saved.launcherPosition)
              ? saved.launcherPosition
              : undefined,
          panelPosition:
            'panelPosition' in saved && isStoredPosition(saved.panelPosition)
              ? saved.panelPosition
              : undefined,
        };
      }
    } catch {
      /* Ignore invalid or stale browser storage. */
    }
  }
  useOperationAssistantStore.setState({
    owner,
    open: false,
    draft: '',
    messages: [],
    running: false,
    launcherPosition: undefined,
    panelPosition: undefined,
    ...restored,
  });
  persist();
}

export function bindOperationAssistantIdentity() {
  initializeOperationIdentity(useAuthStore.getState().user);
  return useAuthStore.subscribe((state, previous) => {
    if (
      state.user?.userId !== previous.user?.userId ||
      state.user?.organizationId !== previous.user?.organizationId
    ) {
      initializeOperationIdentity(state.user);
    }
  });
}

function history(messages: OperationMessage[]): OperationQuestion['history'] {
  let total = 0;
  const selected: OperationQuestion['history'] = [];
  for (const message of messages.slice(-12).reverse()) {
    if (message.status !== 'DONE' || !message.content.trim()) continue;
    const content = message.content.slice(0, 6000);
    if (total + content.length > 24000) break;
    selected.unshift({ role: message.role, content });
    total += content.length;
  }
  return selected;
}

async function run(
  question: string,
  pageId: string | undefined,
  context: OperationMessage[],
  assistantId: string,
) {
  controller = new AbortController();
  const signal = controller.signal;
  const runId = ++generation;
  const owner = useOperationAssistantStore.getState().owner;
  const update = (patch: Partial<OperationMessage>) => {
    if (runId !== generation || useOperationAssistantStore.getState().owner !== owner) return;
    useOperationAssistantStore.setState((state) => ({
      messages: state.messages.map((message) =>
        message.id === assistantId ? { ...message, ...patch } : message,
      ),
    }));
    persist();
  };
  try {
    await operationAssistantApi.stream(
      { question, currentPageId: pageId, history: history(context) },
      signal,
      (event) => {
        if (runId !== generation) return;
        if (event.event === 'delta') {
          const current =
            useOperationAssistantStore
              .getState()
              .messages.find((message) => message.id === assistantId)?.content || '';
          update({
            content: operationAnswerText(
              event.data.replace ? event.data.text : current + event.data.text,
            ),
          });
        } else if (event.event === 'done') {
          update({
            content: operationAnswerText(event.data.answer),
            status: 'DONE',
            response: event.data,
            error: undefined,
          });
        }
      },
    );
  } catch (error) {
    if (!signal.aborted) {
      const requestError = error instanceof OperationRequestError ? error : undefined;
      const fallback = requestError?.fallback;
      update({
        status: 'FAILED',
        error: error instanceof Error ? error.message : '回答未完成，请重试。',
        errorRequestId: requestError?.requestId,
        ...(fallback ? { content: operationAnswerText(fallback.answer), response: fallback } : {}),
      });
    }
  } finally {
    if (runId === generation) {
      controller = undefined;
      useOperationAssistantStore.setState({ running: false });
      persist();
    }
  }
}

export const useOperationAssistantStore = create<OperationState>((set, get) => ({
  open: false,
  draft: '',
  messages: [],
  running: false,
  setOpen: (open) => {
    set({ open });
    persist();
  },
  setDraft: (draft) => {
    set({ draft });
    persist();
  },
  setPosition: (mode, position, persistNow = false) => {
    set(mode === 'launcher' ? { launcherPosition: position } : { panelPosition: position });
    if (persistNow) persist();
  },
  clear: () => {
    cancel();
    set({ messages: [], draft: '', running: false });
    persist();
  },
  send: async (input, pageId) => {
    const question = input.trim();
    if (!get().owner || get().running || !question || question.length > 3000) return;
    const context = get().messages;
    const assistantId = generateUUID();
    set({
      draft: '',
      running: true,
      messages: [
        ...context,
        {
          id: generateUUID(),
          role: 'USER',
          content: question,
          status: 'DONE',
          currentPageId: pageId,
        },
        {
          id: assistantId,
          role: 'ASSISTANT',
          content: '',
          status: 'RUNNING',
          currentPageId: pageId,
        },
      ].slice(-60) as OperationMessage[],
    });
    persist();
    await run(question, pageId, context, assistantId);
  },
  retry: async () => {
    if (get().running || !get().owner) return;
    const messages = get().messages;
    const answer = messages.at(-1);
    const question = messages.at(-2);
    if (!answer || answer.status !== 'FAILED' || !question || question.role !== 'USER') return;
    set({
      running: true,
      messages: messages.map((message) =>
        message.id === answer.id
          ? {
              ...message,
              content: '',
              status: 'RUNNING',
              response: undefined,
              error: undefined,
              errorRequestId: undefined,
            }
          : message,
      ),
    });
    persist();
    await run(question.content, question.currentPageId, messages.slice(0, -2), answer.id);
  },
}));
