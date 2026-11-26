import { forwardRef, useImperativeHandle, type ReactNode } from 'react';
import { App } from 'antd';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { Link, MemoryRouter, Route, Routes } from 'react-router-dom';
import type { DataPreview, DataWorkbookSnapshot } from '@/services/data/data-api';
import type { WorkbookTemplateInstance } from '@/features/template-workspace/workbook-instance';
import { DataImportJobPage } from './DataImportJobPage';

const api = vi.hoisted(() => ({ preview: vi.fn(), getImportWorkbookSnapshot: vi.fn(), saveWorkbookDraft: vi.fn(), listCategories: vi.fn(), experimentSyncStatus: vi.fn(), commit: vi.fn() }));
vi.mock('@/services/data/data-api', () => ({ dataApi: api }));
vi.mock('@/components/auth/usePermission', () => ({ usePermission: () => true }));
vi.mock('@/services/project/project-resource-api', () => ({ projectResourceApi: { links: () => Promise.resolve([]) } }));
vi.mock('@/features/template-workspace/instance-definition', () => ({ resolveInstanceDefinition: (_snapshot: unknown, instance: unknown) => instance }));
vi.mock('@/features/template-workspace/workbook-instance', () => ({ writeWorkbookInstance: () => {}, updateWorkbookField: () => {} }));
vi.mock('@/features/data-workbench/import-workbook-loader', () => ({ buildImportWorkbookView: () => Promise.resolve({ fields: [], records: [], regions: [], fieldDefinitions: [] }) }));
vi.mock('@/features/data-workbench/DataPublicationPanel', () => ({ DataPublicationPanel: () => null }));
vi.mock('@/features/template-workspace/InstanceFieldEditor', () => ({ InstanceFieldEditor: () => null }));
vi.mock('@/components/file-preview', () => ({ FilePreviewModal: () => null, downloadPreviewFile: () => {} }));
vi.mock('@/features/data-workbench/DataWorkbench', () => ({
  DataWorkbenchShell: ({ title, actions, notice, canvas, panel, footer }: { title: string; actions: ReactNode; notice: ReactNode; canvas: ReactNode; panel: ReactNode; footer: ReactNode }) => <><h1>{title}</h1>{actions}{notice}{canvas}{panel}{footer}</>,
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
function deferred<T>() { let resolve!: (value: T) => void; let reject!: (error: Error) => void; const promise = new Promise<T>((done, fail) => { resolve = done; reject = fail; }); return { promise, resolve, reject }; }
function mount(workspaceMode: 'DATA' | 'EXPERIMENT' = 'DATA') {
  render(<App><MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }} initialEntries={['/one']}><Link to="/two">切换记录</Link><Routes><Route path="/:id" element={<DataImportJobPage workspaceMode={workspaceMode} />} /></Routes></MemoryRouter></App>);
}
beforeEach(() => {
  vi.clearAllMocks();
  api.preview.mockImplementation((id: string) => Promise.resolve(preview(id)));
  api.getImportWorkbookSnapshot.mockResolvedValue(workbook);
  api.listCategories.mockResolvedValue([]);
  api.saveWorkbookDraft.mockResolvedValue({ job: preview('one', '2').job, workbook });
  api.experimentSyncStatus.mockResolvedValue({ plan: { candidates: [], readyCount: 0 }, links: [] });
});
const dirtyNotice = '工作簿还有未保存的修改，可继续编辑或点击“保存草稿”。';


it('starts workbook loading before the summary finishes', async () => {
  const delayed = deferred<DataPreview>(); api.preview.mockReturnValueOnce(delayed.promise);
  mount();
  await waitFor(() => expect(api.getImportWorkbookSnapshot).toHaveBeenCalledTimes(1));
  expect(api.preview).toHaveBeenCalledWith('one', false, 'summary', expect.any(AbortSignal));
  await act(() => { delayed.resolve(preview()); return Promise.resolve(); });
  await screen.findByRole('button', { name: '保存草稿' });
});

it('shows task information while the workbook is still loading', async () => {
  const delayed = deferred<DataWorkbookSnapshot>();
  api.getImportWorkbookSnapshot.mockReturnValueOnce(delayed.promise);
  mount();
  await screen.findByRole('heading', { name: 'one.xlsx' });
  expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  expect(screen.queryByRole('button', { name: '保存草稿' })).not.toBeInTheDocument();
  await act(() => { delayed.resolve(workbook); return Promise.resolve(); });
  await screen.findByRole('button', { name: '保存草稿' });
});

it('keeps task information visible on snapshot failure and retries the workbook', async () => {
  api.getImportWorkbookSnapshot.mockRejectedValueOnce(new Error('读取超时'));
  mount();
  await screen.findByText('加载失败，请重试');
  expect(screen.getByRole('heading', { name: 'one.xlsx' })).toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: /重试/ }));
  await screen.findByRole('button', { name: '保存草稿' });
  expect(screen.queryByText('加载失败，请重试')).not.toBeInTheDocument();
});

