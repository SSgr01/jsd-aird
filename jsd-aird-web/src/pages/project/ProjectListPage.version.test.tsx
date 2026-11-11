import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { AppProviders } from '@/app/providers/AppProviders';
import { queryClient } from '@/services/query/query-client';
import { useAuthStore } from '@/stores/auth-store';
import { testUser } from '@/test/auth-user';
import * as api from '@/services/project/project-api';
import { ProjectListPage } from './ProjectListPage';

vi.mock('@/services/project/project-api', async (original) => ({
  ...await original<typeof api>(), getProjects: vi.fn(), deleteProjects: vi.fn(), copyProjects: vi.fn(),
}));

const first: api.Project = {
  id: 'project-one', projectCode: 'P-1', name: '原项目', version: 12,
  priority: 'MEDIUM', status: 'IN_PROGRESS', teamSize: 1, teamMembers: [],
};
const result = (project: api.Project) => ({ items: [project], page: 1, size: 10, total: 1, totalPages: 1 });
beforeEach(() => {
  queryClient.clear();
  useAuthStore.setState({ user: { ...testUser, permissions: [...testUser.permissions, 'project.list.create', 'project.list.delete', 'project.list.view'] }, status: 'authenticated' });
  vi.clearAllMocks();
  vi.mocked(api.getProjects).mockResolvedValue(result(first));
  vi.mocked(api.deleteProjects).mockResolvedValue(undefined);
});
const open = () => render(<AppProviders><MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}><ProjectListPage /></MemoryRouter></AppProviders>);
async function remoteUpdate() {
  vi.mocked(api.getProjects).mockResolvedValue(result({ ...first, name: '别人修改后的项目', version: 13 }));
  await act(async () => { await queryClient.refetchQueries({ queryKey: ['business'] }); });
  await screen.findByText('别人修改后的项目');
}

it('keeps the selected version through a background update and bulk confirmation', async () => {
  open();
  await screen.findByText('原项目');
  fireEvent.click(screen.getAllByRole('checkbox')[1]!);
  await remoteUpdate();
  fireEvent.click(screen.getAllByRole('button', { name: /删\s*除/ })[0]!);
  const dialog = await screen.findByRole('dialog');
  fireEvent.click(within(dialog).getByRole('button', { name: '删 除' }));
  await waitFor(() => expect(api.deleteProjects).toHaveBeenCalledWith([{ id: first.id, version: 12 }]));
});

it('keeps the revision shown when the row delete confirmation was opened', async () => {
  open();
  await screen.findByText('原项目');
  fireEvent.click(within(screen.getByRole('table')).getByRole('button', { name: /删\s*除/ }));
  await screen.findByText('确认删除该项目？');
  await remoteUpdate();
  fireEvent.click(screen.getByRole('button', { name: '确 定' }));
  await waitFor(() => expect(api.deleteProjects).toHaveBeenCalledWith([{ id: first.id, version: 12 }]));
});

it('offers bulk actions after selecting records and clears them with the selection', async () => {
  open();
  await screen.findByText('原项目');
  expect(screen.queryByRole('button', { name: /复制/ })).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('checkbox', { name: '选择项目 原项目' }));
  expect(screen.getByRole('button', { name: /复制/ })).toBeEnabled();
  expect(screen.getByRole('status')).toHaveTextContent('已选 1 项');
  fireEvent.click(screen.getByRole('button', { name: '清除已选' }));
  expect(screen.queryByRole('button', { name: /复制/ })).not.toBeInTheDocument();
  expect(screen.getByRole('checkbox', { name: '选择项目 原项目' })).not.toBeChecked();
});

it('copies a selection once while pending and clears it only after success', async () => {
  let finish!: () => void;
  vi.mocked(api.copyProjects).mockImplementation(() => new Promise<void>(resolve => { finish = resolve; }));
  open();
  await screen.findByText('原项目');
  fireEvent.click(screen.getByRole('checkbox', { name: '选择项目 原项目' }));
  const copy = screen.getByRole('button', { name: /复制/ });
  fireEvent.click(copy);
  fireEvent.click(copy);
  expect(api.copyProjects).toHaveBeenCalledTimes(1);
  expect(api.copyProjects).toHaveBeenCalledWith([first.id]);
  expect(screen.getByRole('checkbox', { name: '选择项目 原项目' })).toBeChecked();
  await act(async () => { finish(); });
  await waitFor(() => expect(screen.queryByRole('button', { name: /复制/ })).not.toBeInTheDocument());
});

it('retains the selection after a failed copy so the user can retry', async () => {
  vi.mocked(api.copyProjects).mockRejectedValueOnce(new Error('网络暂不可用')).mockResolvedValueOnce(undefined);
  open();
  await screen.findByText('原项目');
  fireEvent.click(screen.getByRole('checkbox', { name: '选择项目 原项目' }));
  fireEvent.click(screen.getByRole('button', { name: /复制/ }));
  await screen.findByText('网络暂不可用');
  expect(screen.getByRole('checkbox', { name: '选择项目 原项目' })).toBeChecked();
  await waitFor(() => expect(screen.getByRole('button', { name: /复制/ })).not.toHaveClass('ant-btn-loading'));
  fireEvent.click(screen.getByRole('button', { name: /复制/ }));
  await waitFor(() => expect(api.copyProjects).toHaveBeenCalledTimes(2));
  await waitFor(() => expect(screen.queryByRole('button', { name: /复制/ })).not.toBeInTheDocument());
});
