import { forwardRef, useEffect, useImperativeHandle, type ReactNode, type Ref } from 'react';
import { App } from 'antd';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { ExperimentWorkspacePage } from './ExperimentWorkspacePage';
import type { ExperimentDetail } from '@/services/experiments/experiment-api';

const api = vi.hoisted(() => ({ getExperiment: vi.fn(), listVersions: vi.fn(), getExperimentTemplateDataReview: vi.fn(),
  confirmExperimentTemplateData: vi.fn(), saveExperiment: vi.fn(), actExperiment: vi.fn(), getExperimentPresentation: vi.fn() }));
const editor = vi.hoisted(() => ({ revision: 0, mounts: 0 }));
vi.mock('@/services/experiments/experiment-api', async importOriginal => ({ ...await importOriginal<object>(), ...api }));
vi.mock('@/components/auth/usePermission', () => ({ usePermission: () => true }));
vi.mock('@/components/auth/Can', () => ({ Can: ({ children }: { children: ReactNode }) => children }));
vi.mock('@/features/template-workspace/preload-editor', () => ({ preloadWorkspaceEditor: () => {} }));
vi.mock('@/components/workspace-review/ReviewHistoryButton', () => ({ ReviewHistoryButton: () => null }));
vi.mock('@/features/template-workspace/InstanceFieldEditor', () => ({ InstanceFieldEditor: () => null }));
vi.mock('@/features/data-workbench/field-presentation', () => ({ presentWorkbookFields: (value: unknown) => value }));
vi.mock('@/features/experiment-workspace/template-workbench', () => ({ experimentTemplateWorkbook: () => ({ fields: [], records: [], regions: [] }) }));
vi.mock('@/features/data-workbench/DataWorkbench', () => ({ DataWorkbenchShell: (props: {
  title: string; rootRef: Ref<HTMLElement>; meta: ReactNode; actions: ReactNode; navigation: ReactNode; canvas: ReactNode; panel: ReactNode; content: ReactNode;
}) => <main ref={props.rootRef}><h1>{props.title}</h1>{props.meta}{props.actions}{props.navigation}{props.canvas}{props.panel}{props.content}</main> }));
vi.mock('./ExperimentTemplateDataPanel', () => ({ ExperimentTemplateDataPanel: ({ onConfirm, busy, editable }: { onConfirm: () => void; busy: boolean; editable: boolean }) =>
  <button disabled={busy || !editable} onClick={onConfirm}>完成数据核对</button> }));
vi.mock('@/features/template-workspace/editor-runtime', () => ({ SheetsEditor: forwardRef(function Editor({ snapshot, onDirty }: { snapshot: Record<string, unknown>; onDirty: () => void }, ref) {
  useEffect(() => { editor.mounts++; }, [snapshot]);
  useImperativeHandle(ref, () => ({ getSnapshot: () => snapshot, getContentRevision: () => editor.revision, flushChanges: () => {} }), [snapshot]);
  return <button onClick={() => { editor.revision++; onDirty(); }}>修改数据</button>;
}), DocsEditor: () => null }));

function detail(status = 'DRAFT', revision = 1): ExperimentDetail {
  const instance = { version: 2, model: { modelVersion: 1, fields: [], groups: [], blocks: [], semanticAnnotations: [] }, mapping: [], records: [], items: [], issues: [] };
  return { summary: { id: 'one', title: '实验一', experimentNo: 'EXP-1', sourceType: 'EXCEL_IMPORT', status, revision }, currentVersionId: 'current',
    editModel: { documentFormat: 'excel', sourceImportJobId: 'import', importDataReview: { confirmed: true, confirmedRevision: revision },
      documentSnapshot: { sheets: { s: { id: 's', name: '实验', custom: { labTemplate: { version: 1, fields: {} }, templateInstance: instance }, cellData: {} } } } },
  } as unknown as ExperimentDetail;
}
function mount(search = '') { render(<App><MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }} initialEntries={[`/experiments/one${search}`]}><ExperimentWorkspacePage experimentId="one" /></MemoryRouter></App>); }
beforeEach(() => {
  vi.clearAllMocks(); editor.revision = 0; editor.mounts = 0;
  vi.mocked(window.matchMedia).mockImplementation(query => ({ matches: true, media: query, addEventListener: vi.fn(), removeEventListener: vi.fn() }) as unknown as MediaQueryList);
  api.getExperiment.mockResolvedValue(detail()); api.listVersions.mockResolvedValue([]);
  api.getExperimentPresentation.mockReturnValue(new Promise(() => {}));
  api.confirmExperimentTemplateData.mockResolvedValue(detail('DRAFT', 2));
  api.saveExperiment.mockResolvedValue(detail('DRAFT', 2));
});

