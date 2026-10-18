import { App } from 'antd';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import type { DataPublication } from '@/services/data/data-api';
import { DataPublicationPanel } from './DataPublicationPanel';

vi.mock('@/components/auth/usePermission', () => ({ usePermission: () => true }));
const api = vi.hoisted(() => ({
  publication: vi.fn(), publicationAction: vi.fn(), dataReviewers: vi.fn(), formalRecords: vi.fn(), review: vi.fn(),
}));
vi.mock('@/services/data/data-api', () => ({ dataApi: api }));

const draft: DataPublication = { status: 'DRAFT', legacyPublished: false, publishedEnabled: true, versions: [], events: [] };

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
  await waitFor(() => expect(api.publicationAction).toHaveBeenCalledWith('job', 'submit', 'reviewer'));
  expect(api.publicationAction).toHaveBeenCalledTimes(1);
});

it('opens a revision directly from published data', async () => {
  api.publication.mockResolvedValue({ ...draft, status: 'PUBLISHED', publishedSnapshotId: 'snapshot' });
  const onEdit = vi.fn();
  render(<App><DataPublicationPanel jobId="job" onEdit={onEdit} /></App>);
  fireEvent.click(await screen.findByRole('button', { name: '创建修订' }));
  await waitFor(() => expect(onEdit).toHaveBeenCalledOnce());
  expect(api.publicationAction).toHaveBeenCalledWith('job', 'revise');
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
  await waitFor(() => expect(api.publicationAction).toHaveBeenCalledWith('job', 'publish'));
});

it('approves a submission without publishing it', async () => {
  api.publication.mockResolvedValue({ ...draft, status: 'PENDING_REVIEW', confirmedSnapshot: {
    id: 'snapshot', versionNo: 1, contentHash: 'hash', confirmedBy: 'user', confirmedAt: '2026-10-18T09:00:00Z',
  } });
  render(<App><DataPublicationPanel jobId="job" /></App>);
  fireEvent.click(await screen.findByRole('button', { name: '审核通过' }));
  await waitFor(() => expect(api.review).toHaveBeenCalledWith('job', 'snapshot', true));
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
