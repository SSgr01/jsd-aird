import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useNavigate } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AppProviders } from '@/app/providers/AppProviders';
import { TemplateWorkspacePage } from './TemplateWorkspacePage';
import { HttpError } from '@/services/http/errors';
import type { EditorHandle } from '@/features/template-workspace/types';

const { api, runtime } = vi.hoisted(() => ({
  api: { getEditModel: vi.fn(), listVersions: vi.fn(), review: vi.fn(), stageSnapshot: vi.fn(), saveDraft: vi.fn(), getRecognitionReview: vi.fn() },
  runtime: { revision: 0, snapshot: { body: { dataStream: 'before\r\n' }, snapshotFormatVersion: 5 }, dirty: () => {}, reads: vi.fn(), mounts: 0 },
}));
vi.mock('@/services/templates/template-api', () => ({ templateApi: api }));
vi.mock('@/features/template-workspace/preload-editor', () => ({ preloadWorkspaceEditor: vi.fn() }));
vi.mock('@/features/template-workspace/editor-runtime', async () => {
  const React = await import('react');
  const Editor = React.forwardRef<EditorHandle, { onDirty: () => void }>((props, ref) => {
    runtime.dirty = props.onDirty;
    React.useEffect(() => { runtime.mounts++; }, []);
    React.useImperativeHandle(ref, () => ({ getSnapshot: () => { runtime.reads(); return runtime.snapshot; }, getContentRevision: () => runtime.revision, flushChanges: () => {}, readBinding: () => undefined, writeBinding: async () => {}, focusBinding: () => {} }), []);
    return <div data-testid="live-editor">{runtime.snapshot.body.dataStream}</div>;
  });
  return { DocsEditor: Editor, SheetsEditor: Editor };
});
function Go() { const go = useNavigate(); return <button onClick={() => go('/templates/version-2/workspace')}>switch-document</button>; }
function open() { return render(<AppProviders><MemoryRouter initialEntries={['/templates/version-1/workspace']} future={{ v7_startTransition: true, v7_relativeSplatPath: true }}><Go /><Routes><Route path="/templates/:versionId/workspace" element={<TemplateWorkspacePage />} /></Routes></MemoryRouter></AppProviders>); }
function edit(text: string, notify = true) { act(() => { runtime.snapshot.body.dataStream = text; runtime.revision++; if (notify) runtime.dirty(); }); }
function deferred<T>() { let resolve!: (value: T) => void; const promise = new Promise<T>(done => { resolve = done; }); return { promise, resolve }; }
beforeEach(() => {
  vi.clearAllMocks(); runtime.revision = 0; runtime.snapshot = { body: { dataStream: 'before\r\n' }, snapshotFormatVersion: 5 }; runtime.mounts = 0;
  vi.spyOn(window, 'matchMedia').mockImplementation(query => ({ matches: query.includes('min-width'), media: query, onchange: null, addListener: () => {}, removeListener: () => {}, addEventListener: () => {}, removeEventListener: () => {}, dispatchEvent: () => false }));
  api.getEditModel.mockImplementation((versionId: string) => Promise.resolve({ templateId: 'template', versionId, name: versionId, versionNo: 1, format: 'DOCX', status: 'DRAFT', schema: {}, mapping: [], data: {}, inlineSnapshot: runtime.snapshot, snapshotFormatVersion: 5, lockVersion: 0, workspaceHash: 'base' }));
  api.listVersions.mockResolvedValue([]); api.review.mockResolvedValue({ status: 'NOT_SUBMITTED', lockVersion: 0, events: [] });
  api.stageSnapshot.mockResolvedValue({ fileId: 'staged', sha256: 'hash' });
  api.saveDraft.mockResolvedValue({ lockVersion: 1, workspaceHash: 'saved' });
});

describe('template save without per-edit snapshot reads', () => {
  it('does not export during typing and captures one immutable upload per save', async () => {
    open(); await screen.findByTestId('live-editor');
    edit('captured\r\n'); expect(runtime.reads).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: /保存草稿/ }));
    await waitFor(() => expect(api.saveDraft).toHaveBeenCalledTimes(1));
    expect(runtime.reads).toHaveBeenCalledTimes(1);
    expect(api.stageSnapshot.mock.calls[0]![0]).toBeInstanceOf(Blob);
    await waitFor(() => expect(screen.getByRole('button', { name: /保存草稿/ })).toBeDisabled());
    expect(runtime.mounts).toBe(1);
  });
  it('keeps a later edit dirty even if its notification has not yet reached the parent', async () => {
    const pending = deferred<{ lockVersion: number; workspaceHash: string }>(); api.saveDraft.mockReturnValue(pending.promise);
    open(); await screen.findByTestId('live-editor'); edit('captured\r\n');
    fireEvent.click(screen.getByRole('button', { name: /保存草稿/ }));
    await waitFor(() => expect(api.saveDraft).toHaveBeenCalledTimes(1));
    edit('newer\r\n', false);
    await act(async () => { pending.resolve({ lockVersion: 1, workspaceHash: 'saved' }); await pending.promise; });
    await waitFor(() => expect(screen.getByRole('button', { name: /保存草稿/ })).toBeEnabled());
    expect(screen.getByTestId('live-editor')).toHaveTextContent('newer'); expect(runtime.mounts).toBe(1);
    const text = await new Promise<string>(resolve => { const reader = new FileReader(); reader.onload = () => resolve(typeof reader.result === 'string' ? reader.result : ''); reader.readAsText(api.stageSnapshot.mock.calls[0]![0] as Blob); });
    expect(JSON.parse(text)).toMatchObject({ body: { dataStream: 'captured\r\n' } });
  });
  it('ignores an old document response after navigation', async () => {
    const pending = deferred<{ lockVersion: number; workspaceHash: string }>(); api.saveDraft.mockReturnValue(pending.promise);
    open(); await screen.findByTestId('live-editor'); edit('first');
    fireEvent.click(screen.getByRole('button', { name: /保存草稿/ })); await waitFor(() => expect(api.saveDraft).toHaveBeenCalled());
    fireEvent.click(screen.getByText('switch-document')); await screen.findByText('version-2');
    await act(async () => { pending.resolve({ lockVersion: 20, workspaceHash: 'old-response' }); await pending.promise; });
    expect(screen.getByText('version-2')).toBeInTheDocument(); expect(screen.queryByText('version-1')).not.toBeInTheDocument();
  });
  it('preserves the edited content and stops automatic writes after a conflict', async () => {
    api.saveDraft.mockRejectedValue(new HttpError('version conflict', 'OPTIMISTIC_LOCK_CONFLICT', 409));
    open(); await screen.findByTestId('live-editor'); edit('keep local');
    fireEvent.click(screen.getByRole('button', { name: /保存草稿/ }));
    await screen.findAllByText('草稿已在其他位置更新');
    expect(screen.getByTestId('live-editor')).toHaveTextContent('keep local'); expect(api.saveDraft).toHaveBeenCalledTimes(1);
  });
});
