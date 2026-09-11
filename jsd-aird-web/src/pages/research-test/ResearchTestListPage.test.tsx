import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AppProviders } from '@/app/providers/AppProviders';
import { ResearchTestListPage } from './ResearchTestListPage';
import type { PageResponse } from '@/types/api';
import type { ResearchTestSummary } from '@/services/research-test/research-test-api';

const listMock = vi.fn<() => Promise<PageResponse<ResearchTestSummary>>>();
const templateListMock = vi.fn(() => Promise.resolve({ items: [] }));
vi.mock('@/services/research-test/research-test-api', () => ({
  listResearchTests: () => listMock(),
  createResearchTest: vi.fn(),
  copyResearchTest: vi.fn(),
  deleteResearchTest: vi.fn(),
}));
vi.mock('@/services/templates/template-api', () => ({
  templateApi: { list: () => templateListMock() },
}));
describe('ResearchTestListPage', () => {
  beforeEach(() =>
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
    }),
  );
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
  }, 15_000);

  it('offers file import mode for a new standard', async () => {
    render(
      <AppProviders>
        <MemoryRouter>
          <ResearchTestListPage type="STANDARD" />
        </MemoryRouter>
      </AppProviders>,
    );
    fireEvent.click(await screen.findByRole('button', { name: /新增测试标准/ }));
    fireEvent.click(screen.getByRole('button', { name: /导入文件/ }));
    expect(screen.getByText(/支持 DOC、DOCX、XLS、XLSX/)).toBeInTheDocument();
  });
});
