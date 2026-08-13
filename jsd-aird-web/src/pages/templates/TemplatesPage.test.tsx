import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

import { AppProviders } from '@/app/providers/AppProviders';
import type { TemplateListItem } from '@/features/template-workspace/types';
import { templateApi } from '@/services/templates/template-api';
import { TemplatesPage } from './TemplatesPage';

vi.mock('@/services/templates/template-api', () => ({
  templateApi: {
    list: vi.fn(),
    listFacets: vi.fn(),
    listCategories: vi.fn(),
    filterOptions: vi.fn(),
    exportCsv: vi.fn(),
  },
}));

// The service is mocked as a plain object; these references are mock functions, not context-sensitive methods.
// eslint-disable-next-line @typescript-eslint/unbound-method
const listMock = vi.mocked(templateApi.list);
// eslint-disable-next-line @typescript-eslint/unbound-method
const listFacetsMock = vi.mocked(templateApi.listFacets);
// eslint-disable-next-line @typescript-eslint/unbound-method
const listCategoriesMock = vi.mocked(templateApi.listCategories);
// eslint-disable-next-line @typescript-eslint/unbound-method
const filterOptionsMock = vi.mocked(templateApi.filterOptions);
// eslint-disable-next-line @typescript-eslint/unbound-method
const exportCsvMock = vi.mocked(templateApi.exportCsv);

const publishedWithDraft: TemplateListItem = {
  templateId: 'template-published',
  versionId: 'draft-version',
  templateCode: 'TPL-PUBLISHED',
  name: '已发布模板',
  category: '生命周期',
  categoryId: 'category-lifecycle',
  format: 'DOCX',
  status: 'PUBLISHED',
  versionNo: 2,
  currentPublishedVersionId: 'published-version',
  currentPublishedVersionNo: 1,
  draftVersionId: 'draft-version',
  draftVersionNo: 2,
  hasDraft: true,
  lockVersion: 3,
  updatedAt: '2026-08-13T00:00:00Z',
  createdAt: '2026-08-01T00:00:00Z',
  createdByName: '本地开发用户',
  issueCount: 0,
};

const retiredWithDraft: TemplateListItem = {
  ...publishedWithDraft,
  templateId: 'template-retired',
  versionId: 'retired-draft-version',
  templateCode: 'TPL-RETIRED',
  name: '已停用模板',
  status: 'RETIRED',
  currentPublishedVersionId: undefined,
  currentPublishedVersionNo: undefined,
  retiredVersionNo: 3,
  draftVersionId: 'retired-draft-version',
  draftVersionNo: 4,
  versionNo: 4,
};

function page(items: TemplateListItem[], total = items.length) {
  return { items, total, page: 1, size: 20, totalPages: total ? 1 : 0 };
}

