import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AppProviders } from '@/app/providers/AppProviders';
import { ResearchTestListPage } from './ResearchTestListPage';
import { useAuthStore } from '@/stores/auth-store';
import type { PageResponse } from '@/types/api';
import type { ResearchTestSummary } from '@/services/research-test/research-test-api';
import type { TemplateListItem } from '@/features/template-workspace/types';

const listMock = vi.fn<() => Promise<PageResponse<ResearchTestSummary>>>();
const templateListMock = vi.fn<() => Promise<PageResponse<TemplateListItem>>>(() => Promise.resolve({
  items: [], page: 1, size: 100, total: 0, totalPages: 0,
}));
const nextReportNumberMock = vi.fn(() => Promise.resolve('CTR-20261028-001'));
const nextStandardNumberMock = vi.fn(() => Promise.resolve('STD-20261028-001'));
vi.mock('@/services/research-test/research-test-api', () => ({
  listResearchTests: () => listMock(),
  createResearchTest: vi.fn(),
  nextReportNumber: () => nextReportNumberMock(),
  nextStandardNumber: () => nextStandardNumberMock(),
  copyResearchTest: vi.fn(),
  deleteResearchTest: vi.fn(),
}));
vi.mock('@/services/templates/template-api', () => ({
  templateApi: { list: () => templateListMock(), listCategories: () => Promise.resolve([]) },
}));
describe('ResearchTestListPage', () => {
  beforeEach(() => {
    templateListMock.mockResolvedValue({ items: [], page: 1, size: 100, total: 0, totalPages: 0 });
    useAuthStore.setState({ user: {
      userId: 'user-1', organizationId: 'org-1', organizationName: 'Test',
      username: 'tester', displayName: '当前测试用户', status: 'ACTIVE', authVersion: 1,
      permissions: ['research-test.report.view.create', 'research-test.standard.create'],
    } });
    listMock.mockResolvedValue({
      items: [
        {
          id: 'r1',
          recordType: 'REPORT',
          businessNo: 'CTR-001',
          name: '综合测试报告',
          documentFormat: 'WORD',
          sourceType: 'BLANK',
          status: 'DRAFT',
          visibility: 'ALL',
          versionNo: 1,
          lockVersion: 0,
          createdAt: '2026-08-31',
          updatedAt: '2026-08-31',
        },
      ],
      page: 1,
      size: 10,
      total: 1,
      totalPages: 1,
    });
  });
  it('renders persisted reports and core actions', async () => {
    render(
      <AppProviders>
        <MemoryRouter>
          <ResearchTestListPage type="REPORT" />
        </MemoryRouter>
      </AppProviders>,
    );
    expect(await screen.findByRole('heading', { name: '综合测试报告' })).toBeInTheDocument();
    await waitFor(() => expect(listMock).toHaveBeenCalled());
    expect(screen.getByRole('button', { name: /新增报告/ })).toBeInTheDocument();
    expect(screen.getByText('CTR-001')).toBeInTheDocument();
  });

  it('switches standard creation mode and document format', async () => {
    render(
      <AppProviders>
        <MemoryRouter>
          <ResearchTestListPage type="STANDARD" />
        </MemoryRouter>
      </AppProviders>,
    );
    fireEvent.click(await screen.findByRole('button', { name: /新增测试标准/ }));
    fireEvent.click(screen.getByRole('button', { name: /选择模板新建/ }));
    expect(await screen.findByText('模板分类')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /空白新建/ }));
    fireEvent.click(screen.getByRole('button', { name: /Excel 测试标准/ }));
    expect(screen.getByRole('button', { name: /Excel 测试标准/ })).toHaveClass('active');
  }, 60_000);

  it('defaults report owner to the signed-in user and omits file import', async () => {
    render(
      <AppProviders>
        <MemoryRouter>
          <ResearchTestListPage type="REPORT" />
        </MemoryRouter>
      </AppProviders>,
    );
    fireEvent.click(await screen.findByRole('button', { name: /新增报告/ }));
    expect(screen.getByRole('textbox', { name: '负责人' })).toHaveValue('当前测试用户');
    expect(await screen.findByRole('textbox', { name: '报告编号' })).toHaveValue('CTR-20261028-001');
    expect(screen.queryByRole('button', { name: /导入文件/ })).not.toBeInTheDocument();
  });

  it('omits file import mode for a new standard', async () => {
    render(
      <AppProviders>
        <MemoryRouter>
          <ResearchTestListPage type="STANDARD" />
        </MemoryRouter>
      </AppProviders>,
    );
    fireEvent.click(await screen.findByRole('button', { name: /新增测试标准/ }));
    expect(screen.queryByRole('button', { name: /导入文件/ })).not.toBeInTheDocument();
    expect(await screen.findByRole('textbox', { name: '标准编号' })).toHaveValue('STD-20261028-001');
  });

  it.each(['REPORT', 'STANDARD'] as const)('lists published templates without name keywords for %s', async (type) => {
    templateListMock.mockResolvedValue({
      items: [{ templateId: 't1', versionId: 'v1', currentPublishedVersionId: 'published-v1', templateCode: 'T1', name: '通用表单',
        format: 'DOCX', status: 'PUBLISHED', versionNo: 1, lockVersion: 1, revision: 'r1',
        updatedAt: '', issueCount: 0, hasDraft: false, createdAt: '' }],
      page: 1, size: 100, total: 1, totalPages: 1,
    });
    render(<AppProviders><MemoryRouter><ResearchTestListPage type={type} /></MemoryRouter></AppProviders>);
    fireEvent.click(await screen.findByRole('button', { name: type === 'REPORT' ? /新增报告/ : /新增测试标准/ }));
    fireEvent.click(screen.getByRole('button', { name: /选择模板新建/ }));
    await waitFor(() => expect(templateListMock).toHaveBeenCalled());
    fireEvent.mouseDown(screen.getByRole('combobox', { name: '选择模板' }));
    expect(await screen.findByText(/通用表单/)).toBeInTheDocument();
  });
});
