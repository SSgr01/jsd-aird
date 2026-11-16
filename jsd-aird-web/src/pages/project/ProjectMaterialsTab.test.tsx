import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, expect, it, vi } from 'vitest';
import { AppProviders } from '@/app/providers/AppProviders';
import { useAuthStore } from '@/stores/auth-store';
import { testUser } from '@/test/auth-user';
import { qualityApi } from '@/services/quality/quality-api';
import { productionUploadApi } from '@/services/production-orders/production-upload-api';
import { listResearchTests } from '@/services/research-test/research-test-api';
import { ProjectMaterialsTab } from './ProjectMaterialsTab';

vi.mock('@/services/quality/quality-api', () => ({ qualityApi: { uploads: vi.fn() } }));
vi.mock('@/services/production-orders/production-upload-api', () => ({ productionUploadApi: { list: vi.fn() } }));
vi.mock('@/services/research-test/research-test-api', () => ({ listResearchTests: vi.fn(), downloadResearchTest: vi.fn() }));

beforeEach(() => {
  vi.clearAllMocks();
  useAuthStore.setState({ user: { ...testUser, permissions: ['quality.upload.view'] }, status: 'authenticated' });
  vi.mocked(qualityApi.uploads).mockResolvedValue({ items: [{ id: 'quality-1', originalName: '来料检测明细.xlsx' }], total: 1 } as Awaited<ReturnType<typeof qualityApi.uploads>>);
  vi.mocked(productionUploadApi.list).mockResolvedValue({ items: [{ id: 'production-1', originalName: '排产明细.xlsx' }], total: 1 } as Awaited<ReturnType<typeof productionUploadApi.list>>);
  vi.mocked(listResearchTests).mockResolvedValue({ items: [], total: 0, page: 1, size: 20, totalPages: 0 });
});
const open = () => render(<AppProviders><ProjectMaterialsTab projectId="project-1" /></AppProviders>);

it('loads only permitted domains and hides downloads for a reader', async () => {
  open();
  expect(await screen.findByText('来料检测明细.xlsx')).toBeInTheDocument();
  expect(productionUploadApi.list).not.toHaveBeenCalled();
  expect(listResearchTests).not.toHaveBeenCalled();
  expect(screen.queryByRole('tab', { name: /生产单|综合测试报告/ })).not.toBeInTheDocument();
  expect(screen.queryByText('下载')).not.toBeInTheDocument();
});

it('keeps an authorized domain readable when another domain fails', async () => {
  useAuthStore.setState({ user: { ...testUser, permissions: ['quality.upload.view', 'production.upload.view', 'production.upload.download'] } });
  vi.mocked(qualityApi.uploads).mockRejectedValueOnce(new Error('品管资料暂不可用'));
  open();
  await waitFor(() => expect(productionUploadApi.list).toHaveBeenCalled());
  fireEvent.click(screen.getByRole('tab', { name: /生产单/ }));
  expect(await screen.findByText('排产明细.xlsx')).toBeInTheDocument();
  expect(screen.getByText('下载')).toBeInTheDocument();
  expect(listResearchTests).not.toHaveBeenCalled();
});

it('shows no domain directory or counts without domain read permissions', async () => {
  useAuthStore.setState({ user: { ...testUser, permissions: ['project.materials.view'] } });
  open();
  expect(await screen.findByText('暂无可查阅的关联资料')).toBeInTheDocument();
  expect(qualityApi.uploads).not.toHaveBeenCalled();
  expect(productionUploadApi.list).not.toHaveBeenCalled();
  expect(listResearchTests).not.toHaveBeenCalled();
});
