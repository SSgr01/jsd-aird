import type { ReactNode } from 'react';
import { App } from 'antd';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { Link, MemoryRouter, Route, Routes } from 'react-router-dom';
import type { KnowledgeReview, StructuredDocument } from '@/services/knowledge';
import { HttpError } from '@/services/http/errors';
import { reviewFixture } from './__tests__/review-fixture';
import { KnowledgeReviewPage } from './KnowledgeReviewPage';

const api = vi.hoisted(() => ({ review: vi.fn(), get: vi.fn(), saveReview: vi.fn(), createRevision: vi.fn(), contentUrl: () => '/original.pdf' }));
vi.mock('@/services/knowledge', () => ({ knowledgeApi: api }));
vi.mock('@/components/auth/usePermission', () => ({ usePermission: () => true }));
vi.mock('@/components/workspace/WorkspaceFrame', () => ({
  WorkspaceFrame: ({ children }: { children: ReactNode }) => <div>{children}</div>, WorkspaceMaximizeButton: () => null,
}));
vi.mock('@/components/workspace-review/WorkspaceReviewActions', () => ({
  WorkspaceReviewActions: ({ onStatusChange }: { onStatusChange: (status: string) => void }) => <button onClick={() => onStatusChange('SUBMITTED')}>送审</button>,
}));
vi.mock('@/components/knowledge-document', () => ({
  DocumentComparisonViewer: ({ result }: { result: ReactNode }) => <div>{result}</div>,
  OriginalDocumentViewer: () => null, VirtualDataTable: () => null,
  sourceNodeMap: () => new Map(), findNodeByReviewId: () => undefined, anchorLabel: () => '',
  StructuredDocumentEditor: ({ value, onChange, disabled }: { value: StructuredDocument; onChange: (value: StructuredDocument) => void; disabled: boolean }) =>
    <textarea aria-label="校对正文" disabled={disabled} value={value.content?.[0]?.content?.[0]?.text || ''} onChange={(event) => onChange({ type: 'doc', content: [{ type: 'paragraph', content: [{ type: 'text', text: event.target.value }] }] })} />,
}));
function deferred<T>() { let resolve!: (value: T) => void; const promise = new Promise<T>((done) => { resolve = done; }); return { promise, resolve }; }
function mount() {
  return render(<App><MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }} initialEntries={['/one/v1']}><Link to="/two/v2">切换文档</Link><Routes>
    <Route path="/:documentId/:versionId" element={<KnowledgeReviewPage />} />
  </Routes></MemoryRouter></App>);
}
beforeEach(() => {
  vi.clearAllMocks(); api.review.mockResolvedValue(reviewFixture()); api.get.mockResolvedValue({});
  api.saveReview.mockImplementation((review: KnowledgeReview) => Promise.resolve({ ...review, reviewRevision: { ...review.reviewRevision!, lockVersion: review.reviewRevision!.lockVersion + 1 } }));
});
afterEach(() => vi.useRealTimers());

it('shows pending approval without claiming the read-only review is published', async () => {
  mount(); await screen.findByRole('textbox', { name: '校对正文' });
  fireEvent.click(screen.getByRole('button', { name: '送审' }));
  expect(screen.getByRole('textbox', { name: '校对正文' })).toBeDisabled();
  expect(screen.getByText('待审核')).toBeInTheDocument();
  expect(screen.queryByText('已发布')).not.toBeInTheDocument();
});

it('keeps typing during an in-flight save and submits it with the acknowledged revision', async () => {
  const delayed = deferred<KnowledgeReview>(); api.saveReview.mockReturnValueOnce(delayed.promise);
  mount(); const editor = await screen.findByRole('textbox', { name: '校对正文' });
  vi.useFakeTimers();
  fireEvent.change(editor, { target: { value: '第一处修正' } });
  await act(() => vi.advanceTimersByTimeAsync(2000)); expect(api.saveReview).toHaveBeenCalledTimes(1);
  fireEvent.change(editor, { target: { value: '继续补充工艺条件' } });
  const sent = api.saveReview.mock.calls[0]![0] as KnowledgeReview;
  await act(() => { delayed.resolve({ ...sent, reviewRevision: { ...sent.reviewRevision!, lockVersion: 1 } }); return Promise.resolve(); });
  expect(editor).toHaveValue('继续补充工艺条件'); expect(screen.getByText('有未保存修改')).toBeInTheDocument();
  await act(() => vi.advanceTimersByTimeAsync(2000));
  expect(api.saveReview).toHaveBeenCalledTimes(2);
  const next = api.saveReview.mock.calls[1]![0] as KnowledgeReview;
  expect(next.reviewRevision!.lockVersion).toBe(1);
  expect(next.reviewRevision!.confirmedDocument.content![0]!.content![0]!.text).toBe('继续补充工艺条件');
});

it.each([409, 503])('preserves edits on HTTP %s and does not repeatedly autosave the same failed revision', async (status) => {
  api.saveReview.mockRejectedValueOnce(new HttpError('保存失败', 'WRITE_FAILED', status));
  mount(); const editor = await screen.findByRole('textbox', { name: '校对正文' });
  vi.useFakeTimers(); fireEvent.change(editor, { target: { value: '未丢失的修正' } });
  await act(() => vi.advanceTimersByTimeAsync(2000));
  expect(editor).toHaveValue('未丢失的修正');
  expect(screen.getByText(status === 409 ? '保存冲突' : '尚未保存')).toBeInTheDocument();
  await act(() => vi.advanceTimersByTimeAsync(10000)); expect(api.saveReview).toHaveBeenCalledTimes(1);
  if (status === 503) {
    fireEvent.click(screen.getByRole('button', { name: '重试保存' }));
    await act(async () => {}); expect(api.saveReview).toHaveBeenCalledTimes(2);
    expect(screen.getByText('已保存')).toBeInTheDocument();
  }
});

it('does not show an old document after navigating while it is loading', async () => {
  const delayed = deferred<KnowledgeReview>();
  api.review.mockReturnValueOnce(delayed.promise).mockResolvedValue({ ...reviewFixture(), documentId: 'two', versionId: 'v2', title: '第二份材料资料' });
  mount(); fireEvent.click(screen.getByRole('link', { name: '切换文档' }));
  await screen.findByText(/第二份材料资料/);
  await act(() => { delayed.resolve(reviewFixture()); return Promise.resolve(); });
  expect(screen.getByText(/第二份材料资料/)).toBeInTheDocument();
  expect(screen.queryByText(/光固化涂层/)).not.toBeInTheDocument();
  await waitFor(() => expect(api.review).toHaveBeenCalledTimes(2));
});