it('cancels speculative workbook loading while the import is parsing', async () => {
  api.preview.mockResolvedValue({ ...preview(), job: { ...preview().job, status: 'PARSING' } });
  mount();
  await screen.findByRole('heading', { name: 'one.xlsx' });
  expect(api.getImportWorkbookSnapshot.mock.calls[0]?.[2].aborted).toBe(true);
});

it('ignores a stale workbook after navigating to another record', async () => {
  const delayed = deferred<DataWorkbookSnapshot>();
  api.getImportWorkbookSnapshot.mockReturnValueOnce(delayed.promise);
  mount(); await screen.findByRole('heading', { name: 'one.xlsx' });
  fireEvent.click(screen.getByRole('link', { name: '切换记录' }));
  await screen.findByRole('button', { name: '保存草稿' });
  await act(() => { delayed.reject(new Error('旧工作簿读取超时')); return Promise.resolve(); });
  expect(screen.getByRole('heading', { name: 'two.xlsx' })).toBeInTheDocument();
  expect(screen.queryByText('旧工作簿读取超时')).not.toBeInTheDocument();
});

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
  expect(api.getImportWorkbookSnapshot).toHaveBeenCalledTimes(1);
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
  await screen.findByText('草稿保存失败，当前修改仍保留');
  expect(screen.getByText(dirtyNotice)).toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: '保存草稿' }));
  await waitFor(() => expect(screen.queryByText(dirtyNotice)).not.toBeInTheDocument());
  expect(api.saveWorkbookDraft).toHaveBeenCalledTimes(2);
});

it('accepts a save acknowledgement without rebuilding the workbook and keeps its revision after refresh fails', async () => {
  api.saveWorkbookDraft.mockResolvedValueOnce({ job: preview('one', '2').job, workbook: null });
  mount(); await screen.findByRole('button', { name: '保存草稿' });
  api.preview.mockRejectedValueOnce(new Error('刷新暂不可用'));
  fireEvent.click(screen.getByRole('button', { name: '修改表格' }));
  fireEvent.click(screen.getByRole('button', { name: '保存草稿' }));
  await waitFor(() => expect(screen.queryByText(dirtyNotice)).not.toBeInTheDocument());
  expect(screen.queryByText('草稿保存失败，当前修改仍保留')).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: '修改表格' }));
  fireEvent.click(screen.getByRole('button', { name: '保存草稿' }));
  await waitFor(() => expect(api.saveWorkbookDraft).toHaveBeenCalledTimes(2));
  expect(api.saveWorkbookDraft.mock.calls[1]?.[1]?.revision).toBe('2');
  expect(api.getImportWorkbookSnapshot).toHaveBeenCalledTimes(1);
});

it('keeps the existing confirmation button visible after confirmation', async () => {
  mount('EXPERIMENT');
  expect(await screen.findByRole('button', { name: '完成数据核对' })).toBeDisabled();
  expect(screen.getByText('本次数据已完成核对')).toBeInTheDocument();
});

it('allows an earlier confirmed import to resume draft preparation when it never queued a job', async () => {
  api.preview.mockResolvedValue({ ...preview(), job: { ...preview().job, importContractVersion: 9 } });
  api.experimentSyncStatus.mockResolvedValue({ plan: { candidates: [{ assemblyKey: 'file', status: 'READY' }], readyCount: 1 }, links: [] });
  api.commit.mockResolvedValue(preview('one', '2').job);
  mount('EXPERIMENT');
  const button = await screen.findByRole('button', { name: '完成数据核对' });
  await waitFor(() => expect(button).toBeEnabled());
  fireEvent.click(button);
  await waitFor(() => expect(api.commit).toHaveBeenCalledWith('one', true, '1'));
});

it('acknowledges confirmation before a delayed status refresh finishes', async () => {
  const initial = { ...preview(), job: { ...preview().job, status: 'WAITING_CONFIRM' as const } };
  api.preview.mockResolvedValue(initial);
  api.commit.mockResolvedValue(preview('one', '2').job);
  mount('EXPERIMENT');
  const button = await screen.findByRole('button', { name: '完成数据核对' });
  await waitFor(() => expect(button).toBeEnabled());
  const delayed = deferred<DataPreview>();
  api.preview.mockReturnValueOnce(delayed.promise);
  fireEvent.click(button);
  await screen.findByText('本次数据已完成核对');
  expect(api.commit).toHaveBeenCalledWith('one', true, '1');
  expect(button).toBeDisabled();
  await act(() => { delayed.resolve(preview('one', '2')); return Promise.resolve(); });
});
