import { App } from 'antd';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import type { DataPublication } from '@/services/data/data-api';
import { DataPublicationPanel } from './DataPublicationPanel';

vi.mock('@/components/auth/usePermission', () => ({ usePermission: () => true }));
const api = vi.hoisted(() => ({
  publication: vi.fn(), publicationAction: vi.fn(), dataReviewers: vi.fn(), formalRecords: vi.fn(), review: vi.fn(),
}));
vi.mock('@/services/data/data-api', () => ({ dataApi: api }));

const draft: DataPublication = { revision: 'a'.repeat(64), status: 'DRAFT', legacyPublished: false, publishedEnabled: true, versions: [], events: [] };
function deferred<T>() { let resolve!: (value: T) => void; const promise = new Promise<T>((done) => { resolve = done; }); return { promise, resolve }; }

beforeEach(() => {
  vi.clearAllMocks();
  api.publication.mockResolvedValue(draft);
  api.formalRecords.mockResolvedValue([]);
  api.dataReviewers.mockResolvedValue([{ id: 'reviewer', name: '陈志远' }]);
  api.publicationAction.mockResolvedValue({ ...draft, status: 'PENDING_REVIEW' });
  api.review.mockResolvedValue({ ...draft, status: 'APPROVED' });
});

it('submits a ready draft from the workbench without a separate confirmation step', async () => {
  render(<App><DataPublicationPanel jobId="job" canConfirm /></App>);
  const submit = await screen.findByRole('button', { name: '提交审核' });
  expect(submit).toBeEnabled();
  expect(screen.queryByRole('button', { name: '确认数据' })).not.toBeInTheDocument();
  fireEvent.click(submit);
  const dialog = await screen.findByRole('dialog', { name: '提交审核' });
  const confirm = within(dialog).getByRole('button', { name: '提交审核' });
  await waitFor(() => expect(confirm).toBeEnabled());
  fireEvent.click(confirm);
  await waitFor(() => expect(api.publicationAction).toHaveBeenCalledWith('job', 'submit', draft.revision, 'reviewer'));
  expect(api.publicationAction).toHaveBeenCalledTimes(1);
});

it('opens a revision directly from published data', async () => {
  api.publication.mockResolvedValue({ ...draft, status: 'PUBLISHED', publishedSnapshotId: 'snapshot' });
  const onEdit = vi.fn();
  render(<App><DataPublicationPanel jobId="job" onEdit={onEdit} /></App>);
  fireEvent.click(await screen.findByRole('button', { name: '创建修订' }));
  await waitFor(() => expect(onEdit).toHaveBeenCalledOnce());
  expect(api.publicationAction).toHaveBeenCalledWith('job', 'revise',draft.revision);
});

it('allows a historical source to submit after opening its first revision', async () => {
  api.publication.mockResolvedValue({ ...draft, legacyPublished: true, events: [{
    id: 'event', action: 'REVISION_CREATED', actorName: '陈志远', createdAt: '2026-10-18T10:00:00Z',
  }] });
  render(<App><DataPublicationPanel jobId="job" canConfirm /></App>);
  expect(await screen.findByRole('button', { name: '提交审核' })).toBeEnabled();
  expect(screen.queryByRole('button', { name: '创建修订' })).not.toBeInTheDocument();
});

it('offers a separate publish action after approval', async () => {
  api.publication.mockResolvedValue({ ...draft, status: 'APPROVED' });
  render(<App><DataPublicationPanel jobId="job" /></App>);
  const publish = await screen.findByRole('button', { name: /发\s*布/ });
  expect(api.publicationAction).not.toHaveBeenCalled();
  expect(screen.queryByRole('button', { name: '审核通过并发布' })).not.toBeInTheDocument();
  fireEvent.click(publish);
  await waitFor(() => expect(api.publicationAction).toHaveBeenCalledWith('job', 'publish',draft.revision));
});

it('approves a submission without publishing it', async () => {
  api.publication.mockResolvedValue({ ...draft, status: 'PENDING_REVIEW', confirmedSnapshot: {
    id: 'snapshot', versionNo: 1, contentHash: 'hash', confirmedBy: 'user', confirmedAt: '2026-10-18T09:00:00Z',
  } });
  render(<App><DataPublicationPanel jobId="job" /></App>);
  fireEvent.click(await screen.findByRole('button', { name: '审核通过' }));
  await waitFor(() => expect(api.review).toHaveBeenCalledWith('job', 'snapshot', true,draft.revision));
  expect(api.publicationAction).not.toHaveBeenCalled();
});

