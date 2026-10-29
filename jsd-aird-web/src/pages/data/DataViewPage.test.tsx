import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

import { AppProviders } from '@/app/providers/AppProviders';
import { dataApi } from '@/services/data/data-api';
import { DataViewPage } from './DataViewPage';
import { testUser } from '@/test/auth-user';
import { useAuthStore } from '@/stores/auth-store';
import { queryClient } from '@/services/query/query-client';
import { HttpError } from '@/services/http/errors';

vi.mock('@/services/data/data-api', () => ({
  dataApi: {
    listSourceFiles: vi.fn(),
    listCategories: vi.fn(),
    sourceFacets: vi.fn(),
    listTemplates: vi.fn(),
    sourceBlob: vi.fn(),
    assignSourceCategory: vi.fn(),
    createCategory: vi.fn(),
    renameCategory: vi.fn(),
    deleteCategory: vi.fn(),
  },
}));

vi.mock('@/services/project/project-api', () => ({
  getProjects: vi.fn().mockResolvedValue({ items: [], page: 1, size: 100, total: 0, totalPages: 0 }),
}));

const listSourceFilesMock = vi.mocked(dataApi.listSourceFiles);
const listCategoriesMock = vi.mocked(dataApi.listCategories);
const sourceFacetsMock = vi.mocked(dataApi.sourceFacets);
const listTemplatesMock = vi.mocked(dataApi.listTemplates);
const testRouterFuture = { v7_startTransition: true, v7_relativeSplatPath: true } as const;

describe('DataViewPage source-file list', () => {
  beforeEach(() => {
    queryClient.clear();
    useAuthStore.setState({ user: testUser, status: 'authenticated' });
    vi.clearAllMocks();
    listSourceFilesMock.mockResolvedValue({
      items: [{
        importJobId: 'job-1', revision: 'a'.repeat(64), fileObjectId: 'file-1', originalName: '检测报告.xlsx', sourceFormat: 'XLSX',
        templateVersionId: 'version-1', categoryId: 'cat-1', categoryName: '检测标准', status: 'COMPLETED', progress: 100,
        recognitionMode: 'TEMPLATE_GUIDED',
        formalStatus: 'CONFIRMED',
        createdAt: '2026-08-10T00:00:00Z', updatedAt: '2026-08-10T00:00:00Z',
      }], page: 1, size: 20, total: 1, totalPages: 1,
    });
    listCategoriesMock.mockResolvedValue([{ id: 'cat-1', revision: 'a'.repeat(64), name: '检测标准', sortOrder: 1, sourceCount: 17 }]);
    sourceFacetsMock.mockResolvedValue({
      categories: [{ id: 'cat-1', revision: 'a'.repeat(64), name: '检测标准', sortOrder: 1, sourceCount: 17 }],
      totalSourceCount: 23,
      uncategorizedSourceCount: 6,
    });
    listTemplatesMock.mockResolvedValue([{ templateId: 'tpl-1', versionId: 'version-1', templateCode: 'test', name: '检测模板', category: '检测', versionNo: 1, format: 'XLSX' }]);
  });

  it('shows one row for the source file and its import summary', async () => {
    render(<AppProviders><MemoryRouter future={testRouterFuture}><DataViewPage /></MemoryRouter></AppProviders>);

    expect(await screen.findByText('检测报告.xlsx')).toBeInTheDocument();
    expect(screen.getAllByText('检测标准').length).toBeGreaterThan(0);
    expect(screen.getByText('检测模板 · test · V1')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /全部来源文件/ })).toHaveTextContent('23');
    expect(screen.getByRole('button', { name: /未分类/ })).toHaveTextContent('6');
    expect(screen.getByRole('button', { name: /检测标准/ })).toHaveTextContent('17');
    expect(screen.getByText('解析进度', { selector: 'th' })).toBeInTheDocument();
    expect(screen.getByText('100%')).toBeInTheDocument();
    expect(screen.queryByText('工作表', { selector: 'th' })).not.toBeInTheDocument();
    expect(screen.queryByText('识别记录', { selector: 'th' })).not.toBeInTheDocument();
    expect(screen.queryByText('字段值', { selector: 'th' })).not.toBeInTheDocument();
    expect(screen.queryByText('资产名称')).not.toBeInTheDocument();
    expect(screen.queryByText('数据类型')).not.toBeInTheDocument();
    expect(screen.queryByText('资产编码')).not.toBeInTheDocument();
  }, 30_000);

  it('loads source files with category-only filters', async () => {
    render(<AppProviders><MemoryRouter future={testRouterFuture}><DataViewPage /></MemoryRouter></AppProviders>);
    await screen.findByText('检测报告.xlsx');
    expect(listSourceFilesMock).toHaveBeenCalledWith(expect.objectContaining({ categoryId: undefined }), expect.any(AbortSignal));
  }, 10_000);

  it('silently keeps readable files visible when category access is forbidden', async () => {
    listCategoriesMock.mockRejectedValue(new HttpError('分类无权限','PERMISSION_DENIED',403));
    render(<AppProviders><MemoryRouter future={testRouterFuture}><DataViewPage /></MemoryRouter></AppProviders>);
    expect(await screen.findByText('检测报告.xlsx')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.queryByText(/分类或筛选项|暂无权限查看此内容|重试/)).not.toBeInTheDocument();
    expect(screen.getByRole('button',{name:/全部来源文件/})).toHaveTextContent('23');
  });

  it('retains cached rows without a banner or retry button after a background read fails', async () => {
    const first=render(<AppProviders><MemoryRouter future={testRouterFuture}><DataViewPage /></MemoryRouter></AppProviders>);
    await screen.findByText('检测报告.xlsx'); first.unmount();
    listSourceFilesMock.mockRejectedValue(new HttpError('offline','NETWORK'));
    render(<AppProviders><MemoryRouter future={testRouterFuture}><DataViewPage /></MemoryRouter></AppProviders>);
    expect(screen.getByText('检测报告.xlsx')).toBeInTheDocument();
    await waitFor(()=>expect(listSourceFilesMock).toHaveBeenCalledTimes(3),{timeout:4000});
    expect(screen.getByText('检测报告.xlsx')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.queryByText(/更新失败|加载失败|网络不可用|正在更新|重试/)).not.toBeInTheDocument();
  });
});
