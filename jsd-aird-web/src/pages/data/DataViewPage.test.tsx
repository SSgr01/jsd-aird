import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

import { AppProviders } from '@/app/providers/AppProviders';
import { dataApi } from '@/services/data/data-api';
import { DataViewPage } from './DataViewPage';

vi.mock('@/services/data/data-api', () => ({
  dataApi: {
    listSourceFiles: vi.fn(),
    listCategories: vi.fn(),
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
const listTemplatesMock = vi.mocked(dataApi.listTemplates);
const testRouterFuture = { v7_startTransition: true, v7_relativeSplatPath: true } as const;

describe('DataViewPage source-file list', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    listSourceFilesMock.mockResolvedValue({
      items: [{
        importJobId: 'job-1', fileObjectId: 'file-1', originalName: '检测报告.xlsx', sourceFormat: 'XLSX',
        templateVersionId: 'version-1', categoryId: 'cat-1', categoryName: '检测标准', status: 'COMPLETED', progress: 100,
        createdAt: '2026-08-10T00:00:00Z', updatedAt: '2026-08-10T00:00:00Z',
      }], page: 1, size: 20, total: 1, totalPages: 1,
    });
    listCategoriesMock.mockResolvedValue([{ id: 'cat-1', name: '检测标准', sortOrder: 1, sourceCount: 1 }]);
    listTemplatesMock.mockResolvedValue([{ templateId: 'tpl-1', versionId: 'version-1', templateCode: 'test', name: '检测模板', category: '检测', versionNo: 1, format: 'XLSX' }]);
  });

  it('shows one row for the source file and its import summary', async () => {
    render(<AppProviders><MemoryRouter future={testRouterFuture}><DataViewPage /></MemoryRouter></AppProviders>);

    expect(await screen.findByText('检测报告.xlsx')).toBeInTheDocument();
    expect(screen.getAllByText('检测标准').length).toBeGreaterThan(0);
    expect(screen.getByText('检测模板 · test · V1')).toBeInTheDocument();
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
    expect(listSourceFilesMock).toHaveBeenCalledWith(expect.objectContaining({ categoryId: undefined }));
  }, 10_000);
});