describe('TemplatesPage catalog presentation', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    listMock.mockImplementation((params) => Promise.resolve(page(
      [publishedWithDraft, retiredWithDraft],
      params?.uncategorized ? 89 : params?.categoryId ? 5 : 94,
    )));
    listFacetsMock.mockResolvedValue({
      totalCount: 94,
      uncategorizedCount: 89,
      categoryCounts: [{ categoryId: 'category-lifecycle', count: 5 }],
    });
    listCategoriesMock.mockResolvedValue([
      { id: 'category-lifecycle', name: '生命周期', description: '生命周期模板', sortOrder: 1, templateCount: 5 },
      { id: 'category-empty', name: '空分类', description: '暂无模板', sortOrder: 2, templateCount: 0 },
    ]);
    filterOptionsMock.mockResolvedValue([{ id: 'creator-1', displayName: '本地开发用户' }]);
    exportCsvMock.mockResolvedValue(undefined);
  });

  it('uses facet counts and displays lifecycle status separately from both versions', async () => {
    render(<AppProviders><MemoryRouter><TemplatesPage /></MemoryRouter></AppProviders>);

    expect(await screen.findByText('已发布模板')).toBeInTheDocument();
    const allCard = screen.getByText('全部模板').closest('.catalog-category-card');
    const uncategorizedCard = screen.getByText('未分类').closest('.catalog-category-card');
    const emptyCard = screen.getByText('空分类').closest('.catalog-category-card');
    expect(allCard).not.toBeNull();
    expect(uncategorizedCard).not.toBeNull();
    expect(emptyCard).not.toBeNull();
    expect(within(allCard as HTMLElement).getByText('94')).toBeInTheDocument();
    expect(within(uncategorizedCard as HTMLElement).getByText('89')).toBeInTheDocument();
    expect(within(emptyCard as HTMLElement).getByText('0')).toBeInTheDocument();
    expect(screen.getByRole('columnheader', { name: '状态' })).toBeInTheDocument();
    expect(screen.getAllByText('有未发布修改')).toHaveLength(2);
    expect(screen.getByText('发布 V1')).toBeInTheDocument();
    expect(screen.getByText('草稿 V2')).toBeInTheDocument();
    expect(screen.getByText('停用 V3')).toBeInTheDocument();
    expect(screen.getByText('草稿 V4')).toBeInTheDocument();
    expect(screen.queryByText(/有草稿 V/)).not.toBeInTheDocument();

    const renameCategoryButton = screen.getByRole('button', { name: '重命名生命周期' });
    const categorySelector = renameCategoryButton.closest('.catalog-category-card')?.querySelector('.catalog-category-select');
    expect(categorySelector).not.toBeNull();
    expect(categorySelector?.contains(renameCategoryButton)).toBe(false);

    const createButton = screen.getByRole('button', { name: /新建模板/ });
    const exportButton = screen.getByRole('button', { name: /导出当前筛选结果/ });
    expect(createButton.compareDocumentPosition(exportButton) & Node.DOCUMENT_POSITION_FOLLOWING).not.toBe(0);

    fireEvent.click(screen.getAllByRole('checkbox')[1]!);
    const exportSelectedButton = await screen.findByRole('button', { name: /导出选中/ });
    fireEvent.click(exportSelectedButton);
    await waitFor(() => expect(exportCsvMock).toHaveBeenCalledWith(expect.objectContaining({
      templateIds: ['template-published'],
    })));
  }, 15_000);

  it('submits search explicitly and never sends the active category to facets', async () => {
    render(<AppProviders><MemoryRouter><TemplatesPage /></MemoryRouter></AppProviders>);
    await screen.findByText('已发布模板');
    const initialListCalls = listMock.mock.calls.length;
    const search = screen.getByPlaceholderText('名称或编码');

    fireEvent.change(search, { target: { value: '生命周期' } });
    await new Promise((resolve) => window.setTimeout(resolve, 0));
    expect(listMock).toHaveBeenCalledTimes(initialListCalls);
    fireEvent.keyDown(search, { key: 'Enter', code: 'Enter' });
    await waitFor(() => expect(listMock).toHaveBeenLastCalledWith(expect.objectContaining({ keyword: '生命周期' })));

    fireEvent.click(screen.getByRole('button', { name: /未分类/ }));
    await waitFor(() => expect(listMock).toHaveBeenLastCalledWith(expect.objectContaining({ uncategorized: true })));
    expect(await screen.findByText('共 89 个模板')).toBeInTheDocument();
    const lastFacetParams = listFacetsMock.mock.calls.at(-1)?.[0];
    expect(lastFacetParams).not.toHaveProperty('categoryId');
    expect(lastFacetParams).not.toHaveProperty('uncategorized');
  });

  it('ignores an older list response after a category switch', async () => {
    let resolveInitial: ((value: ReturnType<typeof page>) => void) | undefined;
    listMock.mockReset();
    listMock
      .mockImplementationOnce(() => new Promise((resolve) => { resolveInitial = resolve; }))
      .mockResolvedValueOnce(page([{ ...publishedWithDraft, templateId: 'new-result', name: '未分类新结果' }], 89));

    render(<AppProviders><MemoryRouter><TemplatesPage /></MemoryRouter></AppProviders>);
    fireEvent.click(await screen.findByRole('button', { name: /未分类/ }));
    expect(await screen.findByText('未分类新结果')).toBeInTheDocument();

    resolveInitial?.(page([{ ...publishedWithDraft, templateId: 'old-result', name: '过期旧结果' }], 94));
    await new Promise((resolve) => window.setTimeout(resolve, 0));
    expect(screen.queryByText('过期旧结果')).not.toBeInTheDocument();
  });

  it('reports export failures and restores the export button', async () => {
    exportCsvMock.mockRejectedValueOnce(new Error('导出服务不可用'));
    render(<AppProviders><MemoryRouter><TemplatesPage /></MemoryRouter></AppProviders>);
    await screen.findByText('已发布模板');

    const exportButton = screen.getByRole('button', { name: /导出当前筛选结果/ });
    fireEvent.click(exportButton);

    expect(await screen.findByText('导出服务不可用')).toBeInTheDocument();
    await waitFor(() => expect(exportButton).not.toBeDisabled());
  });
});
