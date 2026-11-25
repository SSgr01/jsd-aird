import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useNavigate } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { EditorHandle } from '@/features/template-workspace/types';
import { AppProviders } from '@/app/providers/AppProviders';
import { HttpError } from '@/services/http/errors';
import { ProductionWorkspacePage } from './production-orders/ProductionWorkspacePage';
import { ProductionUploadWorkspacePage } from './production-orders/ProductionUploadWorkspacePage';
import { ExperimentWorkspacePage } from './experiments/ExperimentWorkspacePage';

const mocks = vi.hoisted(() => ({
  order: { getEditModel: vi.fn(), downloadSnapshot: vi.fn(), listRevisions: vi.fn(), stageSnapshot: vi.fn(), saveDraft: vi.fn() },
  record: { get: vi.fn(), versions: vi.fn(), saveBatch: vi.fn() },
  experiment: { getExperiment: vi.fn(), listVersions: vi.fn(), saveExperiment: vi.fn(), actExperiment: vi.fn(), confirmExperimentTemplateData: vi.fn() },
  runtime: { revision: 0, reads: vi.fn(), mounts: 0, dirty: () => {}, text: 'before' },
}));
vi.mock('@/services/production-orders/production-order-api', () => ({ productionOrderApi: mocks.order }));
vi.mock('@/services/production-orders/production-upload-api', () => ({ productionOrderRecordApi: mocks.record, productionUploadApi: {}, productionStageLabel: (s: string) => s }));
vi.mock('@/services/experiments/experiment-api', () => mocks.experiment);
vi.mock('@/features/template-workspace/preload-editor', () => ({ preloadWorkspaceEditor: vi.fn() }));
vi.mock('@/components/auth/usePermission', () => ({ usePermission: () => true }));
vi.mock('@/components/auth/Can', () => ({ Can: ({ children }: { children: React.ReactNode }) => children }));
vi.mock('./production-orders/ProductionUploadFieldManagerPanel', () => ({ ProductionUploadFieldManagerPanel: () => null }));
vi.mock('./production-orders/ProductionUploadRecognitionStatusBar', () => ({ ProductionUploadRecognitionStatusBar: () => null }));
vi.mock('@/features/template-workspace/editor-runtime', async () => {
  const React = await import('react');
  const Editor = React.forwardRef<EditorHandle, { onDirty: () => void; onReady?: () => void }>((props, ref) => {
    mocks.runtime.dirty = props.onDirty;
    React.useEffect(() => { mocks.runtime.mounts++; props.onReady?.(); }, []);
    React.useImperativeHandle(ref, () => ({ getSnapshot: () => { mocks.runtime.reads(); return { id: 'doc', body: { dataStream: mocks.runtime.text + '\r\n' }, sheetOrder: ['s'], sheets: { s: { id: 's', cellData: {} } } }; }, flushChanges: () => {}, getContentRevision: () => mocks.runtime.revision, readBinding: () => undefined, writeBinding: async () => {}, focusBinding: () => {} }), []);
    return <div data-testid="editor">{mocks.runtime.text}</div>;
  });
  return { SheetsEditor: Editor, DocsEditor: Editor };
});
vi.mock('@/features/template-workspace/UniverSheetsEditor', async () => ({ UniverSheetsEditor: (await import('@/features/template-workspace/editor-runtime')).SheetsEditor }));

