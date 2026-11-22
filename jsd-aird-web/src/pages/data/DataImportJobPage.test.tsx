import { forwardRef, useImperativeHandle, type ReactNode } from 'react';
import { App } from 'antd';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { Link, MemoryRouter, Route, Routes } from 'react-router-dom';
import type { DataPreview, DataWorkbookSnapshot } from '@/services/data/data-api';
import type { WorkbookTemplateInstance } from '@/features/template-workspace/workbook-instance';
import { DataImportJobPage } from './DataImportJobPage';

const api = vi.hoisted(() => ({ preview: vi.fn(), getImportWorkbookSnapshot: vi.fn(), saveWorkbookDraft: vi.fn(), listCategories: vi.fn() }));
vi.mock('@/services/data/data-api', () => ({ dataApi: api }));
vi.mock('@/components/auth/usePermission', () => ({ usePermission: () => true }));
vi.mock('@/services/project/project-resource-api', () => ({ projectResourceApi: { links: () => Promise.resolve([]) } }));
vi.mock('@/features/template-workspace/instance-definition', () => ({ resolveInstanceDefinition: (_snapshot: unknown, instance: unknown) => instance }));
vi.mock('@/features/template-workspace/workbook-instance', () => ({ writeWorkbookInstance: () => {}, updateWorkbookField: () => {} }));
vi.mock('@/features/data-workbench/instance-workbook-view', () => ({ instanceWorkbookView: () => ({ fields: [], records: [], regions: [], fieldDefinitions: [] }) }));
vi.mock('@/features/data-workbench/DataPublicationPanel', () => ({ DataPublicationPanel: () => null }));
vi.mock('@/features/template-workspace/InstanceFieldEditor', () => ({ InstanceFieldEditor: () => null }));
vi.mock('@/components/file-preview', () => ({ FilePreviewModal: () => null, downloadPreviewFile: () => {} }));
vi.mock('@/features/data-workbench/DataWorkbench', () => ({
  DataWorkbenchShell: ({ title, actions, notice, canvas }: { title: string; actions: ReactNode; notice: ReactNode; canvas: ReactNode }) => <><h1>{title}</h1>{actions}{notice}{canvas}</>,
  DataFieldDataBrowser: () => null, DataFieldStructureBrowser: () => null,
  DataWorkbookCanvas: forwardRef(function Canvas({ instance, onDirty }: { instance: WorkbookTemplateInstance; onDirty: () => void }, ref) {
    useImperativeHandle(ref, () => ({ getSnapshot: () => ({ sheets: {} }), getTemplateInstance: () => instance }));
    return <button onClick={onDirty}>修改表格</button>;
  }),
}));

const instance: WorkbookTemplateInstance = { version: 2, model: { modelVersion: 1, fields: [], groups: [], blocks: [], semanticAnnotations: [] }, mapping: [], records: [], items: [], issues: [] };
const workbook: DataWorkbookSnapshot = { fileName: '配方.xlsx', format: 'XLSX', snapshot: { sheets: {} }, sheets: [], editable: true, regions: [], records: [], fields: [], templateInstance: instance };
function preview(id = 'one', revision = '1'): DataPreview {
  return { job: { id, revision, sourceFileId: id, sourceSha256: '', sourceFileName: `${id}.xlsx`, sourceFormat: 'XLSX', status: 'COMPLETED', progress: 100, createdAt: '', updatedAt: '' }, sheets: [], mappings: [], rows: [], issues: [] };
}
function deferred<T>() { let resolve!: (value: T) => void; const promise = new Promise<T>(done => { resolve = done; }); return { promise, resolve }; }
function mount() {
  render(<App><MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }} initialEntries={['/one']}><Link to="/two">切换记录</Link><Routes><Route path="/:id" element={<DataImportJobPage />} /></Routes></MemoryRouter></App>);
}
beforeEach(() => {
  vi.clearAllMocks();
  api.preview.mockImplementation((id: string) => Promise.resolve(preview(id)));
  api.getImportWorkbookSnapshot.mockResolvedValue(workbook);
  api.listCategories.mockResolvedValue([]);
  api.saveWorkbookDraft.mockResolvedValue({ job: preview('one', '2').job, workbook });
});
const dirtyNotice = '工作簿还有未保存的修改，可继续编辑或点击“保存草稿”。';