it('shows review results, operator, time and rejection reason in the template-style timeline', async () => {
  api.publication.mockResolvedValue({ ...draft, status: 'RETURNED', events: [
    { id: 'rejected', action: 'RETURNED', actorName: '陈志远', createdAt: '2026-10-18T10:00:00Z', comment: '请核对黏度读数' },
    { id: 'submitted', action: 'SUBMITTED', actorName: '王晓宁', createdAt: '2026-10-18T09:00:00Z' },
  ] });
  render(<App><DataPublicationPanel jobId="job" canConfirm /></App>);
  await screen.findByRole('button', { name: '重新提交审核' });
  fireEvent.click(screen.getByRole('button', { name: '审核记录' }));
  const history = await screen.findByRole('dialog', { name: '数据审核记录' });
  expect(within(history).getByText('审核驳回')).toBeInTheDocument();
  expect(within(history).getByText('请核对黏度读数')).toBeInTheDocument();
  expect(within(history).getByText(/陈志远 ·/)).toBeInTheDocument();
  expect(within(history).getByText('提交审核')).toBeInTheDocument();
  expect(within(history).queryByRole('table')).not.toBeInTheDocument();
});

it('keeps publication after an older approved-state refresh arrives', async () => {
  const delayed = deferred<DataPublication>();
  api.publication.mockResolvedValueOnce({ ...draft, status: 'APPROVED' })
    .mockReturnValueOnce(delayed.promise).mockResolvedValue({ ...draft, status: 'PUBLISHED' });
  const changed = vi.fn();
  const view = render(<App><DataPublicationPanel jobId="job" refreshKey="one" onStateChange={changed} /></App>);
  await screen.findByRole('button', { name: /发\s*布/ });
  view.rerender(<App><DataPublicationPanel jobId="job" refreshKey="two" onStateChange={changed} /></App>);
  await waitFor(() => expect(api.publication).toHaveBeenCalledTimes(2));
  fireEvent.click(screen.getByRole('button', { name: /发\s*布/ }));
  await screen.findByText('已发布', { selector: '.ant-tag' });
  await act(async () => delayed.resolve({ ...draft, status: 'APPROVED' }));
  expect(changed).toHaveBeenLastCalledWith(expect.objectContaining({ status: 'PUBLISHED' }));
  expect(screen.queryByRole('button', { name: /发\s*布/ })).not.toBeInTheDocument();
});

it('does not open another record when a revision action finishes after navigation', async () => {
  const delayed = deferred<unknown>();
  api.publication.mockResolvedValueOnce({ ...draft, status: 'PUBLISHED' }).mockResolvedValue(draft);
  api.publicationAction.mockReturnValueOnce(delayed.promise);
  const edit = vi.fn();
  const view = render(<App><DataPublicationPanel jobId="old" onEdit={edit} canConfirm /></App>);
  fireEvent.click(await screen.findByRole('button', { name: '创建修订' }));
  view.rerender(<App><DataPublicationPanel jobId="new" onEdit={edit} canConfirm /></App>);
  await screen.findByRole('button', { name: '提交审核' });
  await act(async () => delayed.resolve({}));
  expect(edit).not.toHaveBeenCalled();
  expect(api.publication).toHaveBeenCalledTimes(2);
});

it('preserves the last state when the action succeeds but its refresh fails', async () => {
  api.publication.mockResolvedValueOnce({ ...draft, status: 'PUBLISHED' }).mockRejectedValue(new Error('offline'));
  const edit = vi.fn();
  render(<App><DataPublicationPanel jobId="job" onEdit={edit} /></App>);
  fireEvent.click(await screen.findByRole('button', { name: '创建修订' }));
  await screen.findByText('操作已提交，但状态刷新失败，请刷新确认');
  expect(edit).not.toHaveBeenCalled();
  expect(screen.getByText('已发布', { selector: '.ant-tag' })).toBeInTheDocument();
});