const workbook = { id: 'book', sheetOrder: ['s'], sheets: { s: { id: 's', cellData: {} } } };
function experiment(id = 'one') { return { summary: { id, title: id, status: 'DRAFT', sourceType: 'MANUAL', revision: 1 }, editModel: { title: id, documentFormat: 'word', documentSnapshot: { id: 'doc', body: { dataStream: 'before\r\n' } } } }; }
function record(id = 'one') { return { id, productionName: id, orderNo: id, originalName: 'order.xlsx', status: 'DRAFT', lockVersion: 1, workbookSnapshot: workbook, recognitionResult: { recognitionReview: { summary: {} } } }; }
function Go({ prefix }: { prefix: string }) { const go = useNavigate(); return <button onClick={() => go(`/${prefix}/two`)}>switch</button>; }
type Kind = 'production' | 'upload' | 'experiment';
function open(kind: Kind) {
  const prefix = kind[0]!;
  return render(<AppProviders><MemoryRouter initialEntries={[`/${prefix}/one`]} future={{ v7_startTransition: true, v7_relativeSplatPath: true }}><Go prefix={prefix} /><Routes>
    <Route path="/p/:orderId" element={<ProductionWorkspacePage />} /><Route path="/u/:uploadId" element={<ProductionUploadWorkspacePage />} /><Route path="/e/:id" element={<ExperimentWorkspacePage />} />
  </Routes></MemoryRouter></AppProviders>);
}
function edit(text: string, notify = true) { act(() => { mocks.runtime.text = text; mocks.runtime.revision++; if (notify) mocks.runtime.dirty(); }); }
function deferred<T>() { let resolve!: (value: T) => void; const promise = new Promise<T>(done => { resolve = done; }); return { promise, resolve }; }
beforeEach(() => {
  vi.clearAllMocks(); mocks.runtime.revision = 0; mocks.runtime.text = 'before'; mocks.runtime.mounts = 0;
  vi.spyOn(window, 'matchMedia').mockImplementation(query => ({ matches: query.includes('min-width'), media: query, onchange: null, addListener: () => {}, removeListener: () => {}, addEventListener: () => {}, removeEventListener: () => {}, dispatchEvent: () => false }));
  mocks.order.getEditModel.mockImplementation((id: string) => Promise.resolve({ orderId: id, orderNo: id, status: 'DRAFT', format: 'XLSX', schema: {}, mapping: [], data: {}, snapshotFileId: 'snapshot', lockVersion: 1 }));
  mocks.order.downloadSnapshot.mockResolvedValue(workbook); mocks.order.listRevisions.mockResolvedValue([]); mocks.order.stageSnapshot.mockResolvedValue({ fileId: 'staged', sha256: 'hash' }); mocks.order.saveDraft.mockResolvedValue({ lockVersion: 2 });
  mocks.record.get.mockImplementation((id: string) => Promise.resolve({ ...record(id), lockVersion: id === 'one' && mocks.record.saveBatch.mock.calls.length ? 2 : 1 })); mocks.record.versions.mockResolvedValue([]); mocks.record.saveBatch.mockResolvedValue({});
  mocks.experiment.getExperiment.mockImplementation((id: string) => Promise.resolve(experiment(id))); mocks.experiment.listVersions.mockResolvedValue([]); mocks.experiment.saveExperiment.mockResolvedValue(experiment());
  mocks.experiment.actExperiment.mockResolvedValue(undefined);
});

it('adopts data confirmation without reloading the workbook or resetting the editor', async () => {
  const draft = { ...experiment(), summary: { ...experiment().summary, sourceType: 'EXCEL_IMPORT' },
    editModel: { title: 'one', documentFormat: 'excel', documentSnapshot: { ...workbook,
      sheets: { s: { id: 's', name: '实验', cellData: { 0: { 0: { v: '样品' } } } } } },
      importDataReview: { confirmed: false } } };
  mocks.experiment.getExperiment.mockResolvedValue(draft);
  mocks.experiment.saveExperiment.mockImplementation(async () => {
    // Calculation/rendering can advance the editor counter without changing reviewed values.
    mocks.runtime.revision++;
    return { ...draft, summary: { ...draft.summary, revision: 2 } };
  });
  mocks.experiment.confirmExperimentTemplateData.mockResolvedValue({ ...draft,
    summary: { ...draft.summary, revision: 3 }, editModel: { ...draft.editModel,
      importDataReview: { confirmed: true, confirmedRevision: 3 } } });
  open('experiment');
  await screen.findByTestId('editor');
  const confirm = await screen.findByRole('button', { name: '完成数据核对' });
  await waitFor(() => expect(confirm).toBeEnabled());
  fireEvent.click(confirm);
  await waitFor(() => expect(mocks.experiment.confirmExperimentTemplateData).toHaveBeenCalledWith('one', 2));
  await waitFor(() => expect(screen.getAllByText('已核对').length).toBeGreaterThan(0));
  expect(mocks.experiment.getExperiment).toHaveBeenCalledTimes(1);
  expect(mocks.runtime.mounts).toBe(1);
  expect(confirm).toBeDisabled();
});

it.each(['EXCEL_IMPORT', 'OCR_IMPORT'])('content-only %s keeps the editor without review panels and can submit', async (sourceType) => {
  const draft = {
    ...experiment(),
    summary: { ...experiment().summary, sourceType },
    editModel: {
      ...experiment().editModel,
      documentFormat: 'excel',
      documentSnapshot: {
        ...workbook,
        sheets: { s: { id: 's', name: '原始实验', cellData: { 0: { 0: { v: '实验人' }, 1: { v: '张三' } } } } },
      },
      importProcessingMode: 'CONTENT_ONLY',
      sourceImportJobId: 'legacy-import',
      importDataReview: { confirmed: false },
      recognizedFields: [],
      recognizedRegions: [],
    },
  };
  mocks.experiment.getExperiment.mockResolvedValue(draft);
  open('experiment');
  await screen.findByTestId('editor');
  const submit = await screen.findByRole('button', { name: '提交审核' });
  await waitFor(() => expect(submit).toBeEnabled());
  expect(screen.queryByRole('complementary', { name: '字段数据核对' })).not.toBeInTheDocument();
  expect(screen.queryByText('本次实验数据')).not.toBeInTheDocument();
  expect(screen.queryByText('数据：待核对')).not.toBeInTheDocument();
  expect(screen.queryByText('AI 识别待确认')).not.toBeInTheDocument();
  expect(screen.getByTestId('editor').closest('.template-workspace-grid')).not.toHaveClass('has-field-bindings');
  fireEvent.click(submit);
  await waitFor(() => expect(mocks.experiment.actExperiment).toHaveBeenCalledWith('one', 'submit-review', 1));
});