it('opens the authoritative embedded workbook without loading history or the same review structure again', async () => {
  mount(); await screen.findByRole('button', { name: /保存草稿/ });
  expect(api.listVersions).not.toHaveBeenCalled(); expect(api.getExperimentTemplateDataReview).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole('tab', { name: '版本记录' }));
  await waitFor(() => expect(api.listVersions).toHaveBeenCalledTimes(1));
});

it('skips an unchanged draft save and confirms the already saved revision directly', async () => {
  mount(); fireEvent.click(await screen.findByRole('button', { name: /保存草稿/ }));
  await act(async () => { await Promise.resolve(); });
  expect(api.saveExperiment).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole('button', { name: '完成数据核对' }));
  await waitFor(() => expect(api.confirmExperimentTemplateData).toHaveBeenCalledWith('one', 1));
  expect(api.saveExperiment).not.toHaveBeenCalled();
  expect(editor.mounts).toBe(1);
});

it('initializes a read-only workbook once after its projection arrives', async () => {
  const source = detail('COMPLETED');
  api.getExperiment.mockResolvedValue(source);
  let resolve!: (value: unknown) => void;
  api.getExperimentPresentation.mockReturnValue(new Promise(done => { resolve = done; }));
  mount();
  await waitFor(() => expect(api.getExperimentPresentation).toHaveBeenCalledTimes(1));
  expect(editor.mounts).toBe(0);
  await act(async () => { resolve({ snapshot: structuredClone(source.editModel.documentSnapshot), locationMap: {} }); });
  await screen.findByRole('button', { name: '修改数据' });
  expect(editor.mounts).toBe(1);
});

it('uses workflow responses through submit, approve and publish while projection loads independently', async () => {
  api.actExperiment.mockImplementation((_id: string, action: string, revision: number) =>
    Promise.resolve(detail(action === 'submit-review' ? 'PENDING_REVIEW' : action === 'approve' ? 'APPROVED' : 'COMPLETED', revision + 1)));
  mount(); fireEvent.click(await screen.findByRole('button', { name: '提交审核' }));
  fireEvent.click(await screen.findByRole('button', { name: '审核通过' }));
  fireEvent.click(await screen.findByRole('button', { name: /^发\s*布$/ }));
  await screen.findByRole('button', { name: '创建修订' });
  expect(api.actExperiment.mock.calls.map(args => args.slice(1))).toEqual([['submit-review', 1], ['approve', 2], ['publish', 3]]);
  expect(api.getExperiment).toHaveBeenCalledTimes(1); expect(api.listVersions).not.toHaveBeenCalled();
  expect(api.getExperimentTemplateDataReview).not.toHaveBeenCalled(); expect(editor.mounts).toBe(1);
  expect(screen.getByText('数据：已核对')).toBeInTheDocument();
});

it('persists a real edit before confirmation and uses its acknowledged revision', async () => {
  mount(); fireEvent.click(await screen.findByRole('button', { name: '修改数据' }));
  fireEvent.click(screen.getByRole('button', { name: '完成数据核对' }));
  await waitFor(() => expect(api.confirmExperimentTemplateData).toHaveBeenCalledWith('one', 2));
  expect(api.saveExperiment).toHaveBeenCalledTimes(1);
});

it('validates an explicitly requested historical version before opening it', async () => {
  mount('?version_id=not-visible');
  await screen.findByText('引用的实验版本已不可用，请重新核对来源。');
  expect(api.listVersions).toHaveBeenCalledTimes(1);
  expect(screen.queryByRole('button', { name: '修改数据' })).not.toBeInTheDocument();
});
