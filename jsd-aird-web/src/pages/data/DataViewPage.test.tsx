import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

import { AppProviders } from '@/app/providers/AppProviders';
import { dataApi } from '@/services/data/data-api';
import { DataViewPage } from './DataViewPage';

vi.mock('@/services/data/data-api', () => ({
  dataApi: {
    listAssets: vi.fn(),
    listCategories: vi.fn(),
    listTemplates: vi.fn(),
    exportAssets: vi.fn(),
  },
  dataTypeOptions: [
    { value: 'MATERIAL', label: '物料/原料' },
    { value: 'FORMULA', label: '配方' },
  ],
}));

// The data service is mocked as a plain object; these references are the mock functions, not context-sensitive methods.
// eslint-disable-next-line @typescript-eslint/unbound-method
const listAssetsMock = vi.mocked(dataApi.listAssets);
// eslint-disable-next-line @typescript-eslint/unbound-method
const listCategoriesMock = vi.mocked(dataApi.listCategories);
// eslint-disable-next-line @typescript-eslint/unbound-method
const listTemplatesMock = vi.mocked(dataApi.listTemplates);
// eslint-disable-next-line @typescript-eslint/unbound-method
const exportAssetsMock = vi.mocked(dataApi.exportAssets);

describe('DataViewPage batch export', () => {
  let restoreAnchorClick: (() => void) | undefined;

  beforeEach(() => {
    vi.clearAllMocks();
    const anchorPrototype = HTMLAnchorElement.prototype;
    const anchorClick = vi.spyOn(anchorPrototype, 'click').mockImplementation(() => undefined);
    restoreAnchorClick = () => anchorClick.mockRestore();
    vi.stubGlobal('URL', {
      createObjectURL: vi.fn().mockReturnValue('blob:test'),
      revokeObjectURL: vi.fn(),
    });
    listAssetsMock.mockResolvedValue({
      items: [
        { id: 'asset-1', targetDataType: 'MATERIAL', assetKey: 'M-1', displayName: '物料一', status: 'ACTIVE', updatedAt: '2026-08-10T00:00:00Z' },
        { id: 'asset-2', targetDataType: 'MATERIAL', assetKey: 'M-2', displayName: '物料二', status: 'ACTIVE', updatedAt: '2026-08-10T00:00:00Z' },
      ],
      page: 1,
      size: 20,
      total: 2,
      totalPages: 1,
    });
    listCategoriesMock.mockResolvedValue([]);
    listTemplatesMock.mockResolvedValue([
      { templateId: 'tpl-1', versionId: 'version-1', templateCode: 'material', name: '物料导出模板', category: '原料', targetDataType: 'MATERIAL', versionNo: 1, format: 'XLSX' },
    ]);
    exportAssetsMock.mockResolvedValue(new Blob(['zip'], { type: 'application/zip' }));
  });

  afterEach(() => {
    restoreAnchorClick?.();
    vi.unstubAllGlobals();
  });

  it('selects the current result, chooses a template, and downloads a ZIP', async () => {
    render(<AppProviders><MemoryRouter><DataViewPage /></MemoryRouter></AppProviders>);

    expect(await screen.findByText('物料一')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '选择当前结果' }));
    fireEvent.click(screen.getByRole('button', { name: /批量导出文件/ }));

    expect(await screen.findByText('批量导出 2 个资产')).toBeInTheDocument();
    await waitFor(() => expect(listTemplatesMock).toHaveBeenCalledWith('MATERIAL'));
    const templateSelect = screen.getByRole('combobox', { name: '导出模板' });
    fireEvent.mouseDown(templateSelect);
    fireEvent.click(await screen.findByText('物料导出模板 · material · V1'));
    fireEvent.click(screen.getByRole('button', { name: '生成 ZIP' }));

    await waitFor(() => expect(exportAssetsMock).toHaveBeenCalledWith({
      targetDataType: 'MATERIAL',
      templateVersionId: 'version-1',
      assetIds: ['asset-1', 'asset-2'],
    }));
    expect((URL as unknown as { createObjectURL: ReturnType<typeof vi.fn> }).createObjectURL).toHaveBeenCalled();
  });

  it('does not enable export until an asset is selected', async () => {
    render(<AppProviders><MemoryRouter><DataViewPage /></MemoryRouter></AppProviders>);
    await screen.findByText('物料一');
    expect(screen.getByRole('button', { name: /批量导出文件/ })).toBeDisabled();
  });
});
