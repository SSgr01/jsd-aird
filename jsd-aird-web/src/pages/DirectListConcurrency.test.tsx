import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { App } from 'antd';
import type * as AntDesign from 'antd';
import { MemoryRouter } from 'react-router-dom';
import { ProductionOrderListPage } from './production-orders/ProductionOrderListPage';
import { QualityDataPage } from './quality/QualityDataPage';
import { SpectrumViewPage } from './spectrum/SpectrumViewPage';
import { PartnerListPage } from './partners/PartnerListPage';

const mocks = vi.hoisted(() => ({
  production: vi.fn<(params: Record<string, unknown>, signal?: AbortSignal) => Promise<unknown>>(),
  quality: vi.fn<(params: Record<string, unknown>, signal?: AbortSignal) => Promise<unknown>>(),
  spectrum: vi.fn<(params: Record<string, unknown>, signal?: AbortSignal) => Promise<unknown>>(),
  partners: vi.fn<(params: Record<string, unknown>, signal?: AbortSignal) => Promise<unknown>>(),
}));
vi.mock('@/services/production-orders/production-upload-api', () => ({
  productionUploadApi: { list: mocks.production },
}));
vi.mock('@/services/quality/quality-api', () => ({
  qualityApi: {
    records: mocks.quality,
    types: () => Promise.resolve([{ id: 'standard', name: '标准', fields: [] }]),
    categories: () =>
      Promise.resolve([
        { id: 'category', name: '分类', businessType: 'standard', revision: 'a'.repeat(64) },
      ]),
  },
}));
vi.mock('@/services/spectrum/spectrum-api', () => ({
  spectrumApi: { listCharts: mocks.spectrum, categories: () => Promise.resolve([]) },
}));
vi.mock('@/services/partners/partner-api', () => ({
  getPartners: mocks.partners,
  createPartner: vi.fn(),
  deletePartner: vi.fn(),
}));
vi.mock('@/services/project/project-api', () => ({
  getProjects: () => Promise.resolve({ items: [] }),
  getProjectStages: () => Promise.resolve([]),
  getProjectTasks: () => Promise.resolve([]),
}));
vi.mock('./partners/PartnerPrototypeModals', () => ({ PartnerPrototypeModal: () => null }));
vi.mock('antd', async (importOriginal) => {
  const actual = await importOriginal<typeof AntDesign>();
  function Table(props: { dataSource?: Array<Record<string, unknown>> }) {
    const text = (value: unknown) =>
      typeof value === 'string' || typeof value === 'number' ? String(value) : '';
    return (
      <div>
        {props.dataSource?.map((row) => (
          <span key={text(row.id)}>
            {text(row.productionName ?? row.businessNo ?? row.title ?? row.name)}
          </span>
        ))}
      </div>
    );
  }
  return { ...actual, Table: Object.assign(Table, actual.Table) };
});
function deferred() {
  let resolve!: (value: unknown) => void;
  const promise = new Promise<unknown>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}
function page(name: string) {
  return {
    items: [
      {
        id: name,
        name,
        contacts: [],
        ownerNames: [],
        title: name,
        productionName: name,
        businessNo: name,
        displayName: name,
        originalName: 'fixture.xlsx',
        status: 'READY',
        sourceType: 'XLSX',
        categoryId: 'category',
        categoryName: '分类',
        categoryCode: 'TEST',
        fields: [],
        metadata: {},
        data: {},
        pageCount: 1,
        lockVersion: 0,
        revision: 'a'.repeat(64),
        createdAt: '2026-10-23T00:00:00Z',
        updatedAt: '2026-10-23T00:00:00Z',
      },
    ],
    page: 1,
    size: 20,
    total: 1,
    totalPages: 1,
  };
}
beforeEach(() => vi.clearAllMocks());
it.each([
  { name: 'production', Page: ProductionOrderListPage, read: mocks.production },
  { name: 'quality', Page: QualityDataPage, read: mocks.quality },
  { name: 'spectrum', Page: SpectrumViewPage, read: mocks.spectrum },
  { name: 'partners', Page: PartnerListPage, read: mocks.partners },
])(
  '$name ignores an old filter response after the new list has arrived',
  async ({ Page, read, name }) => {
    const old = deferred();
    let initial = true;
    read.mockImplementation((params) => {
      if (name === 'partners' && params.size === 100)
        return Promise.resolve({ items: [], page: 1, size: 100, total: 0 });
      if (initial) {
        initial = false;
        return old.promise;
      }
      return Promise.resolve(page('fresh-latest'));
    });
    render(
      <MemoryRouter>
        <App>
          <Page />
        </App>
      </MemoryRouter>,
    );
    await waitFor(() => expect(read.mock.calls.some((call) => call[1] !== undefined)).toBe(true));
    const placeholder =
      name === 'production'
        ? '生产单名称、订单号、品名或文件名'
        : name === 'quality'
          ? '搜索当前表格'
          : name === 'spectrum'
            ? '搜索图谱名称、样品或批号'
            : '搜索公司名称、简称、负责人';
    const textbox = await screen.findByPlaceholderText(placeholder);
    fireEvent.change(textbox, { target: { value: 'fresh' } });
    fireEvent.keyDown(textbox, { key: 'Enter', code: 'Enter' });
    await waitFor(() => expect(screen.queryAllByText(/fresh-latest/).length).toBeGreaterThan(0));
    await act(async () => {
      old.resolve(page('fresh-obsolete'));
      await Promise.resolve();
    });
    expect(screen.queryAllByText(/fresh-obsolete/)).toHaveLength(0);
    expect(screen.queryAllByText(/fresh-latest/).length).toBeGreaterThan(0);
    const first = read.mock.calls.find((call) => call[1] !== undefined)?.[1];
    expect(first?.aborted).toBe(true);
  },
);