it('ignores an old preview after navigating to another record', async () => {
  const delayed = deferred<DataPreview>(); api.preview.mockReturnValueOnce(delayed.promise);
  mount(); fireEvent.click(screen.getByRole('link', { name: '切换记录' }));
  await screen.findByRole('heading', { name: 'two.xlsx' });
  await act(() => { delayed.resolve(preview()); return Promise.resolve(); });
  expect(screen.queryByRole('heading', { name: 'one.xlsx' })).not.toBeInTheDocument();
});

it('keeps later edits dirty and uses the acknowledged revision on the next save', async () => {
  const delayed = deferred<{ job: DataPreview['job']; workbook: DataWorkbookSnapshot }>();
  api.saveWorkbookDraft.mockReturnValueOnce(delayed.promise);
  mount(); await screen.findByRole('heading', { name: 'one.xlsx' });
  fireEvent.click(screen.getByRole('button', { name: '修改表格' }));
  fireEvent.click(screen.getByRole('button', { name: '保存草稿' }));
  await waitFor(() => expect(api.saveWorkbookDraft).toHaveBeenCalledTimes(1));
  // An editor event already queued when saving started must still be preserved.
  fireEvent.click(screen.getByRole('button', { name: '修改表格' }));
  api.preview.mockResolvedValue(preview('one', '2'));
  await act(() => { delayed.resolve({ job: preview('one', '2').job, workbook }); return Promise.resolve(); });
  expect(screen.getByText(dirtyNotice)).toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: '保存草稿' }));
  await waitFor(() => expect(api.saveWorkbookDraft).toHaveBeenCalledTimes(2));
  expect(api.saveWorkbookDraft.mock.calls[1]).toEqual(['one', expect.objectContaining({ revision: '2' }), false]);
  await waitFor(() => expect(screen.queryByText(dirtyNotice)).not.toBeInTheDocument());
});

it('does not reload the previous record when its save finishes after navigation', async () => {
  const delayed = deferred<{ job: DataPreview['job']; workbook: DataWorkbookSnapshot }>(); api.saveWorkbookDraft.mockReturnValueOnce(delayed.promise);
  mount(); await screen.findByRole('heading', { name: 'one.xlsx' });
  fireEvent.click(screen.getByRole('button', { name: '修改表格' })); fireEvent.click(screen.getByRole('button', { name: '保存草稿' }));
  await waitFor(() => expect(api.saveWorkbookDraft).toHaveBeenCalledTimes(1));
  fireEvent.click(screen.getByRole('link', { name: '切换记录' })); await screen.findByRole('heading', { name: 'two.xlsx' });
  await act(() => { delayed.resolve({ job: preview('one', '2').job, workbook }); return Promise.resolve(); });
  expect(screen.getByRole('heading', { name: 'two.xlsx' })).toBeInTheDocument();
  expect(api.preview.mock.calls.filter(([id]) => id === 'one')).toHaveLength(1);
});

it('preserves the unsaved workbook after a failed save and allows retry', async () => {
  api.saveWorkbookDraft.mockRejectedValueOnce(new Error('网络暂不可用'));
  mount(); await screen.findByRole('heading', { name: 'one.xlsx' });
  fireEvent.click(screen.getByRole('button', { name: '修改表格' })); fireEvent.click(screen.getByRole('button', { name: '保存草稿' }));
  await screen.findByText('草稿保存失败，当前未保存修改仍保留');
  expect(screen.getByText(dirtyNotice)).toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: '保存草稿' }));
  await waitFor(() => expect(screen.queryByText(dirtyNotice)).not.toBeInTheDocument());
  expect(api.saveWorkbookDraft).toHaveBeenCalledTimes(2);
});