for (const kind of ['production', 'upload', 'experiment'] as const) describe(`${kind} save lifecycle`, () => {
  const api = kind === 'production' ? mocks.order.saveDraft : kind === 'upload' ? mocks.record.saveBatch : mocks.experiment.saveExperiment;
  const button = () => screen.getAllByRole('button', { name: kind === 'production' ? /^save 保存$|^保存$/ : /保存草稿/ })[0]!;
  const ready = async () => { await screen.findByTestId('editor'); if (kind !== 'production') await waitFor(() => expect(button()).toBeEnabled()); };
  it('exports once on save, never during editing, and keeps the editor mounted', async () => {
    open(kind); await ready(); edit('saved'); expect(mocks.runtime.reads).not.toHaveBeenCalled();
    fireEvent.click(button()); await waitFor(() => expect(api).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(button()).not.toHaveClass('ant-btn-loading'));
    expect(mocks.runtime.reads).toHaveBeenCalledTimes(1); expect(mocks.runtime.mounts).toBe(1);
  });
  it('keeps a newer revision dirty while an earlier save is pending', async () => {
    const pending = deferred<unknown>(); api.mockReturnValue(pending.promise);
    open(kind); await ready(); edit('captured'); fireEvent.click(button());
    await waitFor(() => expect(api).toHaveBeenCalledTimes(1)); edit('newer', false);
    await act(async () => { pending.resolve(kind === 'experiment' ? experiment() : { lockVersion: 2 }); await pending.promise; });
    await waitFor(() => expect(screen.getAllByText(kind === 'production' ? '有未保存内容' : '未保存').length).toBeGreaterThan(0));
    expect(screen.getByTestId('editor')).toHaveTextContent('newer'); expect(mocks.runtime.mounts).toBe(1);
  });
  it('ignores completion after switching to a different document', async () => {
    const pending = deferred<unknown>(); api.mockReturnValue(pending.promise);
    open(kind); await ready(); edit('captured'); fireEvent.click(button()); await waitFor(() => expect(api).toHaveBeenCalledTimes(1));
    fireEvent.click(screen.getByText('switch')); await waitFor(() => expect(kind === 'production' ? mocks.order.getEditModel : kind === 'upload' ? mocks.record.get : mocks.experiment.getExperiment).toHaveBeenCalledWith('two'));
    await act(async () => { pending.resolve(kind === 'experiment' ? experiment('one') : { lockVersion: 20 }); await pending.promise; });
    expect(screen.getByTestId('editor')).toBeInTheDocument();
    expect(screen.queryByText('有未保存内容')).not.toBeInTheDocument();
  });
  it('retains content on a rejected write without automatically repeating that write', async () => {
    api.mockRejectedValue(new HttpError('write conflict', 'OPTIMISTIC_LOCK_CONFLICT', 409));
    open(kind); await ready(); edit('keep local'); fireEvent.click(button());
    await screen.findByText('write conflict');
    await act(async () => { await new Promise(done => window.setTimeout(done, 2200)); });
    expect(api).toHaveBeenCalledTimes(1); expect(screen.getByTestId('editor')).toHaveTextContent('keep local');
  });
});

it('does not adopt a later writer’s version returned by the production acknowledgement GET', async () => {
  mocks.record.get.mockResolvedValueOnce(record()).mockResolvedValueOnce({ ...record(), lockVersion: 3 });
  open('upload'); await screen.findByTestId('editor');
  await waitFor(() => expect(screen.getAllByRole('button', { name: /保存草稿/ })[0]).toBeEnabled());
  edit('keep my workbook'); fireEvent.click(screen.getAllByRole('button', { name: /保存草稿/ })[0]!);
  await screen.findByText('生产单已在其他位置更新，本地内容仍然保留');
  expect(screen.getAllByText('未保存').length).toBeGreaterThan(0);
  expect(screen.getByTestId('editor')).toHaveTextContent('keep my workbook');
});
