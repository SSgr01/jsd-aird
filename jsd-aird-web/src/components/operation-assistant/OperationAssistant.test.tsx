import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { AppProviders } from '@/app/providers/AppProviders';
import {
  operationAssistantApi,
  OperationRequestError,
} from '@/services/operation-assistant/operation-assistant-api';
import { useAuthStore } from '@/stores/auth-store';
import {
  initializeOperationIdentity,
  useOperationAssistantStore,
} from '@/stores/operation-assistant-store';
import { operationTestUser } from '@/test/operation-assistant-fixtures';
import { OperationAssistant } from './OperationAssistant';

function CurrentPath() {
  return <div data-testid="path">{useLocation().pathname}</div>;
}
const originalPointerEvent = window.PointerEvent;
const mount = () =>
  render(
    <AppProviders>
      <MemoryRouter initialEntries={['/experiments/list']}>
        <CurrentPath />
        <OperationAssistant />
      </MemoryRouter>
    </AppProviders>,
  );

describe('operation assistant panel', () => {
  beforeAll(() => {
    Object.defineProperty(window, 'PointerEvent', { configurable: true, value: window.MouseEvent });
  });
  afterAll(() => {
    if (originalPointerEvent) {
      Object.defineProperty(window, 'PointerEvent', {
        configurable: true,
        value: originalPointerEvent,
      });
    } else {
      Reflect.deleteProperty(window, 'PointerEvent');
    }
  });
  beforeEach(() => {
    initializeOperationIdentity(null);
    sessionStorage.clear();
    useAuthStore.setState({ user: operationTestUser(), status: 'authenticated' });
  });
  afterEach(() => {
    cleanup();
    initializeOperationIdentity(null);
    act(() => useAuthStore.setState({ user: null, status: 'anonymous' }));
    vi.restoreAllMocks();
  });

  it('opens for a user without ai.use, supports keyboard close and never appears when logged out', async () => {
    mount();
    fireEvent.click(screen.getByRole('button', { name: '打开 AI 操作助手' }));
    expect(screen.getByRole('dialog', { name: 'AI 操作助手' })).toBeInTheDocument();
    expect(screen.getByText('直接说你要做什么。')).toBeInTheDocument();
    expect(screen.queryByText('操作步骤和页面入口，都在这里')).not.toBeInTheDocument();
    expect(screen.getByRole('textbox', { name: '输入操作问题' })).toHaveAttribute(
      'placeholder',
      '例如：导入实验记录',
    );
    expect(screen.getByRole('textbox', { name: '输入操作问题' })).toHaveFocus();
    fireEvent.keyDown(screen.getByRole('textbox'), { key: 'Escape' });
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    await waitFor(() =>
      expect(screen.getByRole('button', { name: '打开 AI 操作助手' })).toHaveFocus(),
    );
    useAuthStore.setState({ user: null, status: 'anonymous' });
    await waitFor(() =>
      expect(screen.queryByRole('button', { name: '打开 AI 操作助手' })).not.toBeInTheDocument(),
    );
  });

  it('renders only verified page buttons and navigates without losing the conversation', async () => {
    vi.spyOn(operationAssistantApi, 'stream').mockImplementation((_input, _signal, receive) => {
      receive({
        event: 'done',
        data: {
          answer: '选择模板并上传。 [不可信](//evil.example)',
          links: [
            { pageId: 'experiment-upload', label: '实验文件导入', path: '/experiments/upload' },
            { pageId: 'permissions', label: '权限设置', path: '/system/user-permissions' },
            { pageId: 'experiment-new', label: '不可信', path: '//evil.example' },
          ],
          sources: [{ sectionId: 'experiment-import', title: '导入实验文件' }],
          manualVersion: 'v1',
          requestId: 'req',
        },
      });
      return Promise.resolve();
    });
    mount();
    fireEvent.click(screen.getByRole('button', { name: '打开 AI 操作助手' }));
    const input = screen.getByRole('textbox', { name: '输入操作问题' });
    fireEvent.change(input, { target: { value: '怎样导入实验记录？' } });
    fireEvent.keyDown(input, { key: 'Enter' });
    const link = await screen.findByRole('button', { name: '打开实验文件导入' });
    expect(screen.queryByRole('button', { name: '打开用户权限配置' })).not.toBeInTheDocument();
    expect(screen.queryByRole('link')).not.toBeInTheDocument();
    fireEvent.click(link);
    expect(screen.getByTestId('path')).toHaveTextContent('/experiments/upload');
    expect(useOperationAssistantStore.getState().messages).toHaveLength(2);
    expect(screen.getByRole('dialog')).toBeInTheDocument();
  });

  it('shows the request id when the API rejects the request before opening the stream', async () => {
    vi.spyOn(operationAssistantApi, 'stream').mockRejectedValue(
      new OperationRequestError('服务器内部错误', 'INTERNAL_ERROR', 'req-500'),
    );
    mount();
    fireEvent.click(screen.getByRole('button', { name: '打开 AI 操作助手' }));
    const input = screen.getByRole('textbox', { name: '输入操作问题' });
    fireEvent.change(input, { target: { value: '怎样导入实验记录？' } });
    fireEvent.keyDown(input, { key: 'Enter' });
    expect(await screen.findByText('req-500')).toBeInTheDocument();
    expect(screen.getByText('请求编号：')).toBeInTheDocument();
  });

  it('drags the launcher and expanded panel independently', async () => {
    mount();
    act(() => useOperationAssistantStore.getState().setPosition('launcher', { x: 100, y: 100 }, true));
    const launcher = screen.getByRole('button', { name: '打开 AI 操作助手' });
    fireEvent.pointerDown(launcher, {
      pointerId: 1,
      pointerType: 'mouse',
      button: 0,
      clientX: 120,
      clientY: 120,
    });
    fireEvent.pointerMove(launcher, {
      pointerId: 1,
      pointerType: 'mouse',
      buttons: 1,
      clientX: 160,
      clientY: 155,
    });
    fireEvent.pointerUp(launcher, { pointerId: 1, pointerType: 'mouse', button: 0 });
    expect(useOperationAssistantStore.getState().launcherPosition).toEqual({ x: 140, y: 135 });

    await new Promise((resolve) => window.setTimeout(resolve, 1));
    fireEvent.click(launcher);
    const panel = screen.getByRole('dialog', { name: 'AI 操作助手' });
    act(() => useOperationAssistantStore.getState().setPosition('panel', { x: 200, y: 100 }, true));
    const header = panel.querySelector('.operation-assistant-header')!;
    fireEvent.pointerDown(header, {
      pointerId: 2,
      pointerType: 'mouse',
      button: 0,
      clientX: 220,
      clientY: 120,
    });
    fireEvent.pointerMove(header, {
      pointerId: 2,
      pointerType: 'mouse',
      buttons: 1,
      clientX: 250,
      clientY: 145,
    });
    fireEvent.pointerUp(header, { pointerId: 2, pointerType: 'mouse', button: 0 });
    expect(useOperationAssistantStore.getState().panelPosition).toEqual({ x: 230, y: 125 });
  });

  it('keeps Shift+Enter and IME composition from submitting and enforces the question limit', () => {
    const api = vi.spyOn(operationAssistantApi, 'stream').mockResolvedValue();
    mount();
    fireEvent.click(screen.getByRole('button', { name: '打开 AI 操作助手' }));
    const input = screen.getByRole('textbox', { name: '输入操作问题' });
    expect(input).toHaveAttribute('maxlength', '3000');
    fireEvent.change(input, { target: { value: '问题' } });
    fireEvent.keyDown(input, { key: 'Enter', shiftKey: true });
    fireEvent.keyDown(input, { key: 'Enter', isComposing: true });
    expect(api).not.toHaveBeenCalled();
  });
});
