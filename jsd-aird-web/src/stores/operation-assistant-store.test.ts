import {
  operationAssistantApi,
  OperationRequestError,
  type OperationEvent,
} from '@/services/operation-assistant/operation-assistant-api';
import { operationTestUser } from '@/test/operation-assistant-fixtures';
import {
  initializeOperationIdentity,
  useOperationAssistantStore,
} from './operation-assistant-store';

describe('operation assistant browser session', () => {
  beforeEach(() => {
    initializeOperationIdentity(null);
    sessionStorage.clear();
    initializeOperationIdentity(operationTestUser());
  });
  afterEach(() => {
    initializeOperationIdentity(null);
    vi.restoreAllMocks();
  });

  it('restores a completed conversation after refresh and clears it on logout', async () => {
    vi.spyOn(operationAssistantApi, 'stream').mockImplementation((_input, _signal, receive) => {
      receive({
        event: 'done',
        data: { answer: '选择模板', links: [], sources: [], manualVersion: 'v1', requestId: 'req' },
      });
      return Promise.resolve();
    });
    await useOperationAssistantStore.getState().send('怎样导入？', 'experiment-upload');
    useOperationAssistantStore.getState().setOpen(true);
    useOperationAssistantStore.setState({ owner: undefined, messages: [], open: false });
    initializeOperationIdentity(operationTestUser());
    expect(useOperationAssistantStore.getState().messages).toHaveLength(2);
    expect(useOperationAssistantStore.getState().open).toBe(true);
    initializeOperationIdentity(null);
    expect(useOperationAssistantStore.getState().messages).toHaveLength(0);
    expect(sessionStorage.length).toBe(0);
  });

  it('restores independent launcher and panel positions on refresh in the same account', () => {
    const launcherPosition = { x: 128, y: 246 };
    const panelPosition = { x: 340, y: 72 };
    useOperationAssistantStore.getState().setPosition('launcher', launcherPosition, true);
    useOperationAssistantStore.getState().setPosition('panel', panelPosition, true);
    useOperationAssistantStore.setState({
      owner: undefined,
      launcherPosition: undefined,
      panelPosition: undefined,
    });

    initializeOperationIdentity(operationTestUser());

    expect(useOperationAssistantStore.getState().launcherPosition).toEqual(launcherPosition);
    expect(useOperationAssistantStore.getState().panelPosition).toEqual(panelPosition);
  });

  it('cancels a running request on identity change and ignores late responses', async () => {
    let receive: ((event: OperationEvent) => void) | undefined;
    let finish: (() => void) | undefined;
    let signal: AbortSignal | undefined;
    vi.spyOn(operationAssistantApi, 'stream').mockImplementation((_input, abort, listener) => {
      receive = listener;
      signal = abort;
      return new Promise<void>((resolve) => {
        finish = resolve;
      });
    });
    useOperationAssistantStore.getState().setPosition('launcher', { x: 90, y: 120 }, true);
    const pending = useOperationAssistantStore.getState().send('问题');
    initializeOperationIdentity({ ...operationTestUser(), userId: 'customer-2' });
    expect(signal?.aborted).toBe(true);
    expect(useOperationAssistantStore.getState().launcherPosition).toBeUndefined();
    receive?.({ event: 'delta', data: { text: '旧账号的回答', replace: true } });
    finish?.();
    await pending;
    expect(useOperationAssistantStore.getState().messages).toHaveLength(0);
    expect(useOperationAssistantStore.getState().owner).toContain('customer-2');
  });

  it('prevents duplicate submissions and retries the same question without duplicating it', async () => {
    const api = vi
      .spyOn(operationAssistantApi, 'stream')
      .mockRejectedValueOnce(new OperationRequestError('断流', 'STREAM_INTERRUPTED'))
      .mockImplementation((_input, _signal, receive) => {
        receive({
          event: 'done',
          data: {
            answer: '完成回答',
            links: [],
            sources: [],
            manualVersion: 'v1',
            requestId: 'req',
          },
        });
        return Promise.resolve();
      });
    const first = useOperationAssistantStore.getState().send('怎样导入？');
    await useOperationAssistantStore.getState().send('重复问题');
    await first;
    expect(api).toHaveBeenCalledTimes(1);
    await useOperationAssistantStore.getState().retry();
    expect(useOperationAssistantStore.getState().messages).toHaveLength(2);
    expect(useOperationAssistantStore.getState().messages.at(-1)?.status).toBe('DONE');
    expect(api.mock.calls[1]?.[0].question).toBe('怎样导入？');
    expect(api.mock.calls[1]?.[0].history).toEqual([]);
  });

  it('marks an interrupted saved stream as retryable when the page is refreshed', () => {
    sessionStorage.setItem(
      'operation-assistant.v1.organization-1:customer-1',
      JSON.stringify({
        version: 1,
        owner: 'organization-1:customer-1',
        messages: [
          { id: 'q', role: 'USER', content: '问题', status: 'DONE' },
          { id: 'a', role: 'ASSISTANT', content: '部分回答', status: 'RUNNING' },
        ],
      }),
    );
    useOperationAssistantStore.setState({ owner: undefined });
    initializeOperationIdentity(operationTestUser());
    expect(useOperationAssistantStore.getState().messages.at(-1)).toMatchObject({
      status: 'FAILED',
      error: '页面刷新中断了回答，请重试。',
    });
  });
});
